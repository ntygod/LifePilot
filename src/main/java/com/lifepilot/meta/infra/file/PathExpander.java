package com.lifepilot.meta.infra.file;

/**
 * 路径字符串预处理 —— 把 LLM/用户传入的 {@code ~} / {@code ~/} 前缀展开为
 * {@code System.getProperty("user.home")}，让跨平台路径表达更自然。
 *
 * <p>只处理前缀，不修改其它字面 path 形式。后续 Path.of / Files API 由调用方继续做
 * 平台相关解析。</p>
 *
 * @author zsg
 * @since 2026-04-25
 */
public final class PathExpander {

    private PathExpander() {}

    /**
     * 展开以 {@code ~} 开头的路径为绝对路径。
     *
     * <ul>
     *   <li>{@code "~"} → 用户 home</li>
     *   <li>{@code "~/foo"} 或 {@code "~\foo"} → home + "/foo"</li>
     *   <li>其它 → 原样返回</li>
     * </ul>
     */
    public static String expand(String path) {
        if (path == null || path.isBlank()) return path;
        String trimmed = path.trim();
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) return path;
        if (trimmed.equals("~")) return home;
        if (trimmed.startsWith("~/") || trimmed.startsWith("~\\")) {
            return home + trimmed.substring(1);
        }
        return path;
    }
}
