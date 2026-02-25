package com.lifepilot.skill.action;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * 模板渲染执行器 — 使用 {@link VariableResolver} 替换 ${result.xxx} 占位符。
 *
 * <p>最简单的动作执行器，将模板字符串中的变量占位符替换为前序结果中的值，
 * 用于格式化输出场景。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class TemplateActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(TemplateActionExecutor.class);

    private final VariableResolver variableResolver;

    /**
     * 构造模板渲染执行器。
     *
     * @param variableResolver 变量替换引擎
     */
    public TemplateActionExecutor(VariableResolver variableResolver) {
        this.variableResolver = variableResolver;
    }

    /**
     * 执行模板渲染 — 替换模板中的 ${result.xxx} 占位符。
     *
     * @param action         模板动作定义
     * @param previousResult 前序结果（可为 null，null 时保留 ${result.xxx} 原样）
     * @return 渲染后的结果
     */
    public ActionResult execute(SkillAction.TemplateAction action,
                                @Nullable Map<String, Object> previousResult) {
        // 模板为空时返回错误
        if (action.template() == null || action.template().isBlank()) {
            log.warn("模板内容为空");
            return ActionResult.error("模板内容为空");
        }

        String resolvedTemplate = variableResolver.resolve(
                action.template(), Map.of(), previousResult);

        log.debug("模板渲染完成: template={}, resolved={}", action.template(), resolvedTemplate);
        return ActionResult.success(resolvedTemplate);
    }
}
