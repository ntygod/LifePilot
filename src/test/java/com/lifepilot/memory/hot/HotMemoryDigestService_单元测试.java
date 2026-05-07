package com.lifepilot.memory.hot;

import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.experience.SubtaskReflector;
import com.lifepilot.memory.lifecycle.LifecycleState;
import com.lifepilot.memory.lifecycle.Temporality;
import com.lifepilot.memory.procedural.PreferenceRule;
import com.lifepilot.memory.procedural.ProceduralMemory;
import com.lifepilot.memory.quality.MemoryEvidenceKind;
import com.lifepilot.memory.quality.MemoryTrustLevel;
import com.lifepilot.memory.scope.MemoryReadFilter;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import com.lifepilot.observability.redactor.DataRedactor;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link HotMemoryDigestService} 单元测试。
 *
 * @author zsg
 * @since 2026-05-05
 */
class HotMemoryDigestService_单元测试 {

    private static final Instant NOW = Instant.parse("2026-05-05T08:00:00Z");

    @Test
    void 热摘要只包含可消费实体并排除工具级经验() {
        SemanticMemory semanticMemory = mock(SemanticMemory.class);
        var properties = new MemoryProperties();
        var service = new HotMemoryDigestService(
                semanticMemory,
                properties,
                null,
                null,
                Clock.fixed(NOW, ZoneOffset.UTC));
        var filter = MemoryReadFilter.userMemory();

        when(semanticMemory.findAllCurrent(filter)).thenReturn(List.of(
                entity("pref-1", EntityType.PREFERENCE, "response_language", "用户偏好中文回答",
                        Map.of(), MemoryTrustLevel.EXPLICIT, MemoryEvidenceKind.USER_CONFIRMED,
                        LifecycleState.ACTIVE, 0.9f, 0.8f),
                entity("exp-tool", EntityType.EXPERIENCE, "shell 失败经验", "工具级经验不进热摘要",
                        Map.of("granularity", SubtaskReflector.TOOL_LEVEL),
                        MemoryTrustLevel.DERIVED, MemoryEvidenceKind.LLM_SUMMARIZED_EXPERIENCE,
                        LifecycleState.ACTIVE, 0.7f, 0.7f),
                entity("goal-old", EntityType.GOAL, "过期目标", "不应注入",
                        Map.of(), MemoryTrustLevel.EXPLICIT, MemoryEvidenceKind.USER_CONFIRMED,
                        LifecycleState.EXPIRED, 0.9f, 0.9f),
                entity("fact-low", EntityType.TOPIC, "低信任事实", "不应注入",
                        Map.of(), MemoryTrustLevel.UNVERIFIED, MemoryEvidenceKind.UNKNOWN,
                        LifecycleState.ACTIVE, 0.0f, 0.8f),
                entity("fact-inferred", EntityType.TOPIC, "推断事实", "默认热摘要不注入推断记忆",
                        Map.of(), MemoryTrustLevel.INFERRED, MemoryEvidenceKind.CHAT_INFERRED,
                        LifecycleState.ACTIVE, 0.7f, 0.8f)
        ));

        HotMemoryDigest digest = service.build(filter, "personal");

        String allContent = digest.sections().stream()
                .map(HotMemoryDigest.HotMemorySection::content)
                .reduce("", (left, right) -> left + "\n" + right);
        assertThat(allContent).contains("用户偏好中文回答");
        assertThat(allContent).doesNotContain("工具级经验不进热摘要");
        assertThat(allContent).doesNotContain("过期目标");
        assertThat(allContent).doesNotContain("低信任事实");
        assertThat(allContent).doesNotContain("默认热摘要不注入推断记忆");
        assertThat(digest.sections()).hasSize(1);
        assertThat(digest.sections().getFirst().sourceEntityIds()).containsExactly("pref-1");
    }

    @Test
    void 巩固画像存在时优先使用巩固画像避免碎片重复() {
        SemanticMemory semanticMemory = mock(SemanticMemory.class);
        var properties = new MemoryProperties();
        properties.getHotDigest().setUserProfileMaxEntries(1);
        var service = new HotMemoryDigestService(
                semanticMemory,
                properties,
                null,
                null,
                Clock.fixed(NOW, ZoneOffset.UTC));
        var filter = MemoryReadFilter.userProfile();

        when(semanticMemory.findAllCurrent(filter)).thenReturn(List.of(
                entity("profile-1", EntityType.CUSTOM, "__consolidated_profile", "用户长期偏好安静务实的回答。",
                        Map.of(), MemoryTrustLevel.DERIVED, MemoryEvidenceKind.DERIVED,
                        LifecycleState.ACTIVE, 0.7f, 0.9f, List.of("pref-1", "habit-1")),
                entity("pref-1", EntityType.PREFERENCE, "tone", "用户喜欢直接回答",
                        Map.of(), MemoryTrustLevel.EXPLICIT, MemoryEvidenceKind.USER_CONFIRMED,
                        LifecycleState.ACTIVE, 0.9f, 0.8f)
        ));

        HotMemoryDigest digest = service.build(filter, "personal");

        var profileSection = digest.sections().stream()
                .filter(section -> section.kind() == HotMemorySectionKind.USER_PROFILE)
                .findFirst()
                .orElseThrow();
        assertThat(profileSection.content()).contains("用户长期偏好安静务实的回答。");
        assertThat(profileSection.content()).doesNotContain("用户喜欢直接回答");
        assertThat(profileSection.sourceEntityIds()).containsExactly("profile-1", "pref-1", "habit-1");
    }

    @Test
    void 摘要内容会在注入前脱敏() {
        SemanticMemory semanticMemory = mock(SemanticMemory.class);
        var service = new HotMemoryDigestService(
                semanticMemory,
                new MemoryProperties(),
                null,
                new DataRedactor(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        var filter = MemoryReadFilter.all();

        when(semanticMemory.findAllCurrent(filter)).thenReturn(List.of(
                entity("fact-1", EntityType.PERSON, "联系人", "手机号 13812345678",
                        Map.of(), MemoryTrustLevel.VERIFIED, MemoryEvidenceKind.TOOL_VERIFIED,
                        LifecycleState.ACTIVE, 0.9f, 0.8f)
        ));

        HotMemoryDigest digest = service.build(filter, "personal");

        String allContent = digest.sections().stream()
                .map(HotMemoryDigest.HotMemorySection::content)
                .reduce("", (left, right) -> left + "\n" + right);
        assertThat(allContent).contains("138****5678");
        assertThat(allContent).doesNotContain("13812345678");
    }

    @Test
    void L4高置信偏好会并入用户画像且必须有可消费源实体() {
        SemanticMemory semanticMemory = mock(SemanticMemory.class);
        ProceduralMemory proceduralMemory = mock(ProceduralMemory.class);
        var service = new HotMemoryDigestService(
                semanticMemory,
                new MemoryProperties(),
                proceduralMemory,
                null,
                Clock.fixed(NOW, ZoneOffset.UTC));
        var filter = MemoryReadFilter.userMemory();
        var source = entity("pref-source", EntityType.PREFERENCE, "response_language", "用户偏好中文回答",
                Map.of(), MemoryTrustLevel.EXPLICIT, MemoryEvidenceKind.USER_CONFIRMED,
                LifecycleState.ACTIVE, 0.9f, 0.8f);

        when(semanticMemory.findAllCurrent(filter)).thenReturn(List.of(source));
        when(proceduralMemory.getPreferences("user-preference")).thenReturn(List.of(
                new PreferenceRule("rule-1", "user-preference", "response_language", "中文",
                        0.9f, "test", 3, NOW.minusSeconds(3600), NOW, source.id(), null),
                new PreferenceRule("rule-low", "user-preference", "tone", "随意",
                        0.5f, "test", 1, NOW.minusSeconds(3600), NOW, source.id(), null),
                new PreferenceRule("rule-missing-source", "user-preference", "format", "markdown",
                        0.95f, "test", 2, NOW.minusSeconds(3600), NOW, "missing-source", null)
        ));

        HotMemoryDigest digest = service.build(filter, "personal");

        var profileSection = digest.sections().stream()
                .filter(section -> section.kind() == HotMemorySectionKind.USER_PROFILE)
                .findFirst()
                .orElseThrow();
        assertThat(profileSection.content())
                .contains("response_language = 中文")
                .contains("L4偏好")
                .doesNotContain("tone = 随意")
                .doesNotContain("format = markdown");
        assertThat(profileSection.sourceEntityIds()).containsExactly(source.id());
    }

    private TemporalEntity entity(String id,
                                  EntityType type,
                                  String name,
                                  String description,
                                  Map<String, Object> properties,
                                  MemoryTrustLevel trustLevel,
                                  MemoryEvidenceKind evidenceKind,
                                  LifecycleState lifecycleState,
                                  float trustScore,
                                  float importanceScore) {
        return entity(id, type, name, description, properties, trustLevel, evidenceKind,
                lifecycleState, trustScore, importanceScore, List.of());
    }

    private TemporalEntity entity(String id,
                                  EntityType type,
                                  String name,
                                  String description,
                                  Map<String, Object> properties,
                                  MemoryTrustLevel trustLevel,
                                  MemoryEvidenceKind evidenceKind,
                                  LifecycleState lifecycleState,
                                  float trustScore,
                                  float importanceScore,
                                  List<String> derivationSources) {
        return new TemporalEntity(
                id,
                type,
                name,
                description,
                properties,
                1,
                true,
                NOW.minusSeconds(3600),
                null,
                "session-1",
                trustScore,
                importanceScore,
                0,
                null,
                NOW.minusSeconds(3600),
                NOW.minusSeconds(300),
                lifecycleState,
                null,
                null,
                Temporality.PERSISTENT,
                null,
                !derivationSources.isEmpty(),
                derivationSources,
                evidenceKind,
                trustLevel,
                trustScore,
                1,
                NOW.minusSeconds(300));
    }
}
