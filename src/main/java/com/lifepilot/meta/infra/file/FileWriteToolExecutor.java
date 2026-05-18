package com.lifepilot.meta.infra.file;

import com.lifepilot.config.path.PathResolver;
import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.meta.infra.file.history.FileEditHistory;
import com.lifepilot.meta.infra.file.history.LintHookExecutor;
import com.lifepilot.tool.artifact.ArtifactFilter;
import com.lifepilot.tool.artifact.ArtifactFilterConfig;
import com.lifepilot.tool.model.ToolArtifact;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件写入工具 — 原子写入（临时文件 + {@code Files.move(ATOMIC_MOVE)}）。
 *
 * <p>安全机制：
 * <ul>
 *   <li>RiskLevel MEDIUM — GuardrailEngine 自动执行 + 审计日志</li>
 *   <li>通过 {@link PathSecurityChecker} 校验路径白名单/黑名单</li>
 *   <li>原子写入防止写入中断导致文件损坏</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class FileWriteToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(FileWriteToolExecutor.class);

    private final PathSecurityChecker securityChecker;
    @Nullable
    private final FileEditHistory editHistory;
    @Nullable
    private final LintHookExecutor lintHook;
    @Nullable
    private final MetaProperties.Infra.FileEdit fileEditConfig;
    @Nullable
    private final Path workspaceRoot;
    private final ArtifactFilterConfig artifactFilterConfig;

    /**
     * 构造函数 — 共享 PathSecurityChecker 实例。
     *
     * <p>历史调用方使用此构造器；artifact 登记会被关闭（workspaceRoot=null）。</p>
     *
     * @param securityChecker 路径安全检查器（共享）
     * @param editHistory     文件编辑历史（可为 null）
     * @param lintHook        lint 钩子执行器（可为 null）
     * @param fileEditConfig  文件编辑配置（可为 null）
     */
    FileWriteToolExecutor(PathSecurityChecker securityChecker,
                          @Nullable FileEditHistory editHistory,
                          @Nullable LintHookExecutor lintHook,
                          @Nullable MetaProperties.Infra.FileEdit fileEditConfig) {
        this(securityChecker, editHistory, lintHook, fileEditConfig, null,
                ArtifactFilterConfig.defaultConfig());
    }

    /**
     * 完整构造函数 — 支持 artifact 登记。
     *
     * @param securityChecker      路径安全检查器（共享）
     * @param editHistory          文件编辑历史（可为 null）
     * @param lintHook             lint 钩子执行器（可为 null）
     * @param fileEditConfig       文件编辑配置（可为 null）
     * @param workspaceRoot        workspace 白名单根目录；为 null 时关闭 artifact 登记
     * @param artifactFilterConfig 产物过滤配置；调用方应已应用 {@code GatewayDeliveryProperties.toFilterConfig()}
     */
    FileWriteToolExecutor(PathSecurityChecker securityChecker,
                          @Nullable FileEditHistory editHistory,
                          @Nullable LintHookExecutor lintHook,
                          @Nullable MetaProperties.Infra.FileEdit fileEditConfig,
                          @Nullable Path workspaceRoot,
                          ArtifactFilterConfig artifactFilterConfig) {
        this.securityChecker = securityChecker;
        this.editHistory = editHistory;
        this.lintHook = lintHook;
        this.fileEditConfig = fileEditConfig;
        this.workspaceRoot = workspaceRoot;
        this.artifactFilterConfig = artifactFilterConfig != null
                ? artifactFilterConfig : ArtifactFilterConfig.defaultConfig();
    }

    /**
     * 原子写入文件。
     *
     * @param input 工具输入，必需参数 path 和 content，可选 createDirectories
     * @return 包含 path 和 bytesWritten 的结构化结果
     */
    public ToolResult execute(ToolInput input) {
        String pathStr;
        String content;
        try {
            pathStr = input.getParam("path", String.class);
            content = input.getParam("content", String.class);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("缺少必需参数: " + e.getMessage());
        }
        pathStr = PathResolver.expand(pathStr);

        boolean createDirectories = input.getOptionalParam("createDirectories", Boolean.class)
                .orElse(true);

        String mode = input.getOptionalParam("mode", String.class).orElse("write");

        Path filePath = Path.of(pathStr);

        // 路径安全检查（写入场景，文件可能不存在）
        var rejection = securityChecker.checkForWrite(filePath);
        if (rejection.isPresent()) {
            return ToolResult.error(rejection.get());
        }

        try {
            Path parentDir = filePath.toAbsolutePath().normalize().getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                if (createDirectories) {
                    Files.createDirectories(parentDir);
                } else {
                    return ToolResult.error("父目录不存在: " + parentDir);
                }
            }

            Path normalizedPath = filePath.toAbsolutePath().normalize();

            // 修改前捕获快照（仅对已存在的文件）
            if (editHistory != null && Files.exists(normalizedPath)) {
                editHistory.captureBeforeModify(normalizedPath);
            }

            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

            if ("append".equalsIgnoreCase(mode)) {
                // 追加模式
                Files.write(normalizedPath, bytes,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
                log.debug("文件追加成功: path={}, bytesWritten={}", pathStr, bytes.length);
            } else {
                // 原子写入：先写临时文件，再 move
                Path tempFile = Files.createTempFile(parentDir, ".lifepilot-write-", ".tmp");
                try {
                    Files.write(tempFile, bytes);
                    Files.move(tempFile, normalizedPath,
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    Files.deleteIfExists(tempFile);
                    throw e;
                }
                log.debug("文件写入成功: path={}, bytesWritten={}", pathStr, bytes.length);
            }

            var data = new LinkedHashMap<String, Object>();
            data.put("path", normalizedPath.toString());
            data.put("bytesWritten", bytes.length);
            data.put("mode", mode);

            // 写入成功后执行 lint 检查
            if (lintHook != null && fileEditConfig != null && fileEditConfig.isAutoLint()) {
                String lintOutput = lintHook.runLint(normalizedPath,
                        fileEditConfig.getLintCommands(),
                        fileEditConfig.getLintTimeoutSeconds());
                if (!lintOutput.isEmpty()) {
                    data.put("lintWarning", lintOutput);
                }
            }

            // 写入成功后登记 ToolArtifact（仅当 workspace 白名单 + 过滤通过）
            List<ToolArtifact> artifacts = collectArtifact(normalizedPath, bytes.length);

            return ToolResult.success(Map.copyOf(data), com.lifepilot.tool.model.ToolResultMeta.empty(), artifacts);

        } catch (IOException e) {
            log.error("文件写入失败: path={}, error={}", pathStr, e.getMessage(), e);
            return ToolResult.error("文件写入失败: " + e.getMessage());
        }
    }

    /**
     * 写入完成后构造 ToolArtifact；越界 / 过滤不通过时返回空列表。
     *
     * <p>artifact 登记失败不影响 write 操作本身的成功语义，错误降级为 DEBUG 日志。</p>
     */
    private List<ToolArtifact> collectArtifact(Path normalizedPath, long size) {
        if (workspaceRoot == null) {
            return List.of();
        }
        try {
            if (!ArtifactFilter.isInWorkspaceRoot(normalizedPath, workspaceRoot)) {
                log.debug("artifact 路径越界，跳过登记: {}", normalizedPath);
                return List.of();
            }
            if (!ArtifactFilter.accept(normalizedPath, size, artifactFilterConfig)) {
                log.debug("artifact 过滤命中，跳过登记: {} (size={})", normalizedPath, size);
                return List.of();
            }
            return List.of(ToolArtifact.fromFile(normalizedPath));
        } catch (IOException e) {
            log.debug("artifact 元信息读取失败，跳过登记: path={}, error={}", normalizedPath, e.getMessage());
            return List.of();
        }
    }
}
