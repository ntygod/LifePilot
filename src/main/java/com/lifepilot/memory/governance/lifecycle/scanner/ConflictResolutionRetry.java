package com.lifepilot.memory.governance.lifecycle.scanner;

import com.lifepilot.agent.learning.conflict.ConflictResolutionRepository;
import com.lifepilot.agent.learning.conflict.ConflictResolutionService;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 冲突裁决重试扫描器 —— 每日凌晨 4 点扫描 {@code conflict_resolution_queue} 中状态为
 * {@code FAILED} 且 {@code attempt_count &lt; 3} 的队列项，重新调
 * {@link ConflictResolutionService#resolveAsync(TemporalEntity, List)}。
 *
 * <p>重试入口由 {@link ConflictResolutionService} 自身负责再次入队+计数，本 Cron 仅做
 * "拉取 → 重放"。当新实体已被硬删除 / 全部候选已不存在时直接跳过该条（不再消耗重试次数）。</p>
 *
 * <p>设计要点：
 * <ul>
 *   <li>依赖或 LLM 提交异常直接抛出，避免重试调度失败被日志吞掉</li>
 *   <li>不自己发事件 —— 裁决服务内部会经 {@link SemanticMemory#updateLifecycleState} 代发</li>
 *   <li>{@link #retryNow()} 给测试用；{@link #retry()} 由 {@link Scheduled} 调用后委托</li>
 *   <li>{@code @EnableScheduling} 已由 {@code MemoryAutoConfiguration} 开启</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-23
 */
@ConditionalOnProperty(prefix = "lifepilot.memory", name = "enabled", havingValue = "true", matchIfMissing = true)
@Component
public class ConflictResolutionRetry {

    private static final Logger log = LoggerFactory.getLogger(ConflictResolutionRetry.class);

    /** 最大重试次数 —— 与 {@link ConflictResolutionService#markFailed} 递增的 attempt_count 对齐。 */
    private static final int MAX_ATTEMPTS = 3;

    private final ConflictResolutionRepository queueRepository;
    private final ConflictResolutionService resolutionService;
    private final SemanticMemory semanticMemory;

    public ConflictResolutionRetry(ConflictResolutionRepository queueRepository,
                                   ConflictResolutionService resolutionService,
                                   SemanticMemory semanticMemory) {
        this.queueRepository = Objects.requireNonNull(queueRepository, "queueRepository 不能为空");
        this.resolutionService = Objects.requireNonNull(resolutionService, "resolutionService 不能为空");
        this.semanticMemory = Objects.requireNonNull(semanticMemory, "semanticMemory 不能为空");
    }

    /**
     * 定时入口 —— 每天凌晨 4 点触发。
     *
     * <p>选凌晨 4 点：在 {@code OrphanProvenanceScanner} 3 点扫描之后，避开同时触发
     * ProvenanceStaleListener 的 markStale 与本 Cron 的 LLM 调用抢占 SQLite 单写入者。</p>
     */
    @Scheduled(cron = "0 0 4 * * *")
    public void retry() {
        retryNow();
    }

    /**
     * 测试友好入口 —— 与 {@link #retry()} 共享逻辑，方便手动触发和单元测试直接调用。
     */
    public void retryNow() {
        var pending = Objects.requireNonNull(
                queueRepository.findFailedRetriable(MAX_ATTEMPTS),
                "冲突裁决重试队列查询结果不能为空");
        if (pending.isEmpty()) {
            log.debug("ConflictResolutionRetry 无可重试项，跳过");
            return;
        }
        log.info("ConflictResolutionRetry 拉取 {} 条 FAILED 待重试项", pending.size());

        int skipped = 0;
        int replayed = 0;
        for (var item : pending) {
            var newEntityOpt = semanticMemory.findById(item.newEntityId());
            if (newEntityOpt.isEmpty()) {
                // 新实体已被硬删除 / 归档清理 —— 跳过即可，不推进 attempt_count
                log.debug("ConflictResolutionRetry 新实体已不存在，跳过, queueId={}, newId={}",
                        item.id(), item.newEntityId());
                skipped++;
                continue;
            }
            var candidates = loadCandidates(item.candidateEntityIds());
            if (candidates.isEmpty()) {
                log.debug("ConflictResolutionRetry 全部候选均不存在，跳过, queueId={}, newId={}",
                        item.id(), item.newEntityId());
                skipped++;
                continue;
            }
            Objects.requireNonNull(
                    resolutionService.resolveAsync(newEntityOpt.get(), candidates),
                    "冲突裁决重试提交结果不能为空").join();
            replayed++;
        }
        if (replayed > 0 || skipped > 0) {
            log.info("ConflictResolutionRetry 完成: 重放={}, 跳过={}, 共={}",
                    replayed, skipped, pending.size());
        }
    }

    /**
     * 按 id 列表回捞候选实体 —— 已被硬删除的 id 静默丢弃，不影响其他候选。
     *
     * @param ids 候选 ID 列表
     * @return 仍然存在的候选实体（保序，但空缺项被过滤）
     */
    private List<TemporalEntity> loadCandidates(List<String> ids) {
        var result = new ArrayList<TemporalEntity>(ids.size());
        for (var id : ids) {
            semanticMemory.findById(id).ifPresent(result::add);
        }
        return result;
    }
}
