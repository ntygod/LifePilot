package com.lifepilot.skill.install;

import com.lifepilot.skill.config.SkillConfigProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 用户端 Skill 导入：.skill 压缩包 / Git URL。
 *
 * <p>Phase B.4 —— 手动导入入口。职责：</p>
 * <ul>
 *   <li>解压 .skill 包到临时目录（带 zip slip + zip bomb 防护）</li>
 *   <li>根目录必须含 SKILL.md —— 委托 {@link SkillInstaller} 完成 parse / validate / writeFile / upsert</li>
 *   <li>复制辅助目录（references/scripts/assets）到最终安装目录</li>
 *   <li>无论成功失败都清理临时目录</li>
 * </ul>
 *
 * <p>Git URL 导入当前仅留 stub，抛 {@link UnsupportedOperationException}（后续迭代补齐）。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
@Service
public class SkillImportService {

    private static final Logger log = LoggerFactory.getLogger(SkillImportService.class);

    /** zip 包最大条目数，超出判定为 zip bomb。 */
    private static final int MAX_ZIP_ENTRIES = 100;

    /** zip 解压总字节上限（10MB），超出判定为 zip bomb。 */
    private static final long MAX_ZIP_TOTAL_BYTES = 10L * 1024 * 1024;

    private final SkillInstaller installer;
    private final SkillInstallationRepository repository;
    private final SkillConfigProperties config;

    public SkillImportService(SkillInstaller installer,
                              SkillInstallationRepository repository,
                              SkillConfigProperties config) {
        this.installer = installer;
        this.repository = repository;
        this.config = config;
    }

    /**
     * 从 .skill 压缩包导入 Skill。
     *
     * @param file 上传的 .skill / .zip 文件，根目录必须含 SKILL.md
     * @return 最终写入的 {@link SkillInstallation} 记录
     * @throws IOException               文件 I/O 失败
     * @throws IllegalArgumentException  上传为空或缺少 SKILL.md
     * @throws SecurityException         zip slip / zip bomb 等安全违规
     */
    public SkillInstallation importFromPackage(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        Path tempDir = Files.createTempDirectory("skill-import-");
        try {
            unzipSafe(file, tempDir);
            Path skillMd = tempDir.resolve("SKILL.md");
            if (!Files.exists(skillMd)) {
                throw new IllegalArgumentException(".skill 包根目录必须包含 SKILL.md");
            }
            String content = Files.readString(skillMd);
            Path skillsRoot = Paths.get(config.getDirectory());
            Files.createDirectories(skillsRoot);
            var install = installer.install(new SkillInstaller.InstallRequest(
                    SkillSourceType.USER_IMPORTED,
                    "file://" + sanitize(file.getOriginalFilename()),
                    null,
                    content,
                    skillsRoot));
            // aux 复制失败必须回滚：否则 skills 表与文件系统会进入 "入表但 references 缺失" 的脏态
            try {
                copyAuxFiles(tempDir, Paths.get(install.filePath()));
            } catch (IOException | RuntimeException auxError) {
                rollbackInstall(install, auxError);
                throw auxError;
            }
            log.info("导入 Skill 成功：name={}, source=USER_IMPORTED", install.name());
            return install;
        } finally {
            deleteDirRecursive(tempDir);
        }
    }

    /**
     * 安装后 aux 复制失败回滚：删 DB 记录 + 删文件系统目录，恢复到未安装态。
     * 清理过程中的异常只记 WARN，不再抛出（避免掩盖原始 auxError）。
     */
    private void rollbackInstall(SkillInstallation install, Exception cause) {
        log.warn("Skill 导入 aux 复制失败，回滚安装: name={}, error={}",
                install.name(), cause.getMessage());
        try {
            repository.delete(install.name());
        } catch (Exception dbErr) {
            log.warn("回滚 skills 表失败（忽略）: name={}, error={}", install.name(), dbErr.getMessage());
        }
        try {
            deleteDirRecursive(Paths.get(install.filePath()));
        } catch (Exception fsErr) {
            log.warn("回滚 skill 目录失败（忽略）: path={}, error={}", install.filePath(), fsErr.getMessage());
        }
    }

    /**
     * 从 Git 仓库 URL 克隆导入 Skill（当前为 stub，后续实现）。
     *
     * @param gitUrl Git 仓库 URL
     * @throws UnsupportedOperationException 尚未实现
     */
    public SkillInstallation importFromGitUrl(String gitUrl) throws IOException {
        throw new UnsupportedOperationException(
                "Git URL 导入待后续实现（TODO Phase B.4 follow-up）");
    }

    /**
     * 安全解压 zip 到目标目录。
     *
     * <p>防御：</p>
     * <ul>
     *   <li><strong>zip slip</strong>：解压后路径 normalize 后必须仍在 target 子树内</li>
     *   <li><strong>zip bomb — 条目数</strong>：超过 {@value #MAX_ZIP_ENTRIES} 即拒绝</li>
     *   <li><strong>zip bomb — 总字节</strong>：累计解压字节超 {@value #MAX_ZIP_TOTAL_BYTES} 即拒绝</li>
     *   <li><strong>symlink</strong>：通过只走 {@code Files.createDirectories} 与 {@code Files.newOutputStream}
     *       的拷贝路径，不会主动创建符号链接；ZipEntry 也无语义能要求 JDK 创建 symlink</li>
     * </ul>
     */
    private void unzipSafe(MultipartFile file, Path target) throws IOException {
        Path targetAbs = target.toAbsolutePath().normalize();
        long totalBytes = 0;
        int entryCount = 0;

        try (var zis = new ZipInputStream(file.getInputStream())) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ZIP_ENTRIES) {
                    throw new SecurityException("zip 包条目数超过 " + MAX_ZIP_ENTRIES);
                }

                // zip slip 校验：normalize 后必须仍在 target 子树内
                Path entryAbs = target.resolve(entry.getName()).toAbsolutePath().normalize();
                if (!entryAbs.startsWith(targetAbs)) {
                    throw new SecurityException("zip slip detected: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(entryAbs);
                    continue;
                }

                Path parent = entryAbs.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                byte[] buf = new byte[8192];
                try (var out = Files.newOutputStream(entryAbs)) {
                    int n;
                    while ((n = zis.read(buf)) > 0) {
                        totalBytes += n;
                        if (totalBytes > MAX_ZIP_TOTAL_BYTES) {
                            throw new SecurityException(
                                    "zip 解压总字节超 " + MAX_ZIP_TOTAL_BYTES + " 限制");
                        }
                        out.write(buf, 0, n);
                    }
                }
            }
        }
    }

    /**
     * 复制 references / scripts / assets 三个辅助目录到安装目录。
     * 主 SKILL.md 已由 {@link SkillInstaller} 负责写入。
     */
    private void copyAuxFiles(Path src, Path dst) throws IOException {
        for (String sub : new String[]{"references", "scripts", "assets"}) {
            Path subSrc = src.resolve(sub);
            if (Files.isDirectory(subSrc)) {
                Path subDst = dst.resolve(sub);
                Files.createDirectories(subDst);
                try (var stream = Files.walk(subSrc)) {
                    stream.filter(p -> !Files.isDirectory(p)).forEach(p -> {
                        try {
                            Path rel = subSrc.relativize(p);
                            Path targetFile = subDst.resolve(rel.toString());
                            Path parent = targetFile.getParent();
                            if (parent != null) {
                                Files.createDirectories(parent);
                            }
                            Files.copy(p, targetFile, StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
                }
            }
        }
    }

    /**
     * 递归删除目录，失败只记 WARN（不抛出），避免掩盖上游真实异常。
     */
    private void deleteDirRecursive(Path dir) {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            log.warn("清理临时目录失败: {}", dir, e);
        }
    }

    /**
     * 把上传文件名清洗为仅含字母/数字/点/下划线/短横线的 ASCII 串，避免 sourceUri 里混入
     * 可控字符污染日志或后续路径推断。
     */
    private String sanitize(String filename) {
        return filename == null ? "unknown.skill" : filename.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
