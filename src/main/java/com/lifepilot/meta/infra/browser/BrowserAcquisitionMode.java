package com.lifepilot.meta.infra.browser;

/**
 * 浏览器获取模式 — 决定 Playwright 如何获取 Browser / BrowserContext 实例。
 *
 * @author zsg
 * @since 2026-04-04
 */
public enum BrowserAcquisitionMode {

    /** 默认模式 — Playwright 自行启动并管理 Chromium 实例。 */
    LAUNCH,

    /** CDP 模式 — 通过 Chrome DevTools Protocol 连接到用户预先启动的 Chrome。 */
    CDP,

    /** 持久上下文模式 — 使用 userDataDir 启动带完整用户配置文件的 Chromium。 */
    PERSISTENT
}
