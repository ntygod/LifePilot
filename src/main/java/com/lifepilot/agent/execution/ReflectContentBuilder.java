package com.lifepilot.agent.execution;

import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 反思内容构建器 — 根据触发原因和当前状态动态拼装反思文本。
 *
 * <p>无状态工具类，所有方法均为静态方法。用于在 ReAct 循环的特定检查点
 * 生成结构化的反思提示，引导 LLM 评估执行进展并调整策略。</p>
 *
 * @author zsg
 * @since 2026-04-04
 */
public final class ReflectContentBuilder {

    private ReflectContentBuilder() {}

    /**
     * 根据触发原因和当前状态构建反思文本。
     *
     * @param state     当前 Agent 状态
     * @param iteration 当前迭代轮次（从 0 开始）
     * @param trigger   反思触发原因
     * @return 反思文本
     */
    public static String buildContent(ReactAgentState state, int iteration,
                                      ReactStep.ReflectTrigger trigger) {
        int remaining = state.budget().stepsRemaining();
        String goal = state.goal();

        return switch (trigger) {
            case TOOL_FAILURE -> buildToolFailureContent(state, iteration, remaining);
            case PERIODIC -> buildPeriodicContent(state, iteration, remaining, goal);
            case STALL_DETECTED -> buildStallDetectedContent(state);
        };
    }

    /**
     * 构建工作区进度快照，用于写入 L1 工作区。
     *
     * @param state     当前 Agent 状态
     * @param iteration 当前迭代轮次（从 0 开始）
     * @return 格式化的进度快照文本
     */
    public static String buildTaskStateSummary(ReactAgentState state, int iteration) {
        var steps = state.steps();
        int remaining = state.budget().stepsRemaining();
        String goal = state.goal();
        String goalPreview = goal != null && goal.length() > 80
                ? goal.substring(0, 80) + "..."
                : (goal != null ? goal : "");

        // 收集成功工具名（去重）
        var successTools = new LinkedHashSet<String>();
        // 收集失败工具名和错误摘要
        var failedEntries = new ArrayList<String>();

        for (var step : steps) {
            if (step instanceof ReactStep.Observation obs) {
                String toolDisplay = obs.toolName() != null ? obs.toolName() : obs.toolId();
                if (obs.success()) {
                    successTools.add(toolDisplay);
                } else {
                    String errorPreview = obs.output() != null && obs.output().length() > 60
                            ? obs.output().substring(0, 60) + "..."
                            : (obs.output() != null ? obs.output() : "");
                    failedEntries.add(toolDisplay + ": " + errorPreview);
                }
            }
        }

        var sb = new StringBuilder();
        sb.append("目标：").append(goalPreview).append('\n');
        sb.append("已完成：").append(successTools.isEmpty() ? "无" : String.join(", ", successTools)).append('\n');
        if (!failedEntries.isEmpty()) {
            sb.append("失败：").append(String.join("; ", failedEntries)).append('\n');
        }
        sb.append("当前轮次：").append(iteration + 1).append("，剩余步骤预算：").append(remaining);

        return sb.toString();
    }

    // ===== 私有方法 =====

    /** 工具失败触发的反思文本。 */
    private static String buildToolFailureContent(ReactAgentState state, int iteration, int remaining) {
        var steps = state.steps();
        String toolName = "未知工具";
        String briefError = "";

        // 逆序扫描找到最近一个失败的 Observation
        for (int i = steps.size() - 1; i >= 0; i--) {
            if (steps.get(i) instanceof ReactStep.Observation obs && !obs.success()) {
                toolName = obs.toolName() != null ? obs.toolName() : obs.toolId();
                briefError = obs.output() != null && obs.output().length() > 100
                        ? obs.output().substring(0, 100) + "..."
                        : (obs.output() != null ? obs.output() : "");
                break;
            }
        }

        return "上一步工具 %s 执行失败: %s。我需要分析失败原因并决定：重试（修改参数）、切换策略、还是向用户说明。当前已执行 %d 轮，剩余步骤预算 %d。"
                .formatted(toolName, briefError, iteration + 1, remaining);
    }

    /** 周期性触发的反思文本。 */
    private static String buildPeriodicContent(ReactAgentState state, int iteration,
                                               int remaining, String goal) {
        var steps = state.steps();
        int successCount = 0;
        int failCount = 0;

        for (var step : steps) {
            if (step instanceof ReactStep.Observation obs) {
                if (obs.success()) {
                    successCount++;
                } else {
                    failCount++;
                }
            }
        }

        return "已执行 %d 轮，%d 次工具调用成功，%d 次失败。原始目标：%s。评估当前进展是否合理，是否需要调整后续步骤。"
                .formatted(iteration + 1, successCount, failCount, goal);
    }

    /** 停滞检测触发的反思文本。 */
    private static String buildStallDetectedContent(ReactAgentState state) {
        var steps = state.steps();
        String toolName = "未知工具";

        // 逆序找到最近的 ToolCall
        for (int i = steps.size() - 1; i >= 0; i--) {
            if (steps.get(i) instanceof ReactStep.ToolCall tc) {
                toolName = tc.toolName() != null ? tc.toolName() : tc.toolId();
                break;
            }
        }

        return "检测到连续多次调用同一工具 %s，可能陷入循环。需要切换策略或使用其他工具来推进任务。"
                .formatted(toolName);
    }
}
