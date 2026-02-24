package com.lifepilot.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.annotation.Nullable;

/**
 * JSON-RPC 2.0 消息。
 *
 * @param jsonrpc 协议版本，固定为 "2.0"
 * @param id 请求 ID（通知消息为 null）
 * @param method 方法名
 * @param params 请求参数
 * @param result 响应结果
 * @param error 错误信息
 * @author zsg
 * @since 2026-02-24
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcMessage(
        String jsonrpc,
        @Nullable Long id,
        @Nullable String method,
        @Nullable Object params,
        @Nullable Object result,
        @Nullable Object error
) {

    /** 创建 JSON-RPC 请求。 */
    public static JsonRpcMessage request(long id, String method, Object params) {
        return new JsonRpcMessage("2.0", id, method, params, null, null);
    }

    /** 创建 JSON-RPC 通知（无 id）。 */
    public static JsonRpcMessage notification(String method, Object params) {
        return new JsonRpcMessage("2.0", null, method, params, null, null);
    }

    /** 创建 JSON-RPC 响应。 */
    public static JsonRpcMessage response(long id, Object result) {
        return new JsonRpcMessage("2.0", id, null, null, result, null);
    }
}
