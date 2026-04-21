package com.lifepilot.document.tool;

import com.lifepilot.document.generator.DocumentGenerationException;
import com.lifepilot.document.model.SessionDocumentRecord;
import com.lifepilot.document.repository.SessionDocumentRepository;
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
 * document.create_* 工具执行体抽象模板 —— 聚合 docx / xlsx / pptx 三类工具重复的落盘流程。
 *
 * <p>{@link #execute(ToolInput)} 为 final 模板方法, 按顺序执行:</p>
 * <ol>
 *   <li>读 {@code fileName} 参数 + 上下文 {@code sessionId};</li>
 *   <li>fileName 安全校验 (禁止 {@code / \ \0 ..} + 超长);</li>
 *   <li>自动追加 {@link #ext()} 扩展名 (已带则不重复);</li>
 *   <li>调用 {@link #generateBytes(ToolInput)} 生成文档字节 (子类实现);</li>
 *   <li>落盘到 {@code storageDir}/{uuid}_{fileName};</li>
 *   <li>入 {@code session_documents} + {@code message_attachments} 两表 (entry_id 占位);</li>
 *   <li>返回 {@code documentId / fileName / fileSize / downloadUrl}。</li>
 * </ol>
 *
 * <p>子类只需提供 MIME / 扩展名 / 字节生成逻辑, 流程骨架由本类控制, 避免三份字面复制。
 * {@link #generateBytes(ToolInput)} 允许抛 {@link IllegalArgumentException} (参数解析失败)
 * 和 {@link DocumentGenerationException} (生成器失败), 均由本类捕获转 {@link ToolResult#error}。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
public abstract class AbstractDocumentCreateToolExecutor {

    /**
     * 下载路径模板。与 {@code DocumentController.@GetMapping("/{id}/download")} 保持同步,
     * 改动一侧时必须改另一侧 —— 集中为常量便于 grep 追踪耦合。
     */
    protected static final String DOWNLOAD_URL_TEMPLATE = "/api/documents/%s/download";
    /** fileName 最大长度 (多数文件系统单文件名上限 255 字节, 预留扩展名空间)。 */
    protected static final int MAX_FILE_NAME_LENGTH = 240;

    /** 日志按运行时类名取, 子类无需自己声明 logger。 */
    private final Logger log = LoggerFactory.getLogger(getClass());

    protected final SessionDocumentRepository sessionDocumentRepository;
    protected final AttachmentRepository attachmentRepository;
    protected final String storageDir;

    protected AbstractDocumentCreateToolExecutor(SessionDocumentRepository sessionDocumentRepository,
                                                 AttachmentRepository attachmentRepository,
                                                 String storageDir) {
        this.sessionDocumentRepository = sessionDocumentRepository;
        this.attachmentRepository = attachmentRepository;
        this.storageDir = storageDir;
    }

    /**
     * 产物 MIME type, 入 {@code session_documents.mime_type} 和 attachment。
     *
     * @return MIME 字符串, 例 {@code application/vnd.openxmlformats-officedocument.wordprocessingml.document}
     */
    protected abstract String mime();

    /**
     * 产物扩展名 (含点号), 用于 fileName 自动补齐。
     *
     * @return 扩展名字符串, 例 {@code .docx} / {@code .xlsx} / {@code .pptx}
     */
    protected abstract String ext();

    /**
     * 从 {@link ToolInput} 解析业务参数并生成文档字节。
     *
     * <p>实现可自由抛:</p>
     * <ul>
     *   <li>{@link IllegalArgumentException} — 参数校验失败 (由本类捕获转"参数校验失败"错误);</li>
     *   <li>{@link DocumentGenerationException} — 生成器失败 (由本类捕获转"文档生成失败"错误)。</li>
     * </ul>
     *
     * @param input 工具输入
     * @return 文档字节
     */
    protected abstract byte[] generateBytes(ToolInput input);

    /**
     * 模板方法: docx / xlsx / pptx 三者共用的落盘流程。
     *
     * @param input 工具输入, 必选 fileName + 上下文 sessionId
     * @return 成功时含 documentId / fileName / fileSize / downloadUrl 的结构化结果
     */
    public final ToolResult execute(ToolInput input) {
        // 1) fileName 必选参数 —— 子类参数解析由 generateBytes 负责, 这里只先取 fileName
        String fileName;
        try {
            fileName = input.getParam("fileName", String.class);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("参数校验失败：" + e.getMessage());
        }
        // sessionId 属执行上下文, 由 ToolExecutionCoordinator 注入, 不在 LLM schema 中暴露
        String sessionId = input.getContextValue("sessionId", String.class).orElse(null);
        if (sessionId == null || sessionId.isBlank()) {
            return ToolResult.error("缺少执行上下文 sessionId（应由 runtime 注入）");
        }

        // 2) fileName 安全校验 —— LLM 可能产出含路径分隔符 / .. / NUL 等越界字符串,
        // 直接拼接会触发 Path.resolve 路径穿越 (写到 storageDir 以外), 必须拦截
        if (fileName.contains("/") || fileName.contains("\\")
                || fileName.contains("\0") || fileName.contains("..")) {
            return ToolResult.error("fileName 含非法字符（禁止路径分隔符、.. 与 NUL）");
        }
        if (fileName.length() > MAX_FILE_NAME_LENGTH) {
            return ToolResult.error("fileName 超长（>" + MAX_FILE_NAME_LENGTH + " 字符）");
        }

        // 3) fileName 自动追加扩展名 (已带则不追加)
        String normalizedFileName = fileName.toLowerCase().endsWith(ext())
                ? fileName : fileName + ext();

        // 4) 生成字节 —— 子类负责解析业务参数, 异常由本类统一转 ToolResult.error
        byte[] bytes;
        try {
            bytes = generateBytes(input);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("参数校验失败：" + e.getMessage());
        } catch (DocumentGenerationException e) {
            log.warn("{} 生成失败：fileName={}, error={}", ext(), normalizedFileName, e.getMessage());
            return ToolResult.error("文档生成失败：" + e.getMessage());
        }

        // 5) 落盘 —— UUID + 原文件名组合防冲突
        Path storageRoot = Paths.get(storageDir);
        String documentId = UUID.randomUUID().toString();
        String storedName = documentId + "_" + normalizedFileName;
        Path filePath = storageRoot.resolve(storedName);
        try {
            Files.createDirectories(storageRoot);
            Files.write(filePath, bytes);
        } catch (IOException e) {
            log.error("{} 落盘失败：filePath={}", ext(), filePath, e);
            return ToolResult.error("文档落盘失败：" + e.getMessage());
        }

        // 6) 入 session_documents 表 (origin=agent_generated, entry_id 暂空)
        // P3 扩展字段：sourcePath=null（AI 产物非 path 源）、latestVersion=0（未被 patch）
        var record = new SessionDocumentRecord(
                documentId, sessionId, null, normalizedFileName, filePath.toString(),
                bytes.length, mime(), SessionDocumentRecord.ORIGIN_AGENT_GENERATED,
                null, 0, Instant.now());
        String savedId = sessionDocumentRepository.save(record);

        // 入 message_attachments 表 (entry_id=null, 由 AgentPersistenceHandler 回填)
        String downloadUrl = String.format(DOWNLOAD_URL_TEMPLATE, savedId);
        attachmentRepository.saveForEntry(
                null, sessionId, normalizedFileName, filePath.toString(),
                (long) bytes.length, mime(), downloadUrl);

        log.info("document.create_{} 成功：documentId={}, fileName={}, size={}",
                extLogTag(), savedId, normalizedFileName, bytes.length);

        // 7) 结构化返回
        var data = new LinkedHashMap<String, Object>();
        data.put("documentId", savedId);
        data.put("fileName", normalizedFileName);
        data.put("fileSize", (long) bytes.length);
        data.put("downloadUrl", downloadUrl);
        return ToolResult.success(Map.copyOf(data));
    }

    /** 日志 tag —— 从 {@link #ext()} 去掉点号, 例 {@code .docx} → {@code docx}。 */
    private String extLogTag() {
        String e = ext();
        return e.startsWith(".") ? e.substring(1) : e;
    }
}
