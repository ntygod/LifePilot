package com.lifepilot.tool.artifact;

import java.nio.file.Path;
import java.util.Locale;

/**
 * 工具产物过滤工具类。
 *
 * <p>统一应用「过滤噪声 + workspace 白名单校验」两类规则，让 {@code FileWriteToolExecutor}、
 * {@code ShellExecToolExecutor}、{@code CodeExecuteToolExecutor} 等工具执行器
 * 共享同一套噪声排除策略与安全边界。</p>
 *
 * <p>过滤规则按 {@link ArtifactFilterConfig} 配置生效：</p>
 * <ul>
 *   <li>{@code size <= 0} 或 {@code size > maxSizeMb} 拒绝</li>
 *   <li>文件名以 {@code .} 开头（隐藏文件）拒绝</li>
 *   <li>扩展名命中 {@code excludedExtensions} 拒绝</li>
 *   <li>路径任意一段命中 {@code excludedDirs} 拒绝</li>
 * </ul>
 *
 * <p>workspace 白名单通过 {@link #isInWorkspaceRoot(Path, Path)} 校验：候选路径
 * 与 workspace 根都先 {@code toAbsolutePath().normalize()} 解析符号链接和
 * 相对引用，防止 {@code ..} 路径攻击。</p>
 *
 * @author zsg
 * @since 2026-05-17
 */
public final class ArtifactFilter {

    private ArtifactFilter() {
        // utility class
    }

    /**
     * 判断指定文件是否通过过滤规则、可登记为工具产物。
     *
     * @param absolutePath 候选文件绝对路径（调用方应已 normalize）
     * @param size         文件大小（字节）
     * @param config       过滤配置
     * @return 通过时返回 {@code true}
     */
    public static boolean accept(Path absolutePath, long size, ArtifactFilterConfig config) {
        if (absolutePath == null || config == null) {
            return false;
        }
        if (size <= 0) {
            return false;
        }
        long maxBytes = config.maxSizeMb() * 1024L * 1024L;
        if (size > maxBytes) {
            return false;
        }

        Path fileNamePath = absolutePath.getFileName();
        if (fileNamePath == null) {
            return false;
        }
        String fileName = fileNamePath.toString();
        if (fileName.startsWith(".")) {
            return false;
        }

        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx >= 0 && dotIdx < fileName.length() - 1) {
            String ext = fileName.substring(dotIdx + 1).toLowerCase(Locale.ROOT);
            if (config.excludedExtensions().contains(ext)) {
                return false;
            }
        }

        for (Path segment : absolutePath) {
            if (config.excludedDirs().contains(segment.toString())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断候选路径是否在 workspace 根目录之下。
     *
     * <p>实现要点：
     * <ul>
     *   <li>{@code candidate} 与 {@code workspaceRoot} 都做
     *       {@code toAbsolutePath().normalize()} —— 解析 {@code ..} / {@code .}
     *       并消除相对引用，防止路径攻击</li>
     *   <li>使用 {@link Path#startsWith(Path)} 而非字符串前缀比对，避免
     *       {@code /workspace/} 与 {@code /workspace_evil/} 误命中</li>
     * </ul>
     *
     * @param candidate     候选路径
     * @param workspaceRoot workspace 根目录
     * @return {@code candidate} 在 {@code workspaceRoot} 之下时返回 {@code true}
     */
    public static boolean isInWorkspaceRoot(Path candidate, Path workspaceRoot) {
        if (candidate == null || workspaceRoot == null) {
            return false;
        }
        Path canonical = candidate.toAbsolutePath().normalize();
        Path root = workspaceRoot.toAbsolutePath().normalize();
        return canonical.startsWith(root);
    }
}
