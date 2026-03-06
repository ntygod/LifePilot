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
 * PromptRegistry — unified prompt template registry for loading, caching and rendering.
 *
 * @author zsg
 * @since 2026-03-06
 */
public class PromptRegistry {

    private static final Logger log = LoggerFactory.getLogger(PromptRegistry.class);

    private final ConcurrentHashMap<String, PromptTemplate> templates = new ConcurrentHashMap<>();

    /**
     * Register a template from a classpath resource.
     *
     * @param key      template key (e.g. "agent/understanding")
     * @param resource classpath resource
     */
    public void register(String key, Resource resource) {
        try {
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            var template = PromptTemplate.builder().template(content).build();
            templates.put(key, template);
            log.debug("prompt template registered: key={}", key);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read prompt template: key=" + key, e);
        }
    }

    /**
     * Render a template with variables.
     *
     * @param key       template key
     * @param variables variable map
     * @return rendered prompt text
     */
    public String render(String key, Map<String, Object> variables) {
        var template = templates.get(key);
        if (template == null) {
            throw new PromptTemplateNotFoundException(key);
        }
        return template.render(variables);
    }

    /**
     * Render a template without variables.
     *
     * @param key template key
     * @return rendered prompt text
     */
    public String render(String key) {
        return render(key, Map.of());
    }

    /**
     * Get raw template (for debugging).
     *
     * @param key template key
     * @return template instance or empty Optional
     */
    public Optional<PromptTemplate> getTemplate(String key) {
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
