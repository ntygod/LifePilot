package com.lifepilot.skill.bridge;

import com.lifepilot.agent.model.AgentState;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.skill.activation.SkillLifecycleManager;
import com.lifepilot.skill.event.SkillRegistryEvent;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SubAgentResult;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Skill 工具桥接 — 监听 SkillRegistryEvent，将 Skill 包装为 BuiltinTool 注册到 DynamicToolRegistry。
 *
 * <p>实现 Sub-Agent-as-Tools 范式：每个已注册的 Skill 在 DynamicToolRegistry 中
 * 以 "skill.{skillId}" 为 ID 注册为 BuiltinTool，executor lambda 委托给
 * {@link SkillLifecycleManager#activate} 执行。</p>
 *
 * @author zsg
 * @since 2026-07-28
 */
public class SkillToToolBridge {

    private static final Logger log = LoggerFactory.getLogger(SkillToToolBridge.class);

    /** 工具 ID 前缀。 */
    private static final String TOOL_ID_PREFIX = "skill.";

    private final DynamicToolRegistry toolRegistry;
    private final SkillLifecycleManager lifecycleManager;

    public SkillToToolBridge(DynamicToolRegistry toolRegistry,
                             SkillLifecycleManager lifecycleManager) {
        this.toolRegistry = toolRegistry;
        this.lifecycleManager = lifecycleManager;
    }

    /**
     * 监听 SkillRegistered 事件，创建 BuiltinTool 并注册到 DynamicToolRegistry。
     *
     * <p>工具 ID 格式：skill.{skillId}。executor lambda 从 ToolInput 提取 "input" 参数，
     * 创建最小 AgentState 后委托给 {@link SkillLifecycleManager#activate}，
     * 将 {@link SubAgentResult} 转换为 {@link ToolResult} 返回。</p>
     *
     * @param event Skill 注册事件
     */
    @EventListener
    public void onSkillRegistered(SkillRegistryEvent.SkillRegistered event) {
        SkillDefinition definition = event.definition();
        String toolId = TOOL_ID_PREFIX + definition.id();

        BuiltinTool tool = BuiltinTool.builder()
                .id(toolId)
                .name(definition.name())
                .description(definition.description())
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .tags(List.of("skill"))
                .executor(input -> {
                    try {
                        // 从 ToolInput 提取用户输入
                        String userInput = input.getOptionalParam("input", String.class)
                                .orElse("");

                        // 创建最小 AgentState 用于激活调用
                        AgentState minimalState = AgentState.builder()
                                .traceId(UUID.randomUUID().toString())
                                .sessionId("bridge-" + UUID.randomUUID().toString().substring(0, 8))
                                .goal(userInput)
                                .phase(com.lifepilot.agent.model.AgentPhase.UNDERSTANDING)
                                .channel("internal")
                                .steps(List.of())
                                .stepCount(0)
                                .plan(null)
                                .planStepIndex(0)
                                .revisionCount(0)
                                .shortTermMemory(List.of())
                                .mentionedEntities(List.of())
                                .budget(Budget.defaultBudget())
                                .parentTraceId(null)
                                .depth(0)
                                .done(false)
                                .finalOutput(null)
                                .terminationReason(null)
                                .build();

                        // 委托 SkillLifecycleManager 激活 Skill
                        SubAgentResult result = lifecycleManager.activate(
                                definition.id(), userInput, minimalState);

                        // SubAgentResult → ToolResult 转换
                        return convertToToolResult(result);
                    } catch (Exception e) {
                        log.error("Skill 工具执行失败: toolId={}, error={}", toolId, e.getMessage(), e);
                        return ToolResult.error("Skill 执行失败: " + e.getMessage());
                    }
                })
                .build();

        toolRegistry.registerBuiltinTool(tool);
        log.info("Skill 工具桥接注册成功: skillId={}, toolId={}", definition.id(), toolId);
    }

    /**
     * 监听 SkillUnregistered 事件，注销对应工具。
     *
     * <p>由于 {@link DynamicToolRegistry} 未提供 unregisterBuiltinTool 方法，
     * 当前仅记录 WARN 日志，工具将在注册中心中保留为孤立条目。</p>
     *
     * @param event Skill 注销事件
     */
    @EventListener
    public void onSkillUnregistered(SkillRegistryEvent.SkillUnregistered event) {
        String toolId = TOOL_ID_PREFIX + event.skillId();
        log.warn("Skill 工具注销请求: toolId={}，DynamicToolRegistry 不支持单个 BuiltinTool 注销，工具将保留为孤立条目",
                toolId);
    }

    /**
     * 监听 SkillUpdated 事件，先注销旧工具再注册新工具。
     *
     * <p>由于无法真正注销旧工具，实际行为是用新定义重新注册（覆盖）。</p>
     *
     * @param event Skill 更新事件
     */
    @EventListener
    public void onSkillUpdated(SkillRegistryEvent.SkillUpdated event) {
        // 先尝试注销旧工具（实际只记录日志）
        onSkillUnregistered(new SkillRegistryEvent.SkillUnregistered(event.oldDefinition().id()));
        // 注册新工具（覆盖旧工具）
        onSkillRegistered(new SkillRegistryEvent.SkillRegistered(event.newDefinition()));
        log.info("Skill 工具桥接更新完成: skillId={}", event.newDefinition().id());
    }

    /**
     * 将 SubAgentResult 转换为 ToolResult。
     *
     * @param result SubAgent 执行结果
     * @return 工具执行结果
     */
    private ToolResult convertToToolResult(SubAgentResult result) {
        if (result.success()) {
            return ToolResult.success(Map.of(
                    "output", result.output(),
                    "tokensUsed", result.tokensUsed()
            ));
        } else {
            return ToolResult.error(result.output());
        }
    }
}
