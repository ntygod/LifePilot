package com.lifepilot.eval.web;

import org.springframework.lang.Nullable;

/**
 * 统一 API 响应包装。
 *
 * @param code    状态码（200 成功，其他为错误码）
 * @param message 描述信息
 * @param data    响应数据
 * @param <T>     数据类型
 * @author zsg
 * @since 2026-03-22
 */
public record ApiResponse<T>(int code, String message, @Nullable T data) {

    /**
     * 成功响应。
     */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(200, "success", data);
    }

    /**
     * 成功响应（无数据）。
     */
    public static <T> ApiResponse<T> ok() {
        return new ApiResponse<>(200, "success", null);
    }

    /**
     * 错误响应。
     */
    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
