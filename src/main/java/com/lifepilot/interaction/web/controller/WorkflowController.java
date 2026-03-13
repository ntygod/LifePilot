package com.lifepilot.interaction.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.interaction.web.model.ErrorResponse;
import com.lifepilot.interaction.web.model.TriggerWorkflowRequest;
import com.lifepilot.interaction.web.model.WorkflowDetailDto;
import com.lifepilot.interaction.web.model.WorkflowItemDto;
import com.lifepilot.interaction.web.sse.SseEventType;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.workflow.config.WorkflowConfigProperties;
import com.lifepilot.workflow.engine.InputValidationResult;
import com.lifepilot.workflow.engine.InputValidator;
import com.lifepilot.workflow.engine.WorkflowCommandService;
import com.lifepilot.workflow.engine.WorkflowEngine;
import com.lifepilot.workflow.engine.WorkflowEventRecorder;
import com.lifepilot.workflow.engine.WorkflowRealtimeEventHub;
import com.lifepilot.workflow.model.ApprovalDecision;
import com.lifepilot.workflow.model.Result;
import com.lifepilot.workflow.model.StepLog;
import com.lifepilot.workflow.model.ValidationResponse;
import com.lifepilot.workflow.model.WorkflowDefinition;
import com.lifepilot.workflow.model.WorkflowEvent;
import com.lifepilot.workflow.model.WorkflowInstance;
import com.lifepilot.workflow.model.WorkflowTrigger;
import com.lifepilot.workflow.parser.WorkflowYamlParser;
import com.lifepilot.workflow.parser.WorkflowYamlPrinter;
import com.lifepilot.workflow.registry.WorkflowRegistry;
import com.lifepilot.workflow.repository.WorkflowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

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
    private final WorkflowRealtimeEventHub workflowRealtimeEventHub;
    private final SseSessionManager sseSessionManager;
    private final WorkflowYamlParser yamlParser;
    private final WorkflowYamlPrinter yamlPrinter;
    private final WorkflowConfigProperties workflowConfig;
    private final ObjectMapper objectMapper;
    private final Path workflowsDirectory;

    public WorkflowController(WorkflowRegistry workflowRegistry,
                               WorkflowEngine workflowEngine,
                               WorkflowCommandService workflowCommandService,
                               WorkflowRepository workflowRepository,
                               WorkflowEventRecorder workflowEventRecorder,
                               WorkflowRealtimeEventHub workflowRealtimeEventHub,
                               SseSessionManager sseSessionManager,
                               WorkflowYamlParser yamlParser,
                               WorkflowYamlPrinter yamlPrinter,
                               WorkflowConfigProperties workflowConfig) {
        this.workflowRegistry = workflowRegistry;
        this.workflowEngine = workflowEngine;
        this.workflowCommandService = workflowCommandService;
        this.workflowRepository = workflowRepository;
        this.workflowEventRecorder = workflowEventRecorder;
        this.workflowRealtimeEventHub = workflowRealtimeEventHub;
        this.sseSessionManager = sseSessionManager;
        this.yamlParser = yamlParser;
        this.yamlPrinter = yamlPrinter;
        this.workflowConfig = workflowConfig;
        this.objectMapper = new ObjectMapper();
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
     * 获取工作流定义列表，支持按标签筛选。
     *
     * @param tag 可选标签过滤参数
     * @return 工作流定义列表
     */
    @GetMapping
    public ResponseEntity<?> listWorkflows(@RequestParam(required = false) String tag) {
        log.debug("查询工作流列表: tag={}", tag);
        var definitions = (tag != null && !tag.isBlank())
                ? workflowRegistry.findByTag(tag)
                : workflowRegistry.listAll();
        var dtos = definitions.stream()
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
        var instance = workflowRepository.findInstance(instanceId)
                .orElseThrow(() -> new IllegalStateException("Workflow instance not found after trigger: id=" + instanceId));
        log.info("工作流触发成功: workflowId={}, instanceId={}", id, instanceId);
        return ResponseEntity.accepted().body(instance);
    }

    /**
     * 试运行工作流：模拟执行，不持久化状态，不产生副作用。
     *
     * @param id     工作流 ID
     * @param inputs 可选输入参数
     * @return DryRunResult，404 不存在，400 参数错误
     */
    @PostMapping("/{id}/dry-run")
    public ResponseEntity<?> dryRun(@PathVariable String id,
                                     @RequestBody(required = false) Map<String, Object> inputs) {
        var defOpt = workflowRegistry.find(id);
        if (defOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    new ErrorResponse(404, "工作流不存在: id=" + id, Instant.now()));
        }

        try {
            var result = workflowCommandService.dryRun(id, inputs);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("试运行失败: workflowId={}", id, e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    new ErrorResponse(400, "试运行失败: " + e.getMessage(), Instant.now()));
        }
    }

    /**
     * 校验 YAML 工作流定义。
     *
     * <p>语法正确但有语义警告时返回 200，语法错误时返回 400。</p>
     *
     * @param request 包含 yamlContent 的请求体
     * @return ValidationResponse，400 参数错误或语法错误
     */
    @PostMapping("/validate")
    public ResponseEntity<?> validateYaml(@RequestBody Map<String, Object> request) {
        String yamlContent = getString(request, "yamlContent");
        if (yamlContent == null || yamlContent.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse(400, "yamlContent 不能为空", Instant.now()));
        }

        ValidationResponse validationResponse = workflowCommandService.validateYaml(yamlContent);

        if (!validationResponse.valid()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(validationResponse);
        }
        return ResponseEntity.ok(validationResponse);
    }

    /**
     * Webhook 触发工作流执行。
     *
     * <p>外部系统通过 POST /api/workflows/{id}/webhook 触发工作流。
     * 配置了 {@code secret} 时验证 {@code X-Webhook-Signature} HMAC-SHA256 签名。
     *
     * @param id        工作流 ID
     * @param body      JSON 请求体作为工作流输入参数
     * @param signature X-Webhook-Signature 请求头
     * @return 202 触发成功，400 已禁用/未配置 Webhook，401 签名验证失败，404 不存在
     */
    @PostMapping("/{id}/webhook")
    public ResponseEntity<?> webhookTrigger(@PathVariable String id,
                                             @RequestBody(required = false) Map<String, Object> body,
                                             @RequestHeader(value = "X-Webhook-Signature", required = false) String signature) {
        // 1. 检查工作流是否存在
        var defOpt = workflowRegistry.find(id);
        if (defOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    new ErrorResponse(404, "工作流不存在: id=" + id, Instant.now()));
        }

        var definition = defOpt.get();

        // 2. 检查工作流是否启用
        if (!definition.enabled()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    new ErrorResponse(400, "工作流已禁用，不可通过 Webhook 触发", Instant.now()));
        }

        // 3. 检查是否配置了 WebhookTrigger
        var webhookTrigger = definition.triggers().stream()
                .filter(t -> t instanceof WorkflowTrigger.WebhookTrigger)
                .map(t -> (WorkflowTrigger.WebhookTrigger) t)
                .findFirst();

        if (webhookTrigger.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    new ErrorResponse(400, "工作流未配置 Webhook 触发器", Instant.now()));
        }

        // 4. 签名验证（配置了 secret 时）
        String secret = webhookTrigger.get().secret();
        if (secret != null) {
            try {
                String payload = objectMapper.writeValueAsString(body != null ? body : Map.of());
                if (!verifyWebhookSignature(secret, payload, signature)) {
                    log.warn("Webhook 签名验证失败: workflowId={}", id);
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                            new ErrorResponse(401, "Webhook 签名验证失败", Instant.now()));
                }
            } catch (Exception e) {
                log.error("Webhook 签名验证异常: workflowId={}", id, e);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                        new ErrorResponse(401, "Webhook 签名验证失败", Instant.now()));
            }
        }

        // 5. 触发工作流
        Map<String, Object> inputs = body != null ? body : Map.of();
        String instanceId = workflowCommandService.start(id, inputs);
        var instance = workflowRepository.findInstance(instanceId)
                .orElseThrow(() -> new IllegalStateException("Webhook 触发后实例未找到: instanceId=" + instanceId));

        log.info("Webhook 触发工作流成功: workflowId={}, instanceId={}", id, instanceId);
        return ResponseEntity.accepted().body(instance);
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

    /**
     * 获取工作流执行统计。
     *
     * @param id 工作流 ID
     * @return WorkflowStats，不存在返回 404
     */
    @GetMapping("/{id}/stats")
    public ResponseEntity<?> getWorkflowStats(@PathVariable String id) {
        if (workflowRegistry.find(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    new ErrorResponse(404, "工作流不存在: id=" + id, Instant.now()));
        }
        return ResponseEntity.ok(workflowRepository.queryWorkflowStats(id));
    }

    /**
     * 获取工作流步骤执行统计。
     *
     * @param id 工作流 ID
     * @return List<StepStats>，不存在返回 404
     */
    @GetMapping("/{id}/step-stats")
    public ResponseEntity<?> getStepStats(@PathVariable String id) {
        if (workflowRegistry.find(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    new ErrorResponse(404, "工作流不存在: id=" + id, Instant.now()));
        }
        return ResponseEntity.ok(workflowRepository.queryStepStats(id));
    }

    /**
     * 获取工作流实例上下文数据快照。
     *
     * @param instanceId 实例 ID
     * @return context 数据，不存在返回 404
     */
    @GetMapping("/executions/{instanceId}/context")
    public ResponseEntity<?> getInstanceContext(@PathVariable String instanceId) {
        return workflowRepository.findInstance(instanceId)
                .<ResponseEntity<?>>map(instance -> ResponseEntity.ok(instance.context().getData()))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                        new ErrorResponse(404, "工作流实例未找到: id=" + instanceId, Instant.now())));
    }

    /**
     * 获取指定步骤的输出详情（输出数据 + 执行耗时 + 重试次数 + 错误信息）。
     *
     * @param instanceId 实例 ID
     * @param stepId     步骤 ID
     * @return 步骤输出 Map，实例不存在返回 404
     */
    @GetMapping("/executions/{instanceId}/steps/{stepId}/output")
    public ResponseEntity<?> getStepOutput(@PathVariable String instanceId,
                                           @PathVariable String stepId) {
        var instanceOpt = workflowRepository.findInstance(instanceId);
        if (instanceOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    new ErrorResponse(404, "工作流实例未找到: id=" + instanceId, Instant.now()));
        }

        var instance = instanceOpt.get();

        // 从 context 读取步骤输出
        Object output = instance.context().get("steps." + stepId + ".output").orElse(null);

        // 从 StepLog 查询执行详情（取最新一条）
        var stepLogs = workflowRepository.findStepLogs(instanceId).stream()
                .filter(sl -> stepId.equals(sl.stepId()))
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("output", output);

        if (stepLogs.isEmpty()) {
            // 步骤尚未执行
            result.put("state", "PENDING");
            result.put("durationMs", null);
            result.put("retryCount", 0);
            result.put("errorMessage", null);
        } else {
            // 取最后一条日志（最终状态）
            var lastLog = stepLogs.getLast();
            result.put("state", lastLog.state().name());
            result.put("durationMs", lastLog.durationMs());
            result.put("retryCount", lastLog.retryCount());
            result.put("errorMessage", lastLog.errorMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 获取工作流 DAG 依赖图数据。
     *
     * @param id 工作流 ID
     * @return DagData，不存在返回 404
     */
    @GetMapping("/{id}/dag")
    public ResponseEntity<?> getDagData(@PathVariable String id) {
        if (workflowRegistry.find(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    new ErrorResponse(404, "工作流不存在: id=" + id, Instant.now()));
        }
        return ResponseEntity.ok(workflowCommandService.buildDagData(id));
    }

    @GetMapping(value = "/{id}/executions/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamWorkflowExecutions(@PathVariable String id) {
        if (workflowRegistry.find(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "工作流不存在: id=" + id);
        }

        String streamId = "workflow-executions-" + id + "-" + UUID.randomUUID();
        SseEmitter emitter = sseSessionManager.createEmitter(streamId);
        AutoCloseable executionSubscription = workflowRealtimeEventHub.onWorkflowExecution(
                id,
                execution -> sseSessionManager.sendEvent(
                        streamId,
                        SseEventType.WORKFLOW_EXECUTION_UPDATED,
                        execution
                )
        );
        registerSseCleanup(emitter, streamId, executionSubscription);
        sseSessionManager.sendEvent(
                streamId,
                SseEventType.WORKFLOW_EXECUTIONS_SNAPSHOT,
                new WorkflowExecutionsSnapshot(workflowRepository.findInstancesByWorkflowId(id))
        );
        return emitter;
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
    @GetMapping(value = "/executions/{instanceId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamInstance(@PathVariable String instanceId) {
        var instance = workflowRepository.findInstance(instanceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "工作流实例未找到: id=" + instanceId));

        String streamId = "workflow-instance-" + instanceId + "-" + UUID.randomUUID();
        SseEmitter emitter = sseSessionManager.createEmitter(streamId);
        AutoCloseable executionSubscription = workflowRealtimeEventHub.onInstanceExecution(
                instanceId,
                updated -> sseSessionManager.sendEvent(
                        streamId,
                        SseEventType.WORKFLOW_EXECUTION_UPDATED,
                        updated
                )
        );
        AutoCloseable eventSubscription = workflowRealtimeEventHub.onInstanceEvent(
                instanceId,
                event -> sseSessionManager.sendEvent(
                        streamId,
                        SseEventType.WORKFLOW_EVENT_CREATED,
                        event
                )
        );
        AutoCloseable stepLogSubscription = workflowRealtimeEventHub.onInstanceStepLog(
                instanceId,
                stepLog -> sseSessionManager.sendEvent(
                        streamId,
                        SseEventType.WORKFLOW_STEP_LOG_CREATED,
                        stepLog
                )
        );
        registerSseCleanup(emitter, streamId, executionSubscription, eventSubscription, stepLogSubscription);
        sseSessionManager.sendEvent(streamId, SseEventType.WORKFLOW_EXECUTION_SNAPSHOT, instance);
        sseSessionManager.sendEvent(
                streamId,
                SseEventType.WORKFLOW_TIMELINE_SNAPSHOT,
                new WorkflowTimelineSnapshot(workflowEventRecorder.getTimeline(instanceId))
        );
        sseSessionManager.sendEvent(
                streamId,
                SseEventType.WORKFLOW_STEP_LOGS_SNAPSHOT,
                new WorkflowStepLogsSnapshot(workflowRepository.findStepLogsSummary(instanceId))
        );
        return emitter;
    }

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

    record WorkflowExecutionsSnapshot(List<WorkflowInstance> executions) {}

    record WorkflowTimelineSnapshot(List<WorkflowEvent> events) {}

    record WorkflowStepLogsSnapshot(List<StepLog> stepLogs) {}

    private void registerSseCleanup(SseEmitter emitter, String streamId, AutoCloseable... closables) {
        Runnable cleanup = () -> {
            closeQuietly(closables);
            sseSessionManager.closeEmitter(streamId);
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ex -> cleanup.run());
    }

    private void closeQuietly(AutoCloseable... closables) {
        for (AutoCloseable closable : closables) {
            if (closable == null) {
                continue;
            }
            try {
                closable.close();
            } catch (Exception closeError) {
                log.debug("关闭工作流 SSE 订阅失败: {}", closeError.getMessage(), closeError);
            }
        }
    }

    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    /**
     * 验证 Webhook HMAC-SHA256 签名。
     *
     * @param secret    签名密钥
     * @param payload   请求体原文
     * @param signature X-Webhook-Signature 头部值（格式: sha256=<hex>）
     * @return 签名是否匹配
     */
    private boolean verifyWebhookSignature(String secret, String payload, String signature) {
        if (signature == null || !signature.startsWith("sha256=")) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String expected = "sha256=" + HexFormat.of().formatHex(hash);
            return expected.equals(signature);
        } catch (Exception e) {
            log.warn("HMAC-SHA256 签名计算失败: {}", e.getMessage());
            return false;
        }
    }
}
