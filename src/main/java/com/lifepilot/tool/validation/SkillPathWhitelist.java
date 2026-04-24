package com.lifepilot.tool.validation;

import com.lifepilot.config.workspace.WorkspaceResolver;
import com.lifepilot.skill.config.SkillConfigProperties;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code file.read} 路径白名单校验器 —— 防 path traversal。
 *
 * <p>Skill 系统重构（2026-04-24）后 {@code file.read} 回归纯文件读取入口，不再承担
 * 加载 SKILL.md 的职责。LLM 只应在"读 Skill 目录下的参考文档"或"读工作区内文件"两种
 * 场景使用 {@code file.read(path=...)}；任何超出这两个根的绝对路径都会被判定为越权。</p>
 *
 * <p>白名单根：</p>
 * <ul>
 *   <li>Skill 目录：{@link SkillConfigProperties#getDirectory()} —— 通常是 {@code ~/.zhiwei/skills}</li>
 *   <li>工作区目录：{@link WorkspaceResolver#getDefaultDir()} —— 通常是 {@code ~/.zhiwei/workspace}</li>
 *   <li>当前 JVM 进程工作目录：{@code System.getProperty("user.dir")} —— 开发态命令行调试兜底</li>
 * </ul>
 *
 * <p>该校验与 {@code PathSecurityChecker} 语义互补：后者读 {@code application.yml} 中
 * 用户/管理员配置的 allow/deny 列表，可能完全放开（空白名单）；本校验是硬约束，确保 LLM
 * 调用 {@code file.read(path=...)} 时不会越出 Skill 系统相关的两个受信目录。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
@Component
public class SkillPathWhitelist {

    private static final Logger log = LoggerFactory.getLogger(SkillPathWhitelist.class);

    /** Windows 平台标识 —— 用于路径大小写不敏感比较。 */
    private static final boolean IS_WINDOWS = java.io.File.separatorChar == '\\';

    /** 白名单根路径（已规范化为绝对路径）。 */
    private final List<Path> whitelistRoots;

    public SkillPathWhitelist(@Nullable SkillConfigProperties skillConfig,
                              @Nullable WorkspaceResolver workspaceResolver) {
        var roots = new ArrayList<Path>();
        if (skillConfig != null && skillConfig.getDirectory() != null
                && !skillConfig.getDirectory().isBlank()) {
            roots.add(normalize(skillConfig.getDirectory()));
        }
        if (workspaceResolver != null) {
            String workspaceDir = workspaceResolver.getDefaultDir();
            if (workspaceDir != null && !workspaceDir.isBlank()) {
                roots.add(normalize(workspaceDir));
            }
        }
        // 兜底 —— 进程工作目录，方便命令行冒烟和 IDE 运行
        roots.add(normalize(System.getProperty("user.dir")));
        this.whitelistRoots = List.copyOf(roots);
        log.info("SkillPathWhitelist 初始化完成，白名单根: {}", whitelistRoots);
    }

    /**
     * 校验路径是否在白名单范围内。
     *
     * @param pathStr 目标路径（绝对或相对均可）
     * @throws SecurityException 路径超出白名单
     * @throws IllegalArgumentException 路径字符串为空
     */
    public void validate(String pathStr) {
        if (pathStr == null || pathStr.isBlank()) {
            throw new IllegalArgumentException("path 不能为空");
        }
        Path normalized = normalize(pathStr);
        for (Path root : whitelistRoots) {
            if (pathStartsWith(normalized, root)) {
                return;
            }
        }
        log.warn("file.read 路径超出白名单: path={}, allowedRoots={}", normalized, whitelistRoots);
        throw new SecurityException("file.read 路径超出白名单: " + normalized
                + "（允许范围: " + whitelistRoots + "）");
    }

    /** 规范化路径 —— 先取绝对路径再 normalize，避免 {@code ..} 偷越白名单。 */
    private static Path normalize(String pathStr) {
        return Paths.get(pathStr).toAbsolutePath().normalize();
    }

    /**
     * 路径前缀比较 —— Windows 下大小写不敏感，其他平台使用 {@link Path#startsWith(Path)}。
     */
    private static boolean pathStartsWith(Path path, Path prefix) {
        if (IS_WINDOWS) {
            // 必须用 Path.startsWith 做组件级比较，避免 "foobar" 匹配 "foo" 前缀
            return Path.of(path.toString().toLowerCase(Locale.ROOT))
                    .startsWith(Path.of(prefix.toString().toLowerCase(Locale.ROOT)));
        }
        return path.startsWith(prefix);
    }
}
