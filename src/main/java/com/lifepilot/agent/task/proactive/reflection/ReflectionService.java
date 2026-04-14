package com.lifepilot.agent.task.proactive.reflection;

import com.lifepilot.agent.task.proactive.profile.UserProfileService;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.notification.NotificationRepository;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 反思服务 — 周频自省，产出经验供下周决策参考。
 *
 * <p>每周日 22:00 触发：收集本周投递+反馈+对话 → LLM 回答三个反思问题 →
 * 写入 proactive_reflection_experiences 表。下周心跳时注入 context。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
public class ReflectionService {

    private static final Logger log = LoggerFactory.getLogger(ReflectionService.class);
    private static final String PROMPT_KEY = "generation/weekly-reflection";
    private static final Duration LLM_TIMEOUT = Duration.ofSeconds(25);

    private final ReflectionRepository reflectionRepository;
    @Nullable private final EpisodicMemory episodicMemory;
    @Nullable private final NotificationRepository notificationRepository;
    @Nullable private final UserProfileService userProfileService;
    @Nullable private final GenerationRouter generationRouter;
    @Nullable private final PromptRegistry promptRegistry;

    public ReflectionService(ReflectionRepository reflectionRepository,
                             @Nullable EpisodicMemory episodicMemory,
                             @Nullable NotificationRepository notificationRepository,
                             @Nullable UserProfileService userProfileService,
                             @Nullable GenerationRouter generationRouter,
                             @Nullable PromptRegistry promptRegistry) {
        this.reflectionRepository = reflectionRepository;
        this.episodicMemory = episodicMemory;
        this.notificationRepository = notificationRepository;
        this.userProfileService = userProfileService;
        this.generationRouter = generationRouter;
        this.promptRegistry = promptRegistry;
    }

    /** 获取最近的反思经验（供心跳时注入 context）。 */
    @Nullable
    public ReflectionExperience getLatestExperience(String userId) {
        return reflectionRepository.findLatestByUserId(userId);
    }

    /** 判断是否该执行反思（周日 22:00-22:59）。 */
    public boolean shouldReflect(Instant now, ZoneId zoneId) {
        LocalDateTime localNow = LocalDateTime.ofInstant(now, zoneId);
        return localNow.getDayOfWeek() == DayOfWeek.SUNDAY && localNow.getHour() == 22;
    }

    /** 执行一次反思。 */
    public void reflect(String userId, ZoneId zoneId) {
        if (generationRouter == null || promptRegistry == null) {
            log.debug("ReflectionService: LLM 不可用，跳过反思");
            return;
        }

        try {
            String deliveryRecords = gatherDeliveryRecords(userId);
            String feedback = gatherUserFeedback();
            String conversations = gatherConversationSummaries();
            String portrait = userProfileService != null
                    ? userProfileService.getProfile(userId).getPortraitOrDefault()
                    : "无画像数据";

            String prompt = promptRegistry.render(PROMPT_KEY, Map.of(
                    "deliveryRecords", deliveryRecords,
                    "userFeedback", feedback,
                    "conversationSummaries", conversations,
                    "userPortrait", portrait));

            LlmResponse response = generationRouter.call("chat", prompt, null, null, null,
                    GenerationCapability.CHAT, LLM_TIMEOUT);

            if (response != null && !response.content().isBlank()) {
                String reflection = response.content().strip();
                String[] parts = splitReflection(reflection);
                String weekStart = LocalDate.now(zoneId)
                        .with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        .format(DateTimeFormatter.ISO_LOCAL_DATE);

                reflectionRepository.save(new ReflectionExperience(
                        UUID.randomUUID().toString(), userId, weekStart,
                        parts[0], parts[1], parts[2], reflection, Instant.now()));

                log.info("周度反思完成: userId={}, weekStart={}", userId, weekStart);
            }
        } catch (Exception e) {
            log.warn("周度反思失败: userId={}, error={}", userId, e.getMessage());
        }
    }

    private String[] splitReflection(String text) {
        String[] paragraphs = text.split("\n\n");
        String[] result = new String[3];
        for (int i = 0; i < 3; i++) {
            result[i] = i < paragraphs.length ? paragraphs[i].strip() : null;
        }
        return result;
    }

    private String gatherDeliveryRecords(String userId) {
        if (notificationRepository == null) return "无投递记录";
        try {
            Instant weekAgo = Instant.now().minus(Duration.ofDays(7));
            long count = notificationRepository.countSentByUserIdAndTypeSince(
                    userId, "proactive_action", weekAgo);
            return "本周共投递 " + count + " 条主动通知";
        } catch (Exception e) {
            return "投递数据获取失败";
        }
    }

    private String gatherUserFeedback() {
        return "（由反馈闭环系统自动收集）";
    }

    private String gatherConversationSummaries() {
        if (episodicMemory == null) return "无对话数据";
        try {
            var recent = episodicMemory.getRecent(Duration.ofDays(7));
            if (recent.isEmpty()) return "本周无对话";
            return recent.stream()
                    .map(c -> c.summary() != null ? c.summary() : c.goal())
                    .filter(s -> s != null && !s.isBlank())
                    .limit(15)
                    .collect(Collectors.joining("\n- ", "- ", ""));
        } catch (Exception e) {
            return "对话数据获取失败";
        }
    }
}
