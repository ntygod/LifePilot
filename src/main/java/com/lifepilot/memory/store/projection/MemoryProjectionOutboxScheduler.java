package com.lifepilot.memory.store.projection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;

/**
 * 记忆投影 outbox 补偿调度器。
 *
 * <p>提交后即时处理只覆盖正常路径；进程重启、向量索引短暂不可用或任务卡在 PROCESSING 时，
 * 由本调度器周期性扫描到期任务并幂等补偿。</p>
 *
 * @author zsg
 * @since 2026-06-18
 */
public class MemoryProjectionOutboxScheduler {

    private static final Logger log = LoggerFactory.getLogger(MemoryProjectionOutboxScheduler.class);
    private static final int DEFAULT_BATCH_SIZE = 50;
    private static final Duration PROCESSING_TIMEOUT = Duration.ofMinutes(5);

    private final MemoryProjectionOutboxProcessor processor;

    public MemoryProjectionOutboxScheduler(MemoryProjectionOutboxProcessor processor) {
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${lifepilot.memory.projection.outbox.retry-delay:PT1M}")
    public void processDueTasks() {
        try {
            int count = processor.processDue(DEFAULT_BATCH_SIZE, PROCESSING_TIMEOUT);
            if (count > 0) {
                log.info("记忆投影 outbox 补偿完成: count={}", count);
            }
        } catch (Exception e) {
            log.warn("记忆投影 outbox 补偿失败: error={}", e.getMessage());
        }
    }
}
