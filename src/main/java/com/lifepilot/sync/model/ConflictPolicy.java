package com.lifepilot.sync.model;

/**
 * 冲突解决策略枚举。
 *
 * <p>定义当同一实体在本地和远程同时被修改时的解决方式。
 *
 * @author zsg
 * @since 2026-02-26
 */
public enum ConflictPolicy {

    /** 以最后写入时间戳较晚的版本为准。 */
    LAST_WRITE_WINS,

    /** 始终以远程版本为准。 */
    REMOTE_WINS,

    /** 始终以本地版本为准。 */
    LOCAL_WINS,

    /** 标记为未解决，等待用户手动确认。 */
    USER_CONFIRM
}
