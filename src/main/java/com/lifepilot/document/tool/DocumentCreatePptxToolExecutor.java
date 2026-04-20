package com.lifepilot.document.tool;

import com.lifepilot.document.generator.DocumentGenerationException;
import com.lifepilot.document.generator.PowerpointGenerator;
import com.lifepilot.document.generator.SlideData;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * document.create_pptx 工具执行体。
 *
 * <p>流程与 {@link DocumentCreateDocxToolExecutor} / {@link DocumentCreateXlsxToolExecutor}
 * 对称：接收 {@code fileName + slides + sessionId} → {@link #parseSlides} 把 Map
 * 反序列化为 {@link SlideData} 列表 → {@link PowerpointGenerator} 生成 pptx 字节 →
 * 落盘到 {@code storageDir}/{uuid}_{fileName} → 入 session_documents 表
 * （origin=agent_generated）+ 入 message_attachments（entry_id=null 占位，由
 * AgentPersistenceHandler 后续回填）→ 返回 documentId / fileName / fileSize /
 * downloadUrl。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
public class DocumentCreatePptxToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(DocumentCreatePptxToolExecutor.class);
    private static final String PPTX_MIME =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    private static final String PPTX_EXT = ".pptx";
    /**
     * 下载路径模板。与 {@code DocumentController.@GetMapping("/{id}/download")} 保持同步，
     * 改动一侧时必须改另一侧 —— 集中为常量便于 grep 追踪耦合。
     */
    private static final String DOWNLOAD_URL_TEMPLATE = "/api/documents/%s/download";
    /** fileName 最大长度（多数文件系统单文件名上限 255 字节，预留扩展名空间）。 */
    private static final int MAX_FILE_NAME_LENGTH = 240;

    private final PowerpointGenerator generator;
    private final SessionDocumentRepository sessionDocumentRepository;
    private final AttachmentRepository attachmentRepository;
    private final String storageDir;

    public DocumentCreatePptxToolExecutor(PowerpointGenerator generator,
                                          SessionDocumentRepository sessionDocumentRepository,
                                          AttachmentRepository attachmentRepository,
                                          String storageDir) {
        this.generator = generator;
        this.sessionDocumentRepository = sessionDocumentRepository;
        this.attachmentRepository = attachmentRepository;
        this.storageDir = storageDir;
    }

    /**
     * 执行 pptx 生成工具调用。
     *
     * @param input 工具输入，必选 fileName / slides / sessionId 三参
     * @return 成功时含 documentId / fileName / fileSize / downloadUrl 的结构化结果
     */
    public ToolResult execute(ToolInput input) {
        String fileName;
        List<?> slidesRaw;
        String sessionId;
        try {
            fileName = input.getParam("fileName", String.class);
            slidesRaw = input.getParam("slides", List.class);
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

        // fileName 自动追加 .pptx（已带则不追加）
        String normalizedFileName = fileName.toLowerCase().endsWith(PPTX_EXT)
                ? fileName : fileName + PPTX_EXT;

        // slides 反序列化
        List<SlideData> slides;
        try {
            slides = parseSlides(slidesRaw);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("slides 参数格式非法：" + e.getMessage());
        }

        // 生成字节
        byte[] bytes;
        try {
            bytes = generator.generate(slides);
        } catch (DocumentGenerationException e) {
            log.warn("pptx 生成失败：fileName={}, error={}", normalizedFileName, e.getMessage());
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
            log.error("pptx 落盘失败：filePath={}", filePath, e);
            return ToolResult.error("文档落盘失败：" + e.getMessage());
        }

        // 入 session_documents 表（origin=agent_generated，entry_id 暂空）
        var record = new SessionDocumentRecord(
                documentId, sessionId, null, normalizedFileName, filePath.toString(),
                bytes.length, PPTX_MIME, SessionDocumentRecord.ORIGIN_AGENT_GENERATED, Instant.now());
        String savedId = sessionDocumentRepository.save(record);

        // 入 message_attachments 表（entry_id=null，由 AgentPersistenceHandler 回填）
        String downloadUrl = String.format(DOWNLOAD_URL_TEMPLATE, savedId);
        attachmentRepository.saveForEntry(
                null, sessionId, normalizedFileName, filePath.toString(),
                (long) bytes.length, PPTX_MIME, downloadUrl);

        log.info("document.create_pptx 成功：documentId={}, fileName={}, size={}",
                savedId, normalizedFileName, bytes.length);

        var data = new LinkedHashMap<String, Object>();
        data.put("documentId", savedId);
        data.put("fileName", normalizedFileName);
        data.put("fileSize", (long) bytes.length);
        data.put("downloadUrl", downloadUrl);
        return ToolResult.success(Map.copyOf(data));
    }

    /**
     * 把 Map 反序列化为 {@link SlideData} 列表。期望格式：
     * <pre>
     * [{ "title": "首页", "bullets": ["要点 A", "要点 B"], "notes": "讲稿" }]
     * </pre>
     *
     * <p>title / notes 可选（非 String 或缺失时降级为 null → 生成器不渲染对应区域）；
     * bullets 可选（缺失或非 List → 空列表，生成器不渲染要点框）。
     * bullets 子元素非 String 时调用 {@code toString()} 兜底，null 归一化为空字符串
     * —— 保证 SlideData 的 compact constructor 不因 {@code List.copyOf} 遇到 null 元素 NPE。</p>
     */
    private List<SlideData> parseSlides(List<?> raw) {
        List<SlideData> result = new ArrayList<>();
        for (Object item : raw) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("slide 项必须是 object，收到：" + item);
            }
            String title = map.get("title") instanceof String t ? t : null;
            String notes = map.get("notes") instanceof String n ? n : null;

            List<String> bullets = new ArrayList<>();
            Object bulletsObj = map.get("bullets");
            if (bulletsObj instanceof List<?> bulletsRaw) {
                for (Object b : bulletsRaw) {
                    bullets.add(b == null ? "" : b.toString());
                }
            }

            result.add(new SlideData(title, bullets, notes));
        }
        return result;
    }
}
