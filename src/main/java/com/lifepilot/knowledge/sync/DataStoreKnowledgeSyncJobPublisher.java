package com.lifepilot.knowledge.sync;

import com.lifepilot.datastore.model.Collection;
import com.lifepilot.datastore.model.Document;
import com.lifepilot.datastore.repository.CollectionRepository;
import com.lifepilot.datastore.sync.DataStoreKnowledgeSyncPublisher;
import com.lifepilot.knowledge.repository.KnowledgeBaseDatastoreRepository;
import com.lifepilot.knowledge.repository.KnowledgeSyncJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Datastore 变更同步任务发布器。
 *
 * @author zsg
 * @since 2026-03-27
 */
public class DataStoreKnowledgeSyncJobPublisher implements DataStoreKnowledgeSyncPublisher {

    private static final Logger log = LoggerFactory.getLogger(DataStoreKnowledgeSyncJobPublisher.class);

    private final KnowledgeBaseDatastoreRepository knowledgeBaseDatastoreRepository;
    private final KnowledgeSyncJobRepository knowledgeSyncJobRepository;
    private final CollectionRepository collectionRepository;
    private final DatastoreDocumentProjector projector;

    public DataStoreKnowledgeSyncJobPublisher(
            KnowledgeBaseDatastoreRepository knowledgeBaseDatastoreRepository,
            KnowledgeSyncJobRepository knowledgeSyncJobRepository,
            CollectionRepository collectionRepository,
            DatastoreDocumentProjector projector) {
        this.knowledgeBaseDatastoreRepository = knowledgeBaseDatastoreRepository;
        this.knowledgeSyncJobRepository = knowledgeSyncJobRepository;
        this.collectionRepository = collectionRepository;
        this.projector = projector;
    }

    @Override
    public void publishDocumentUpsert(Collection collection, Document document) {
        List<String> knowledgeBaseIds = knowledgeBaseDatastoreRepository.findKnowledgeBaseIdsByDatastoreId(collection.id());
        if (knowledgeBaseIds.isEmpty()) {
            log.info("Datastore 文档变更未触发知识同步: datastoreId={}, documentId={}, reason=未挂载知识库",
                    collection.id(), document.id());
            return;
        }

        var projected = projector.project(collection, document);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("documentId", document.id());
        payload.put("sourceCollectionId", collection.id());
        payload.put("fileName", projected.fileName());
        payload.put("filePath", projected.filePath());
        payload.put("content", projected.content());
        payload.put("sourceRef", projected.sourceRef());

        enqueueForKnowledgeBases(
                KnowledgeSyncJobType.UPSERT_DATASTORE_DOCUMENT,
                knowledgeBaseIds,
                collection.id(),
                sourceKey(collection.id(), document.id()),
                document.updatedAt(),
                payload
        );
        log.info("Datastore 文档已发布同步任务: datastoreId={}, documentId={}, knowledgeBaseCount={}",
                collection.id(), document.id(), knowledgeBaseIds.size());
    }

    @Override
    public void publishDocumentDelete(String datastoreId, String documentId, @Nullable String sourceVersion) {
        List<String> knowledgeBaseIds = knowledgeBaseDatastoreRepository.findKnowledgeBaseIdsByDatastoreId(datastoreId);
        if (knowledgeBaseIds.isEmpty()) {
            log.info("Datastore 文档删除未触发知识同步: datastoreId={}, documentId={}, reason=未挂载知识库",
                    datastoreId, documentId);
            return;
        }
        enqueueForKnowledgeBases(
                KnowledgeSyncJobType.DELETE_DATASTORE_DOCUMENT,
                knowledgeBaseIds,
                datastoreId,
                sourceKey(datastoreId, documentId),
                sourceVersion,
                Map.of("documentId", documentId)
        );
        log.info("Datastore 文档删除已发布同步任务: datastoreId={}, documentId={}, knowledgeBaseCount={}",
                datastoreId, documentId, knowledgeBaseIds.size());
    }

    @Override
    public void publishDatastoreResync(String datastoreId) {
        if (collectionRepository.findById(datastoreId).isEmpty()) {
            return;
        }
        List<String> knowledgeBaseIds = knowledgeBaseDatastoreRepository.findKnowledgeBaseIdsByDatastoreId(datastoreId);
        enqueueForKnowledgeBases(
                KnowledgeSyncJobType.RESYNC_DATASTORE,
                knowledgeBaseIds,
                datastoreId,
                null,
                null,
                Map.of()
        );
        log.info("Datastore 重同步已发布任务: datastoreId={}, knowledgeBaseCount={}",
                datastoreId, knowledgeBaseIds.size());
    }

    @Override
    public void publishDatastorePurge(String datastoreId) {
        List<String> knowledgeBaseIds = knowledgeBaseDatastoreRepository.findKnowledgeBaseIdsByDatastoreId(datastoreId);
        if (knowledgeBaseIds.isEmpty()) {
            log.info("Datastore 清理未触发知识同步: datastoreId={}, reason=未挂载知识库", datastoreId);
            return;
        }
        enqueueForKnowledgeBases(
                KnowledgeSyncJobType.PURGE_DATASTORE,
                knowledgeBaseIds,
                datastoreId,
                null,
                null,
                Map.of()
        );
        log.info("Datastore 清理已发布任务: datastoreId={}, knowledgeBaseCount={}",
                datastoreId, knowledgeBaseIds.size());
    }

    private void enqueueForKnowledgeBases(KnowledgeSyncJobType jobType,
                                          List<String> knowledgeBaseIds,
                                          String datastoreId,
                                          @Nullable String sourceKey,
                                          @Nullable String sourceVersion,
                                          Map<String, Object> payload) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return;
        }
        for (String knowledgeBaseId : knowledgeBaseIds) {
            knowledgeSyncJobRepository.enqueue(
                    jobType,
                    knowledgeBaseId,
                    datastoreId,
                    sourceKey,
                    sourceVersion,
                    payload
            );
        }
        log.debug("知识同步任务已发布: type={}, datastoreId={}, knowledgeBaseCount={}",
                jobType, datastoreId, knowledgeBaseIds.size());
    }

    private String sourceKey(String datastoreId, String documentId) {
        return "DATASTORE:" + datastoreId + ":" + documentId;
    }
}
