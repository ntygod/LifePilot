package com.lifepilot.knowledge.sync;

import com.lifepilot.knowledge.KnowledgeBaseManager;
import com.lifepilot.knowledge.ingest.DocumentIngester;
import com.lifepilot.knowledge.model.DocumentSourceType;
import com.lifepilot.knowledge.model.DocumentStatus;
import com.lifepilot.knowledge.repository.DocumentRepository;
import com.lifepilot.knowledge.repository.KnowledgeBaseDatastoreRepository;
import com.lifepilot.knowledge.repository.KnowledgeSyncJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 知识同步任务 worker。
 *
 * @author zsg
 * @since 2026-03-27
 */
public class KnowledgeSyncWorker {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSyncWorker.class);

    private final KnowledgeSyncJobRepository knowledgeSyncJobRepository;
    private final KnowledgeBaseDatastoreRepository knowledgeBaseDatastoreRepository;
    private final com.lifepilot.datastore.repository.CollectionRepository datastoreCollectionRepository;
    private final com.lifepilot.datastore.repository.DocumentRepository datastoreDocumentRepository;
    private final DocumentRepository knowledgeDocumentRepository;
    private final KnowledgeBaseManager knowledgeBaseManager;
    private final DocumentIngester documentIngester;
    private final DatastoreDocumentProjector projector;

    public KnowledgeSyncWorker(
            KnowledgeSyncJobRepository knowledgeSyncJobRepository,
            KnowledgeBaseDatastoreRepository knowledgeBaseDatastoreRepository,
            com.lifepilot.datastore.repository.CollectionRepository datastoreCollectionRepository,
            com.lifepilot.datastore.repository.DocumentRepository datastoreDocumentRepository,
            DocumentRepository knowledgeDocumentRepository,
            KnowledgeBaseManager knowledgeBaseManager,
            DocumentIngester documentIngester,
            DatastoreDocumentProjector projector) {
        this.knowledgeSyncJobRepository = knowledgeSyncJobRepository;
        this.knowledgeBaseDatastoreRepository = knowledgeBaseDatastoreRepository;
        this.datastoreCollectionRepository = datastoreCollectionRepository;
        this.datastoreDocumentRepository = datastoreDocumentRepository;
        this.knowledgeDocumentRepository = knowledgeDocumentRepository;
        this.knowledgeBaseManager = knowledgeBaseManager;
        this.documentIngester = documentIngester;
        this.projector = projector;
    }

    /**
     * 定时拉取并处理同步任务。
     */
    @Scheduled(fixedDelayString = "${lifepilot.knowledge.sync.fixed-delay-ms:5000}")
    public void processAvailableJobs() {
        for (KnowledgeSyncJob job : knowledgeSyncJobRepository.findAvailableJobs(20)) {
            if (!knowledgeSyncJobRepository.markProcessing(job.id())) {
                continue;
            }
            try {
                handleJob(job);
                knowledgeSyncJobRepository.markCompleted(job.id());
            } catch (Exception e) {
                int nextAttempt = job.attemptCount() + 1;
                Instant nextAvailableAt = Instant.now().plusSeconds(Math.min(300, 5L * nextAttempt));
                knowledgeSyncJobRepository.markFailed(job.id(), e.getMessage(), nextAttempt, nextAvailableAt);
                log.warn("知识同步任务处理失败: jobId={}, type={}, error={}",
                        job.id(), job.jobType(), e.getMessage(), e);
            }
        }
    }

    private void handleJob(KnowledgeSyncJob job) {
        switch (job.jobType()) {
            case UPSERT_DATASTORE_DOCUMENT -> handleUpsertJob(job);
            case DELETE_DATASTORE_DOCUMENT -> handleDeleteJob(job);
            case RESYNC_DATASTORE -> handleResyncJob(job);
            case PURGE_DATASTORE -> handlePurgeJob(job);
        }
    }

    private void handleUpsertJob(KnowledgeSyncJob job) {
        if (!isMounted(job.knowledgeBaseId(), job.datastoreId())) {
            handleDeleteJob(job);
            return;
        }
        String sourceKey = requireSourceKey(job);
        String content = requireString(job.payload(), "content");
        String sourceCollectionId = optionalString(job.payload(), "sourceCollectionId").orElse(job.datastoreId());
        upsertProjectedDocument(
                job.knowledgeBaseId(),
                job.datastoreId(),
                sourceCollectionId,
                sourceKey,
                job.sourceVersion(),
                optionalString(job.payload(), "fileName").orElse(sourceKey),
                optionalString(job.payload(), "filePath").orElse("datastore://" + job.datastoreId()),
                content,
                extractSourceRef(job.payload())
        );
        log.info("Datastore 文档同步完成: jobId={}, datastoreId={}, knowledgeBaseId={}, sourceKey={}",
                job.id(), job.datastoreId(), job.knowledgeBaseId(), sourceKey);
    }

    private void handleDeleteJob(KnowledgeSyncJob job) {
        if (job.sourceKey() == null || job.sourceKey().isBlank()) {
            return;
        }
        Optional<com.lifepilot.knowledge.model.Document> existingOpt =
                knowledgeDocumentRepository.findByKnowledgeBaseIdAndSourceKey(job.knowledgeBaseId(), job.sourceKey());
        if (existingOpt.isEmpty()) {
            return;
        }
        var existing = existingOpt.get();
        if (existing.sourceType() != DocumentSourceType.DATASTORE_DOCUMENT) {
            log.warn("跳过删除非 datastore 同步文档: knowledgeBaseId={}, documentId={}, sourceKey={}",
                    job.knowledgeBaseId(), existing.id(), job.sourceKey());
            return;
        }
        if (isNewerThanJob(existing, job.sourceVersion())) {
            log.debug("跳过过期删除任务: knowledgeBaseId={}, sourceKey={}, jobVersion={}, existingUpdatedAt={}",
                    job.knowledgeBaseId(), job.sourceKey(), job.sourceVersion(), existing.updatedAt());
            return;
        }
        knowledgeBaseManager.removeDocument(existing.id());
        log.info("Datastore 同步文档删除完成: jobId={}, datastoreId={}, knowledgeBaseId={}, sourceKey={}",
                job.id(), job.datastoreId(), job.knowledgeBaseId(), job.sourceKey());
    }

    private void handleResyncJob(KnowledgeSyncJob job) {
        if (!isMounted(job.knowledgeBaseId(), job.datastoreId())) {
            handlePurgeJob(job);
            return;
        }
        var collection = datastoreCollectionRepository.findById(job.datastoreId())
                .orElseThrow(() -> new IllegalStateException("Datastore 不存在: id=" + job.datastoreId()));
        List<com.lifepilot.datastore.model.Document> datastoreDocuments =
                datastoreDocumentRepository.findByCollectionId(job.datastoreId());
        Set<String> activeSourceKeys = new HashSet<>();

        for (com.lifepilot.datastore.model.Document datastoreDocument : datastoreDocuments) {
            String sourceKey = sourceKey(job.datastoreId(), datastoreDocument.id());
            activeSourceKeys.add(sourceKey);
            var projected = projector.project(collection, datastoreDocument);
            upsertProjectedDocument(
                    job.knowledgeBaseId(),
                    job.datastoreId(),
                    collection.id(),
                    sourceKey,
                    datastoreDocument.updatedAt(),
                    projected.fileName(),
                    projected.filePath(),
                    projected.content(),
                    projected.sourceRef()
            );
        }

        List<com.lifepilot.knowledge.model.Document> syncedDocuments =
                knowledgeDocumentRepository.findByKnowledgeBaseIdAndSourceDatastoreIdAndSourceType(
                        job.knowledgeBaseId(),
                        job.datastoreId(),
                        DocumentSourceType.DATASTORE_DOCUMENT
                );
        for (com.lifepilot.knowledge.model.Document syncedDocument : syncedDocuments) {
            if (!activeSourceKeys.contains(syncedDocument.sourceKey())) {
                knowledgeBaseManager.removeDocument(syncedDocument.id());
            }
        }
        log.info("Datastore 重同步完成: jobId={}, datastoreId={}, knowledgeBaseId={}, activeSourceCount={}",
                job.id(), job.datastoreId(), job.knowledgeBaseId(), activeSourceKeys.size());
    }

    private void handlePurgeJob(KnowledgeSyncJob job) {
        List<com.lifepilot.knowledge.model.Document> syncedDocuments =
                knowledgeDocumentRepository.findByKnowledgeBaseIdAndSourceDatastoreIdAndSourceType(
                        job.knowledgeBaseId(),
                        job.datastoreId(),
                        DocumentSourceType.DATASTORE_DOCUMENT
                );
        for (com.lifepilot.knowledge.model.Document syncedDocument : syncedDocuments) {
            knowledgeBaseManager.removeDocument(syncedDocument.id());
        }
        log.info("Datastore 清理完成: jobId={}, datastoreId={}, knowledgeBaseId={}, removedCount={}",
                job.id(), job.datastoreId(), job.knowledgeBaseId(), syncedDocuments.size());
    }

    private void upsertProjectedDocument(String knowledgeBaseId,
                                         String datastoreId,
                                         String sourceCollectionId,
                                         String sourceKey,
                                         String sourceVersion,
                                         String fileName,
                                         String filePath,
                                         String content,
                                         Map<String, Object> sourceRef) {
        Optional<com.lifepilot.knowledge.model.Document> existingOpt =
                knowledgeDocumentRepository.findByKnowledgeBaseIdAndSourceKey(knowledgeBaseId, sourceKey);
        if (existingOpt.isPresent()) {
            var existing = existingOpt.get();
            if (existing.sourceType() != DocumentSourceType.DATASTORE_DOCUMENT) {
                log.warn("跳过覆盖非 datastore 同步文档: knowledgeBaseId={}, documentId={}, sourceKey={}",
                        knowledgeBaseId, existing.id(), sourceKey);
                return;
            }
            if (isNotOlder(existing, sourceVersion)) {
                return;
            }
            knowledgeBaseManager.removeDocument(existing.id());
        }

        Instant versionTime = parseInstant(sourceVersion).orElse(Instant.now());
        var metadata = new LinkedHashMap<String, String>();
        metadata.put("syncSource", "datastore");

        var doc = new com.lifepilot.knowledge.model.Document(
                existingOpt.map(com.lifepilot.knowledge.model.Document::id).orElse(UUID.randomUUID().toString()),
                knowledgeBaseId,
                fileName,
                filePath,
                content.getBytes(StandardCharsets.UTF_8).length,
                "application/vnd.lifepilot.datastore+json",
                sha256(content),
                DocumentStatus.UPLOADING,
                0,
                0,
                null,
                null,
                Map.copyOf(metadata),
                existingOpt.map(com.lifepilot.knowledge.model.Document::createdAt).orElse(versionTime),
                versionTime,
                DocumentSourceType.DATASTORE_DOCUMENT,
                sourceKey,
                datastoreId,
                sourceCollectionId,
                sourceRef
        );
        knowledgeDocumentRepository.save(doc);
        documentIngester.ingestProjectedDocument(doc, content);
        log.info("Datastore 投影文档已写入知识库: datastoreId={}, knowledgeBaseId={}, documentId={}, chunkSource={}",
                datastoreId, knowledgeBaseId, doc.id(), sourceKey);
    }

    private boolean isMounted(String knowledgeBaseId, String datastoreId) {
        return knowledgeBaseDatastoreRepository.findDatastoreIdsByKnowledgeBaseId(knowledgeBaseId)
                .contains(datastoreId);
    }

    private boolean isNotOlder(com.lifepilot.knowledge.model.Document existing, String sourceVersion) {
        Optional<Instant> versionTime = parseInstant(sourceVersion);
        return versionTime.isPresent() && !existing.updatedAt().isBefore(versionTime.get());
    }

    private boolean isNewerThanJob(com.lifepilot.knowledge.model.Document existing, String sourceVersion) {
        Optional<Instant> versionTime = parseInstant(sourceVersion);
        return versionTime.isPresent() && existing.updatedAt().isAfter(versionTime.get());
    }

    private Optional<Instant> parseInstant(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(rawValue));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String requireSourceKey(KnowledgeSyncJob job) {
        if (job.sourceKey() == null || job.sourceKey().isBlank()) {
            throw new IllegalArgumentException("同步任务缺少 sourceKey: jobId=" + job.id());
        }
        return job.sourceKey();
    }

    private String requireString(Map<String, Object> payload, String key) {
        return optionalString(payload, key)
                .orElseThrow(() -> new IllegalArgumentException("同步任务缺少字段: " + key));
    }

    private Optional<String> optionalString(Map<String, Object> payload, String key) {
        Object rawValue = payload.get(key);
        if (rawValue instanceof String value && !value.isBlank()) {
            return Optional.of(value);
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractSourceRef(Map<String, Object> payload) {
        Object rawValue = payload.get("sourceRef");
        if (rawValue instanceof Map<?, ?> rawMap) {
            var sourceRef = new LinkedHashMap<String, Object>();
            rawMap.forEach((key, value) -> {
                if (key instanceof String stringKey) {
                    sourceRef.put(stringKey, value);
                }
            });
            return Map.copyOf(sourceRef);
        }
        return Map.of();
    }

    private String sourceKey(String datastoreId, String documentId) {
        return "DATASTORE:" + datastoreId + ":" + documentId;
    }

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            var builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException("计算内容哈希失败", e);
        }
    }
}
