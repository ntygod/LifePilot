package com.lifepilot.knowledge.detect;

import com.lifepilot.knowledge.model.DuplicateCheckResult;
import com.lifepilot.knowledge.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 重复文档检测器 — 基于 SHA-256 内容哈希检测重复。
 *
 * <p>同一知识库内重复文档阻止导入，跨知识库允许相同内容。
 *
 * @author zsg
 * @since 2026-02-25
 */
public class DuplicateDetector {

    private static final Logger log = LoggerFactory.getLogger(DuplicateDetector.class);

    private final DocumentRepository docRepository;

    /**
     * 构造重复文档检测器。
     *
     * @param docRepository 文档数据访问层
     */
    public DuplicateDetector(DocumentRepository docRepository) {
        this.docRepository = docRepository;
        log.info("DuplicateDetector 初始化完成");
    }

    /**
     * 检测文件是否与指定知识库中已有文档重复。
     *
     * <p>计算文件的 SHA-256 哈希，在同一知识库内查找是否存在相同哈希的文档。
     * 跨知识库的相同内容不视为重复。
     *
     * @param kbId     知识库 ID
     * @param filePath 文件路径
     * @return 重复检测结果
     */
    public DuplicateCheckResult check(String kbId, Path filePath) {
        String contentHash = computeHash(filePath);

        // 在同一知识库内按哈希精确查找，避免加载全部文档
        var duplicateId = docRepository.findIdByKnowledgeBaseIdAndContentHash(kbId, contentHash);
        if (duplicateId.isPresent()) {
            log.info("检测到重复文档: kbId={}, hash={}, existingDocId={}", kbId, contentHash, duplicateId.get());
            return new DuplicateCheckResult(true, contentHash, duplicateId);
        }

        return new DuplicateCheckResult(false, contentHash, Optional.empty());
    }

    /**
     * 计算文件的 SHA-256 内容哈希。
     *
     * @param filePath 文件路径
     * @return 十六进制哈希字符串
     */
    public String computeHash(Path filePath) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var is = Files.newInputStream(filePath);
                 var dis = new java.security.DigestInputStream(is, digest)) {
                byte[] buf = new byte[8192];
                while (dis.read(buf) != -1) { /* 消费流 */ }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        } catch (IOException e) {
            throw new RuntimeException("文件读取失败: " + filePath, e);
        }
    }
}
