package com.lifepilot.agent.task.proactive.schedule;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ScheduleExtractor 单元测试。
 *
 * @author zsg
 * @since 2026-04-14
 */
class ScheduleExtractor_单元测试 {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    // 固定为 2026-04-15 (周三) 10:00 CST，消除测试对系统时间的依赖
    private static final Clock FIXED_CLOCK = Clock.fixed(
            LocalDateTime.of(2026, 4, 15, 10, 0).atZone(ZONE).toInstant(), ZONE);
    private final ScheduleExtractor extractor = new ScheduleExtractor(FIXED_CLOCK);

    @Test
    void 提取明天下午开会() {
        var events = extractor.extract("u1", "sess-1",
                List.of("明天下午3点开会"), ZONE);
        assertThat(events).anyMatch(e -> e.title().contains("开会") && e.eventTime() != null);
        // 明天 = 2026-04-16，下午3点
        var event = events.stream().filter(e -> e.title().contains("开会")).findFirst().orElseThrow();
        LocalDateTime resolved = LocalDateTime.ofInstant(event.eventTime(), ZONE);
        assertThat(resolved.toLocalDate()).isEqualTo(LocalDate.of(2026, 4, 16));
        assertThat(resolved.getHour()).isEqualTo(3);
    }

    @Test
    void 提取周五去杭州() {
        var events = extractor.extract("u1", "sess-2",
                List.of("周五去杭州出差"), ZONE);
        assertThat(events).anyMatch(e -> e.title().contains("杭州"));
        // 固定为周三，next(FRIDAY) = 2026-04-17
        var event = events.stream().filter(e -> e.title().contains("杭州")).findFirst().orElseThrow();
        LocalDate resolved = LocalDateTime.ofInstant(event.eventTime(), ZONE).toLocalDate();
        assertThat(resolved).isEqualTo(LocalDate.of(2026, 4, 17));
    }

    @Test
    void 提取有个面试() {
        var events = extractor.extract("u1", "sess-3",
                List.of("下周一有个面试"), ZONE);
        assertThat(events).isNotEmpty();
        // next(MONDAY) from 周三 = 2026-04-20
        var event = events.stream().filter(e -> e.eventTime() != null).findFirst().orElseThrow();
        LocalDate resolved = LocalDateTime.ofInstant(event.eventTime(), ZONE).toLocalDate();
        assertThat(resolved).isEqualTo(LocalDate.of(2026, 4, 20));
    }

    @Test
    void 无日程文本返回空() {
        var events = extractor.extract("u1", "sess-4",
                List.of("今天天气不错"), ZONE);
        assertThat(events).isEmpty();
    }
}
