package com.lifepilot.interaction.web.service;

import com.lifepilot.conversation.transcript.SessionTranscriptRepository;
import com.lifepilot.conversation.transcript.SessionTranscriptRepository.TranscriptMessageViewRow;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 对话摘要自动生成服务。
 *
 * <p>在对话结束后，基于最近的消息内容调用 LLM 生成简短摘要，
 * 写入 {@code session_store.summary} 字段，供日报、情景记忆检索等使用。</p>
 *
 * @author zsg
 * @since 2026-04-15
 */
@Service
public class ConversationSummaryGenerator {

    private static final Logger log = LoggerFactory.getLogger(ConversationSummaryGenerator.class);
    private static final String PROMPT_KEY = "generation/conversation-summary";
    private static final String LLM_SCENE = "conversation-summary";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final int MIN_MESSAGE_COUNT = 3;
    private static final int MAX_MESSAGES_FOR_PROMPT = 10;
    private static final int MAX_SUMMARY_LENGTH = 80;
    private static final Set<String> CONVERSATION_ROLES = Set.of("user", "assistant");

    private final ChatSessionRepository sessionRepository;
    private final SessionTranscriptRepository transcriptRepository;
    @Nullable
    private final GenerationRouter generationRouter;
    @Nullable
    private final PromptRegistry promptRegistry;

    public ConversationSummaryGenerator(ChatSessionRepository sessionRepository,
                                        SessionTranscriptRepository transcriptRepository,
                                        @Nullable GenerationRouter generationRouter,
                                        @Nullable PromptRegistry promptRegistry) {
        this.sessionRepository = sessionRepository;
        this.transcriptRepository = transcriptRepository;
        this.generationRouter = generationRouter;
        this.promptRegistry = promptRegistry;
    }

    /**
     * 为指定会话生成 LLM 摘要并写入 session_store.summary。
     *
     * <p>前置条件：消息数 >= 3（单轮问答不需要摘要）。
     * 已有摘要时跳过，避免重复生成。</p>
     *
     * @param sessionId 会话 ID
     */
    public void generateIfNeeded(String sessionId) {
        if (generationRouter == null || promptRegistry == null) {
            return;
        }
        // 非 Web 渠道跳过
        if (sessionId == null || sessionId.contains(":")) {
            return;
        }

        var session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            return;
        }
        // 消息数不足，跳过
        if (session.messageCount() < MIN_MESSAGE_COUNT) {
            log.debug("对话摘要生成跳过（消息不足）：sessionId={}, messageCount={}", sessionId, session.messageCount());
            return;
        }
        // 已有摘要，跳过
        if (session.summary() != null && !session.summary().isBlank()) {
            log.debug("对话摘要生成跳过（已有摘要）：sessionId={}", sessionId);
            return;
        }

        try {
            String transcript = buildTranscript(sessionId);
            if (transcript.isBlank()) {
                log.debug("对话摘要生成跳过（无有效消息）：sessionId={}", sessionId);
                return;
            }

            String prompt = promptRegistry.render(PROMPT_KEY, Map.of("transcript", transcript));

            var response = generationRouter.call(
                    LLM_SCENE,
                    prompt,
                    null,
                    null,
                    null,
                    GenerationCapability.CHAT,
                    TIMEOUT
            );

            String summary = cleanSummary(response.content());
            if (summary.isEmpty()) {
                return;
            }

            sessionRepository.updateSummary(sessionId, summary);
            log.info("自动生成对话摘要：sessionId={}, summary={}", sessionId, summary);
        } catch (Exception e) {
            log.warn("对话摘要生成失败：sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

    /**
     * 从 transcript 读取最近消息，拼接为对话文本。
     * 只取 user/assistant 角色的消息，最多取最后 {@value MAX_MESSAGES_FOR_PROMPT} 条。
     */
    private String buildTranscript(String sessionId) {
        List<TranscriptMessageViewRow> allMessages = transcriptRepository.findUserConversationRowsBySessionId(sessionId);

        // 只保留 user/assistant 角色的对话消息
        List<TranscriptMessageViewRow> conversationMessages = allMessages.stream()
                .filter(msg -> msg.role() != null && CONVERSATION_ROLES.contains(msg.role()))
                .toList();

        // 取最后 N 条
        int size = conversationMessages.size();
        List<TranscriptMessageViewRow> recent = size <= MAX_MESSAGES_FOR_PROMPT
                ? conversationMessages
                : conversationMessages.subList(size - MAX_MESSAGES_FOR_PROMPT, size);

        return recent.stream()
                .map(msg -> {
                    String roleLabel = "user".equals(msg.role()) ? "问" : "答";
                    String content = msg.content().length() > 300
                            ? msg.content().substring(0, 300) + "…"
                            : msg.content();
                    return roleLabel + ": " + content;
                })
                .collect(Collectors.joining("\n"));
    }

    /** 清理 LLM 输出：去引号、标点、空白，截断到最大长度 */
    private String cleanSummary(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.strip()
                .replaceAll("^[\"'《「]+|[\"'》」]+$", "")   // 去首尾引号
                .replaceAll("[。！？.!?]+$", "")              // 去末尾标点
                .strip();
        if (cleaned.length() > MAX_SUMMARY_LENGTH) {
            cleaned = cleaned.substring(0, MAX_SUMMARY_LENGTH) + "…";
        }
        return cleaned;
    }
}
