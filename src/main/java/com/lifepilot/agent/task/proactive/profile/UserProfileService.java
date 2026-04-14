package com.lifepilot.agent.task.proactive.profile;

import com.lifepilot.agent.task.proactive.intent.IntentMemoryService;
import com.lifepilot.agent.task.proactive.intent.IntentRecord;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 用户画像服务 — 周期性 LLM 巩固，生成自然语言用户理解。
 *
 * <p>触发条件：每 10 轮对话或每周巩固一次。
 * 产出：三维自然语言画像（工作节奏、偏好风格、当前目标），
 * 注入到所有 behavior 的 prompt context 让 LLM "懂"用户。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
public class UserProfileService {

    private static final Logger log = LoggerFactory.getLogger(UserProfileService.class);
    private static final String PROMPT_KEY = "generation/user-profile-consolidation";
    private static final Duration LLM_TIMEOUT = Duration.ofSeconds(20);
    private static final int CONSOLIDATION_INTERVAL_CONVERSATIONS = 10;
    private static final Duration CONSOLIDATION_INTERVAL_TIME = Duration.ofDays(7);

    private final UserProfileRepository profileRepository;
    @Nullable private final EpisodicMemory episodicMemory;
    @Nullable private final IntentMemoryService intentMemoryService;
    @Nullable private final GenerationRouter generationRouter;
    @Nullable private final PromptRegistry promptRegistry;

    public UserProfileService(UserProfileRepository profileRepository,
                              @Nullable EpisodicMemory episodicMemory,
                              @Nullable IntentMemoryService intentMemoryService,
                              @Nullable GenerationRouter generationRouter,
                              @Nullable PromptRegistry promptRegistry) {
        this.profileRepository = profileRepository;
        this.episodicMemory = episodicMemory;
        this.intentMemoryService = intentMemoryService;
        this.generationRouter = generationRouter;
        this.promptRegistry = promptRegistry;
    }

    /** 获取用户画像（不存在则返回空画像）。 */
    public UserProfile getProfile(String userId) {
        var profile = profileRepository.findByUserId(userId);
        return profile != null ? profile : UserProfile.empty(userId);
    }

    /** 记录一轮对话完成，检查是否需要巩固画像。 */
    public void onConversationCompleted(String userId) {
        var profile = getProfile(userId);
        if (profile.isEmpty()) {
            // 首次：创建空画像
            profileRepository.upsert(UserProfile.empty(userId));
            profile = getProfile(userId);
        }
        profileRepository.incrementConversationCount(userId);

        // 检查是否需要巩固
        boolean needConsolidate = false;
        if (profile.conversationCount() + 1 >= CONSOLIDATION_INTERVAL_CONVERSATIONS) {
            needConsolidate = true;
        }
        if (profile.lastConsolidatedAt() == null
                || Duration.between(profile.lastConsolidatedAt(), Instant.now()).compareTo(CONSOLIDATION_INTERVAL_TIME) > 0) {
            needConsolidate = true;
        }

        if (needConsolidate) {
            consolidate(userId);
        }
    }

    /** 执行画像巩固 — 调用 LLM 生成/更新自然语言画像。 */
    public void consolidate(String userId) {
        if (generationRouter == null || promptRegistry == null) {
            log.debug("UserProfileService: LLM 不可用，跳过画像巩固");
            return;
        }

        try {
            var currentProfile = getProfile(userId);
            String conversations = gatherRecentConversations();
            String feedback = gatherRecentFeedback(userId);
            String intents = gatherActiveIntents(userId);

            String prompt = promptRegistry.render(PROMPT_KEY, Map.of(
                    "currentPortrait", currentProfile.getPortraitOrDefault(),
                    "recentConversations", conversations,
                    "recentFeedback", feedback,
                    "activeIntents", intents));

            LlmResponse response = generationRouter.call("chat", prompt, null, null, null,
                    GenerationCapability.CHAT, LLM_TIMEOUT);

            if (response != null && !response.content().isBlank()) {
                String portrait = response.content().strip();
                // 解析三维画像（按段落拆分）
                String[] parts = splitPortrait(portrait);
                Instant now = Instant.now();
                profileRepository.upsert(new UserProfile(
                        userId, parts[0], parts[1], parts[2], portrait,
                        0, now, now));
                log.info("用户画像巩固完成: userId={}, length={}", userId, portrait.length());
            }
        } catch (Exception e) {
            log.warn("用户画像巩固失败: userId={}, error={}", userId, e.getMessage());
        }
    }

    /** 将画像文本按段落拆分为三维。 */
    private String[] splitPortrait(String portrait) {
        String[] paragraphs = portrait.split("\n\n");
        String[] result = new String[3];
        for (int i = 0; i < 3; i++) {
            result[i] = i < paragraphs.length ? paragraphs[i].strip() : null;
        }
        return result;
    }

    private String gatherRecentConversations() {
        if (episodicMemory == null) return "无近期对话数据";
        try {
            var recent = episodicMemory.getRecent(Duration.ofDays(7));
            if (recent.isEmpty()) return "近7天无对话";
            return recent.stream()
                    .map(c -> c.summary() != null ? c.summary() : c.goal())
                    .filter(s -> s != null && !s.isBlank())
                    .limit(10)
                    .collect(Collectors.joining("\n- ", "- ", ""));
        } catch (Exception e) {
            return "对话数据获取失败";
        }
    }

    private String gatherRecentFeedback(String userId) {
        // 从偏好数据推断反馈趋势
        return "（由偏好学习系统自动收集）";
    }

    private String gatherActiveIntents(String userId) {
        if (intentMemoryService == null) return "无活跃意图";
        try {
            var intents = intentMemoryService.getActiveIntents(userId);
            if (intents.isEmpty()) return "无活跃意图";
            return intents.stream()
                    .map(i -> i.intentType() + ": " + i.goal())
                    .limit(5)
                    .collect(Collectors.joining("\n- ", "- ", ""));
        } catch (Exception e) {
            return "意图数据获取失败";
        }
    }
}
