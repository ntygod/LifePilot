package com.lifepilot.skill.action;

import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * 动作执行结果。
 *
 * @param success 是否成功
 * @param output  输出内容（成功时为结果，失败时为错误信息）
 * @param data    结构化输出数据（可选，用于 Chain 步骤间传递）
 * @author zsg
 * @since 2026-02-25
 */
public record ActionResult(
        boolean success,
        String output,
        @Nullable Map<String, Object> data
) {

    /**
     * 创建成功结果（无结构化数据）。
     *
     * @param output 输出内容
     * @return 成功结果
     */
    public static ActionResult success(String output) {
        return new ActionResult(true, output, null);
    }

    /**
     * 创建成功结果（含结构化数据）。
     *
     * @param output 输出内容
     * @param data   结构化输出数据
     * @return 成功结果
     */
    public static ActionResult success(String output, Map<String, Object> data) {
        return new ActionResult(true, output, Map.copyOf(data));
    }

    /**
     * 创建错误结果。
     *
     * @param message 错误信息
     * @return 错误结果
     */
    public static ActionResult error(String message) {
        return new ActionResult(false, message, null);
    }
}
