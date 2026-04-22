package com.lifepilot.document.version;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.document.model.DocumentVersionRecord;
import org.springframework.lang.Nullable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.lifepilot.document.model.SessionDocumentRecord;
import com.lifepilot.document.patch.DocumentPatchOperation;
import com.lifepilot.document.patch.DocumentPatchResult;
import com.lifepilot.document.patch.DocxPatchOperation;
import com.lifepilot.document.patch.XlsxPatchOperation;
import com.lifepilot.document.patch.docx.DocxDiffBuilder;
import com.lifepilot.document.patch.docx.DocxPatchEngine;
import com.lifepilot.document.patch.xlsx.XlsxDiffBuilder;
import com.lifepilot.document.patch.xlsx.XlsxPatchEngine;
import com.lifepilot.document.repository.DocumentVersionRepository;
import com.lifepilot.document.repository.SessionDocumentRepository;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.meta.infra.file.PathSecurityChecker;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <p>P3B 起支持 docx / xlsx 双格式：{@link #applyPatch} 按 {@link SessionDocumentRecord#mimeType()}
 * 分支到 docx / xlsx engine；跨 MIME 混用的 op 由 {@link #castDocxOps} / {@link #castXlsxOps}
 * 运行时 instanceof 校验拦截（抛 {@link IllegalArgumentException}）。</p>
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

    public static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    public static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** commit overwrite 自动备份的保留份数；超出则按时间戳最旧的优先删除。 */
    private static final int BACKUP_RETENTION_COUNT = 5;

    /** 同文件多次 backup 命名：{sourceFileName}.{yyyyMMddHHmmss}.bak —— 精确匹配自动备份，不误伤手工命名的 .bak。 */
    private static final java.util.regex.Pattern BACKUP_FILENAME =
            java.util.regex.Pattern.compile("^.+\\.(\\d{14})\\.bak$");

    /** rollback / diff JSON 序列化复用实例；Jackson ObjectMapper 线程安全。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 聚合 3 个 repository，避免构造器膨胀；上层装配/测试只需构造一次 record。 */
    public record DocumentRepositories(
            SessionDocumentRepository documents,
            DocumentVersionRepository versions,
            AttachmentRepository attachments) {}

    /** 聚合 docx / xlsx 两组 patch engine + diff builder，避免构造器膨胀。 */
    public record PatchEngines(
            DocxPatchEngine docxEngine,
            DocxDiffBuilder docxDiffBuilder,
            XlsxPatchEngine xlsxEngine,
            XlsxDiffBuilder xlsxDiffBuilder) {}

    private final SessionDocumentRepository documentRepository;
    private final DocumentVersionRepository versionRepository;
    private final AttachmentRepository attachmentRepository;
    private final DocxPatchEngine engine;
    private final DocxDiffBuilder diffBuilder;
    private final XlsxPatchEngine xlsxEngine;
    private final XlsxDiffBuilder xlsxDiffBuilder;
    private final String storageDir;
    private final PathSecurityChecker pathSecurityChecker;
    /** P2-11：DB 写入事务模板；null 时降级为非事务写入（向后兼容既有测试，不强制依赖）。 */
    @Nullable
    private final TransactionTemplate transactionTemplate;

    public DocumentVersionService(DocumentRepositories repositories,
                                  PatchEngines engines,
                                  String storageDir,
                                  PathSecurityChecker pathSecurityChecker) {
        this(repositories, engines, storageDir, pathSecurityChecker, null);
    }

    public DocumentVersionService(DocumentRepositories repositories,
                                  PatchEngines engines,
                                  String storageDir,
                                  PathSecurityChecker pathSecurityChecker,
                                  @Nullable PlatformTransactionManager transactionManager) {
        this.documentRepository = repositories.documents();
        this.versionRepository = repositories.versions();
        this.attachmentRepository = repositories.attachments();
        this.engine = engines.docxEngine();
        this.diffBuilder = engines.docxDiffBuilder();
        this.xlsxEngine = engines.xlsxEngine();
        this.xlsxDiffBuilder = engines.xlsxDiffBuilder();
        this.storageDir = storageDir;
        this.pathSecurityChecker = pathSecurityChecker;
        this.transactionTemplate = transactionManager != null
                ? new TransactionTemplate(transactionManager)
                : null;
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
        // 扩展名白名单：Phase 3B 支持 .docx / .xlsx，避免 LLM 注入让 AI copy .exe/.bat 等文件到 working 目录
        String lower = sourcePath.toLowerCase();
        boolean supportedExt = lower.endsWith(".docx") || lower.endsWith(".xlsx");
        if (!supportedExt) {
            throw new IllegalArgumentException("只支持 .docx / .xlsx 源文件：" + sourcePath);
        }
        String inferredMime = lower.endsWith(".xlsx") ? XLSX_MIME : DOCX_MIME;
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
        Path workingV0 = workingPath(sessionId, documentId, 0, inferredMime);
        Files.createDirectories(workingV0.getParent());
        Files.copy(source, workingV0, StandardCopyOption.REPLACE_EXISTING);
        long size = Files.size(workingV0);
        String fileName = source.getFileName().toString();

        // P2-12：同 session 并发 check → save race 条件处理。V14 的 (session_id, source_path)
        // UNIQUE 会让后到者 DataIntegrityViolationException；捕获后退回到 findBySessionAndSourcePath
        // 拿已存在行的 documentId，达到"同 session 幂等"语义。
        try {
            documentRepository.save(new SessionDocumentRecord(
                    documentId, sessionId, null, fileName, workingV0.toString(), size,
                    inferredMime, SessionDocumentRecord.ORIGIN_USER_LOCAL_FILE,
                    sourcePath, 0, Instant.now()));
            versionRepository.save(new DocumentVersionRecord(
                    UUID.randomUUID().toString(), documentId, 0, workingV0.toString(),
                    DocumentVersionRecord.SOURCE_INITIAL, null, null, Instant.now()));
            log.info("checkout 本机路径：documentId={}, sourcePath={}, mime={}",
                    documentId, sourcePath, inferredMime);
            return documentId;
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // 并发 race：另一个线程已经先 save 了。删掉本线程创建的 workingV0 副本避免孤儿，
            // 然后返回已存在记录的 id 达成幂等语义
            try {
                Files.deleteIfExists(workingV0);
            } catch (IOException io) {
                log.warn("并发 race 后清理 workingV0 失败：{}", workingV0, io);
            }
            var winner = documentRepository.findBySessionAndSourcePath(sessionId, sourcePath);
            if (winner != null) {
                log.info("checkoutFromPath 并发 race 退让：sessionId={}, sourcePath={}, winnerId={}",
                        sessionId, sourcePath, winner.id());
                return winner.id();
            }
            throw e;
        }
    }

    private String checkoutFromAttachment(String sessionId, String attachmentId) throws IOException {
        var att = attachmentRepository.findById(attachmentId);
        if (att == null) {
            throw new IllegalArgumentException("attachment 不存在：" + attachmentId);
        }
        // 扩展名 / mime-type 白名单：Phase 3B 支持 docx / xlsx，防止 LLM 注入非预期文件类型
        String name = att.fileName() == null ? "" : att.fileName().toLowerCase();
        boolean extDocx = name.endsWith(".docx");
        boolean extXlsx = name.endsWith(".xlsx");
        boolean mimeDocx = DOCX_MIME.equals(att.mimeType());
        boolean mimeXlsx = XLSX_MIME.equals(att.mimeType());
        if (!(extDocx || extXlsx || mimeDocx || mimeXlsx)) {
            throw new IllegalArgumentException(
                    "只支持 .docx / .xlsx 附件：fileName=" + att.fileName() + ", mimeType=" + att.mimeType());
        }
        String inferredMime = (extXlsx || mimeXlsx) ? XLSX_MIME : DOCX_MIME;
        // 附件本地路径即源；沿用 PathSource checkout 但 origin 区分
        String documentId = UUID.randomUUID().toString();
        Path sourceFile = Paths.get(att.filePath());
        Path workingV0 = workingPath(sessionId, documentId, 0, inferredMime);
        Files.createDirectories(workingV0.getParent());
        Files.copy(sourceFile, workingV0, StandardCopyOption.REPLACE_EXISTING);
        long size = Files.size(workingV0);

        documentRepository.save(new SessionDocumentRecord(
                documentId, sessionId, null, att.fileName(), workingV0.toString(), size,
                inferredMime, SessionDocumentRecord.ORIGIN_USER_ATTACHMENT_EDITED,
                null, 0, Instant.now()));
        versionRepository.save(new DocumentVersionRecord(
                UUID.randomUUID().toString(), documentId, 0, workingV0.toString(),
                DocumentVersionRecord.SOURCE_INITIAL, null, null, Instant.now()));
        log.info("checkout 附件：documentId={}, attachmentId={}, mime={}",
                documentId, attachmentId, inferredMime);
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
        Path workingV0 = workingPath(existing.sessionId(), documentId, 0, existing.mimeType());
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
        return switch (record.mimeType()) {
            case DOCX_MIME -> applyDocxPatch(record, castDocxOps(ops));
            case XLSX_MIME -> applyXlsxPatch(record, castXlsxOps(ops));
            default -> throw new IllegalStateException("不支持的 MIME：" + record.mimeType());
        };
    }

    private DocumentPatchResult applyDocxPatch(SessionDocumentRecord record,
                                               List<DocxPatchOperation> ops)
            throws IOException, JsonProcessingException {
        int nextVersion = record.latestVersion() + 1;
        Path currentFile = Paths.get(record.filePath());
        Path nextFile = workingPath(record, nextVersion);
        Files.createDirectories(nextFile.getParent());

        try (InputStream in = Files.newInputStream(currentFile);
             XWPFDocument doc = new XWPFDocument(in)) {
            var engineResult = engine.apply(doc, ops);
            if (!engineResult.success()) {
                return DocumentPatchResult.failure(engineResult.failedOps());
            }
            byte[] bytes = serializeDocx(doc);
            Files.write(nextFile, bytes);

            String diffJson = diffBuilder.build(record.id(), record.latestVersion(), nextVersion,
                    engineResult.appliedOps());
            String summary = diffBuilder.summarize(engineResult.appliedOps());

            // P2-11：4 个 DB 写入包在事务里；事务失败则删除已落盘的 nextFile 回滚
            persistPatchTransactional(record.id(), nextVersion, nextFile, bytes.length, summary, diffJson);

            log.info("docx patch 成功：documentId={}, version={}→{}, {}",
                    record.id(), record.latestVersion(), nextVersion, summary);
            return DocumentPatchResult.success(nextVersion, diffJson, summary);
        }
    }

    /**
     * P2-11 将 4 条 DB 写入包在事务里，任一失败则整体回滚，并删除已写盘的 nextFile 保证
     * "DB 状态 = 文件系统状态"一致。若未注入 TransactionTemplate（仅兼容旧测试场景）则直接顺序
     * 写入，失败时尽力删掉 nextFile 作为 best-effort 回滚。
     */
    private void persistPatchTransactional(String documentId, int nextVersion, Path nextFile,
                                            long size, String summary, String diffJson) {
        Runnable dbWrites = () -> {
            versionRepository.save(new DocumentVersionRecord(
                    UUID.randomUUID().toString(), documentId, nextVersion, nextFile.toString(),
                    DocumentVersionRecord.SOURCE_PATCH, summary, diffJson, Instant.now()));
            documentRepository.updateLatestVersion(documentId, nextVersion);
            documentRepository.updateFilePath(documentId, nextFile.toString(), size);
            attachmentRepository.updateSizeByFilePath(nextFile.toString(), size);
        };
        try {
            if (transactionTemplate != null) {
                transactionTemplate.executeWithoutResult(status -> dbWrites.run());
            } else {
                dbWrites.run();
            }
        } catch (RuntimeException e) {
            log.warn("patch DB 事务失败，尝试删除已落盘的 nextFile 回滚：file={}", nextFile, e);
            try {
                Files.deleteIfExists(nextFile);
            } catch (IOException io) {
                log.warn("删除 nextFile 失败，留给后台 GC 清孤儿：file={}", nextFile, io);
            }
            throw e;
        }
    }

    private DocumentPatchResult applyXlsxPatch(SessionDocumentRecord record,
                                               List<XlsxPatchOperation> ops)
            throws IOException, JsonProcessingException {
        int nextVersion = record.latestVersion() + 1;
        Path currentFile = Paths.get(record.filePath());
        Path nextFile = workingPath(record, nextVersion);
        Files.createDirectories(nextFile.getParent());

        try (InputStream in = Files.newInputStream(currentFile);
             XSSFWorkbook wb = new XSSFWorkbook(in)) {
            var engineResult = xlsxEngine.apply(wb, ops);
            if (!engineResult.success()) {
                return DocumentPatchResult.failure(engineResult.failedOps());
            }
            byte[] bytes = serializeXlsx(wb);
            Files.write(nextFile, bytes);

            String diffJson = xlsxDiffBuilder.build(record.id(), record.latestVersion(), nextVersion,
                    engineResult.appliedOps());
            String summary = xlsxDiffBuilder.summarize(engineResult.appliedOps());

            // P2-11：同 docx 路径走事务化 DB 写入（updateSizeByFilePath 已包含在 persist 事务里）
            persistPatchTransactional(record.id(), nextVersion, nextFile, bytes.length, summary, diffJson);

            log.info("xlsx patch 成功：documentId={}, version={}→{}, {}",
                    record.id(), record.latestVersion(), nextVersion, summary);
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
        pruneOldBackups(target);
        log.info("commit overwrite：documentId={}, target={}, backup={}",
                documentId, target, backup);
        return new CommitResult(target.toString(), backup.toString());
    }

    /**
     * 保留最近 {@link #BACKUP_RETENTION_COUNT} 份自动备份（按文件名里的时间戳倒序），其余删除。
     * best-effort：任何异常只 warn，不影响 commit 主流程。
     * 仅匹配 {@link #BACKUP_FILENAME} 精确命名，不误伤手工命名的 .bak。
     */
    private void pruneOldBackups(Path target) {
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return;
        }
        String prefix = target.getFileName().toString() + ".";
        try (var stream = Files.list(parent)) {
            var backups = stream
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith(prefix) && BACKUP_FILENAME.matcher(name).matches();
                    })
                    // 时间戳字典序 == 时间序，倒序即新 → 旧，跳过前 N 份后全部删除
                    .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                    .skip(BACKUP_RETENTION_COUNT)
                    .toList();
            for (var old : backups) {
                try {
                    Files.deleteIfExists(old);
                    log.debug("清理旧备份：{}", old);
                } catch (IOException e) {
                    log.warn("清理旧备份失败：{}", old, e);
                }
            }
        } catch (IOException e) {
            log.warn("扫描旧备份失败：target={}", target, e);
        }
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
        Path nextFile = workingPath(record, nextVersion);
        Files.createDirectories(nextFile.getParent());
        Files.copy(Paths.get(targetVer.filePath()), nextFile, StandardCopyOption.REPLACE_EXISTING);
        long size = Files.size(nextFile);

        String summary = "回滚到版本 " + targetVersion;
        // rollback 的 diff_json 记录目标版本号，前端 DiffCard 据此渲染"回滚信息"而非"diff 数据暂不可用"。
        // 走 ObjectMapper 而非 String.format：后续若 summary 含用户可控内容也能自动转义，不炸 JSON。
        Map<String, Object> diffPayload = new LinkedHashMap<>();
        diffPayload.put("documentId", documentId);
        diffPayload.put("fromVersion", record.latestVersion());
        diffPayload.put("toVersion", nextVersion);
        diffPayload.put("summary", summary);
        diffPayload.put("rollbackFromVersion", targetVersion);
        diffPayload.put("changes", List.of());
        String rollbackDiffJson = JSON.writeValueAsString(diffPayload);
        versionRepository.save(new DocumentVersionRecord(
                UUID.randomUUID().toString(), documentId, nextVersion, nextFile.toString(),
                DocumentVersionRecord.SOURCE_ROLLBACK, summary, rollbackDiffJson, Instant.now()));
        documentRepository.updateLatestVersion(documentId, nextVersion);
        documentRepository.updateFilePath(documentId, nextFile.toString(), size);
        attachmentRepository.updateSizeByFilePath(nextFile.toString(), size);

        log.info("rollback：documentId={}, to={}", documentId, targetVersion);
        return DocumentPatchResult.success(nextVersion, null, summary);
    }

    // ===== discard / list =====

    public void discard(String documentId) throws IOException {
        // 严格校验 documentId 为 UUID 格式：下面有按 <documentId>_ 前缀扫描 storageDir 的逻辑，
        // 若 id 含 "/" ".." "*" 等字符可能误删/穿越；UUID.fromString 非法值会抛 IAE。
        UUID.fromString(documentId);
        var record = requireDocument(documentId);
        if (record.latestVersion() == 0) {
            throw new IllegalStateException("文档无工作副本（latestVersion=0），不可丢弃");
        }
        Path workingDir = Paths.get(storageDir, record.sessionId(), "working", documentId);
        if (Files.exists(workingDir)) {
            // Files.walk 返回的 Stream 持有打开的目录句柄，必须 try-with-resources 关闭，
            // 否则 Windows 下未释放的句柄会阻塞后续 deleteIfExists。
            try (var stream = Files.walk(workingDir)) {
                stream.sorted((a, b) -> b.toString().length() - a.toString().length())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException e) {
                                log.warn("删除工作副本文件失败：{}", p, e);
                            }
                        });
            }
        }
        // 删 create_docx 落盘的原始文件（v0 字节）以及所有 document_versions 里的 file_path 指向。
        // 原始文件路径是 <storageDir>/<documentId>_<fileName>，patch/rollback 后 record.filePath
        // 会被更新为 working/vN 不再指向原始文件，需要按 documentId 前缀扫描 storageDir 清理孤儿
        var versions = versionRepository.findByDocumentId(documentId);
        for (var v : versions) {
            try {
                Files.deleteIfExists(Paths.get(v.filePath()));
            } catch (IOException e) {
                log.warn("删除版本物理文件失败：{}", v.filePath(), e);
            }
        }
        try {
            Files.deleteIfExists(Paths.get(record.filePath()));
        } catch (IOException e) {
            log.warn("删除文档当前 filePath 指向失败：{}", record.filePath(), e);
        }
        // 按 <documentId>_ 前缀扫描 storageDir 根目录，清理 create 时落盘的原始文件（孤儿）
        Path storageRoot = Paths.get(storageDir);
        if (Files.exists(storageRoot)) {
            try (var stream = Files.list(storageRoot)) {
                stream.filter(p -> {
                    String name = p.getFileName().toString();
                    return name.startsWith(documentId + "_");
                }).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        log.warn("删除原始落盘文件失败：{}", p, e);
                    }
                });
            } catch (IOException e) {
                log.warn("扫描 storageDir 清理孤儿文件失败：documentId={}", documentId, e);
            }
        }
        versionRepository.deleteByDocumentId(documentId);
        documentRepository.deleteById(documentId);
        log.info("discard：documentId={}", documentId);
    }

    public List<DocumentVersionRecord> listVersions(String documentId) {
        requireDocument(documentId);
        return versionRepository.findByDocumentId(documentId);
    }

    /**
     * P1-7 任意两版本 diff 计算 —— 不依赖 patch 缓存，从两版本物理文件重新对比。
     *
     * @param fromVersion 起始版本号（含），必须存在
     * @param toVersion   目标版本号（含），必须存在
     * @return diff JSON 字符串（结构对齐 DocxDiffBuilder / XlsxDiffBuilder 输出）
     */
    public String compareVersions(String documentId, int fromVersion, int toVersion) throws IOException {
        var record = requireDocument(documentId);
        var fromVer = versionRepository.findByDocumentIdAndVersion(documentId, fromVersion);
        var toVer = versionRepository.findByDocumentIdAndVersion(documentId, toVersion);
        if (fromVer == null || toVer == null) {
            throw new IllegalArgumentException(
                    "版本不存在：documentId=" + documentId + ", from=" + fromVersion + ", to=" + toVersion);
        }
        Path fromFile = Paths.get(fromVer.filePath());
        Path toFile = Paths.get(toVer.filePath());
        if (!Files.exists(fromFile) || !Files.exists(toFile)) {
            throw new IOException("版本物理文件丢失");
        }
        return switch (record.mimeType()) {
            case DOCX_MIME -> new DocxVersionComparator().compare(documentId, fromVersion, toVersion, fromFile, toFile);
            case XLSX_MIME -> new XlsxVersionComparator().compare(documentId, fromVersion, toVersion, fromFile, toFile);
            default -> throw new IllegalStateException("不支持的 MIME：" + record.mimeType());
        };
    }

    /** 分页列版本；page 从 1 起，pageSize 上限 100。防御性 clamp 非法入参。 */
    public VersionPage listVersions(String documentId, int page, int pageSize) {
        requireDocument(documentId);
        int p = Math.max(1, page);
        int ps = Math.min(100, Math.max(1, pageSize));
        int offset = (p - 1) * ps;
        var items = versionRepository.findByDocumentId(documentId, offset, ps);
        int total = versionRepository.countByDocumentId(documentId);
        return new VersionPage(items, total, p, ps);
    }

    public record VersionPage(List<DocumentVersionRecord> items, int total, int page, int pageSize) {}

    /** 暴露 sessionDocument 记录给上层 tool 层，用于拿 sourcePath/fileName 等元信息做响应组装。 */
    public SessionDocumentRecord getDocumentRecord(String documentId) {
        return requireDocument(documentId);
    }

    /**
     * 返回 docx 最新工作副本的段落预览列表 —— patch 锚点失败时给 LLM 的 hint，
     * 让它基于真实文档内容调整 locator 而不是瞎猜。每段取前 80 字，空段落跳过。
     * 非 docx MIME 返回空列表（xlsx/pptx 走各自的 outline 方法）。
     */
    public List<Map<String, Object>> getDocxOutline(String documentId) {
        var record = requireDocument(documentId);
        if (!DOCX_MIME.equals(record.mimeType())) {
            return List.of();
        }
        Path filePath = Paths.get(record.filePath());
        if (!Files.exists(filePath)) {
            return List.of();
        }
        // 路径安全校验：record.filePath 来自 DB，理论上只指向 working 目录或 checkout 时校验过的源路径；
        // 但 hint 函数恰是 patch 失败时频繁调用，多一层白名单校验可兜住历史脏数据或软链接攻击，
        // 校验失败 soft fail 返回空列表不中断主流程。
        var rejection = pathSecurityChecker.check(filePath);
        if (rejection.isPresent()) {
            log.warn("getDocxOutline 路径校验失败：documentId={}, reason={}", documentId, rejection.get());
            return List.of();
        }
        try (InputStream is = Files.newInputStream(filePath);
             XWPFDocument doc = new XWPFDocument(is)) {
            var result = new ArrayList<Map<String, Object>>();
            int index = 0;
            for (var p : doc.getParagraphs()) {
                String text = p.getText() == null ? "" : p.getText();
                if (!text.isBlank()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("paragraphIndex", index);
                    m.put("preview", text.length() > 80 ? text.substring(0, 80) + "…" : text);
                    String style = p.getStyle();
                    if (style != null && !style.isBlank()) {
                        m.put("style", style);
                    }
                    result.add(m);
                }
                index++;
            }
            return result;
        } catch (Exception e) {
            log.warn("读取 docx 段落预览失败：documentId={}", documentId, e);
            return List.of();
        }
    }

    // ===== 辅助 =====

    private SessionDocumentRecord requireDocument(String documentId) {
        var record = documentRepository.findById(documentId);
        if (record == null) {
            throw new IllegalArgumentException("document 不存在：" + documentId);
        }
        return record;
    }

    private Path workingPath(SessionDocumentRecord record, int version) {
        return workingPath(record.sessionId(), record.id(), version, record.mimeType());
    }

    private Path workingPath(String sessionId, String documentId, int version, String mimeType) {
        return Paths.get(storageDir, sessionId, "working", documentId,
                "v" + version + workingExtension(mimeType));
    }

    /** 按 mimeType 返回工作副本的文件扩展名（含点）。 */
    private static String workingExtension(String mimeType) {
        return switch (mimeType) {
            case DOCX_MIME -> ".docx";
            case XLSX_MIME -> ".xlsx";
            default -> throw new IllegalArgumentException("不支持的 MIME：" + mimeType);
        };
    }

    private byte[] serializeDocx(XWPFDocument doc) throws IOException {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            doc.write(bos);
            return bos.toByteArray();
        }
    }

    private byte[] serializeXlsx(XSSFWorkbook wb) throws IOException {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    /** 运行时 instanceof 校验：docx 文档只能接受 DocxPatchOperation。 */
    private static List<DocxPatchOperation> castDocxOps(List<DocumentPatchOperation> ops) {
        List<DocxPatchOperation> result = new ArrayList<>(ops.size());
        for (int i = 0; i < ops.size(); i++) {
            DocumentPatchOperation op = ops.get(i);
            if (!(op instanceof DocxPatchOperation d)) {
                throw new IllegalArgumentException(
                        "op #" + i + " 不是 docx 操作（" + op.getClass().getSimpleName()
                                + "），目标文档是 docx 类型");
            }
            result.add(d);
        }
        return result;
    }

    /** 运行时 instanceof 校验：xlsx 文档只能接受 XlsxPatchOperation。 */
    private static List<XlsxPatchOperation> castXlsxOps(List<DocumentPatchOperation> ops) {
        List<XlsxPatchOperation> result = new ArrayList<>(ops.size());
        for (int i = 0; i < ops.size(); i++) {
            DocumentPatchOperation op = ops.get(i);
            if (!(op instanceof XlsxPatchOperation x)) {
                throw new IllegalArgumentException(
                        "op #" + i + " 不是 xlsx 操作（" + op.getClass().getSimpleName()
                                + "），目标文档是 xlsx 类型");
            }
            result.add(x);
        }
        return result;
    }
}
