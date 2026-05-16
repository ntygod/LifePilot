package com.lifepilot.config.workspace;

import com.lifepilot.config.path.ZhiweiPaths;
import com.lifepilot.interaction.web.repository.UserSettingsRepository;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * 统一工作目录解析器。
 *
 * <p>为沙箱、Shell 执行、Tmux 会话等子系统提供统一的默认工作目录。
 * 解析优先级：用户设置（DB） &gt; ZhiweiPaths.workspace()。</p>
 *
 * @author zsg
 * @since 2026-04-12
 */
@Component
public class WorkspaceResolver {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceResolver.class);

    private final UserSettingsRepository settingsRepository;
    private final ZhiweiPaths zhiweiPaths;

    /**
     * 构造工作目录解析器。
     *
     * @param settingsRepository 用户设置仓库，可为 null（CLI 模式下无 DB）
     * @param zhiweiPaths        统一路径提供 Bean，提供默认 workspace 路径
     */
    public WorkspaceResolver(@Nullable UserSettingsRepository settingsRepository,
                             ZhiweiPaths zhiweiPaths) {
        this.settingsRepository = settingsRepository;
        this.zhiweiPaths = zhiweiPaths;
    }

    /**
     * 解析当前生效的工作目录路径。
     *
     * <p>优先级：用户设置 &gt; ZhiweiPaths.workspace()</p>
     *
     * @return 绝对路径
     */
    public Path resolve() {
        // 优先读取用户在设置页面中配置的值
        if (settingsRepository != null) {
            try {
                var settings = settingsRepository.getSettings();
                String userWorkspace = settings.defaultWorkspace();
                if (userWorkspace != null && !userWorkspace.isBlank()) {
                    Path userPath = Path.of(userWorkspace);
                    if (userPath.isAbsolute()) {
                        return userPath;
                    }
                    log.warn("用户配置的工作目录不是绝对路径，忽略: {}", userWorkspace);
                }
            } catch (Exception e) {
                log.debug("读取用户工作目录设置失败，使用默认值: {}", e.getMessage());
            }
        }
        return zhiweiPaths.workspace();
    }

    /**
     * 解析工作目录并确保目录存在。
     *
     * @return 已创建的绝对路径
     */
    public Path resolveAndCreate() {
        Path dir = resolve();
        if (!Files.exists(dir)) {
            try {
                Files.createDirectories(dir);
                log.info("工作目录已创建: {}", dir);
            } catch (IOException e) {
                log.warn("创建工作目录失败: path={}, error={}", dir, e.getMessage());
            }
        }
        return dir;
    }

    /**
     * 获取默认工作目录路径（不考虑用户设置）。
     *
     * @return 默认工作目录路径字符串，来源于 ZhiweiPaths.workspace()
     */
    public String getDefaultDir() {
        return zhiweiPaths.workspace().toString();
    }

    /**
     * 获取用户配置的外部 CLI Bash 依赖路径（给 Claude Code / Codex 等用）。
     *
     * @return 配置路径；未配置或读取失败时返回 null
     */
    @Nullable
    public String getExternalCliBashPath() {
        if (settingsRepository == null) {
            return null;
        }
        try {
            String path = settingsRepository.getSettings().externalCliBashPath();
            return (path == null || path.isBlank()) ? null : path;
        } catch (Exception e) {
            log.debug("读取外部 CLI Bash 路径失败: {}", e.getMessage());
            return null;
        }
    }

    /** 匹配 Windows 盘根：C:\, C:/, C: 等形式。 */
    private static final Pattern WINDOWS_DRIVE_ROOT = Pattern.compile("^[A-Za-z]:[\\\\/]?$");

    /**
     * 规范化结果 — 供调用方感知是否发生了路径替换，便于在工具响应里透明告知 LLM。
     *
     * @param path        规范化后的绝对路径（保证非空且已创建）
     * @param replaced    true 表示原始输入被判定为无效并回退到默认值（调用方应在响应里标记 warning）
     * @param reason      回退原因的简短描述，replaced=false 时为 null
     * @param originalInput 原始输入字符串，保留便于日志/响应回显
     */
    public record NormalizedPath(Path path, boolean replaced, @Nullable String reason,
                                  @Nullable String originalInput) {}

    /**
     * 规范化 LLM 传入的工作目录 — 统一兜底策略，所有接受"工作目录"类参数的工具都应经此方法。
     *
     * <p>硬控制规则：以下情况强制回退到用户配置的默认工作目录并记录 WARN 日志：
     * <ul>
     *   <li>null / 空字符串 / 仅空白（静默回退，属于正常默认场景，replaced=false）</li>
     *   <li>Unix 根目录（{@code /} 或 {@code \}）</li>
     *   <li>Windows 盘根（{@code C:\}、{@code D:/}、{@code C:} 等）</li>
     *   <li>相对路径（LLM 应始终传绝对路径或不传）</li>
     *   <li>无意义路径（{@code .}、{@code ..}）</li>
     *   <li>路径解析抛异常</li>
     * </ul>
     * 设计意图见 memory {@code feedback_hard_vs_soft_control.md}：工作目录属于正确性范畴，
     * 不能只靠 schema description 软引导，必须代码硬控制。</p>
     *
     * @param input LLM 传入的原始值，可能为 null
     * @return 规范化后的绝对路径，保证非空且目录已创建
     */
    public Path normalize(@Nullable String input) {
        return normalizeWithInfo(input).path();
    }

    /**
     * 规范化并返回详细信息 — 调用方需要告知 LLM"参数被替换"时使用此方法。
     *
     * <p>典型用法：工具 executor 读取用户传入的 workingDirectory，调此方法；
     * 如果 {@code replaced=true}，把 {@code originalInput} 和 {@code reason} 一并写入 ToolResult，
     * 让 LLM 明确知道"你传的 X 被替换为默认值 Y，原因是 Z"。</p>
     *
     * @param input LLM 传入的原始值，可能为 null
     * @return 详细规范化结果
     */
    public NormalizedPath normalizeWithInfo(@Nullable String input) {
        Path defaultPath = resolveAndCreate();

        if (input == null || input.isBlank()) {
            return new NormalizedPath(defaultPath, false, null, input);
        }

        String trimmed = input.trim();

        if (trimmed.equals("/") || trimmed.equals("\\")
                || WINDOWS_DRIVE_ROOT.matcher(trimmed).matches()) {
            String reason = "传入文件系统根路径";
            log.warn("工作目录回退默认值：{} '{}'，已替换为 '{}'", reason, trimmed, defaultPath);
            return new NormalizedPath(defaultPath, true, reason, trimmed);
        }

        if (trimmed.equals(".") || trimmed.equals("..")) {
            String reason = "传入相对占位符";
            log.warn("工作目录回退默认值：{} '{}'，已替换为 '{}'", reason, trimmed, defaultPath);
            return new NormalizedPath(defaultPath, true, reason, trimmed);
        }

        try {
            Path path = Path.of(trimmed);
            if (!path.isAbsolute()) {
                String reason = "传入值不是绝对路径";
                log.warn("工作目录回退默认值：{} '{}'，已替换为 '{}'", reason, trimmed, defaultPath);
                return new NormalizedPath(defaultPath, true, reason, trimmed);
            }
            return new NormalizedPath(path, false, null, trimmed);
        } catch (Exception e) {
            String reason = "路径解析失败：" + e.getMessage();
            log.warn("工作目录回退默认值：{} '{}'，已替换为 '{}'", reason, trimmed, defaultPath);
            return new NormalizedPath(defaultPath, true, reason, trimmed);
        }
    }
}
