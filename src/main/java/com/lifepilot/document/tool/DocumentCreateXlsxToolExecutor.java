package com.lifepilot.document.tool;

import com.lifepilot.document.generator.DocumentGenerationException;
import com.lifepilot.document.generator.ExcelGenerator;
import com.lifepilot.document.generator.SheetData;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * document.create_xlsx 工具执行体。
 *
 * <p>流程与 {@link DocumentCreateDocxToolExecutor} 对称：接收
 * {@code fileName + sheets + sessionId} → {@link #parseSheets} 把 Map 反序列化为
 * {@link SheetData} 列表 → {@link ExcelGenerator} 生成 xlsx 字节 → 落盘到
 * {@code storageDir}/{uuid}_{fileName} → 入 documents 表（origin=agent_generated）+
 * 入 message_attachments（entry_id=null 占位，由 AgentPersistenceHandler 后续回填）
 * → 返回 documentId / fileName / fileSize / downloadUrl。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
public class DocumentCreateXlsxToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(DocumentCreateXlsxToolExecutor.class);
    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String XLSX_EXT = ".xlsx";
    /**
     * 下载路径模板。与 {@code DocumentController.@GetMapping("/{id}/download")} 保持同步，
     * 改动一侧时必须改另一侧 —— 集中为常量便于 grep 追踪耦合。
     */
    private static final String DOWNLOAD_URL_TEMPLATE = "/api/documents/%s/download";
    /** fileName 最大长度（多数文件系统单文件名上限 255 字节，预留扩展名空间）。 */
    private static final int MAX_FILE_NAME_LENGTH = 240;

    private final ExcelGenerator generator;
    private final DocumentRepository documentRepository;
    private final AttachmentRepository attachmentRepository;
    private final String storageDir;

    public DocumentCreateXlsxToolExecutor(ExcelGenerator generator,
                                          DocumentRepository documentRepository,
                                          AttachmentRepository attachmentRepository,
                                          String storageDir) {
        this.generator = generator;
        this.documentRepository = documentRepository;
        this.attachmentRepository = attachmentRepository;
        this.storageDir = storageDir;
    }

    /**
     * 执行 xlsx 生成工具调用。
     *
     * @param input 工具输入，必选 fileName / sheets / sessionId 三参
     * @return 成功时含 documentId / fileName / fileSize / downloadUrl 的结构化结果
     */
    public ToolResult execute(ToolInput input) {
        String fileName;
        List<?> sheetsRaw;
        String sessionId;
        try {
            fileName = input.getParam("fileName", String.class);
            sheetsRaw = input.getParam("sheets", List.class);
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

        // fileName 自动追加 .xlsx（已带则不追加）
        String normalizedFileName = fileName.toLowerCase().endsWith(XLSX_EXT)
                ? fileName : fileName + XLSX_EXT;

        // sheets 反序列化
        List<SheetData> sheets;
        try {
            sheets = parseSheets(sheetsRaw);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("sheets 参数格式非法：" + e.getMessage());
        }

        // 生成字节
        byte[] bytes;
        try {
            bytes = generator.generate(sheets);
        } catch (DocumentGenerationException e) {
            log.warn("xlsx 生成失败：fileName={}, error={}", normalizedFileName, e.getMessage());
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
            log.error("xlsx 落盘失败：filePath={}", filePath, e);
            return ToolResult.error("文档落盘失败：" + e.getMessage());
        }

        // 入 documents 表（origin=agent_generated，entry_id 暂空）
        var record = new DocumentRecord(
                documentId, sessionId, null, normalizedFileName, filePath.toString(),
                bytes.length, XLSX_MIME, DocumentRecord.ORIGIN_AGENT_GENERATED, Instant.now());
        String savedId = documentRepository.save(record);

        // 入 message_attachments 表（entry_id=null，由 AgentPersistenceHandler 回填）
        String downloadUrl = String.format(DOWNLOAD_URL_TEMPLATE, savedId);
        attachmentRepository.saveForEntry(
                null, sessionId, normalizedFileName, filePath.toString(),
                (long) bytes.length, XLSX_MIME, downloadUrl);

        log.info("document.create_xlsx 成功：documentId={}, fileName={}, size={}",
                savedId, normalizedFileName, bytes.length);

        var data = new LinkedHashMap<String, Object>();
        data.put("documentId", savedId);
        data.put("fileName", normalizedFileName);
        data.put("fileSize", (long) bytes.length);
        data.put("downloadUrl", downloadUrl);
        return ToolResult.success(Map.copyOf(data));
    }

    /**
     * 把 Map 反序列化为 {@link SheetData} 列表。期望格式：
     * <pre>
     * [{ "name": "工作表名", "headers": ["列A", "列B"], "rows": [[val1, val2], ...] }]
     * </pre>
     *
     * <p>headers 可选（缺失或空数组 → 不输出表头），rows 可选（缺失 → 空工作表）。
     * 单元格值保留 Number / Boolean / String 类型透传给 POI，生成器按类型写入相应 cell type。</p>
     */
    @SuppressWarnings("unchecked")
    private List<SheetData> parseSheets(List<?> raw) {
        List<SheetData> result = new ArrayList<>();
        for (Object item : raw) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("sheet 项必须是 object，收到：" + item);
            }
            Object nameObj = map.get("name");
            if (!(nameObj instanceof String name) || name.isBlank()) {
                throw new IllegalArgumentException("sheet.name 必须是非空字符串");
            }

            List<String> headers = null;
            Object headersObj = map.get("headers");
            if (headersObj instanceof List<?> headersRaw && !headersRaw.isEmpty()) {
                headers = new ArrayList<>();
                for (Object h : headersRaw) {
                    headers.add(h == null ? "" : h.toString());
                }
            }

            List<List<Object>> rows = new ArrayList<>();
            Object rowsObj = map.get("rows");
            if (rowsObj instanceof List<?> rowsRaw) {
                for (Object rowObj : rowsRaw) {
                    if (!(rowObj instanceof List<?> rowRaw)) {
                        throw new IllegalArgumentException("sheet.rows 项必须是 array");
                    }
                    rows.add(new ArrayList<>((List<Object>) rowRaw));
                }
            }

            result.add(new SheetData(name, headers, rows));
        }
        return result;
    }
}
