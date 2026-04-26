package com.lifepilot.sandbox.runtime;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Python 运行时安装进度 SSE 推送器。
 *
 * <p>多前端订阅同一进度流，全局单例。同时只允许一个 install 进程，
 * 进度由 {@link PythonRuntimeManager#install} 通过 {@link #emit} 推送。</p>
 *
 * @author zsg
 * @since 2026-04-26
 */
@Component
public class RuntimeInstallProgressEmitter {

    private static final Logger log = LoggerFactory.getLogger(RuntimeInstallProgressEmitter.class);

    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /**
     * 订阅安装进度流；返回的 {@link SseEmitter} 由 Spring MVC 持有直至关闭。
     *
     * @return 无超时的 SSE emitter
     */
    public SseEmitter subscribe() {
        var emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        log.debug("新增运行时安装进度订阅，当前订阅数={}", emitters.size());
        return emitter;
    }

    /**
     * 推送一次进度更新。任何 send 失败的 emitter 会被自动剔除。
     *
     * @param status 运行时状态快照
     */
    public void emit(RuntimeStatus status) {
        for (var emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("progress").data(status));
            } catch (IOException e) {
                emitters.remove(emitter);
            }
        }
    }

    /**
     * 推送终态失败事件并关闭所有订阅。
     *
     * @param reason 失败原因
     */
    public void emitFailed(String reason) {
        for (var emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("failed").data(reason));
                emitter.complete();
            } catch (IOException ignored) {
                // 连接已断，complete 也会失败，忽略
            }
        }
        emitters.clear();
    }
}
