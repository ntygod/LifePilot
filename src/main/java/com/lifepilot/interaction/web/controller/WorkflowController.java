package com.lifepilot.interaction.web.controller;

import com.lifepilot.interaction.web.model.ErrorResponse;
import com.lifepilot.interaction.web.model.TriggerWorkflowRequest;
import com.lifepilot.interaction.web.model.WorkflowDetailDto;
import com.lifepilot.interaction.web.model.WorkflowItemDto;
import com.lifepilot.workflow.config.WorkflowConfigProperties;
import com.lifepilot.workflow.engine.InputValidationResult;
import com.lifepilot.workflow.engine.InputValidator;
import com.lifepilot.workflow.engine.WorkflowCommandService;
import com.lifepilot.workflow.engine.WorkflowEngine;
import com.lifepilot.workflow.engine.WorkflowEventRecorder;
import com.lifepilot.workflow.model.ApprovalDecision;
import com.lifepilot.workflow.model.Result;
import com.lifepilot.workflow.model.WorkflowDefinition;
import com.lifepilot.workflow.parser.WorkflowYamlParser;
import com.lifepilot.workflow.parser.WorkflowYamlPrinter;
import com.lifepilot.workflow.registry.WorkflowRegistry;
import com.lifepilot.workflow.repository.WorkflowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 工作流管理 REST Controller。
 *
 * <p>提供工作流列表/详情/启用/禁用/手动触发/执行历史端点。</p>
 *
 * @author zsg
 * @since 2026-02-27
 */
@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private static final Logger log = LoggerFactory.getLogger(WorkflowController.class);

    private final WorkflowRegistry workflowRegistry;
    private final WorkflowEngine workflowEngine;
    private final WorkflowCommandService workflowCommandService;
    private final WorkflowRepository workflowRepository;
    private final WorkflowEventRecorder workflowEventRecorder;
    private final WorkflowYamlParser yamlParser;
    private final WorkflowYamlPrinter yamlPrinter;
    private final WorkflowConfigProperties workflowConfig;
    private final Path workflowsDirectory;

    public WorkflowController(WorkflowRegistry workflowRegistry,
                               WorkflowEngine workflowEngine,
                               WorkflowCommandService workflowCommandService,
                               WorkflowRepository workflowRepository,
                               WorkflowEventRecorder workflowEventRecorder,
                               WorkflowYamlParser yamlParser,
                               WorkflowYamlPrinter yamlPrinter,
                               WorkflowConfigProperties workflowConfig) {
        this.workflowRegistry = workflowRegistry;
        this.workflowEngine = workflowEngine;
        this.workflowCommandService = workflowCommandService;
        this.workflowRepository = workflowRepository;
        this.workflowEventRecorder = workflowEventRecorder;
        this.yamlParser = yamlParser;
        this.yamlPrinter = yamlPrinter;
        this.workflowConfig = workflowConfig;
        String dir = workflowConfig.getDefinitionsDir();
        // 处理 ~ 符号
        if (dir.startsWith("~")) {
            dir = System.getProperty("user.home") + dir.substring(1);
        }
        this.workflowsDirectory = Path.of(dir);
    }

    /**
     * 创建 Workflow。
     *
     * @param request 创建请求（包含 yamlContent）
     * @return 201 创建成功，400 参数错误
     */
    @PostMapping
    public ResponseEntity<?> createWorkflow(@RequestBody Map<String, Object> request) {
        log.debug("创建 Workflow: request={}", request);
        
        try {
            // 获取 YAML 内容
            String yamlContent = getString(request, "yamlContent");
            if (yamlContent == null || yamlContent.isBlank()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(new ErrorResponse(400, "yamlContent 不能为空", Instant.now()));
            }

            // 解析 YAML
            Result<WorkflowDefinition, List<String>> parseResult = yamlParser.parse(yamlContent);
            if (parseResult instanceof Result.Err<WorkflowDefinition, List<String>> err) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(new ErrorResponse(400, "YAML 解析失败: " + String.join(", ", err.error()), Instant.now()));
            }

            WorkflowDefinition definition = ((Result.Ok<WorkflowDefinition, List<String>>) parseResult).value();

            // 检查是否已存在
            if (workflowRegistry.find(definition.id()).isPresent()) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(new ErrorResponse(409, "Workflow ID 已存在: " + definition.id(), Instant.now()));
            }

            // 保存到文件系统
            Path workflowFile = workflowsDirectory.resolve(definition.id() + ".yaml");
            if (!Files.exists(workflowsDirectory)) {
                Files.createDirectories(workflowsDirectory);
            }
            Files.writeString(workflowFile, yamlContent);

            // 注册到 WorkflowRegistry
            boolean registered = workflowRegistry.register(definition);
            if (!registered) {
                // 如果注册失败，删除文件
                Files.deleteIfExists(workflowFile);
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(new ErrorResponse(400, "Workflow 注册失败，请检查定义", Instant.now()));
            }

            log.info("Workflow 创建成功: id={}, name={}", definition.id(), definition.name());
            return ResponseEntity.status(HttpStatus.CREATED).body(WorkflowDetailDto.from(definition));
        } catch (Exception e) {
            log.error("创建 Workflow 失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse(500, "创建失败: " + e.getMessage(), Instant.now()));
        }
    }

    /**
     * 更新 Workflow。
     *
     * @param id Workflow ID
     * @param request 更新请求（包含 yamlContent）
     * @return 200 更新成功，404 不存在，400 参数错误
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateWorkflow(@PathVariable String id,
                                            @RequestBody Map<String, Object> request) {
        log.debug("更新 Workflow: id={}, request={}", id, request);
        
        // 检查 Workflow 是否存在
        var existingOpt = workflowRegistry.find(id);
        if (existingOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse(404, "Workflow 不存在: id=" + id, Instant.now()));
        }

        try {
            // 获取 YAML 内容
            String yamlContent = getString(request, "yamlContent");
            if (yamlContent == null || yamlContent.isBlank()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(new ErrorResponse(400, "yamlContent 不能为空", Instant.now()));
            }

            // 解析 YAML
            Result<WorkflowDefinition, List<String>> parseResult = yamlParser.parse(yamlContent);
            if (parseResult instanceof Result.Err<WorkflowDefinition, List<String>> err) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(new ErrorResponse(400, "YAML 解析失败: " + String.join(", ", err.error()), Instant.now()));
            }

            WorkflowDefinition definition = ((Result.Ok<WorkflowDefinition, List<String>>) parseResult).value();

            // 检查 ID 是否匹配
            if (!definition.id().equals(id)) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(new ErrorResponse(400, "YAML 中的 ID 必须与路径参数一致", Instant.now()));
            }

            // 更新文件系统
            Path workflowFile = workflowsDirectory.resolve(id + ".yaml");
            Files.writeString(workflowFile, yamlContent);

            // 更新注册表
            boolean registered = workflowRegistry.register(definition);
            if (!registered) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(new ErrorResponse(400, "Workflow 更新失败，请检查定义", Instant.now()));
            }

            log.info("Workflow 更新成功: id={}", id);
            return ResponseEntity.ok(WorkflowDetailDto.from(definition));
        } catch (Exception e) {
            log.error("更新 Workflow 失败: id={}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse(500, "更新失败: " + e.getMessage(), Instant.now()));
        }
    }

    /**
     * 删除 Workflow（删除 YAML 文件 + 从注册表移除）。
     *
     * <p>工作流定义的权威来源为文件系统中的 YAML 文件，删除操作仅删除文件并从注册表注销。
     * 已有的执行实例历史（workflow_instances）不受影响。</p>
     *
     * @param id Workflow ID
     * @return 204 成功，404 不存在
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteWorkflow(@PathVariable String id) {
        log.debug("删除 Workflow: id={}", id);

        // 仅允许删除已存在的工作流
        if (workflowRegistry.find(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse(404, "Workflow 不存在: id=" + id, Instant.now()));
        }

        try {
            // 删除文件系统中的 YAML（兼容 .yaml/.yml）
            if (!Files.exists(workflowsDirectory)) {
                log.debug("workflowsDirectory 不存在，跳过删除文件: dir={}", workflowsDirectory);
            } else {
                Files.deleteIfExists(workflowsDirectory.resolve(id + ".yaml"));
                Files.deleteIfExists(workflowsDirectory.resolve(id + ".yml"));
            }

            // 从注册表移除（并注销触发器）
            workflowRegistry.unregister(id);

            log.info("Workflow 删除成功: id={}", id);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            log.error("删除 Workflow 失败: id={}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse(500, "删除失败: " + e.getMessage(), Instant.now()));
        }
    }

    /**
     * 获取所有工作流定义列表。
     *
     * @return 工作流定义列表
     */
    @GetMapping
    public ResponseEntity<?> listWorkflows() {
        log.debug("查询工作流列表");
        var dtos = workflowRegistry.listAll().stream()
                .map(WorkflowItemDto::from)
                .toList();
        return ResponseEntity.ok(dtos);
    }

    /**
     * 获取指定工作流定义详情。
     *
     * @param id 工作流 ID
     * @return 工作流定义，不存在返回 404
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getWorkflow(@PathVariable String id) {
        return workflowRegistry.find(id)
                .map(WorkflowDetailDto::from)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                        new ErrorResponse(404, "工作流不存在: id=" + id, Instant.now())));
    }

    /**
     * 获取指定工作流的原始 YAML 内容（用于前端 YAML 编辑）。
     *
     * @param id 工作流 ID
     * @return 200 返回 { yamlContent }，404 不存在
     */
    @GetMapping("/{id}/yaml")
    public ResponseEntity<?> getWorkflowYaml(@PathVariable String id) {
        // 验证工作流存在（避免读取任意文件）
        if (workflowRegistry.find(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    new ErrorResponse(404, "工作流不存在: id=" + id, Instant.now()));
        }

        try {
            Path yamlFile = workflowsDirectory.resolve(id + ".yaml");
            Path ymlFile = workflowsDirectory.resolve(id + ".yml");

            String yamlContent = null;
            if (Files.exists(yamlFile)) {
                yamlContent = Files.readString(yamlFile);
            } else if (Files.exists(ymlFile)) {
                yamlContent = Files.readString(ymlFile);
            }

            if (yamlContent == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                        new ErrorResponse(404, "YAML 文件不存在: id=" + id, Instant.now()));
            }

            return ResponseEntity.ok(Map.of("yamlContent", yamlContent));
        } catch (Exception e) {
            log.error("读取 Workflow YAML 失败: id={}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse(500, "读取失败: " + e.getMessage(), Instant.now()));
        }
    }

    /**
     * 启用指定工作流。
     *
     * @param id 工作流 ID
     * @return 204 成功，404 不存在
     */
    @PostMapping("/{id}/enable")
    public ResponseEntity<?> enableWorkflow(@PathVariable String id) {
        if (!workflowRegistry.enable(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    new ErrorResponse(404, "工作流不存在: id=" + id, Instant.now()));
        }
        log.info("工作流已启用: id={}", id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 禁用指定工作流。
     *
     * @param id 工作流 ID
     * @return 204 成功，404 不存在
     */
    @PostMapping("/{id}/disable")
    public ResponseEntity<?> disableWorkflow(@PathVariable String id) {
        if (!workflowRegistry.disable(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    new ErrorResponse(404, "工作流不存在: id=" + id, Instant.now()));
        }
        log.info("工作流已禁用: id={}", id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 手动触发工作流执行。
     *
     * <p>禁用的工作流不可触发，返回 400。
     *
     * @param id      工作流 ID
     * @param request 触发请求（包含可选输入参数）
     * @return 工作流实例，404 不存在，400 已禁用
     */
    @PostMapping("/{id}/trigger")
    public ResponseEntity<?> triggerWorkflow(@PathVariable String id,
                                              @RequestBody(required = false) TriggerWorkflowRequest request) {
        var defOpt = workflowRegistry.find(id);
        if (defOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    new ErrorResponse(404, "工作流不存在: id=" + id, Instant.now()));
        }

        if (!defOpt.get().enabled()) {
            log.warn("尝试触发禁用工作流被拒绝: id={}", id);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    new ErrorResponse(400, "工作流已禁用，不可触发: id=" + id, Instant.now()));
        }

        Map<String, Object> inputs = (request != null && request.inputs() != null)
                ? request.inputs() : Map.of();

        // 输入参数校验
        InputValidationResult validation = InputValidator.validate(defOpt.get().inputs(), inputs);
        if (!validation.valid()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    new ErrorResponse(400,
                            "缺少必填输入参数: " + String.join(", ", validation.missingParams()),
                            Instant.now()));
        }

        String instanceId = workflowCommandService.start(id, validation.mergedInputs());
        log.info("工作流触发成功: workflowId={}, instanceId={}", id, instanceId);
        return ResponseEntity.accepted().body(Map.of(
                "instanceId", instanceId,
                "workflowId", id,
                "message", "工作流已提交异步执行"
        ));
    }

    /**
     * 获取指定工作流的执行历史。
     *
     * @param id 工作流 ID
     * @return 执行实例列表（按创建时间倒序）
     */
    @GetMapping("/{id}/executions")
    public ResponseEntity<?> getWorkflowExecutions(@PathVariable String id) {
        // 验证工作流存在
        if (workflowRegistry.find(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    new ErrorResponse(404, "工作流不存在: id=" + id, Instant.now()));
        }
        return ResponseEntity.ok(workflowRepository.findInstancesByWorkflowId(id));
    }

    // ── 执行实例端点 ──────────────────────────────────────

    /**
     * 获取单个工作流执行实例详情。
     *
     * @param instanceId 实例 ID
     * @return 实例详情，不存在返回 404
     */
    @GetMapping("/executions/{instanceId}")
    public ResponseEntity<?> getInstance(@PathVariable String instanceId) {
        return workflowRepository.findInstance(instanceId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                        new ErrorResponse(404, "工作流实例未找到: id=" + instanceId, Instant.now())));
    }

    /**
     * 提交审批决策。
     *
     * @param instanceId 实例 ID
     * @param stepId     审批步骤 ID
     * @param request    审批请求
     * @return 更新后的实例，404 不存在，400 状态/步骤不匹配
     */
    @PostMapping("/executions/{instanceId}/steps/{stepId}/approve")
    public ResponseEntity<?> approveStep(@PathVariable String instanceId,
                                         @PathVariable String stepId,
                                         @RequestBody ApproveRequest request) {
        try {
            var decision = new ApprovalDecision(
                    ApprovalDecision.Decision.valueOf(request.decision()),
                    request.decidedBy(),
                    request.reason(),
                    Instant.now()
            );
            var updated = workflowEngine.approve(instanceId, stepId, decision);
            log.info("审批操作完成: instanceId={}, stepId={}, decision={}", instanceId, stepId, request.decision());
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse(404, e.getMessage(), Instant.now()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse(400, e.getMessage(), Instant.now()));
        } catch (Exception e) {
            log.error("审批操作失败: instanceId={}, stepId={}", instanceId, stepId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse(500, "审批操作失败: " + e.getMessage(), Instant.now()));
        }
    }

    /**
     * 获取实例事件时间线。
     *
     * @param instanceId 实例 ID
     * @return 事件列表（按 createdAt 升序），不存在返回 404
     */
    @GetMapping("/executions/{instanceId}/events")
    public ResponseEntity<?> getEventTimeline(@PathVariable String instanceId) {
        if (workflowRepository.findInstance(instanceId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse(404, "工作流实例未找到: id=" + instanceId, Instant.now()));
        }
        return ResponseEntity.ok(workflowEventRecorder.getTimeline(instanceId));
    }

    /**
     * 获取实例步骤执行日志。
     *
     * @param instanceId 实例 ID
     * @return 步骤日志列表（按 createdAt 升序），不存在返回 404
     */
    @GetMapping("/executions/{instanceId}/step-logs")
    public ResponseEntity<?> getStepLogs(@PathVariable String instanceId) {
        if (workflowRepository.findInstance(instanceId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse(404, "工作流实例未找到: id=" + instanceId, Instant.now()));
        }
        return ResponseEntity.ok(workflowRepository.findStepLogsSummary(instanceId));
    }

    // ── 辅助方法 ──────────────────────────────────────────

    /** 审批请求 DTO。 */
    record ApproveRequest(String decision, String decidedBy, @Nullable String reason) {}

    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }
}
