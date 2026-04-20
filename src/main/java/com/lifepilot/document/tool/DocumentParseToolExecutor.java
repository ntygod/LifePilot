package com.lifepilot.document.tool;

import com.lifepilot.document.parser.DocumentParserService;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.knowledge.parser.DocumentMetadata;
import com.lifepilot.knowledge.parser.DocumentParseException;
import com.lifepilot.knowledge.parser.ParseResult;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * document.parse 工具的执行体。
 *
 * <p>解析对话附件或本地路径的文档，返回 LLM 可读的文本内容加元数据。
 * 参数 attachmentId 与 path 二选一，当两者同时提供时优先使用 attachmentId。</p>
 *
 * <p>典型返回结构：</p>
 * <ul>
 *   <li>content —— 文本内容（可能被截断）</li>
 *   <li>fileName —— 文件名</li>
 *   <li>totalChars —— 原始文本字符数（截断前）</li>
 *   <li>truncated —— 是否发生截断</li>
 *   <li>metadata —— 解析得到的文档元数据</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-20
 */
public class DocumentParseToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(DocumentParseToolExecutor.class);

    /** 截断提示后缀模板。 */
    private static final String TRUNCATION_HINT = "\n...[内容已截断，maxChars=%d]";

    private final DocumentParserService parserService;
    private final AttachmentRepository attachmentRepository;
    private final int defaultMaxChars;

    /**
     * 构造 document.parse 执行器。
     *
     * @param parserService        文档解析服务（路由 facade）
     * @param attachmentRepository 附件仓储，用于按 attachmentId 查路径
     * @param defaultMaxChars      未显式传入 maxChars 时采用的默认最大字符数
     */
    public DocumentParseToolExecutor(DocumentParserService parserService,
                                     AttachmentRepository attachmentRepository,
                                     int defaultMaxChars) {
        this.parserService = parserService;
        this.attachmentRepository = attachmentRepository;
        this.defaultMaxChars = defaultMaxChars;
    }

    /**
     * 执行解析。
     *
     * @param input 工具输入，参数 attachmentId 或 path 至少提供其一，可选 maxChars
     * @return 结构化解析结果
     */
    public ToolResult execute(ToolInput input) {
        String attachmentId = input.getOptionalParam("attachmentId", String.class).orElse(null);
        String path = input.getOptionalParam("path", String.class).orElse(null);
        boolean hasAttachmentId = attachmentId != null && !attachmentId.isBlank();
        boolean hasPath = path != null && !path.isBlank();
        if (!hasAttachmentId && !hasPath) {
            return ToolResult.error("需要提供 attachmentId 或 path 之一");
        }

        int maxChars = input.getOptionalParam("maxChars", Number.class)
                .map(Number::intValue)
                .map(v -> Math.max(1, v))
                .orElse(defaultMaxChars);

        // 解析文件路径与展示名，attachmentId 优先于 path
        Path filePath;
        String fileName;
        if (hasAttachmentId) {
            var record = attachmentRepository.findById(attachmentId);
            if (record == null) {
                return ToolResult.error("附件不存在：attachmentId=" + attachmentId);
            }
            filePath = Paths.get(record.filePath());
            fileName = record.fileName();
        } else {
            filePath = Paths.get(path);
            fileName = filePath.getFileName().toString();
        }

        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            return ToolResult.error("文件不存在或不是普通文件：" + filePath);
        }
        if (!parserService.supports(filePath)) {
            return ToolResult.error("不支持的文档类型：" + fileName);
        }

        try {
            ParseResult parsed = parserService.parse(filePath);
            String rawText = parsed.text() == null ? "" : parsed.text();
            int originalLength = rawText.length();

            String text = rawText;
            boolean truncated = false;
            if (text.length() > maxChars) {
                text = text.substring(0, maxChars) + TRUNCATION_HINT.formatted(maxChars);
                truncated = true;
            }

            var data = new LinkedHashMap<String, Object>();
            data.put("content", text);
            data.put("fileName", fileName);
            data.put("totalChars", originalLength);
            data.put("truncated", truncated);
            if (parsed.metadata() != null) {
                data.put("metadata", buildMetadataMap(parsed.metadata()));
            }

            log.info("document.parse 成功：fileName={}, chars={}, truncated={}",
                    fileName, originalLength, truncated);
            return ToolResult.success(Map.copyOf(data));
        } catch (DocumentParseException e) {
            log.warn("document.parse 失败：fileName={}, phase={}, error={}",
                    fileName, e.getPhase(), e.getMessage());
            return ToolResult.error("文档解析失败：" + e.getMessage());
        }
    }

    /**
     * 将 {@link DocumentMetadata} 转换为 LLM 友好的 Map，Optional 空值不写入键，
     * Instant 以 ISO 8601 字符串输出，extraProperties 原样透传。
     *
     * @param metadata 原始元数据
     * @return 扁平化后的键值 Map
     */
    private Map<String, Object> buildMetadataMap(DocumentMetadata metadata) {
        var map = new LinkedHashMap<String, Object>();
        metadata.title().ifPresent(v -> map.put("title", v));
        metadata.author().ifPresent(v -> map.put("author", v));
        metadata.createdAt().map(Instant::toString).ifPresent(v -> map.put("createdAt", v));
        metadata.modifiedAt().map(Instant::toString).ifPresent(v -> map.put("modifiedAt", v));
        map.put("pageCount", metadata.pageCount());
        map.put("wordCount", metadata.wordCount());
        metadata.language().ifPresent(v -> map.put("language", v));
        if (metadata.extraProperties() != null && !metadata.extraProperties().isEmpty()) {
            map.put("extraProperties", Map.copyOf(metadata.extraProperties()));
        }
        return Map.copyOf(map);
    }
}
