package com.lifepilot.memory.governance.lifecycle.listeners;

import com.lifepilot.interaction.web.repository.MemoryProvenanceRepository;
import com.lifepilot.memory.governance.lifecycle.events.SourceInvalidated;
import com.lifepilot.memory.governance.lifecycle.feedback.RevalidationQueueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 源对象失效时，为所有引用该 source 的实体入再验证队列（{@code PENDING}）。
 *
 * <p>与 {@code ProvenanceStaleListener} 共享同一 {@link SourceInvalidated} 事件但分工互补：
 * <ul>
 *   <li>{@code ProvenanceStaleListener}: 在 provenance 行上打 {@code STALE} 标记</li>
 *   <li>{@code ReValidationListener}: 把受影响实体全部入再验证队列</li>
 * </ul>
 * 两者顺序无关 —— 队列表与 provenance 表在事件事务提交后独立更新。</p>
 *
 * <p>检索层后续行为：命中队列中存在 PENDING 行的实体时，会附 {@code needsRevalidation=true}
 * 提示 LLM 在回答时复核；复核后将队列行置为 {@code RESOLVED}。</p>
 *
 * <p>幂等性：同一事件重放会产生重复 PENDING 条目 —— 因设计上允许同一实体对同一 source
 * 的多次失效留痕（如先 ARCHIVED 后 CONTENT_CHANGED），检索层按 created_at 取最新即可。
 * 如需去重，可在 Repository 层加 UNIQUE 约束。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@ConditionalOnProperty(prefix = "lifepilot.memory", name = "enabled", havingValue = "true", matchIfMissing = true)
@Component
public class ReValidationListener {

    private static final Logger log = LoggerFactory.getLogger(ReValidationListener.class);

    private final MemoryProvenanceRepository provenanceRepo;
    private final RevalidationQueueRepository queueRepo;
    private final Clock clock;

    public ReValidationListener(MemoryProvenanceRepository provenanceRepo,
                                RevalidationQueueRepository queueRepo,
                                Clock clock) {
        this.provenanceRepo = Objects.requireNonNull(provenanceRepo, "provenanceRepo 不能为空");
        this.queueRepo = Objects.requireNonNull(queueRepo, "queueRepo 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    /**
     * 处理源失效事件。
     *
     * @param event 源失效事件
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSourceInvalidated(SourceInvalidated event) {
        Objects.requireNonNull(event, "源失效事件不能为空");
        List<String> entityIds = Objects.requireNonNull(
                provenanceRepo.findEntityIdsBySource(event.sourceType(), event.sourceId()),
                "受影响实体查询结果不能为空");
        validateEntityIds(entityIds);
        if (entityIds.isEmpty()) {
            log.debug("再验证: 无引用实体, sourceType={} sourceId={}",
                    event.sourceType(), event.sourceId());
            return;
        }
        Instant when = clock.instant();
        for (String id : entityIds) {
            queueRepo.enqueue(id, event.sourceType(), event.sourceId(), when);
        }
        log.info("再验证: 入队 {} 个实体, source={}:{}",
                entityIds.size(), event.sourceType(), event.sourceId());
    }

    private void validateEntityIds(List<String> entityIds) {
        for (String entityId : entityIds) {
            if (entityId == null || entityId.isBlank()) {
                throw new IllegalArgumentException("受影响实体 ID 不能为空");
            }
        }
    }
}
