package com.lifepilot.memory.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 测试用可推进时钟。
 *
 * <p>生产代码通过 {@link Clock} 注入，场景测试期通过 {@code ScenarioTestConfiguration}
 * 的 {@code @Primary} Bean 将其覆盖为本实现，从而在测试里按需推进当前时间。</p>
 *
 * <p>本类对所有状态读写加同步锁，支持多线程并发推进，但单测一般在单线程下调用。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
public class MutableClock extends Clock {

    private Instant now;
    private final ZoneId zone;

    /**
     * 构造可推进时钟。
     *
     * @param initial 初始时刻
     * @param zone    时区
     */
    public MutableClock(Instant initial, ZoneId zone) {
        this.now = java.util.Objects.requireNonNull(initial, "initial");
        this.zone = java.util.Objects.requireNonNull(zone, "zone");
    }

    /**
     * 将当前时刻向前推进指定时长。
     *
     * @param duration 推进时长，支持负值
     */
    public synchronized void advance(Duration duration) {
        this.now = this.now.plus(duration);
    }

    /**
     * 直接设置为指定时刻。
     *
     * @param instant 目标时刻
     */
    public synchronized void setTo(Instant instant) {
        this.now = instant;
    }

    @Override
    public synchronized Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId targetZone) {
        return new MutableClock(now, targetZone);
    }
}
