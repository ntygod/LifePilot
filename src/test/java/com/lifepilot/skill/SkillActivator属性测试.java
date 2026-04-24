package com.lifepilot.skill;

import com.lifepilot.skill.activation.SkillActivator;
import com.lifepilot.skill.activation.SkillMetricsTracker;
import com.lifepilot.skill.install.SkillInstallation;
import com.lifepilot.skill.install.SkillInstallationRepository;
import com.lifepilot.skill.install.SkillSourceType;
import com.lifepilot.skill.model.SkillActivation;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import com.lifepilot.skill.registry.SkillRegistry;
import net.jqwik.api.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.lang.NonNull;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 2+3: 激活内容一致性 + 激活幂等性 属性测试（v2）。
 *
 * <p>Property 2: 对任意已启用并已注册的 SkillDefinition，
 * activate() 返回的 SkillActivation 的 name/instructions/suggestedTools 与原始定义一致。</p>
 *
 * <p>Property 3: 对同一 Skill 多次调用 activate()，返回内容完全相同（幂等性）。</p>
 *
 * <p>v2 迁移要点：Activator 构造器新增 {@link SkillInstallationRepository}；测试用内存桩替代。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
class SkillActivator属性测试 {

    // ── Property 2: 激活结果与定义内容一致 ──

    @Property(tries = 100)
    @Label("Feature: skill-system-refactor, Property 2: 激活结果与定义内容一致")
    void 激活结果的instructions和suggestedTools与原始定义一致(
            @ForAll("activatableSkillDefinitions") SkillDefinition definition) {

        var registry = new StubSkillRegistry();
        registry.addSkill(definition);
        var repo = new StubInstallationRepository();
        repo.register(definition.id(), "/tmp/skills/" + definition.id(), true);
        var activator = new SkillActivator(registry, repo, new SkillMetricsTracker(), new NoOpEventPublisher());

        SkillActivation activation = activator.activate(definition.id());

        // **Validates: AC-2.1**
        assertThat(activation.name()).isEqualTo(definition.id());
        assertThat(activation.instructions()).isEqualTo(definition.instructions());
        assertThat(activation.suggestedTools()).isEqualTo(definition.suggestedTools());
    }

    // ── Property 3: 激活幂等性 ──

    @Property(tries = 100)
    @Label("Feature: skill-system-refactor, Property 3: 激活幂等性")
    void 多次激活同一Skill_返回内容完全相同(
            @ForAll("activatableSkillDefinitions") SkillDefinition definition) {

        var registry = new StubSkillRegistry();
        registry.addSkill(definition);
        var repo = new StubInstallationRepository();
        repo.register(definition.id(), "/tmp/skills/" + definition.id(), true);
        var activator = new SkillActivator(registry, repo, new SkillMetricsTracker(), new NoOpEventPublisher());

        SkillActivation first = activator.activate(definition.id());
        SkillActivation second = activator.activate(definition.id());
        SkillActivation third = activator.activate(definition.id());

        // **Validates: P-2**
        assertThat(second.name()).isEqualTo(first.name());
        assertThat(second.instructions()).isEqualTo(first.instructions());
        assertThat(second.suggestedTools()).isEqualTo(first.suggestedTools());

        assertThat(third.name()).isEqualTo(first.name());
        assertThat(third.instructions()).isEqualTo(first.instructions());
        assertThat(third.suggestedTools()).isEqualTo(first.suggestedTools());
    }

    // ── 生成器 ──

    @Provide
    Arbitrary<SkillDefinition> activatableSkillDefinitions() {
        var sourceArb = Arbitraries.of(
                (SkillSource) new SkillSource.UserDefined("/tmp/skills/test", null),
                new SkillSource.UserDefined("/tmp/skills/test2", null)
        );

        var idArb = Arbitraries.strings()
                .withCharRange('a', 'z')
                .numeric()
                .withChars('-')
                .ofMinLength(1).ofMaxLength(20)
                .filter(s -> !s.isBlank() && s.matches("^[a-z0-9-]+$"));

        var nameArb = safeString(1, 30);
        var descArb = safeString(1, 50);
        // 排除含 3 种占位符的字符串，避免 activate() 替换后断言失败
        var instructionsArb = safeString(1, 100)
                .filter(s -> !s.contains("{skill_scripts_dir}"))
                .filter(s -> !s.contains("{skill_references_dir}"))
                .filter(s -> !s.contains("{skill_dir}"));

        var toolArb = Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars('-', '_')
                .ofMinLength(1).ofMaxLength(15)
                .filter(s -> !s.isBlank());
        var toolsArb = toolArb.list().ofMinSize(0).ofMaxSize(5);

        return Combinators.combine(idArb, nameArb, descArb, instructionsArb, toolsArb, sourceArb)
                .as((id, name, desc, instructions, tools, source) ->
                        SkillDefinition.builder()
                                .id(id)
                                .name(name)
                                .description(desc)
                                .version("1.0.0")
                                .source(source)
                                .instructions(instructions)
                                .suggestedTools(tools)
                                .metadata(Map.of())
                                .build()
                );
    }

    private Arbitrary<String> safeString(int minLen, int maxLen) {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('A', 'Z')
                .withCharRange('0', '9')
                .withChars(' ', '-', '_')
                .ofMinLength(minLen).ofMaxLength(maxLen)
                .filter(s -> !s.isBlank());
    }

    // ── 桩内部类 ──

    /** 简易 SkillRegistry 桩 — 绕过构造依赖，仅实现 find()。 */
    private static class StubSkillRegistry extends SkillRegistry {

        private final java.util.concurrent.ConcurrentHashMap<String, SkillDefinition> store =
                new java.util.concurrent.ConcurrentHashMap<>();

        StubSkillRegistry() {
            super(null, null, null, new com.lifepilot.skill.config.SkillConfigProperties());
        }

        void addSkill(SkillDefinition definition) {
            store.put(definition.id(), definition);
        }

        @Override
        public Optional<SkillDefinition> find(String skillId) {
            return Optional.ofNullable(store.get(skillId));
        }
    }

    /** 简易 SkillInstallationRepository 桩 — 绕过 JdbcTemplate，仅实现 findByName + updateLastActivatedAt。 */
    private static class StubInstallationRepository extends SkillInstallationRepository {

        private final java.util.concurrent.ConcurrentHashMap<String, SkillInstallation> store =
                new java.util.concurrent.ConcurrentHashMap<>();

        StubInstallationRepository() {
            super(null);
        }

        void register(String name, String filePath, boolean enabled) {
            var now = Instant.now();
            store.put(name, new SkillInstallation(
                    name,
                    SkillSourceType.USER_IMPORTED,
                    null,
                    filePath,
                    "1.0.0",
                    enabled,
                    null,
                    null,
                    now,
                    now,
                    null
            ));
        }

        @Override
        public Optional<SkillInstallation> findByName(String name) {
            return Optional.ofNullable(store.get(name));
        }

        @Override
        public void updateLastActivatedAt(String name, Instant at) {
            // 属性测试不关注激活时间记录 —— 异步调用若失败也吞掉
        }
    }

    /** 空操作事件发布器。 */
    private static class NoOpEventPublisher implements ApplicationEventPublisher {
        @Override
        public void publishEvent(@NonNull Object event) {
            // 不做任何操作
        }
    }
}
