package com.lifepilot.interaction.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.conversation.transcript.SessionTranscriptRepository;
import com.lifepilot.interaction.web.model.ChatSession;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.interaction.web.repository.SessionDatastoreRepository;
import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * MessageInfo 暴露 reasoningContent / reasoningDurationMs 字段测试（Phase 10 follow-up）。
 *
 * <p>背景：流式结束后 chatStore.loadMessages 会重拉历史，若 MessageInfo 不暴露
 * reasoningContent，前端 mapBackendMessage 会把 reasoning buffer 覆盖成空，导致
 * 已经流出来的推理过程在历史会话里看不到。本测试验证：
 *
 * <ol>
 *   <li>row 携带 reasoningContent / reasoningDurationMs 时 → MessageInfo 透传字段；</li>
 *   <li>row 字段为 null 时 → MessageInfo 字段也为 null（兼容旧消息）。</li>
 * </ol>
 *
 * @author zsg
 * @since 2026-04-27
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatSessionService MessageInfo reasoningContent 暴露")
class ChatSessionService_ReasoningContent暴露测试 {

    @Mock
    ChatSessionRepository sessionRepository;
    @Mock
    SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository;
    @Mock
    SessionDatastoreRepository sessionDatastoreRepository;
    @Mock
    AttachmentRepository attachmentRepository;
    @Mock
    SessionTranscriptRepository transcriptRepository;

    private ChatSessionService service;

    @BeforeEach
    void setUp() {
        service = new ChatSessionService(
                sessionRepository,
                sessionKnowledgeBaseRepository,
                sessionDatastoreRepository,
                attachmentRepository,
                new ObjectMapper(),
                transcriptRepository,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    @Test
    void payload_json含reasoning_content时MessageInfo透传字段() {
        String sessionId = "session-1";
        String entryId = "entry-1";
        Instant now = Instant.parse("2026-04-27T10:00:00Z");

        // row 由 SessionTranscriptRepository.toTranscriptMessageView 反序列化 payload_json
        // 后构造，本测试直接 mock 出已含 reasoningContent / reasoningDurationMs 的 row
        var assistantRow = new SessionTranscriptRepository.TranscriptMessageViewRow(
                entryId, sessionId, "assistant_message", "assistant",
                "最终答案", null, null, null, null, null, null, null,
                true, true, now,
                "我先想了第一步，再想第二步",
                12345L
        );

        when(sessionRepository.findById(sessionId))
                .thenReturn(Optional.of(new ChatSession(
                        sessionId, "标题", null, 0, false, false, null, now, now, null)));
        when(transcriptRepository.findUserConversationRowsBySessionId(sessionId))
                .thenReturn(List.of(assistantRow));
        when(attachmentRepository.findByEntryIds(List.of(entryId)))
                .thenReturn(java.util.Map.of());

        var messages = service.getSessionMessages(sessionId);

        assertThat(messages).hasSize(1);
        var msg = messages.getFirst();
        assertThat(msg.id()).isEqualTo(entryId);
        assertThat(msg.role()).isEqualTo("assistant");
        assertThat(msg.content()).isEqualTo("最终答案");
        assertThat(msg.reasoningContent()).isEqualTo("我先想了第一步，再想第二步");
        assertThat(msg.reasoningDurationMs()).isEqualTo(12345L);
    }

    @Test
    void 缺reasoning_content时MessageInfo字段为null保持向后兼容() {
        String sessionId = "session-2";
        String entryId = "entry-2";
        Instant now = Instant.parse("2026-04-27T10:00:00Z");

        // 旧消息 payload_json 不含 reasoning_content（反序列化后字段 null）
        var oldRow = new SessionTranscriptRepository.TranscriptMessageViewRow(
                entryId, sessionId, "assistant_message", "assistant",
                "旧答案", null, null, null, null, null, null, null,
                true, true, now
        );

        when(sessionRepository.findById(sessionId))
                .thenReturn(Optional.of(new ChatSession(
                        sessionId, "标题", null, 0, false, false, null, now, now, null)));
        when(transcriptRepository.findUserConversationRowsBySessionId(sessionId))
                .thenReturn(List.of(oldRow));
        when(attachmentRepository.findByEntryIds(List.of(entryId)))
                .thenReturn(java.util.Map.of());

        var messages = service.getSessionMessages(sessionId);

        assertThat(messages).hasSize(1);
        var msg = messages.getFirst();
        assertThat(msg.reasoningContent()).isNull();
        assertThat(msg.reasoningDurationMs()).isNull();
    }
}
