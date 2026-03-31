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
    void buildGitTools_应返回7个工具() {
        List<BuiltinTool> tools = provider.buildGitTools();
        assertThat(tools).hasSize(7);
    }

    @Test
    void 每个工具的id和inputSchema应正确() {
        List<BuiltinTool> tools = provider.buildGitTools();

        Set<String> ids = tools.stream()
                .map(BuiltinTool::id)
                .collect(Collectors.toSet());

        assertThat(ids).containsExactlyInAnyOrder(
                "git.status",
                "git.diff",
                "git.log",
                "git.commit",
                "git.blame",
                "git.stash",
                "git.branch"
        );

        // 每个工具都应该有非空的 inputSchema
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
    void 读操作工具应为PARALLEL_SAFE() {
        List<BuiltinTool> tools = provider.buildGitTools();

        Set<String> readToolIds = Set.of("git.status", "git.diff", "git.log", "git.blame");
        for (BuiltinTool tool : tools) {
            if (readToolIds.contains(tool.id())) {
                assertThat(tool.executionSemantics().schedulingMode())
                        .as("工具 %s 应为 PARALLEL_SAFE", tool.id())
                        .isEqualTo(com.lifepilot.tool.model.ToolSchedulingMode.PARALLEL_SAFE);
            }
        }
    }

    @Test
    void 写操作工具应为SEQUENTIAL() {
        List<BuiltinTool> tools = provider.buildGitTools();

        Set<String> writeToolIds = Set.of("git.commit", "git.stash", "git.branch");
        for (BuiltinTool tool : tools) {
            if (writeToolIds.contains(tool.id())) {
                assertThat(tool.executionSemantics().schedulingMode())
                        .as("工具 %s 应为 SEQUENTIAL", tool.id())
                        .isEqualTo(com.lifepilot.tool.model.ToolSchedulingMode.SEQUENTIAL);
            }
        }
    }

    @Test
    void commit工具风险等级应为HIGH() {
        List<BuiltinTool> tools = provider.buildGitTools();

        BuiltinTool commitTool = tools.stream()
                .filter(t -> "git.commit".equals(t.id()))
                .findFirst()
                .orElseThrow();

        assertThat(commitTool.riskLevel())
                .isEqualTo(com.lifepilot.observability.guardrail.RiskLevel.HIGH);
    }

    @Test
    void stash和branch工具风险等级应为MEDIUM() {
        List<BuiltinTool> tools = provider.buildGitTools();

        Set<String> mediumRiskIds = Set.of("git.stash", "git.branch");
        for (BuiltinTool tool : tools) {
            if (mediumRiskIds.contains(tool.id())) {
                assertThat(tool.riskLevel())
                        .as("工具 %s 风险等级应为 MEDIUM", tool.id())
                        .isEqualTo(com.lifepilot.observability.guardrail.RiskLevel.MEDIUM);
            }
        }
    }

    @Test
    void commit工具inputSchema应包含message为必需参数() {
        List<BuiltinTool> tools = provider.buildGitTools();

        BuiltinTool commitTool = tools.stream()
                .filter(t -> "git.commit".equals(t.id()))
                .findFirst()
                .orElseThrow();

        assertThat(commitTool.inputSchema().requiredFields()).contains("message");
    }

    @Test
    void blame工具inputSchema应包含filePath为必需参数() {
        List<BuiltinTool> tools = provider.buildGitTools();

        BuiltinTool blameTool = tools.stream()
                .filter(t -> "git.blame".equals(t.id()))
                .findFirst()
                .orElseThrow();

        assertThat(blameTool.inputSchema().requiredFields()).contains("filePath");
    }
}
