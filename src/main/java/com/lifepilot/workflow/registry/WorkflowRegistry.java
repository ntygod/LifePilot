package com.lifepilot.workflow.registry;

import com.lifepilot.config.path.ZhiweiPaths;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.workflow.config.WorkflowConfigProperties;
import com.lifepilot.workflow.engine.DagScheduler;
import com.lifepilot.workflow.model.Result;
import com.lifepilot.workflow.model.WorkflowDefinition;
import com.lifepilot.workflow.model.WorkflowStep;
import com.lifepilot.workflow.parser.WorkflowYamlParser;
import com.lifepilot.workflow.parser.WorkflowYamlPrinter;
import com.lifepilot.workflow.repository.WorkflowRepository;
import com.lifepilot.workflow.trigger.WorkflowTriggerManager;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.TaskScheduler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Workflow definition registry with local hot-reload support.
 *
 * @author zsg
 * @since 2026-02-26
 */
public class WorkflowRegistry {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRegistry.class);

    private final ConcurrentHashMap<String, WorkflowDefinition> definitions = new ConcurrentHashMap<>();
    private final WorkflowYamlParser parser;
    private final ConcurrentHashMap<String, Instant> fileLastModified = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> fileToWorkflowId = new ConcurrentHashMap<>();
    private static volatile String duplicateStemSignature = "";

    /** 持久化仓储，注册时同步写入数据库以满足外键约束。 */
    @Setter
    @Nullable
    private WorkflowRepository repository;

    /** YAML 序列化器，当原始 YAML 不可用时用于生成持久化内容。 */
    @Setter
    @Nullable
    private WorkflowYamlPrinter yamlPrinter;

    @Setter
    private WorkflowConfigProperties configProperties;

    /** 统一路径提供 Bean，用于获取工作流定义目录。 */
    @Setter
    private ZhiweiPaths zhiweiPaths;

    @Setter
    private TaskScheduler taskScheduler;

    @Setter
    private WorkflowTriggerManager triggerManager;

    @Setter
    private DagScheduler dagScheduler;

    @Setter
    private DynamicToolRegistry toolRegistry;

    @Setter
    private SkillRegistry skillRegistry;

    private volatile boolean startupPhase = true;

    public record ValidationResult(boolean valid, List<String> warnings) {
        public static ValidationResult ok() {
            return new ValidationResult(true, List.of());
        }

        public static ValidationResult fail() {
            return new ValidationResult(false, List.of());
        }

        public static ValidationResult withWarnings(List<String> warnings) {
            return new ValidationResult(true, List.copyOf(warnings));
        }
    }

    public WorkflowRegistry(WorkflowYamlParser parser) {
        this.parser = parser;
    }

    public void setStartupPhase(boolean startupPhase) {
        this.startupPhase = startupPhase;
        log.info("工作流注册中心启动阶段标记: startupPhase={}", startupPhase);
    }

    public boolean registerBuiltin(WorkflowDefinition definition, String resourceName) {
        return register(definition);
    }

    public void startScheduledScan() {
        if (zhiweiPaths == null || configProperties == null || taskScheduler == null) {
            log.warn("热加载启动失败: zhiweiPaths / configProperties / taskScheduler 未注入");
            return;
        }

        Path directory = zhiweiPaths.home("workflows");
        int intervalSeconds = configProperties.getScanIntervalSeconds();
        performScan(directory);
        taskScheduler.scheduleAtFixedRate(() -> performScan(directory), Duration.ofSeconds(intervalSeconds));
        log.info("工作流 YAML 热加载已启动: dir={}, interval={}s", directory, intervalSeconds);
    }

    public boolean register(WorkflowDefinition definition) {
        return register(definition, null);
    }

    /**
     * 注册工作流定义（带 YAML 原文），同时持久化到数据库。
     *
     * @param definition  工作流定义
     * @param yamlContent YAML 原文（可空，为空时通过 YamlPrinter 生成）
     * @return 注册是否成功
     */
    public boolean register(WorkflowDefinition definition, @Nullable String yamlContent) {
        ValidationResult validation = validate(definition);
        if (!validation.valid()) {
            return false;
        }

        if (!validation.warnings().isEmpty()) {
            log.info("工作流定义注册（含 {} 条资源警告）: id={}", validation.warnings().size(), definition.id());
        }

        boolean isUpdate = definitions.containsKey(definition.id());
        definitions.put(definition.id(), definition);

        // 同步持久化到数据库，确保 workflow_instances 外键约束可满足
        persistDefinition(definition, yamlContent);

        if (isUpdate) {
            log.info("工作流定义已更新: id={}, name={}", definition.id(), definition.name());
        } else {
            log.info("工作流定义注册成功: id={}, name={}", definition.id(), definition.name());
        }

        if (!startupPhase && definition.enabled() && triggerManager != null) {
            triggerManager.registerTriggers(definition);
        }
        return true;
    }

    /**
     * 将工作流定义持久化到数据库。
     * 优先使用传入的 YAML 原文，否则通过 YamlPrinter 生成。
     */
    private void persistDefinition(WorkflowDefinition definition, @Nullable String yamlContent) {
        if (repository == null) {
            return;
        }
        try {
            String yaml = yamlContent;
            if (yaml == null || yaml.isBlank()) {
                yaml = (yamlPrinter != null) ? yamlPrinter.print(definition) : "";
            }
            repository.saveDefinition(definition, yaml);
        } catch (Exception e) {
            log.warn("工作流定义持久化失败（不影响内存注册）: id={}, error={}", definition.id(), e.getMessage());
        }
    }

    public boolean unregister(String workflowId) {
        WorkflowDefinition removed = definitions.remove(workflowId);
        if (removed == null) {
            log.warn("工作流定义注销失败，未找到: id={}", workflowId);
            return false;
        }
        notifyTriggerUnregister(workflowId);

        // 同步软删除到数据库
        if (repository != null) {
            try {
                repository.markDefinitionDeleted(workflowId);
            } catch (Exception e) {
                log.warn("工作流定义软删除持久化失败: id={}, error={}", workflowId, e.getMessage());
            }
        }

        log.info("工作流定义已注销: id={}", workflowId);
        return true;
    }

    public boolean enable(String workflowId) {
        return updateEnabled(workflowId, true);
    }

    public boolean disable(String workflowId) {
        return updateEnabled(workflowId, false);
    }

    public Optional<WorkflowDefinition> find(String workflowId) {
        return Optional.ofNullable(definitions.get(workflowId));
    }

    public List<WorkflowDefinition> listAll() {
        return List.copyOf(definitions.values());
    }

    public List<WorkflowDefinition> listEnabled() {
        return definitions.values().stream()
                .filter(WorkflowDefinition::enabled)
                .toList();
    }

    /**
     * 按标签筛选工作流定义。
     *
     * @param tag 标签
     * @return 包含该标签的工作流定义列表
     */
    public List<WorkflowDefinition> findByTag(String tag) {
        return definitions.values().stream()
                .filter(def -> def.tags().contains(tag))
                .toList();
    }

    void performScan(Path directory) {
        if (!Files.isDirectory(directory)) {
            log.debug("工作流定义目录不存在，跳过扫描: path={}", directory);
            return;
        }

        Set<String> currentFiles = new HashSet<>();
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> yamlFiles = files
                    .filter(this::isYamlFile)
                    .sorted((left, right) -> {
                        try {
                            int compare = Files.getLastModifiedTime(left).compareTo(Files.getLastModifiedTime(right));
                            if (compare != 0) {
                                return compare;
                            }
                        } catch (IOException ignored) {
                            // fallback to path ordering
                        }
                        return left.toAbsolutePath().toString().compareTo(right.toAbsolutePath().toString());
                    })
                    .toList();
            logDuplicateStemWarnings(yamlFiles);
            yamlFiles.forEach(file -> scanFile(file, currentFiles));
        } catch (IOException e) {
            log.error("扫描工作流定义目录失败: path={}, error={}", directory, e.getMessage());
            return;
        }

        handleDeletedFiles(currentFiles);
    }

    public List<String> checkResourceAvailability(WorkflowDefinition definition) {
        List<String> missing = new ArrayList<>();
        for (WorkflowStep step : definition.steps()) {
            switch (step) {
                case WorkflowStep.ToolStep ts -> {
                    if (toolRegistry != null && toolRegistry.resolve(ts.toolId()).isEmpty()) {
                        missing.add("toolId=" + ts.toolId() + " (stepId=" + ts.id() + ")");
                    }
                }
                case WorkflowStep.SkillStep ss -> {
                    if (skillRegistry != null && skillRegistry.find(ss.skillId()).isEmpty()) {
                        missing.add("skillId=" + ss.skillId() + " (stepId=" + ss.id() + ")");
                    }
                }
                default -> {
                    // no-op
                }
            }
        }
        return List.copyOf(missing);
    }

    /**
     * @deprecated 使用 {@link ZhiweiPaths#home(String)} 替代，传入 "workflows"。
     */
    @Deprecated(forRemoval = true)
    public static Path resolveDefinitionsDir(String dir) {
        if (dir.startsWith("~")) {
            return Path.of(System.getProperty("user.home") + dir.substring(1));
        }
        return Path.of(dir);
    }

    private void scanFile(Path file, Set<String> currentFiles) {
        String filePath = file.toAbsolutePath().toString();
        currentFiles.add(filePath);
        try {
            Instant lastModified = Files.getLastModifiedTime(file).toInstant();
            Instant previousModified = fileLastModified.get(filePath);
            if (previousModified == null) {
                handleNewFile(file, filePath, lastModified);
            } else if (!lastModified.equals(previousModified)) {
                handleModifiedFile(file, filePath, lastModified);
            }
        } catch (IOException e) {
            log.warn("读取文件修改时间失败: file={}, error={}", file, e.getMessage());
        }
    }

    private void handleNewFile(Path file, String filePath, Instant lastModified) {
        try {
            String yaml = Files.readString(file);
            Result<WorkflowDefinition, List<String>> result = parser.parse(yaml);
            switch (result) {
                case Result.Ok<WorkflowDefinition, List<String>> ok -> {
                    if (register(ok.value(), yaml)) {
                        fileLastModified.put(filePath, lastModified);
                        fileToWorkflowId.put(filePath, ok.value().id());
                        log.info("热加载: 新增工作流文件: file={}, id={}", file.getFileName(), ok.value().id());
                    }
                }
                case Result.Err<WorkflowDefinition, List<String>> err -> {
                    log.warn("热加载: YAML 解析失败，跳过文件: file={}, errors={}", file.getFileName(), err.error());
                    fileLastModified.put(filePath, lastModified);
                }
            }
        } catch (IOException e) {
            log.warn("热加载: 读取文件失败: file={}, error={}", file.getFileName(), e.getMessage());
        }
    }

    private void handleModifiedFile(Path file, String filePath, Instant lastModified) {
        try {
            String yaml = Files.readString(file);
            Result<WorkflowDefinition, List<String>> result = parser.parse(yaml);
            switch (result) {
                case Result.Ok<WorkflowDefinition, List<String>> ok -> {
                    String previousId = fileToWorkflowId.get(filePath);
                    if (previousId != null && !previousId.equals(ok.value().id())) {
                        disable(previousId);
                    } else if (previousId != null) {
                        notifyTriggerUnregister(previousId);
                    }

                    if (register(ok.value(), yaml)) {
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
            log.warn("热加载: 读取文件失败: file={}, error={}", file.getFileName(), e.getMessage());
        }
    }

    private void handleDeletedFiles(Set<String> currentFiles) {
        Set<String> knownFiles = new HashSet<>(fileLastModified.keySet());
        for (String knownFile : knownFiles) {
            if (currentFiles.contains(knownFile)) {
                continue;
            }
            String workflowId = fileToWorkflowId.remove(knownFile);
            fileLastModified.remove(knownFile);
            if (workflowId != null) {
                disable(workflowId);
                log.info("热加载: 文件已删除，已禁用对应工作流: file={}, id={}", knownFile, workflowId);
            }
        }
    }

    private void notifyTriggerUnregister(String workflowId) {
        if (triggerManager != null) {
            triggerManager.unregisterTriggers(workflowId);
        }
    }

    private ValidationResult validate(WorkflowDefinition definition) {
        if (definition == null) {
            log.warn("工作流定义校验失败: definition 不能为空");
            return ValidationResult.fail();
        }
        if (definition.id() == null || definition.id().isBlank()) {
            log.warn("工作流定义校验失败: id 不能为空");
            return ValidationResult.fail();
        }
        if (definition.name() == null || definition.name().isBlank()) {
            log.warn("工作流定义校验失败: name 不能为空");
            return ValidationResult.fail();
        }
        if (definition.steps() == null || definition.steps().isEmpty()) {
            log.warn("工作流定义校验失败: steps 不能为空, id={}", definition.id());
            return ValidationResult.fail();
        }

        if (dagScheduler != null) {
            try {
                dagScheduler.buildExecutionPlan(definition.steps());
            } catch (IllegalArgumentException e) {
                log.warn("工作流定义校验失败: 步骤存在环依赖, id={}, error={}", definition.id(), e.getMessage());
                return ValidationResult.fail();
            }
        }

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
                default -> {
                    // no-op
                }
            }
        }
        return warnings.isEmpty() ? ValidationResult.ok() : ValidationResult.withWarnings(warnings);
    }

    private boolean updateEnabled(String workflowId, boolean enabled) {
        WorkflowDefinition existing = definitions.get(workflowId);
        if (existing == null) {
            log.warn("工作流{}失败，未找到: id={}", enabled ? "启用" : "禁用", workflowId);
            return false;
        }

        WorkflowDefinition updated = existing.toBuilder().enabled(enabled).build();
        definitions.put(workflowId, updated);

        // 同步启用状态到数据库
        if (repository != null) {
            try {
                repository.updateDefinitionEnabled(workflowId, enabled);
            } catch (Exception e) {
                log.warn("工作流启用状态持久化失败: id={}, error={}", workflowId, e.getMessage());
            }
        }

        if (triggerManager != null) {
            if (enabled) {
                triggerManager.registerTriggers(updated);
            } else {
                triggerManager.unregisterTriggers(workflowId);
            }
        }

        log.info("工作流已{}: id={}", enabled ? "启用" : "禁用", workflowId);
        return true;
    }

    private boolean isYamlFile(Path path) {
        String fileName = path.getFileName().toString().toLowerCase();
        return Files.isRegularFile(path)
                && (fileName.endsWith(".yml") || fileName.endsWith(".yaml"));
    }

    private static String fileStem(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }

    private synchronized void logDuplicateStemWarnings(List<Path> yamlFiles) {
        Map<String, List<String>> grouped = new TreeMap<>();
        for (Path yamlFile : yamlFiles) {
            grouped.compute(fileStem(yamlFile.getFileName().toString()), (ignored, existing) -> {
                List<String> files = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
                files.add(yamlFile.getFileName().toString());
                return files;
            });
        }

        Map<String, List<String>> duplicates = new TreeMap<>();
        grouped.forEach((stem, files) -> {
            if (files.size() > 1) {
                duplicates.put(stem, files.stream().sorted(Comparator.naturalOrder()).toList());
            }
        });

        String signature = duplicates.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + String.join(",", entry.getValue()))
                .reduce((left, right) -> left + "|" + right)
                .orElse("");
        if (signature.equals(duplicateStemSignature)) {
            return;
        }

        duplicateStemSignature = signature;
        duplicates.forEach((stem, files) -> log.warn(
                "热加载: 检测到重复的本地工作流文件名 stem='{}'，将按最后修改时间顺序依次加载，本地最新文件会生效: files={}",
                stem,
                files));
    }
}
