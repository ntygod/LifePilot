package com.lifepilot.skill;

import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import com.lifepilot.skill.registry.SkillDefinitionValidator;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.skill.registry.SkillSearchIndex;
import net.jqwik.api.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.lang.NonNull;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 4: 注册表计数一致性 属性测试。
 *
 * <p>验证：对任意 SkillRegistry 状态，listSummaries() 返回的摘要数量
 * 应等于 listAll() 返回的 SkillDefinition 数量。</p>
 *
 * <p><b>Validates: Requirements P-3</b></p>
 *
 * @author zsg
 * @since 2026-03-08
 */
class SkillRegistry属性测试 {

    // ── Property 4: 注册表计数一致性 ──

    @Property(tries = 100)
    @Label("Feature: skill-system-refactor, Property 4: 注册表计数一致性")
    void listSummaries数量等于listAll数量(
            @ForAll("registryOperationSequences") List<RegistryOp> operations) {

        // 构建真实 SkillRegistry（使用 Stub 依赖绕过 LLM 和 DynamicToolRegistry）
        var config = new SkillConfigProperties();
        var validator = new SkillDefinitionValidator(config);
        var searchIndex = new StubSkillSearchIndex();
        var eventPublisher = new NoOpEventPublisher();
        var registry = new SkillRegistry(validator, searchIndex, eventPublisher, config);

        // 执行随机操作序列
        for (RegistryOp op : operations) {
            switch (op) {
                case RegistryOp.Register reg -> registry.register(reg.definition());
                case RegistryOp.Unregister unreg -> registry.unregister(unreg.skillId());
            }
        }

        // **Validates: P-3** — 计数一致性
        assertThat(registry.listSummaries().size())
                .as("listSummaries() 数量应等于 listAll() 数量")
                .isEqualTo(registry.listAll().size());
    }

    // ── 操作类型 ──

    sealed interface RegistryOp {
        record Register(SkillDefinition definition) implements RegistryOp {}
        record Unregister(String skillId) implements RegistryOp {}
    }

    // ── 生成器 ──

    @Provide
    Arbitrary<List<RegistryOp>> registryOperationSequences() {
        // 使用固定 ID 池，使注销操作有较高概率命中已注册的 Skill
        var idPool = List.of("skill-a", "skill-b", "skill-c", "skill-d", "skill-e");

        var registerArb = Arbitraries.of(idPool).map(id ->
                (RegistryOp) new RegistryOp.Register(
                        SkillDefinition.builder()
                                .id(id)
                                .name(id + "-name")
                                .description(id + " 描述")
                                .version("1.0.0")
                                .source(new SkillSource.UserDefined("/tmp/" + id, null))
                                .instructions(id + " 的指令内容")
                                .suggestedTools(List.of())
                                .metadata(Map.of())
                                .build()
                )
        );

        var unregisterArb = Arbitraries.of(idPool).map(id ->
                (RegistryOp) new RegistryOp.Unregister(id)
        );

        var opArb = Arbitraries.frequencyOf(
                Tuple.of(3, registerArb),   // 注册概率更高
                Tuple.of(1, unregisterArb)
        );

        return opArb.list().ofMinSize(1).ofMaxSize(20);
    }

    // ── Stub 内部类 ──

    /** Stub SkillSearchIndex — 空操作。 */
    private static class StubSkillSearchIndex extends SkillSearchIndex {
        StubSkillSearchIndex() {
            super(null);
        }

        @Override
        public void index(SkillDefinition definition) {}

        @Override
        public void remove(String skillId) {}
    }

    /** 空操作事件发布器。 */
    private static class NoOpEventPublisher implements ApplicationEventPublisher {
        @Override
        public void publishEvent(@NonNull Object event) {}
    }
}
