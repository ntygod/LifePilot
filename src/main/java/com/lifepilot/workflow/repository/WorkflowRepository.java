package com.lifepilot.workflow.repository;

import com.lifepilot.workflow.model.Result;
import com.lifepilot.workflow.model.StepLog;
import com.lifepilot.workflow.model.StepState;
import com.lifepilot.workflow.model.WorkflowContext;
import com.lifepilot.workflow.model.WorkflowDefinition;
import com.lifepilot.workflow.model.WorkflowInstance;
import com.lifepilot.workflow.model.WorkflowState;
import com.lifepilot.workflow.parser.WorkflowYamlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 工作流持久化仓储 — 基于 JdbcTemplate 操作 SQLite 工作流相关表。
 *
 * <p>管理 {@code workflow_definitions}、{@code workflow_instances}、
 * {@code workflow_step_logs} 三张表的 CRUD 操作。
 *
 * <p>时间字段以 ISO 8601 TEXT 存储，枚举以 {@code .name()} TEXT 存储，
 * {@link WorkflowContext} 通过 {@code toJson()} / {@code fromJson()} 序列化。
 *
 * @author zsg
 * @since 2026-02-26
 */
public class WorkflowRepository {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final WorkflowYamlParser yamlParser;
    private final RowMapper<WorkflowInstance> instanceRowMapper;
    private final RowMapper<StepLog> stepLogRowMapper;

    public WorkflowRepository(JdbcTemplate jdbcTemplate, WorkflowYamlParser yamlParser) {
        this.jdbcTemplate = jdbcTemplate;
        this.yamlParser = yamlParser;
        this.instanceRowMapper = this::mapInstance;
        this.stepLogRowMapper = this::mapStepLog;
    }

    // ==================== WorkflowDefinition CRUD ====================

    /**
     * 保存工作流定义。
     *
     * @param def         工作流定义
     * @param yamlContent YAML 原始内容
     */
    public void saveDefinition(WorkflowDefinition def, String yamlContent) {
        String now = Instant.now().toString();
        jdbcTemplate.update("""
                INSERT INTO workflow_definitions (id, name, description, version, enabled, definition_yaml, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    name = excluded.name,
                    description = excluded.description,
                    version = excluded.version,
                    enabled = excluded.enabled,
                    definition_yaml = excluded.definition_yaml,
                    updated_at = excluded.updated_at
                """,
                def.id(), def.name(), def.description(), def.version(),
                def.enabled() ? 1 : 0, yamlContent, now, now);
        log.info("工作流定义保存成功: id={}, name={}", def.id(), def.name());
    }

    /**
     * 根据 ID 查找工作流定义。
     *
     * <p>从数据库读取 {@code definition_yaml} 列，通过 {@link WorkflowYamlParser} 解析为
     * {@link WorkflowDefinition}。解析失败时返回 empty 并记录 WARN 日志。
     *
     * @param id 工作流定义 ID
     * @return 工作流定义 Optional，不存在或解析失败时返回 empty
     */
    public Optional<WorkflowDefinition> findDefinition(String id) {
        List<WorkflowDefinition> results = jdbcTemplate.query(
                "SELECT * FROM workflow_definitions WHERE id = ?",
                (rs, rowNum) -> mapDefinition(rs.getString("definition_yaml"),
                        rs.getInt("enabled") == 1),
                id);
        return results.stream().findFirst();
    }

    /**
     * 查询所有工作流定义。
     *
     * @return 所有工作流定义列表（解析失败的定义会被跳过）
     */
    public List<WorkflowDefinition> findAllDefinitions() {
        return jdbcTemplate.query(
                "SELECT * FROM workflow_definitions",
                (rs, rowNum) -> mapDefinition(rs.getString("definition_yaml"),
                        rs.getInt("enabled") == 1))
                .stream()
                .toList();
    }

    /**
     * 更新工作流定义的启用状态。
     *
     * @param id      工作流定义 ID
     * @param enabled 是否启用
     */
    public void updateDefinitionEnabled(String id, boolean enabled) {
        String now = Instant.now().toString();
        jdbcTemplate.update(
                "UPDATE workflow_definitions SET enabled = ?, updated_at = ? WHERE id = ?",
                enabled ? 1 : 0, now, id);
        log.info("工作流定义启用状态更新: id={}, enabled={}", id, enabled);
    }

    // ==================== WorkflowInstance CRUD ====================

    /**
     * 保存工作流实例。
     *
     * @param instance 工作流实例
     */
    public void saveInstance(WorkflowInstance instance) {
        jdbcTemplate.update("""
                INSERT INTO workflow_instances
                    (id, workflow_id, state, input_json, context_json, current_step_index,
                     started_at, completed_at, failure_reason, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                instance.id(),
                instance.workflowId(),
                instance.state().name(),
                instance.context() != null ? instance.context().toJson() : null,
                instance.context() != null ? instance.context().toJson() : null,
                instance.currentStepIndex(),
                toText(instance.startedAt()),
                toText(instance.completedAt()),
                instance.failureReason(),
                toText(instance.createdAt()),
                toText(instance.updatedAt()));
        log.info("工作流实例保存成功: id={}, workflowId={}, state={}",
                instance.id(), instance.workflowId(), instance.state());
    }

    /**
     * 更新工作流实例（状态、上下文、步骤索引等）。
     *
     * @param instance 更新后的工作流实例
     */
    public void updateInstance(WorkflowInstance instance) {
        jdbcTemplate.update("""
                UPDATE workflow_instances SET
                    state = ?, context_json = ?, current_step_index = ?,
                    started_at = ?, completed_at = ?, failure_reason = ?, updated_at = ?
                WHERE id = ?
                """,
                instance.state().name(),
                instance.context() != null ? instance.context().toJson() : null,
                instance.currentStepIndex(),
                toText(instance.startedAt()),
                toText(instance.completedAt()),
                instance.failureReason(),
                toText(instance.updatedAt()),
                instance.id());
        log.debug("工作流实例更新: id={}, state={}, stepIndex={}",
                instance.id(), instance.state(), instance.currentStepIndex());
    }

    /**
     * 根据 ID 查找工作流实例。
     *
     * @param id 实例 ID
     * @return 工作流实例 Optional，不存在时返回 empty
     */
    public Optional<WorkflowInstance> findInstance(String id) {
        List<WorkflowInstance> results = jdbcTemplate.query(
                "SELECT * FROM workflow_instances WHERE id = ?",
                instanceRowMapper, id);
        return results.stream().findFirst();
    }

    /**
     * 按状态查询工作流实例。
     *
     * @param states 要查询的状态（varargs）
     * @return 匹配状态的实例列表
     */
    public List<WorkflowInstance> findInstancesByState(WorkflowState... states) {
        if (states == null || states.length == 0) {
            return List.of();
        }
        String placeholders = Arrays.stream(states)
                .map(s -> "?")
                .collect(Collectors.joining(", "));
        String sql = "SELECT * FROM workflow_instances WHERE state IN (" + placeholders + ")";
        Object[] params = Arrays.stream(states)
                .map(WorkflowState::name)
                .toArray();
        return jdbcTemplate.query(sql, instanceRowMapper, params);
    }

    // ==================== StepLog ====================

    /**
     * 插入步骤执行日志。
     *
     * @param stepLog 步骤日志
     */
    public void insertStepLog(StepLog stepLog) {
        jdbcTemplate.update("""
                INSERT INTO workflow_step_logs
                    (id, instance_id, step_id, step_type, state, attempt,
                     input_json, output_json, error_message,
                     started_at, completed_at, duration_ms, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                stepLog.id(),
                stepLog.instanceId(),
                stepLog.stepId(),
                stepLog.stepType(),
                stepLog.state().name(),
                stepLog.attempt(),
                stepLog.inputJson(),
                stepLog.outputJson(),
                stepLog.errorMessage(),
                toText(stepLog.startedAt()),
                toText(stepLog.completedAt()),
                stepLog.durationMs(),
                toText(stepLog.createdAt()));
        log.debug("步骤日志插入: instanceId={}, stepId={}, state={}, attempt={}",
                stepLog.instanceId(), stepLog.stepId(), stepLog.state(), stepLog.attempt());
    }

    /**
     * 查询指定实例的所有步骤日志，按创建时间升序排列。
     *
     * @param instanceId 工作流实例 ID
     * @return 步骤日志列表
     */
    public List<StepLog> findStepLogs(String instanceId) {
        return jdbcTemplate.query(
                "SELECT * FROM workflow_step_logs WHERE instance_id = ? ORDER BY created_at ASC",
                stepLogRowMapper, instanceId);
    }

    // ==================== 内部方法 ====================

    /**
     * 将 YAML 内容解析为 WorkflowDefinition，并覆盖 enabled 状态。
     * 解析失败时返回 null（由调用方过滤）。
     */
    @Nullable
    private WorkflowDefinition mapDefinition(String yamlContent, boolean enabled) {
        Result<WorkflowDefinition, List<String>> result = yamlParser.parse(yamlContent);
        return switch (result) {
            case Result.Ok<WorkflowDefinition, List<String>> ok ->
                    ok.value().toBuilder().enabled(enabled).build();
            case Result.Err<WorkflowDefinition, List<String>> err -> {
                log.warn("工作流定义 YAML 解析失败: {}", err.error());
                yield null;
            }
        };
    }

    /** 将 ResultSet 行映射为 WorkflowInstance record。 */
    private WorkflowInstance mapInstance(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        String contextJson = rs.getString("context_json");
        WorkflowContext context = (contextJson != null && !contextJson.isBlank())
                ? WorkflowContext.fromJson(contextJson)
                : new WorkflowContext();

        return WorkflowInstance.builder()
                .id(rs.getString("id"))
                .workflowId(rs.getString("workflow_id"))
                .state(WorkflowState.valueOf(rs.getString("state")))
                .context(context)
                .currentStepIndex(rs.getInt("current_step_index"))
                .startedAt(parseInstant(rs.getString("started_at")))
                .completedAt(parseInstant(rs.getString("completed_at")))
                .failureReason(rs.getString("failure_reason"))
                .createdAt(parseInstant(rs.getString("created_at")))
                .updatedAt(parseInstant(rs.getString("updated_at")))
                .build();
    }

    /** 将 ResultSet 行映射为 StepLog record。 */
    private StepLog mapStepLog(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        Long durationMs = rs.getObject("duration_ms") != null
                ? rs.getLong("duration_ms") : null;
        return new StepLog(
                rs.getString("id"),
                rs.getString("instance_id"),
                rs.getString("step_id"),
                rs.getString("step_type"),
                StepState.valueOf(rs.getString("state")),
                rs.getInt("attempt"),
                rs.getString("input_json"),
                rs.getString("output_json"),
                rs.getString("error_message"),
                parseInstant(rs.getString("started_at")),
                parseInstant(rs.getString("completed_at")),
                durationMs,
                parseInstant(rs.getString("created_at"))
        );
    }

    /** ISO 8601 TEXT → Instant，null 安全。 */
    @Nullable
    private static Instant parseInstant(@Nullable String text) {
        return (text != null && !text.isBlank()) ? Instant.parse(text) : null;
    }

    /** Instant → ISO 8601 TEXT，null 安全。 */
    @Nullable
    private static String toText(@Nullable Instant instant) {
        return instant != null ? instant.toString() : null;
    }
}
