package com.lifepilot.skill.registry;

import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.event.SkillRegistryEvent;
import com.lifepilot.skill.model.*;
import com.lifepilot.skill.registry.SkillDefinitionValidator.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@link SkillRegistry} 单元测试。
 *
 * @author zsg
 * @since 2026-07-28
 */
class SkillRegistryTest {

    private SkillDefinitionValidator validator;
    private SkillSearchIndex searchIndex;
    private ApplicationEventPublisher eventPublisher;
    private SkillRegistry registry;

    @BeforeEach
    void setUp() {
        validator = mock(SkillDefinitionValidator.class);
        searchIndex = mock(SkillSearchIndex.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        registry = new SkillRegistry(validator, searchIndex, eventPublisher, new SkillConfigProperties());

        // 默认校验通过
        when(validator.validate(any())).thenReturn(new ValidationResult(true, List.of()));
        // 默认搜索返回空
        when(searchIndex.search(anyString(), anyInt())).thenReturn(List.of());
    }

    /** 构建合法的 SkillDefinition。 */
    private SkillDefinition builtinDefinition(String id) {
        return SkillDefinition.builder()
                .id(id)
                .name("测试技能-" + id)
                .description("测试描述-" + id)
                .version("1.0.0")
                .source(new SkillSource.UserDefined("/test", null))
                .instructions("你是测试助手")
                .suggestedTools(List.of("tool-a"))
                .metadata(Map.of())
                .build();
    }

    private SkillDefinition userDefinedDefinition(String id) {
        return builtinDefinition(id).toBuilder()
                .source(new SkillSource.UserDefined("/path/to/skill"))
                .build();
    }

    // ─────────────────────────────────────────────
    //  register — 校验失败
    // ─────────────────────────────────────────────

    @Test
    void 注册_校验失败_返回false() {
        when(validator.validate(any())).thenReturn(
                new ValidationResult(false, List.of("ID 格式不合法")));

        boolean result = registry.register(builtinDefinition("todo"));

        assertThat(result).isFalse();
        assertThat(registry.find("todo")).isEmpty();
        verify(eventPublisher, never()).publishEvent(any());
        verify(searchIndex, never()).index(any());
    }

    // ─────────────────────────────────────────────
    //  register — 新注册
    // ─────────────────────────────────────────────

    @Test
    void 注册_新Skill_成功并发布SkillRegistered事件() {
        var def = builtinDefinition("todo");

        boolean result = registry.register(def);

        assertThat(result).isTrue();
        assertThat(registry.find("todo")).isPresent().contains(def);
        verify(searchIndex).index(def);

        var captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(SkillRegistryEvent.SkillRegistered.class);
        var event = (SkillRegistryEvent.SkillRegistered) captor.getValue();
        assertThat(event.definition()).isEqualTo(def);
    }

    // ─────────────────────────────────────────────
    //  register — BUILTIN 保护
    // ─────────────────────────────────────────────

    @Test
    void 注册_覆盖BUILTIN来源_拒绝() {
        var original = builtinDefinition("todo");
        registry.register(original);
        reset(eventPublisher, searchIndex);

        var override = builtinDefinition("todo").toBuilder()
                .name("新名称")
                .source(new SkillSource.UserDefined("/new.yaml"))
                .build();

        boolean result = registry.register(override);

        assertThat(result).isFalse();
        // 原始定义不变
        assertThat(registry.find("todo")).isPresent().contains(original);
        verify(eventPublisher, never()).publishEvent(any());
        verify(searchIndex, never()).index(any());
    }

    // ─────────────────────────────────────────────
    //  register — 非 BUILTIN 更新
    // ─────────────────────────────────────────────

    @Test
    void 注册_覆盖非BUILTIN来源_允许更新并发布SkillUpdated事件() {
        var original = userDefinedDefinition("custom");
        registry.register(original);
        reset(eventPublisher, searchIndex);

        var updated = userDefinedDefinition("custom").toBuilder()
                .name("更新后的名称")
                .build();

        boolean result = registry.register(updated);

        assertThat(result).isTrue();
        assertThat(registry.find("custom")).isPresent().contains(updated);
        verify(searchIndex).index(updated);

        var captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(SkillRegistryEvent.SkillUpdated.class);
        var event = (SkillRegistryEvent.SkillUpdated) captor.getValue();
        assertThat(event.oldDefinition()).isEqualTo(original);
        assertThat(event.newDefinition()).isEqualTo(updated);
    }

    // ─────────────────────────────────────────────
    //  unregister
    // ─────────────────────────────────────────────

    @Test
    void 注销_已注册Skill_成功并发布SkillUnregistered事件() {
        registry.register(builtinDefinition("todo"));
        reset(eventPublisher);

        boolean result = registry.unregister("todo");

        assertThat(result).isTrue();
        assertThat(registry.find("todo")).isEmpty();
        verify(searchIndex).remove("todo");

        var captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(SkillRegistryEvent.SkillUnregistered.class);
        var event = (SkillRegistryEvent.SkillUnregistered) captor.getValue();
        assertThat(event.skillId()).isEqualTo("todo");
    }

    @Test
    void 注销_不存在的Skill_返回false() {
        boolean result = registry.unregister("nonexistent");

        assertThat(result).isFalse();
        verify(eventPublisher, never()).publishEvent(any());
        verify(searchIndex, never()).remove(anyString());
    }

    // ─────────────────────────────────────────────
    //  find
    // ─────────────────────────────────────────────

    @Test
    void 查找_已注册Skill_返回Optional包含定义() {
        var def = builtinDefinition("todo");
        registry.register(def);

        Optional<SkillDefinition> found = registry.find("todo");

        assertThat(found).isPresent().contains(def);
    }

    @Test
    void 查找_未注册Skill_返回空Optional() {
        assertThat(registry.find("nonexistent")).isEmpty();
    }

    // ─────────────────────────────────────────────
    //  search
    // ─────────────────────────────────────────────

    @Test
    void 搜索_委托给SkillSearchIndex并映射回SkillDefinition() {
        var def = builtinDefinition("todo");
        registry.register(def);
        when(searchIndex.search("待办", 10)).thenReturn(
                List.of(new SkillSearchIndex.SearchResult("todo", 0.9)));

        List<SkillDefinition> results = registry.search("待办");

        assertThat(results).hasSize(1).contains(def);
    }

    @Test
    void 搜索_过滤已注销但索引未清理的条目() {
        when(searchIndex.search("待办", 10)).thenReturn(
                List.of(new SkillSearchIndex.SearchResult("deleted-skill", 0.8)));

        List<SkillDefinition> results = registry.search("待办");

        assertThat(results).isEmpty();
    }

    // ─────────────────────────────────────────────
    //  listSummaries
    // ─────────────────────────────────────────────

    @Test
    void 列出摘要_返回所有已注册Skill的摘要() {
        registry.register(builtinDefinition("todo"));
        registry.register(userDefinedDefinition("custom"));

        List<String> summaries = registry.listSummaries();

        assertThat(summaries).hasSize(2);
        assertThat(summaries).anyMatch(s -> s.contains("todo"));
        assertThat(summaries).anyMatch(s -> s.contains("custom"));
    }

    @Test
    void 列出摘要_空注册中心_返回空列表() {
        assertThat(registry.listSummaries()).isEmpty();
    }

    // ─────────────────────────────────────────────
    //  unregisterBySource
    // ─────────────────────────────────────────────

    @Test
    void 按来源注销_仅移除匹配来源的Skill() {
        registry.register(builtinDefinition("builtin-1"));
        registry.register(userDefinedDefinition("user-1"));
        registry.register(userDefinedDefinition("user-2"));

        int count = registry.unregisterBySource(SkillSource.UserDefined.class);

        assertThat(count).isEqualTo(2);
        assertThat(registry.find("builtin-1")).isPresent();
        assertThat(registry.find("user-1")).isEmpty();
        assertThat(registry.find("user-2")).isEmpty();
    }

    @Test
    void 按来源注销_无匹配_返回0() {
        registry.register(builtinDefinition("builtin-1"));

        int count = registry.unregisterBySource(SkillSource.AutoGenerated.class);

        assertThat(count).isEqualTo(0);
        assertThat(registry.find("builtin-1")).isPresent();
    }
}
