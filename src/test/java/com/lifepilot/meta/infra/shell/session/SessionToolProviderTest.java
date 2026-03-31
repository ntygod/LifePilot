package com.lifepilot.meta.infra.shell.session;

import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolSchedulingMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link SessionToolProvider} 单元测试。
 *
 * @author zsg
 * @since 2026-03-31
 */
class SessionToolProviderTest {

    private SessionToolProvider provider;

    @BeforeEach
    void setUp() {
        var sessionManager = mock(TmuxSessionManager.class);
        provider = new SessionToolProvider(sessionManager);
    }

    @Test
    void buildSessionTools_应返回8个工具() {
        List<BuiltinTool> tools = provider.buildSessionTools();
        assertThat(tools).hasSize(8);
    }

    @Test
    void 每个工具的id和inputSchema应正确() {
        List<BuiltinTool> tools = provider.buildSessionTools();

        Set<String> ids = tools.stream()
                .map(BuiltinTool::id)
                .collect(Collectors.toSet());

        assertThat(ids).containsExactlyInAnyOrder(
                "shell.session.create",
                "shell.session.exec",
                "shell.session.write",
                "shell.session.read",
                "shell.session.signal",
                "shell.session.list",
                "shell.session.close",
                "shell.session.resize"
        );

        // 每个工具都应该有非空的 inputSchema
        for (BuiltinTool tool : tools) {
            assertThat(tool.inputSchema()).isNotNull();
        }
    }

    @Test
    void 所有工具应包含infrastructure标签() {
        List<BuiltinTool> tools = provider.buildSessionTools();

        for (BuiltinTool tool : tools) {
            assertThat(tool.tags()).contains("infrastructure");
        }
    }

    @Test
    void 读操作工具应为PARALLEL_SAFE() {
        List<BuiltinTool> tools = provider.buildSessionTools();

        Set<String> readToolIds = Set.of("shell.session.read", "shell.session.list");
        for (BuiltinTool tool : tools) {
            if (readToolIds.contains(tool.id())) {
                assertThat(tool.executionSemantics().schedulingMode())
                        .as("工具 %s 应为 PARALLEL_SAFE", tool.id())
                        .isEqualTo(ToolSchedulingMode.PARALLEL_SAFE);
            }
        }
    }

    @Test
    void 写操作工具应为SEQUENTIAL() {
        List<BuiltinTool> tools = provider.buildSessionTools();

        Set<String> writeToolIds = Set.of(
                "shell.session.create", "shell.session.exec", "shell.session.write",
                "shell.session.signal", "shell.session.close", "shell.session.resize"
        );
        for (BuiltinTool tool : tools) {
            if (writeToolIds.contains(tool.id())) {
                assertThat(tool.executionSemantics().schedulingMode())
                        .as("工具 %s 应为 SEQUENTIAL", tool.id())
                        .isEqualTo(ToolSchedulingMode.SEQUENTIAL);
            }
        }
    }

    @Test
    void HIGH风险工具应正确标记() {
        List<BuiltinTool> tools = provider.buildSessionTools();

        Set<String> highRiskIds = Set.of("shell.session.create", "shell.session.exec", "shell.session.signal");
        for (BuiltinTool tool : tools) {
            if (highRiskIds.contains(tool.id())) {
                assertThat(tool.riskLevel())
                        .as("工具 %s 风险等级应为 HIGH", tool.id())
                        .isEqualTo(com.lifepilot.observability.guardrail.RiskLevel.HIGH);
            }
        }
    }

    @Test
    void MEDIUM风险工具应正确标记() {
        List<BuiltinTool> tools = provider.buildSessionTools();

        Set<String> mediumRiskIds = Set.of("shell.session.write", "shell.session.close");
        for (BuiltinTool tool : tools) {
            if (mediumRiskIds.contains(tool.id())) {
                assertThat(tool.riskLevel())
                        .as("工具 %s 风险等级应为 MEDIUM", tool.id())
                        .isEqualTo(com.lifepilot.observability.guardrail.RiskLevel.MEDIUM);
            }
        }
    }

    @Test
    void exec工具inputSchema应包含sessionId和command为必需参数() {
        List<BuiltinTool> tools = provider.buildSessionTools();

        BuiltinTool execTool = tools.stream()
                .filter(t -> "shell.session.exec".equals(t.id()))
                .findFirst()
                .orElseThrow();

        assertThat(execTool.inputSchema().requiredFields())
                .contains("sessionId", "command");
    }
}
