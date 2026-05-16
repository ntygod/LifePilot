package com.lifepilot.config.path;

import java.nio.file.Path;

/**
 * 统一路径解析工具 —— 合并项目中 6+ 处重复的 {@code ~} 展开逻辑，
 * 提供路径规范化、相对路径解析、绝对路径验证和逃逸检测等能力。
 *
 * <p>本类为纯工具类（非 Spring Bean），所有方法均为静态方法，无副作用。</p>
 *
 * @author zsg
 * @since 2026-06-15
 */
public final class PathResolver {

    private PathResolver() {}

    /**
     * 展开以 {@code ~} 开头的路径为绝对路径。
     *
     * <ul>
     *   <li>{@code "~"} → 用户 home 目录</li>
     *   <li>{@code "~/foo"} 或 {@code "~\foo"} → home + 子路径</li>
     *   <li>其它（含 {@code ~user} 形式）→ 原样返回</li>
     *   <li>null / 空白 → 原样返回</li>
     * </ul>
     *
     * @param path 待展开的路径字符串，可为 null
     * @return 展开后的路径字符串；null 输入返回 null
     */
    public static String expand(String path) {
        if (path == null || path.isBlank()) {
            return path;
        }
        String trimmed = path.trim();
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            return path;
        }
        if (trimmed.equals("~")) {
            return home;
        }
        if (trimmed.startsWith("~/") || trimmed.startsWith("~\\")) {
            return home + trimmed.substring(1);
        }
        return path;
    }

    /**
     * 规范化路径分隔符为平台原生分隔符。
     *
     * <p>在 Windows 上将 {@code /} 替换为 {@code \}，
     * 在 Unix 上将 {@code \} 替换为 {@code /}。</p>
     *
     * @param path 待规范化的路径字符串，可为 null
     * @return 规范化后的路径字符串；null 输入返回 null
     */
    public static String normalize(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        String sep = java.io.File.separator;
        if ("\\".equals(sep)) {
            // Windows：将正斜杠替换为反斜杠
            return path.replace('/', '\\');
        } else {
            // Unix/Mac：将反斜杠替换为正斜杠
            return path.replace('\\', '/');
        }
    }

    /**
     * 将相对路径基于给定 base 目录解析为绝对路径。
     *
     * <p>如果 path 已经是绝对路径，则直接规范化返回；
     * 如果是相对路径，则基于 base 解析后规范化返回。</p>
     *
     * @param path 待解析的路径字符串，不可为 null
     * @param base 基准目录，不可为 null
     * @return 解析后的绝对路径
     * @throws IllegalArgumentException 如果 path 或 base 为 null
     */
    public static Path resolve(String path, Path base) {
        if (path == null) {
            throw new IllegalArgumentException("路径不能为 null");
        }
        if (base == null) {
            throw new IllegalArgumentException("基准目录不能为 null");
        }
        Path target = Path.of(path);
        if (target.isAbsolute()) {
            return target.normalize();
        }
        return base.resolve(target).normalize();
    }

    /**
     * 当路径为 null 或空白时返回默认值，否则返回原路径。
     *
     * @param path         待检查的路径字符串，可为 null
     * @param defaultValue 默认值，当 path 为 null/blank 时返回
     * @return path 本身或 defaultValue
     */
    public static String resolveOrDefault(String path, String defaultValue) {
        if (path == null || path.isBlank()) {
            return defaultValue;
        }
        return path;
    }

    /**
     * 验证路径为绝对路径。
     *
     * @param path 待验证的路径，不可为 null
     * @throws IllegalArgumentException 如果路径不是绝对路径
     */
    public static void validateAbsolute(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("路径不能为 null");
        }
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("路径必须为绝对路径，当前值: " + path);
        }
    }

    /**
     * 拒绝包含 {@code ..} 段导致逃逸 root 目录的路径。
     *
     * <p>通过规范化后检查路径是否仍以 root 为前缀来判断是否发生逃逸。</p>
     *
     * @param path 待检查的路径，不可为 null
     * @param root 限定的根目录，不可为 null
     * @throws IllegalArgumentException 如果路径逃逸了 root 目录
     */
    public static void rejectEscape(Path path, Path root) {
        if (path == null) {
            throw new IllegalArgumentException("路径不能为 null");
        }
        if (root == null) {
            throw new IllegalArgumentException("根目录不能为 null");
        }
        Path normalizedPath = path.toAbsolutePath().normalize();
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (!normalizedPath.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException(
                    "路径逃逸了限定根目录: path=" + normalizedPath + ", root=" + normalizedRoot);
        }
    }
}
