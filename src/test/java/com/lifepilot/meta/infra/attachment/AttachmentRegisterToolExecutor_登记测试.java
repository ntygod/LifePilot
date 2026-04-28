package com.lifepilot.meta.infra.attachment;

import com.lifepilot.config.workspace.WorkspaceResolver;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.tool.model.ToolContextKeys;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AttachmentRegisterToolExecutor 单元测试。
 *
 * @author zsg
 * @since 2026-04-28
 */
@DisplayName("attachment.register 登记")
class AttachmentRegisterToolExecutor_登记测试 {

    private static final String SESSION_ID = "session-test";

    private final AttachmentRepository repository = mock(AttachmentRepository.class);

    @Test
    void 成功登记_workspace_内的文件_并返回_attachmentId(@TempDir Path workspace) throws Exception {
        var resolver = new TestWorkspaceResolver(workspace);
        Path file = workspace.resolve("chart.png");
        Files.writeString(file, "fake-png-bytes");

        when(repository.saveForEntry(isNull(), eq(SESSION_ID), eq("chart.png"),
                eq(file.toString()), anyLong(), any(), isNull()))
                .thenReturn("att-123");

        var executor = new AttachmentRegisterToolExecutor(repository, resolver);
        ToolResult result = executor.execute(input(Map.of("path", file.toString())));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.data().get("attachmentId")).isEqualTo("att-123");
        assertThat(result.data().get("fileName")).isEqualTo("chart.png");
        assertThat((Long) result.data().get("size")).isGreaterThan(0L);
        // entryId=null orphan 模式 —— 后续 backfillOrphanEntryIds 关联
        verify(repository).saveForEntry(isNull(), eq(SESSION_ID), eq("chart.png"),
                eq(file.toString()), anyLong(), any(), isNull());
    }

    @Test
    void 拒绝_workspace_外的路径(@TempDir Path workspace, @TempDir Path outside) throws Exception {
        var resolver = new TestWorkspaceResolver(workspace);
        Path foreign = outside.resolve("foreign.png");
        Files.writeString(foreign, "x");

        var executor = new AttachmentRegisterToolExecutor(repository, resolver);
        ToolResult result = executor.execute(input(Map.of("path", foreign.toString())));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("仅允许登记 workspace 目录");
    }

    @Test
    void 拒绝不存在的文件(@TempDir Path workspace) {
        var resolver = new TestWorkspaceResolver(workspace);
        Path missing = workspace.resolve("missing.png");

        var executor = new AttachmentRegisterToolExecutor(repository, resolver);
        ToolResult result = executor.execute(input(Map.of("path", missing.toString())));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("文件不存在");
    }

    @Test
    void 拒绝空文件(@TempDir Path workspace) throws Exception {
        var resolver = new TestWorkspaceResolver(workspace);
        Path empty = workspace.resolve("empty.png");
        Files.createFile(empty);

        var executor = new AttachmentRegisterToolExecutor(repository, resolver);
        ToolResult result = executor.execute(input(Map.of("path", empty.toString())));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("空文件");
    }

    @Test
    void 缺_sessionId_上下文返回错误(@TempDir Path workspace) throws Exception {
        var resolver = new TestWorkspaceResolver(workspace);
        var executor = new AttachmentRegisterToolExecutor(repository, resolver);
        ToolResult result = executor.execute(new ToolInput(
                "attachment.register", Map.of("path", "x"),
                JsonSchema.of(Map.of()), null, null));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("会话上下文");
    }

    @Test
    void 缺_path_参数返回错误(@TempDir Path workspace) {
        var resolver = new TestWorkspaceResolver(workspace);
        var executor = new AttachmentRegisterToolExecutor(repository, resolver);
        ToolResult result = executor.execute(input(Map.of()));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("path");
    }

    @Test
    void displayName_覆盖文件名(@TempDir Path workspace) throws Exception {
        var resolver = new TestWorkspaceResolver(workspace);
        Path file = workspace.resolve("ww.png");
        Files.writeString(file, "data");

        when(repository.saveForEntry(isNull(), eq(SESSION_ID), eq("鸣潮趋势图.png"),
                any(), anyLong(), any(), isNull())).thenReturn("att-456");

        var executor = new AttachmentRegisterToolExecutor(repository, resolver);
        ToolResult result = executor.execute(input(Map.of(
                "path", file.toString(),
                "displayName", "鸣潮趋势图.png")));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.data().get("fileName")).isEqualTo("鸣潮趋势图.png");
    }

    private ToolInput input(Map<String, Object> params) {
        return new ToolInput("attachment.register", params, JsonSchema.of(Map.of()),
                null, Map.of(ToolContextKeys.SESSION_ID, SESSION_ID));
    }

    /**
     * 测试用 WorkspaceResolver —— 直接返回固定路径，避免依赖 UserSettingsRepository / yaml。
     */
    private static final class TestWorkspaceResolver extends WorkspaceResolver {
        private final Path root;

        TestWorkspaceResolver(Path root) {
            super(null, root.toString());
            this.root = root;
        }

        @Override
        public Path resolve() {
            return root;
        }
    }
}
