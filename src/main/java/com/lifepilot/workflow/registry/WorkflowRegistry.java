package com.lifepilot.workflow.registry;

import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.workflow.config.WorkflowConfigProperties;
import com.lifepilot.workflow.engine.DagScheduler;
import com.lifepilot.workflow.model.Result;
import com.lifepilot.workflow.model.WorkflowDefinition;
import com.lifepilot.workflow.model.WorkflowStep;
import com.lifepilot.workflow.parser.WorkflowYamlParser;
import com.lifepilot.workflow.trigger.WorkflowTriggerManager;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 工作流注册中心 — 管理 WorkflowDefinition 的注册、查询、启用/禁用。
 *
 * <p>使用 {@link ConcurrentHashMap} 存储工作流定义，保证线程安全的并发访问。
 * 启动时从数据库加载所有已有定义，运行时支持动态注册/注销。
 *
 * <p>注册时执行基本验证（必填字段、步骤非空），无效定义被拒绝并返回 {@code false}。
 * 重复 ID 注册时更新已有定义。禁用的工作流阻止新实例创建，但不影响运行中实例。
 *
 * <p>支持 YAML 文件热加载：定时扫描 definitionsDir 目录，自动检测新增、修改和删除的文件。
 *
 * @author zsg
 * @since 2026-02-26
 */
public class WorkflowRegistry {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRegistry.class);

    private final ConcurrentHashMap<String, WorkflowDefinition> definitions = new ConcurrentHashMap<>();
    private final WorkflowYamlParser parser;

    /** 文件路径 → 最后修改时间，用于检测文件变更。 */
    private final ConcurrentHashMap<String, Instant> fileLastModified = new ConcurrentHashMap<>();

    /** 文件路径 → 工作流 ID，用于检测文件删除后禁用对应定义。 */
    private final ConcurrentHashMap<String, String> fileToWorkflowId = new ConcurrentHashMap<>();

    /** 热加载配置属性，通过 {@link #setConfigProperties(WorkflowConfigProperties)} 注入。
     * -- SETTER --
     *  设置配置属性（热加载所需）。
     *
     */
    @Setter
    private WorkflowConfigProperties configProperties;

    /** 任务调度器，通过 {@link #setTaskScheduler(TaskScheduler)} 注入。
     * -- SETTER --
     *  设置任务调度器（热加载所需）。
     *
     */
    @Setter
    private TaskScheduler taskScheduler;

    /** 触发器管理器引用，用于热加载时注销已删除/禁用工作流的触发器。
     * -- SETTER --
     *  设置触发器管理器（热加载时注销触发器所需）。
     *
     */
    @Setter
    private WorkflowTriggerManager triggerManager;

    /** DAG 调度器引用，用于注册时验证步骤依赖无环。
     * -- SETTER --
     *  设置 DAG 调度器（环检测所需）。
     *
     */
    @Setter
    private DagScheduler dagScheduler;

    /** 工具注册表引用，用于校验 ToolStep 的 toolId 存在性。 */
    @Setter
    private DynamicToolRegistry toolRegistry;

    /** Skill 注册表引用，用于校验 SkillStep 的 skillId 存在性。 */
    @Setter
    private SkillRegistry skillRegistry;

    /**
     * 工作流定义校验结果。
     *
     * @param valid    是否通过基本校验（id/name/steps 非空 + DAG 无环）
     * @param warnings 资源 ID 校验警告列表（不阻止注册）
     */
    public record ValidationResult(boolean valid, List<String> warnings) {
        /** 校验通过且无警告。 */
        public static ValidationResult ok() {
            return new ValidationResult(true, List.of());
        }

        /** 校验失败。 */
        public static ValidationResult fail() {
            return new ValidationResult(false, List.of());
        }

        /** 校验通过但有警告。 */
        public static ValidationResult withWarnings(List<String> warnings) {
            return new ValidationResult(true, List.copyOf(warnings));
        }
    }

    /**
     * 构造 WorkflowRegistry，注入 YAML 解析器。
     *
     * <p>工作流定义仅通过 {@link #startScheduledScan()} 的文件扫描加载到内存缓存，
     * 不再从数据库预加载。
     *
     * @param parser YAML 解析器
     */
    public WorkflowRegistry(WorkflowYamlParser parser) {
        this.parser = parser;
    }

    /**
     * 启动定时扫描 — 周期性扫描 definitionsDir 目录，检测新增/修改/删除的 YAML 文件。
     *
     * <p>首次调用时立即执行一次全量扫描，之后按 {@code scanIntervalSeconds} 配置的间隔定时扫描。
     * 扫描逻辑：
     * <ul>
     *   <li>新增文件 → 解析并注册</li>
     *   <li>修改文件（lastModified 变化）→ 重新解析并更新</li>
     *   <li>删除文件 → 禁用对应工作流定义（保留实例历史）</li>
     *   <li>解析失败 → WARN 日志，跳过该文件</li>
     * </ul>
     *
     * <p>需要先通过 {@link #setConfigProperties(WorkflowConfigProperties)} 和
     * {@link #setTaskScheduler(TaskScheduler)} 注入依赖。
     */
    public void startScheduledScan() {
        if (configProperties == null || taskScheduler == null) {
            log.warn("热加载启动失败: configProperties 或 taskScheduler 未注入");
            return;
        }

        Path directory = resolveDefinitionsDir(configProperties.getDefinitionsDir());
        int intervalSeconds = configProperties.getScanIntervalSeconds();

        // 首次立即执行全量扫描
        performScan(directory);

        // 注册定时任务
        taskScheduler.scheduleAtFixedRate(
                () -> performScan(directory),
                Duration.ofSeconds(intervalSeconds)
        );

        log.info("工作流 YAML 热加载已启动: dir={}, interval={}s", directory, intervalSeconds);
    }

    /**
     * 注册工作流定义。
     *
     * <p>注册前执行验证：id 和 name 不能为空/空白，steps 不能为空。
     * 验证失败时返回 {@code false} 并记录 WARN 日志。
     *
     * <p>如果已存在相同 ID 的定义，则更新已有定义并记录 INFO 日志。
     * 注册仅更新内存缓存，不持久化到数据库（定义的权威来源为 YAML 文件）。
     *
     * @param definition 工作流定义
     * @return 注册成功返回 {@code true}，验证失败返回 {@code false}
     */
    public boolean register(WorkflowDefinition definition) {
        // 验证必填字段
        ValidationResult validation = validate(definition);
        if (!validation.valid()) {
            return false;
        }

        // 记录资源校验警告
        if (!validation.warnings().isEmpty()) {
            log.info("工作流定义注册（含 {} 条资源警告）: id={}", validation.warnings().size(), definition.id());
        }

        // 检查是否为更新
        boolean isUpdate = definitions.containsKey(definition.id());
        definitions.put(definition.id(), definition);

        if (isUpdate) {
            log.info("工作流定义更新: id={}, name={}", definition.id(), definition.name());
        } else {
            log.info("工作流定义注册成功: id={}, name={}", definition.id(), definition.name());
        }

        // 注册成功且已启用时，通知触发器管理器注册触发器
        if (definition.enabled() && triggerManager != null) {
            triggerManager.registerTriggers(definition);
        }

        return true;
    }

    /**
     * 注销工作流定义。
     *
     * <p>从内存中移除定义，但不从数据库删除（保留历史记录）。
     *
     * @param workflowId 工作流定义 ID
     * @return 移除成功返回 {@code true}，未找到返回 {@code false}
     */
    public boolean unregister(String workflowId) {
        WorkflowDefinition removed = definitions.remove(workflowId);
        if (removed != null) {
            notifyTriggerUnregister(workflowId);
            log.info("工作流定义注销: id={}", workflowId);
            return true;
        }
        log.warn("工作流定义注销失败，未找到: id={}", workflowId);
        return false;
    }

    /**
     * 启用工作流定义。
     *
     * <p>仅更新内存中的启用状态。
     *
     * @param workflowId 工作流定义 ID
     * @return 启用成功返回 {@code true}，未找到返回 {@code false}
     */
    public boolean enable(String workflowId) {
        return updateEnabled(workflowId, true);
    }

    /**
     * 禁用工作流定义。
     *
     * <p>禁用后阻止新实例创建，但允许运行中实例完成。
     * 仅更新内存中的启用状态。
     *
     * @param workflowId 工作流定义 ID
     * @return 禁用成功返回 {@code true}，未找到返回 {@code false}
     */
    public boolean disable(String workflowId) {
        return updateEnabled(workflowId, false);
    }

    /**
     * 根据 ID 查找工作流定义。
     *
     * <p>仅从内存缓存查找，缓存由 {@link #startScheduledScan()} 的文件扫描填充。
     *
     * @param workflowId 工作流定义 ID
     * @return 工作流定义 Optional，未找到时返回 empty
     */
    public Optional<WorkflowDefinition> find(String workflowId) {
        return Optional.ofNullable(definitions.get(workflowId));
    }

    /**
     * 返回所有已注册的工作流定义。
     *
     * @return 不可变的工作流定义列表
     */
    public List<WorkflowDefinition> listAll() {
        return List.copyOf(definitions.values());
    }

    /**
     * 返回所有已启用的工作流定义。
     *
     * @return 不可变的已启用工作流定义列表
     */
    public List<WorkflowDefinition> listEnabled() {
        return definitions.values().stream()
                .filter(WorkflowDefinition::enabled)
                .toList();
    }

    // ==================== 热加载内部方法 ====================

    /**
     * 通知触发器管理器注销指定工作流的触发器。
     *
     * <p>如果 triggerManager 未注入则跳过（不影响核心功能）。
     *
     * @param workflowId 工作流定义 ID
     */
    private void notifyTriggerUnregister(String workflowId) {
        if (triggerManager != null) {
            triggerManager.unregisterTriggers(workflowId);
        }
    }

    /**
     * 执行一次扫描周期 — 检测新增、修改和删除的 YAML 文件。
     *
     * @param directory 工作流定义目录
     */
    void performScan(Path directory) {
        if (!Files.isDirectory(directory)) {
            log.debug("工作流定义目录不存在，跳过扫描: path={}", directory);
            return;
        }

        Set<String> currentFiles = new HashSet<>();

        try (Stream<Path> files = Files.list(directory)) {
            files.filter(this::isYamlFile)
                    .forEach(file -> {
                        String filePath = file.toAbsolutePath().toString();
                        currentFiles.add(filePath);

                        try {
                            Instant lastModified = Files.getLastModifiedTime(file).toInstant();
                            Instant previousModified = fileLastModified.get(filePath);

                            if (previousModified == null) {
                                // 新增文件
                                handleNewFile(file, filePath, lastModified);
                            } else if (!lastModified.equals(previousModified)) {
                                // 修改文件
                                handleModifiedFile(file, filePath, lastModified);
                            }
                            // 未修改 → 跳过
                        } catch (IOException e) {
                            log.warn("读取文件修改时间失败: file={}, 原因={}", file, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.error("扫描工作流定义目录失败: path={}, 原因={}", directory, e.getMessage());
            return;
        }

        // 检测删除的文件
        handleDeletedFiles(currentFiles);
    }

    /**
     * 处理新增的 YAML 文件 — 解析并注册。
     */
    private void handleNewFile(Path file, String filePath, Instant lastModified) {
        try {
            String yaml = Files.readString(file);
            Result<WorkflowDefinition, List<String>> result = parser.parse(yaml);
            switch (result) {
                case Result.Ok<WorkflowDefinition, List<String>> ok -> {
                    if (register(ok.value())) {
                        fileLastModified.put(filePath, lastModified);
                        fileToWorkflowId.put(filePath, ok.value().id());
                        log.info("热加载: 新增工作流文件: file={}, id={}", file.getFileName(), ok.value().id());
                    }
                }
                case Result.Err<WorkflowDefinition, List<String>> err -> {
                    log.warn("热加载: YAML 解析失败，跳过文件: file={}, errors={}", file.getFileName(), err.error());
                    // 记录文件时间，避免每次扫描都重新尝试解析
                    fileLastModified.put(filePath, lastModified);
                }
            }
        } catch (IOException e) {
            log.warn("热加载: 读取文件失败: file={}, 原因={}", file.getFileName(), e.getMessage());
        }
    }

    /**
     * 处理修改的 YAML 文件 — 重新解析并更新注册。
     */
    private void handleModifiedFile(Path file, String filePath, Instant lastModified) {
        try {
            String yaml = Files.readString(file);
            Result<WorkflowDefinition, List<String>> result = parser.parse(yaml);
            switch (result) {
                case Result.Ok<WorkflowDefinition, List<String>> ok -> {
                    // 如果文件之前关联了不同的 workflowId，清理旧映射并注销触发器
                    String previousId = fileToWorkflowId.get(filePath);
                    if (previousId != null && !previousId.equals(ok.value().id())) {
                        disable(previousId);
                    } else if (previousId != null) {
                        // 同一 workflowId 更新：先注销旧触发器，register() 内部会注册新触发器
                        notifyTriggerUnregister(previousId);
                    }
                    if (register(ok.value())) {
                        fileLastModified.put(filePath, lastModified);
                        fileToWorkflowId.put(filePath, ok.value().id());
                        log.info("热加载: 更新工作流文件: file={}, id={}", file.getFileName(), ok.value().id());
                    }
                }
                case Result.Err<WorkflowDefinition, List<String>> err -> {
                    log.warn("热加载: YAML 解析失败，跳过更新: file={}, errors={}", file.getFileName(), err.error());
                    fileLastModified.put(filePath, lastModified);
                }
            }
        } catch (IOException e) {
            log.warn("热加载: 读取文件失败: file={}, 原因={}", file.getFileName(), e.getMessage());
        }
    }

    /**
     * 处理删除的文件 — 禁用对应的工作流定义并注销触发器。
     */
    private void handleDeletedFiles(Set<String> currentFiles) {
        Set<String> knownFiles = new HashSet<>(fileLastModified.keySet());
        for (String knownFile : knownFiles) {
            if (!currentFiles.contains(knownFile)) {
                String workflowId = fileToWorkflowId.remove(knownFile);
                fileLastModified.remove(knownFile);
                if (workflowId != null) {
                    disable(workflowId);
                    notifyTriggerUnregister(workflowId);
                    log.info("热加载: 文件已删除，禁用工作流并注销触发器: file={}, id={}", knownFile, workflowId);
                }
            }
        }
    }

    /**
     * 解析 definitionsDir 路径，将 {@code ~} 替换为用户主目录。
     *
     * @param dir 配置的目录路径
     * @return 解析后的绝对路径
     */
    static Path resolveDefinitionsDir(String dir) {
        if (dir.startsWith("~")) {
            String home = System.getProperty("user.home");
            return Path.of(home + dir.substring(1));
        }
        return Path.of(dir);
    }

    // ==================== 内部方法 ====================

    /**
     * 验证工作流定义的必填字段。
     *
     * @param definition 工作流定义
     * @return 验证通过返回 {@code true}，否则返回 {@code false}
     */
    private ValidationResult validate(WorkflowDefinition definition) {
        if (definition == null) {
            log.warn("工作流定义验证失败: 定义为 null");
            return ValidationResult.fail();
        }
        if (definition.id() == null || definition.id().isBlank()) {
            log.warn("工作流定义验证失败: id 为空");
            return ValidationResult.fail();
        }
        if (definition.name() == null || definition.name().isBlank()) {
            log.warn("工作流定义验证失败: name 为空");
            return ValidationResult.fail();
        }
        if (definition.steps() == null || definition.steps().isEmpty()) {
            log.warn("工作流定义验证失败: steps 为空, id={}", definition.id());
            return ValidationResult.fail();
        }

        // DAG 环检测
        if (dagScheduler != null) {
            try {
                dagScheduler.buildExecutionPlan(definition.steps());
            } catch (IllegalArgumentException e) {
                log.warn("工作流定义验证失败: 步骤存在环依赖, id={}, error={}", definition.id(), e.getMessage());
                return ValidationResult.fail();
            }
        }

        // 资源 ID 存在性校验（仅警告，不阻止注册）
        List<String> warnings = new ArrayList<>();
        for (WorkflowStep step : definition.steps()) {
            switch (step) {
                case WorkflowStep.ToolStep ts -> {
                    if (toolRegistry != null && toolRegistry.resolve(ts.toolId()).isEmpty()) {
                        String msg = "步骤 '" + ts.id() + "' 引用的 toolId '" + ts.toolId() + "' 未在注册表中找到";
                        warnings.add(msg);
                        log.warn("工作流资源校验警告: workflowId={}, {}", definition.id(), msg);
                    }
                }
                case WorkflowStep.SkillStep ss -> {
                    if (skillRegistry != null && skillRegistry.find(ss.skillId()).isEmpty()) {
                        String msg = "步骤 '" + ss.id() + "' 引用的 skillId '" + ss.skillId() + "' 未在注册表中找到";
                        warnings.add(msg);
                        log.warn("工作流资源校验警告: workflowId={}, {}", definition.id(), msg);
                    }
                }
                case WorkflowStep.SubWorkflowStep sw -> {
                    if (find(sw.workflowId()).isEmpty()) {
                        String msg = "步骤 '" + sw.id() + "' 引用的 workflowId '" + sw.workflowId() + "' 未在注册表中找到";
                        warnings.add(msg);
                        log.warn("工作流资源校验警告: workflowId={}, {}", definition.id(), msg);
                    }
                }
                default -> { /* LLM、Condition、Loop 等步骤无需校验外部资源 */ }
            }
        }

        return warnings.isEmpty() ? ValidationResult.ok() : ValidationResult.withWarnings(warnings);
    }

    /**
     * 更新工作流定义的启用状态（仅内存）。
     */
    private boolean updateEnabled(String workflowId, boolean enabled) {
        WorkflowDefinition existing = definitions.get(workflowId);
        if (existing == null) {
            log.warn("工作流定义{}失败，未找到: id={}", enabled ? "启用" : "禁用", workflowId);
            return false;
        }

        WorkflowDefinition updated = existing.toBuilder().enabled(enabled).build();
        definitions.put(workflowId, updated);

        // 通知触发器管理器：启用时注册触发器，禁用时注销触发器
        if (triggerManager != null) {
            if (enabled) {
                triggerManager.registerTriggers(updated);
            } else {
                triggerManager.unregisterTriggers(workflowId);
            }
        }

        log.info("工作流定义{}: id={}", enabled ? "启用" : "禁用", workflowId);
        return true;
    }

    /**
     * 判断文件是否为 YAML 文件。
     */
    private boolean isYamlFile(Path path) {
        String fileName = path.getFileName().toString().toLowerCase();
        return Files.isRegularFile(path)
                && (fileName.endsWith(".yml") || fileName.endsWith(".yaml"));
    }
}


