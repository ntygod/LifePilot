package com.lifepilot.meta.infra.git;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.BuiltinTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link GitToolProvider} 单元测试。
 *
 * @author zsg
 * @since 2026-03-31
 */
class GitToolProviderTest {

    private GitToolProvider provider;

    @BeforeEach
    void setUp() {
        var gitConfig = new MetaProperties.Infra.Git();
        var gitCmd = mock(GitCommandExecutor.class);
        provider = new GitToolProvider(gitCmd, gitConfig);
    }

    @Test
    void buildGitTools_应返回2个统一工具() {
        List<BuiltinTool> tools = provider.buildGitTools();
        assertThat(tools).hasSize(2);
    }

    @Test
    void 每个工具的id和inputSchema应正确() {
        List<BuiltinTool> tools = provider.buildGitTools();

        Set<String> ids = tools.stream()
                .map(BuiltinTool::id)
                .collect(Collectors.toSet());

        assertThat(ids).containsExactlyInAnyOrder(
                "git.query",
                "git.mutate"
        );

        for (BuiltinTool tool : tools) {
            assertThat(tool.inputSchema()).isNotNull();
            assertThat(tool.inputSchema().toMap()).isNotEmpty();
            assertThat(tool.inputSchema().toMap()).containsKey("type");
        }
    }

    @Test
    void 所有工具应包含infrastructure标签() {
        List<BuiltinTool> tools = provider.buildGitTools();

        for (BuiltinTool tool : tools) {
            assertThat(tool.tags()).contains("infrastructure");
        }
    }

    @Test
    void query工具应为PARALLEL_SAFE() {
        List<BuiltinTool> tools = provider.buildGitTools();

        BuiltinTool queryTool = tools.stream()
                .filter(t -> "git.query".equals(t.id()))
                .findFirst()
                .orElseThrow();

        assertThat(queryTool.executionSemantics().schedulingMode())
                .isEqualTo(com.lifepilot.tool.model.ToolSchedulingMode.PARALLEL_SAFE);
    }

    @Test
    void mutate工具应为SEQUENTIAL() {
        List<BuiltinTool> tools = provider.buildGitTools();

        BuiltinTool mutateTool = tools.stream()
                .filter(t -> "git.mutate".equals(t.id()))
                .findFirst()
                .orElseThrow();

        assertThat(mutateTool.executionSemantics().schedulingMode())
                .isEqualTo(com.lifepilot.tool.model.ToolSchedulingMode.SEQUENTIAL);
    }

    @Test
    void mutate工具风险等级应为HIGH() {
        List<BuiltinTool> tools = provider.buildGitTools();

        BuiltinTool mutateTool = tools.stream()
                .filter(t -> "git.mutate".equals(t.id()))
                .findFirst()
                .orElseThrow();

        assertThat(mutateTool.riskLevel())
                .isEqualTo(com.lifepilot.observability.guardrail.RiskLevel.HIGH);
    }

    @Test
    void query工具inputSchema应包含action为必需参数() {
        List<BuiltinTool> tools = provider.buildGitTools();

        BuiltinTool queryTool = tools.stream()
                .filter(t -> "git.query".equals(t.id()))
                .findFirst()
                .orElseThrow();

        assertThat(queryTool.inputSchema().requiredFields()).contains("action");
    }

    @Test
    void mutate工具inputSchema应包含action为必需参数() {
        List<BuiltinTool> tools = provider.buildGitTools();

        BuiltinTool mutateTool = tools.stream()
                .filter(t -> "git.mutate".equals(t.id()))
                .findFirst()
                .orElseThrow();

        assertThat(mutateTool.inputSchema().requiredFields()).contains("action");
    }
}
