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
}
