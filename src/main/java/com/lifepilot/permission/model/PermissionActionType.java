package com.lifepilot.permission.model;

/**
 * 权限动作类型。
 *
 * <p>用于把底层工具调用归一到用户可理解、可授权的动作语义。</p>
 *
 * @author zsg
 * @since 2026-03-25
 */
public enum PermissionActionType {

    GENERIC_TOOL_OPERATION,
    READ_FILE,
    WRITE_FILE,
    DELETE_FILE,
    EXECUTE_SHELL,
    BROWSER_AUTOMATION,
    HTTP_REQUEST,
    WRITE_MEMORY,
    CREATE_SCHEDULE
}
