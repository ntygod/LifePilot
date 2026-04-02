package com.lifepilot.a2a.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.lang.Nullable;

/**
 * A2A JSON-RPC 2.0 错误对象。
 *
 * <p>标准错误码：
 * <ul>
 *   <li>-32700: 解析错误</li>
 *   <li>-32600: 无效请求</li>
 *   <li>-32601: 方法不存在</li>
 *   <li>-32602: 无效参数</li>
 *   <li>-32603: 内部错误</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-02
 */
public record A2aJsonRpcError(
        int code,
        String message,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) Object data
) {

    /** 解析错误。 */
    public static final int PARSE_ERROR = -32700;
    /** 无效请求。 */
    public static final int INVALID_REQUEST = -32600;
    /** 方法不存在。 */
    public static final int METHOD_NOT_FOUND = -32601;
    /** 无效参数。 */
    public static final int INVALID_PARAMS = -32602;
    /** 内部错误。 */
    public static final int INTERNAL_ERROR = -32603;

    public static A2aJsonRpcError parseError(String detail) {
        return new A2aJsonRpcError(PARSE_ERROR, "解析错误", detail);
    }

    public static A2aJsonRpcError invalidRequest(String detail) {
        return new A2aJsonRpcError(INVALID_REQUEST, "无效请求", detail);
    }

    public static A2aJsonRpcError methodNotFound(String method) {
        return new A2aJsonRpcError(METHOD_NOT_FOUND, "方法不存在: " + method, null);
    }

    public static A2aJsonRpcError invalidParams(String detail) {
        return new A2aJsonRpcError(INVALID_PARAMS, "无效参数", detail);
    }

    public static A2aJsonRpcError internalError(String detail) {
        return new A2aJsonRpcError(INTERNAL_ERROR, "内部错误", detail);
    }
}
