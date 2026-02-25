package com.lifepilot.skill.memory;

import com.lifepilot.skill.model.MemoryAccessPolicy;
import com.lifepilot.skill.model.MemoryReadPermission;
import com.lifepilot.skill.model.MemoryWritePermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MemoryAccessEnforcer 单元测试。
 *
 * @author zsg
 * @since 2026-07-28
 */
class MemoryAccessEnforcerTest {

    private MemoryAccessEnforcer enforcer;

    @BeforeEach
    void setUp() {
        enforcer = new MemoryAccessEnforcer();
    }

    // --- checkRead ---

    @Test
    void checkRead_授权的读取不抛异常() {
        var policy = new MemoryAccessPolicy(
                List.of(new MemoryReadPermission("L2_EPISODIC", List.of("TODO", "SCHEDULE"), null)),
                List.of()
        );
        enforcer.checkRead(policy, "L2_EPISODIC", "TODO");
    }

    @Test
    void checkRead_通配符允许所有实体类型() {
        var policy = new MemoryAccessPolicy(
                List.of(new MemoryReadPermission("L3_SEMANTIC", List.of("*"), null)),
                List.of()
        );
        enforcer.checkRead(policy, "L3_SEMANTIC", "PERSON");
    }

    @Test
    void checkRead_未授权的层抛出异常() {
        var policy = MemoryAccessPolicy.none();
        assertThatThrownBy(() -> enforcer.checkRead(policy, "L2_EPISODIC", "TODO"))
                .isInstanceOf(MemoryAccessViolationException.class)
                .hasMessageContaining("未授权的记忆读取")
                .hasMessageContaining("L2_EPISODIC")
                .hasMessageContaining("TODO");
    }

    @Test
    void checkRead_未授权的实体类型抛出异常() {
        var policy = new MemoryAccessPolicy(
                List.of(new MemoryReadPermission("L2_EPISODIC", List.of("TODO"), null)),
                List.of()
        );
        assertThatThrownBy(() -> enforcer.checkRead(policy, "L2_EPISODIC", "HABIT"))
                .isInstanceOf(MemoryAccessViolationException.class);
    }

    // --- checkWrite ---

    @Test
    void checkWrite_授权的写入不抛异常() {
        var policy = new MemoryAccessPolicy(
                List.of(),
                List.of(new MemoryWritePermission("L2_EPISODIC", List.of("TODO"), false))
        );
        enforcer.checkWrite(policy, "L2_EPISODIC", "TODO");
    }

    @Test
    void checkWrite_未授权的写入抛出异常() {
        var policy = MemoryAccessPolicy.none();
        assertThatThrownBy(() -> enforcer.checkWrite(policy, "L2_EPISODIC", "TODO"))
                .isInstanceOf(MemoryAccessViolationException.class)
                .hasMessageContaining("未授权的记忆写入");
    }

    @Test
    void checkWrite_通配符允许所有实体类型() {
        var policy = new MemoryAccessPolicy(
                List.of(),
                List.of(new MemoryWritePermission("L2_EPISODIC", List.of("*"), false))
        );
        enforcer.checkWrite(policy, "L2_EPISODIC", "SCHEDULE");
    }

    // --- requiresApproval ---

    @Test
    void requiresApproval_需要确认时返回true() {
        var policy = new MemoryAccessPolicy(
                List.of(),
                List.of(new MemoryWritePermission("L2_EPISODIC", List.of("TODO"), true))
        );
        assertThat(enforcer.requiresApproval(policy, "L2_EPISODIC", "TODO")).isTrue();
    }

    @Test
    void requiresApproval_不需要确认时返回false() {
        var policy = new MemoryAccessPolicy(
                List.of(),
                List.of(new MemoryWritePermission("L2_EPISODIC", List.of("TODO"), false))
        );
        assertThat(enforcer.requiresApproval(policy, "L2_EPISODIC", "TODO")).isFalse();
    }

    @Test
    void requiresApproval_无匹配权限时返回false() {
        var policy = MemoryAccessPolicy.none();
        assertThat(enforcer.requiresApproval(policy, "L2_EPISODIC", "TODO")).isFalse();
    }

    @Test
    void requiresApproval_通配符匹配且需要确认() {
        var policy = new MemoryAccessPolicy(
                List.of(),
                List.of(new MemoryWritePermission("L2_EPISODIC", List.of("*"), true))
        );
        assertThat(enforcer.requiresApproval(policy, "L2_EPISODIC", "ANYTHING")).isTrue();
    }

    // --- checkTimeRange ---

    @Test
    void checkTimeRange_在范围内不抛异常() {
        var policy = new MemoryAccessPolicy(
                List.of(new MemoryReadPermission("L2_EPISODIC", List.of("TODO"), "30d")),
                List.of()
        );
        // 10 天前在 30 天范围内
        enforcer.checkTimeRange(policy, "L2_EPISODIC", Instant.now().minus(Duration.ofDays(10)));
    }

    @Test
    void checkTimeRange_超出范围抛出异常() {
        var policy = new MemoryAccessPolicy(
                List.of(new MemoryReadPermission("L2_EPISODIC", List.of("TODO"), "30d")),
                List.of()
        );
        // 60 天前超出 30 天范围
        Instant queryTime = Instant.now().minus(Duration.ofDays(60));
        assertThatThrownBy(() -> enforcer.checkTimeRange(policy, "L2_EPISODIC", queryTime))
                .isInstanceOf(MemoryAccessViolationException.class)
                .hasMessageContaining("记忆查询时间超出范围");
    }

    @Test
    void checkTimeRange_无时间范围约束不抛异常() {
        var policy = new MemoryAccessPolicy(
                List.of(new MemoryReadPermission("L2_EPISODIC", List.of("TODO"), null)),
                List.of()
        );
        // 即使查询很久以前的数据也不抛异常
        enforcer.checkTimeRange(policy, "L2_EPISODIC", Instant.now().minus(Duration.ofDays(365)));
    }

    @Test
    void checkTimeRange_小时单位解析正确() {
        var policy = new MemoryAccessPolicy(
                List.of(new MemoryReadPermission("L2_EPISODIC", List.of("TODO"), "24h")),
                List.of()
        );
        // 2 小时前在 24 小时范围内
        enforcer.checkTimeRange(policy, "L2_EPISODIC", Instant.now().minus(Duration.ofHours(2)));
    }

    @Test
    void checkTimeRange_分钟单位解析正确() {
        var policy = new MemoryAccessPolicy(
                List.of(new MemoryReadPermission("L2_EPISODIC", List.of("TODO"), "60m")),
                List.of()
        );
        // 30 分钟前在 60 分钟范围内
        enforcer.checkTimeRange(policy, "L2_EPISODIC", Instant.now().minus(Duration.ofMinutes(30)));
    }

    @Test
    void checkTimeRange_无匹配层时不抛异常() {
        var policy = new MemoryAccessPolicy(
                List.of(new MemoryReadPermission("L3_SEMANTIC", List.of("PERSON"), "30d")),
                List.of()
        );
        // 查询不同层，不匹配任何权限，不检查时间范围
        enforcer.checkTimeRange(policy, "L2_EPISODIC", Instant.now().minus(Duration.ofDays(365)));
    }
}
