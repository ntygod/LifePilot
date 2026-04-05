package com.lifepilot.agent.task.reminder;

/**
 * 剪贴板意图类型枚举。
 *
 * <p>由 Tauri 桌面端识别剪贴板内容后分类上报。</p>
 *
 * @author zsg
 * @since 2026-04-05
 */
public enum ReminderClipboardIntentType {

    /** 快递单号。 */
    TRACKING_NUMBER,

    /** 航班号。 */
    FLIGHT_NUMBER,

    /** 火车车次。 */
    TRAIN_NUMBER,

    /** 链接。 */
    URL,

    /** 电话号码。 */
    PHONE
}
