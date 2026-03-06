package com.lifepilot.prompt.config;

import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;

/**
 * Prompt module auto-configuration.
 *
 * <p>Scans all .st template files under prompts directory
 * on startup and registers them into {@link PromptRegistry}.</p>
 *
 * @author zsg
 * @since 2026-03-06
 */
@AutoConfiguration
@EnableConfigurationProperties(PromptProperties.class)
public class PromptAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PromptAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public PromptRegistry promptRegistry(PromptProperties properties,
                                          ResourcePatternResolver resolver) throws IOException {
        var registry = new PromptRegistry();
        String basePath = properties.getBasePath();

        // Scan all .st template files
        String pattern = basePath + "**/*.st";
        Resource[] resources;
        try {
            resources = resolver.getResources(pattern);
        } catch (IOException e) {
            log.warn("Prompt template directory scan failed: pattern={}", pattern);
            return registry;
        }

        // Compute normalized base prefix for extracting relative paths
        // basePath = "classpath:prompts/" -> normalizedBase = "prompts/"
        String normalizedBase = basePath.replace("classpath:", "");

        for (Resource resource : resources) {
            if (!resource.isReadable()) {
                log.warn("Prompt template not readable, skipping: {}", resource);
                continue;
            }
            String key = extractTemplateKey(resource, normalizedBase);
            if (key != null) {
                registry.register(key, resource);
            }
        }

        log.info("Prompt registration complete: count={}", registry.size());
        return registry;
    }

    /**
     * Extract template key from a Resource URL path.
     *
     * <p>Example: {@code classpath:prompts/agent/understanding.st} becomes {@code "agent/understanding"}</p>
     */
    private String extractTemplateKey(Resource resource, String normalizedBase) {
        try {
            String url = resource.getURL().toString();
            int idx = url.indexOf(normalizedBase);
            if (idx < 0) {
                log.warn("Cannot extract template key, path does not contain basePath: {}", url);
                return null;
            }
            String relativePath = url.substring(idx + normalizedBase.length());
            if (relativePath.endsWith(".st")) {
                relativePath = relativePath.substring(0, relativePath.length() - 3);
            }
            return relativePath;
        } catch (IOException e) {
            log.warn("Cannot get resource URL: {}", resource, e);
            return null;
        }
    }
}
