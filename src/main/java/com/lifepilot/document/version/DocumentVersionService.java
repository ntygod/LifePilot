package com.lifepilot.document.version;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.lifepilot.document.model.DocumentVersionRecord;
import com.lifepilot.document.model.SessionDocumentRecord;
import com.lifepilot.document.patch.DocumentPatchOperation;
import com.lifepilot.document.patch.DocumentPatchResult;
import com.lifepilot.document.patch.DocxPatchOperation;
import com.lifepilot.document.patch.docx.DocxDiffBuilder;
import com.lifepilot.document.patch.docx.DocxPatchEngine;
import com.lifepilot.document.repository.DocumentVersionRepository;
import com.lifepilot.document.repository.SessionDocumentRepository;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.meta.infra.file.PathSecurityChecker;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * 文档版本服务 —— P3 核心编排。
 *
 * <p>完整生命周期：checkout（首次引用源）→ applyPatch（多次）→
 * commit（覆盖原路径 / 另存） / rollback / discard。</p>
 *
 * <p>与 Phase 2 的关系：</p>
 * <ul>
 *   <li>checkout 会为 {@link com.lifepilot.document.version.SourceRef.PathSource} 和
 *       {@link com.lifepilot.document.version.SourceRef.AttachmentSource} 首次引用
 *       在 {@code session_documents} 插入新行；</li>
 *   <li>{@link com.lifepilot.document.version.SourceRef.DocumentSource} 若对应记录
 *       {@code latest_version == 0}，会将其原始 file_path 复制为 working/v0，
 *       之后 file_path 切换到 working 路径。</li>
 * </ul>
 *
 * <p>本类不标 {@code @Service}：构造器依赖 {@code storageDir} 字符串，
 * 由 {@code DocumentAutoConfiguration}（Task 12）显式 {@code @Bean} 装配。</p>
 *
 * @author zsg
 * @since 2026-04-21
 */
public class DocumentVersionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentVersionService.class);

    private static final DateTimeFormatter BACKUP_TS =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.systemDefault());

    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final SessionDocumentRepository documentRepository;
    private final DocumentVersionRepository versionRepository;
    private final AttachmentRepository attachmentRepository;
    private final DocxPatchEngine engine;
    private final DocxDiffBuilder diffBuilder;
    private final String storageDir;
    private final PathSecurityChecker pathSecurityChecker;

    public DocumentVersionService(SessionDocumentRepository documentRepository,
                                  DocumentVersionRepository versionRepository,
                                  AttachmentRepository attachmentRepository,
                                  DocxPatchEngine engine,
                                  DocxDiffBuilder diffBuilder,
                                  String storageDir,
                                  PathSecurityChecker pathSecurityChecker) {
        this.documentRepository = documentRepository;
        this.versionRepository = versionRepository;
        this.attachmentRepository = attachmentRepository;
        this.engine = engine;
        this.diffBuilder = diffBuilder;
        this.storageDir = storageDir;
        this.pathSecurityChecker = pathSecurityChecker;
    }

    // ===== checkout =====

    /**
     * 确保源文件有对应工作副本与 session_documents 行；返回 documentId。
     */
    public String checkout(String sessionId, SourceRef source) throws IOException {
        return switch (source) {
            case SourceRef.PathSource p -> checkoutFromPath(sessionId, p.path());
            case SourceRef.AttachmentSource a -> checkoutFromAttachment(sessionId, a.attachmentId());
            case SourceRef.DocumentSource d -> checkoutFromDocument(d.documentId());
        };
    }

    private String checkoutFromPath(String sessionId, String sourcePath) throws IOException {
        // 扩展名白名单：Phase 3A 仅支持 .docx，避免 LLM 注入让 AI copy .exe/.bat 等文件到 working 目录
        if (!sourcePath.toLowerCase().endsWith(".docx")) {
            throw new IllegalArgumentException("只支持 .docx 源文件（P3A）：" + sourcePath);
        }
        // 路径安全校验：读取场景（文件已存在），对齐 FileReadToolExecutor 的白名单/黑名单规则
        Path source = Paths.get(sourcePath);
        var rejection = pathSecurityChecker.check(source);
        if (rejection.isPresent()) {
            throw new SecurityException(rejection.get());
        }
        var existing = documentRepository.findBySessionAndSourcePath(sessionId, sourcePath);
        if (existing != null) {
            return existing.id();
        }
        String documentId = UUID.randomUUID().toString();
        Path workingV0 = workingPath(sessionId, documentId, 0);
        Files.createDirectories(workingV0.getParent());
        Files.copy(source, workingV0, StandardCopyOption.REPLACE_EXISTING);
        long size = Files.size(workingV0);
        String fileName = source.getFileName().toString();

        documentRepository.save(new SessionDocumentRecord(
                documentId, sessionId, null, fileName, workingV0.toString(), size,
                DOCX_MIME, SessionDocumentRecord.ORIGIN_USER_LOCAL_FILE,
                sourcePath, 0, Instant.now()));
        versionRepository.save(new DocumentVersionRecord(
                UUID.randomUUID().toString(), documentId, 0, workingV0.toString(),
                DocumentVersionRecord.SOURCE_INITIAL, null, null, Instant.now()));
        log.info("checkout 本机路径：documentId={}, sourcePath={}", documentId, sourcePath);
        return documentId;
    }

    private String checkoutFromAttachment(String sessionId, String attachmentId) throws IOException {
        var att = attachmentRepository.findById(attachmentId);
        if (att == null) {
            throw new IllegalArgumentException("attachment 不存在：" + attachmentId);
        }
        // 扩展名 / mime-type 白名单：Phase 3A 仅支持 docx，防止 LLM 注入非预期文件类型
        boolean extDocx = att.fileName() != null && att.fileName().toLowerCase().endsWith(".docx");
        boolean mimeDocx = DOCX_MIME.equals(att.mimeType());
        if (!extDocx && !mimeDocx) {
            throw new IllegalArgumentException("只支持 .docx 附件（P3A）：fileName=" + att.fileName()
                    + ", mimeType=" + att.mimeType());
        }
        // 附件本地路径即源；沿用 PathSource checkout 但 origin 区分
        String documentId = UUID.randomUUID().toString();
        Path sourceFile = Paths.get(att.filePath());
        Path workingV0 = workingPath(sessionId, documentId, 0);
        Files.createDirectories(workingV0.getParent());
        Files.copy(sourceFile, workingV0, StandardCopyOption.REPLACE_EXISTING);
        long size = Files.size(workingV0);

        documentRepository.save(new SessionDocumentRecord(
                documentId, sessionId, null, att.fileName(), workingV0.toString(), size,
                att.mimeType(), SessionDocumentRecord.ORIGIN_USER_ATTACHMENT_EDITED,
                null, 0, Instant.now()));
        versionRepository.save(new DocumentVersionRecord(
                UUID.randomUUID().toString(), documentId, 0, workingV0.toString(),
                DocumentVersionRecord.SOURCE_INITIAL, null, null, Instant.now()));
        log.info("checkout 附件：documentId={}, attachmentId={}", documentId, attachmentId);
        return documentId;
    }

    private String checkoutFromDocument(String documentId) throws IOException {
        var existing = documentRepository.findById(documentId);
        if (existing == null) {
            throw new IllegalArgumentException("document 不存在：" + documentId);
        }
        if (existing.latestVersion() > 0) {
            return documentId;
        }
        // 首次从 Phase 2 AI 产物 checkout：复制到 working/v0
        Path oldFile = Paths.get(existing.filePath());
        Path workingV0 = workingPath(existing.sessionId(), documentId, 0);
        Files.createDirectories(workingV0.getParent());
        Files.copy(oldFile, workingV0, StandardCopyOption.REPLACE_EXISTING);
        long size = Files.size(workingV0);

        documentRepository.updateFilePath(documentId, workingV0.toString(), size);
        versionRepository.save(new DocumentVersionRecord(
                UUID.randomUUID().toString(), documentId, 0, workingV0.toString(),
                DocumentVersionRecord.SOURCE_INITIAL, null, null, Instant.now()));
        log.info("checkout Phase2 产物：documentId={}", documentId);
        return documentId;
    }

    // ===== applyPatch =====

    public DocumentPatchResult applyPatch(String documentId, List<DocumentPatchOperation> ops)
            throws IOException, JsonProcessingException {
        var record = requireDocument(documentId);
        int nextVersion = record.latestVersion() + 1;
        Path currentFile = Paths.get(record.filePath());
        Path nextFile = workingPath(record.sessionId(), documentId, nextVersion);
        Files.createDirectories(nextFile.getParent());

        try (InputStream in = Files.newInputStream(currentFile);
             XWPFDocument doc = new XWPFDocument(in)) {
            // Task 1 桥接：engine.apply 已收窄为 List<DocxPatchOperation>，而 service 公共签名
            // 暂保留为 List<DocumentPatchOperation>（Task 8 会改为 MIME 分支）。P3A 链路当前只会传入
            // DocxPatchOperation 子类型，这里做一次 unchecked cast 让协议收窄不泄漏到调用方。
            @SuppressWarnings("unchecked")
            List<DocxPatchOperation> docxOps = (List<DocxPatchOperation>) (List<?>) ops;
            var engineResult = engine.apply(doc, docxOps);
            if (!engineResult.success()) {
                return DocumentPatchResult.failure(engineResult.failedOps());
            }
            byte[] bytes = serializeDocx(doc);
            Files.write(nextFile, bytes);

            String diffJson = diffBuilder.build(documentId, record.latestVersion(), nextVersion,
                    engineResult.appliedOps());
            String summary = diffBuilder.summarize(engineResult.appliedOps());

            versionRepository.save(new DocumentVersionRecord(
                    UUID.randomUUID().toString(), documentId, nextVersion, nextFile.toString(),
                    DocumentVersionRecord.SOURCE_PATCH, summary, diffJson, Instant.now()));
            documentRepository.updateLatestVersion(documentId, nextVersion);
            documentRepository.updateFilePath(documentId, nextFile.toString(), bytes.length);
            attachmentRepository.updateSizeByFilePath(nextFile.toString(), (long) bytes.length);

            log.info("patch 成功：documentId={}, version={}→{}, {}",
                    documentId, record.latestVersion(), nextVersion, summary);
            return DocumentPatchResult.success(nextVersion, diffJson, summary);
        }
    }

    // ===== commit =====

    public record CommitResult(String committedPath, String backupPath) {}

    /** overwrite：覆盖 sourcePath；另存：copy 到 saveAsPath。均返回实际落盘目标路径与 .bak（overwrite 时）。 */
    public CommitResult commitOverwrite(String documentId) throws IOException {
        var record = requireDocument(documentId);
        if (record.sourcePath() == null || record.sourcePath().isBlank()) {
            throw new IllegalStateException("此文档没有 sourcePath（非本机路径源），不能 overwrite");
        }
        // 路径安全校验：写入场景（覆盖已存在的源文件）；即便 sourcePath 入库时校验过，
        // 这里再校一次以防白名单配置变化或持久化后的路径被绕过
        Path target = Paths.get(record.sourcePath());
        var rejection = pathSecurityChecker.checkForWrite(target);
        if (rejection.isPresent()) {
            throw new SecurityException(rejection.get());
        }
        Path backup = Paths.get(record.sourcePath() + "." + BACKUP_TS.format(Instant.now()) + ".bak");
        if (Files.exists(target)) {
            Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.copy(Paths.get(record.filePath()), target, StandardCopyOption.REPLACE_EXISTING);
        log.info("commit overwrite：documentId={}, target={}, backup={}",
                documentId, target, backup);
        return new CommitResult(target.toString(), backup.toString());
    }

    public CommitResult commitSaveAs(String documentId, String saveAsPath) throws IOException {
        var record = requireDocument(documentId);
        // 路径安全校验：saveAsPath 由 LLM / 用户提供，必须校验以防 AI 被注入写入
        // 敏感路径（~/.ssh/authorized_keys、系统目录等）
        Path target = Paths.get(saveAsPath);
        var rejection = pathSecurityChecker.checkForWrite(target);
        if (rejection.isPresent()) {
            throw new SecurityException(rejection.get());
        }
        Files.createDirectories(target.getParent() == null ? Paths.get(".") : target.getParent());
        Files.copy(Paths.get(record.filePath()), target, StandardCopyOption.REPLACE_EXISTING);
        log.info("commit saveAs：documentId={}, target={}", documentId, target);
        return new CommitResult(target.toString(), null);
    }

    // ===== rollback =====

    public DocumentPatchResult rollback(String documentId, int targetVersion) throws IOException {
        var record = requireDocument(documentId);
        var targetVer = versionRepository.findByDocumentIdAndVersion(documentId, targetVersion);
        if (targetVer == null) {
            throw new IllegalArgumentException(
                    "目标版本不存在：documentId=" + documentId + ", version=" + targetVersion);
        }
        int nextVersion = record.latestVersion() + 1;
        Path nextFile = workingPath(record.sessionId(), documentId, nextVersion);
        Files.createDirectories(nextFile.getParent());
        Files.copy(Paths.get(targetVer.filePath()), nextFile, StandardCopyOption.REPLACE_EXISTING);
        long size = Files.size(nextFile);

        String summary = "回滚到版本 " + targetVersion;
        versionRepository.save(new DocumentVersionRecord(
                UUID.randomUUID().toString(), documentId, nextVersion, nextFile.toString(),
                DocumentVersionRecord.SOURCE_ROLLBACK, summary, null, Instant.now()));
        documentRepository.updateLatestVersion(documentId, nextVersion);
        documentRepository.updateFilePath(documentId, nextFile.toString(), size);
        attachmentRepository.updateSizeByFilePath(nextFile.toString(), size);

        log.info("rollback：documentId={}, to={}", documentId, targetVersion);
        return DocumentPatchResult.success(nextVersion, null, summary);
    }

    // ===== discard / list =====

    public void discard(String documentId) throws IOException {
        var record = requireDocument(documentId);
        if (record.latestVersion() == 0) {
            throw new IllegalStateException("文档无工作副本（latestVersion=0），不可丢弃");
        }
        Path workingDir = Paths.get(storageDir, record.sessionId(), "working", documentId);
        if (Files.exists(workingDir)) {
            Files.walk(workingDir)
                    .sorted((a, b) -> b.toString().length() - a.toString().length())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            log.warn("删除工作副本文件失败：{}", p, e);
                        }
                    });
        }
        versionRepository.deleteByDocumentId(documentId);
        documentRepository.deleteById(documentId);
        log.info("discard：documentId={}", documentId);
    }

    public List<DocumentVersionRecord> listVersions(String documentId) {
        requireDocument(documentId);
        return versionRepository.findByDocumentId(documentId);
    }

    // ===== 辅助 =====

    private SessionDocumentRecord requireDocument(String documentId) {
        var record = documentRepository.findById(documentId);
        if (record == null) {
            throw new IllegalArgumentException("document 不存在：" + documentId);
        }
        return record;
    }

    private Path workingPath(String sessionId, String documentId, int version) {
        return Paths.get(storageDir, sessionId, "working", documentId, "v" + version + ".docx");
    }

    private byte[] serializeDocx(XWPFDocument doc) throws IOException {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            doc.write(bos);
            return bos.toByteArray();
        }
    }
}
