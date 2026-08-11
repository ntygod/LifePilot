package com.lifepilot.memory.governance.lifecycle.listeners;

import com.lifepilot.interaction.web.repository.MemoryProvenanceRepository;
import com.lifepilot.memory.governance.lifecycle.events.SourceInvalidated;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.util.Objects;

/**
 * 源对象（文档 / 知识库 / 会话）失效时，将所有引用它的 {@code memory_entity_provenances}
 * 记录整体标为 {@code STALE}。
 *
 * <p>{@code STALE} 本身不会让实体从主库消失 —— 这是刻意设计：记忆实体可能同时由多个
 * source 贡献，单一 source 失效不应全盘否定实体内容。检索层召回时，会结合
 * provenance 的 {@code status=STALE} 标记附上 {@code needsRevalidation=true} 提示，
 * 交由 LLM 侧在回答时复核。</p>
 *
 * <p>真正的再验证入队流程由 {@code ReValidationListener} 独立处理 —— 二者共享同一
 * {@link SourceInvalidated} 事件但职责互补。</p>
 *
 * <p>底层写入失败会直接上抛，避免来源已失效但 provenance 仍保持 VALID。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@ConditionalOnProperty(prefix = "lifepilot.memory", name = "enabled", havingValue = "true", matchIfMissing = true)
@Component
public class ProvenanceStaleListener {

    private final MemoryProvenanceRepository repo;
    private final Clock clock;

    public ProvenanceStaleListener(MemoryProvenanceRepository repo, Clock clock) {
        this.repo = Objects.requireNonNull(repo, "repo 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    /**
     * 处理源失效事件。
     *
     * @param event 源对象失效事件
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSourceInvalidated(SourceInvalidated event) {
        Objects.requireNonNull(event, "源失效事件不能为空");
        repo.markStale(event.sourceType(), event.sourceId(), clock.instant());
    }
}
