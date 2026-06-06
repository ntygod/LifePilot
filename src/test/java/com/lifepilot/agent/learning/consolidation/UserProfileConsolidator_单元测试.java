package com.lifepilot.agent.learning.consolidation;

import com.lifepilot.agent.learning.consolidation.UserProfileConsolidator;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.memory.episodic.ConversationRecord;
import com.lifepilot.memory.store.episodic.EpisodicMemory;
import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.governance.lifecycle.Temporality;
import com.lifepilot.memory.store.procedural.PreferenceRule;
import com.lifepilot.memory.store.procedural.ProceduralMemory;
import com.lifepilot.memory.consumption.quality.MemoryEvidenceKind;
import com.lifepilot.memory.consumption.quality.MemoryTrustLevel;
import com.lifepilot.memory.store.scope.MemoryReadFilter;
import com.lifepilot.memory.store.scope.MemoryWriteContext;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UserProfileConsolidator 单元测试。
 *
 * @author zsg
 * @since 2026-05-07
 */
@ExtendWith(MockitoExtension.class)
class UserProfileConsolidator_单元测试 {

    private static final String PROFILE_ENTITY_NAME = "__consolidated_profile";
    private static final String PROFILE_SOURCE_COUNT_KEY = "profileSourceCount";
    private static final String PROFILE_CONSOLIDATED_AT_KEY = "profileConsolidatedAt";
    private static final Instant FIXED_TIME = Instant.parse("2026-05-07T00:00:00Z");

    @Mock
    private SemanticMemory semanticMemory;

    @Mock
    private EpisodicMemory episodicMemory;

    @Mock
    private ProceduralMemory proceduralMemory;

    @Mock
    private GenerationRouter generationRouter;

    @Mock
    private PromptRegistry promptRegistry;

    private UserProfileConsolidator consolidator;

    @BeforeEach
    void 初始化() {
        consolidator = new UserProfileConsolidator(
                semanticMemory, episodicMemory, proceduralMemory, generationRouter, promptRegistry,
                Duration.ofSeconds(120));
    }

    @Test
    void 首次巩固应写入源签名元数据() {
        // given
        var preference = 画像碎片("pref-1", EntityType.PREFERENCE, "偏好中文回复", "用户希望默认使用中文沟通");
        准备基础输入(List.of(preference), List.of(), Optional.empty());
        when(promptRegistry.render(eq("generation/user-profile-consolidation"), anyMap()))
                .thenReturn("画像巩固 prompt");
        when(generationRouter.call(
                eq(LlmScene.BACKGROUND_ANALYSIS),
                eq("画像巩固 prompt"),
                isNull(),
                isNull(),
                isNull(),
                eq(GenerationCapability.CHAT),
                eq(Duration.ofSeconds(120)),
                eq(true)
        )).thenReturn(new LlmResponse("用户偏好中文回复。", null, null, List.of(), Map.of(), 10, 5, null, 0, "mock-provider", "mock-model", 100, false));

        // when
        consolidator.consolidate();

        // then
        var captor = ArgumentCaptor.forClass(TemporalEntity.class);
        verify(semanticMemory).upsertWithConflictDetection(
                captor.capture(), eq("user-profile-consolidation"), any(MemoryWriteContext.class));

        var saved = captor.getValue();
        assertThat(saved.name()).isEqualTo(PROFILE_ENTITY_NAME);
        assertThat(saved.type()).isEqualTo(EntityType.CUSTOM);
        assertThat(saved.description()).isEqualTo("用户偏好中文回复。");
        assertThat(saved.isDerived()).isTrue();
        assertThat(saved.derivationSources()).containsExactly("pref-1");
        assertThat(saved.properties())
                .containsKey(UserProfileConsolidator.PROFILE_SOURCE_SIGNATURE_KEY)
                .containsEntry(PROFILE_SOURCE_COUNT_KEY, 1);
        assertThat(String.valueOf(saved.properties().get(UserProfileConsolidator.PROFILE_SOURCE_SIGNATURE_KEY)))
                .matches("[0-9a-f]{64}");
        assertThat(Instant.parse(String.valueOf(saved.properties().get(PROFILE_CONSOLIDATED_AT_KEY))))
                .isNotNull();
    }

    @Test
    void 源签名相同应跳过LLM并避免重复写入() {
        // given
        var preference = 画像碎片("pref-1", EntityType.PREFERENCE, "偏好中文回复", "用户希望默认使用中文沟通");
        准备基础输入(List.of(preference), List.of(), Optional.empty());
        when(promptRegistry.render(eq("generation/user-profile-consolidation"), anyMap()))
                .thenReturn("画像巩固 prompt");
        when(generationRouter.call(
                eq(LlmScene.BACKGROUND_ANALYSIS),
                eq("画像巩固 prompt"),
                isNull(),
                isNull(),
                isNull(),
                eq(GenerationCapability.CHAT),
                eq(Duration.ofSeconds(120)),
                eq(true)
        )).thenReturn(new LlmResponse("用户偏好中文回复。", null, null, List.of(), Map.of(), 10, 5, null, 0, "mock-provider", "mock-model", 100, false));

        consolidator.consolidate();
        var captor = ArgumentCaptor.forClass(TemporalEntity.class);
        verify(semanticMemory).upsertWithConflictDetection(
                captor.capture(), eq("user-profile-consolidation"), any(MemoryWriteContext.class));
        var persistedProfile = 已持久化画像(captor.getValue());

        var secondFixture = 新巩固器();
        secondFixture.准备基础输入(List.of(preference), List.of(), Optional.of(persistedProfile));

        // when
        secondFixture.consolidator.consolidate();

        // then
        verify(secondFixture.generationRouter, never()).call(
                any(), any(), any(), any(), any(), any(), any(), eq(true));
        verify(secondFixture.semanticMemory, never()).upsertWithConflictDetection(
                any(TemporalEntity.class), eq("user-profile-consolidation"), any(MemoryWriteContext.class));
    }

    @Test
    void 源已变化但距上次巩固不足两小时应延后() {
        // given
        var oldProperties = Map.<String, Object>of(
                UserProfileConsolidator.PROFILE_SOURCE_SIGNATURE_KEY, "old-signature",
                PROFILE_SOURCE_COUNT_KEY, 1,
                PROFILE_CONSOLIDATED_AT_KEY, Instant.now().minus(Duration.ofMinutes(30)).toString()
        );
        var existingProfile = 已持久化画像(new TemporalEntity(
                "profile-1",
                EntityType.CUSTOM,
                PROFILE_ENTITY_NAME,
                "旧画像",
                oldProperties,
                1,
                true,
                FIXED_TIME,
                null,
                null,
                1.0f,
                0.9f,
                0,
                null,
                FIXED_TIME,
                FIXED_TIME,
                LifecycleState.ACTIVE,
                null,
                null,
                Temporality.PERSISTENT,
                null,
                true,
                List.of("pref-1")
        ));
        var changedPreference = 画像碎片("pref-1", EntityType.PREFERENCE, "偏好中文回复", "用户希望默认使用中文，并要求结论更靠前");
        准备基础输入(List.of(changedPreference), List.of(), Optional.of(existingProfile));

        // when
        consolidator.consolidate();

        // then
        verify(generationRouter, never()).call(
                any(), any(), any(), any(), any(), any(), any(), eq(true));
        verify(semanticMemory, never()).upsertWithConflictDetection(
                any(TemporalEntity.class), eq("user-profile-consolidation"), any(MemoryWriteContext.class));
    }

    @Test
    void 手动强制巩固应绕过两小时防抖() {
        // given
        var oldProperties = Map.<String, Object>of(
                UserProfileConsolidator.PROFILE_SOURCE_SIGNATURE_KEY, "old-signature",
                PROFILE_SOURCE_COUNT_KEY, 1,
                PROFILE_CONSOLIDATED_AT_KEY, Instant.now().minus(Duration.ofMinutes(30)).toString()
        );
        var existingProfile = 已持久化画像(new TemporalEntity(
                "profile-1",
                EntityType.CUSTOM,
                PROFILE_ENTITY_NAME,
                "旧画像",
                oldProperties,
                1,
                true,
                FIXED_TIME,
                null,
                null,
                1.0f,
                0.9f,
                0,
                null,
                FIXED_TIME,
                FIXED_TIME,
                LifecycleState.ACTIVE,
                null,
                null,
                Temporality.PERSISTENT,
                null,
                true,
                List.of("pref-1")
        ));
        var changedPreference = 画像碎片("pref-1", EntityType.PREFERENCE,
                "偏好中文回复", "用户希望默认使用中文，并要求结论更靠前");
        准备基础输入(List.of(changedPreference), List.of(), Optional.of(existingProfile));
        when(promptRegistry.render(eq("generation/user-profile-consolidation"), anyMap()))
                .thenReturn("画像巩固 prompt");
        when(generationRouter.call(
                eq(LlmScene.BACKGROUND_ANALYSIS),
                eq("画像巩固 prompt"),
                isNull(),
                isNull(),
                isNull(),
                eq(GenerationCapability.CHAT),
                eq(Duration.ofSeconds(120)),
                eq(true)
        )).thenReturn(new LlmResponse("用户偏好中文且喜欢结论靠前。", null, null, List.of(), Map.of(), 10, 5, null, 0, "mock-provider", "mock-model", 100, false));

        // when
        consolidator.consolidate(true);

        // then
        verify(semanticMemory).upsertWithConflictDetection(
                any(TemporalEntity.class), eq("user-profile-consolidation"), any(MemoryWriteContext.class));
    }

    private void 准备基础输入(List<TemporalEntity> fragments,
                         List<PreferenceRule> rules,
                         Optional<TemporalEntity> existingProfile) {
        when(semanticMemory.findCurrentByType(eq(EntityType.PREFERENCE), eq(MemoryReadFilter.userProfile())))
                .thenReturn(fragments.stream().filter(e -> e.type() == EntityType.PREFERENCE).toList());
        when(semanticMemory.findCurrentByType(eq(EntityType.HABIT), eq(MemoryReadFilter.userProfile())))
                .thenReturn(fragments.stream().filter(e -> e.type() == EntityType.HABIT).toList());
        when(semanticMemory.findCurrentByType(eq(EntityType.GOAL), eq(MemoryReadFilter.userProfile())))
                .thenReturn(fragments.stream().filter(e -> e.type() == EntityType.GOAL).toList());
        when(semanticMemory.findCurrentByType(eq(EntityType.SKILL), eq(MemoryReadFilter.userProfile())))
                .thenReturn(fragments.stream().filter(e -> e.type() == EntityType.SKILL).toList());
        when(episodicMemory.getRecent(eq(Duration.ofDays(7))))
                .thenReturn(List.<ConversationRecord>of());
        when(proceduralMemory.getPreferences(eq("proactive-domain")))
                .thenReturn(rules.stream().filter(r -> "proactive-domain".equals(r.category())).toList());
        when(proceduralMemory.getPreferences(eq("user-preference")))
                .thenReturn(rules.stream().filter(r -> "user-preference".equals(r.category())).toList());
        when(semanticMemory.findCurrentByNameAndType(
                eq(PROFILE_ENTITY_NAME), eq(EntityType.CUSTOM), eq(MemoryReadFilter.userProfile())))
                .thenReturn(existingProfile);
    }

    private TemporalEntity 画像碎片(String id, EntityType type, String name, String description) {
        return new TemporalEntity(
                id,
                type,
                name,
                description,
                Map.of(),
                1,
                true,
                FIXED_TIME,
                null,
                "session-1",
                0.9f,
                0.7f,
                0,
                null,
                FIXED_TIME,
                FIXED_TIME)
                .withQuality(MemoryEvidenceKind.USER_EXPLICIT, MemoryTrustLevel.EXPLICIT, 0.9f, 1, FIXED_TIME);
    }

    private TemporalEntity 已持久化画像(TemporalEntity profile) {
        return profile.withQuality(MemoryEvidenceKind.DERIVED, MemoryTrustLevel.DERIVED, 0.6f, 1, FIXED_TIME);
    }

    private Fixture 新巩固器() {
        var fixture = new Fixture(
                org.mockito.Mockito.mock(SemanticMemory.class),
                org.mockito.Mockito.mock(EpisodicMemory.class),
                org.mockito.Mockito.mock(ProceduralMemory.class),
                org.mockito.Mockito.mock(GenerationRouter.class),
                org.mockito.Mockito.mock(PromptRegistry.class)
        );
        return fixture;
    }

    private record Fixture(SemanticMemory semanticMemory,
                           EpisodicMemory episodicMemory,
                           ProceduralMemory proceduralMemory,
                           GenerationRouter generationRouter,
                           PromptRegistry promptRegistry,
                           UserProfileConsolidator consolidator) {

        private Fixture(SemanticMemory semanticMemory,
                        EpisodicMemory episodicMemory,
                        ProceduralMemory proceduralMemory,
                        GenerationRouter generationRouter,
                        PromptRegistry promptRegistry) {
            this(semanticMemory, episodicMemory, proceduralMemory, generationRouter, promptRegistry,
                    new UserProfileConsolidator(
                            semanticMemory, episodicMemory, proceduralMemory, generationRouter, promptRegistry,
                            Duration.ofSeconds(120)));
        }

        private void 准备基础输入(List<TemporalEntity> fragments,
                              List<PreferenceRule> rules,
                              Optional<TemporalEntity> existingProfile) {
            when(semanticMemory.findCurrentByType(eq(EntityType.PREFERENCE), eq(MemoryReadFilter.userProfile())))
                    .thenReturn(fragments.stream().filter(e -> e.type() == EntityType.PREFERENCE).toList());
            when(semanticMemory.findCurrentByType(eq(EntityType.HABIT), eq(MemoryReadFilter.userProfile())))
                    .thenReturn(fragments.stream().filter(e -> e.type() == EntityType.HABIT).toList());
            when(semanticMemory.findCurrentByType(eq(EntityType.GOAL), eq(MemoryReadFilter.userProfile())))
                    .thenReturn(fragments.stream().filter(e -> e.type() == EntityType.GOAL).toList());
            when(semanticMemory.findCurrentByType(eq(EntityType.SKILL), eq(MemoryReadFilter.userProfile())))
                    .thenReturn(fragments.stream().filter(e -> e.type() == EntityType.SKILL).toList());
            when(episodicMemory.getRecent(eq(Duration.ofDays(7))))
                    .thenReturn(List.<ConversationRecord>of());
            when(proceduralMemory.getPreferences(eq("proactive-domain")))
                    .thenReturn(rules.stream().filter(r -> "proactive-domain".equals(r.category())).toList());
            when(proceduralMemory.getPreferences(eq("user-preference")))
                    .thenReturn(rules.stream().filter(r -> "user-preference".equals(r.category())).toList());
            when(semanticMemory.findCurrentByNameAndType(
                    eq(PROFILE_ENTITY_NAME), eq(EntityType.CUSTOM), eq(MemoryReadFilter.userProfile())))
                    .thenReturn(existingProfile);
        }
    }
}
