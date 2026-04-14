package com.lifepilot.agent.task.proactive.intent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IntentExtractor 单元测试。
 *
 * @author zsg
 * @since 2026-04-14
 */
class IntentExtractor_单元测试 {

    private final IntentExtractor extractor = new IntentExtractor();

    @Test
    void 提取目标意图_我想() {
        var intents = extractor.extract("u1", "sess-1",
                List.of("我想买一个降噪耳机"));
        assertThat(intents).anyMatch(i -> i.intentType() == IntentType.GOAL && i.goal().contains("耳机"));
    }

    @Test
    void 提取目标意图_打算() {
        var intents = extractor.extract("u1", "sess-1",
                List.of("打算下周去杭州出差"));
        assertThat(intents).anyMatch(i -> i.intentType() == IntentType.GOAL);
    }

    @Test
    void 提取监控意图_帮我盯着() {
        var intents = extractor.extract("u1", "sess-2",
                List.of("帮我盯着京东上AirPods的价格"));
        assertThat(intents).anyMatch(i -> i.intentType() == IntentType.MONITORING);
    }

    @Test
    void 提取监控意图_帮我关注() {
        var intents = extractor.extract("u1", "sess-2",
                List.of("请帮我关注一下那个项目的进展"));
        assertThat(intents).anyMatch(i -> i.intentType() == IntentType.MONITORING);
    }

    @Test
    void 提取条件触发_等什么时候告诉我() {
        var intents = extractor.extract("u1", "sess-3",
                List.of("等那个降到300以下的时候告诉我"));
        assertThat(intents).anyMatch(i -> i.intentType() == IntentType.CONDITIONAL
                && i.triggerCondition() != null);
    }

    @Test
    void 提取习惯意图_每天() {
        var intents = extractor.extract("u1", "sess-4",
                List.of("每天早上跑步半小时"));
        assertThat(intents).anyMatch(i -> i.intentType() == IntentType.RECURRING);
    }

    @Test
    void 无意图文本返回空() {
        var intents = extractor.extract("u1", "sess-5",
                List.of("今天天气不错", "是啊"));
        assertThat(intents).isEmpty();
    }

    @Test
    void 同一对话内相似目标去重() {
        var intents = extractor.extract("u1", "sess-6",
                List.of("我想买耳机", "我想买个好耳机"));
        assertThat(intents.stream().filter(i -> i.goal().contains("耳机")).count()).isLessThanOrEqualTo(1);
    }

    @Test
    void 多条消息提取多个意图() {
        var intents = extractor.extract("u1", "sess-7",
                List.of("我想买耳机", "每天早上记得喝水"));
        assertThat(intents).hasSizeGreaterThanOrEqualTo(2);
    }
}
