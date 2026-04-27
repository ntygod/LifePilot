package com.lifepilot.sandbox.guard;

import java.util.Set;

import com.lifepilot.sandbox.booter.SandboxBooter;
import com.lifepilot.sandbox.config.SandboxConfigProperties;

/**
 * 命令审查门面 — 组合 HardlineRules / DangerousRules，提供后端类型 bypass 与 yolo 模式。
 *
 * <p>审查顺序：
 * <ol>
 *   <li>CommandGuard 整体禁用 → APPROVED</li>
 *   <li>容器后端（docker） → APPROVED（容器是隔离边界，bypass 全部规则）</li>
 *   <li>HARDLINE 命中 → BLOCKED_HARDLINE（无论 yolo 开关）</li>
 *   <li>yolo 模式开启 → APPROVED（已通过 HARDLINE）</li>
 *   <li>DANGEROUS 命中 → BLOCKED_DANGEROUS</li>
 *   <li>否则 → APPROVED</li>
 * </ol>
 *
 * @author zsg
 * @since 2026-04-26
 */
public class CommandGuard {

    /** 容器后端清单 — 容器作为隔离边界可 bypass 全部规则；未来可扩展 podman / singularity 等。 */
    private static final Set<String> CONTAINER_BACKENDS = Set.of(SandboxBooter.TYPE_DOCKER);

    private final SandboxConfigProperties config;

    public CommandGuard(SandboxConfigProperties config) {
        this.config = config;
    }

    /**
     * 审查命令。
     *
     * @param code        待执行代码（任意语言，guard 仅基于字符串模式）
     * @param booterType  后端类型 "process" | "docker"，docker bypass 全部规则
     * @return 审查结果
     */
    public GuardResult check(String code, String booterType) {
        var guardConfig = config.getRuntime().getCommandGuard();

        if (!guardConfig.isEnabled()) {
            return GuardResult.approved();
        }

        // 容器后端 bypass：booterType 为 null 时按非容器处理（fail-safe，让规则检查接管）
        if (booterType != null && CONTAINER_BACKENDS.contains(booterType)) {
            return GuardResult.approved();
        }

        var hardline = HardlineRules.check(code);
        if (hardline.isBlocked()) {
            return hardline;
        }

        if (guardConfig.isYoloMode()) {
            return GuardResult.approved();
        }

        return DangerousRules.check(code);
    }
}
