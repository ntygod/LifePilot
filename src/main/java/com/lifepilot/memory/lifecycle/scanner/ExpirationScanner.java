package com.lifepilot.memory.lifecycle.scanner;

import com.lifepilot.memory.lifecycle.ChangeSource;
import com.lifepilot.memory.lifecycle.LifecycleState;
import com.lifepilot.memory.semantic.SemanticMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.time.Clock;

/**
 * TTL 过期扫描器 —— 每小时把 {@code expires_at &lt; now} 的 {@code ACTIVE} 实体转入
 * {@link LifecycleState#EXPIRED}，事件由
 * {@link SemanticMemory#updateLifecycleState(String, LifecycleState, String, ChangeSource)}
 * 代发（source = {@link ChangeSource#CRON_EXPIRE}）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>单条失败兜底 —— 不让一个实体的 SQL 异常中断整批扫描</li>
 *   <li>不自己 publish —— 严格遵循 "lifecycle 事件只有 SemanticMemory 一个源头" 的约定</li>
 *   <li>{@link #scanNow()} 暴露给测试，@Scheduled 包装的 {@link #scan()} 只做委托</li>
 *   <li>{@code @EnableScheduling} 已由 {@code MemoryAutoConfiguration} 开启 —— 无需额外配置</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-23
 */
@ConditionalOnProperty(prefix = "lifepilot.memory", name = "enabled", havingValue = "true", matchIfMissing = true)
@Component
public class ExpirationScanner {

    private static final Logger log = LoggerFactory.getLogger(ExpirationScanner.class);

    /** lifecycleReason 常量 —— 写入 memory_entities.lifecycle_reason。 */
    private static final String EXPIRED_REASON = "ttl-reached";

    private final SemanticMemory semanticMemory;
    private final Clock clock;

    public ExpirationScanner(SemanticMemory semanticMemory, Clock clock) {
        this.semanticMemory = semanticMemory;
        this.clock = clock;
    }

    /**
     * 定时扫描入口 —— 每小时整点触发。
     *
     * <p>cron 表达式 {@code 0 0 * * * *}：秒 0、分 0、时 *、日 *、月 *、周 *；
     * 与 Spring 默认六位 cron（含秒）一致。</p>
     */
    @Scheduled(cron = "0 0 * * * *")
    public void scan() {
        scanNow();
    }

    /**
     * 测试友好入口 —— 绕过 @Scheduled 调度，直接执行一次扫描。
     *
     * <p>生产调用路径也走这里（{@link #scan()} 只做委托），方便排查问题时手动触发。</p>
     */
    public void scanNow() {
        var now = clock.instant();
        var expired = semanticMemory.findExpiredActive(now);
        if (expired.isEmpty()) {
            log.debug("ExpirationScanner 扫描无过期实体 at={}", now);
            return;
        }
        log.info("ExpirationScanner 扫描到 {} 个过期实体 at={}", expired.size(), now);
        int failed = 0;
        for (var entity : expired) {
            try {
                semanticMemory.updateLifecycleState(
                        entity.id(), LifecycleState.EXPIRED, EXPIRED_REASON, ChangeSource.CRON_EXPIRE);
            } catch (Exception ex) {
                failed++;
                log.warn("ExpirationScanner 转 EXPIRED 失败 entity={}, error={}",
                        entity.id(), ex.getMessage(), ex);
                // 单条失败不中断整批
            }
        }
        if (failed > 0) {
            log.warn("ExpirationScanner 本轮 {} 条实体转换失败 / 共 {} 条", failed, expired.size());
        }
    }
}
