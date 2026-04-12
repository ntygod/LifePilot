package com.lifepilot.interaction.web.service;

import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.interaction.web.sse.SseEventType;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * 会话标题自动生成服务。
 *
 * <p>在第一轮对话完成后，异步调用 LLM 生成简短中文标题，
 * 更新数据库并通过 SSE 推送到前端。</p>
 *
 * @author zsg
 * @since 2026-04-12
 */
@Service
public class SessionTitleGenerator {

    private static final Logger log = LoggerFactory.getLogger(SessionTitleGenerator.class);
    private static final String PROMPT_KEY = "generation/session-title";
    private static final String LLM_SCENE = "session-title";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_TITLE_LENGTH = 20;
    private static final Set<String> DEFAULT_TITLES = Set.of("新对话", "New Chat");

    private final ChatSessionRepository sessionRepository;
    @Nullable
    private final GenerationRouter generationRouter;
    @Nullable
    private final PromptRegistry promptRegistry;
    @Nullable
    private final SseSessionManager sseSessionManager;

    public SessionTitleGenerator(ChatSessionRepository sessionRepository,
                                 @Nullable GenerationRouter generationRouter,
                                 @Nullable PromptRegistry promptRegistry,
                                 @Nullable SseSessionManager sseSessionManager) {
        this.sessionRepository = sessionRepository;
        this.generationRouter = generationRouter;
        this.promptRegistry = promptRegistry;
        this.sseSessionManager = sseSessionManager;
    }

    /**
     * 若当前会话标题仍为默认值，则调用 LLM 生成新标题并推送。
     *
     * @param sessionId   会话 ID
     * @param userMessage 用户第一条消息内容
     */
    public void generateIfNeeded(String sessionId, @Nullable String userMessage) {
        if (generationRouter == null || promptRegistry == null) {
            return;
        }
        // 非 Web 渠道跳过
        if (sessionId == null || sessionId.contains(":")) {
            return;
        }
        if (userMessage == null || userMessage.isBlank()) {
            return;
        }

        // 检查当前标题是否为默认值
        var session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null || !DEFAULT_TITLES.contains(session.title())) {
            return;
        }

        try {
            String prompt = promptRegistry.render(PROMPT_KEY, Map.of(
                    "userMessage", userMessage.length() > 500
                            ? userMessage.substring(0, 500) + "…"
                            : userMessage
            ));

            var response = generationRouter.call(
                    LLM_SCENE,
                    prompt,
                    null,
                    null,
                    null,
                    GenerationCapability.CHAT,
                    TIMEOUT
            );

            String title = cleanTitle(response.content());
            if (title.isEmpty() || DEFAULT_TITLES.contains(title)) {
                return;
            }

            sessionRepository.updateTitle(sessionId, title);
            log.info("自动生成会话标题：sessionId={}, title={}", sessionId, title);

            pushTitleUpdate(sessionId, title);
        } catch (Exception e) {
            log.warn("会话标题生成失败：sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

    /** 清理 LLM 输出：去引号、标点、空白，截断到最大长度 */
    private String cleanTitle(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.strip()
                .replaceAll("^[\"'《「]+|[\"'》」]+$", "")   // 去首尾引号
                .replaceAll("[。！？，；：、.!?,;:]+$", "")    // 去末尾标点
                .strip();
        if (cleaned.length() > MAX_TITLE_LENGTH) {
            cleaned = cleaned.substring(0, MAX_TITLE_LENGTH) + "…";
        }
        return cleaned;
    }

    /** 通过 SSE 广播标题更新事件 */
    private void pushTitleUpdate(String sessionId, String title) {
        if (sseSessionManager == null) {
            return;
        }
        try {
            sseSessionManager.broadcastByPrefix("notification-",
                    SseEventType.TITLE_GENERATED,
                    Map.of("sessionId", sessionId, "title", title));
        } catch (Exception e) {
            log.debug("标题更新 SSE 广播失败：sessionId={}, error={}", sessionId, e.getMessage());
        }
    }
}
