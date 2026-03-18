package com.lifepilot.multiagent.loader;

import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.multiagent.config.MultiAgentProperties;
import com.lifepilot.multiagent.model.AgentDefinition;
import com.lifepilot.multiagent.model.AgentSource;
import com.lifepilot.multiagent.registry.AgentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Agent Markdown 定义文件加载器（含热加载）。
 *
 * <p>从指定目录加载 {@code .md} 文件，解析为 AgentDefinition 并注册到 AgentRegistry。
 * 支持 ScheduledExecutorService 定期扫描实现热加载（新增/修改/删除检测）。</p>
 *
 * @author zsg
 * @since 2026-02-27
 */
public class AgentMarkdownLoader {

    private static final Logger log = LoggerFactory.getLogger(AgentMarkdownLoader.class);

    private final AgentRegistry agentRegistry;
    private final AgentMarkdownParser parser;
    private final MultiAgentProperties config;
    private final SharedScheduler sharedScheduler;

    /** 已加载文件的 lastModified 缓存，用于热加载变更检测。 */
    private final Map<Path, Instant> loadedFiles = new ConcurrentHashMap<>();

    public AgentMarkdownLoader(AgentRegistry agentRegistry,
                               AgentMarkdownParser parser,
                               MultiAgentProperties config,
                               SharedScheduler sharedScheduler) {
        this.agentRegistry = agentRegistry;
        this.parser = parser;
        this.config = config;
        this.sharedScheduler = sharedScheduler;
    }

    /**
     * 从目录加载所有 .md 文件。
     *
     * @param directory Agent 定义文件目录
     * @return 成功加载的 AgentDefinition 列表
     */
    public List<AgentDefinition> loadFromDirectory(Path directory) {
        if (!Files.exists(directory)) {
            try {
                Files.createDirectories(directory);
                log.info("Agent 定义目录不存在，已自动创建: path={}", directory);
            } catch (IOException e) {
                log.warn("创建 Agent 定义目录失败: path={}, error={}", directory, e.getMessage());
            }
            return List.of();
        }

        var loaded = new ArrayList<AgentDefinition>();
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(p -> p.toString().endsWith(".md"))
                    .forEach(file -> loadFromFile(file).ifPresent(def -> {
                        agentRegistry.register(def);
                        loadedFiles.put(file, getLastModified(file));
                        loaded.add(def);
                    }));
        } catch (IOException e) {
            log.warn("遍历 Agent 定义目录失败: path={}, error={}", directory, e.getMessage());
        }

        log.info("Agent 定义文件加载完成: directory={}, count={}", directory, loaded.size());
        return List.copyOf(loaded);
    }

    /**
     * 解析单个 .md 文件。
     *
     * @param file Agent 定义文件路径
     * @return 解析成功返回 AgentDefinition，失败返回 empty
     */
    public Optional<AgentDefinition> loadFromFile(Path file) {
        try {
            String content = Files.readString(file);
            Instant lastModified = getLastModified(file);
            return parser.parse(content, file, lastModified);
        } catch (IOException e) {
            log.warn("读取 Agent 定义文件失败: path={}, error={}", file, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 启动热加载定时扫描。
     */
    public void startHotReload() {
        int interval = config.getHotReload().getScanIntervalSeconds();
        Path directory = resolveAgentPath();

        sharedScheduler.debounce().scheduleWithFixedDelay(
                () -> performScan(directory),
                interval, interval, TimeUnit.SECONDS);

        log.info("Agent 热加载已启动: directory={}, intervalSeconds={}", directory, interval);
    }

    /** 执行一次热加载扫描。 */
    void performScan(Path directory) {
        try {
            if (!Files.exists(directory)) {
                return;
            }

            // 收集当前目录中的 .md 文件
            Map<Path, Instant> currentFiles = new ConcurrentHashMap<>();
            try (Stream<Path> files = Files.list(directory)) {
                files.filter(p -> p.toString().endsWith(".md"))
                        .forEach(p -> currentFiles.put(p, getLastModified(p)));
            }

            // 检测新增和修改
            for (var entry : currentFiles.entrySet()) {
                Path file = entry.getKey();
                Instant currentModified = entry.getValue();
                Instant cachedModified = loadedFiles.get(file);

                if (cachedModified == null || !cachedModified.equals(currentModified)) {
                    // 新增或修改
                    loadFromFile(file).ifPresent(def -> {
                        agentRegistry.register(def);
                        loadedFiles.put(file, currentModified);
                        log.info("Agent 热加载更新: agentId={}, path={}", def.id(), file);
                    });
                }
            }

            // 检测删除
            var removedFiles = new ArrayList<>(loadedFiles.keySet());
            removedFiles.removeAll(currentFiles.keySet());
            for (Path removed : removedFiles) {
                loadedFiles.remove(removed);
                // 注销该文件对应的 MarkdownDefined Agent
                agentRegistry.listAll().stream()
                        .filter(def -> def.source() instanceof AgentSource.MarkdownDefined md
                                && md.filePath().equals(removed.toString()))
                        .forEach(def -> {
                            agentRegistry.unregister(def.id());
                            log.info("Agent 热加载删除: agentId={}, path={}", def.id(), removed);
                        });
            }
        } catch (Exception e) {
            log.warn("Agent 热加载扫描异常: error={}", e.getMessage());
        }
    }

    /** 获取文件最后修改时间。 */
    private Instant getLastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }

    /** 解析 Agent 定义文件目录路径（支持 ~ 展开）。 */
    private Path resolveAgentPath() {
        String path = config.getAgentDefinitionsPath();
        if (path.startsWith("~")) {
            path = System.getProperty("user.home") + path.substring(1);
        }
        return Path.of(path);
    }
}
