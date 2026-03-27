package com.lifepilot.permission.service;

import com.lifepilot.permission.model.ExecutionGrantScope;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolScopeResolvers;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 权限作用域匹配器测试。
 *
 * @author zsg
 * @since 2026-03-26
 */
class PermissionScopeMatcherTest {

    @Test
    void 文件路径授权应遵守目录边界() {
        boolean matched = PermissionScopeMatcher.matches(
                ExecutionGrantScope.of(Map.of("workspacePaths", "D:/WorkSpace/Project/News")),
                ExecutionGrantScope.of(Map.of("workspacePaths", "D:/WorkSpace/Project/NewsBackup"))
        );

        assertThat(matched).isFalse();
    }

    @Test
    void 多资源请求要求每个资源都被授权覆盖() {
        boolean matched = PermissionScopeMatcher.matches(
                ExecutionGrantScope.of(Map.of("paths", java.util.List.of(
                        "D:/WorkSpace/Project/News/a.txt",
                        "D:/WorkSpace/Project/News/b.txt"
                ))),
                ExecutionGrantScope.of(Map.of("paths", java.util.List.of(
                        "D:/WorkSpace/Project/News/a.txt",
                        "D:/WorkSpace/Project/News/c.txt"
                )))
        );

        assertThat(matched).isFalse();
    }

    @Test
    void 文件资源解析器应同时产出路径与工作区路径() {
        var resolution = ToolScopeResolvers.paths("source", "destination").resolve(new ToolInput(
                "file.copy",
                Map.of(
                        "source", "D:/WorkSpace/Project/work/a.txt",
                        "destination", "D:/WorkSpace/Project/work/out/b.txt"
                ),
                JsonSchema.empty(),
                null,
                null
        ));

        assertThat(resolution.scope().stringValues("paths"))
                .containsExactly(
                        "D:/WorkSpace/Project/work/a.txt",
                        "D:/WorkSpace/Project/work/out/b.txt"
                );
        assertThat(resolution.scope().stringValues("workspacePaths"))
                .containsExactly(
                        "D:/WorkSpace/Project/work",
                        "D:/WorkSpace/Project/work/out"
                );
        assertThat(resolution.normalizedResources())
                .containsExactly(
                        "path:D:/WorkSpace/Project/work/a.txt",
                        "path:D:/WorkSpace/Project/work/out/b.txt"
                );
    }
}
