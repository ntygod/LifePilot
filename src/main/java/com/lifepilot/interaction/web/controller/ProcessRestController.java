package com.lifepilot.interaction.web.controller;

import com.lifepilot.interaction.web.model.ApiResponse;
import com.lifepilot.meta.infra.shell.BackgroundProcessManager;
import com.lifepilot.meta.infra.shell.ProcessInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台进程 REST 管理控制器。
 *
 * <p>提供前端气泡 UI 的直接操作通道（列表、停止），不经 LLM 工具调用链路，
 * 避免产生噪音或触发权限审批。</p>
 *
 * @author zsg
 * @since 2026-04-18
 */
@RestController
@RequestMapping("/api/processes")
@ConditionalOnProperty(name = "lifepilot.gateway.channels.web.enabled", havingValue = "true")
public class ProcessRestController {

    private static final Logger log = LoggerFactory.getLogger(ProcessRestController.class);

    private final BackgroundProcessManager backgroundProcessManager;

    public ProcessRestController(BackgroundProcessManager backgroundProcessManager) {
        this.backgroundProcessManager = backgroundProcessManager;
    }

    /**
     * 列出当前所有活跃的后台进程。
     *
     * @return 进程摘要列表
     */
    @GetMapping
    public ApiResponse<Map<String, Object>> list() {
        var processes = backgroundProcessManager.listProcesses().stream()
                .map(this::toPayload)
                .toList();
        return ApiResponse.ok(Map.of("processes", processes, "count", processes.size()));
    }

    /**
     * 终止指定后台进程。
     *
     * <p>幂等语义：进程已完成或不存在时返回错误描述，不抛异常。</p>
     *
     * @param sessionId 进程会话标识
     * @return 操作结果
     */
    @DeleteMapping("/{sessionId}")
    public ApiResponse<Map<String, String>> kill(@PathVariable String sessionId) {
        try {
            backgroundProcessManager.killProcess(sessionId);
            log.info("后台进程已通过 REST 终止: sessionId={}", sessionId);
            return ApiResponse.ok(Map.of("sessionId", sessionId, "message", "进程已终止"));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(404, "进程不存在或已清理: " + sessionId);
        }
    }

    private Map<String, Object> toPayload(ProcessInfo p) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("sessionId", p.sessionId());
        entry.put("command", p.command());
        entry.put("state", p.state().name());
        if (p.exitCode() != null) entry.put("exitCode", p.exitCode());
        entry.put("startTime", p.startTime().toString());
        entry.put("workDir", p.workDir());
        return Map.copyOf(entry);
    }
}
