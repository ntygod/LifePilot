package com.lifepilot.config.workspace;

import com.lifepilot.interaction.web.repository.UserSettingsRepository;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 统一工作目录解析器。
 *
 * <p>为沙箱、Shell 执行、Tmux 会话等子系统提供统一的默认工作目录。
 * 解析优先级：用户设置（DB） &gt; application.yml 配置 &gt; 默认 ~/.zhiwei/workspace/。</p>
 *
 * @author zsg
 * @since 2026-04-12
 */
@Component
public class WorkspaceResolver {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceResolver.class);

    private final UserSettingsRepository settingsRepository;
    private final String defaultWorkspaceDir;

    public WorkspaceResolver(@Nullable UserSettingsRepository settingsRepository,
                             @Value("${zhiwei.workspace-dir:}") String configuredDir) {
        this.settingsRepository = settingsRepository;
        this.defaultWorkspaceDir = configuredDir.isBlank()
                ? Path.of(System.getProperty("user.home"), ".zhiwei", "workspace").toString()
                : configuredDir;
    }

    /**
     * 解析当前生效的工作目录路径。
     *
     * <p>优先级：用户设置 &gt; yml 配置 &gt; ~/.zhiwei/workspace/</p>
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
        return Path.of(defaultWorkspaceDir);
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
     * 获取 application.yml 中配置的默认值（不考虑用户设置）。
     *
     * @return 默认工作目录路径字符串
     */
    public String getDefaultDir() {
        return defaultWorkspaceDir;
    }
}
