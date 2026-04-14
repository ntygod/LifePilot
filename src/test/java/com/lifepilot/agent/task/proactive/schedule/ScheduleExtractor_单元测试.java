package com.lifepilot.agent.task.proactive.schedule;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduleExtractor_单元测试 {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final ScheduleExtractor extractor = new ScheduleExtractor();

    @Test
    void 提取明天下午开会() {
        var events = extractor.extract("u1", "sess-1",
                List.of("明天下午3点开会"), ZONE);
        assertThat(events).anyMatch(e -> e.title().contains("开会") && e.eventTime() != null);
    }

    @Test
    void 提取周五去杭州() {
        var events = extractor.extract("u1", "sess-2",
                List.of("周五去杭州出差"), ZONE);
        assertThat(events).anyMatch(e -> e.title().contains("杭州"));
    }

    @Test
    void 提取有个面试() {
        var events = extractor.extract("u1", "sess-3",
                List.of("下周一有个面试"), ZONE);
        assertThat(events).isNotEmpty();
    }

    @Test
    void 无日程文本返回空() {
        var events = extractor.extract("u1", "sess-4",
                List.of("今天天气不错"), ZONE);
        assertThat(events).isEmpty();
    }
}
