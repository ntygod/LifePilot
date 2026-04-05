package com.lifepilot.a2a.server;

import com.lifepilot.a2a.model.A2aTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * A2A Task 管理 REST 端点（已废弃，请使用 JSON-RPC 端点 {@code POST /api/a2a}）。
 *
 * <p>提供 Task 状态查询和取消功能。</p>
 *
 * @author zsg
 * @since 2026-02-28
 * @deprecated 请使用 {@link A2aJsonRpcController} 的 JSON-RPC 2.0 端点
 */
@Deprecated
@RestController
@RequestMapping("/api/a2a/tasks")
@ConditionalOnProperty(prefix = "lifepilot.a2a.server", name = "enabled", havingValue = "true", matchIfMissing = true)
public class A2aTaskController {

    private static final Logger log = LoggerFactory.getLogger(A2aTaskController.class);

    private final A2aTaskStore taskStore;

    public A2aTaskController(A2aTaskStore taskStore) {
        this.taskStore = taskStore;
    }

    /**
     * 查询 Task 状态。
     *
     * @param id Task ID
     * @return Task（不存在返回 404）
     */
    @GetMapping("/{id}")
    public ResponseEntity<A2aTask> getTask(@PathVariable String id) {
        return taskStore.find(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> {
                    log.debug("Task 不存在: id={}", id);
                    return ResponseEntity.notFound().build();
                });
    }

    /**
     * 取消 Task。
     *
     * <p>终态 Task 返回 409 Conflict。</p>
     *
     * @param id Task ID
     * @return 取消后的 Task（不存在返回 404，终态返回 409）
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancelTask(@PathVariable String id) {
        var taskOpt = taskStore.find(id);
        if (taskOpt.isEmpty()) {
            log.debug("取消 Task 失败，Task 不存在: id={}", id);
            return ResponseEntity.notFound().build();
        }

        boolean canceled = taskStore.cancel(id);
        if (!canceled) {
            log.info("取消 Task 失败，Task 已处于终态: id={}, state={}", id, taskOpt.get().status().state());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", Map.of("code", 409, "message", "Task 已处于终态，无法取消")));
        }

        log.info("Task 已取消: id={}", id);
        return taskStore.find(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
