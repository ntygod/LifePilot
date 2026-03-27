package com.lifepilot.datastore.sync;

import com.lifepilot.datastore.model.Collection;

/**
 * Datastore 默认知识库编排器。
 *
 * <p>负责为 Datastore 自动确保内部知识库存在，并在删除 Datastore 时清理对应的内部知识库。</p>
 *
 * @author zsg
 * @since 2026-03-27
 */
public interface DatastoreKnowledgeBaseProvisioner {

    /**
     * 确保 Datastore 拥有默认内部知识库。
     *
     * @param collection Datastore 集合
     * @return 默认内部知识库 ID
     */
    String ensureDefaultKnowledgeBase(Collection collection);

    /**
     * 删除 Datastore 对应的默认内部知识库。
     *
     * @param collection Datastore 集合
     */
    void deleteDefaultKnowledgeBase(Collection collection);
}
