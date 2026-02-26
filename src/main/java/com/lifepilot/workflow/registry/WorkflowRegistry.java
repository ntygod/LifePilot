package com.lifepilot.workflow.registry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lifepilot.workflow.model.Result;
import com.lifepilot.workflow.model.WorkflowDefinition;
import com.lifepilot.workflow.parser.WorkflowYamlParser;
import com.lifepilot.workflow.parser.WorkflowYamlPrinter;
import com.lifepilot.workflow.repository.WorkflowRepository;

/**
 * 工作流注册中心 — 管理 WorkflowDefinition 的注册、查询、启用/禁用。
 *
 * <p>使用 {@link ConcurrentHashMap} 存储工作流定义，保证线程安全的并发访问。
 * 启动时从数据库加载所有已有定义，运行时支持动态注册/注销。
 *
 * <p>注册时执行基本验证（必填字段、步骤非空），无效定义被拒绝并返回 {@code false}。
 * 重复 ID 注册时更新已有定义。禁用的工作流阻止新实例创建，但不影响运行中实例。
 *
 * @author zsg
 * @since 2026-02-26
 */
public class WorkflowRegistry {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRegistry.class);

    private final ConcurrentHashMap<String, WorkflowDefinition> definitions = new ConcurrentHashMap<>();
    private final WorkflowRepository repository;
    private final WorkflowYamlParser parser;
    private final WorkflowYamlPrinter printer;

    /**
     * 构造 WorkflowRegistry，注入持久化仓储、YAML 解析器和打印器。
     *
     * <p>构造完成后自动从数据库加载所有已有工作流定义到内存。
     *
     * @param repository 工作流持久化仓储
     * @param parser     YAML 解析器
     * @param printer    YAML 打印器
     */
    public WorkflowRegistry(WorkflowRepository repository,
                             WorkflowYamlParser parser,
                             WorkflowYamlPrinter printer) {
        this.repository = repository;
        this.parser = parser;
        this.printer = printer;
        loadFromDatabase();
    }

    /**
     * 注册工作流定义。
     *
     * <p>注册前执行验证：id 和 name 不能为空/空白，steps 不能为空。
     * 验证失败时返回 {@code false} 并记录 WARN 日志。
     *
     * <p>如果已存在相同 ID 的定义，则更新已有定义并记录 INFO 日志。
     * 注册成功后同时持久化到数据库。
     *
     * @param definition 工作流定义
     * @return 注册成功返回 {@code true}，验证失败返回 {@code false}
     */
    public boolean register(WorkflowDefinition definition) {
        // 验证必填字段
        if (!validate(definition)) {
            return false;
        }

        // 检查是否为更新
        boolean isUpdate = definitions.containsKey(definition.id());
        definitions.put(definition.id(), definition);

        if (isUpdate) {
            log.info("工作流定义更新: id={}, name={}", definition.id(), definition.name());
        } else {
            log.info("工作流定义注册成功: id={}, name={}", definition.id(), definition.name());
        }

        // 持久化到数据库
        try {
            String yamlContent = printer.print(definition);
            repository.saveDefinition(definition, yamlContent);
        } catch (Exception e) {
            log.warn("工作流定义持久化失败: id={}, 原因={}", definition.id(), e.getMessage());
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
            log.info("工作流定义注销: id={}", workflowId);
            return true;
        }
        log.warn("工作流定义注销失败，未找到: id={}", workflowId);
        return false;
    }

    /**
     * 启用工作流定义。
     *
     * <p>同时更新内存和数据库中的启用状态。
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
     * 同时更新内存和数据库中的启用状态。
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

    /**
     * 扫描目录中的 YAML 文件并注册有效的工作流定义。
     *
     * <p>扫描 {@code *.yml} 和 {@code *.yaml} 文件，解析成功的注册到注册中心，
     * 解析失败的记录 WARN 日志并跳过。
     *
     * @param directory 工作流 YAML 文件目录
     */
    public void scanAndRegister(Path directory) {
        if (!Files.isDirectory(directory)) {
            log.warn("工作流定义目录不存在: path={}", directory);
            return;
        }

        try (Stream<Path> files = Files.list(directory)) {
            files.filter(this::isYamlFile)
                    .forEach(this::loadAndRegister);
        } catch (IOException e) {
            log.error("扫描工作流定义目录失败: path={}, 原因={}", directory, e.getMessage());
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 从数据库加载所有工作流定义到内存。
     */
    private void loadFromDatabase() {
        try {
            List<WorkflowDefinition> loaded = repository.findAllDefinitions();
            for (WorkflowDefinition def : loaded) {
                definitions.put(def.id(), def);
            }
            log.info("从数据库加载工作流定义: count={}", loaded.size());
        } catch (Exception e) {
            log.warn("从数据库加载工作流定义失败: 原因={}", e.getMessage());
        }
    }

    /**
     * 验证工作流定义的必填字段。
     *
     * @param definition 工作流定义
     * @return 验证通过返回 {@code true}，否则返回 {@code false}
     */
    private boolean validate(WorkflowDefinition definition) {
        if (definition == null) {
            log.warn("工作流定义验证失败: 定义为 null");
            return false;
        }
        if (definition.id() == null || definition.id().isBlank()) {
            log.warn("工作流定义验证失败: id 为空");
            return false;
        }
        if (definition.name() == null || definition.name().isBlank()) {
            log.warn("工作流定义验证失败: name 为空");
            return false;
        }
        if (definition.steps() == null || definition.steps().isEmpty()) {
            log.warn("工作流定义验证失败: steps 为空, id={}", definition.id());
            return false;
        }
        return true;
    }

    /**
     * 更新工作流定义的启用状态（内存 + 数据库）。
     */
    private boolean updateEnabled(String workflowId, boolean enabled) {
        WorkflowDefinition existing = definitions.get(workflowId);
        if (existing == null) {
            log.warn("工作流定义{}失败，未找到: id={}", enabled ? "启用" : "禁用", workflowId);
            return false;
        }

        WorkflowDefinition updated = existing.toBuilder().enabled(enabled).build();
        definitions.put(workflowId, updated);

        try {
            repository.updateDefinitionEnabled(workflowId, enabled);
        } catch (Exception e) {
            log.warn("工作流定义{}持久化失败: id={}, 原因={}", enabled ? "启用" : "禁用", workflowId, e.getMessage());
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

    /**
     * 加载并注册单个 YAML 文件。
     */
    private void loadAndRegister(Path file) {
        try {
            String yaml = Files.readString(file);
            Result<WorkflowDefinition, List<String>> result = parser.parse(yaml);
            switch (result) {
                case Result.Ok<WorkflowDefinition, List<String>> ok -> register(ok.value());
                case Result.Err<WorkflowDefinition, List<String>> err ->
                        log.warn("工作流 YAML 解析失败: file={}, errors={}", file, err.error());
            }
        } catch (IOException e) {
            log.warn("读取工作流 YAML 文件失败: file={}, 原因={}", file, e.getMessage());
        }
    }
}
