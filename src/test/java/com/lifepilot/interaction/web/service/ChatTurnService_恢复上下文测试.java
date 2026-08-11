package com.lifepilot.interaction.web.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.conversation.transcript.SessionTranscriptRepository;
import com.lifepilot.interaction.web.model.ChatRequest;
import com.lifepilot.interaction.web.model.ChatTurnAction;
import com.lifepilot.interaction.web.model.ChatTurnRecord;
import com.lifepilot.interaction.web.model.ChatTurnStatus;
import com.lifepilot.interaction.web.model.TurnRecoveryActionRequest;
import com.lifepilot.interaction.web.repository.ChatTurnRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatTurnService 恢复上下文测试。
 *
 * @author zsg
 * @since 2026-07-04
 */
class ChatTurnService_恢复上下文测试 {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void RESUME应把上一轮恢复断点写入turn请求快照() throws Exception {
        var turnRepository = mock(ChatTurnRepository.class);
        var transcriptRepository = mock(SessionTranscriptRepository.class);
        var service = new ChatTurnService(
                turnRepository,
                transcriptRepository,
                objectMapper,
                mock(ApplicationEventPublisher.class),
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        Instant now = Instant.parse("2026-07-04T06:00:00Z");
        String requestPayloadJson = """
                {
                  "content":"运行前端测试并修复失败",
                  "attachmentIds":null,
                  "preferredProvider":null,
                  "singleTurnOverride":null
                }
                """;
        when(turnRepository.findBySessionIdAndTurnId("session-1", "turn-1"))
                .thenReturn(Optional.of(new ChatTurnRecord(
                        "turn-1",
                        "session-1",
                        ChatTurnAction.SEND,
                        ChatTurnStatus.DEGRADED,
                        requestPayloadJson,
                        "user-entry-1",
                        "assistant-entry-1",
                        "trace-failed-1",
                        null,
                        "DEGRADED",
                        null,
                        null,
                        1,
                        now,
                        now)));
        String taskRecoveryJson = objectMapper.writeValueAsString(Map.of(
                "title", "Shell 执行 没有完成",
                "detail", "命令或代码没有完成，可以修正错误后继续执行。",
                "checkpoint", Map.of(
                        "kind", "TOOL_FAILURE",
                        "toolId", "shell.exec",
                        "toolName", "Shell 执行",
                        "failureCategory", "COMMAND",
                        "workingDirectory", "D:\\WorkSpace\\Project\\News",
                        "inputSummary", "执行 `npm test`",
                        "outputSummary", "断言失败",
                        "outputDetail", "AssertionError: expected true to be false"),
                "nextActions", List.of("查看命令输出并修正报错原因", "从失败命令后继续执行验证")
        ));
        String assistantPayloadJson = objectMapper.writeValueAsString(Map.of(
                "content", "这轮没有完整完成",
                "taskRecoveryJson", taskRecoveryJson
        ));
        when(transcriptRepository.findById("assistant-entry-1"))
                .thenReturn(Optional.of(new SessionTranscriptRepository.SessionTranscriptEntryRow(
                        "assistant-entry-1",
                        "session-1",
                        "main",
                        "MESSAGE",
                        "assistant",
                        "turn-1",
                        "trace-failed-1",
                        true,
                        true,
                        assistantPayloadJson,
                        100,
                        now)));

        var resolved = service.prepare("session-1", new ChatRequest(
                "turn-1",
                ChatTurnAction.RESUME,
                "继续，先修 npm test",
                "session-1",
                null,
                null));

        assertThat(resolved.content())
                .contains("运行前端测试并修复失败")
                .contains("<resume_user_input>")
                .contains("继续，先修 npm test");
        assertThat(resolved.turnRecoveryContext())
                .containsEntry("action", "RESUME")
                .containsEntry("sourceTraceId", "trace-failed-1")
                .containsEntry("title", "Shell 执行 没有完成");

        var payloadCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(turnRepository).markAttemptStarted(
                eq("session-1"),
                eq("turn-1"),
                eq(ChatTurnAction.RESUME),
                payloadCaptor.capture(),
                org.mockito.ArgumentMatchers.any());
        Map<String, Object> payload = objectMapper.readValue(payloadCaptor.getValue(), MAP_TYPE);
        assertThat(payload).containsEntry("content", "运行前端测试并修复失败");
        Map<String, Object> recovery = objectMapper.convertValue(
                payload.get("lastRecoveryContext"), MAP_TYPE);
        assertThat(recovery)
                .containsEntry("action", "RESUME")
                .containsEntry("resumeInput", "继续，先修 npm test")
                .containsEntry("sourceTraceId", "trace-failed-1")
                .containsEntry("assistantEntryId", "assistant-entry-1")
                .containsEntry("title", "Shell 执行 没有完成");
        Map<String, Object> checkpoint = objectMapper.convertValue(recovery.get("checkpoint"), MAP_TYPE);
        assertThat(checkpoint)
                .containsEntry("toolId", "shell.exec")
                .containsEntry("failureCategory", "COMMAND")
                .containsEntry("inputSummary", "执行 `npm test`")
                .containsEntry("outputSummary", "断言失败");
        assertThat(String.valueOf(recovery.get("nextActions")))
                .contains("从失败命令后继续执行验证");
        verify(transcriptRepository, never()).updateVisibility("assistant-entry-1", false, false);
    }

    @Test
    void RESUME结构化恢复动作应生成恢复指令并写入turn请求快照() throws Exception {
        var turnRepository = mock(ChatTurnRepository.class);
        var transcriptRepository = mock(SessionTranscriptRepository.class);
        var service = new ChatTurnService(
                turnRepository,
                transcriptRepository,
                objectMapper,
                mock(ApplicationEventPublisher.class),
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        Instant now = Instant.parse("2026-07-04T06:00:00Z");
        String requestPayloadJson = """
                {
                  "content":"帮我跑测试",
                  "attachmentIds":null,
                  "preferredProvider":null,
                  "singleTurnOverride":null
                }
                """;
        when(turnRepository.findBySessionIdAndTurnId("session-1", "turn-1"))
                .thenReturn(Optional.of(new ChatTurnRecord(
                        "turn-1",
                        "session-1",
                        ChatTurnAction.SEND,
                        ChatTurnStatus.DEGRADED,
                        requestPayloadJson,
                        "user-entry-1",
                        "assistant-entry-1",
                        "trace-failed-1",
                        null,
                        "DEGRADED",
                        null,
                        null,
                        1,
                        now,
                        now)));
        when(transcriptRepository.findById("assistant-entry-1")).thenReturn(Optional.empty());

        var recoveryAction = new TurnRecoveryActionRequest(
                "resume",
                "修正后继续",
                "保留已完成步骤，修正命令或代码错误后继续验证。",
                "resume",
                "shell.exec",
                "Shell 执行",
                "TOOL",
                "执行命令",
                "COMMAND",
                null,
                null,
                "执行 `npm test`",
                "{\"command\":\"npm test\",\"workingDirectory\":\"D:\\\\WorkSpace\\\\Project\\\\News\"}",
                "测试失败",
                "AssertionError: expected true to be false",
                "D:\\WorkSpace\\Project\\News",
                "D:\\WorkSpace\\Project\\News\\target\\report.md",
                "先修复失败断言再继续验证",
                List.of("查看命令输出并修正报错原因", "从失败命令后继续执行验证"),
                "call-shell-1",
                true,
                List.of(Map.of(
                        "artifactId", "artifact-1",
                        "fileName", "report.md",
                        "mimeType", "text/markdown",
                        "kind", "FILE",
                        "size", 128L,
                        "downloadUrl", "/api/artifacts/artifact-1/download")),
                null);
        var resolved = service.prepare("session-1", new ChatRequest(
                "turn-1",
                ChatTurnAction.RESUME,
                "",
                "session-1",
                null,
                null,
                null,
                null,
                recoveryAction));

        assertThat(resolved.content())
                .contains("帮我跑测试")
                .contains("<resume_user_input>")
                .contains("从上一轮断点继续。")
                .contains("恢复动作：修正后继续")
                .contains("动作说明：保留已完成步骤，修正命令或代码错误后继续验证。")
                .contains("恢复模式：resume")
                .contains("继续策略：从已开始但未返回结果的步骤继续，保留已完成步骤，不要重复成功部分。")
                .contains("调用 ID：call-shell-1")
                .contains("目标：Shell 执行（命令执行/COMMAND）")
                .contains("操作：执行命令")
                .contains("中断状态：已开始但没有返回执行结果")
                .contains("上次输入：执行 `npm test`")
                .contains("上次输入详情：{\"command\":\"npm test\",\"workingDirectory\":\"D:\\\\WorkSpace\\\\Project\\\\News\"}")
                .contains("上次输出：测试失败")
                .contains("相关文件：D:\\WorkSpace\\Project\\News\\target\\report.md")
                .contains("产物复用：已生成文件可直接复用：D:\\WorkSpace\\Project\\News\\target\\report.md。继续时先检查并引用它，不要无故重复生成或覆盖。")
                .contains("产物引用：report.md（FILE/text/markdown） id=artifact-1 url=/api/artifacts/artifact-1/download。继续时优先复用这些产物，不要无故重复生成。")
                .contains("恢复计划：查看命令输出并修正报错原因、从失败命令后继续执行验证")
                .contains("请从这个失败点继续，不要重复已经完成的步骤。");
        assertThat(new ArrayList<>(resolved.turnRecoveryContext().keySet()))
                .containsExactly(
                        "action",
                        "resumeInput",
                        "sourceTraceId",
                        "assistantEntryId",
                        "title",
                        "detail",
                        "resumeStrategy",
                        "checkpoint",
                        "nextActions",
                        "capturedAt");
        @SuppressWarnings("unchecked")
        Map<String, Object> resolvedCheckpoint = (Map<String, Object>) resolved.turnRecoveryContext().get("checkpoint");
        assertThat(new ArrayList<>(resolvedCheckpoint.keySet()))
                .containsExactly(
                        "kind",
                        "recoveryActionId",
                        "recoveryActionLabel",
                        "recoveryActionDescription",
                        "recoveryActionMode",
                        "callId",
                        "toolId",
                        "toolName",
                        "executionKind",
                        "action",
                        "failureCategory",
                        "interrupted",
                        "inputSummary",
                        "inputDetail",
                        "outputSummary",
                        "outputDetail",
                        "workingDirectory",
                        "generatedFilePath",
                        "artifactRefs");

        var payloadCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(turnRepository).markAttemptStarted(
                eq("session-1"),
                eq("turn-1"),
                eq(ChatTurnAction.RESUME),
                payloadCaptor.capture(),
                org.mockito.ArgumentMatchers.any());
        Map<String, Object> payload = objectMapper.readValue(payloadCaptor.getValue(), MAP_TYPE);
        Map<String, Object> recovery = objectMapper.convertValue(
                payload.get("lastRecoveryContext"), MAP_TYPE);
        assertThat(recovery)
                .containsEntry("action", "RESUME")
                .containsEntry("sourceTraceId", "trace-failed-1")
                .containsEntry("assistantEntryId", "assistant-entry-1")
                .containsEntry("title", "修正后继续")
                .containsEntry("detail", "先修复失败断言再继续验证")
                .containsEntry("resumeStrategy", "从已开始但未返回结果的步骤继续，保留已完成步骤，不要重复成功部分。");
        assertThat(String.valueOf(recovery.get("resumeInput")))
                .contains("恢复动作：修正后继续")
                .contains("动作说明：保留已完成步骤，修正命令或代码错误后继续验证。")
                .contains("恢复模式：resume")
                .contains("目标：Shell 执行（命令执行/COMMAND）")
                .contains("上次输入详情：{\"command\":\"npm test\",\"workingDirectory\":\"D:\\\\WorkSpace\\\\Project\\\\News\"}")
                .contains("产物复用：已生成文件可直接复用：D:\\WorkSpace\\Project\\News\\target\\report.md。继续时先检查并引用它，不要无故重复生成或覆盖。")
                .contains("产物引用：report.md（FILE/text/markdown） id=artifact-1 url=/api/artifacts/artifact-1/download。继续时优先复用这些产物，不要无故重复生成。")
                .contains("恢复计划：查看命令输出并修正报错原因、从失败命令后继续执行验证");
        assertThat(String.valueOf(recovery.get("nextActions")))
                .contains("从失败命令后继续执行验证");
        Map<String, Object> checkpoint = objectMapper.convertValue(recovery.get("checkpoint"), MAP_TYPE);
        assertThat(checkpoint)
                .containsEntry("callId", "call-shell-1")
                .containsEntry("recoveryActionMode", "resume")
                .containsEntry("interrupted", true)
                .containsEntry("toolId", "shell.exec")
                .containsEntry("toolName", "Shell 执行")
                .containsEntry("executionKind", "TOOL")
                .containsEntry("action", "执行命令")
                .containsEntry("failureCategory", "COMMAND")
                .containsEntry("inputSummary", "执行 `npm test`")
                .containsEntry("inputDetail", "{\"command\":\"npm test\",\"workingDirectory\":\"D:\\\\WorkSpace\\\\Project\\\\News\"}")
                .containsEntry("outputSummary", "测试失败")
                .containsEntry("generatedFilePath", "D:\\WorkSpace\\Project\\News\\target\\report.md");
        assertThat(checkpoint).containsKey("artifactRefs");
        verify(transcriptRepository, never()).updateVisibility("assistant-entry-1", false, false);
    }

    @Test
    void RESUME能力缺失恢复动作应提示先修复连接再继续() throws Exception {
        var turnRepository = mock(ChatTurnRepository.class);
        var transcriptRepository = mock(SessionTranscriptRepository.class);
        var service = new ChatTurnService(
                turnRepository,
                transcriptRepository,
                objectMapper,
                mock(ApplicationEventPublisher.class),
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        Instant now = Instant.parse("2026-07-04T06:00:00Z");
        String requestPayloadJson = """
                {
                  "content":"帮我调研今天的最新资讯",
                  "attachmentIds":null,
                  "preferredProvider":null,
                  "singleTurnOverride":null
                }
                """;
        when(turnRepository.findBySessionIdAndTurnId("session-1", "turn-1"))
                .thenReturn(Optional.of(new ChatTurnRecord(
                        "turn-1",
                        "session-1",
                        ChatTurnAction.SEND,
                        ChatTurnStatus.DEGRADED,
                        requestPayloadJson,
                        "user-entry-1",
                        "assistant-entry-1",
                        "trace-capability-1",
                        null,
                        "DEGRADED",
                        null,
                        null,
                        1,
                        now,
                        now)));
        when(transcriptRepository.findById("assistant-entry-1")).thenReturn(Optional.empty());

        var recoveryAction = new TurnRecoveryActionRequest(
                "resume",
                "修复能力后继续",
                "保留当前进度，修复缺失工具或技能连接后从失败步骤接上。",
                "resume",
                "web.search",
                "网页搜索",
                "TOOL",
                "搜索资料",
                "CAPABILITY",
                null,
                null,
                "搜索今天的最新资讯",
                "{\"query\":\"今天的最新资讯\"}",
                "未找到可用工具",
                "工具 web.search 未注册",
                null,
                null,
                "依赖的工具或技能当前不可用，可以在能力中心修复连接或调整 Skill 元数据后继续。",
                List.of("修复 web.search 工具能力", "从搜索步骤继续整理资料"),
                "call-web-1",
                false,
                null,
                null);
        var resolved = service.prepare("session-1", new ChatRequest(
                "turn-1",
                ChatTurnAction.RESUME,
                "",
                "session-1",
                null,
                null,
                null,
                null,
                recoveryAction));

        assertThat(resolved.content())
                .contains("帮我调研今天的最新资讯")
                .contains("恢复动作：修复能力后继续")
                .contains("继续策略：先修复缺失工具或技能连接，再从失败断点继续，保留已完成步骤。")
                .contains("目标：网页搜索（能力缺口/CAPABILITY）")
                .contains("恢复计划：修复 web.search 工具能力、从搜索步骤继续整理资料");
        Map<String, Object> checkpoint = objectMapper.convertValue(
                resolved.turnRecoveryContext().get("checkpoint"), MAP_TYPE);
        assertThat(checkpoint)
                .containsEntry("toolId", "web.search")
                .containsEntry("toolName", "网页搜索")
                .containsEntry("failureCategory", "CAPABILITY");

        var payloadCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(turnRepository).markAttemptStarted(
                eq("session-1"),
                eq("turn-1"),
                eq(ChatTurnAction.RESUME),
                payloadCaptor.capture(),
                org.mockito.ArgumentMatchers.any());
        Map<String, Object> payload = objectMapper.readValue(payloadCaptor.getValue(), MAP_TYPE);
        Map<String, Object> recovery = objectMapper.convertValue(
                payload.get("lastRecoveryContext"), MAP_TYPE);
        assertThat(recovery)
                .containsEntry("action", "RESUME")
                .containsEntry("title", "修复能力后继续")
                .containsEntry("resumeStrategy", "先修复缺失工具或技能连接，再从失败断点继续，保留已完成步骤。");
        assertThat(String.valueOf(recovery.get("resumeInput")))
                .contains("目标：网页搜索（能力缺口/CAPABILITY）")
                .contains("恢复提示：依赖的工具或技能当前不可用，可以在能力中心修复连接或调整 Skill 元数据后继续。");
        Map<String, Object> persistedCheckpoint = objectMapper.convertValue(recovery.get("checkpoint"), MAP_TYPE);
        assertThat(persistedCheckpoint).containsEntry("failureCategory", "CAPABILITY");
    }

    @Test
    void RESUME只有恢复计划时不应伪造工具断点() throws Exception {
        var turnRepository = mock(ChatTurnRepository.class);
        var transcriptRepository = mock(SessionTranscriptRepository.class);
        var service = new ChatTurnService(
                turnRepository,
                transcriptRepository,
                objectMapper,
                mock(ApplicationEventPublisher.class),
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        Instant now = Instant.parse("2026-07-04T06:00:00Z");
        String requestPayloadJson = """
                {
                  "content":"继续处理剩余任务",
                  "attachmentIds":null,
                  "preferredProvider":null,
                  "singleTurnOverride":null
                }
                """;
        when(turnRepository.findBySessionIdAndTurnId("session-1", "turn-1"))
                .thenReturn(Optional.of(new ChatTurnRecord(
                        "turn-1",
                        "session-1",
                        ChatTurnAction.SEND,
                        ChatTurnStatus.SUSPENDED,
                        requestPayloadJson,
                        "user-entry-1",
                        "assistant-entry-1",
                        "trace-suspended-1",
                        null,
                        "SUSPENDED",
                        null,
                        null,
                        1,
                        now,
                        now)));
        when(transcriptRepository.findById("assistant-entry-1")).thenReturn(Optional.empty());

        var recoveryAction = new TurnRecoveryActionRequest(
                "task-recovery-resume",
                "继续处理",
                "保留当前进度，按恢复计划从卡住的位置继续。",
                "resume",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "已有结果会保留，继续处理剩余部分。",
                List.of("复用已有结果", "继续处理剩余任务"),
                null,
                null,
                null,
                null);
        var resolved = service.prepare("session-1", new ChatRequest(
                "turn-1",
                ChatTurnAction.RESUME,
                "",
                "session-1",
                null,
                null,
                null,
                null,
                recoveryAction));

        assertThat(resolved.content())
                .contains("继续处理剩余任务")
                .contains("<resume_user_input>")
                .contains("按上一轮恢复计划继续。")
                .contains("恢复动作：继续处理")
                .contains("动作说明：保留当前进度，按恢复计划从卡住的位置继续。")
                .contains("恢复模式：resume")
                .contains("继续策略：按恢复计划继续处理剩余任务，保留已完成内容。")
                .contains("恢复提示：已有结果会保留，继续处理剩余部分。")
                .contains("恢复计划：复用已有结果、继续处理剩余任务")
                .contains("请按恢复计划继续，保留已经完成的内容。")
                .doesNotContain("从上一轮断点继续。")
                .doesNotContain("请从这个失败点继续")
                .doesNotContain("目标：")
                .doesNotContain("执行类型：")
                .doesNotContain("上次输入：")
                .doesNotContain("TOOL_FAILURE");

        var payloadCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(turnRepository).markAttemptStarted(
                eq("session-1"),
                eq("turn-1"),
                eq(ChatTurnAction.RESUME),
                payloadCaptor.capture(),
                org.mockito.ArgumentMatchers.any());
        Map<String, Object> payload = objectMapper.readValue(payloadCaptor.getValue(), MAP_TYPE);
        Map<String, Object> recovery = objectMapper.convertValue(
                payload.get("lastRecoveryContext"), MAP_TYPE);
        assertThat(recovery)
                .containsEntry("action", "RESUME")
                .containsEntry("sourceTraceId", "trace-suspended-1")
                .containsEntry("assistantEntryId", "assistant-entry-1")
                .containsEntry("title", "继续处理")
                .containsEntry("detail", "已有结果会保留，继续处理剩余部分。")
                .containsEntry("resumeStrategy", "按恢复计划继续处理剩余任务，保留已完成内容。");
        assertThat(recovery.get("checkpoint")).isNull();
        assertThat(String.valueOf(recovery.get("nextActions")))
                .contains("复用已有结果")
                .contains("继续处理剩余任务");
        verify(transcriptRepository, never()).updateVisibility("assistant-entry-1", false, false);
    }

    @Test
    void RESUME无结构化动作但有历史恢复摘要时应生成恢复指令() throws Exception {
        var turnRepository = mock(ChatTurnRepository.class);
        var transcriptRepository = mock(SessionTranscriptRepository.class);
        var service = new ChatTurnService(
                turnRepository,
                transcriptRepository,
                objectMapper,
                mock(ApplicationEventPublisher.class),
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        Instant now = Instant.parse("2026-07-04T06:00:00Z");
        String requestPayloadJson = """
                {
                  "content":"帮我跑测试",
                  "attachmentIds":null,
                  "preferredProvider":null,
                  "singleTurnOverride":null
                }
                """;
        when(turnRepository.findBySessionIdAndTurnId("session-1", "turn-1"))
                .thenReturn(Optional.of(new ChatTurnRecord(
                        "turn-1",
                        "session-1",
                        ChatTurnAction.SEND,
                        ChatTurnStatus.DEGRADED,
                        requestPayloadJson,
                        "user-entry-1",
                        "assistant-entry-1",
                        "trace-failed-1",
                        null,
                        "DEGRADED",
                        null,
                        null,
                        1,
                        now,
                        now)));
        String taskRecoveryJson = objectMapper.writeValueAsString(Map.of(
                "title", "Shell 执行 没有完成",
                "detail", "命令或代码没有完成，可以修正错误后继续执行。",
                "actionLabel", "修正后继续",
                "checkpoint", Map.of(
                        "kind", "TOOL_FAILURE",
                        "toolId", "shell.exec",
                        "toolName", "Shell 执行",
                        "executionKind", "TOOL",
                        "action", "执行命令",
                        "failureCategory", "COMMAND",
                        "inputSummary", "执行 `npm test`",
                        "outputSummary", "测试失败"),
                "nextActions", List.of("查看命令输出并修正报错原因", "从失败命令后继续执行验证")
        ));
        String assistantPayloadJson = objectMapper.writeValueAsString(Map.of(
                "content", "测试没有跑完",
                "taskRecoveryJson", taskRecoveryJson
        ));
        when(transcriptRepository.findById("assistant-entry-1"))
                .thenReturn(Optional.of(new SessionTranscriptRepository.SessionTranscriptEntryRow(
                        "assistant-entry-1",
                        "session-1",
                        "main",
                        "MESSAGE",
                        "assistant",
                        "turn-1",
                        "trace-failed-1",
                        true,
                        true,
                        assistantPayloadJson,
                        100,
                        now)));

        var resolved = service.prepare("session-1", new ChatRequest(
                "turn-1",
                ChatTurnAction.RESUME,
                "",
                "session-1",
                null,
                null));

        assertThat(resolved.content())
                .contains("帮我跑测试")
                .contains("<resume_user_input>")
                .contains("按上一轮恢复计划继续。")
                .contains("恢复标题：Shell 执行 没有完成")
                .contains("恢复动作：修正后继续")
                .contains("继续策略：从失败断点继续，保留已完成步骤和失败输出，不要重复成功部分。")
                .contains("目标：Shell 执行（命令执行/COMMAND）")
                .contains("恢复计划：查看命令输出并修正报错原因、从失败命令后继续执行验证")
                .contains("请按恢复计划继续，保留已经完成的内容。");

        var payloadCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(turnRepository).markAttemptStarted(
                eq("session-1"),
                eq("turn-1"),
                eq(ChatTurnAction.RESUME),
                payloadCaptor.capture(),
                org.mockito.ArgumentMatchers.any());
        Map<String, Object> payload = objectMapper.readValue(payloadCaptor.getValue(), MAP_TYPE);
        Map<String, Object> recovery = objectMapper.convertValue(
                payload.get("lastRecoveryContext"), MAP_TYPE);
        assertThat(recovery)
                .containsEntry("action", "RESUME")
                .containsEntry("sourceTraceId", "trace-failed-1")
                .containsEntry("assistantEntryId", "assistant-entry-1")
                .containsEntry("title", "Shell 执行 没有完成")
                .containsEntry("detail", "命令或代码没有完成，可以修正错误后继续执行。")
                .containsEntry("resumeStrategy", "从失败断点继续，保留已完成步骤和失败输出，不要重复成功部分。");
        assertThat(String.valueOf(recovery.get("resumeInput")))
                .contains("按上一轮恢复计划继续。")
                .contains("目标：Shell 执行（命令执行/COMMAND）");
        assertThat(String.valueOf(recovery.get("nextActions")))
                .contains("从失败命令后继续执行验证");
        verify(transcriptRepository, never()).updateVisibility("assistant-entry-1", false, false);
    }

    @Test
    void RESUME恢复计划应裁剪过长和过多建议() throws Exception {
        var turnRepository = mock(ChatTurnRepository.class);
        var transcriptRepository = mock(SessionTranscriptRepository.class);
        var service = new ChatTurnService(
                turnRepository,
                transcriptRepository,
                objectMapper,
                mock(ApplicationEventPublisher.class),
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        Instant now = Instant.parse("2026-07-04T06:00:00Z");
        String requestPayloadJson = """
                {
                  "content":"继续处理剩余任务",
                  "attachmentIds":null,
                  "preferredProvider":null,
                  "singleTurnOverride":null
                }
                """;
        when(turnRepository.findBySessionIdAndTurnId("session-1", "turn-1"))
                .thenReturn(Optional.of(new ChatTurnRecord(
                        "turn-1",
                        "session-1",
                        ChatTurnAction.SEND,
                        ChatTurnStatus.SUSPENDED,
                        requestPayloadJson,
                        "user-entry-1",
                        "assistant-entry-1",
                        "trace-suspended-1",
                        null,
                        "SUSPENDED",
                        null,
                        null,
                        1,
                        now,
                        now)));
        when(transcriptRepository.findById("assistant-entry-1")).thenReturn(Optional.empty());
        List<String> nextActions = List.of(
                "复用已有结果",
                "继续处理剩余任务",
                " 继续处理剩余任务 ",
                "重新检查失败点",
                "运行最小验证",
                "重新检查失败点",
                "整理恢复说明-" + "x".repeat(300) + "-尾部",
                "第六步不应注入");
        var recoveryAction = new TurnRecoveryActionRequest(
                "task-recovery-resume",
                "继续处理",
                "保留当前进度，按恢复计划从卡住的位置继续。",
                "resume",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "已有结果会保留，继续处理剩余部分。",
                nextActions,
                null,
                null,
                null,
                null);

        var resolved = service.prepare("session-1", new ChatRequest(
                "turn-1",
                ChatTurnAction.RESUME,
                "",
                "session-1",
                null,
                null,
                null,
                null,
                recoveryAction));

        assertThat(resolved.content())
                .contains("恢复计划：复用已有结果、继续处理剩余任务、重新检查失败点、运行最小验证、整理恢复说明-")
                .doesNotContain("继续处理剩余任务、继续处理剩余任务")
                .doesNotContain("第六步不应注入")
                .doesNotContain("尾部");

        var payloadCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(turnRepository).markAttemptStarted(
                eq("session-1"),
                eq("turn-1"),
                eq(ChatTurnAction.RESUME),
                payloadCaptor.capture(),
                org.mockito.ArgumentMatchers.any());
        Map<String, Object> payload = objectMapper.readValue(payloadCaptor.getValue(), MAP_TYPE);
        Map<String, Object> recovery = objectMapper.convertValue(
                payload.get("lastRecoveryContext"), MAP_TYPE);
        @SuppressWarnings("unchecked")
        List<String> recoveredNextActions = (List<String>) recovery.get("nextActions");
        assertThat(recoveredNextActions)
                .hasSize(5)
                .containsExactly(
                        "复用已有结果",
                        "继续处理剩余任务",
                        "重新检查失败点",
                        "运行最小验证",
                        recoveredNextActions.get(4))
                .doesNotContain("第六步不应注入");
        assertThat(recoveredNextActions.get(4))
                .startsWith("整理恢复说明-")
                .endsWith("…")
                .doesNotContain("尾部");
    }

    @Test
    void RESTART只有恢复计划时不应提示修正失败点() throws Exception {
        var turnRepository = mock(ChatTurnRepository.class);
        var transcriptRepository = mock(SessionTranscriptRepository.class);
        var service = new ChatTurnService(
                turnRepository,
                transcriptRepository,
                objectMapper,
                mock(ApplicationEventPublisher.class),
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        Instant now = Instant.parse("2026-07-04T06:00:00Z");
        String requestPayloadJson = """
                {
                  "content":"继续处理剩余任务",
                  "attachmentIds":null,
                  "preferredProvider":null,
                  "singleTurnOverride":null
                }
                """;
        when(turnRepository.findBySessionIdAndTurnId("session-1", "turn-1"))
                .thenReturn(Optional.of(new ChatTurnRecord(
                        "turn-1",
                        "session-1",
                        ChatTurnAction.SEND,
                        ChatTurnStatus.SUSPENDED,
                        requestPayloadJson,
                        "user-entry-1",
                        "assistant-entry-1",
                        "trace-suspended-1",
                        null,
                        "SUSPENDED",
                        null,
                        null,
                        1,
                        now,
                        now)));
        when(transcriptRepository.findById("assistant-entry-1")).thenReturn(Optional.empty());

        var recoveryAction = new TurnRecoveryActionRequest(
                "task-recovery-restart",
                "重新开始",
                "保留恢复摘要和失败线索，重新开始这一轮。",
                "restart",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "已有结果可复用，请重新组织剩余任务。",
                List.of("复用已有结果", "重新处理剩余任务"),
                null,
                null,
                null,
                null);
        var resolved = service.prepare("session-1", new ChatRequest(
                "turn-1",
                ChatTurnAction.RESTART,
                "",
                "session-1",
                null,
                null,
                null,
                null,
                recoveryAction));

        assertThat(resolved.content())
                .contains("<restart_original_user_input>")
                .contains("继续处理剩余任务")
                .contains("<restart_instruction>")
                .contains("重新开始上一轮任务。")
                .contains("恢复动作：重新开始")
                .contains("动作说明：保留恢复摘要和失败线索，重新开始这一轮。")
                .contains("继续策略：重新开始原始任务，保留可复用线索并按恢复计划推进。")
                .contains("恢复计划：复用已有结果、重新处理剩余任务")
                .contains("请重新开始这一轮，保留可复用信息并按恢复计划推进。")
                .contains("<task_recovery_checkpoint>")
                .contains("- 重新开始时保留可复用信息，按计划完成原始请求。")
                .doesNotContain("优先修正失败点")
                .doesNotContain("TOOL_FAILURE");

        var payloadCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(turnRepository).markAttemptStarted(
                eq("session-1"),
                eq("turn-1"),
                eq(ChatTurnAction.RESTART),
                payloadCaptor.capture(),
                org.mockito.ArgumentMatchers.any());
        Map<String, Object> payload = objectMapper.readValue(payloadCaptor.getValue(), MAP_TYPE);
        Map<String, Object> recovery = objectMapper.convertValue(
                payload.get("lastRecoveryContext"), MAP_TYPE);
        assertThat(recovery)
                .containsEntry("action", "RESTART")
                .containsEntry("sourceTraceId", "trace-suspended-1")
                .containsEntry("assistantEntryId", "assistant-entry-1")
                .containsEntry("title", "重新开始")
                .containsEntry("detail", "已有结果可复用，请重新组织剩余任务。")
                .containsEntry("resumeStrategy", "重新开始原始任务，保留可复用线索并按恢复计划推进。");
        assertThat(recovery.get("checkpoint")).isNull();
        assertThat(String.valueOf(recovery.get("nextActions")))
                .contains("复用已有结果")
                .contains("重新处理剩余任务");
    }

    @Test
    void RESTART应使用本次重启指令并保留用户可见原始问题() throws Exception {
        var turnRepository = mock(ChatTurnRepository.class);
        var transcriptRepository = mock(SessionTranscriptRepository.class);
        var service = new ChatTurnService(
                turnRepository,
                transcriptRepository,
                objectMapper,
                mock(ApplicationEventPublisher.class),
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        Instant now = Instant.parse("2026-07-04T06:00:00Z");
        String requestPayloadJson = """
                {
                  "content":"帮我跑测试",
                  "attachmentIds":null,
                  "preferredProvider":null,
                  "singleTurnOverride":null
                }
                """;
        when(turnRepository.findBySessionIdAndTurnId("session-1", "turn-1"))
                .thenReturn(Optional.of(new ChatTurnRecord(
                        "turn-1",
                        "session-1",
                        ChatTurnAction.SEND,
                        ChatTurnStatus.DEGRADED,
                        requestPayloadJson,
                        "user-entry-1",
                        "assistant-entry-1",
                        "trace-failed-1",
                        null,
                        "DEGRADED",
                        null,
                        null,
                        1,
                        now,
                        now)));
        String taskRecoveryJson = objectMapper.writeValueAsString(Map.of(
                "title", "Shell 执行 没有完成",
                "detail", "命令或代码没有完成，可以修正错误后继续执行。",
                "checkpoint", Map.of(
                        "kind", "TOOL_FAILURE",
                        "toolId", "shell.exec",
                        "toolName", "Shell 执行",
                        "failureCategory", "COMMAND",
                        "workingDirectory", "D:\\WorkSpace\\Project\\News",
                        "inputSummary", "执行 `npm test`",
                        "outputSummary", "断言失败",
                        "outputDetail", "AssertionError: expected true to be false"),
                "nextActions", List.of("查看命令输出并修正报错原因", "从失败命令后继续执行验证")
        ));
        String assistantPayloadJson = objectMapper.writeValueAsString(Map.of(
                "content", "这轮没有完整完成",
                "taskRecoveryJson", taskRecoveryJson
        ));
        when(transcriptRepository.findById("assistant-entry-1"))
                .thenReturn(Optional.of(new SessionTranscriptRepository.SessionTranscriptEntryRow(
                        "assistant-entry-1",
                        "session-1",
                        "main",
                        "MESSAGE",
                        "assistant",
                        "turn-1",
                        "trace-failed-1",
                        true,
                        true,
                        assistantPayloadJson,
                        100,
                        now)));

        String restartInstruction = "重新开始：重新开始。目标：Shell 执行（命令执行）。请重新开始这一轮，并优先修正这个失败点。";
        var resolved = service.prepare("session-1", new ChatRequest(
                "turn-1",
                ChatTurnAction.RESTART,
                restartInstruction,
                "session-1",
                null,
                null,
                null,
                "帮我跑测试"));

        assertThat(resolved.content())
                .contains(restartInstruction)
                .contains("<restart_original_user_input>")
                .contains("帮我跑测试")
                .contains("</restart_original_user_input>")
                .contains("<restart_instruction>")
                .contains("</restart_instruction>")
                .contains("<task_recovery_checkpoint>")
                .contains("工具: Shell 执行")
                .contains("失败分类: 命令执行/COMMAND")
                .contains("工作目录: D:\\WorkSpace\\Project\\News")
                .contains("输入: 执行 `npm test`")
                .contains("输出: 断言失败")
                .contains("详细输出: AssertionError: expected true to be false")
                .contains("从失败命令后继续执行验证")
                .contains("重新开始时优先修正失败点");

        var payloadCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(turnRepository).markAttemptStarted(
                eq("session-1"),
                eq("turn-1"),
                eq(ChatTurnAction.RESTART),
                payloadCaptor.capture(),
                org.mockito.ArgumentMatchers.any());
        Map<String, Object> payload = objectMapper.readValue(payloadCaptor.getValue(), MAP_TYPE);
        assertThat(payload).containsEntry("content", "帮我跑测试");
        Map<String, Object> recovery = objectMapper.convertValue(
                payload.get("lastRecoveryContext"), MAP_TYPE);
        assertThat(recovery)
                .containsEntry("action", "RESTART")
                .containsEntry("resumeInput", restartInstruction)
                .containsEntry("sourceTraceId", "trace-failed-1")
                .containsEntry("assistantEntryId", "assistant-entry-1")
                .containsEntry("title", "Shell 执行 没有完成");
        verify(transcriptRepository).updateVisibility("assistant-entry-1", false, false);
        verify(transcriptRepository, never()).updateMessageContent("user-entry-1", "帮我跑测试");
    }

    @Test
    void RESTART结构化恢复动作应保留原始输入并注入断点上下文() throws Exception {
        var turnRepository = mock(ChatTurnRepository.class);
        var transcriptRepository = mock(SessionTranscriptRepository.class);
        var service = new ChatTurnService(
                turnRepository,
                transcriptRepository,
                objectMapper,
                mock(ApplicationEventPublisher.class),
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        Instant now = Instant.parse("2026-07-04T06:00:00Z");
        String requestPayloadJson = """
                {
                  "content":"帮我跑测试",
                  "attachmentIds":null,
                  "preferredProvider":null,
                  "singleTurnOverride":null
                }
                """;
        when(turnRepository.findBySessionIdAndTurnId("session-1", "turn-1"))
                .thenReturn(Optional.of(new ChatTurnRecord(
                        "turn-1",
                        "session-1",
                        ChatTurnAction.SEND,
                        ChatTurnStatus.DEGRADED,
                        requestPayloadJson,
                        "user-entry-1",
                        "assistant-entry-1",
                        "trace-failed-1",
                        null,
                        "DEGRADED",
                        null,
                        null,
                        1,
                        now,
                        now)));
        when(transcriptRepository.findById("assistant-entry-1")).thenReturn(Optional.empty());

        var recoveryAction = new TurnRecoveryActionRequest(
                "restart",
                "重新开始",
                "保留失败输出，重新开始并优先修正命令错误。",
                "restart",
                "shell.exec",
                "Shell 执行",
                "TOOL",
                "执行命令",
                "COMMAND",
                null,
                null,
                "执行 `npm test`",
                "{\"command\":\"npm test\",\"workingDirectory\":\"D:\\\\WorkSpace\\\\Project\\\\News\"}",
                "测试失败",
                "AssertionError: expected true to be false",
                "D:\\WorkSpace\\Project\\News",
                null,
                "重新运行前先修复失败断言",
                List.of("查看命令输出并修正报错原因", "从失败命令后继续执行验证"),
                "call-shell-1",
                true,
                null,
                null);
        var resolved = service.prepare("session-1", new ChatRequest(
                "turn-1",
                ChatTurnAction.RESTART,
                "",
                "session-1",
                null,
                null,
                null,
                "帮我跑测试",
                recoveryAction));

        assertThat(resolved.content())
                .contains("<restart_original_user_input>")
                .contains("帮我跑测试")
                .contains("<restart_instruction>")
                .contains("重新开始上一轮任务。")
                .contains("恢复动作：重新开始")
                .contains("动作说明：保留失败输出，重新开始并优先修正命令错误。")
                .contains("恢复模式：restart")
                .contains("调用 ID：call-shell-1")
                .contains("目标：Shell 执行（命令执行/COMMAND）")
                .contains("中断状态：已开始但没有返回执行结果")
                .contains("恢复计划：查看命令输出并修正报错原因、从失败命令后继续执行验证")
                .contains("<task_recovery_checkpoint>")
                .contains("恢复模式: restart")
                .contains("调用 ID: call-shell-1")
                .contains("工具: Shell 执行")
                .contains("执行类型: TOOL")
                .contains("操作: 执行命令")
                .contains("中断状态: 已开始但没有返回执行结果")
                .contains("失败分类: 命令执行/COMMAND")
                .contains("工作目录: D:\\WorkSpace\\Project\\News")
                .contains("输入: 执行 `npm test`")
                .contains("输入详情: {\"command\":\"npm test\",\"workingDirectory\":\"D:\\\\WorkSpace\\\\Project\\\\News\"}")
                .contains("输出: 测试失败")
                .contains("重新开始时优先修正失败点");

        var payloadCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(turnRepository).markAttemptStarted(
                eq("session-1"),
                eq("turn-1"),
                eq(ChatTurnAction.RESTART),
                payloadCaptor.capture(),
                org.mockito.ArgumentMatchers.any());
        Map<String, Object> payload = objectMapper.readValue(payloadCaptor.getValue(), MAP_TYPE);
        assertThat(payload).containsEntry("content", "帮我跑测试");
        Map<String, Object> recovery = objectMapper.convertValue(
                payload.get("lastRecoveryContext"), MAP_TYPE);
        assertThat(recovery)
                .containsEntry("action", "RESTART")
                .containsEntry("title", "重新开始")
                .containsEntry("detail", "重新运行前先修复失败断言");
        assertThat(String.valueOf(recovery.get("nextActions")))
                .contains("从失败命令后继续执行验证");
        Map<String, Object> checkpoint = objectMapper.convertValue(recovery.get("checkpoint"), MAP_TYPE);
        assertThat(checkpoint)
                .containsEntry("callId", "call-shell-1")
                .containsEntry("recoveryActionMode", "restart")
                .containsEntry("interrupted", true)
                .containsEntry("toolId", "shell.exec")
                .containsEntry("failureCategory", "COMMAND");
        verify(transcriptRepository).updateVisibility("assistant-entry-1", false, false);
        verify(transcriptRepository, never()).updateMessageContent("user-entry-1", "帮我跑测试");
    }

    @Test
    void RESTART恢复断点应裁剪超长详细输出() throws Exception {
        var turnRepository = mock(ChatTurnRepository.class);
        var transcriptRepository = mock(SessionTranscriptRepository.class);
        var service = new ChatTurnService(
                turnRepository,
                transcriptRepository,
                objectMapper,
                mock(ApplicationEventPublisher.class),
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        Instant now = Instant.parse("2026-07-04T06:00:00Z");
        String requestPayloadJson = """
                {
                  "content":"帮我跑测试",
                  "attachmentIds":null,
                  "preferredProvider":null,
                  "singleTurnOverride":null
                }
                """;
        when(turnRepository.findBySessionIdAndTurnId("session-1", "turn-1"))
                .thenReturn(Optional.of(new ChatTurnRecord(
                        "turn-1",
                        "session-1",
                        ChatTurnAction.SEND,
                        ChatTurnStatus.DEGRADED,
                        requestPayloadJson,
                        "user-entry-1",
                        "assistant-entry-1",
                        "trace-failed-1",
                        null,
                        "DEGRADED",
                        null,
                        null,
                        1,
                        now,
                        now)));
        String longOutputDetail = "日志开始-" + "x".repeat(1500) + "-日志尾部";
        String taskRecoveryJson = objectMapper.writeValueAsString(Map.of(
                "title", "Shell 执行 没有完成",
                "detail", "命令输出过长，只保留关键失败上下文。",
                "checkpoint", Map.of(
                        "kind", "TOOL_FAILURE",
                        "toolId", "shell.exec",
                        "toolName", "Shell 执行",
                        "failureCategory", "COMMAND",
                        "outputSummary", "测试失败",
                        "outputDetail", longOutputDetail),
                "nextActions", List.of("根据失败摘要重新运行最小验证")
        ));
        String assistantPayloadJson = objectMapper.writeValueAsString(Map.of(
                "content", "这轮没有完整完成",
                "taskRecoveryJson", taskRecoveryJson
        ));
        when(transcriptRepository.findById("assistant-entry-1"))
                .thenReturn(Optional.of(new SessionTranscriptRepository.SessionTranscriptEntryRow(
                        "assistant-entry-1",
                        "session-1",
                        "main",
                        "MESSAGE",
                        "assistant",
                        "turn-1",
                        "trace-failed-1",
                        true,
                        true,
                        assistantPayloadJson,
                        100,
                        now)));

        var resolved = service.prepare("session-1", new ChatRequest(
                "turn-1",
                ChatTurnAction.RESTART,
                "重新开始：先修复失败测试。",
                "session-1",
                null,
                null,
                null,
                "帮我跑测试"));

        String detailLine = resolved.content()
                .lines()
                .filter(line -> line.startsWith("- 详细输出:"))
                .findFirst()
                .orElseThrow();
        assertThat(detailLine)
                .contains("日志开始-")
                .endsWith("…")
                .doesNotContain("日志尾部");
        assertThat(detailLine.length()).isLessThan(1250);
        assertThat(resolved.content()).doesNotContain(longOutputDetail);

        var payloadCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(turnRepository).markAttemptStarted(
                eq("session-1"),
                eq("turn-1"),
                eq(ChatTurnAction.RESTART),
                payloadCaptor.capture(),
                org.mockito.ArgumentMatchers.any());
        Map<String, Object> payload = objectMapper.readValue(payloadCaptor.getValue(), MAP_TYPE);
        Map<String, Object> recovery = objectMapper.convertValue(
                payload.get("lastRecoveryContext"), MAP_TYPE);
        Map<String, Object> checkpoint = objectMapper.convertValue(recovery.get("checkpoint"), MAP_TYPE);
        assertThat(String.valueOf(checkpoint.get("outputDetail")))
                .contains("日志开始-")
                .endsWith("…")
                .doesNotContain("日志尾部")
                .hasSize(1200);
    }

    @Test
    void RESTART应把技能恢复主体写入结构化断点() throws Exception {
        var turnRepository = mock(ChatTurnRepository.class);
        var transcriptRepository = mock(SessionTranscriptRepository.class);
        var service = new ChatTurnService(
                turnRepository,
                transcriptRepository,
                objectMapper,
                mock(ApplicationEventPublisher.class),
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        Instant now = Instant.parse("2026-07-04T06:00:00Z");
        String requestPayloadJson = """
                {
                  "content":"帮我用调研技能整理资料",
                  "attachmentIds":null,
                  "preferredProvider":null,
                  "singleTurnOverride":null
                }
                """;
        when(turnRepository.findBySessionIdAndTurnId("session-1", "turn-1"))
                .thenReturn(Optional.of(new ChatTurnRecord(
                        "turn-1",
                        "session-1",
                        ChatTurnAction.SEND,
                        ChatTurnStatus.DEGRADED,
                        requestPayloadJson,
                        "user-entry-1",
                        "assistant-entry-1",
                        "trace-skill-failed-1",
                        null,
                        "DEGRADED",
                        null,
                        null,
                        1,
                        now,
                        now)));
        String taskRecoveryJson = objectMapper.writeValueAsString(Map.of(
                "title", "技能 research-assistant 没有完成",
                "detail", "技能加载没有完成，可以检查技能名称或依赖后继续。",
                "checkpoint", Map.of(
                        "kind", "TOOL_FAILURE",
                        "toolId", "skill.load",
                        "toolName", "加载 Skill",
                        "executionKind", "SKILL",
                        "action", "加载技能",
                        "failureCategory", "SKILL",
                        "subjectLabel", "技能",
                        "subjectNames", List.of("research-assistant"),
                        "inputSummary", "加载技能「research-assistant」",
                        "outputSummary", "技能 research-assistant 不存在"),
                "nextActions", List.of("确认技能 research-assistant 的名称和依赖是否可用", "重新加载技能后继续当前任务")
        ));
        String assistantPayloadJson = objectMapper.writeValueAsString(Map.of(
                "content", "技能加载失败",
                "taskRecoveryJson", taskRecoveryJson
        ));
        when(transcriptRepository.findById("assistant-entry-1"))
                .thenReturn(Optional.of(new SessionTranscriptRepository.SessionTranscriptEntryRow(
                        "assistant-entry-1",
                        "session-1",
                        "main",
                        "MESSAGE",
                        "assistant",
                        "turn-1",
                        "trace-skill-failed-1",
                        true,
                        true,
                        assistantPayloadJson,
                        100,
                        now)));

        String restartInstruction = "重新开始：重新开始。目标：加载 Skill（技能加载）。技能：research-assistant。";
        var resolved = service.prepare("session-1", new ChatRequest(
                "turn-1",
                ChatTurnAction.RESTART,
                restartInstruction,
                "session-1",
                null,
                null,
                null,
                "帮我用调研技能整理资料"));

        assertThat(resolved.content())
                .contains("<task_recovery_checkpoint>")
                .contains("标题: 技能 research-assistant 没有完成")
                .contains("工具: 加载 Skill")
                .contains("执行类型: SKILL")
                .contains("失败分类: 技能/SKILL")
                .contains("操作: 加载技能")
                .contains("关联对象: 技能 research-assistant")
                .contains("输入: 加载技能「research-assistant」")
                .contains("输出: 技能 research-assistant 不存在")
                .contains("确认技能 research-assistant 的名称和依赖是否可用")
                .contains("重新加载技能后继续当前任务");
    }

    @Test
    void RESTART编辑用户消息时应更新快照和历史用户消息() throws Exception {
        var turnRepository = mock(ChatTurnRepository.class);
        var transcriptRepository = mock(SessionTranscriptRepository.class);
        var service = new ChatTurnService(
                turnRepository,
                transcriptRepository,
                objectMapper,
                mock(ApplicationEventPublisher.class),
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        Instant now = Instant.parse("2026-07-04T06:00:00Z");
        String requestPayloadJson = """
                {
                  "content":"帮我写周报",
                  "attachmentIds":null,
                  "preferredProvider":null,
                  "singleTurnOverride":null
                }
                """;
        when(turnRepository.findBySessionIdAndTurnId("session-1", "turn-1"))
                .thenReturn(Optional.of(new ChatTurnRecord(
                        "turn-1",
                        "session-1",
                        ChatTurnAction.SEND,
                        ChatTurnStatus.SUCCESS,
                        requestPayloadJson,
                        "user-entry-1",
                        "assistant-entry-1",
                        "trace-old-1",
                        null,
                        "NORMAL",
                        null,
                        null,
                        1,
                        now,
                        now)));
        when(transcriptRepository.findById("assistant-entry-1")).thenReturn(Optional.empty());

        var resolved = service.prepare("session-1", new ChatRequest(
                "turn-1",
                ChatTurnAction.RESTART,
                "帮我写周报，重点突出风险和下周计划",
                "session-1",
                null,
                null));

        assertThat(resolved.content()).isEqualTo("帮我写周报，重点突出风险和下周计划");

        var payloadCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(turnRepository).markAttemptStarted(
                eq("session-1"),
                eq("turn-1"),
                eq(ChatTurnAction.RESTART),
                payloadCaptor.capture(),
                org.mockito.ArgumentMatchers.any());
        Map<String, Object> payload = objectMapper.readValue(payloadCaptor.getValue(), MAP_TYPE);
        assertThat(payload)
                .containsEntry("content", "帮我写周报，重点突出风险和下周计划")
                .containsEntry("lastRecoveryContext", null);
        verify(transcriptRepository).updateVisibility("assistant-entry-1", false, false);
        verify(transcriptRepository).updateMessageContent(
                "user-entry-1",
                "帮我写周报，重点突出风险和下周计划");
    }
}
