package com.lifepilot.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PromptRegistry — unified prompt template registry for loading, caching and rendering.
 *
 * @author zsg
 * @since 2026-03-06
 */
public class PromptRegistry {

    private static final Logger log = LoggerFactory.getLogger(PromptRegistry.class);

    private final ConcurrentHashMap<String, String> templates = new ConcurrentHashMap<>();

    /**
     * 注册模板（从 classpath resource 加载原始文本）。
     *
     * @param key      模板键（如 "agent/understanding"）
     * @param resource classpath 资源
     */
    public void register(String key, Resource resource) {
        try {
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            templates.put(key, content);
            log.debug("prompt template registered: key={}", key);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read prompt template: key=" + key, e);
        }
    }

    /**
     * 渲染模板，使用简单的 {key} 占位符替换，避免 ST4 特殊字符冲突。
     *
     * @param key       模板键
     * @param variables 变量映射
     * @return 渲染后的文本
     */
    public String render(String key, Map<String, Object> variables) {
        var template = templates.get(key);
        if (template == null) {
            throw new PromptTemplateNotFoundException(key);
        }
        String result = template;
        for (var entry : variables.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return result;
    }

    /**
     * 渲染模板（无变量）。
     *
     * @param key 模板键
     * @return 渲染后的文本
     */
    public String render(String key) {
        return render(key, Map.of());
    }

    /**
     * 获取原始模板文本（调试用）。
     *
     * @param key 模板键
     * @return 模板文本或空 Optional
     */
    public Optional<String> getTemplate(String key) {
        return Optional.ofNullable(templates.get(key));
    }

    /**
     * Number of registered templates.
     */
    public int size() {
        return templates.size();
    }

    /**
     * All registered template keys (immutable copy).
     */
    public Set<String> keys() {
        return Set.copyOf(templates.keySet());
    }
}
