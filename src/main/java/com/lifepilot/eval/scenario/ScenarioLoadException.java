package com.lifepilot.eval.scenario;

/**
 * 场景加载异常 — YAML 解析失败、必填字段缺失、校验不通过时抛出。
 *
 * @author zsg
 * @since 2026-08-01
 */
public class ScenarioLoadException extends RuntimeException {

    public ScenarioLoadException(String message) {
        super(message);
    }

    public ScenarioLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
