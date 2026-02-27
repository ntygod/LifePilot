package com.lifepilot.observability.redactor;

import java.util.List;

/**
 * 脱敏审计结果 — 记录脱敏后的文本和应用的规则。
 *
 * @param redactedText 脱敏后的文本
 * @param appliedRules 实际匹配并应用的规则名称列表
 * @author zsg
 * @since 2026-02-27
 */
public record RedactionAudit(
        String redactedText,
        List<String> appliedRules
) {

    /**
     * 紧凑构造函数 — 使用 List.copyOf() 保证不可变性。
     */
    public RedactionAudit {
        appliedRules = List.copyOf(appliedRules);
    }
}
