package com.lifepilot.prompt;

/**
 * 提示词模板未找到异常 — 当 PromptRegistry 中不存在指定模板键时抛出。
 *
 * @author zsg
 * @since 2026-03-06
 */
public class PromptTemplateNotFoundException extends RuntimeException {

    private final String templateKey;

    public PromptTemplateNotFoundException(String templateKey) {
        super("提示词模板不存在: key=" + templateKey);
        this.templateKey = templateKey;
    }

    public String getTemplateKey() {
        return templateKey;
    }
}
