package com.lifepilot.skill.builtin;

import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.Comparator;
import java.util.List;

/**
 * 内置 Skill 注册器 — ApplicationReadyEvent 时按 order 注册。
 *
 * <p>收集所有 {@link BuiltinSkillProvider} Bean，按 {@link BuiltinSkill#order()} 升序排列，
 * 依次注册工具和 Skill 定义。注册前检查 {@link SkillConfigProperties.Builtin} 配置开关，
 * 禁用的 Skill 跳过注册。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class BuiltinSkillRegistrar {

    private static final Logger log = LoggerFactory.getLogger(BuiltinSkillRegistrar.class);

    private final List<BuiltinSkillProvider> providers;
    private final SkillRegistry skillRegistry;
    private final DynamicToolRegistry toolRegistry;
    private final SkillConfigProperties skillConfigProperties;

    public BuiltinSkillRegistrar(List<BuiltinSkillProvider> providers,
                                 SkillRegistry skillRegistry,
                                 DynamicToolRegistry toolRegistry,
                                 SkillConfigProperties skillConfigProperties) {
        this.providers = providers;
        this.skillRegistry = skillRegistry;
        this.toolRegistry = toolRegistry;
        this.skillConfigProperties = skillConfigProperties;
    }

    /**
     * 收集所有 BuiltinSkillProvider Bean，按 order 排序后注册。
     *
     * <p>注册流程：
     * <ol>
     *   <li>按 {@link BuiltinSkill#order()} 升序排列所有 provider</li>
     *   <li>检查 {@link SkillConfigProperties.Builtin#isEnabled(String)} 配置开关，禁用时跳过</li>
     *   <li>对每个启用的 provider，先调用 {@link BuiltinSkillProvider#registerTools(DynamicToolRegistry)} 注册工具</li>
     *   <li>再调用 {@link SkillRegistry#register} 注册 Skill 定义</li>
     *   <li>任一步骤失败记录 ERROR 日志，继续处理下一个 provider</li>
     * </ol>
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void registerAll() {
        int registered = 0;
        int skipped = 0;

        // 按 @BuiltinSkill 注解的 order 升序排列
        List<BuiltinSkillProvider> sorted = providers.stream()
                .sorted(Comparator.comparingInt(this::getOrder))
                .toList();

        for (BuiltinSkillProvider provider : sorted) {
            String skillId = getSkillId(provider);

            // 检查配置开关
            if (!skillConfigProperties.getBuiltin().isEnabled(skillId)) {
                log.info("内置 Skill 已禁用，跳过注册: skillId={}", skillId);
                skipped++;
                continue;
            }

            try {
                // 注册工具和 Skill 定义
                provider.registerTools(toolRegistry);
                skillRegistry.register(provider.provide());

                registered++;
            } catch (Exception e) {
                log.error("内置 Skill 注册失败: provider={}, 原因={}",
                        provider.getClass().getSimpleName(), e.getMessage(), e);
            }
        }

        log.info("内置 Skill 注册完成: 成功={}, 跳过={}, 总数={}", registered, skipped, sorted.size());
    }

    /**
     * 获取 provider 上 {@link BuiltinSkill} 注解的 order 值。
     *
     * <p>若注解缺失，返回 {@link Integer#MAX_VALUE} 作为默认值，排在最后。</p>
     *
     * @param provider 内置 Skill 提供者
     * @return order 值
     */
    private int getOrder(BuiltinSkillProvider provider) {
        BuiltinSkill annotation = provider.getClass().getAnnotation(BuiltinSkill.class);
        return annotation != null ? annotation.order() : Integer.MAX_VALUE;
    }

    /**
     * 获取 provider 上 {@link BuiltinSkill} 注解的 id 值。
     *
     * <p>若注解缺失，返回类名作为默认值。</p>
     *
     * @param provider 内置 Skill 提供者
     * @return Skill ID
     */
    private String getSkillId(BuiltinSkillProvider provider) {
        BuiltinSkill annotation = provider.getClass().getAnnotation(BuiltinSkill.class);
        return annotation != null ? annotation.id() : provider.getClass().getSimpleName();
    }
}
