package com.lifepilot.memory.consumption.quality;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 关系质量来源推导单元测试（relation-quality-gate Property 1）。
 *
 * <p>验证不同来源的关系按 {@link MemoryQualityPolicy} 得到合理的相对可信度：
 * 用户显式 &gt; 文档锚定 &gt; 对话推断；派生关系走 DERIVED 档。</p>
 *
 * @author zsg
 * @since 2026-06-07
 */
class RelationQuality_来源推导_单元测试 {

    @Test
    void 用户显式关系可信度高于对话推断() {
        float strength = 0.8f;
        float userConfirmed = MemoryQualityPolicy.trustScoreFor(MemoryEvidenceKind.USER_CONFIRMED, strength);
        float chatInferred = MemoryQualityPolicy.trustScoreFor(MemoryEvidenceKind.CHAT_INFERRED, strength);

        assertThat(userConfirmed).isGreaterThan(chatInferred);
    }

    @Test
    void 文档锚定关系可信度高于对话推断() {
        float strength = 0.8f;
        float docGrounded = MemoryQualityPolicy.trustScoreFor(MemoryEvidenceKind.DOCUMENT_GROUNDED, strength);
        float chatInferred = MemoryQualityPolicy.trustScoreFor(MemoryEvidenceKind.CHAT_INFERRED, strength);

        assertThat(docGrounded).isGreaterThan(chatInferred);
    }

    @Test
    void trustLevel按来源与分数推导() {
        float uc = MemoryQualityPolicy.trustScoreFor(MemoryEvidenceKind.USER_CONFIRMED, 0.9f);
        assertThat(MemoryQualityPolicy.trustLevelFor(MemoryEvidenceKind.USER_CONFIRMED, uc))
                .isEqualTo(MemoryTrustLevel.EXPLICIT);

        float derived = MemoryQualityPolicy.trustScoreFor(MemoryEvidenceKind.DERIVED, 0.7f);
        assertThat(MemoryQualityPolicy.trustLevelFor(MemoryEvidenceKind.DERIVED, derived))
                .isEqualTo(MemoryTrustLevel.DERIVED);
    }

    @Test
    void 各来源可信分均在合法区间() {
        for (var kind : MemoryEvidenceKind.values()) {
            float score = MemoryQualityPolicy.trustScoreFor(kind, 0.5f);
            assertThat(score).isBetween(0.0f, 1.0f);
        }
    }
}
