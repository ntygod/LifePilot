package com.lifepilot.memory.quality;

import com.lifepilot.memory.consumption.quality.MemoryEvidenceKind;
import com.lifepilot.memory.consumption.quality.MemoryQualityPolicy;
import com.lifepilot.memory.consumption.quality.MemoryTrustLevel;
import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.governance.lifecycle.Temporality;
import com.lifepilot.memory.semantic.AudnDecision;
import com.lifepilot.memory.semantic.AudnOperation;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.TemporalEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 记忆质量策略测试。
 *
 * @author zsg
 * @since 2026-05-05
 */
@DisplayName("MemoryQualityPolicy")
class MemoryQualityPolicyTest {

    @Test
    void 缺失证据类型按契约失败且不会默认提升为用户显式证据() {
        var decision = new AudnDecision(
                AudnOperation.ADD,
                "用户偏好",
                EntityType.PREFERENCE,
                "用户喜欢简洁回答",
                Map.of(),
                0.9f,
                0.7f,
                "PERSISTENT",
                null,
                null,
                null);

        assertThatThrownBy(() -> MemoryQualityPolicy.evidenceKindFromDecision(decision))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("证据类型不能为空");
        assertThat(MemoryQualityPolicy.trustScoreFor(MemoryEvidenceKind.UNKNOWN, 0.95f))
                .isZero();
        assertThat(MemoryQualityPolicy.trustLevelFor(MemoryEvidenceKind.UNKNOWN, 0.95f))
                .isEqualTo(MemoryTrustLevel.UNVERIFIED);
    }

    @Test
    void 证据类型必须使用精确枚举值() {
        assertThatThrownBy(() -> MemoryQualityPolicy.parseEvidenceKind(" user_explicit "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("证据类型不能包含首尾空白");

        assertThatThrownBy(() -> MemoryQualityPolicy.parseEvidenceKind("user_explicit"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知证据类型");
    }

    @Test
    void 可消费门槛同时过滤质量生命周期和过期时间() {
        assertThat(MemoryQualityPolicy.isPromptConsumable(实体(LifecycleState.ACTIVE, null, null, MemoryTrustLevel.EXPLICIT, 0.9f)))
                .isTrue();
        assertThat(MemoryQualityPolicy.isPromptConsumable(实体(LifecycleState.ARCHIVED, null, null, MemoryTrustLevel.EXPLICIT, 0.9f)))
                .isFalse();
        assertThat(MemoryQualityPolicy.isPromptConsumable(实体(LifecycleState.ACTIVE, Instant.now().minusSeconds(60), null, MemoryTrustLevel.EXPLICIT, 0.9f)))
                .isFalse();
        assertThat(MemoryQualityPolicy.isPromptConsumable(实体(LifecycleState.ACTIVE, null, Instant.now().minusSeconds(60), MemoryTrustLevel.EXPLICIT, 0.9f)))
                .isFalse();
        assertThat(MemoryQualityPolicy.isPromptConsumable(实体(LifecycleState.ACTIVE, null, null, MemoryTrustLevel.UNVERIFIED, 0.0f)))
                .isFalse();
    }

    @Test
    void 写入质量门禁拒绝UNKNOWN证据类型() {
        var entity = 实体(MemoryEvidenceKind.UNKNOWN, MemoryTrustLevel.EXPLICIT, 0.9f, 1);

        assertThatThrownBy(() -> MemoryQualityPolicy.requireWritableQuality(entity))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("有效证据类型");
    }

    @Test
    void 写入质量门禁拒绝UNVERIFIED可信等级() {
        var entity = 实体(MemoryEvidenceKind.USER_CONFIRMED, MemoryTrustLevel.UNVERIFIED, 0.9f, 1);

        assertThatThrownBy(() -> MemoryQualityPolicy.requireWritableQuality(entity))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("可消费的可信等级");
    }

    @Test
    void 写入质量门禁拒绝非正可信分() {
        var entity = 实体(MemoryEvidenceKind.USER_CONFIRMED, MemoryTrustLevel.EXPLICIT, 0.0f, 1);

        assertThatThrownBy(() -> MemoryQualityPolicy.requireWritableQuality(entity))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("可信分必须在 (0,1]");
    }

    @Test
    void 写入质量门禁拒绝非正证据数量() {
        var entity = 实体(MemoryEvidenceKind.USER_CONFIRMED, MemoryTrustLevel.EXPLICIT, 0.9f, 0);

        assertThatThrownBy(() -> MemoryQualityPolicy.requireWritableQuality(entity))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("证据数量必须大于 0");
    }

    @Test
    void 写入质量门禁通过时返回原实体() {
        var entity = 实体(MemoryEvidenceKind.USER_CONFIRMED, MemoryTrustLevel.EXPLICIT, 0.9f, 1);

        assertThat(MemoryQualityPolicy.requireWritableQuality(entity))
                .isSameAs(entity);
    }

    private TemporalEntity 实体(MemoryEvidenceKind evidenceKind,
                              MemoryTrustLevel trustLevel,
                              float trustScore,
                              int evidenceCount) {
        var now = Instant.now();
        return new TemporalEntity(
                "entity-1",
                EntityType.TOPIC,
                "测试实体",
                "测试描述",
                Map.of(),
                1,
                true,
                now.minusSeconds(3600),
                null,
                "session-1",
                0.9f,
                0.7f,
                0,
                null,
                now,
                now,
                LifecycleState.ACTIVE,
                null,
                null,
                Temporality.PERSISTENT,
                null,
                false,
                List.of(),
                evidenceKind,
                trustLevel,
                trustScore,
                evidenceCount,
                evidenceKind == MemoryEvidenceKind.USER_CONFIRMED ? now : null);
    }

    private TemporalEntity 实体(LifecycleState lifecycleState,
                              Instant validTo,
                              Instant expiresAt,
                              MemoryTrustLevel trustLevel,
                              float trustScore) {
        var now = Instant.now();
        return new TemporalEntity(
                "entity-1",
                EntityType.TOPIC,
                "测试实体",
                "测试描述",
                Map.of(),
                1,
                true,
                now.minusSeconds(3600),
                validTo,
                "session-1",
                0.9f,
                0.7f,
                0,
                null,
                now,
                now,
                lifecycleState,
                null,
                expiresAt,
                Temporality.PERSISTENT,
                null,
                false,
                List.of(),
                trustLevel == MemoryTrustLevel.EXPLICIT ? MemoryEvidenceKind.USER_CONFIRMED : MemoryEvidenceKind.UNKNOWN,
                trustLevel,
                trustScore,
                trustLevel == MemoryTrustLevel.EXPLICIT ? 1 : 0,
                trustLevel == MemoryTrustLevel.EXPLICIT ? now : null);
    }
}
