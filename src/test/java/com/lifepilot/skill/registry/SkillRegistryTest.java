package com.lifepilot.skill.registry;

import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.event.SkillRegistryEvent;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import com.lifepilot.skill.registry.SkillDefinitionValidator.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

        when(validator.validate(any())).thenReturn(new ValidationResult(true, List.of()));
        when(searchIndex.search(anyString(), anyInt())).thenReturn(List.of());
    }

    private SkillDefinition marketplaceDefinition(String id) {
        return SkillDefinition.builder()
                .id(id)
                .name("测试技能-" + id)
                .description("测试描述-" + id)
                .version("1.0.0")
                .source(new SkillSource.Marketplace("pkg-" + id, "https://example.test/index.json", Instant.EPOCH))
                .instructions("你是测试助手")
                .suggestedTools(List.of("tool-a"))
                .metadata(Map.of())
                .build();
    }

    private SkillDefinition userDefinedDefinition(String id) {
        return marketplaceDefinition(id).toBuilder()
                .source(new SkillSource.UserDefined("/path/to/" + id, null))
                .build();
    }

    @Test
    void 注册_校验失败_返回false() {
        when(validator.validate(any())).thenReturn(new ValidationResult(false, List.of("ID 格式不合法")));

        boolean result = registry.register(marketplaceDefinition("todo"));

        assertThat(result).isFalse();
        assertThat(registry.find("todo")).isEmpty();
        verify(eventPublisher, never()).publishEvent(any());
        verify(searchIndex, never()).index(any());
    }

    @Test
    void 注册_新Skill_成功并发布SkillRegistered事件() {
        var definition = marketplaceDefinition("todo");

        boolean result = registry.register(definition);

        assertThat(result).isTrue();
        assertThat(registry.find("todo")).contains(definition);
        verify(searchIndex).index(definition);

        var captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(SkillRegistryEvent.SkillRegistered.class);
        assertThat(((SkillRegistryEvent.SkillRegistered) captor.getValue()).definition()).isEqualTo(definition);
    }

    @Test
    void 注册_覆盖Marketplace来源_允许更新并发布SkillUpdated事件() {
        var original = marketplaceDefinition("todo");
        registry.register(original);
        reset(eventPublisher, searchIndex);

        var updated = marketplaceDefinition("todo").toBuilder()
                .name("新的名称")
                .source(new SkillSource.UserDefined("/new.yaml"))
                .build();

        boolean result = registry.register(updated);

        assertThat(result).isTrue();
        assertThat(registry.find("todo")).contains(updated);
        verify(searchIndex).index(updated);

        var captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(SkillRegistryEvent.SkillUpdated.class);
        var event = (SkillRegistryEvent.SkillUpdated) captor.getValue();
        assertThat(event.oldDefinition()).isEqualTo(original);
        assertThat(event.newDefinition()).isEqualTo(updated);
    }

    @Test
    void 注册_覆盖UserDefined来源_允许更新并发布SkillUpdated事件() {
        var original = userDefinedDefinition("custom");
        registry.register(original);
        reset(eventPublisher, searchIndex);

        var updated = userDefinedDefinition("custom").toBuilder()
                .name("更新后的名称")
                .build();

        boolean result = registry.register(updated);

        assertThat(result).isTrue();
        assertThat(registry.find("custom")).contains(updated);
        verify(searchIndex).index(updated);

        var captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(SkillRegistryEvent.SkillUpdated.class);
        var event = (SkillRegistryEvent.SkillUpdated) captor.getValue();
        assertThat(event.oldDefinition()).isEqualTo(original);
        assertThat(event.newDefinition()).isEqualTo(updated);
    }

    @Test
    void 注销_已注册Skill_成功并发布SkillUnregistered事件() {
        registry.register(marketplaceDefinition("todo"));
        reset(eventPublisher);

        boolean result = registry.unregister("todo");

        assertThat(result).isTrue();
        assertThat(registry.find("todo")).isEmpty();
        verify(searchIndex).remove("todo");

        var captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(SkillRegistryEvent.SkillUnregistered.class);
        assertThat(((SkillRegistryEvent.SkillUnregistered) captor.getValue()).skillId()).isEqualTo("todo");
    }

    @Test
    void 注销_不存在的Skill_返回false() {
        boolean result = registry.unregister("nonexistent");

        assertThat(result).isFalse();
        verify(eventPublisher, never()).publishEvent(any());
        verify(searchIndex, never()).remove(anyString());
    }

    @Test
    void 查找_已注册Skill_返回定义() {
        var definition = marketplaceDefinition("todo");
        registry.register(definition);

        assertThat(registry.find("todo")).contains(definition);
    }

    @Test
    void 查找_未注册Skill_返回空() {
        assertThat(registry.find("nonexistent")).isEmpty();
    }

    @Test
    void 搜索_委托给SkillSearchIndex并映射回SkillDefinition() {
        var definition = marketplaceDefinition("todo");
        registry.register(definition);
        when(searchIndex.search("待办", 10)).thenReturn(List.of(new SkillSearchIndex.SearchResult("todo", 0.9)));

        var results = registry.search("待办");

        assertThat(results).containsExactly(definition);
    }

    @Test
    void 搜索_过滤已注销但索引未清理的条目() {
        when(searchIndex.search("待办", 10)).thenReturn(List.of(new SkillSearchIndex.SearchResult("deleted", 0.8)));

        var results = registry.search("待办");

        assertThat(results).isEmpty();
    }

    @Test
    void 列出摘要_返回所有已注册Skill的摘要() {
        registry.register(marketplaceDefinition("todo"));
        registry.register(userDefinedDefinition("custom"));

        var summaries = registry.listSummaries();

        assertThat(summaries).hasSize(2);
        assertThat(summaries).anyMatch(summary -> summary.contains("todo"));
        assertThat(summaries).anyMatch(summary -> summary.contains("custom"));
    }

    @Test
    void 列出摘要_空注册表返回空列表() {
        assertThat(registry.listSummaries()).isEmpty();
    }

    @Test
    void 按来源注销_仅移除匹配来源的Skill() {
        registry.register(marketplaceDefinition("market-1"));
        registry.register(userDefinedDefinition("user-1"));
        registry.register(userDefinedDefinition("user-2"));

        int count = registry.unregisterBySource(SkillSource.UserDefined.class);

        assertThat(count).isEqualTo(2);
        assertThat(registry.find("market-1")).isPresent();
        assertThat(registry.find("user-1")).isEmpty();
        assertThat(registry.find("user-2")).isEmpty();
    }

    @Test
    void 按来源注销_无匹配时返回0() {
        registry.register(marketplaceDefinition("market-1"));

        int count = registry.unregisterBySource(SkillSource.AutoGenerated.class);

        assertThat(count).isEqualTo(0);
        assertThat(registry.find("market-1")).isPresent();
    }
}
