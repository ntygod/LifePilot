package com.lifepilot.interaction.web.controller;

import com.lifepilot.interaction.web.model.ErrorResponse;
import com.lifepilot.interaction.web.model.TriggerWorkflowRequest;
import com.lifepilot.workflow.engine.WorkflowEngine;
import com.lifepilot.workflow.registry.WorkflowRegistry;
import com.lifepilot.workflow.repository.WorkflowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
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
    private final WorkflowRepository workflowRepository;

    public WorkflowController(WorkflowRegistry workflowRegistry,
                               WorkflowEngine workflowEngine,
                               WorkflowRepository workflowRepository) {
        this.workflowRegistry = workflowRegistry;
        this.workflowEngine = workflowEngine;
        this.workflowRepository = workflowRepository;
    }

    /**
     * 获取所有工作流定义列表。
     *
     * @return 工作流定义列表
     */
    @GetMapping
    public ResponseEntity<?> listWorkflows() {
        log.debug("查询工作流列表");
        return ResponseEntity.ok(workflowRegistry.listAll());
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
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                        new ErrorResponse(404, "工作流不存在: id=" + id, Instant.now())));
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
        var instance = workflowEngine.execute(id, inputs);
        log.info("工作流触发成功: workflowId={}, instanceId={}", id, instance.id());
        return ResponseEntity.ok(instance);
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
}
