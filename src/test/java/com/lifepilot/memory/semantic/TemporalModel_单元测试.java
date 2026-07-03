package com.lifepilot.memory.semantic;

import com.lifepilot.memory.consumption.quality.MemoryEvidenceKind;
import com.lifepilot.memory.consumption.quality.MemoryTrustLevel;
import com.lifepilot.memory.store.scope.MemoryRealityType;
import com.lifepilot.memory.store.scope.MemoryScope;
import com.lifepilot.memory.store.scope.MemoryWriteContext;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.TemporalEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 时序记忆模型单元测试。
 *
 * @author zsg
 * @since 2026-06-24
 */
class TemporalModel_单元测试 {

    @Test
    void 实体可信分越界时抛异常() {
        assertThatThrownBy(() -> 实体(1.2f, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("实体可信分必须在 [0,1] 范围内");
        assertThatThrownBy(() -> 实体(Float.NaN, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("实体可信分必须在 [0,1] 范围内");
    }

    @Test
    void 实体证据数量为负数时抛异常() {
        assertThatThrownBy(() -> 实体(0.8f, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("实体证据数量不能为负数");
    }

    @Test
    void 实体名称为空白或带首尾空白时抛异常() {
        assertThatThrownBy(() -> 实体(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("实体名称不能为空");
        assertThatThrownBy(() -> 实体(" 咖啡偏好"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("实体名称不能包含首尾空白");
    }

    @Test
    void 实体质量枚举缺失时抛异常() {
        assertThatThrownBy(() -> 实体(null, MemoryTrustLevel.EXPLICIT, 0.8f, 1))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("实体证据类型不能为空");
        assertThatThrownBy(() -> 实体(MemoryEvidenceKind.USER_EXPLICIT, null, 0.8f, 1))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("实体可信等级不能为空");
    }

    @Test
    void 关系强度和可信分越界时抛异常() {
        assertThatThrownBy(() -> 关系(1.2f, 0.8f))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("关系强度必须在 [0.0, 1.0] 范围内");
        assertThatThrownBy(() -> 关系(0.8f, Float.NaN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("关系可信分必须在 [0,1] 范围内");
    }

    @Test
    void 关系质量枚举缺失时抛异常() {
        assertThatThrownBy(() -> 关系(MemoryEvidenceKind.CHAT_INFERRED, null, 0.8f))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("关系可信等级不能为空");
        assertThatThrownBy(() -> 关系(null, MemoryTrustLevel.INFERRED, 0.8f))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("关系证据类型不能为空");
    }

    @Test
    void 写入上下文来源和现实类型缺失时抛异常() {
        assertThatThrownBy(() -> new MemoryWriteContext(
                null,
                MemoryScope.USER_PROFILE,
                null,
                MemoryRealityType.REAL,
                "source-1",
                null,
                null,
                null,
                null,
                null,
                null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("记忆来源类型不能为空");
        assertThatThrownBy(() -> new MemoryWriteContext(
                null,
                MemoryScope.USER_PROFILE,
                com.lifepilot.memory.store.scope.MemoryOriginType.CHAT,
                null,
                "source-1",
                null,
                null,
                null,
                null,
                null,
                null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("记忆现实类型不能为空");
    }

    private TemporalEntity 实体(float trustScore, int evidenceCount) {
        return 实体("咖啡偏好", MemoryEvidenceKind.USER_EXPLICIT, MemoryTrustLevel.EXPLICIT, trustScore, evidenceCount);
    }

    private TemporalEntity 实体(MemoryEvidenceKind evidenceKind,
                                 MemoryTrustLevel trustLevel,
                                 float trustScore,
                                 int evidenceCount) {
        return 实体("咖啡偏好", evidenceKind, trustLevel, trustScore, evidenceCount);
    }

    private TemporalEntity 实体(String name) {
        return 实体(name, MemoryEvidenceKind.USER_EXPLICIT, MemoryTrustLevel.EXPLICIT, 0.8f, 1);
    }

    private TemporalEntity 实体(String name,
                                 MemoryEvidenceKind evidenceKind,
                                 MemoryTrustLevel trustLevel,
                                 float trustScore,
                                 int evidenceCount) {
        var now = Instant.now();
        return new TemporalEntity(
                "entity-1",
                EntityType.PREFERENCE,
                name,
                "用户喜欢拿铁",
                Map.of(),
                1,
                true,
                now,
                null,
                "session-1",
                0.9f,
                0.7f,
                0,
                null,
                now,
                now,
                com.lifepilot.memory.governance.lifecycle.LifecycleState.ACTIVE,
                null,
                null,
                com.lifepilot.memory.governance.lifecycle.Temporality.PERSISTENT,
                null,
                false,
                List.of(),
                evidenceKind,
                trustLevel,
                trustScore,
                evidenceCount,
                now);
    }

    private TemporalRelation 关系(float strength, float trustScore) {
        return 关系(MemoryEvidenceKind.CHAT_INFERRED, MemoryTrustLevel.INFERRED, strength, trustScore);
    }

    private TemporalRelation 关系(MemoryEvidenceKind evidenceKind,
                                   MemoryTrustLevel trustLevel,
                                   float trustScore) {
        return 关系(evidenceKind, trustLevel, 0.8f, trustScore);
    }

    private TemporalRelation 关系(MemoryEvidenceKind evidenceKind,
                                   MemoryTrustLevel trustLevel,
                                   float strength,
                                   float trustScore) {
        var now = Instant.now();
        return new TemporalRelation(
                "relation-1",
                "source-1",
                "target-1",
                "RELATED_TO",
                strength,
                null,
                now,
                null,
                "session-1",
                now,
                evidenceKind,
                trustLevel,
                trustScore);
    }
}
