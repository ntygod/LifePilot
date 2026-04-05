package com.lifepilot.marketplace.clawhub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * ClawHub zip 包解压器 — 将下载的 zip 解压到 Skill 安装目录。
 *
 * <p>解压过程中进行路径穿越防护和大小限制检查。
 * 解压后的 SKILL.md 可直接被 {@code MarkdownSkillLoader} 加载。</p>
 *
 * @author zsg
 * @since 2026-04-03
 */
public class ClawHubZipExtractor {

    private static final Logger log = LoggerFactory.getLogger(ClawHubZipExtractor.class);

    /** 解压后最大总大小：10MB。 */
    private static final long MAX_UNCOMPRESSED_SIZE = 10 * 1024 * 1024;

    /** 解压最大条目数。 */
    private static final int MAX_ENTRIES = 50;

    /**
     * 将 zip 字节解压到目标文件夹。
     *
     * @param zipBytes     zip 文件内容
     * @param targetFolder 目标文件夹（如 ~/.zhiwei/skills/{slug}/）
     * @return 目标文件夹路径
     * @throws IOException 解压失败、路径穿越或超出限制时抛出
     */
    public Path extract(byte[] zipBytes, Path targetFolder) throws IOException {
        Files.createDirectories(targetFolder);
        Path normalizedTarget = targetFolder.toAbsolutePath().normalize();

        long totalSize = 0;
        int entryCount = 0;

        try (var zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ENTRIES) {
                    throw new IOException("ClawHub zip 条目数超过限制: " + MAX_ENTRIES);
                }

                // 跳过目录条目
                if (entry.isDirectory()) {
                    zis.closeEntry();
                    continue;
                }

                // 路径穿越防护
                Path entryPath = normalizedTarget.resolve(entry.getName()).normalize();
                if (!entryPath.startsWith(normalizedTarget)) {
                    throw new IOException("检测到路径穿越: " + entry.getName());
                }

                // 创建父目录
                Files.createDirectories(entryPath.getParent());

                // 读取内容并检查大小
                byte[] content = zis.readAllBytes();
                totalSize += content.length;
                if (totalSize > MAX_UNCOMPRESSED_SIZE) {
                    throw new IOException("ClawHub zip 解压总大小超过限制: " + MAX_UNCOMPRESSED_SIZE / 1024 + "KB");
                }

                Files.write(entryPath, content);
                zis.closeEntry();
            }
        }

        log.info("ClawHub zip 解压完成: target={}, entries={}, totalSize={}KB",
                targetFolder, entryCount, totalSize / 1024);
        return targetFolder;
    }
}
