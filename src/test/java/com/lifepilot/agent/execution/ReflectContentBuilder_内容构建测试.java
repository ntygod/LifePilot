package com.lifepilot.agent.execution;

import com.lifepilot.agent.model.*;
import com.lifepilot.interaction.model.InteractionSource;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReflectContentBuilder 内容构建测试。
 *
 * <p>覆盖 buildContent 的 3 种触发类型、buildTaskStateSummary 正常场景，
 * 以及边界场景（空步骤列表、null goal、无失败 Observation）。</p>
 *
 * @author zsg
 * @since 2026-04-04
 */
class ReflectContentBuilder_内容构建测试 {

    // ========== 辅助方法 ==========

    /** 创建默认预算。 */
    private Budget 默认预算() {
        return Budget.builder()
                .maxTokens(1000000).tokensUsed(0).tokensReserved(0)
                .maxSteps(30).stepsUsed(5)
                .maxDuration(Duration.ofMinutes(5)).elapsed(Duration.ZERO)
                .build();
    }

    /** 创建带指定步骤列表和 goal 的状态。 */
    private ReactAgentState 构建状态(String goal, List<ReactStep> steps) {
        return ReactAgentState.builder()
                .traceId("trace-test")
                .sessionId("session-test")
                .goal(goal)
                .source(InteractionSource.system("test"))
                .taskMode(AgentTaskMode.AUTO)
                .steps(steps)
                .stepCount(steps.size())
                .shortTermMemory(List.of())
                .mentionedEntities(List.of())
                .budget(默认预算())
                .depth(0)
                .done(false)
                .completionMode(CompletionMode.NORMAL)
                .earlyStopRejectCount(0)
                .suspended(false)
                .build();
    }

    /** 创建空步骤状态。 */
    private ReactAgentState 空步骤状态(String goal) {
        return 构建状态(goal, List.of());
    }

    /** 创建包含一次失败 Observation 的步骤列表。 */
    private List<ReactStep> 含失败观察步骤() {
        var steps = new ArrayList<ReactStep>();
        steps.add(new ReactStep.ToolCall("web.fetch", "网页抓取", "{\"url\":\"https://example.com\"}", 100L));
        steps.add(new ReactStep.Observation("web.fetch", "网页抓取", false, "连接超时: Connection timed out", 50));
        return steps;
    }

    /** 创建包含多次成功 Observation 的步骤列表。 */
    private List<ReactStep> 含成功观察步骤() {
        var steps = new ArrayList<ReactStep>();
        steps.add(new ReactStep.ToolCall("web.search", "网页搜索", "{\"query\":\"测试\"}", 200L));
        steps.add(new ReactStep.Observation("web.search", "网页搜索", true, "搜索结果...", 100));
        steps.add(new ReactStep.ToolCall("todo.create", "创建待办", "{\"title\":\"任务\"}", 50L));
        steps.add(new ReactStep.Observation("todo.create", "创建待办", true, "创建成功", 30));
        return steps;
    }

    /** 创建包含连续相同工具调用的步骤列表。 */
    private List<ReactStep> 含重复工具调用步骤() {
        var steps = new ArrayList<ReactStep>();
        for (int i = 0; i < 3; i++) {
            steps.add(new ReactStep.ToolCall("todo.create", "创建待办", "{\"title\":\"任务\"}", 50L));
            steps.add(new ReactStep.Observation("todo.create", "创建待办", true, "创建成功", 30));
        }
        return steps;
    }

    // ========== buildContent 三种触发类型 ==========

    @Nested
    class TOOL_FAILURE触发 {

        @Test
        void 工具失败时返回包含工具名和错误信息的反思文本() {
            var state = 构建状态("搜索新闻", 含失败观察步骤());

            String content = ReflectContentBuilder.buildContent(state, 2, ReactStep.ReflectTrigger.TOOL_FAILURE);

            assertThat(content)
                    .contains("网页抓取")
                    .contains("连接超时")
                    .contains("3")    // iteration + 1
                    .contains("失败");
        }

        @Test
        void 空步骤列表时使用默认工具名() {
            var state = 空步骤状态("测试目标");

            String content = ReflectContentBuilder.buildContent(state, 0, ReactStep.ReflectTrigger.TOOL_FAILURE);

            assertThat(content).contains("未知工具");
        }
    }

    @Nested
    class PERIODIC触发 {

        @Test
        void 周期性反思包含工具成功和失败计数() {
            var steps = new ArrayList<>(含成功观察步骤());
            steps.addAll(含失败观察步骤());
            var state = 构建状态("分析数据", steps);

            String content = ReflectContentBuilder.buildContent(state, 5, ReactStep.ReflectTrigger.PERIODIC);

            assertThat(content)
                    .contains("6")    // iteration + 1
                    .contains("2")    // 成功次数
                    .contains("1")    // 失败次数
                    .contains("分析数据");
        }

        @Test
        void 空步骤列表时成功和失败计数均为零() {
            var state = 空步骤状态("测试目标");

            String content = ReflectContentBuilder.buildContent(state, 0, ReactStep.ReflectTrigger.PERIODIC);

            assertThat(content).contains("0 次工具调用成功").contains("0 次失败");
        }
    }

    @Nested
    class STALL_DETECTED触发 {

        @Test
        void 停滞检测包含重复工具名() {
            var state = 构建状态("执行任务", 含重复工具调用步骤());

            String content = ReflectContentBuilder.buildContent(state, 3, ReactStep.ReflectTrigger.STALL_DETECTED);

            assertThat(content)
                    .contains("创建待办")
                    .contains("循环");
        }

        @Test
        void 空步骤列表时使用默认工具名() {
            var state = 空步骤状态("测试目标");

            String content = ReflectContentBuilder.buildContent(state, 0, ReactStep.ReflectTrigger.STALL_DETECTED);

            assertThat(content).contains("未知工具");
        }
    }

    // ========== null goal 保护 ==========

    @Nested
    class null_goal处理 {

        @Test
        void buildContent_goal为null时不输出null字符串() {
            var state = 空步骤状态(null);

            String content = ReflectContentBuilder.buildContent(state, 0, ReactStep.ReflectTrigger.PERIODIC);

            assertThat(content).doesNotContain("null");
            assertThat(content).contains("未指定");
        }

        @Test
        void buildTaskStateSummary_goal为null时不输出null字符串() {
            var state = 空步骤状态(null);

            String summary = ReflectContentBuilder.buildTaskStateSummary(state, 0);

            assertThat(summary).doesNotContain("null");
            assertThat(summary).contains("未指定");
        }
    }

    // ========== buildTaskStateSummary ==========

    @Nested
    class 任务状态摘要 {

        @Test
        void 正常场景包含目标和逐步进度() {
            var steps = new ArrayList<>(含成功观察步骤());
            steps.addAll(含失败观察步骤());
            var state = 构建状态("搜索新闻并整理", steps);

            String summary = ReflectContentBuilder.buildTaskStateSummary(state, 3);

            assertThat(summary)
                    .contains("目标：搜索新闻并整理")
                    .contains("✓")           // 成功步骤标记
                    .contains("web.search")  // 工具 ID
                    .contains("✗")           // 失败步骤标记
                    .contains("web.fetch")   // 失败工具 ID
                    .contains("当前轮次：4");
        }

        @Test
        void 空步骤列表时显示尚未执行() {
            var state = 空步骤状态("测试目标");

            String summary = ReflectContentBuilder.buildTaskStateSummary(state, 0);

            assertThat(summary)
                    .contains("尚未执行工具")
                    .doesNotContain("✗");
        }

        @Test
        void 仅成功步骤时不包含失败标记() {
            var state = 构建状态("测试目标", 含成功观察步骤());

            String summary = ReflectContentBuilder.buildTaskStateSummary(state, 1);

            assertThat(summary)
                    .contains("✓")
                    .doesNotContain("✗");
        }

        @Test
        void 超长目标被截断() {
            String longGoal = "这是一段超长目标文本".repeat(20);
            var state = 空步骤状态(longGoal);

            String summary = ReflectContentBuilder.buildTaskStateSummary(state, 0);

            assertThat(summary).contains("...");
        }
    }
}
