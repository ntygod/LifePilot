package com.lifepilot.sandbox.guard;

import org.junit.jupiter.api.Test;

import com.lifepilot.sandbox.config.SandboxConfigProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CommandGuard 主类组合规则测试 — 验证容器 bypass、yolo 模式、整体禁用三类分支。
 *
 * @author zsg
 * @since 2026-04-26
 */
class CommandGuard_容器Bypass测试 {

    @Test
    void Docker后端_HARDLINE也bypass() {
        var config = new SandboxConfigProperties();
        var guard = new CommandGuard(config);
        var result = guard.check("rm -rf /", "docker");
        assertThat(result.decision()).isEqualTo(GuardResult.Decision.APPROVED);
    }

    @Test
    void Process后端_HARDLINE被拦截() {
        var config = new SandboxConfigProperties();
        var guard = new CommandGuard(config);
        var result = guard.check("rm -rf /", "process");
        assertThat(result.decision()).isEqualTo(GuardResult.Decision.BLOCKED_HARDLINE);
    }

    @Test
    void Process后端_yolo开启时DANGEROUS放行() {
        var config = new SandboxConfigProperties();
        config.getRuntime().getCommandGuard().setYoloMode(true);
        var guard = new CommandGuard(config);
        var result = guard.check("git reset --hard HEAD~3", "process");
        assertThat(result.decision()).isEqualTo(GuardResult.Decision.APPROVED);
    }

    @Test
    void Process后端_yolo开启时HARDLINE仍拦截() {
        var config = new SandboxConfigProperties();
        config.getRuntime().getCommandGuard().setYoloMode(true);
        var guard = new CommandGuard(config);
        var result = guard.check("rm -rf /", "process");
        assertThat(result.decision()).isEqualTo(GuardResult.Decision.BLOCKED_HARDLINE);
    }

    @Test
    void CommandGuard禁用时全部放行() {
        var config = new SandboxConfigProperties();
        config.getRuntime().getCommandGuard().setEnabled(false);
        var guard = new CommandGuard(config);
        var result = guard.check("rm -rf /", "process");
        assertThat(result.decision()).isEqualTo(GuardResult.Decision.APPROVED);
    }

    @Test
    void Docker后端_yolo开启时仍走bypass分支() {
        var config = new SandboxConfigProperties();
        config.getRuntime().getCommandGuard().setYoloMode(true);
        var guard = new CommandGuard(config);
        var result = guard.check("rm -rf /", "docker");
        // 验证 docker bypass 早于 yolo 检查
        assertThat(result.decision()).isEqualTo(GuardResult.Decision.APPROVED);
    }

    @Test
    void Process后端_无危险代码直接放行() {
        var config = new SandboxConfigProperties();
        var guard = new CommandGuard(config);
        var result = guard.check("print('hello world')", "process");
        assertThat(result.decision()).isEqualTo(GuardResult.Decision.APPROVED);
    }

    @Test
    void 未知booterType按非容器处理() {
        var config = new SandboxConfigProperties();
        var guard = new CommandGuard(config);
        var result = guard.check("rm -rf /", "firecracker");
        // 未在 CONTAINER_BACKENDS 白名单 → 走规则检查 → HARDLINE 拦截
        assertThat(result.decision()).isEqualTo(GuardResult.Decision.BLOCKED_HARDLINE);
    }

    @Test
    void booterType为null时按非容器处理() {
        var config = new SandboxConfigProperties();
        var guard = new CommandGuard(config);
        var result = guard.check("rm -rf /", null);
        // null 视为非容器（fail-safe），走规则检查 → HARDLINE 拦截
        assertThat(result.decision()).isEqualTo(GuardResult.Decision.BLOCKED_HARDLINE);
    }
}
