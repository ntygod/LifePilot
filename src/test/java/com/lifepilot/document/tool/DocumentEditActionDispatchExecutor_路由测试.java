package com.lifepilot.document.tool;

import com.lifepilot.document.model.DocumentVersionRecord;
import com.lifepilot.document.model.SessionDocumentRecord;
import com.lifepilot.document.patch.DocumentPatchResult;
import com.lifepilot.document.version.DocumentVersionService;
import com.lifepilot.document.version.SourceRef;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * DocumentEditActionDispatchExecutor 路由测试。
 *
 * <p>覆盖点：</p>
 * <ul>
 *   <li>action=patch: 解析 source + operations, 调 checkout + applyPatch, 回填 downloadUrl</li>
 *   <li>action=commit + commitTarget=overwrite: 调 commitOverwrite, 回填 committedPath + backupPath</li>
 *   <li>action=list_versions: 结构化映射 DocumentVersionRecord 列表</li>
 *   <li>action=unknown: 返回 "不支持的操作" 错误</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-21
 */
@ExtendWith(MockitoExtension.class)
class DocumentEditActionDispatchExecutor_路由测试 {

    @Mock
    DocumentVersionService versionService;

    private DocumentEditActionDispatchExecutor dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new DocumentEditActionDispatchExecutor(versionService);
    }

    @Test
    @DisplayName("patch action — 路径源 + replace_text 正确路由到 checkout + applyPatch")
    void patch_action路由正确() throws Exception {
        when(versionService.checkout(eq("sess-1"), any(SourceRef.PathSource.class))).thenReturn("doc-p1");
        when(versionService.applyPatch(eq("doc-p1"), any())).thenReturn(
                DocumentPatchResult.success(1, "{\"summary\":\"ok\"}", "共 1 处"));

        ToolResult result = dispatcher.execute(newInput(Map.of(
                "action", "patch",
                "source", Map.of("type", "path", "value", "D:/x.docx"),
                "operations", List.of(Map.of(
                        "op", "replace_text",
                        "target", "old",
                        "new_text", "new"))
        )));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.data())
                .containsEntry("documentId", "doc-p1")
                .containsEntry("newVersion", 1)
                .containsEntry("summary", "共 1 处")
                .containsEntry("downloadUrl", "/api/documents/doc-p1/download");
    }

    @Test
    @DisplayName("commit overwrite — 有 sourcePath + userConfirmation 时回填 committedPath 与 backupPath")
    void commit_overwrite路由正确() throws Exception {
        when(versionService.getDocumentRecord("doc-c1")).thenReturn(recordWithSource("D:/x.docx"));
        when(versionService.commitOverwrite("doc-c1"))
                .thenReturn(new DocumentVersionService.CommitResult("D:/x.docx", "D:/x.docx.20260421.bak"));

        ToolResult result = dispatcher.execute(newInput(Map.of(
                "action", "commit",
                "documentId", "doc-c1",
                "commitTarget", "overwrite",
                "userConfirmation", "overwrite"
        )));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.data())
                .containsEntry("committedPath", "D:/x.docx")
                .containsEntry("backupPath", "D:/x.docx.20260421.bak");
    }

    @Test
    @DisplayName("commit overwrite — 无 sourcePath（附件/AI 生成）直接拒绝并引导 saveAs（P0-4）")
    void commit_overwrite无sourcePath被拒() {
        when(versionService.getDocumentRecord("doc-c1")).thenReturn(recordWithSource(null));

        ToolResult result = dispatcher.execute(newInput(Map.of(
                "action", "commit",
                "documentId", "doc-c1",
                "commitTarget", "overwrite",
                "userConfirmation", "overwrite"
        )));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("该文档没有原路径");
        assertThat(result.error()).contains("saveAs");
    }

    private SessionDocumentRecord recordWithSource(String sourcePath) {
        return new SessionDocumentRecord(
                "doc-c1", "sess", null, "x.docx", "/working/v1", 100L,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                SessionDocumentRecord.ORIGIN_USER_LOCAL_FILE, sourcePath, 1, Instant.now());
    }

    @Test
    @DisplayName("commit 缺少 userConfirmation 直接拒绝（P0-2 硬化）")
    void commit_缺少userConfirmation被拒() {
        ToolResult result = dispatcher.execute(newInput(Map.of(
                "action", "commit",
                "documentId", "doc-c1",
                "commitTarget", "overwrite"
        )));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("必须先取得用户明确同意");
        assertThat(result.error()).contains("userConfirmation=\"overwrite\"");
    }

    @Test
    @DisplayName("commit 的 userConfirmation 与 commitTarget 不一致时拒绝")
    void commit_userConfirmation不匹配被拒() {
        ToolResult result = dispatcher.execute(newInput(Map.of(
                "action", "commit",
                "documentId", "doc-c1",
                "commitTarget", "overwrite",
                "userConfirmation", "saveAs"   // 与 target 不一致
        )));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("必须先取得用户明确同意");
    }

    @Test
    @DisplayName("list_versions 返回结构化版本列表")
    void list_versions返回列表() {
        Instant now = Instant.parse("2026-04-21T10:00:00Z");
        when(versionService.listVersions("doc-l1")).thenReturn(List.of(
                new DocumentVersionRecord("v0", "doc-l1", 0, "/p/v0", "initial", null, null, now),
                new DocumentVersionRecord("v1", "doc-l1", 1, "/p/v1", "patch", "共 1 处", "{}", now)
        ));

        ToolResult result = dispatcher.execute(newInput(Map.of(
                "action", "list_versions",
                "documentId", "doc-l1"
        )));

        assertThat(result.isSuccess()).isTrue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> versions = (List<Map<String, Object>>) result.data().get("versions");
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0))
                .containsEntry("versionNo", 0)
                .containsEntry("source", "initial")
                .containsEntry("summary", "");
        assertThat(versions.get(1))
                .containsEntry("versionNo", 1)
                .containsEntry("source", "patch")
                .containsEntry("summary", "共 1 处");
    }

    @Test
    @DisplayName("未知 action 返回 不支持的操作 错误")
    void 未知action返回错误() {
        ToolResult result = dispatcher.execute(newInput(Map.of(
                "action", "unknown_action",
                "documentId", "doc-x"
        )));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("不支持的操作");
        assertThat(result.error()).contains("unknown_action");
    }

    // ===== 辅助 =====

    private ToolInput newInput(Map<String, Object> params) {
        return new ToolInput("document.edit", params,
                JsonSchema.of(Map.of("type", "object")), null,
                Map.of("sessionId", "sess-1"));
    }
}
