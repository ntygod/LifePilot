package com.lifepilot.sandbox.runtime;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;

/**
 * Python 运行时安装进度 SSE 推送器。
 *
 * <p>多前端订阅同一进度流，全局单例。同时只允许一个 install 进程，
 * 进度由 {@link PythonRuntimeManager#install} 通过 {@link #emit} 推送。</p>
 *
 * <p><b>注：</b>本类不带 {@code @Component} 注解，由 {@link com.lifepilot.sandbox.config.SandboxAutoConfiguration}
 * 通过 {@code @Bean} 集中注册，与 {@link RuntimeInstallHistoryRepository} 保持一致风格：
 * sandbox/runtime 子包不依赖 ComponentScan，受 {@code lifepilot.sandbox.enabled} 开关统一控制。</p>
 *
 * <p><b>线程契约</b>：同一时刻只允许单一 install 协程调用 {@link #emit} / {@link #emitFailed}，
 * 其他线程仅可调用 {@link #subscribe}。{@link SseEmitter#send} 非线程安全，
 * 违反此约束需调用方自行加锁。</p>
 *
 * <p>预期订阅者规模：单用户 1-5 个（多 tab + 桌面端），最大不超过 20 个。
 * 超出此规模需评估是否换用 ConcurrentHashMap 或重构为 SseSessionManager 子类。</p>
 *
 * @author zsg
 * @since 2026-04-26
 */
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
        emitter.onError(e -> {
            emitters.remove(emitter);
            log.trace("SSE 订阅异常断开", e);
        });
        log.debug("新增运行时安装进度订阅，当前订阅数={}", emitters.size());
        return emitter;
    }

    /**
     * 推送一次进度更新。任何 send 失败的 emitter 会被剔除并显式 complete，
     * 避免底层 AsyncContext 半关闭。
     *
     * @param status 运行时状态快照
     */
    public void emit(RuntimeStatus status) {
        for (var emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("progress").data(status));
            } catch (IOException e) {
                emitters.remove(emitter);
                try { emitter.complete(); } catch (Exception ignored) {}
                log.debug("SSE 推送失败，已剔除订阅者，剩余订阅数={}", emitters.size());
            }
        }
    }

    /**
     * 推送终态失败事件并关闭所有订阅。
     *
     * <p>调用后所有现有订阅者断开；下次 install 重试需要前端重新调用 {@link #subscribe}。</p>
     *
     * @param reason 失败原因
     */
    public void emitFailed(String reason) {
        for (var emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("failed").data(reason));
                emitter.complete();
            } catch (Exception ignored) {
                // 连接已断或已 complete，complete() 也可能抛 IllegalStateException，忽略
            }
        }
        emitters.clear();
    }

    /**
     * 应用关闭时清理所有订阅，避免遗留 AsyncContext 阻塞容器关闭。
     */
    @PreDestroy
    public void shutdown() {
        int count = emitters.size();
        for (var emitter : emitters) {
            try { emitter.complete(); } catch (Exception ignored) {}
        }
        emitters.clear();
        log.info("RuntimeInstallProgressEmitter 关闭，已清理 {} 个订阅", count);
    }
}
