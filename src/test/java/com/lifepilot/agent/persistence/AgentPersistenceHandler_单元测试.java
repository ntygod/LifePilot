package com.lifepilot.agent.persistence;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.agent.model.CompletionMode;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import com.lifepilot.agent.model.ResumePolicy;
import com.lifepilot.conversation.transcript.TranscriptStore;
import com.lifepilot.interaction.model.ArtifactRef;
import com.lifepilot.interaction.web.model.ChatTurnAction;
import com.lifepilot.interaction.web.model.ChatTurnRecord;
import com.lifepilot.interaction.web.model.ChatTurnStatus;
import com.lifepilot.interaction.web.service.BrowserIngressService;
import com.lifepilot.interaction.web.service.ChatTurnService;
import com.lifepilot.tool.model.ArtifactKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
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
    void RESTART结构化恢复提示只持久化用户原始问题() {
        String structuredRestart = """
                <restart_original_user_input>
                帮我跑测试
                </restart_original_user_input>

                <restart_instruction>
                重新开始：重新开始。目标：Shell 执行（命令执行）。
                </restart_instruction>

                <task_recovery_checkpoint>
                - 工具: Shell 执行
                - 失败分类: 命令执行/COMMAND
                </task_recovery_checkpoint>
                """;
        ReactAgentState state = buildState("session-restart", "turn-restart", structuredRestart);

        when(chatTurnService.findBySessionIdAndTurnId("session-restart", "turn-restart"))
                .thenReturn(Optional.empty());
        when(transcriptStore.appendUserMessage(
                eq("session-restart"), eq("turn-restart"), eq("帮我跑测试"),
                eq(state.traceId()), eq(true), isNull()))
                .thenReturn("user-entry-restart");

        String entryId = handler.persistUserMessageReturningId(state, ChatTurnAction.RESTART);

        assertThat(entryId).isEqualTo("user-entry-restart");
        verify(transcriptStore).appendUserMessage(
                eq("session-restart"), eq("turn-restart"), eq("帮我跑测试"),
                eq(state.traceId()), eq(true), isNull());
        verify(chatTurnService).bindUserEntry("session-restart", "turn-restart", "user-entry-restart");
    }

    @Test
    void normalizeUserMessageForPersistence_剥离重启内部协议块() {
        String structuredRestart = """
                <restart_original_user_input>
                帮我跑测试
                </restart_original_user_input>

                <restart_instruction>
                重新开始：重新开始。目标：Shell 执行（命令执行）。
                </restart_instruction>

                <task_recovery_checkpoint>
                - 工具: Shell 执行
                - 失败分类: 命令执行/COMMAND
                </task_recovery_checkpoint>
                """;

        assertThat(AgentPersistenceHandler.normalizeUserMessageForPersistence(structuredRestart))
                .isEqualTo("帮我跑测试");
    }

    @Test
    void 持久化助手消息时_上下文产物引用进入工具摘要和恢复摘要() {
        ReactAgentState state = buildState("session-artifact", "turn-artifact", "生成报告并跑测试")
                .toBuilder()
                .finalOutput("报告已生成，但测试失败。")
                .completionMode(CompletionMode.DEGRADED)
                .terminationReason("测试失败")
                .steps(List.of(
                        new ReactStep.ToolCall(
                                "shell.exec",
                                "Shell 执行",
                                "{\"command\":\"npm test\"}",
                                37,
                                "call-shell-1"),
                        new ReactStep.Observation(
                                "shell.exec",
                                "Shell 执行",
                                false,
                                "{\"error\":\"测试失败\"}",
                                0,
                                "call-shell-1")))
                .stepCount(2)
                .build();
        var artifactRefs = List.of(new ArtifactRef(
                "artifact-report",
                "report.md",
                "text/markdown",
                ArtifactKind.FILE,
                128L));
        var toolsSummaryCaptor = ArgumentCaptor.forClass(String.class);
        var taskRecoveryCaptor = ArgumentCaptor.forClass(String.class);

        handler.persistAssistantMessage(state, null, artifactRefs);

        verify(transcriptStore).appendAssistantMessage(
                eq("session-artifact"),
                eq("turn-artifact"),
                eq("报告已生成，但测试失败。"),
                isNull(),
                eq(state.traceId()),
                isNull(),
                isNull(),
                toolsSummaryCaptor.capture(),
                taskRecoveryCaptor.capture(),
                isNull(),
                eq(CompletionMode.DEGRADED),
                isNull(),
                isNull());
        assertThat(toolsSummaryCaptor.getValue())
                .contains("\"artifactId\":\"artifact-report\"")
                .contains("\"fileName\":\"report.md\"");
        assertThat(taskRecoveryCaptor.getValue())
                .contains("\"checkpoint\"")
                .contains("\"artifactId\":\"artifact-report\"")
                .contains("\"downloadUrl\":\"/api/artifacts/artifact-report/download\"");
    }

    @Test
    void 持久化助手消息时_执行约束摘要进入TranscriptPayload() {
        ReactAgentState state = buildState("session-constraints", "turn-constraints", "不要联网，整理本地资料")
                .toBuilder()
                .finalOutput("已按本地资料整理，未联网。")
                .disabledToolIds(List.of("web", "browser"))
                .build();
        var executionConstraintsCaptor = ArgumentCaptor.forClass(String.class);

        handler.persistAssistantMessage(state, null);

        verify(transcriptStore).appendAssistantMessage(
                eq("session-constraints"),
                eq("turn-constraints"),
                eq("已按本地资料整理，未联网。"),
                isNull(),
                eq(state.traceId()),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                executionConstraintsCaptor.capture(),
                eq(CompletionMode.NORMAL),
                isNull(),
                isNull());
        assertThat(executionConstraintsCaptor.getValue())
                .contains("\"disabledTools\"")
                .contains("\"id\":\"web\"")
                .contains("\"label\":\"联网搜索\"")
                .contains("\"id\":\"browser\"")
                .contains("\"label\":\"浏览器操作\"");
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
