package com.lifepilot.sync.model;

/**
 * 同步方向枚举。
 *
 * <p>定义数据在本地与远程之间的同步方向。
 *
 * @author zsg
 * @since 2026-02-26
 */
public enum SyncDirection {

    /** 双向同步：本地变更推送到远程，远程变更拉取到本地。 */
    BIDIRECTIONAL,

    /** 仅拉取：只从远程拉取变更到本地，不推送本地变更。 */
    PULL_ONLY,

    /** 仅推送：只将本地变更推送到远程，不拉取远程变更。 */
    PUSH_ONLY
}
