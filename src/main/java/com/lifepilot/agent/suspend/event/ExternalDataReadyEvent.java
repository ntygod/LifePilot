package com.lifepilot.agent.suspend.event;

/**
 * 外部数据就绪恢复事件 — 外部数据源准备完毕后发布。
 *
 * @author zsg
 * @since 2026-03-17
 */
public record ExternalDataReadyEvent(String dataSourceId, String dataLocationOrContent) {}
