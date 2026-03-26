package com.lifepilot.permission.service;

import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 权限作用域归一化器测试。
 *
 * @author zsg
 * @since 2026-03-26
 */
class PermissionScopeResolverTest {

    private final PermissionScopeResolver resolver = new PermissionScopeResolver();

    @Test
    void 文件运行时作用域应归一到父目录() {
        var scope = resolver.resolveRuntimeScope(
                PermissionActionType.WRITE_FILE,
                new ToolInput(
                        "builtin.file.write",
                        Map.of("path", "D:/WorkSpace/Project/work/hello_output.txt"),
                        JsonSchema.empty(),
                        null,
                        null
                )
        );

        assertThat(scope.get("path")).isEqualTo("D:/WorkSpace/Project/work/hello_output.txt");
        assertThat(scope.get("workspacePath")).isEqualTo("D:/WorkSpace/Project/work");
    }

    @Test
    void 命令执行运行时仅在显式工作目录时记录目录作用域() {
        var scope = resolver.resolveRuntimeScope(
                PermissionActionType.EXECUTE_SHELL,
                new ToolInput(
                        "builtin.shell.exec",
                        Map.of("cwd", "D:/WorkSpace/Project/News"),
                        JsonSchema.empty(),
                        null,
                        null
                )
        );

        assertThat(scope.get("workspacePath")).isEqualTo("D:/WorkSpace/Project/News");
    }

    @Test
    void 点开头目录不应被误判为文件父目录() {
        var scope = resolver.resolveRuntimeScope(
                PermissionActionType.READ_FILE,
                new ToolInput(
                        "builtin.file.info",
                        Map.of("path", "D:/WorkSpace/Project/News/.git"),
                        JsonSchema.empty(),
                        null,
                        null
                )
        );

        assertThat(scope.get("path")).isEqualTo("D:/WorkSpace/Project/News/.git");
        assertThat(scope.get("workspacePath")).isEqualTo("D:/WorkSpace/Project/News/.git");
    }

    @Test
    void 路径授权匹配应遵守目录边界() {
        boolean matched = PermissionScopeMatcher.matches(
                com.lifepilot.permission.model.ExecutionGrantScope.of(
                        Map.of("workspacePath", "D:/WorkSpace/Project/News")
                ),
                com.lifepilot.permission.model.ExecutionGrantScope.of(
                        Map.of("workspacePath", "D:/WorkSpace/Project/NewsBackup")
                )
        );

        assertThat(matched).isFalse();
    }
}
