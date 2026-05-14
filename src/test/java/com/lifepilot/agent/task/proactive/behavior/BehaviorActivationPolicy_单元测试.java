package com.lifepilot.agent.task.proactive.behavior;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.task.proactive.ContextPacket;
import com.lifepilot.agent.task.proactive.boundary.BoundaryState;
import com.lifepilot.agent.task.proactive.boundary.FocusMode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BehaviorActivationPolicy 单元测试。
 *
 * @author zsg
 * @since 2026-05-09
 */
class BehaviorActivationPolicy_单元测试 {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Test
    void 开关关闭时全激活() {
        var cfg = new AgentConfigProperties().getTask();
        cfg.setProactiveBehaviorLayeredActivationEnabled(false);
        var policy = new BehaviorActivationPolicy(cfg);

        var ctx = ctx(BoundaryState.OUT_OF_BOUNDARY, null, null);

        for (var layer : BehaviorLayer.values()) {
            assertThat(policy.shouldActivate(layer, ctx)).isTrue();
        }
    }

    @Test
    void STANDALONE始终激活() {
        var cfg = enabledConfig();
        var policy = new BehaviorActivationPolicy(cfg);

        assertThat(policy.shouldActivate(BehaviorLayer.STANDALONE,
                ctx(BoundaryState.OUT_OF_BOUNDARY, null, null))).isTrue();
        assertThat(policy.shouldActivate(BehaviorLayer.STANDALONE,
                ctx(BoundaryState.IN_BOUNDARY, null, null))).isTrue();
    }

    @Test
    void FACT_DRIVEN_在boundary内激活() {
        var policy = new BehaviorActivationPolicy(enabledConfig());
        assertThat(policy.shouldActivate(BehaviorLayer.FACT_DRIVEN,
                ctx(BoundaryState.IN_BOUNDARY, null, null))).isTrue();
        assertThat(policy.shouldActivate(BehaviorLayer.FACT_DRIVEN,
                ctx(BoundaryState.UNKNOWN, null, null))).isTrue();
    }

    @Test
    void FACT_DRIVEN_boundary外无软降级条件时跳过() {
        var policy = new BehaviorActivationPolicy(enabledConfig());
        // 无画像且 actionsSentToday > 0 → 跳过
        var ctx = ctxFull(BoundaryState.OUT_OF_BOUNDARY, FocusMode.NORMAL, null, null, 1);
        assertThat(policy.shouldActivate(BehaviorLayer.FACT_DRIVEN, ctx)).isFalse();
    }

    @Test
    void FACT_DRIVEN_boundary外且有画像且未推过时激活() {
        var policy = new BehaviorActivationPolicy(enabledConfig());
        var ctx = ctxFull(BoundaryState.OUT_OF_BOUNDARY, FocusMode.NORMAL, "用户画像信息", null, 0);
        assertThat(policy.shouldActivate(BehaviorLayer.FACT_DRIVEN, ctx)).isTrue();
    }

    @Test
    void EXPERIENCE_DRIVEN_有经验时激活() {
        var policy = new BehaviorActivationPolicy(enabledConfig());
        var ctx = ctxFull(BoundaryState.UNKNOWN, FocusMode.NORMAL, null, "近期经验", 0);
        assertThat(policy.shouldActivate(BehaviorLayer.EXPERIENCE_DRIVEN, ctx)).isTrue();
    }

    @Test
    void EXPERIENCE_DRIVEN_无经验时跳过() {
        var policy = new BehaviorActivationPolicy(enabledConfig());
        assertThat(policy.shouldActivate(BehaviorLayer.EXPERIENCE_DRIVEN,
                ctx(BoundaryState.IN_BOUNDARY, null, null))).isFalse();
    }

    @Test
    void HABIT_DRIVEN_boundary外活跃时段激活() {
        var policy = new BehaviorActivationPolicy(enabledConfig());
        // 14:00 CST (06:00 UTC) → afternoon 活跃时段
        var now = Instant.parse("2026-05-09T06:00:00Z");
        var ctx = new ContextPacket("u1", now, ZONE, null, null, 0, 5,
                null, null, 30, null, null,
                BoundaryState.OUT_OF_BOUNDARY, FocusMode.NORMAL);
        assertThat(policy.shouldActivate(BehaviorLayer.HABIT_DRIVEN, ctx)).isTrue();
    }

    private AgentConfigProperties.TaskConfig enabledConfig() {
        var cfg = new AgentConfigProperties().getTask();
        cfg.setProactiveBehaviorLayeredActivationEnabled(true);
        return cfg;
    }

    private ContextPacket ctx(BoundaryState bs, FocusMode fm, String experience) {
        return ctxFull(bs, fm != null ? fm : FocusMode.NORMAL, null, experience, 0);
    }

    private ContextPacket ctxFull(BoundaryState bs, FocusMode fm, String profile,
                                   String experience, int actionsSentToday) {
        return new ContextPacket("u1", Instant.parse("2026-05-09T06:00:00Z"), ZONE,
                null, null, actionsSentToday, 5, null, null, 30,
                profile, experience, bs != null ? bs : BoundaryState.UNKNOWN,
                fm != null ? fm : FocusMode.NORMAL);
    }
}
