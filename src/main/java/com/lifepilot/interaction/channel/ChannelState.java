package com.lifepilot.interaction.channel;

/**
 * 通道适配器生命周期状态枚举。
 *
 * <p>状态转换规则：
 * <ul>
 *   <li>{@code CREATED → STARTING → RUNNING → STOPPING → STOPPED}</li>
 *   <li>{@code RUNNING/STARTING → ERROR → STARTING}（自动重连）</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-02-26
 */
public enum ChannelState {

    /** 已创建，尚未启动。 */
    CREATED,

    /** 正在启动。 */
    STARTING,

    /** 运行中。 */
    RUNNING,

    /** 正在停止。 */
    STOPPING,

    /** 已停止。 */
    STOPPED,

    /** 错误状态，等待重连。 */
    ERROR
}
