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

import org.springframework.beans.factory.annotation.Value;

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
    private static final int MAX_TITLE_LENGTH = 20;
    private static final Set<String> DEFAULT_TITLES = Set.of("新对话", "New Chat");

    private final ChatSessionRepository sessionRepository;
    @Nullable
    private final GenerationRouter generationRouter;
    @Nullable
    private final PromptRegistry promptRegistry;
    @Nullable
    private final SseSessionManager sseSessionManager;
    /** 单次标题生成的外层超时；真正提速靠 UI 把 session-title scene 绑到 flash 级模型。 */
    private final Duration timeout;

    public SessionTitleGenerator(ChatSessionRepository sessionRepository,
                                 @Nullable GenerationRouter generationRouter,
                                 @Nullable PromptRegistry promptRegistry,
                                 @Nullable SseSessionManager sseSessionManager,
                                 @Value("${lifepilot.interaction.session-title.timeout-seconds:30}") int timeoutSeconds) {
        this.sessionRepository = sessionRepository;
        this.generationRouter = generationRouter;
        this.promptRegistry = promptRegistry;
        this.sseSessionManager = sseSessionManager;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    /**
     * 若当前会话标题仍为默认值，按渠道决定标题生成方式：
     * <ul>
     *   <li>Web（channelPlatform = "web" / null）：调用 LLM 生成自然语言标题</li>
     *   <li>Channel（其他）：直接用 "[平台中文] · 首条消息前 15 字" 固定格式，不调 LLM
     *       —— 飞书/钉钉等渠道有自己的会话标题 UI，主服务标题主要是后台管理用，
     *       不值得为此付超时重试的 LLM 调用成本</li>
     * </ul>
     *
     * @param sessionId       会话 ID
     * @param userMessage     用户第一条消息内容
     * @param channelPlatform 渠道平台名（feishu / wecom / dingtalk / web / null）
     */
    public void generateIfNeeded(String sessionId, @Nullable String userMessage,
                                  @Nullable String channelPlatform) {
        if (sessionId == null) {
            return;
        }
        boolean isWeb = channelPlatform == null || channelPlatform.isBlank()
                || "web".equalsIgnoreCase(channelPlatform);

        // Web 路径特有的早期跳过（对齐旧行为）：依赖缺失 / 消息为空时不触发查库和调用
        if (isWeb) {
            if (generationRouter == null || promptRegistry == null) {
                return;
            }
            if (userMessage == null || userMessage.isBlank()) {
                return;
            }
        }

        var session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null || !DEFAULT_TITLES.contains(session.title())) {
            return;
        }

        if (isWeb) {
            generateViaLlm(sessionId, userMessage);
        } else {
            generateFromChannelMeta(sessionId, userMessage, channelPlatform);
        }
    }

    /** 兼容旧 2 参签名（仅用于测试或过渡期调用方）。 */
    public void generateIfNeeded(String sessionId, @Nullable String userMessage) {
        generateIfNeeded(sessionId, userMessage, null);
    }

    /** Web 场景：调 LLM 生成自然语言标题。 */
    private void generateViaLlm(String sessionId, @Nullable String userMessage) {
        if (generationRouter == null || promptRegistry == null) {
            return;
        }
        if (userMessage == null || userMessage.isBlank()) {
            return;
        }
        try {
            String prompt = promptRegistry.render(PROMPT_KEY, Map.of(
                    "userMessage", userMessage.length() > 500
                            ? userMessage.substring(0, 500) + "…"
                            : userMessage
            ));
            var response = generationRouter.call(
                    LLM_SCENE, prompt, null, null, null,
                    GenerationCapability.CHAT, timeout,
                    true);  // skipCache: 每个会话标题必须独立生成，不能因 prompt 模板相似而复用旧标题
            String title = cleanTitle(response.content());
            if (title.isEmpty() || DEFAULT_TITLES.contains(title)) {
                return;
            }
            sessionRepository.updateTitle(sessionId, title);
            log.info("自动生成会话标题（LLM）：sessionId={}, title={}", sessionId, title);
            pushTitleUpdate(sessionId, title);
        } catch (Exception e) {
            log.warn("会话标题生成失败：sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

    /**
     * Channel 场景：直接用 "[平台中文] · 首条消息前 N 字" 组装标题。userMessage 为空时退化为
     * 仅平台名（如 "飞书对话"）。
     */
    private void generateFromChannelMeta(String sessionId, @Nullable String userMessage,
                                          String channelPlatform) {
        String platformCn = platformDisplay(channelPlatform);
        String snippet = pickSnippet(userMessage);
        String title = snippet.isEmpty()
                ? platformCn + "对话"
                : platformCn + " · " + snippet;
        if (title.length() > MAX_TITLE_LENGTH) {
            title = title.substring(0, MAX_TITLE_LENGTH) + "…";
        }
        sessionRepository.updateTitle(sessionId, title);
        log.info("自动生成会话标题（channel）：sessionId={}, platform={}, title={}",
                sessionId, channelPlatform, title);
        pushTitleUpdate(sessionId, title);
    }

    private static String platformDisplay(String platform) {
        return switch (platform.toLowerCase()) {
            case "feishu" -> "飞书";
            case "wecom" -> "企业微信";
            case "dingtalk" -> "钉钉";
            case "qq" -> "QQ";
            default -> platform;
        };
    }

    /**
     * 取 userMessage 首行前 15 字作为标题后缀；
     * 若消息是 connector 塞的文件名/键值串（含 {@code file_v3_} 前缀等），回退为空，只保留平台名。
     */
    private static String pickSnippet(@Nullable String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return "";
        String firstLine = userMessage.strip().split("\\R", 2)[0].strip();
        // 过滤 connector 自动塞的技术串（飞书 file_key / image_key 等），避免标题是 file_v3_xxxxx
        if (firstLine.startsWith("file_v3_") || firstLine.startsWith("img_v3_")) return "";
        int max = 15;
        return firstLine.length() > max ? firstLine.substring(0, max) : firstLine;
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
