package com.lifepilot.agent.task.proactive;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * 时段解析工具 — 将时间映射为离散时段标签。
 *
 * @author zsg
 * @since 2026-04-15
 */
public final class TimeSlotResolver {

    private TimeSlotResolver() {}

    /** 从 ContextPacket 解析当前时段。 */
    public static String resolve(ContextPacket ctx) {
        return resolve(ctx.now(), ctx.zoneId());
    }

    /** 从 Instant + ZoneId 解析时段。 */
    public static String resolve(Instant instant, ZoneId zoneId) {
        int hour = LocalTime.ofInstant(instant, zoneId).getHour();
        if (hour >= 6 && hour < 9) return "early-morning";
        if (hour >= 9 && hour < 12) return "morning";
        if (hour >= 12 && hour < 14) return "noon";
        if (hour >= 14 && hour < 18) return "afternoon";
        if (hour >= 18 && hour < 21) return "evening";
        return "night";
    }
}
