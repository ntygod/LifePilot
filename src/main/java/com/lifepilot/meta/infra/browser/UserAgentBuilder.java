package com.lifepilot.meta.infra.browser;

import jakarta.annotation.Nullable;

/**
 * 浏览器 User-Agent 构造器 — 把"配置值 + Chromium 运行时版本号"拼成最终 UA。
 *
 * <p>使用场景：
 * <ul>
 *   <li>配置为 {@code "auto"}（默认）→ 用运行时 {@code browser.version()} 拼 UA，
 *       版本号随 Playwright 升级自动跟随，避免硬编码漂移导致的反爬指纹识别。</li>
 *   <li>显式配置 UA 字符串 → 原样使用，兼容用户自定义场景。</li>
 *   <li>auto 但版本号缺失（如 PERSISTENT 模式拿不到 Browser 实例）→ 返回 null，
 *       调用方应跳过 {@code setUserAgent()}，让 Chromium 输出自身真实 UA（反指纹更优）。</li>
 * </ul>
 *
 * <p>本类为纯函数（无状态、无副作用），便于单元测试。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
public final class UserAgentBuilder {

    /** 标准 Windows Chrome UA 模板，{@code %s} 替换为 Chromium 版本号。 */
    private static final String TEMPLATE =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/%s Safari/537.36";

    /** 表示"自动从 Chromium 运行时版本推导 UA"的配置值（大小写不敏感）。 */
    private static final String AUTO_MARKER = "auto";

    private UserAgentBuilder() {
        // 工具类禁止实例化
    }

    /**
     * 根据配置值与 Chromium 运行时版本号拼最终 UA。
     *
     * @param configured      配置的 UA 字符串；{@code null} / 空白 / {@code "auto"}（大小写不敏感）均视为 auto 模式
     * @param chromiumVersion Chromium 运行时版本号（来自 {@code browser.version()}）
     * @return 最终 UA 字符串；auto 模式下若版本号缺失则返回 {@code null}
     */
    @Nullable
    public static String build(@Nullable String configured, @Nullable String chromiumVersion) {
        if (!isAuto(configured)) {
            return configured;
        }
        if (chromiumVersion == null || chromiumVersion.isBlank()) {
            return null;
        }
        return TEMPLATE.formatted(chromiumVersion.trim());
    }

    /** 判断配置值是否为 auto 模式（null / 空白 / 忽略大小写的 "auto"）。 */
    private static boolean isAuto(@Nullable String configured) {
        if (configured == null) return true;
        String trimmed = configured.trim();
        return trimmed.isEmpty() || AUTO_MARKER.equalsIgnoreCase(trimmed);
    }
}
