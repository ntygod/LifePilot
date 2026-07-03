package com.lifepilot.memory.store.vector;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.lang.Nullable;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * sqlite-vec 扩展自动加载器。
 *
 * <p>目标是做到「真正一键」：用户只需启动应用，如果打包中包含
 * 对应平台的 sqlite-vec 原生库（.dll/.so/.dylib），则自动完成：</p>
 *
 * <ul>
 *     <li>从 classpath 中选择当前 OS/Arch 对应的动态库资源；</li>
 *     <li>解压到临时目录；</li>
 *     <li>调用 {@code load_extension(...)} 加载 sqlite-vec；</li>
 *     <li>通过 {@code vec_version()} 验证是否可用。</li>
 * </ul>
 *
 * <p>如果任一环节失败，将直接终止启动，避免向量索引静默缺失。</p>
 *
 * <p>注意：本组件只负责「自动加载」，不负责「编译」。各平台原生库应在
 * 构建阶段预先放入 {@code src/main/resources/native/sqlite-vec/...}，例如：</p>
 *
 * <ul>
 *     <li>{@code native/sqlite-vec/windows-x86_64/vec0.dll}</li>
 *     <li>{@code native/sqlite-vec/linux-x86_64/vec0.so}</li>
 *     <li>{@code native/sqlite-vec/macos-aarch64/vec0.dylib}</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-03-05
 */
public class SqliteVecInitializer {

    private static final Logger log = LoggerFactory.getLogger(SqliteVecInitializer.class);

    private volatile @Nullable String extractedExtensionAbsolutePath;

    /** sqlite-vec 的入口函数名（第二参数）。 */
    public static final String ENTRYPOINT = "sqlite3_vec_init";

    @PostConstruct
    public void init() {
        try {
            String resourcePath = resolveResourcePath();

            try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                if (in == null) {
                    throw new IllegalStateException("sqlite-vec 资源不存在: " + resourcePath);
                }

                Path tempFile = Files.createTempFile("sqlite-vec-", getFileSuffix(resourcePath));
                Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
                tempFile.toFile().deleteOnExit();

                String absPath = tempFile.toAbsolutePath().toString().replace("\\", "/");
                this.extractedExtensionAbsolutePath = absPath;
                // 不在这里直接 load_extension/vec_version：sqlite-vec 是“按连接加载”的，
                // 校验和加载应该在获取 Connection 时进行，保证所有连接一致可用。
                log.info("记忆系统: sqlite-vec 扩展资源已准备, 路径={}", absPath);

            }
        } catch (Exception e) {
            throw new IllegalStateException("sqlite-vec 扩展资源准备失败: " + e.getMessage(), e);
        }
    }

    public @Nullable String getExtractedExtensionAbsolutePath() {
        return extractedExtensionAbsolutePath;
    }

    /**
     * 根据当前 OS / Arch 选择资源路径。
     */
    private String resolveResourcePath() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();

        // 目前主要覆盖常见桌面/服务器平台，可按需扩展
        if (os.contains("win") && (arch.contains("64") || arch.contains("amd64") || arch.contains("x86_64"))) {
            return "native/sqlite-vec/windows-x86_64/vec0.dll";
        }
        if ((os.contains("nux") || os.contains("nix")) && (arch.contains("64") || arch.contains("amd64") || arch.contains("x86_64"))) {
            return "native/sqlite-vec/linux-x86_64/vec0.so";
        }
        if (os.contains("mac")) {
            if (arch.contains("aarch64") || arch.contains("arm64")) {
                return "native/sqlite-vec/macos-aarch64/vec0.dylib";
            }
            if (arch.contains("x86_64") || arch.contains("amd64")) {
                return "native/sqlite-vec/macos-x86_64/vec0.dylib";
            }
        }

        throw new IllegalStateException("未支持的 sqlite-vec 平台: os=%s, arch=%s".formatted(os, arch));
    }

    private String getFileSuffix(String resourcePath) {
        if (resourcePath.endsWith(".dll")) {
            return ".dll";
        }
        if (resourcePath.endsWith(".dylib")) {
            return ".dylib";
        }
        return ".so";
    }
}
