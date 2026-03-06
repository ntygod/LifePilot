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
 * 提示词模块自动配置 — 启动时扫描 {@code classpath:prompts/**/*.st} 并注册到 {@link PromptRegistry}。
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

        // 扫描所有 .st 模板文件
        String pattern = basePath + "**/*.st";
        Resource[] resources;
        try {
            resources = resolver.getResources(pattern);
        } catch (IOException e) {
            log.warn("提示词模板目录扫描失败: pattern={}", pattern);
            return registry;
        }

        // 计算 basePath 在 URL 中的标准化前缀，用于提取相对路径
        String normalizedBase = basePath.replace("classpath:", "prompts/");

        for (Resource resource : resources) {
            if (!resource.isReadable()) {
                log.warn("提示词模板不可读，跳过: {}", resource);
                continue;
            }
            String key = extractTemplateKey(resource, normalizedBase);
            if (key != null) {
                registry.register(key, resource);
            }
        }

        log.info("提示词注册完成: count={}", registry.size());
        return registry;
    }

    /**
     * 从 Resource 的 URL 路径中提取模板键。
     *
     * <p>例如 {@code classpath:prompts/agent/understanding.st} → {@code "agent/understanding"}</p>
     */
    private String extractTemplateKey(Resource resource, String normalizedBase) {
        try {
            String url = resource.getURL().toString();
            // 找到 prompts/ 之后的相对路径
            int idx = url.indexOf(normalizedBase);
            if (idx < 0) {
                log.warn("无法提取模板键，路径不包含 basePath: {}", url);
                return null;
            }
            String relativePath = url.substring(idx + normalizedBase.length());
            // 去除 .st 后缀
            if (relativePath.endsWith(".st")) {
                relativePath = relativePath.substring(0, relativePath.length() - 3);
            }
            return relativePath;
        } catch (IOException e) {
            log.warn("无法获取资源 URL: {}", resource, e);
            return null;
        }
    }
}
