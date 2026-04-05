package com.lifepilot.meta.infra.file;

import com.lifepilot.meta.config.MetaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 路径安全检查器 — 白名单/黑名单校验。
 *
 * <p>校验流程：
 * <ol>
 *   <li>规范化路径（已存在文件用 {@code toRealPath()}，不存在文件用 {@code toAbsolutePath().normalize()}）</li>
 *   <li>如果白名单非空，检查路径是否在白名单目录下</li>
 *   <li>检查路径是否在黑名单目录下</li>
 *   <li>如果白名单为空，仅保留黑名单约束，不再额外限制到用户 home</li>
 * </ol>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class PathSecurityChecker {

    private static final Logger log = LoggerFactory.getLogger(PathSecurityChecker.class);

    /** Windows 平台标识，用于路径大小写不敏感比较。 */
    private static final boolean IS_WINDOWS = java.io.File.separatorChar == '\\';

    private final List<Path> allowedPaths;
    private final List<Path> deniedPaths;

    public PathSecurityChecker(MetaProperties.Infra.FileAccess config) {
        this.allowedPaths = config.getAllowedDirectories().stream()
                .map(s -> Path.of(s).toAbsolutePath().normalize())
                .toList();
        this.deniedPaths = config.getDeniedDirectories().stream()
                .map(s -> Path.of(s).toAbsolutePath().normalize())
                .toList();
    }

    /**
     * 校验路径安全性（文件已存在场景，如读取、列表、搜索）。
     *
     * <p>使用 {@code Path.toRealPath()} 解析符号链接后校验。</p>
     *
     * @param target 目标路径
     * @return 空表示安全，非空表示拒绝原因
     */
    public Optional<String> check(Path target) {
        Path normalized;
        try {
            normalized = target.toRealPath();
        } catch (IOException e) {
            // 文件不存在，回退到 normalize
            normalized = target.toAbsolutePath().normalize();
        }
        return doCheck(normalized);
    }

    /**
     * 校验路径安全性（文件可能不存在场景，如写入）。
     *
     * <p>使用 {@code toAbsolutePath().normalize()} 规范化，
     * 并检查父目录是否存在且安全。</p>
     *
     * @param target 目标路径
     * @return 空表示安全，非空表示拒绝原因
     */
    public Optional<String> checkForWrite(Path target) {
        Path normalized = target.toAbsolutePath().normalize();
        return doCheck(normalized);
    }

    /**
     * 执行白名单/黑名单校验。
     *
     * @param normalized 已规范化的绝对路径
     * @return 空表示安全，非空表示拒绝原因
     */
    private Optional<String> doCheck(Path normalized) {
        // 黑名单检查
        for (Path denied : deniedPaths) {
            if (pathStartsWith(normalized, denied)) {
                log.warn("路径被黑名单拒绝: path={}, deniedDir={}", normalized, denied);
                return Optional.of("路径被安全策略拒绝: 位于黑名单目录 [" + denied + "]");
            }
        }

        // 白名单检查
        if (!allowedPaths.isEmpty()) {
            boolean allowed = allowedPaths.stream().anyMatch(a -> pathStartsWith(normalized, a));
            if (!allowed) {
                log.warn("路径不在白名单中: path={}", normalized);
                return Optional.of("路径被安全策略拒绝: 不在允许的目录列表中");
            }
            return Optional.empty();
        }

        return Optional.empty();
    }

    /**
     * 路径前缀比较 — Windows 下大小写不敏感，其他平台使用 {@code Path.startsWith()}。
     *
     * @param path   待检查路径
     * @param prefix 前缀路径
     * @return 是否以 prefix 开头
     */
    private boolean pathStartsWith(Path path, Path prefix) {
        if (IS_WINDOWS) {
            // 必须用 Path.startsWith() 做组件级比较，避免 "foobar" 匹配 "foo" 前缀
            return Path.of(path.toString().toLowerCase(Locale.ROOT))
                    .startsWith(Path.of(prefix.toString().toLowerCase(Locale.ROOT)));
        }
        return path.startsWith(prefix);
    }
}
