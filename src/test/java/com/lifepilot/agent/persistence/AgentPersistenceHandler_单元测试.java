package com.lifepilot.agent.persistence;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ResumePolicy;
import com.lifepilot.conversation.transcript.TranscriptStore;
import com.lifepilot.interaction.web.model.ChatTurnAction;
import com.lifepilot.interaction.web.model.ChatTurnRecord;
import com.lifepilot.interaction.web.model.ChatTurnStatus;
import com.lifepilot.interaction.web.service.BrowserIngressService;
import com.lifepilot.interaction.web.service.ChatTurnService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentPersistenceHandler 单元测试。
 *
 * @author zsg
 * @since 2026-03-25
 */
@ExtendWith(MockitoExtension.class)
class AgentPersistenceHandler_单元测试 {

    @Mock
    private TranscriptStore transcriptStore;

    @Mock
    private ChatTurnService chatTurnService;

    private AgentPersistenceHandler handler;
    private AgentConfigProperties config;

    @BeforeEach
    void setUp() {
        config = new AgentConfigProperties();
        handler = new AgentPersistenceHandler(
                config,
                null,
                transcriptStore,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                chatTurnService,
                null
        );
    }

    @Test
    void 已绑定用户消息时_重试不会重复写入Transcript() {
        ReactAgentState state = buildState("session-1", "turn-1", "请总结今天的新闻");
        ChatTurnRecord record = new ChatTurnRecord(
                "turn-1",
                "session-1",
                ChatTurnAction.RETRY,
                ChatTurnStatus.FAILED,
                "{}",
                "user-entry-1",
                null,
                null,
                null,
                null,
                null,
                null,
                2,
                Instant.now(),
                Instant.now()
        );
        when(chatTurnService.findBySessionIdAndTurnId("session-1", "turn-1"))
                .thenReturn(Optional.of(record));

        String entryId = handler.persistUserMessageReturningId(state);

        assertThat(entryId).isEqualTo("user-entry-1");
        verify(transcriptStore, never()).appendUserMessage(any(), any(), any(), any(), any());
    }

    @Test
    void 首次发送写入用户消息后_会绑定到Turn记录() {
        ReactAgentState state = buildState("session-2", "turn-2", "请安排今天的计划");
        when(chatTurnService.findBySessionIdAndTurnId("session-2", "turn-2"))
                .thenReturn(Optional.empty());
        when(transcriptStore.appendUserMessage("session-2", "turn-2", "请安排今天的计划", state.traceId(), true, null))
                .thenReturn("user-entry-2");

        String entryId = handler.persistUserMessageReturningId(state);

        assertThat(entryId).isEqualTo("user-entry-2");
        verify(chatTurnService).bindUserEntry("session-2", "turn-2", "user-entry-2");
    }

    @Test
    void 持久化用户消息时剥离文档附件提示块() {
        String userText = "请帮我读这份合同";
        String hint = "\n\n" + BrowserIngressService.DOCUMENT_HINT_BEGIN
                + "\n[系统提示] 用户选择了以下文档附件，可调用 file.read(attachmentId=...) 读取内容：\n"
                + "- contract.docx（attachmentId=att-doc）\n"
                + BrowserIngressService.DOCUMENT_HINT_END;
        ReactAgentState state = buildState("session-3", "turn-3", userText + hint);

        when(chatTurnService.findBySessionIdAndTurnId("session-3", "turn-3"))
                .thenReturn(Optional.empty());
        when(transcriptStore.appendUserMessage(
                eq("session-3"), eq("turn-3"), eq(userText),
                eq(state.traceId()), eq(true), isNull()))
                .thenReturn("user-entry-3");

        String entryId = handler.persistUserMessageReturningId(state);

        assertThat(entryId).isEqualTo("user-entry-3");
        // 验证 transcriptStore 收到的是不含 hint 的纯净文本
        verify(transcriptStore).appendUserMessage(
                eq("session-3"), eq("turn-3"), eq(userText),
                eq(state.traceId()), eq(true), isNull());
        // 而 state.goal() 本身不应被改动，模型仍能在原文中看到 hint
        assertThat(state.goal()).contains(BrowserIngressService.DOCUMENT_HINT_BEGIN);
        assertThat(state.goal()).contains("att-doc");
    }

    @Test
    void stripDocumentParseHint_无提示时原样返回() {
        String text = "你好，请帮我处理这个问题";
        assertThat(AgentPersistenceHandler.stripDocumentParseHint(text)).isEqualTo(text);
    }

    @Test
    void stripDocumentParseHint_null与空文本安全返回() {
        assertThat(AgentPersistenceHandler.stripDocumentParseHint(null)).isNull();
        assertThat(AgentPersistenceHandler.stripDocumentParseHint("")).isEqualTo("");
    }

    @Test
    void stripDocumentParseHint_剥离marker包裹块及前置空行() {
        String pure = "总结这篇论文";
        String hint = "\n\n" + BrowserIngressService.DOCUMENT_HINT_BEGIN
                + "\n[系统提示] 文档列表：\n- paper.pdf（attachmentId=att-1）\n"
                + BrowserIngressService.DOCUMENT_HINT_END;
        String result = AgentPersistenceHandler.stripDocumentParseHint(pure + hint);
        assertThat(result).isEqualTo(pure);
        assertThat(result).doesNotContain(BrowserIngressService.DOCUMENT_HINT_BEGIN);
        assertThat(result).doesNotContain(BrowserIngressService.DOCUMENT_HINT_END);
        assertThat(result).doesNotContain("attachmentId");
    }

    private ReactAgentState buildState(String sessionId, String turnId, String goal) {
        AgentRequest request = new AgentRequest(
                goal,
                sessionId,
                "web",
                null,
                turnId,
                ChatTurnAction.SEND,
                null,
                null,
                null,
                0,
                null,
                null,
                null,
                null,
                ResumePolicy.AUTO
        );
        return ReactAgentState.init(request, Budget.fromConfig(config.getBudget()));
    }
}
