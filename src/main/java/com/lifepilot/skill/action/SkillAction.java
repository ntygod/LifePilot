package com.lifepilot.skill.action;

import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;

/**
 * YAML Skill 动作类型 — 穷举四种动作。
 *
 * <p>使用 sealed interface + record 实现，支持 switch 表达式穷举匹配。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public sealed interface SkillAction permits
        SkillAction.HttpAction,
        SkillAction.ShellAction,
        SkillAction.ChainAction,
        SkillAction.TemplateAction {

    /**
     * HTTP 动作 — 调用外部 HTTP API。
     *
     * @param method  HTTP 方法（GET/POST/PUT/DELETE）
     * @param url     请求 URL，支持 ${params.xxx} 和 ${env.XXX} 变量替换
     * @param headers 请求头 Map
     * @param query   查询参数 Map
     * @param body    请求体（Object，可为 null）
     */
    record HttpAction(
            String method,
            String url,
            Map<String, String> headers,
            Map<String, String> query,
            @Nullable Object body
    ) implements SkillAction {
        public HttpAction {
            headers = headers != null ? Map.copyOf(headers) : Map.of();
            query = query != null ? Map.copyOf(query) : Map.of();
        }
    }

    /**
     * Shell 命令动作 — 在受限环境中执行命令。
     *
     * @param command        Shell 命令，支持 ${params.xxx} 变量替换
     * @param timeoutSeconds 超时时间（秒），默认 10
     */
    record ShellAction(String command, int timeoutSeconds) implements SkillAction {
        public ShellAction {
            if (timeoutSeconds <= 0) timeoutSeconds = 10;
        }
    }

    /**
     * 技能串联动作 — 按顺序激活多个 Skill。
     *
     * @param steps 步骤列表
     */
    record ChainAction(List<ChainStep> steps) implements SkillAction {
        public ChainAction { steps = List.copyOf(steps); }
    }

    /**
     * 模板渲染动作 — 格式化输出。
     *
     * @param template 模板字符串，支持 ${result.xxx} 变量替换
     */
    record TemplateAction(String template) implements SkillAction {}

    /**
     * 串联步骤。
     *
     * @param skill  目标 Skill ID
     * @param params 输入参数 Map，支持 ${前一步output.xxx} 引用
     * @param output 输出变量名
     */
    record ChainStep(String skill, Map<String, String> params, String output) {
        public ChainStep { params = params != null ? Map.copyOf(params) : Map.of(); }
    }
}
