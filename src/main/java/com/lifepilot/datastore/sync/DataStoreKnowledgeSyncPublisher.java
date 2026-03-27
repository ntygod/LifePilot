package com.lifepilot.datastore.sync;

import com.lifepilot.datastore.model.Collection;
import com.lifepilot.datastore.model.Document;
import org.springframework.lang.Nullable;

/**
 * Datastore 变更到知识库同步发布器。
 *
 * @author zsg
 * @since 2026-03-26
 */
public interface DataStoreKnowledgeSyncPublisher {

    /**
     * 文档新增或更新后发布同步任务。
     */
    void publishDocumentUpsert(Collection collection, Document document);

    /**
     * 文档删除后发布同步任务。
     */
    void publishDocumentDelete(String datastoreId, String documentId, @Nullable String sourceVersion);

    /**
     * Datastore 全量重建同步任务。
     */
    void publishDatastoreResync(String datastoreId);

    /**
     * Datastore 删除后发布清理任务。
     */
    void publishDatastorePurge(String datastoreId);
}
