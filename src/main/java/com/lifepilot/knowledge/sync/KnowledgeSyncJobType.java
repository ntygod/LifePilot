package com.lifepilot.knowledge.sync;

/**
 * 知识同步任务类型。
 *
 * @author zsg
 * @since 2026-03-26
 */
public enum KnowledgeSyncJobType {
    UPSERT_DATASTORE_DOCUMENT,
    DELETE_DATASTORE_DOCUMENT,
    RESYNC_DATASTORE,
    PURGE_DATASTORE
}
