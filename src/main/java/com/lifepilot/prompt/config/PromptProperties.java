package com.lifepilot.prompt.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 提示词模块配置属性。
 *
 * @author zsg
 * @since 2026-03-06
 */
@ConfigurationProperties(prefix = "lifepilot.prompt")
public class PromptProperties {

    /** 模板根目录，默认 classpath:prompts/ */
    private String basePath = "classpath:prompts/";

    public String getBasePath() {
        return basePath;
    }

    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }
}
