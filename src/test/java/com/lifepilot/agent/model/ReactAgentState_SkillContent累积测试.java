package com.lifepilot.agent.model;

import com.lifepilot.interaction.model.InteractionSource;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReactAgentState.appendSkillContent 去重 + 大小上限行为测试。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>首次加载写入</li>
 *   <li>连续加载相同 skill name 应跳过（不重复累积）</li>
 *   <li>不同 skill name 应累积</li>
 *   <li>空/空白输入直接返回 this</li>
 *   <li>超 20KB 硬上限应截断头部、保留尾部最新内容</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-24
 */
class ReactAgentState_SkillContent累积测试 {

    /** 构造最小可用 ReactAgentState，只填必须字段。 */
    private static ReactAgentState minimal() {
        return ReactAgentState.builder()
                .traceId("trace-1")
                .sessionId("session-1")
                .goal("测试")
                .source(InteractionSource.system("test"))
                .taskMode(AgentTaskMode.AUTO)
                .steps(List.of())
                .stepCount(0)
                .shortTermMemory(List.of())
                .mentionedEntities(List.of())
                .budget(Budget.builder()
                        .maxTokens(1000).tokensUsed(0).tokensReserved(0)
                        .maxSteps(10).stepsUsed(0)
                        .maxDuration(Duration.ofSeconds(60))
                        .elapsed(Duration.ZERO)
                        .build())
                .depth(0)
                .done(false)
                .completionMode(CompletionMode.NORMAL)
                .earlyStopRejectCount(0)
                .suspended(false)
                .build();
    }

    @Test
    void 首次加载应写入loadedSkillContent() {
        var state = minimal();
        var after = state.appendSkillContent("<skill name=\"demo\">hello</skill>");
        assertThat(after.loadedSkillContent()).isEqualTo("<skill name=\"demo\">hello</skill>");
    }

    @Test
    void 空输入应原样返回_不修改state() {
        var state = minimal();
        assertThat(state.appendSkillContent(null)).isSameAs(state);
        assertThat(state.appendSkillContent("")).isSameAs(state);
        assertThat(state.appendSkillContent("   ")).isSameAs(state);
    }

    @Test
    void 重复加载同名skill应跳过() {
        var state = minimal()
                .appendSkillContent("<skill name=\"demo\">body-1</skill>");

        var again = state.appendSkillContent("<skill name=\"demo\">body-2</skill>");

        assertThat(again.loadedSkillContent())
                .as("同名 skill 二次加载应整段跳过，保持原内容")
                .isEqualTo("<skill name=\"demo\">body-1</skill>");
    }

    @Test
    void 不同skill应累积拼接() {
        var state = minimal()
                .appendSkillContent("<skill name=\"a\">body-a</skill>")
                .appendSkillContent("<skill name=\"b\">body-b</skill>");

        assertThat(state.loadedSkillContent())
                .contains("<skill name=\"a\">body-a</skill>")
                .contains("<skill name=\"b\">body-b</skill>");
    }

    @Test
    void 合并后超20KB应从头部截断保留尾部() {
        // 先填一个 19KB 的 skill a
        String bigBody = "x".repeat(19 * 1024);
        var state = minimal()
                .appendSkillContent("<skill name=\"a\">" + bigBody + "</skill>");

        // 再追加 5KB 的 skill b，合并后约 24KB，应触发截断
        String newBody = "y".repeat(5 * 1024);
        var after = state.appendSkillContent("<skill name=\"b\">" + newBody + "</skill>");

        String content = after.loadedSkillContent();
        assertThat(content).isNotNull();
        assertThat(content.length())
                .as("截断后长度应 ≤ 20KB + 截断提示前缀")
                .isLessThanOrEqualTo(20 * 1024 + 100);
        assertThat(content)
                .as("应保留尾部最新加载的 skill b")
                .contains("<skill name=\"b\">")
                .contains("yyyyy");
        assertThat(content)
                .as("应以截断标记前缀开头")
                .startsWith("...[已截断更早的 skill 指南]...");
    }
}
