package com.lifepilot.config.path;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 统一路径提供 Bean —— 负责解析 HOME 目录及其所有子目录的绝对路径，
 * 消除散落在各模块中的重复路径拼接逻辑。
 *
 * <h3>目录模型</h3>
 * <ul>
 *   <li><b>HOME</b>：知微安装数据目录，默认 {@code ~/zhiwei}，所有持久化数据存放于此</li>
 *   <li><b>WORKSPACE</b>：Agent 默认工作目录（cwd），默认 {@code ${HOME}/workspace}</li>
 * </ul>
 *
 * <p>初始化时自动创建 HOME 及所有固定子目录（如不存在）。</p>
 *
 * @author zsg
 * @since 2026-06-15
 */
@Component
public class ZhiweiPaths {

    private static final Logger log = LoggerFactory.getLogger(ZhiweiPaths.class);

    // ==================== 固定子目录常量 ====================

    /** 数据库目录 */
    public static final String DIR_DB = "db";
    /** 技能目录 */
    public static final String DIR_SKILLS = "skills";
    /** Agent 定义目录 */
    public static final String DIR_AGENTS = "agents";
    /** 工作流定义目录 */
    public static final String DIR_WORKFLOWS = "workflows";
    /** 频道配置目录 */
    public static final String DIR_CHANNELS = "channels";
    /** 知识库数据目录 */
    public static final String DIR_KNOWLEDGE = "knowledge";
    /** 文档产物目录 */
    public static final String DIR_DOCUMENTS = "documents";
    /** 缓存目录 */
    public static final String DIR_CACHE = "cache";
    /** 浏览器缓存目录 */
    public static final String DIR_CACHE_BROWSER = "cache/browser";
    /** 运行时目录 */
    public static final String DIR_RUNTIME = "runtime";
    /** Python 运行时目录 */
    public static final String DIR_RUNTIME_PYTHON = "runtime/python";
    /** 日志目录 */
    public static final String DIR_LOGS = "logs";

    /** 所有需要在初始化时创建的固定子目录 */
    private static final List<String> REQUIRED_SUBDIRS = List.of(
            DIR_DB, DIR_SKILLS, DIR_AGENTS, DIR_WORKFLOWS, DIR_CHANNELS,
            DIR_KNOWLEDGE, DIR_DOCUMENTS, DIR_CACHE, DIR_CACHE_BROWSER,
            DIR_RUNTIME, DIR_RUNTIME_PYTHON, DIR_LOGS
    );

    /** HOME 默认值 */
    private static final String DEFAULT_HOME = "~/zhiwei";

    // ==================== 配置注入 ====================

    /**
     * 从配置读取 HOME 路径。
     * BootstrapConfigReader 将 config.json 中的 home 字段映射到此属性。
     */
    @Value("${zhiwei.home:}")
    private String configuredHome;

    /**
     * 从配置读取 WORKSPACE 路径。
     * BootstrapConfigReader 将 config.json 中的 workspace 字段映射到此属性。
     */
    @Value("${zhiwei.workspace:}")
    private String configuredWorkspace;

    // ==================== 解析后的路径 ====================

    private Path homePath;
    private Path workspacePath;

    // ==================== 初始化 ====================

    @PostConstruct
    void init() {
        // 解析 HOME 路径
        String rawHome = PathResolver.resolveOrDefault(configuredHome, DEFAULT_HOME);
        String expandedHome = PathResolver.expand(rawHome);
        this.homePath = Path.of(expandedHome).toAbsolutePath().normalize();
        PathResolver.validateAbsolute(this.homePath);

        // 解析 WORKSPACE 路径
        String defaultWorkspace = homePath.resolve("workspace").toString();
        String rawWorkspace = PathResolver.resolveOrDefault(configuredWorkspace, defaultWorkspace);
        String expandedWorkspace = PathResolver.expand(rawWorkspace);
        this.workspacePath = Path.of(expandedWorkspace).toAbsolutePath().normalize();
        PathResolver.validateAbsolute(this.workspacePath);

        // 创建 HOME 及所有子目录
        ensureDirectories();

        log.info("ZhiweiPaths 初始化完成: HOME={}, WORKSPACE={}", homePath, workspacePath);
    }

    /**
     * 创建 HOME 目录及所有固定子目录（如不存在）。
     * WORKSPACE 目录也一并创建。
     */
    private void ensureDirectories() {
        try {
            // 创建 HOME 目录
            Files.createDirectories(homePath);

            // 创建所有固定子目录
            for (String subDir : REQUIRED_SUBDIRS) {
                Files.createDirectories(homePath.resolve(subDir));
            }

            // 创建 WORKSPACE 目录
            Files.createDirectories(workspacePath);
        } catch (IOException e) {
            throw new UncheckedIOException("创建知微目录结构失败: " + e.getMessage(), e);
        }
    }

    // ==================== 公开 API ====================

    /**
     * 返回 HOME 目录的绝对路径。
     *
     * @return HOME 绝对路径，不为 null
     */
    public Path home() {
        return homePath;
    }

    /**
     * 返回 HOME 下指定子目录的绝对路径。
     *
     * <p>示例：{@code zhiweiPaths.home("skills")} → {@code /home/user/zhiwei/skills}</p>
     *
     * @param subDir 子目录相对路径（如 "skills"、"cache/browser"），不可为 null 或空白
     * @return 子目录绝对路径
     * @throws IllegalArgumentException 如果 subDir 为 null/空白，或路径逃逸 HOME
     */
    public Path home(String subDir) {
        if (subDir == null || subDir.isBlank()) {
            throw new IllegalArgumentException("子目录名不能为 null 或空白");
        }
        Path resolved = homePath.resolve(subDir).normalize();
        // 防止 ".." 逃逸 HOME 目录
        PathResolver.rejectEscape(resolved, homePath);
        return resolved;
    }

    /**
     * 返回 WORKSPACE 目录的绝对路径。
     *
     * <p>如果 WORKSPACE 目录不存在，首次访问时自动创建。</p>
     *
     * @return WORKSPACE 绝对路径，不为 null
     */
    public Path workspace() {
        // 确保 WORKSPACE 目录存在（满足 Req 4.5：首次访问时创建）
        if (!Files.exists(workspacePath)) {
            try {
                Files.createDirectories(workspacePath);
            } catch (IOException e) {
                throw new UncheckedIOException("创建 WORKSPACE 目录失败: " + workspacePath, e);
            }
        }
        return workspacePath;
    }
}
