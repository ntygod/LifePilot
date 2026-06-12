package com.lifepilot.agent.intelligence;

import com.lifepilot.agent.intelligence.model.DecisionSignal;
import com.lifepilot.agent.intelligence.model.EnvironmentState;
import com.lifepilot.agent.intelligence.model.ToolHealth;
import com.lifepilot.memory.store.procedural.IntentMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 自适应决策引擎 — 作为"决策顾问"增强 LLM 的决策上下文。
 *
 * <p><b>核心定位</b>：不替代 LLM 决策，而是将经验/能力/环境信号
 * 结构化地注入 ContextAssembler 的上下文中，让 LLM 基于更丰富的
 * 信息做出更好的决策。</p>
 *
 * <p>注入的信号包括：
 * <ul>
 *   <li>历史经验：类似任务的成功/失败模式（来自 IntentMatcher）</li>
 *   <li>能力评估：当前工具的可靠性评分（来自 CapabilityAssessor）</li>
 *   <li>环境状态：时间、用户状态（来自 EnvironmentPerceptor）</li>
 *   <li>风险预警：检测到的潜在风险因素</li>
 * </ul></p>
 *
 * @author zsg
 * @since 2026-06-01
 */
public class AdaptiveDecisionEngine {

    private static final Logger log = LoggerFactory.getLogger(AdaptiveDecisionEngine.class);
    private static final float MIN_EXPERIENCE_CONFIDENCE = 0.6f;

    private final CapabilityAssessor capabilityAssessor;
    private final EnvironmentPerceptor environmentPerceptor;
    @Nullable
    private final IntentMatcher intentMatcher;

    public AdaptiveDecisionEngine(CapabilityAssessor capabilityAssessor,
                                  EnvironmentPerceptor environmentPerceptor,
                                  @Nullable IntentMatcher intentMatcher) {
        this.capabilityAssessor = capabilityAssessor;
        this.environmentPerceptor = environmentPerceptor;
        this.intentMatcher = intentMatcher;
    }

    /**
     * 构建决策增强信号 — 在 ContextAssembler.assemble() 中调用。
     *
     * <p>返回结构化的决策信号，由 ContextAssembler 格式化后注入系统提示词。</p>
     *
     * @param goal             用户目标/意图文本
     * @param availableToolIds 当前可用工具集
     * @return 决策信号（可能为空信号，表示无额外建议）
     */
    public DecisionSignal buildDecisionSignal(String goal, Set<String> availableToolIds) {
        try {
            // 1. 匹配历史经验
            var experienceHint = matchExperience(goal);

            // 2. 评估工具能力
            var toolHints = assessTools(availableToolIds);

            // 3. 感知环境
            var environment = environmentPerceptor.perceive(availableToolIds);
            var environmentHint = formatEnvironmentHint(environment);

            // 4. 检测风险
            var risks = detectRisks(availableToolIds, environment);

            var signal = new DecisionSignal(experienceHint, toolHints, risks, environmentHint);

            if (!signal.isEmpty()) {
                log.debug("决策引擎: 生成信号, experience={}, tools={}, risks={}",
                        experienceHint != null ? experienceHint.templateName() : "none",
                        toolHints.size(), risks.size());
            }

            return signal;
        } catch (Exception e) {
            log.warn("决策引擎: 构建信号失败，降级为空信号: {}", e.getMessage());
            return DecisionSignal.empty();
        }
    }

    /**
     * 格式化决策信号为可注入 Prompt 的文本。
     *
     * @param signal 决策信号
     * @return 格式化文本（为空时返回 null）
     */
    @Nullable
    public String formatForPrompt(DecisionSignal signal) {
        if (signal.isEmpty()) return null;

        var sb = new StringBuilder();

        if (signal.experienceHint() != null) {
            var hint = signal.experienceHint();
            sb.append("历史经验: ").append(hint.pattern());
            if (hint.caveat() != null) {
                sb.append(" (注意: ").append(hint.caveat()).append(")");
            }
            sb.append("\n");
        }

        if (!signal.toolHints().isEmpty()) {
            for (var tool : signal.toolHints()) {
                if (tool.issue() != null) {
                    sb.append("工具提示: ").append(tool.toolId())
                            .append(" — ").append(tool.issue()).append("\n");
                }
            }
        }

        if (!signal.risks().isEmpty()) {
            for (var risk : signal.risks()) {
                sb.append("风险: ").append(risk.description()).append("\n");
            }
        }

        if (signal.environmentHint() != null) {
            sb.append("环境: ").append(signal.environmentHint()).append("\n");
        }

        String result = sb.toString().strip();
        return result.isEmpty() ? null : result;
    }

    @Nullable
    private DecisionSignal.ExperienceHint matchExperience(String goal) {
        if (intentMatcher == null || goal == null || goal.isBlank()) {
            return null;
        }
        try {
            var match = intentMatcher.match(goal);
            if (match.isEmpty() || match.get().score() < MIN_EXPERIENCE_CONFIDENCE) {
                return null;
            }
            var template = match.get().template();
            return new DecisionSignal.ExperienceHint(
                    template.name(),
                    match.get().score(),
                    template.description() != null ? template.description() : template.name(),
                    template.successRate() < 0.7f ? "历史成功率偏低(" + template.successRate() + ")" : null
            );
        } catch (Exception e) {
            log.debug("决策引擎: IntentMatcher 查询失败: {}", e.getMessage());
            return null;
        }
    }

    private List<DecisionSignal.ToolCapabilityHint> assessTools(Set<String> toolIds) {
        var hints = new ArrayList<DecisionSignal.ToolCapabilityHint>();
        for (String toolId : toolIds) {
            var health = capabilityAssessor.getToolHealth(toolId);
            // 只对有数据且不健康的工具生成提示
            if (health.recentSuccesses() + health.recentFailures() > 0 && !health.isHealthy()) {
                hints.add(new DecisionSignal.ToolCapabilityHint(
                        toolId,
                        health.successRate(),
                        "最近成功率 " + String.format("%.0f%%", health.successRate() * 100)
                                + (health.lastError() != null ? ", 最近错误: " + truncate(health.lastError(), 50) : "")
                ));
            }
        }
        return hints;
    }

    private List<DecisionSignal.RiskWarning> detectRisks(Set<String> toolIds, EnvironmentState env) {
        var risks = new ArrayList<DecisionSignal.RiskWarning>();

        // 检测不健康工具
        for (var entry : env.toolHealth().entrySet()) {
            if (!entry.getValue().isHealthy() && entry.getValue().recentFailures() >= 3) {
                risks.add(new DecisionSignal.RiskWarning(
                        "TOOL_DEGRADED",
                        entry.getKey() + " 最近频繁失败，建议使用替代方案",
                        0.7f
                ));
            }
        }

        return risks;
    }

    @Nullable
    private String formatEnvironmentHint(EnvironmentState env) {
        if (!env.timeContext().isWorkingHours()) {
            return "非工作时间";
        }
        return null;
    }

    private String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
