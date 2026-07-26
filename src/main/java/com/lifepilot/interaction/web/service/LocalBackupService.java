package com.lifepilot.interaction.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.config.path.ZhiweiPaths;
import com.lifepilot.interaction.web.model.LocalBackupFileInfo;
import com.lifepilot.interaction.web.model.LocalBackupInfo;
import com.lifepilot.interaction.web.model.LocalBackupManifestInfo;
import com.lifepilot.interaction.web.model.LocalBackupRestorePlanInfo;
import com.lifepilot.interaction.web.model.LocalBackupRestorePreparationInfo;
import com.lifepilot.interaction.web.model.LocalBackupValidationInfo;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 本地 HOME 数据备份服务。
 *
 * <p>只在用户显式调用时创建 zip 备份；排除备份、缓存、运行时和日志目录，避免备份无限膨胀。</p>
 *
 * @author zsg
 * @since 2026-07-04
 */
@Service
public class LocalBackupService {

    private static final String BACKUP_FORMAT_VERSION = "1";
    private static final String MANIFEST_ENTRY_NAME = ".zhiwei-backup-manifest.json";

    private static final DateTimeFormatter BACKUP_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private static final Set<String> EXCLUDED_TOP_LEVEL_DIRS = Set.of(
            ZhiweiPaths.DIR_BACKUPS,
            ZhiweiPaths.DIR_CACHE,
            ZhiweiPaths.DIR_RUNTIME,
            ZhiweiPaths.DIR_LOGS
    );

    private final ZhiweiPaths zhiweiPaths;
    private final ObjectMapper objectMapper;

    public LocalBackupService(ZhiweiPaths zhiweiPaths, ObjectMapper objectMapper) {
        this.zhiweiPaths = zhiweiPaths;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建 HOME 目录 zip 备份。
     */
    public LocalBackupInfo createBackup() throws IOException {
        Path home = zhiweiPaths.home().toAbsolutePath().normalize();
        Path backupDirectory = zhiweiPaths.home(ZhiweiPaths.DIR_BACKUPS).toAbsolutePath().normalize();
        Files.createDirectories(backupDirectory);

        Instant now = Instant.now();
        String fileName = "zhiwei-backup-" + BACKUP_TIME_FORMAT.format(now) + "-" + now.toEpochMilli() + ".zip";
        Path target = backupDirectory.resolve(fileName).normalize();
        Path temp = backupDirectory.resolve(fileName + ".tmp").normalize();

        if (!target.startsWith(backupDirectory) || !temp.startsWith(backupDirectory)) {
            throw new IOException("备份路径逃逸 HOME/backups 目录");
        }

        AtomicInteger fileCount = new AtomicInteger();
        try {
            writeZip(home, backupDirectory, temp, fileCount, now);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }

        return new LocalBackupInfo(
                now,
                fileName,
                target.toString(),
                Files.size(target),
                fileCount.get()
        );
    }

    /**
     * 列出当前 HOME 目录下已有的知微备份文件。
     */
    public List<LocalBackupFileInfo> listBackups() throws IOException {
        Path backupDirectory = zhiweiPaths.home(ZhiweiPaths.DIR_BACKUPS).toAbsolutePath().normalize();
        if (!Files.exists(backupDirectory)) {
            return List.of();
        }

        try (var stream = Files.list(backupDirectory)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(this::isZhiweiBackupFile)
                    .map(this::toBackupFileInfo)
                    .flatMap(List::stream)
                    .sorted(Comparator.comparing(LocalBackupFileInfo::modifiedAt).reversed())
                    .toList();
        }
    }

    /**
     * 校验指定备份文件是否可读取、路径安全且包含备份清单。
     */
    public LocalBackupValidationInfo validateBackup(String fileName) throws IOException {
        Path backupDirectory = zhiweiPaths.home(ZhiweiPaths.DIR_BACKUPS).toAbsolutePath().normalize();
        String safeFileName = fileName == null ? "" : fileName.trim();
        if (!isSafeBackupFileName(safeFileName)) {
            return validation(
                    safeFileName,
                    "-",
                    "ERROR",
                    "备份文件名不合法",
                    0,
                    0,
                    false,
                    null,
                    List.of("只能校验 backups 目录下的知微备份 zip 文件"),
                    null
            );
        }

        Path target = backupDirectory.resolve(safeFileName).normalize();
        if (!target.startsWith(backupDirectory)) {
            return validation(safeFileName, target.toString(), "ERROR", "备份路径越界", 0, 0,
                    false, null, List.of("备份路径不在 HOME/backups 目录内"), null);
        }
        if (!Files.isRegularFile(target)) {
            return validation(safeFileName, target.toString(), "ERROR", "备份文件不存在", 0, 0,
                    false, null, List.of("未找到要校验的备份文件"), null);
        }

        var problems = new LinkedHashSet<String>();
        int entryCount = 0;
        long estimatedRestoreBytes = 0;
        boolean estimatedRestoreBytesKnown = true;
        boolean manifestPresent = false;
        LocalBackupManifestInfo manifest = null;
        var includedTopLevelItems = new LinkedHashSet<String>();
        try (var zip = new ZipFile(target.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                entryCount++;
                String topLevelItem = topLevelItem(entry.getName());
                if (topLevelItem != null && !MANIFEST_ENTRY_NAME.equals(topLevelItem)) {
                    includedTopLevelItems.add(topLevelItem);
                }
                if (isUnsafeZipEntry(entry.getName())) {
                    problems.add("备份包包含不安全路径：" + entry.getName());
                }
                long entrySize = entry.getSize();
                if (entrySize >= 0) {
                    estimatedRestoreBytes = addSize(estimatedRestoreBytes, entrySize);
                } else {
                    estimatedRestoreBytesKnown = false;
                    problems.add("备份条目大小未知，恢复前请预留足够磁盘空间：" + entry.getName());
                }
            }

            ZipEntry manifestEntry = zip.getEntry(MANIFEST_ENTRY_NAME);
            manifestPresent = manifestEntry != null && !manifestEntry.isDirectory();
            if (manifestPresent) {
                try (var input = zip.getInputStream(manifestEntry)) {
                    manifest = objectMapper.readValue(input, LocalBackupManifestInfo.class);
                } catch (Exception e) {
                    problems.add("备份清单无法读取：" + e.getMessage());
                }
            } else {
                problems.add("备份包缺少清单，建议重新创建备份");
            }
        } catch (ZipException e) {
            return validation(safeFileName, target.toString(), "ERROR", "备份 zip 无法读取",
                    Files.size(target), 0, false, null, List.of("zip 文件损坏或格式不正确：" + e.getMessage()), null);
        }

        if (entryCount == 0) {
            problems.add("备份包没有任何文件条目");
        }

        boolean hasError = problems.stream().anyMatch(problem ->
                problem.startsWith("备份包包含不安全路径")
                        || problem.startsWith("备份清单无法读取")
                        || problem.startsWith("备份包没有任何文件条目"));
        String status = hasError ? "ERROR" : problems.isEmpty() ? "OK" : "WARN";
        String detail = switch (status) {
            case "OK" -> "备份文件结构正常";
            case "WARN" -> "备份文件可读取，但建议重新创建新格式备份";
            default -> "备份文件存在结构风险";
        };
        LocalBackupRestorePlanInfo restorePlan = buildRestorePlan(
                manifest,
                Files.size(target),
                estimatedRestoreBytesKnown ? estimatedRestoreBytes : -1,
                includedTopLevelItems.stream().sorted().toList()
        );
        return validation(safeFileName, target.toString(), status, detail, Files.size(target),
                entryCount, manifestPresent, manifest, List.copyOf(problems), restorePlan);
    }

    /**
     * 将备份包安全解压到恢复准备目录，不覆盖当前 HOME 数据。
     */
    public LocalBackupRestorePreparationInfo prepareRestore(String fileName) throws IOException {
        LocalBackupValidationInfo validation = validateBackup(fileName);
        if ("ERROR".equals(validation.status())) {
            throw new IOException("备份校验失败：" + validation.detail());
        }

        Path backupDirectory = zhiweiPaths.home(ZhiweiPaths.DIR_BACKUPS).toAbsolutePath().normalize();
        String safeFileName = fileName == null ? "" : fileName.trim();
        Path backupFile = backupDirectory.resolve(safeFileName).normalize();
        if (!backupFile.startsWith(backupDirectory) || !Files.isRegularFile(backupFile)) {
            throw new IOException("备份文件不存在或路径越界");
        }

        Instant now = Instant.now();
        Path stagingRoot = zhiweiPaths.home(ZhiweiPaths.DIR_RUNTIME)
                .resolve("restore-staging")
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(stagingRoot);
        Path restoreDirectory = stagingRoot.resolve(restoreDirectoryName(safeFileName, now)).normalize();
        if (!restoreDirectory.startsWith(stagingRoot)) {
            throw new IOException("恢复准备目录路径越界");
        }
        Files.createDirectories(restoreDirectory);

        AtomicInteger fileCount = new AtomicInteger();
        AtomicLong totalBytes = new AtomicLong();
        try {
            try (var zip = new ZipFile(backupFile.toFile())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) {
                        continue;
                    }
                    if (isUnsafeZipEntry(entry.getName())) {
                        throw new IOException("备份包包含不安全路径：" + entry.getName());
                    }
                    Path target = restoreDirectory.resolve(entry.getName()).normalize();
                    if (!target.startsWith(restoreDirectory)) {
                        throw new IOException("备份条目解压路径越界：" + entry.getName());
                    }
                    Files.createDirectories(target.getParent());
                    try (var input = zip.getInputStream(entry)) {
                        Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    fileCount.incrementAndGet();
                    totalBytes.addAndGet(Files.size(target));
                }
            }
        } catch (IOException e) {
            try {
                deleteRecursively(restoreDirectory);
            } catch (IOException cleanupError) {
                e.addSuppressed(cleanupError);
            }
            throw e;
        }

        var warnings = new ArrayList<String>();
        if (validation.restorePlan() != null) {
            warnings.addAll(validation.restorePlan().warnings());
        }
        if ("WARN".equals(validation.status())) {
            warnings.add("备份可读取但存在提示，恢复前请先检查准备目录中的文件。");
        }
        String copyStep = restoreCopyStep(validation.restorePlan());
        return new LocalBackupRestorePreparationInfo(
                now,
                safeFileName,
                restoreDirectory.toString(),
                fileCount.get(),
                totalBytes.get(),
                List.copyOf(warnings),
                List.of(
                        "打开恢复准备目录，检查文件结构和备份清单。",
                        "确认无误后关闭知微，先为当前 HOME 创建一份新备份。",
                        copyStep,
                        "重新启动知微，并刷新本地诊断状态。"
                )
        );
    }

    private void writeZip(Path home,
                          Path backupDirectory,
                          Path temp,
                          AtomicInteger fileCount,
                          Instant createdAt) throws IOException {
        try (var output = new ZipOutputStream(Files.newOutputStream(temp))) {
            Files.walkFileTree(home, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    Path normalized = dir.toAbsolutePath().normalize();
                    if (normalized.equals(home)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (normalized.startsWith(backupDirectory)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    Path relative = home.relativize(normalized);
                    if (isExcludedTopLevel(relative)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path normalized = file.toAbsolutePath().normalize();
                    if (normalized.startsWith(backupDirectory)) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path relative = home.relativize(normalized);
                    if (isExcludedTopLevel(relative)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String entryName = relative.toString().replace('\\', '/');
                    output.putNextEntry(new ZipEntry(entryName));
                    Files.copy(normalized, output);
                    output.closeEntry();
                    fileCount.incrementAndGet();
                    return FileVisitResult.CONTINUE;
                }
            });
            writeManifest(output, home, fileCount.get(), createdAt);
        }
    }

    private void writeManifest(ZipOutputStream output,
                               Path home,
                               int includedFileCount,
                               Instant createdAt) throws IOException {
        var manifest = new LocalBackupManifestInfo(
                BACKUP_FORMAT_VERSION,
                createdAt,
                home.toString(),
                includedFileCount,
                EXCLUDED_TOP_LEVEL_DIRS.stream().sorted().toList()
        );
        output.putNextEntry(new ZipEntry(MANIFEST_ENTRY_NAME));
        output.write(objectMapper.writeValueAsBytes(manifest));
        output.closeEntry();
    }

    private void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private boolean isExcludedTopLevel(Path relative) {
        if (relative.getNameCount() == 0) {
            return false;
        }
        return EXCLUDED_TOP_LEVEL_DIRS.contains(relative.getName(0).toString());
    }

    private boolean isZhiweiBackupFile(Path file) {
        String name = file.getFileName().toString();
        return name.startsWith("zhiwei-backup-") && name.endsWith(".zip");
    }

    private boolean isSafeBackupFileName(String fileName) {
        return !fileName.isBlank()
                && fileName.equals(Path.of(fileName).getFileName().toString())
                && fileName.startsWith("zhiwei-backup-")
                && fileName.endsWith(".zip");
    }

    private String restoreDirectoryName(String fileName, Instant now) {
        String baseName = fileName.endsWith(".zip") ? fileName.substring(0, fileName.length() - 4) : fileName;
        String safeBaseName = baseName.replaceAll("[^a-zA-Z0-9._-]", "_");
        return safeBaseName + "-restore-" + BACKUP_TIME_FORMAT.format(now) + "-" + now.toEpochMilli();
    }

    private String restoreCopyStep(LocalBackupRestorePlanInfo plan) {
        if (plan == null || plan.includedTopLevelItems().isEmpty()) {
            return "把恢复准备目录中的核心数据复制到目标 HOME。";
        }
        return "只把恢复准备目录中的 %s 复制到目标 HOME。"
                .formatted(String.join("、", plan.includedTopLevelItems()));
    }

    private boolean isUnsafeZipEntry(String entryName) {
        String normalized = entryName.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) {
            return true;
        }
        for (String part : normalized.split("/")) {
            if (part.equals("..")) {
                return true;
            }
        }
        return false;
    }

    private String topLevelItem(String entryName) {
        String normalized = entryName == null ? "" : entryName.replace('\\', '/').strip();
        if (normalized.isBlank() || isUnsafeZipEntry(normalized)) {
            return null;
        }
        String first = normalized.split("/", 2)[0];
        return first.isBlank() ? null : first;
    }

    private LocalBackupRestorePlanInfo buildRestorePlan(LocalBackupManifestInfo manifest,
                                                        long backupSizeBytes,
                                                        long estimatedRestoreBytes,
                                                        List<String> includedTopLevelItems) throws IOException {
        Path home = zhiweiPaths.home().toAbsolutePath().normalize();
        Path restoreStagingDirectory = home.resolve(ZhiweiPaths.DIR_RUNTIME)
                .resolve("restore-staging")
                .toAbsolutePath()
                .normalize();
        int currentFileCount = countCoreHomeFiles(home);
        long targetUsableBytes = resolveUsableBytes(home);
        String restoreSpaceStatus = restoreSpaceStatus(targetUsableBytes, estimatedRestoreBytes);
        var warnings = new ArrayList<String>();
        if (currentFileCount > 0) {
            warnings.add("当前 HOME 已有数据，恢复前必须先关闭知微并备份当前 HOME。");
        }
        if (manifest == null) {
            warnings.add("备份缺少清单，无法确认来源 HOME 和数据文件数。");
        } else if (!BACKUP_FORMAT_VERSION.equals(manifest.formatVersion())) {
            warnings.add("备份格式版本不是当前版本，恢复前建议先在临时目录解压检查。");
        }
        if ("WARN".equals(restoreSpaceStatus)) {
            warnings.add("目标磁盘剩余空间不足，恢复准备目录预计需要 %s，可用 %s。".formatted(
                    formatBytes(estimatedRestoreBytes),
                    formatBytes(targetUsableBytes)
            ));
        } else if ("UNKNOWN".equals(restoreSpaceStatus)) {
            warnings.add("无法读取目标磁盘剩余空间，恢复前请确认至少预留 %s。".formatted(
                    formatBytes(estimatedRestoreBytes)
            ));
        }
        if (!includedTopLevelItems.isEmpty()) {
            warnings.add("恢复准备目录只应复制这些顶层数据项：" + String.join("、", includedTopLevelItems) + "。");
        }
        warnings.add("当前版本只提供恢复前预检，不会自动覆盖本机数据。");

        return new LocalBackupRestorePlanInfo(
                "manual-staging",
                true,
                includedTopLevelItems,
                manifest == null ? List.of() : List.copyOf(manifest.excludedTopLevelDirs()),
                home.toString(),
                currentFileCount > 0,
                currentFileCount,
                manifest == null ? "未知（旧格式备份没有清单）" : manifest.sourceHome(),
                manifest == null ? -1 : manifest.includedFileCount(),
                restoreStagingDirectory.toString(),
                backupSizeBytes,
                estimatedRestoreBytes,
                targetUsableBytes,
                restoreSpaceStatus,
                List.copyOf(warnings),
                List.of(
                        "关闭知微，确认没有后台进程占用 HOME 目录。",
                        "先为当前 HOME 创建一份新备份，保留回退点。",
                        "将备份 zip 解压到临时目录，检查文件结构和清单。",
                        includedTopLevelItems.isEmpty()
                                ? "确认无误后，再把数据文件复制到目标 HOME。"
                                : "确认无误后，只把恢复准备目录中的 %s 复制到目标 HOME。"
                                        .formatted(String.join("、", includedTopLevelItems)),
                        "重新启动知微，并在设置中刷新本地诊断状态。"
                )
        );
    }

    private long resolveUsableBytes(Path path) {
        try {
            Path target = path;
            while (target != null && !Files.exists(target)) {
                target = target.getParent();
            }
            if (target == null) {
                return -1;
            }
            return Files.getFileStore(target).getUsableSpace();
        } catch (IOException e) {
            return -1;
        }
    }

    private String restoreSpaceStatus(long usableBytes, long estimatedRestoreBytes) {
        if (usableBytes < 0 || estimatedRestoreBytes < 0) {
            return "UNKNOWN";
        }
        return usableBytes >= estimatedRestoreBytes ? "OK" : "WARN";
    }

    private long addSize(long current, long increment) {
        if (Long.MAX_VALUE - current < increment) {
            return Long.MAX_VALUE;
        }
        return current + increment;
    }

    private String formatBytes(long value) {
        if (value < 0) {
            return "未知";
        }
        if (value < 1024) {
            return value + " B";
        }
        if (value < 1024 * 1024) {
            return "%.1f KB".formatted(value / 1024.0);
        }
        if (value < 1024L * 1024L * 1024L) {
            return "%.1f MB".formatted(value / 1024.0 / 1024.0);
        }
        return "%.1f GB".formatted(value / 1024.0 / 1024.0 / 1024.0);
    }

    private int countCoreHomeFiles(Path home) throws IOException {
        if (!Files.exists(home)) {
            return 0;
        }
        AtomicInteger count = new AtomicInteger();
        Files.walkFileTree(home, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                Path normalized = dir.toAbsolutePath().normalize();
                if (normalized.equals(home)) {
                    return FileVisitResult.CONTINUE;
                }
                Path relative = home.relativize(normalized);
                return isExcludedTopLevel(relative) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                Path relative = home.relativize(file.toAbsolutePath().normalize());
                if (!isExcludedTopLevel(relative)) {
                    count.incrementAndGet();
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return count.get();
    }

    private List<LocalBackupFileInfo> toBackupFileInfo(Path file) {
        try {
            var attrs = Files.readAttributes(file, BasicFileAttributes.class);
            return List.of(new LocalBackupFileInfo(
                    attrs.lastModifiedTime().toInstant(),
                    file.getFileName().toString(),
                    file.toAbsolutePath().normalize().toString(),
                    attrs.size()
            ));
        } catch (IOException e) {
            return List.of();
        }
    }

    private LocalBackupValidationInfo validation(String fileName,
                                                 String path,
                                                 String status,
                                                 String detail,
                                                 long sizeBytes,
                                                 int entryCount,
                                                 boolean manifestPresent,
                                                 LocalBackupManifestInfo manifest,
                                                 List<String> problems,
                                                 LocalBackupRestorePlanInfo restorePlan) {
        return new LocalBackupValidationInfo(
                fileName,
                path,
                status,
                detail,
                sizeBytes,
                entryCount,
                manifestPresent,
                manifest,
                problems,
                restorePlan
        );
    }
}
