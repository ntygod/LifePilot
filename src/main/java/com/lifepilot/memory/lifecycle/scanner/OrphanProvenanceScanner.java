package com.lifepilot.memory.lifecycle.scanner;

import com.lifepilot.document.repository.SessionDocumentRepository;
import com.lifepilot.interaction.web.repository.MemoryProvenanceRepository;
import com.lifepilot.memory.lifecycle.InvalidationKind;
import com.lifepilot.memory.lifecycle.SourceType;
import com.lifepilot.memory.lifecycle.events.SourceInvalidated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * 孤儿 provenance 扫描器 —— 每日凌晨扫描 {@code memory_entity_provenances} 中仍
 * {@code VALID} 的 document 引用，对 {@code session_documents} 里已不存在的
 * "孤儿" document ID 发一条 {@link SourceInvalidated}(DOCUMENT, id, DELETED) 事件。
 *
 * <p>事件链路：下游 {@code ProvenanceStaleListener} 批量把这些 provenance 行置为
 * STALE，{@code ReValidationListener} 同步把受影响实体推进再验证队列。</p>
 *
 * <p>设计要点：
 * <ul>
 *   <li>仅处理 DOCUMENT 类型 —— KnowledgeBase / Session 孤儿的扫描逻辑另做</li>
 *   <li>只发事件不直接写 DB —— 由专职的 {@code ProvenanceStaleListener} 消费，
 *       保留单一职责</li>
 *   <li>{@code listDistinctSourceDocumentIds} 已过滤 {@code status='VALID'}，
 *       确保幂等：同一孤儿 doc 在 STALE 化后不再重复触发</li>
 *   <li>{@link #scanNow()} 给测试用，@Scheduled 的 {@link #scan()} 只委托</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-23
 */
@ConditionalOnProperty(prefix = "lifepilot.memory", name = "enabled", havingValue = "true", matchIfMissing = true)
@Component
public class OrphanProvenanceScanner {

    private static final Logger log = LoggerFactory.getLogger(OrphanProvenanceScanner.class);

    private final MemoryProvenanceRepository provenanceRepo;
    private final SessionDocumentRepository documentRepo;
    private final ApplicationEventPublisher events;

    public OrphanProvenanceScanner(MemoryProvenanceRepository provenanceRepo,
                                   SessionDocumentRepository documentRepo,
                                   ApplicationEventPublisher events) {
        this.provenanceRepo = provenanceRepo;
        this.documentRepo = documentRepo;
        this.events = events;
    }

    /**
     * 定时扫描入口 —— 每天凌晨 3 点触发。
     *
     * <p>选凌晨 3 点避开文档批量写入的高峰，减少与 ProvenanceStaleListener
     * markStale 的 SQLite 单写入者冲突。</p>
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void scan() {
        scanNow();
    }

    /** 测试友好入口 —— 与 {@link #scan()} 共用逻辑。 */
    public void scanNow() {
        var allDocIds = provenanceRepo.listDistinctSourceDocumentIds();
        if (allDocIds.isEmpty()) {
            log.debug("OrphanProvenanceScanner 无 VALID document 引用，跳过");
            return;
        }

        int orphanCount = 0;
        for (var docId : allDocIds) {
            try {
                if (documentRepo.findById(docId) == null) {
                    events.publishEvent(
                            new SourceInvalidated(SourceType.DOCUMENT, docId, InvalidationKind.DELETED));
                    orphanCount++;
                }
            } catch (Exception e) {
                log.warn("OrphanProvenanceScanner 单条检查失败 docId={}, error={}",
                        docId, e.getMessage());
                // 单条异常不中断整批
            }
        }
        if (orphanCount > 0) {
            log.info("OrphanProvenanceScanner 发现 {}/{} 孤儿 document 引用，已发 SourceInvalidated",
                    orphanCount, allDocIds.size());
        } else {
            log.debug("OrphanProvenanceScanner 全部 {} 个 document 均存在，无孤儿", allDocIds.size());
        }
    }
}
