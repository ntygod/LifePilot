package com.lifepilot.agent.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
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

    private static final Logger log = LoggerFactory.getLogger(ReflectContentBuilder.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 进度明细最多保留最近 N 条 */
    private static final int MAX_DETAIL_STEPS = 10;

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
        String goal = state.goal() != null ? state.goal() : "未指定";

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
        String goal = state.goal() != null ? state.goal() : "未指定";
        String goalPreview = goal.length() > 80
                ? goal.substring(0, 80) + "..."
                : goal;

        // 配对 ToolCall + Observation 生成逐步进度
        var detailLines = buildStepDetails(steps);
        int totalToolSteps = detailLines.size();

        var sb = new StringBuilder();
        sb.append("目标：").append(goalPreview).append('\n');

        if (!detailLines.isEmpty()) {
            // 超过上限时，前面的步骤折叠为统计摘要
            if (totalToolSteps > MAX_DETAIL_STEPS) {
                int hidden = totalToolSteps - MAX_DETAIL_STEPS;
                long hiddenSuccess = detailLines.subList(0, hidden).stream()
                        .filter(l -> l.startsWith("✓")).count();
                long hiddenFail = hidden - hiddenSuccess;
                sb.append("前 ").append(hidden).append(" 步：")
                        .append(hiddenSuccess).append(" 成功");
                if (hiddenFail > 0) {
                    sb.append("，").append(hiddenFail).append(" 失败");
                }
                sb.append('\n');
                detailLines = detailLines.subList(hidden, totalToolSteps);
            }
            for (var line : detailLines) {
                sb.append("  ").append(line).append('\n');
            }
        } else {
            sb.append("进度：尚未执行工具\n");
        }

        sb.append("当前轮次：").append(iteration + 1)
                .append("，剩余步骤预算：").append(remaining);
        return sb.toString();
    }

    /**
     * 配对 ToolCall 和 Observation，生成每步进度明细。
     *
     * <p>格式示例：
     * <pre>
     * ✓ file.read(skill=browser-automation)
     * ✓ browser: navigate(京东搜索) → 触发风控验证
     * ✗ browser: wait(.J_MouserOnverReq) → 超时
     * </pre>
     */
    private static List<String> buildStepDetails(List<ReactStep> steps) {
        var result = new ArrayList<String>();
        ReactStep.ToolCall pendingCall = null;

        for (var step : steps) {
            if (step instanceof ReactStep.ToolCall tc) {
                pendingCall = tc;
            } else if (step instanceof ReactStep.Observation obs) {
                String marker = obs.success() ? "✓" : "✗";
                String callDesc = pendingCall != null
                        ? buildCallDescription(pendingCall.toolId(), pendingCall.inputJson())
                        : (obs.toolName() != null ? obs.toolName() : obs.toolId());
                String resultBrief = buildResultBrief(obs.success(), obs.output());
                String line = resultBrief.isEmpty()
                        ? marker + " " + callDesc
                        : marker + " " + callDesc + " → " + resultBrief;
                result.add(line);
                pendingCall = null;
            }
        }
        return result;
    }

    /**
     * 从工具 ID 和输入 JSON 提取简要调用描述。
     */
    private static String buildCallDescription(String toolId, String inputJson) {
        try {
            JsonNode root = MAPPER.readTree(inputJson);
            return switch (toolId) {
                case "browser" -> {
                    String action = textField(root, "action");
                    String detail = switch (action != null ? action : "") {
                        case "navigate" -> truncate(textField(root, "url"), 40);
                        case "click", "input", "wait", "hover", "select" ->
                                truncate(textField(root, "selector"), 30);
                        case "evaluate" -> "JS";
                        case "screenshot" -> "截图";
                        case "accessibility" -> "无障碍树";
                        case "tab" -> truncate(textField(root, "tabAction"), 20);
                        case "close" -> "关闭";
                        default -> "";
                    };
                    yield detail.isEmpty()
                            ? "browser: " + (action != null ? action : "?")
                            : "browser: " + action + "(" + detail + ")";
                }
                case "web.search" -> "web.search(" + truncate(textField(root, "query"), 30) + ")";
                case "web.fetch" -> "web.fetch(" + truncate(textField(root, "url"), 40) + ")";
                case "code.execute" -> "code.execute(" + textFieldOr(root, "language", "python") + ")";
                case "file.write" -> "file.write(" + truncate(textField(root, "path"), 30) + ")";
                case "file.read" -> {
                    String skill = textField(root, "skill");
                    if (skill != null && !skill.isBlank()) {
                        yield "file.read(skill=" + truncate(skill, 30) + ")";
                    }
                    yield "file.read(path=" + truncate(textField(root, "path"), 30) + ")";
                }
                case "memory" -> "memory: " + textFieldOr(root, "action", "?");
                case "shell.exec" -> "shell.exec";
                default -> toolId;
            };
        } catch (Exception e) {
            return toolId;
        }
    }

    /**
     * 从工具输出提取简要结果描述。
     */
    private static String buildResultBrief(boolean success, String output) {
        if (output == null || output.isBlank()) {
            return "";
        }
        if (!success) {
            // 失败时提取关键错误信息
            if (output.contains("Timeout") || output.contains("超时")) return "超时";
            if (output.contains("验证") || output.contains("verify")) return "需要验证";
            if (output.contains("登录") || output.contains("login")) return "需要登录";
            return truncate(output, 40);
        }
        // 成功时提取关键结果信号
        try {
            JsonNode root = MAPPER.readTree(output);
            // 浏览器工具常见字段
            String title = textField(root, "title");
            if (title != null && !title.isBlank()) {
                String textSnapshot = textField(root, "textSnapshot");
                if (textSnapshot != null && textSnapshot.contains("验证")) return title + " → 触发验证";
                if (textSnapshot != null && textSnapshot.contains("登录")) return title + " → 需要登录";
                if (textSnapshot != null && textSnapshot.isEmpty()) return title + " → 内容为空";
                return title;
            }
            // 无障碍树
            String tree = textField(root, "tree");
            if (tree != null) {
                if (tree.contains("登录") || tree.contains("login")) return "页面需要登录";
                return "已获取(" + tree.length() + "字符)";
            }
            // 截图
            if (root.has("screenshot")) return "截图已获取";
            // 文件操作
            String path = textField(root, "path");
            if (path != null) return path;
        } catch (Exception ignored) {
            // 非 JSON 输出
        }
        // 文本摘要输出
        if (output.length() > 60) {
            return truncate(output, 40);
        }
        return "";
    }

    @org.springframework.lang.Nullable
    private static String textField(JsonNode root, String field) {
        JsonNode node = root.path(field);
        return node.isTextual() ? node.asText() : null;
    }

    private static String textFieldOr(JsonNode root, String field, String fallback) {
        String val = textField(root, field);
        return val != null ? val : fallback;
    }

    private static String truncate(@org.springframework.lang.Nullable String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "…" : s;
    }

    // ===== 私有方法 =====

    /** 工具失败触发的反思文本。 */
    private static String buildToolFailureContent(ReactAgentState state, int iteration, int remaining) {
        var steps = state.steps();
        String toolName = "未知工具";
        String toolId = "";
        String briefError = "";

        // 逆序扫描找到最近一个失败的 Observation
        for (int i = steps.size() - 1; i >= 0; i--) {
            if (steps.get(i) instanceof ReactStep.Observation obs && !obs.success()) {
                toolName = obs.toolName() != null ? obs.toolName() : obs.toolId();
                toolId = obs.toolId() != null ? obs.toolId() : "";
                briefError = obs.output() != null && obs.output().length() > 200
                        ? obs.output().substring(0, 200) + "..."
                        : (obs.output() != null ? obs.output() : "");
                break;
            }
        }

        // 统计连续失败次数（从最新步骤向前数，同一工具连续失败的次数）
        int consecutiveFailures = countConsecutiveFailures(steps, toolId);

        var sb = new StringBuilder();
        sb.append("⚠ 工具执行失败\n");
        sb.append("工具: ").append(toolName).append('\n');
        sb.append("错误: ").append(briefError).append('\n');
        if (consecutiveFailures > 1) {
            sb.append("连续失败: ").append(consecutiveFailures).append(" 次\n");
        }
        sb.append("已执行轮次: ").append(iteration + 1).append("，剩余预算: ").append(remaining).append('\n');
        sb.append('\n');
        if (consecutiveFailures >= 2) {
            sb.append("同一工具已连续失败 ").append(consecutiveFailures)
                    .append(" 次，禁止再使用相同参数调用。必须切换工具或根本性调整参数。");
        } else {
            sb.append("请分析错误原因，调整参数重试或切换到其他工具。禁止使用完全相同的参数重复调用。");
        }
        return sb.toString();
    }

    /**
     * 统计从最新步骤向前，同一工具连续失败的次数。
     */
    private static int countConsecutiveFailures(List<ReactStep> steps, String targetToolId) {
        if (targetToolId == null || targetToolId.isEmpty()) {
            return 1;
        }
        int count = 0;
        for (int i = steps.size() - 1; i >= 0; i--) {
            if (steps.get(i) instanceof ReactStep.Observation obs) {
                if (!obs.success() && targetToolId.equals(obs.toolId())) {
                    count++;
                } else {
                    break;
                }
            }
            // 跳过 ToolCall、Reflect 等非 Observation 步骤继续向前
        }
        return Math.max(count, 1);
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
