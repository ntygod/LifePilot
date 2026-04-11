package com.lifepilot.skill;

import com.lifepilot.skill.disclosure.SkillDisclosureTool;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.lang.NonNull;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Skill 渐进式披露 — Agent 集成测试。
 *
 * <p>验证 Spring Context 加载成功、核心 Bean 存在、
 * 旧 skills 工具不存在。使用 {@link SkillTestSupport} 共享配置，
 * 避免 Bean 定义冲突。</p>
 *
 * @author zsg
 * @since 2026-03-19
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = SkillTestSupport.class)
class SkillDisclosure_Agent_集成测试 {

    private static final String DB_ID = UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void configure(@NonNull DynamicPropertyRegistry registry) {
        var tmpDir = System.getProperty("java.io.tmpdir");
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + Path.of(tmpDir, "skill-disclosure-it-" + DB_ID + ".db")
                        .toString().replace("\\", "/"));
        registry.add("lifepilot.skills.enabled", () -> "true");
        registry.add("lifepilot.skills.directory",
                () -> Path.of(tmpDir, "skill-disclosure-it-skills-" + DB_ID)
                        .toString().replace("\\", "/"));
    }

    @Autowired
    private ApplicationContext ctx;

    @Test
    void SpringContext_加载成功() {
        assertThat(ctx).isNotNull();
    }

    @Test
    void 新Bean_SkillDisclosureTool_存在() {
        assertThat(ctx.containsBean("skillDisclosureTool")).isTrue();
        assertThat(ctx.getBean("skillDisclosureTool")).isInstanceOf(SkillDisclosureTool.class);
    }

    @Test
    void 新Bean_SkillRegistry_存在() {
        assertThat(ctx.containsBean("skillRegistry")).isTrue();
        assertThat(ctx.getBean("skillRegistry")).isInstanceOf(SkillRegistry.class);
    }

    @Test
    void 旧Bean_SkillToToolBridge_不存在() {
        assertThat(ctx.containsBean("skillToToolBridge")).isFalse();
    }

    @Test
    void DynamicToolRegistry_不包含旧skills工具() {
        var registry = ctx.getBean(DynamicToolRegistry.class);
        assertThat(registry.resolve("skills")).isEmpty();
    }
}
