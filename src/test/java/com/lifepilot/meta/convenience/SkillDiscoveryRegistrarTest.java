package com.lifepilot.meta.convenience;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.skill.markdown.MarkdownSkillParser;
import com.lifepilot.skill.model.*;
import com.lifepilot.skill.registry.SkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * SkillDiscoveryRegistrar 单元测试 — 验证 SKILL.md 解析和注册。
 *
 * @author zsg
 * @since 2026-03-08
 */
class SkillDiscoveryRegistrarTest {

    private SkillRegistry skillRegistry;
    private MarkdownSkillParser markdownSkillParser;
    private MetaProperties properties;
    private SkillDiscoveryRegistrar registrar;

    @BeforeEach
    void setUp() {
        skillRegistry = mock(SkillRegistry.class);
        markdownSkillParser = new MarkdownSkillParser();
        properties = new MetaProperties();

        when(skillRegistry.register(any())).thenReturn(true);

        registrar = new SkillDiscoveryRegistrar(skillRegistry, markdownSkillParser, properties);
    }

    @Test
    void registerFindSkills_解析内置SKILL_MD并注册成功() {
        // 使用默认配置路径，读取实际的 classpath 资源
        registrar.registerFindSkills();

        // 验证注册被调用
        var captor = ArgumentCaptor.forClass(SkillDefinition.class);
        verify(skillRegistry).register(captor.capture());

        var definition = captor.getValue();
        assertThat(definition.id()).isEqualTo("builtin.find-skills");
        assertThat(definition.name()).isEqualTo("Skill 发现与安装");
        assertThat(definition.source()).isInstanceOf(SkillSource.Builtin.class);
        assertThat(definition.allowedTools()).contains("builtin.shell.exec");
        assertThat(definition.systemPrompt()).contains("Skill 发现");
    }

    @Test
    void registerFindSkills_source覆盖为Builtin() {
        registrar.registerFindSkills();

        verify(skillRegistry).register(argThat(def ->
                def.source() instanceof SkillSource.Builtin));
    }

    @Test
    void registerFindSkills_资源路径不存在时跳过注册() {
        // 设置不存在的路径
        properties.getSkillDiscovery().setBuiltinSkillPath("nonexistent/path");

        registrar.registerFindSkills();

        // 不应调用注册
        verify(skillRegistry, never()).register(any());
    }

    @Test
    void registerFindSkills_解析失败时跳过注册() {
        // 使用 Mock parser 返回失败结果
        var mockParser = mock(MarkdownSkillParser.class);
        when(mockParser.parse(any())).thenReturn(
                new MarkdownSkillParser.ParseResult(false, null, List.of("解析错误"), null));

        var registrarWithMockParser = new SkillDiscoveryRegistrar(
                skillRegistry, mockParser, properties);

        registrarWithMockParser.registerFindSkills();

        verify(skillRegistry, never()).register(any());
    }

    @Test
    void registerFindSkills_注册失败时记录警告() {
        when(skillRegistry.register(any())).thenReturn(false);

        // 不应抛异常
        registrar.registerFindSkills();

        verify(skillRegistry).register(any());
    }

    @Test
    void afterPropertiesSet_触发注册() {
        registrar.afterPropertiesSet();

        verify(skillRegistry).register(any());
    }
}
