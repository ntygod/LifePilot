package com.lifepilot.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提示词注册中心 — 统一管理所有提示词模板的加载、缓存和渲染。
 *
 * <p>启动时由 {@link com.lifepilot.prompt.config.PromptAutoConfiguration} 扫描
 * {@code classpath:prompts/**/*.st} 文件并注册到本实例。运行时各模块通过
 * {@link #render(String, Map)} 获取渲染后的提示词文本。</p>
 *
 * <p>线程安全：内部使用 {@link ConcurrentHashMap} 存储模板，支持并发读写。</p>
 *
 * @author zsg
 * @since 2026-03-06
 */
public class PromptRegistry {

    private static final Logger log = LoggerFactory.getLogger(PromptRegistry.class);

    private final ConcurrentHashMap<String, PromptTemplate> templates = new ConcurrentHashMap<>();

    /**
     * 注册模板。
     *
     * @param key      模板键（如 {@code "agent/understanding"}）
     * @param resource classpath 资源
     * @throws IllegalStateException 资源读取失败
     */
    public void register(String key, Resource resource) {
        try {
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            var template = PromptTemplate.builder().template(content).build();
            templates.put(key, template);
            log.debug("提示词模板已注册: key={}", key);
        } catch (IOException e) {
            throw new IllegalStateException("提示词模板读取失败: key=" + key, e);
        }
    }

    /**
     * 渲染模板。
     *
     * @param key       模板键
     * @param variables 变量映射
     * @return 渲染后的提示词文本
     * @throws PromptTemplateNotFoundException 模板键不存在
     */
    public String render(String key, Map<String, Object> variables) {
        var template = templates.get(key);
        if (template == null) {
            throw new PromptTemplateNotFoundException(key);
        }
        return template.render(variables);
    }

    /**
     * 渲染无变量模板。
     *
     * @param key 模板键
     * @return 渲染后的提示词文本
     * @throws PromptTemplateNotFoundException 模板键不存在
     */
    public String render(String key) {
        return render(key, Map.of());
    }

    /**
     * 获取原始模板（调试用）。
     *
     * @param key 模板键
     * @return 模板实例，不存在时返回空 Optional
     */
    public Optional<PromptTemplate> getTemplate(String key) {
        return Optional.ofNullable(templates.get(key));
    }

    /**
     * 已注册模板数量。
     */
    public int size() {
        return templates.size();
    }

    /**
     * 所有已注册模板键（不可变副本）。
     */
    public Set<String> keys() {
        return Set.copyOf(templates.keySet());
    }
}
