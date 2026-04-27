package com.lifepilot.interaction.web.controller;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.lifepilot.interaction.web.model.ApiResponse;
import com.lifepilot.sandbox.runtime.PythonRuntimeManager;
import com.lifepilot.sandbox.runtime.RuntimeInstallProgressEmitter;
import com.lifepilot.sandbox.runtime.RuntimeStatus;
import com.lifepilot.sandbox.runtime.RuntimeStatusJson;

/**
 * 捆绑 Python 运行时管理 REST 端点。
 *
 * <p>提供 6 个端点：状态查询、安装、卸载、禁用、启用、SSE 进度订阅。</p>
 *
 * <p><b>并发策略</b>：{@link #install()} 通过 {@link AtomicBoolean#compareAndSet} 保证
 * 同一时刻只允许一个安装任务进行中；并发请求返回 409。任务完成（无论成功失败）
 * 都会通过 {@code whenComplete} 释放标志位。</p>
 *
 * <p><b>启用语义</b>：{@link #enable()} 后若状态为 {@link RuntimeStatus.NotInstalled}
 * （文件缺失），自动转入 install 流程；其他状态仅切换内存标志即可。</p>
 *
 * @author zsg
 * @since 2026-04-26
 */
@RestController
@RequestMapping("/api/runtime")
@ConditionalOnProperty(name = "lifepilot.sandbox.enabled", havingValue = "true", matchIfMissing = true)
public class RuntimeController {

    private static final Logger log = LoggerFactory.getLogger(RuntimeController.class);

    private final PythonRuntimeManager manager;
    private final RuntimeInstallProgressEmitter emitter;

    /** 同时只允许一个 install 任务进行中。 */
    private final AtomicBoolean installInProgress = new AtomicBoolean(false);

    public RuntimeController(PythonRuntimeManager manager, RuntimeInstallProgressEmitter emitter) {
        this.manager = manager;
        this.emitter = emitter;
    }

    /**
     * 查询当前 Python 运行时状态。
     *
     * <p>响应字段随状态不同而变化：
     * <ul>
     *   <li>{@code READY} → 含 version, diskBytes</li>
     *   <li>{@code INSTALLING} → 含 phase, bytesDownloaded, totalBytes, percent</li>
     *   <li>{@code INSTALL_FAILED} → 含 reason</li>
     *   <li>{@code NOT_INSTALLED} / {@code DISABLED} → 仅 status</li>
     * </ul></p>
     */
    @GetMapping("/python/status")
    public ApiResponse<Map<String, Object>> status() {
        return ApiResponse.ok(RuntimeStatusJson.toMap(manager.checkStatus()));
    }

    /**
     * 触发异步安装；并发请求返回 409。
     *
     * <p>本端点立即返回，真正的安装进度通过 {@link #installProgress()} SSE 端点推送。</p>
     *
     * <p><b>容错</b>：{@link PythonRuntimeManager#install} 调用本身可能同步抛错
     * （如 OOM 时 executor 创建失败），异常不会进入返回的 future，需 try/finally
     * 兜底重置 {@code installInProgress} 标志，避免后续请求被永久 409。</p>
     */
    @PostMapping("/python/install")
    public ResponseEntity<ApiResponse<Map<String, Object>>> install() {
        if (!installInProgress.compareAndSet(false, true)) {
            log.warn("拒绝并发安装请求：当前已有任务进行中");
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error(HttpStatus.CONFLICT.value(), "已有安装任务进行中"));
        }
        dispatchInstallTask();
        return ResponseEntity.ok(ApiResponse.ok(Map.of("ok", true)));
    }

    /**
     * 派发实际的 install 异步任务；调用前必须已成功 cAS 占住 {@link #installInProgress}。
     *
     * <p>如果 {@link PythonRuntimeManager#install} 同步抛异常，try/finally 会重置标志位
     * 并将异常向上抛出（最终由 {@link WebExceptionHandler} 转成 500）。</p>
     */
    private void dispatchInstallTask() {
        boolean dispatched = false;
        try {
            manager.install(emitter).whenComplete((v, e) -> {
                installInProgress.set(false);
                if (e != null) {
                    log.error("install 任务异常结束: {}", e.getMessage(), e);
                }
            });
            dispatched = true;
        } finally {
            if (!dispatched) {
                installInProgress.set(false);
                log.warn("install dispatch 失败，已释放并发标志");
            }
        }
    }

    /**
     * 卸载 Python 运行时（递归删除安装目录）。
     *
     * @throws IOException 删除目录失败时由全局异常处理器转 500
     */
    @PostMapping("/python/uninstall")
    public ApiResponse<Map<String, Object>> uninstall() throws IOException {
        manager.uninstall();
        return ApiResponse.ok(Map.of("ok", true));
    }

    /**
     * 禁用捆绑 Python — 文件保留，{@link RuntimeStatus} 切到 DISABLED。
     */
    @PostMapping("/python/disable")
    public ApiResponse<Map<String, Object>> disable() {
        manager.disable();
        return ApiResponse.ok(Map.of("ok", true));
    }

    /**
     * 启用捆绑 Python；如果检测到未安装（文件缺失），自动触发 install。
     *
     * <p><b>并发语义</b>：自动 install 时若撞 {@link #installInProgress}（已有安装在跑），
     * 不应让前端误报 enable 失败——{@link PythonRuntimeManager#enable} 本身（disabled=false
     * 写入）已成功，install 被 dedupe 是合理行为。此时返回 200 + {@code installSkipped: true}，
     * 让前端通过下一次 status 查询拿到真实进度。</p>
     */
    @PostMapping("/python/enable")
    public ResponseEntity<ApiResponse<Map<String, Object>>> enable() {
        manager.enable();
        if (!(manager.checkStatus() instanceof RuntimeStatus.NotInstalled)) {
            return ResponseEntity.ok(ApiResponse.ok(Map.of("ok", true)));
        }
        log.info("启用后检测到未安装，自动触发 install");
        if (!installInProgress.compareAndSet(false, true)) {
            log.info("enable 自动 install 撞并发（已有安装在跑），仅切换标志位");
            return ResponseEntity.ok(ApiResponse.ok(Map.of("ok", true, "installSkipped", true)));
        }
        dispatchInstallTask();
        return ResponseEntity.ok(ApiResponse.ok(Map.of("ok", true, "installTriggered", true)));
    }

    /**
     * 订阅安装进度 SSE 流。事件名：{@code progress}（Installing 状态快照）/ {@code failed}（失败原因）。
     */
    @GetMapping(path = "/install/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter installProgress() {
        return emitter.subscribe();
    }

}
