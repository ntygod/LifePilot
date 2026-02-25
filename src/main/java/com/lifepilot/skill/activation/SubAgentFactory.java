package com.lifepilot.skill.activation;

import com.lifepilot.agent.AgentLoop;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.AgentResponse;
import com.lifepilot.agent.model.AgentState;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.skill.memory.MemoryAccessEnforcer;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SubAgentResult;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * SubAgent 工厂 — 从 Skill 定义创建隔离的 SubAgent 实例。
 *
 * <p>激活流程：查找 Skill → 检查深度 → 创建独立 Budget → 创建隔离 AgentState
 * → 执行 AgentLoop → 捕获异常返回 SubAgentResult。</p>
 *
 * <p>所有 AgentLoop 执行异常均被捕获，返回 success=false 的 SubAgentResult，
 * 不向上传播异常，确保主 AgentLoop 不受影响。</p>
 *
 * @author zsg
 * @since 2026-07-28
 */
public class SubAgentFactory {

    private static final Logger log = LoggerFactory.getLogger(SubAgentFactory.class);

    /** 最大激活深度，防止无限递归。 */
    private static final int MAX_ACTIVATION_DEPTH = 2;

    private final SkillRegistry skillRegistry;
    private final AgentLoop agentLoop;
    private final DynamicToolRegistry toolRegistry;
    private final MemoryAccessEnforcer memoryAccessEnforcer;

    public SubAgentFactory(SkillRegistry skillRegistry,
                           AgentLoop agentLoop,
                           DynamicToolRegistry toolRegistry,
                           MemoryAccessEnforcer memoryAccessEnforcer) {
        this.skillRegistry = skillRegistry;
        this.agentLoop = agentLoop;
        this.toolRegistry = toolRegistry;
        this.memoryAccessEnforcer = memoryAccessEnforcer;
    }

    /**
     * 激活 Skill 并执行。
     *
     * <p>执行步骤：
     * <ol>
     *   <li>从 SkillRegistry 查找 SkillDefinition，找不到抛 SkillActivationException</li>
     *   <li>检查 parentState.depth() + 1 是否超过 MAX_ACTIVATION_DEPTH</li>
     *   <li>从 SkillBudget 创建独立 Budget</li>
     *   <li>创建隔离 AgentState，traceId 格式：parentTraceId/sub-skillId-randomSuffix</li>
     *   <li>执行 agentLoop.run()</li>
     *   <li>捕获所有异常，返回 success=false 的 SubAgentResult</li>
     * </ol>
     *
     * @param skillId     Skill ID
     * @param input       用户输入
     * @param parentState 父 Agent 状态
     * @return SubAgent 执行结果
     * @throws SkillActivationException 当 Skill 不存在或激活深度超限时
     */
    public SubAgentResult activate(String skillId, String input, AgentState parentState) {
        // 1. 查找 SkillDefinition
        SkillDefinition skill = skillRegistry.find(skillId)
                .orElseThrow(() -> {
                    log.warn("Skill 不存在: skillId={}", skillId);
                    return new SkillActivationException("Skill 不存在: " + skillId);
                });

        // 2. 检查激活深度
        int newDepth = parentState.depth() + 1;
        if (newDepth > MAX_ACTIVATION_DEPTH) {
            log.warn("Skill 激活深度超限: skillId={}, depth={}, maxDepth={}",
                    skillId, newDepth, MAX_ACTIVATION_DEPTH);
            throw new SkillActivationException(
                    "Skill 激活深度超过限制: depth=%d, maxDepth=%d".formatted(newDepth, MAX_ACTIVATION_DEPTH));
        }

        // 3. 创建独立 Budget
        Budget subBudget = skill.budget().toAgentBudget();

        // 4. 创建隔离 AgentState，覆盖 traceId
        String randomSuffix = UUID.randomUUID().toString().substring(0, 8);
        String subTraceId = parentState.traceId() + "/sub-" + skillId + "-" + randomSuffix;
        AgentState subState = AgentState.forSubAgent(parentState, input, subBudget)
                .toBuilder()
                .traceId(subTraceId)
                .build();

        // 5. 记录开始时间并执行
        long startTime = System.currentTimeMillis();
        try {
            AgentRequest subRequest = new AgentRequest(input, parentState.sessionId(), parentState.channel());
            AgentResponse response = agentLoop.run(subRequest);
            long durationMs = System.currentTimeMillis() - startTime;

            log.info("Skill 激活成功: skillId={}, traceId={}, durationMs={}", skillId, subTraceId, durationMs);

            // 6. 构建成功的 SubAgentResult
            return SubAgentResult.builder()
                    .skillId(skillId)
                    .success(true)
                    .output(response.content())
                    .terminationReason(response.terminationReason())
                    .tokensUsed(response.tokensUsed())
                    .stepsExecuted(response.stepCount())
                    .durationMs(durationMs)
                    .traceId(subTraceId)
                    .build();

        } catch (Exception e) {
            // 7. 捕获所有异常，返回失败的 SubAgentResult
            long durationMs = System.currentTimeMillis() - startTime;
            log.warn("Skill 激活执行异常: skillId={}, traceId={}, error={}",
                    skillId, subTraceId, e.getMessage(), e);

            return SubAgentResult.builder()
                    .skillId(skillId)
                    .success(false)
                    .output("Skill 执行失败: " + e.getMessage())
                    .terminationReason("异常终止: " + e.getClass().getSimpleName())
                    .tokensUsed(0)
                    .stepsExecuted(0)
                    .durationMs(durationMs)
                    .traceId(subTraceId)
                    .build();
        }
    }
}
