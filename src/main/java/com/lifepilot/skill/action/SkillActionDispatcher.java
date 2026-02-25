package com.lifepilot.skill.action;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * 动作分发器 — 根据 {@link SkillAction} 类型路由到对应执行器。
 *
 * <p>使用 switch 表达式穷举匹配 sealed interface 的四个子类型，
 * 确保编译期覆盖所有动作类型。ShellAction 场景下在分发前检查参数值的 Shell 注入。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class SkillActionDispatcher {

    private static final Logger log = LoggerFactory.getLogger(SkillActionDispatcher.class);

    private final HttpActionExecutor httpExecutor;
    private final ShellActionExecutor shellExecutor;
    private final ChainActionExecutor chainExecutor;
    private final TemplateActionExecutor templateExecutor;
    private final VariableResolver variableResolver;

    /**
     * 构造动作分发器。
     *
     * @param httpExecutor     HTTP 动作执行器
     * @param shellExecutor    Shell 命令执行器
     * @param chainExecutor    技能串联执行器
     * @param templateExecutor 模板渲染执行器
     * @param variableResolver 变量替换引擎
     */
    public SkillActionDispatcher(HttpActionExecutor httpExecutor,
                                 ShellActionExecutor shellExecutor,
                                 ChainActionExecutor chainExecutor,
                                 TemplateActionExecutor templateExecutor,
                                 VariableResolver variableResolver) {
        this.httpExecutor = httpExecutor;
        this.shellExecutor = shellExecutor;
        this.chainExecutor = chainExecutor;
        this.templateExecutor = templateExecutor;
        this.variableResolver = variableResolver;
    }

    /**
     * 分发并执行动作。
     *
     * <p>根据 {@link SkillAction} 的具体类型路由到对应执行器。
     * ShellAction 场景下，分发前对参数值执行 Shell 注入检查，
     * 检测到注入字符时立即返回错误结果。</p>
     *
     * @param action         动作定义
     * @param params         输入参数
     * @param previousResult 前序结果（用于 Template 场景）
     * @return 执行结果
     */
    public ActionResult dispatch(SkillAction action, Map<String, Object> params,
                                 @Nullable Map<String, Object> previousResult) {
        log.debug("动作分发: actionType={}", action.getClass().getSimpleName());

        return switch (action) {
            case SkillAction.HttpAction http -> httpExecutor.execute(http, params);
            case SkillAction.ShellAction shell -> dispatchShell(shell, params);
            case SkillAction.ChainAction chain -> chainExecutor.execute(chain, params);
            case SkillAction.TemplateAction template -> templateExecutor.execute(template, previousResult);
        };
    }

    /**
     * 分发 Shell 动作 — 分发前检查参数值的 Shell 注入。
     *
     * @param shell  Shell 动作定义
     * @param params 输入参数
     * @return 执行结果
     */
    private ActionResult dispatchShell(SkillAction.ShellAction shell, Map<String, Object> params) {
        // Shell 注入检查：对每个参数值检测注入字符
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (entry.getValue() != null
                    && variableResolver.containsShellInjection(entry.getValue().toString())) {
                log.warn("Shell 注入检测拦截: command={}, 参数键={}, 参数值={}",
                        shell.command(), entry.getKey(), entry.getValue());
                return ActionResult.error("Shell 注入检测: 参数值包含危险字符");
            }
        }
        return shellExecutor.execute(shell, params);
    }
}
