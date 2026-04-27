package com.lifepilot.sandbox.guard;

/**
 * 命令审查结果。
 *
 * @author zsg
 * @since 2026-04-26
 */
public record GuardResult(Decision decision, String matchedRule, String description) {

    public enum Decision { APPROVED, BLOCKED_HARDLINE, BLOCKED_DANGEROUS }

    public static GuardResult approved() {
        return new GuardResult(Decision.APPROVED, null, null);
    }

    public static GuardResult hardline(String rule, String description) {
        return new GuardResult(Decision.BLOCKED_HARDLINE, rule, description);
    }

    public static GuardResult dangerous(String rule, String description) {
        return new GuardResult(Decision.BLOCKED_DANGEROUS, rule, description);
    }

    public boolean isBlocked() {
        return decision != Decision.APPROVED;
    }
}
