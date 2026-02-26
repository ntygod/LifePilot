package com.lifepilot.sync.model;

/**
 * 同步状态枚举。
 *
 * <p>表示一次同步操作的最终状态。
 *
 * @author zsg
 * @since 2026-02-26
 */
public enum SyncStatus {

    /** 同步完全成功。 */
    SUCCESS,

    /** 同步部分成功（如推送阶段部分失败）。 */
    PARTIAL,

    /** 同步失败。 */
    FAILED
}
