package com.lifepilot.knowledge.sync;

import com.lifepilot.datastore.model.Collection;
import com.lifepilot.datastore.sync.DatastoreKnowledgeBaseProvisioner;
import com.lifepilot.knowledge.KnowledgeBaseManager;
import com.lifepilot.knowledge.repository.KnowledgeBaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Datastore 默认内部知识库编排器。
 *
 * <p>为每个 Datastore 自动维护一个系统管理的内部知识库，
 * 用于承接 Datastore 直传文件和结构化文档的统一索引。</p>
 *
 * @author zsg
 * @since 2026-03-27
 */
public class DefaultDatastoreKnowledgeBaseProvisioner implements DatastoreKnowledgeBaseProvisioner {

    private static final Logger log = LoggerFactory.getLogger(DefaultDatastoreKnowledgeBaseProvisioner.class);

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeBaseManager knowledgeBaseManager;

    public DefaultDatastoreKnowledgeBaseProvisioner(KnowledgeBaseRepository knowledgeBaseRepository,
                                                    KnowledgeBaseManager knowledgeBaseManager) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.knowledgeBaseManager = knowledgeBaseManager;
    }

    @Override
    public String ensureDefaultKnowledgeBase(Collection collection) {
        if (collection.defaultKnowledgeBaseId() != null && !collection.defaultKnowledgeBaseId().isBlank()) {
            var existing = knowledgeBaseRepository.findById(collection.defaultKnowledgeBaseId().strip());
            if (existing.isPresent()) {
                knowledgeBaseManager.ensureDatastoreAssociation(existing.get().id(), collection.id());
                return existing.get().id();
            }
        }

        var created = knowledgeBaseManager.createSystemManagedKnowledgeBase(
                collection.id(),
                collection.name(),
                null,
                null
        );
        log.info("Datastore 默认内部知识库已创建: datastoreId={}, knowledgeBaseId={}",
                collection.id(), created.id());
        return created.id();
    }

    @Override
    public void deleteDefaultKnowledgeBase(Collection collection) {
        String defaultKnowledgeBaseId = collection.defaultKnowledgeBaseId();
        if (defaultKnowledgeBaseId == null || defaultKnowledgeBaseId.isBlank()) {
            defaultKnowledgeBaseId = knowledgeBaseRepository.findSystemManagedByOwnerDatastoreId(collection.id())
                    .map(kb -> kb.id())
                    .orElse(null);
        }
        if (defaultKnowledgeBaseId == null || defaultKnowledgeBaseId.isBlank()) {
            return;
        }
        knowledgeBaseManager.deleteKnowledgeBase(defaultKnowledgeBaseId);
        log.info("Datastore 默认内部知识库已删除: datastoreId={}, knowledgeBaseId={}",
                collection.id(), defaultKnowledgeBaseId);
    }
}
