package com.lifepilot.guardrail;

import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.model.RiskLevel;
import com.lifepilot.tool.model.ToolInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 护栏策略 — 工具调用安全检查。
 *
 * <p>以代码定义安全策略，在 LLM 之外强制执行。
 * 即使 LLM 被越狱或产生幻觉，护栏仍然能阻止危险操作。</p>
 *
 * @author zsg
 * @since 2026-02-24
 */
public class GuardrailPolicy {

    private static final Logger log = LoggerFactory.getLogger(GuardrailPolicy.class);

    /** 工具白名单。 */
    private final Set<String> allowedTools = ConcurrentHashMap.newKeySet();

    /** 工具黑名单（优先于白名单）。 */
    private final Set<String> blockedTools = ConcurrentHashMap.newKeySet();

    /**
     * 检查工具调用是否允许。
     *
     * @param tool 工具契约
     * @param input 工具输入
     * @return 护栏检查结果
     */
    public GuardrailResult checkToolCall(ToolContract tool, ToolInput input) {
        // 1. 黑名单检查
        if (blockedTools.contains(tool.id())) {
            log.warn("工具在黑名单中: toolId={}", tool.id());
            return GuardrailResult.blocked("工具在黑名单中: " + tool.id());
        }

        // 2. 白名单检查
        if (!allowedTools.contains(tool.id())) {
            log.warn("工具不在白名单中: toolId={}", tool.id());
            return GuardrailResult.blocked("工具不在白名单中: " + tool.id());
        }

        // 3. 风险等级审批
        RiskLevel risk = tool.riskLevel();
        if (risk.requiresConfirmation()) {
            String message = "Agent 请求执行 %s 操作:\n工具: %s\n描述: %s\n风险等级: %s"
                    .formatted(risk, tool.name(), tool.description(), risk);
            return GuardrailResult.requiresConfirmation(
                    message, risk.requiresSecondaryVerification());
        }

        return GuardrailResult.allowed();
    }

    /** 添加允许的工具。 */
    public void addAllowedTools(List<String> toolIds) {
        allowedTools.addAll(toolIds);
        log.debug("工具白名单更新: added={}", toolIds);
    }

    /** 移除允许的工具。 */
    public void removeAllowedTools(List<String> toolIds) {
        toolIds.forEach(allowedTools::remove);
        log.debug("工具白名单移除: removed={}", toolIds);
    }

    /** 添加黑名单工具。 */
    public void addBlockedTools(List<String> toolIds) {
        blockedTools.addAll(toolIds);
        log.debug("工具黑名单更新: added={}", toolIds);
    }
}
