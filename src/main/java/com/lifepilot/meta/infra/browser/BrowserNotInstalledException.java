package com.lifepilot.meta.infra.browser;

/**
 * Playwright 浏览器二进制未安装异常。
 *
 * <p>当 Playwright 在 classpath 上但浏览器二进制文件未安装时抛出，
 * 用于区分"Playwright 不可用"和"浏览器未安装"两种场景。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class BrowserNotInstalledException extends RuntimeException {

    public BrowserNotInstalledException(String message) {
        super(message);
    }

    public BrowserNotInstalledException(String message, Throwable cause) {
        super(message, cause);
    }
}
