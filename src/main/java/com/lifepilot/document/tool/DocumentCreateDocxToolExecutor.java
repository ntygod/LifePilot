package com.lifepilot.document.tool;

import com.lifepilot.document.generator.DocumentGenerationException;
import com.lifepilot.document.generator.DocumentGenerator;
import com.lifepilot.document.model.DocumentRecord;
import com.lifepilot.document.repository.DocumentRepository;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * document.create_docx 工具执行体。
 *
 * <p>流程：接收 {@code fileName + markdown + sessionId} → 调用 DocumentGenerator
 * 生成 docx 字节 → 落盘到 {@code storageDir}/{uuid}_{fileName} → 入 session_documents
 * 表（origin=agent_generated） + 入 message_attachments（entry_id=null 占位，由
 * AgentPersistenceHandler 后续回填）→ 返回 documentId / fileName / fileSize / downloadUrl。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
public class DocumentCreateDocxToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(DocumentCreateDocxToolExecutor.class);
    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String DOCX_EXT = ".docx";
    /**
     * 下载路径模板。与 {@code DocumentController.@GetMapping("/{id}/download")} 保持同步，
     * 改动一侧时必须改另一侧 —— 集中为常量便于 grep 追踪耦合。
     */
    private static final String DOWNLOAD_URL_TEMPLATE = "/api/documents/%s/download";
    /** fileName 最大长度（多数文件系统单文件名上限 255 字节，预留扩展名空间）。 */
    private static final int MAX_FILE_NAME_LENGTH = 240;

    private final DocumentGenerator generator;
    private final DocumentRepository documentRepository;
    private final AttachmentRepository attachmentRepository;
    private final String storageDir;

    public DocumentCreateDocxToolExecutor(DocumentGenerator generator,
                                          DocumentRepository documentRepository,
                                          AttachmentRepository attachmentRepository,
                                          String storageDir) {
        this.generator = generator;
        this.documentRepository = documentRepository;
        this.attachmentRepository = attachmentRepository;
        this.storageDir = storageDir;
    }

    /**
     * 执行 docx 生成工具调用。
     *
     * @param input 工具输入，必选 fileName / markdown / sessionId 三参
     * @return 成功时含 documentId / fileName / fileSize / downloadUrl 的结构化结果
     */
    public ToolResult execute(ToolInput input) {
        String fileName;
        String markdown;
        String sessionId;
        try {
            fileName = input.getParam("fileName", String.class);
            markdown = input.getParam("markdown", String.class);
            sessionId = input.getParam("sessionId", String.class);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("参数校验失败：" + e.getMessage());
        }

        // fileName 安全校验 —— LLM 可能产出含路径分隔符 / .. / NUL 等越界字符串，
        // 直接拼接会触发 Path.resolve 路径穿越（写到 storageDir 以外），必须拦截
        if (fileName.contains("/") || fileName.contains("\\")
                || fileName.contains("\0") || fileName.contains("..")) {
            return ToolResult.error("fileName 含非法字符（禁止路径分隔符、.. 与 NUL）");
        }
        if (fileName.length() > MAX_FILE_NAME_LENGTH) {
            return ToolResult.error("fileName 超长（>" + MAX_FILE_NAME_LENGTH + " 字符）");
        }

        // fileName 自动追加 .docx（已带则不追加）
        String normalizedFileName = fileName.toLowerCase().endsWith(DOCX_EXT)
                ? fileName : fileName + DOCX_EXT;

        // 生成字节
        byte[] bytes;
        try {
            bytes = generator.generate(markdown);
        } catch (DocumentGenerationException e) {
            log.warn("docx 生成失败：fileName={}, error={}", normalizedFileName, e.getMessage());
            return ToolResult.error("文档生成失败：" + e.getMessage());
        }

        // 落盘 —— UUID + 原文件名组合防冲突
        Path storageRoot = Paths.get(storageDir);
        String documentId = UUID.randomUUID().toString();
        String storedName = documentId + "_" + normalizedFileName;
        Path filePath = storageRoot.resolve(storedName);
        try {
            Files.createDirectories(storageRoot);
            Files.write(filePath, bytes);
        } catch (IOException e) {
            log.error("docx 落盘失败：filePath={}", filePath, e);
            return ToolResult.error("文档落盘失败：" + e.getMessage());
        }

        // 入 session_documents 表（origin=agent_generated，entry_id 暂空）
        var record = new DocumentRecord(
                documentId, sessionId, null, normalizedFileName, filePath.toString(),
                bytes.length, DOCX_MIME, DocumentRecord.ORIGIN_AGENT_GENERATED, Instant.now());
        String savedId = documentRepository.save(record);

        // 入 message_attachments 表（entry_id=null，由 AgentPersistenceHandler 回填）
        String downloadUrl = String.format(DOWNLOAD_URL_TEMPLATE, savedId);
        attachmentRepository.saveForEntry(
                null, sessionId, normalizedFileName, filePath.toString(),
                (long) bytes.length, DOCX_MIME, downloadUrl);

        log.info("document.create_docx 成功：documentId={}, fileName={}, size={}",
                savedId, normalizedFileName, bytes.length);

        var data = new LinkedHashMap<String, Object>();
        data.put("documentId", savedId);
        data.put("fileName", normalizedFileName);
        data.put("fileSize", (long) bytes.length);
        data.put("downloadUrl", downloadUrl);
        return ToolResult.success(Map.copyOf(data));
    }
}
