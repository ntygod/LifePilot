package com.lifepilot.interaction.model;

/**
 * 交互来源类型。
 *
 * <p>区分用户通信渠道与系统触发源，避免继续把 workflow / cron / heartbeat
 * 这类系统来源误建模为普通渠道。</p>
 *
 * @author zsg
 * @since 2026-03-29
 */
public enum SourceKind {
    CHANNEL,
    WORKFLOW,
    CRON,
    HEARTBEAT,
    SYSTEM
}
