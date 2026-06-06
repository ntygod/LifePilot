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

/**
 * 记忆质量策略测试。
 *
 * @author zsg
 * @since 2026-05-05
 */
@DisplayName("MemoryQualityPolicy")
class MemoryQualityPolicyTest {

    @Test
    void 缺失证据类型不会默认提升为用户显式证据() {
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

        assertThat(MemoryQualityPolicy.evidenceKindFromDecision(decision))
                .isEqualTo(MemoryEvidenceKind.UNKNOWN);
        assertThat(MemoryQualityPolicy.trustScoreFor(MemoryEvidenceKind.UNKNOWN, 0.95f))
                .isZero();
        assertThat(MemoryQualityPolicy.trustLevelFor(MemoryEvidenceKind.UNKNOWN, 0.95f))
                .isEqualTo(MemoryTrustLevel.UNVERIFIED);
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
