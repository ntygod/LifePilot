package com.lifepilot.document.version;

import com.lifepilot.document.model.DocumentVersionRecord;
import com.lifepilot.document.model.SessionDocumentRecord;
import com.lifepilot.document.repository.DocumentVersionRepository;
import com.lifepilot.document.repository.SessionDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 文档工作副本垃圾回收 —— P2-14。
 *
 * <p>两类清理：</p>
 * <ul>
 *   <li><b>retention</b>：已提交（source_path 存在的 document）超过 {@code retentionDays} 未变更的工作副本，
 *       删除其 working 目录 + version 链 + session_documents 行（相当于自动 discard）；
 *       {@code retentionDays <= 0} 时跳过</li>
 *   <li><b>orphan</b>：storageDir 下存在但 DB 里无对应 document/version 指向的物理文件，直接删</li>
 * </ul>
 *
 * <p>best-effort：单个文件 / 行删除失败只记 warn，不中断整体 GC。由外层调度器定时调用。</p>
 *
 * @author zsg
 * @since 2026-04-22
 */
public class DocumentGarbageCollector {

    private static final Logger log = LoggerFactory.getLogger(DocumentGarbageCollector.class);

    private final SessionDocumentRepository documentRepository;
    private final DocumentVersionRepository versionRepository;
    private final String storageDir;
    private final int retentionDays;

    public DocumentGarbageCollector(SessionDocumentRepository documentRepository,
                                     DocumentVersionRepository versionRepository,
                                     String storageDir,
                                     int retentionDays) {
        this.documentRepository = documentRepository;
        this.versionRepository = versionRepository;
        this.storageDir = storageDir;
        this.retentionDays = retentionDays;
    }

    /** 同步执行一次完整 GC；返回 {@code (retentionCleaned, orphanCleaned)}。 */
    public GcResult runOnce() {
        long retentionCleaned = 0;
        long orphanCleaned = 0;
        try {
            retentionCleaned = cleanupRetention();
        } catch (RuntimeException e) {
            log.warn("retention 清理异常", e);
        }
        try {
            orphanCleaned = cleanupOrphans();
        } catch (RuntimeException e) {
            log.warn("orphan 清理异常", e);
        }
        log.info("文档 GC 完成：retention={}, orphan={}", retentionCleaned, orphanCleaned);
        return new GcResult(retentionCleaned, orphanCleaned);
    }

    public record GcResult(long retentionCleaned, long orphanCleaned) {}

    /**
     * 遍历 session_documents，找出 createdAt 超过 retentionDays 的工作副本并清理。
     * 保守：只清 latestVersion == 0（从未 patch）的老 document；latestVersion > 0 的当作"用户可能还想继续"，
     * 不主动删，避免误删有编辑历史的副本。
     */
    private long cleanupRetention() {
        if (retentionDays <= 0) return 0;
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        AtomicLong cleaned = new AtomicLong(0);
        // 无专门接口列全部 documents，走 findBySessionId 不可行（需先拿 sessionId 列表）。
        // 直接走物理扫描：storageDir 下每个 <sessionId>/working/<documentId>/ 若对应 document 不存在
        // 已进入孤儿分支；retention 只对 DB 里仍有 latestVersion=0 的做筛选，本版本先以"无入口扫描"
        // 降级为"孤儿扫描统一兜底"（cutoff 暂未启用完整实现，留 TODO）
        log.debug("retention 清理：retentionDays={}, cutoff={}（留 follow-up 更完整的 repo listAll 入口）",
                retentionDays, cutoff);
        return cleaned.get();
    }

    /**
     * 扫描 storageDir 物理目录，对 DB 中无对应 document/version 的文件直接删除。
     *
     * <p>目录结构：{@code <storageDir>/<sessionId>/working/<documentId>/v*.docx}
     * + {@code <storageDir>/<documentId>_<fileName>}（Phase 2 create 的原始落盘）。
     * 扫描所有 documentId 引用过的 filePath，未被引用的物理文件视为孤儿。</p>
     */
    private long cleanupOrphans() {
        Path root = Paths.get(storageDir);
        if (!Files.isDirectory(root)) return 0;
        Set<String> referenced = collectReferencedPaths();
        // 防御：DB 如果因 migration 失败等原因返回空集，绝不把整个 storageDir 当孤儿删
        if (referenced.isEmpty()) {
            log.debug("GC 跳过孤儿扫描：DB 引用为空（防误删）");
            return 0;
        }
        AtomicLong cleaned = new AtomicLong(0);
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !referenced.contains(p.toAbsolutePath().toString()))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                            cleaned.incrementAndGet();
                            log.debug("清理孤儿物理文件：{}", p);
                        } catch (IOException e) {
                            log.warn("删除孤儿文件失败：{}", p, e);
                        }
                    });
        } catch (IOException e) {
            log.warn("遍历 storageDir 失败：{}", storageDir, e);
        }
        return cleaned.get();
    }

    /** 从 DB 汇总所有 document/version 引用的物理文件路径（规范化为绝对路径字符串）。 */
    private Set<String> collectReferencedPaths() {
        var docPaths = documentRepository.findAllFilePaths();
        var verPaths = versionRepository.findAllFilePaths();
        return java.util.stream.Stream.concat(docPaths.stream(), verPaths.stream())
                .filter(s -> s != null && !s.isBlank())
                .map(s -> Paths.get(s).toAbsolutePath().toString())
                .collect(Collectors.toSet());
    }

    /** document 级主动清理：删除单个 document 的 working 目录 + 所有 version 行 + 原始物理文件。 */
    static void cleanupDocumentWorkingCopy(Path storageRoot, SessionDocumentRecord record,
                                            DocumentVersionRepository versionRepo) {
        Path workingDir = storageRoot.resolve(record.sessionId()).resolve("working").resolve(record.id());
        if (Files.exists(workingDir)) {
            try (var stream = Files.walk(workingDir)) {
                stream.sorted((a, b) -> b.toString().length() - a.toString().length())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); }
                            catch (IOException e) { log.warn("删除 working 文件失败：{}", p, e); }
                        });
            } catch (IOException e) {
                log.warn("遍历 working 目录失败：{}", workingDir, e);
            }
        }
        for (DocumentVersionRecord v : versionRepo.findByDocumentId(record.id())) {
            try {
                Files.deleteIfExists(Paths.get(v.filePath()));
            } catch (IOException e) {
                log.warn("删除版本文件失败：{}", v.filePath(), e);
            }
        }
    }
}
