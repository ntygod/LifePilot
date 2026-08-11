package com.lifepilot.interaction.web.service;

import com.lifepilot.interaction.web.model.TurnRecoveryActionRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TurnRecoveryActionRequest 单元测试。
 *
 * @author zsg
 * @since 2026-07-05
 */
class TurnRecoveryActionRequest_单元测试 {

    @Test
    void 只有恢复计划时不生成工具断点() {
        var request = new TurnRecoveryActionRequest(
                " task-recovery-resume ",
                " 继续处理 ",
                " 保留当前进度，按恢复计划从卡住的位置继续。 ",
                "RESUME",
                " ",
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
                " 已有结果会保留，继续处理剩余部分。 ",
                Arrays.asList(" ", "复用已有结果", "复用已有结果", null, "继续处理剩余任务", "继续处理剩余任务 "),
                " ",
                false,
                null,
                null);

        assertThat(request.isEmpty()).isFalse();
        assertThat(request.id()).isEqualTo("task-recovery-resume");
        assertThat(request.label()).isEqualTo("继续处理");
        assertThat(request.description()).isEqualTo("保留当前进度，按恢复计划从卡住的位置继续。");
        assertThat(request.mode()).isEqualTo("resume");
        assertThat(request.recoveryHint()).isEqualTo("已有结果会保留，继续处理剩余部分。");
        assertThat(request.nextActions()).containsExactly("复用已有结果", "继续处理剩余任务");
        assertThat(request.toCheckpointMap()).isEmpty();
    }

    @Test
    void 有真实断点字段时生成工具断点() {
        var request = new TurnRecoveryActionRequest(
                "resume",
                "修正后继续",
                "保留已完成步骤，修正命令或代码错误后继续验证。",
                "resume",
                "shell.exec",
                "Shell 执行",
                "TOOL",
                "执行命令",
                "COMMAND",
                "技能",
                List.of("research-assistant", " research-assistant "),
                "执行 `npm test`",
                "{\"command\":\"npm test\"}",
                "测试失败",
                null,
                "D:\\WorkSpace\\Project\\News",
                null,
                "先修复失败断言再继续验证",
                List.of("查看命令输出并修正报错原因"),
                "call-shell-1",
                true,
                null,
                null);
        Map<String, Object> checkpoint = request.toCheckpointMap();

        assertThat(checkpoint)
                .containsEntry("kind", "TOOL_FAILURE")
                .containsEntry("recoveryActionId", "resume")
                .containsEntry("recoveryActionLabel", "修正后继续")
                .containsEntry("recoveryActionDescription", "保留已完成步骤，修正命令或代码错误后继续验证。")
                .containsEntry("recoveryActionMode", "resume")
                .containsEntry("callId", "call-shell-1")
                .containsEntry("interrupted", true)
                .containsEntry("toolId", "shell.exec")
                .containsEntry("toolName", "Shell 执行")
                .containsEntry("executionKind", "TOOL")
                .containsEntry("action", "执行命令")
                .containsEntry("failureCategory", "COMMAND")
                .containsEntry("subjectLabel", "技能")
                .containsEntry("inputSummary", "执行 `npm test`")
                .containsEntry("inputDetail", "{\"command\":\"npm test\"}")
                .containsEntry("outputSummary", "测试失败")
                .containsEntry("workingDirectory", "D:\\WorkSpace\\Project\\News");
        assertThat(new ArrayList<>(checkpoint.keySet()))
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
                        "subjectLabel",
                        "subjectNames",
                        "inputSummary",
                        "inputDetail",
                        "outputSummary",
                        "workingDirectory");
        assertThat(checkpoint.get("subjectNames"))
                .isEqualTo(List.of("research-assistant"));
    }

    @Test
    void 产物引用应进入恢复断点并过滤无效项() {
        var request = new TurnRecoveryActionRequest(
                "resume",
                "继续生成",
                null,
                "resume",
                "file.write",
                "文件写入",
                "TOOL",
                "写入文件",
                "FILE",
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
                "call-file-1",
                true,
                List.of(
                        Map.of(
                                "id", " artifact-1 ",
                                "filename", " report.md ",
                                "contentType", " text/markdown ",
                                "type", " FILE ",
                                "size", "128",
                                "url", " /api/artifacts/artifact-1/download "),
                        Map.of("fileName", "missing-id.md")),
                null);

        Map<String, Object> checkpoint = request.toCheckpointMap();

        assertThat(checkpoint).containsKey("artifactRefs");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> artifactRefs = (List<Map<String, Object>>) checkpoint.get("artifactRefs");
        assertThat(artifactRefs).hasSize(1);
        assertThat(artifactRefs.getFirst())
                .containsEntry("artifactId", "artifact-1")
                .containsEntry("fileName", "report.md")
                .containsEntry("mimeType", "text/markdown")
                .containsEntry("kind", "FILE")
                .containsEntry("size", 128L)
                .containsEntry("downloadUrl", "/api/artifacts/artifact-1/download");
    }

    @Test
    void 产物引用缺少下载地址时应按artifactId生成默认地址并按mime推断类型() {
        var request = new TurnRecoveryActionRequest(
                "resume",
                "继续生成",
                null,
                "resume",
                "image.render",
                "生成图片",
                "TOOL",
                "生成图片",
                "FILE",
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
                "call-image-1",
                true,
                List.of(Map.of(
                        "artifact_id", " artifact-image ",
                        "name", "cover.png",
                        "media_type", "image/png")),
                null);

        Map<String, Object> checkpoint = request.toCheckpointMap();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> artifactRefs = (List<Map<String, Object>>) checkpoint.get("artifactRefs");
        assertThat(artifactRefs.getFirst())
                .containsEntry("artifactId", "artifact-image")
                .containsEntry("fileName", "cover.png")
                .containsEntry("mimeType", "image/png")
                .containsEntry("kind", "IMAGE")
                .containsEntry("downloadUrl", "/api/artifacts/artifact-image/download");
    }

    @Test
    void 产物引用应按artifactId去重并保留首次有效引用() {
        var request = new TurnRecoveryActionRequest(
                "resume",
                "继续生成",
                null,
                "resume",
                "file.write",
                "文件写入",
                "TOOL",
                "写入文件",
                "FILE",
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
                "call-file-1",
                true,
                List.of(
                        Map.of(
                                "artifactId", "artifact-1",
                                "fileName", "first.md",
                                "mimeType", "text/markdown",
                                "kind", "FILE"),
                        Map.of(
                                "artifactId", " artifact-1 ",
                                "fileName", "duplicate.md",
                                "mimeType", "text/plain",
                                "kind", "FILE"),
                        Map.of(
                                "artifactId", "artifact-2",
                                "fileName", "second.md",
                                "mimeType", "text/markdown",
                                "kind", "FILE")),
                null);

        Map<String, Object> checkpoint = request.toCheckpointMap();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> artifactRefs = (List<Map<String, Object>>) checkpoint.get("artifactRefs");
        assertThat(artifactRefs).hasSize(2);
        assertThat(artifactRefs.get(0))
                .containsEntry("artifactId", "artifact-1")
                .containsEntry("fileName", "first.md");
        assertThat(artifactRefs.get(1))
                .containsEntry("artifactId", "artifact-2")
                .containsEntry("fileName", "second.md");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 缺失能力应进入恢复断点并去重() {
        var request = new TurnRecoveryActionRequest(
                "resume",
                "修复能力后继续",
                null,
                "resume",
                "web.search",
                "网页搜索",
                "TOOL",
                "搜索资料",
                "CAPABILITY",
                null,
                null,
                "搜索资料",
                "{\"query\":\"AI\"}",
                "工具未注册: web.search",
                null,
                null,
                null,
                "检查缺失工具能力",
                List.of("修复 web.search 工具能力"),
                "call-web-1",
                false,
                null,
                List.of(
                        Map.of(
                                "toolId", " web.search ",
                                "source", " runtime_tool_call ",
                                "reason", " 工具未注册 "),
                        Map.of(
                                "id", "web.search",
                                "source", "duplicate")));

        Map<String, Object> checkpoint = request.toCheckpointMap();

        assertThat(checkpoint).containsKey("missingCapabilities");
        List<Map<String, Object>> missingCapabilities =
                (List<Map<String, Object>>) checkpoint.get("missingCapabilities");
        assertThat(missingCapabilities).hasSize(1);
        assertThat(missingCapabilities.getFirst())
                .containsEntry("kind", "TOOL")
                .containsEntry("id", "web.search")
                .containsEntry("source", "runtime_tool_call")
                .containsEntry("reason", "工具未注册");
    }
}
