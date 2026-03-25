package com.lifepilot.permission.model;

/**
 * 授权主体类型。
 *
 * <p>用于定义授权记录绑定到哪一层作用域。</p>
 *
 * @author zsg
 * @since 2026-03-25
 */
public enum PermissionSubjectType {

    SESSION,
    WORKSPACE,
    TASK,
    USER
}
