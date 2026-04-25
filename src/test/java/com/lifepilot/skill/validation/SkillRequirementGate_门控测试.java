package com.lifepilot.skill.validation;

import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.skill.spec.SkillRequires;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

/**
 * SkillRequirementGate 加载期硬过滤测试。
 *
 * @author zsg
 * @since 2026-04-24
 */
@ExtendWith(MockitoExtension.class)
class SkillRequirementGate_门控测试 {

    @Mock DynamicToolRegistry toolRegistry;

    /** 一个最小可用的工具实例，用于 resolve 返回 present 的场景。 */
    private static final BuiltinTool FAKE_TOOL = BuiltinTool.builder()
            .id("gh.pr.create")
            .name("gh-pr-create")
            .description("创建 PR")
            .riskLevel(RiskLevel.LOW)
            .idempotent(false)
            .executionSemantics(ToolExecutionSemantics.generic())
            .executor(input -> ToolResult.success(Map.of()))
            .build();

    @Test
    void 无依赖应通过() {
        var gate = new SkillRequirementGate(toolRegistry, () -> "linux", name -> true, key -> "val");
        assertThat(gate.satisfies(SkillRequires.empty())).isTrue();
    }

    @Test
    void 缺少bin应拒绝() {
        var gate = new SkillRequirementGate(toolRegistry, () -> "linux", name -> false, key -> "val");
        var req = new SkillRequires(List.of("nonexistent"), List.of(), List.of(), List.of());
        assertThat(gate.satisfies(req)).isFalse();
    }

    @Test
    void 全部bin满足应通过() {
        var gate = new SkillRequirementGate(toolRegistry, () -> "linux", name -> true, key -> "val");
        var req = new SkillRequires(List.of("git", "gh"), List.of(), List.of(), List.of());
        assertThat(gate.satisfies(req)).isTrue();
    }

    @Test
    void 缺少env应拒绝() {
        var gate = new SkillRequirementGate(toolRegistry, () -> "linux", name -> true, key -> null);
        var req = new SkillRequires(List.of(), List.of("GITHUB_TOKEN"), List.of(), List.of());
        assertThat(gate.satisfies(req)).isFalse();
    }

    @Test
    void os不匹配应拒绝() {
        var gate = new SkillRequirementGate(toolRegistry, () -> "windows", name -> true, key -> "v");
        var req = new SkillRequires(List.of(), List.of(), List.of("linux"), List.of());
        assertThat(gate.satisfies(req)).isFalse();
    }

    @Test
    void os匹配应通过() {
        var gate = new SkillRequirementGate(toolRegistry, () -> "linux", name -> true, key -> "v");
        var req = new SkillRequires(List.of(), List.of(), List.of("windows", "darwin", "linux"), List.of());
        assertThat(gate.satisfies(req)).isTrue();
    }

    @Test
    void 缺少tool应拒绝() {
        lenient().when(toolRegistry.resolve("gh.pr.create")).thenReturn(Optional.empty());
        var gate = new SkillRequirementGate(toolRegistry, () -> "linux", n -> true, k -> "v");
        var req = new SkillRequires(List.of(), List.of(), List.of(), List.of("gh.pr.create"));
        assertThat(gate.satisfies(req)).isFalse();
    }

    @Test
    void tool满足应通过() {
        lenient().when(toolRegistry.resolve("gh.pr.create")).thenReturn(Optional.of(FAKE_TOOL));
        var gate = new SkillRequirementGate(toolRegistry, () -> "linux", n -> true, k -> "v");
        var req = new SkillRequires(List.of(), List.of(), List.of(), List.of("gh.pr.create"));
        assertThat(gate.satisfies(req)).isTrue();
    }

    @Test
    void 非法bin名应立即拒绝_不fork子进程() {
        // 使用生产构造器（走缓存 + 白名单 + ProcessBuilder 探测）
        var gate = new SkillRequirementGate(toolRegistry);

        // 含 / 的路径 —— 白名单拒绝
        var withSlash = new SkillRequires(List.of("/bin/sh"), List.of(), List.of(), List.of());
        assertThat(gate.satisfies(withSlash)).isFalse();

        // 超过 64 字符 —— 白名单拒绝
        String longName = "a".repeat(65);
        var tooLong = new SkillRequires(List.of(longName), List.of(), List.of(), List.of());
        assertThat(gate.satisfies(tooLong)).isFalse();

        // 空串 —— 白名单拒绝
        var empty = new SkillRequires(List.of(""), List.of(), List.of(), List.of());
        assertThat(gate.satisfies(empty)).isFalse();

        // 含引号等控制字符 —— 白名单拒绝
        var withQuote = new SkillRequires(List.of("bad\"name"), List.of(), List.of(), List.of());
        assertThat(gate.satisfies(withQuote)).isFalse();
    }
}
