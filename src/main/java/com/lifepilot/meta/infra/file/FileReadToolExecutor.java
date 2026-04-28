package com.lifepilot.meta.infra.file;

import com.lifepilot.document.parser.DocumentParserService;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.knowledge.parser.DocumentMetadata;
import com.lifepilot.knowledge.parser.DocumentParseException;
import com.lifepilot.knowledge.parser.ParseResult;
import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.validation.SkillPathWhitelist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 文件读取工具 —— 按扩展名自动路由的统一读取入口。
 *
 * <p>参数互斥顺序：{@code attachmentId} 优先 &gt; {@code path}。</p>
 *
 * <ul>
 *   <li>{@code attachmentId}：对话附件 handle，依赖 {@link AttachmentRepository} 解析实际路径。</li>
 *   <li>{@code path}：本机文件绝对路径（主入口），按扩展名路由到纯文本或结构化文档 reader。</li>
 * </ul>
 *
 * <p>扩展名路由：docx / pdf / md / markdown / mkd / txt / text / log / csv / tsv
 * 等通过 {@link DocumentParserService} 解析；其他扩展名走 {@link BufferedReader} 按行读取。
 * {@code startLine} / {@code endLine} 仅对纯文本生效，结构化文档路径忽略。</p>
 *
 * <p>安全机制：两道校验串联：</p>
 * <ul>
 *   <li>{@link SkillPathWhitelist}：硬约束，{@code file.read} 只允许访问 skills 目录 + 工作区 + 进程目录，
 *       防 LLM 通过绝对路径读取 {@code /etc/passwd} 等系统敏感文件。</li>
 *   <li>{@link PathSecurityChecker}：软约束，校验用户在 {@code application.yml} 中自定义的 allow/deny 列表。</li>
 * </ul>
 *
 * <p>Skill 加载能力已由 {@code skill.load} 工具承担（2026-04-24 重构），
 * 本工具不再接受 {@code skill} 参数。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class FileReadToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(FileReadToolExecutor.class);

    /** 结构化文档截断提示后缀模板。 */
    private static final String FORMATTED_TRUNCATION_HINT = "\n...[内容已截断，maxChars=%d]";

    /**
     * 二进制扩展名 —— 文本读取必失败，直接报错引导 LLM 走 attachment.register。
     *
     * <p>避免用 BufferedReader UTF-8 强行读 PNG/JPG 等图片文件触发
     * {@code MalformedInputException: Input length = 1}。LLM 看到错误后应通过
     * {@code attachment.register} 把文件挂载为对话附件（返回 attachmentId 在最终
     * 回复里 reference），而不是绕路用 base64 编码到 .txt 再 file_read 读出
     * base64 字符串作为文本返回（实测一次冒烟里 LLM 真这么干了，UI 拿到一长串
     * 文本无法渲染图片）。</p>
     */
    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "svg",
            "mp3", "wav", "ogg", "flac", "m4a",
            "mp4", "webm", "mov", "avi", "mkv",
            "zip", "tar", "gz", "rar", "7z",
            "exe", "dll", "so", "dylib",
            "ttf", "otf", "woff", "woff2"
    );

    /**
     * 结构化文档扩展名白名单 —— 命中则走 {@link DocumentParserService}，
     * 获得 metadata / 结构抽取等能力；未命中则按纯文本 BufferedReader 读取（cat 心智，
     * {@code startLine} / {@code endLine} 保留原行为）。
     *
     * <p>只收录 parser 能真正提供结构价值的扩展名：</p>
     * <ul>
     *   <li>md / markdown / mkd —— MarkdownParser 抽取标题、代码块、表格</li>
     *   <li>csv / tsv —— PlainTextParser 段落识别对表格友好</li>
     *   <li>docx / pdf —— 二进制格式，必须 parser 才能拿到文本</li>
     *   <li>xlsx —— ExcelParser 按工作表 / 行 / 单元格结构化抽取</li>
     *   <li>pptx —— PowerpointParser 按幻灯片 / 文本占位符抽取</li>
     * </ul>
     *
     * <p>{@code .txt} / {@code .log} / {@code .text} / {@code .java} / {@code .json} 等
     * 无视觉结构的纯文本类型，直接走 BufferedReader 保留行号、行范围和"已截断"文案，
     * 避免破坏 {@code cat} 心智和 startLine / endLine 参数语义。</p>
     */
    private static final Set<String> FORMATTED_DOCUMENT_EXTENSIONS = Set.of(
            "md", "markdown", "mkd",
            "csv", "tsv",
            "docx", "pdf",
            "xlsx", "pptx"
    );

    private final PathSecurityChecker securityChecker;
    private final int defaultMaxChars;
    /** Skill 目录 + 工作区 + 进程目录硬约束白名单，防 path traversal。 */
    @org.springframework.lang.Nullable
    private final SkillPathWhitelist skillPathWhitelist;
    /** 附件仓储 —— 用于 attachmentId 分支查询真实文件路径，Web 未启用时为 null。 */
    @org.springframework.lang.Nullable
    private final AttachmentRepository attachmentRepository;
    /** 文档解析路由 facade —— 按扩展名分发到对应 parser。 */
    private final DocumentParserService documentParserService;

    /**
     * 便捷构造器 —— 仅用于单元测试，不支持 attachmentId / 白名单 / 结构化文档路径。
     */
    public FileReadToolExecutor(MetaProperties properties) {
        this(properties, DocumentParserService.buildDefault());
    }

    /**
     * 便捷构造器 —— 支持纯文本读取 + 结构化文档路径，不支持 attachmentId / 白名单。
     */
    public FileReadToolExecutor(MetaProperties properties,
                                DocumentParserService documentParserService) {
        var fileConfig = properties.getInfra().getFile();
        this.securityChecker = new PathSecurityChecker(fileConfig);
        this.defaultMaxChars = fileConfig.getDefaultMaxChars();
        this.skillPathWhitelist = null;
        this.attachmentRepository = null;
        this.documentParserService = documentParserService;
    }

    /**
     * 完整构造器 —— 允许注入全部可选依赖（用于生产装配和测试）。
     *
     * @param securityChecker       路径安全校验器（allow/deny 列表）
     * @param defaultMaxChars       默认最大字符数
     * @param skillPathWhitelist    Skill/工作区硬约束白名单，null 表示跳过白名单校验（仅测试场景）
     * @param attachmentRepository  附件仓储，null 表示不支持 attachmentId 参数
     * @param documentParserService 文档解析服务，必选（覆盖纯文本 + 结构化文档路由）
     */
    FileReadToolExecutor(PathSecurityChecker securityChecker, int defaultMaxChars,
                         @org.springframework.lang.Nullable SkillPathWhitelist skillPathWhitelist,
                         @org.springframework.lang.Nullable AttachmentRepository attachmentRepository,
                         DocumentParserService documentParserService) {
        this.securityChecker = securityChecker;
        this.defaultMaxChars = defaultMaxChars;
        this.skillPathWhitelist = skillPathWhitelist;
        this.attachmentRepository = attachmentRepository;
        this.documentParserService = documentParserService;
    }

    /**
     * 执行读取 —— 按 attachmentId / path 双分支互斥路由。
     *
     * @param input 工具输入，两个参数之一必填，可选 encoding / startLine / endLine / maxChars
     * @return 成功时含 content + 元数据的结构化结果，失败时 error
     */
    public ToolResult execute(ToolInput input) {
        int maxChars = input.getOptionalParam("maxChars", Number.class)
                .map(Number::intValue)
                .map(value -> Math.max(1, value))
                .orElse(defaultMaxChars);

        // ★ 1. attachmentId 分支 —— 对话附件 handle，查库拿真实路径
        String attachmentId = input.getOptionalParam("attachmentId", String.class).orElse(null);
        if (attachmentId != null && !attachmentId.isBlank()) {
            return executeAttachmentRead(attachmentId, maxChars);
        }

        // ★ 2. path 分支 —— 本机文件绝对路径（主入口）
        String pathStr;
        try {
            pathStr = input.getParam("path", String.class);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("需要提供 path 或 attachmentId 之一");
        }
        pathStr = PathExpander.expand(pathStr);

        // ★ 2.1 硬约束白名单 —— 防 path traversal，仅允许 skills / 工作区 / 进程目录
        if (skillPathWhitelist != null) {
            try {
                skillPathWhitelist.validate(pathStr);
            } catch (SecurityException e) {
                return ToolResult.error(e.getMessage());
            } catch (IllegalArgumentException e) {
                return ToolResult.error(e.getMessage());
            }
        }

        String encoding = input.getOptionalParam("encoding", String.class)
                .orElse("UTF-8");
        var startLineOpt = input.getOptionalParam("startLine", Number.class)
                .map(Number::intValue);
        var endLineOpt = input.getOptionalParam("endLine", Number.class)
                .map(Number::intValue);

        Path filePath = Path.of(pathStr);

        // ★ 2.2 软约束 —— 用户自定义 allow/deny 列表
        var rejection = securityChecker.check(filePath);
        if (rejection.isPresent()) {
            return ToolResult.error(rejection.get());
        }

        if (!Files.exists(filePath)) {
            return ToolResult.error("文件不存在: " + pathStr);
        }
        if (!Files.isRegularFile(filePath)) {
            return ToolResult.error("路径不是普通文件: " + pathStr);
        }

        // 二进制文件（图片 / 音视频 / 压缩包等）直接报错引导，不要用 BufferedReader 强读
        if (isBinaryFile(filePath)) {
            return ToolResult.error(
                    "file.read 仅支持文本与结构化文档；该文件是二进制（图片 / 音视频 / 压缩包等）。"
                            + "如需在最终回答中向用户展示该文件，请用 attachment.register 工具把它挂载为对话附件，"
                            + "返回的 attachmentId 在最终回复里引用即可（前端会自动渲染图片/下载入口）。"
                            + "路径: " + pathStr);
        }

        // 按扩展名分流：结构化文档走 DocumentParserService，其他走 BufferedReader
        if (isFormattedDocument(filePath)) {
            return executeFormattedRead(filePath, filePath.getFileName().toString(), maxChars);
        }
        return executePlainTextRead(filePath, pathStr, encoding, startLineOpt, endLineOpt, maxChars);
    }

    /** 按扩展名判断是否为二进制（不可文本读取的）文件类型。 */
    private boolean isBinaryFile(Path filePath) {
        String fileName = filePath.getFileName().toString().toLowerCase();
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return false;
        }
        return BINARY_EXTENSIONS.contains(fileName.substring(dot + 1));
    }

    /**
     * 纯文本读取分支 —— 按字节读取，支持行范围 / 编码 / maxChars 截断。
     */
    private ToolResult executePlainTextRead(Path filePath,
                                            String pathStr,
                                            String encoding,
                                            java.util.Optional<Integer> startLineOpt,
                                            java.util.Optional<Integer> endLineOpt,
                                            int maxChars) {
        try {
            Charset charset = Charset.forName(encoding);
            long fileSize = Files.size(filePath);
            var sb = new StringBuilder();
            boolean truncated = false;
            int totalLines = 0;
            int lastIncludedLine = 0;
            int requestedStart = startLineOpt.map(value -> Math.max(1, value)).orElse(1);
            int requestedEnd = endLineOpt
                    .map(value -> Math.max(requestedStart, value))
                    .orElse(Integer.MAX_VALUE);

            try (BufferedReader reader = Files.newBufferedReader(filePath, charset)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    totalLines++;
                    int lineNumber = totalLines;
                    if (lineNumber < requestedStart || lineNumber > requestedEnd) {
                        continue;
                    }
                    if (truncated) {
                        continue;
                    }

                    int projectedLength = sb.length() + line.length() + (sb.isEmpty() ? 0 : 1);
                    if (sb.isEmpty() && line.length() > maxChars) {
                        sb.append(line, 0, maxChars);
                        truncated = true;
                        lastIncludedLine = lineNumber;
                        continue;
                    }
                    if (!sb.isEmpty() && projectedLength > maxChars) {
                        truncated = true;
                        continue;
                    }
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(line);
                    lastIncludedLine = lineNumber;
                }
            }

            String content = sb.toString();
            int actualStart = totalLines == 0 ? 1 : Math.min(requestedStart, totalLines);
            int clampedEnd = totalLines == 0
                    ? 0
                    : Math.max(actualStart, Math.min(requestedEnd == Integer.MAX_VALUE ? totalLines : requestedEnd, totalLines));
            int actualEnd = truncated && lastIncludedLine > 0 ? lastIncludedLine : clampedEnd;

            if (truncated) {
                content += "[文件已截断]";
                content += "\n...[内容已截断，maxChars=" + maxChars
                        + "，显示行 " + actualStart + "-" + actualEnd + "/" + totalLines + "]";
            }

            var data = new LinkedHashMap<String, Object>();
            data.put("content", content);
            data.put("path", filePath.toAbsolutePath().normalize().toString());
            data.put("size", fileSize);
            data.put("totalLines", totalLines);
            data.put("truncated", truncated);
            if (startLineOpt.isPresent() || endLineOpt.isPresent()) {
                data.put("startLine", actualStart);
                data.put("endLine", actualEnd);
            }

            log.debug("文件读取成功: path={}, size={}, totalLines={}, truncated={}",
                    pathStr, fileSize, totalLines, truncated);
            return ToolResult.success(Map.copyOf(data));

        } catch (IOException e) {
            log.error("文件读取失败: path={}, error={}", pathStr, e.getMessage(), e);
            return ToolResult.error("文件读取失败: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolResult.error("不支持的编码: " + encoding);
        }
    }

    /**
     * 结构化文档读取分支 —— 走 {@link DocumentParserService}，附加 metadata 和 fileName。
     *
     * <p>startLine / endLine 对此路径无效（文档格式按语义解析，不是按行）。</p>
     */
    private ToolResult executeFormattedRead(Path filePath, String fileName, int maxChars) {
        if (!documentParserService.supports(filePath)) {
            return ToolResult.error("不支持的文档类型：" + fileName);
        }
        try {
            ParseResult parsed = documentParserService.parse(filePath);
            String rawText = parsed.text() == null ? "" : parsed.text();
            int originalLength = rawText.length();

            String text = rawText;
            boolean truncated = false;
            if (text.length() > maxChars) {
                text = text.substring(0, maxChars) + FORMATTED_TRUNCATION_HINT.formatted(maxChars);
                truncated = true;
            }

            long fileSize;
            try {
                fileSize = Files.size(filePath);
            } catch (IOException e) {
                fileSize = -1L;
            }

            var data = new LinkedHashMap<String, Object>();
            data.put("content", text);
            data.put("path", filePath.toAbsolutePath().normalize().toString());
            data.put("fileName", fileName);
            data.put("size", fileSize);
            data.put("totalChars", originalLength);
            data.put("truncated", truncated);
            if (parsed.metadata() != null) {
                data.put("metadata", buildMetadataMap(parsed.metadata()));
            }

            log.info("结构化文档读取成功：fileName={}, chars={}, truncated={}",
                    fileName, originalLength, truncated);
            return ToolResult.success(Map.copyOf(data));
        } catch (DocumentParseException e) {
            log.warn("结构化文档读取失败：fileName={}, phase={}, error={}",
                    fileName, e.getPhase(), e.getMessage());
            return ToolResult.error("文档解析失败：" + e.getMessage());
        }
    }

    /**
     * attachmentId 读取分支 —— 通过 {@link AttachmentRepository} 查询真实文件路径，
     * 然后复用 {@link #executeFormattedRead} / {@link #executePlainTextRead} 路由。
     */
    private ToolResult executeAttachmentRead(String attachmentId, int maxChars) {
        if (attachmentRepository == null) {
            return ToolResult.error("附件功能未启用");
        }
        var record = attachmentRepository.findById(attachmentId);
        if (record == null) {
            return ToolResult.error("附件不存在：attachmentId=" + attachmentId);
        }
        Path filePath = Path.of(record.filePath());
        String fileName = record.fileName();

        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            return ToolResult.error("附件文件不存在或不是普通文件：" + filePath);
        }
        // 附件分支不做 securityChecker / skillPathWhitelist 检查 —— 附件物理位置由 AttachmentRepository
        // 控制，属于受信目录（~/.zhiwei/data/attachments），无需再与用户侧白名单对齐。

        if (isFormattedDocument(filePath)) {
            return executeFormattedRead(filePath, fileName, maxChars);
        }
        return executePlainTextRead(filePath, filePath.toString(),
                "UTF-8", java.util.Optional.empty(), java.util.Optional.empty(), maxChars);
    }

    /**
     * 按扩展名判断是否为结构化文档 —— 命中则走 {@link DocumentParserService}，
     * 未命中则按纯文本 BufferedReader 读取。
     */
    private boolean isFormattedDocument(Path filePath) {
        String fileName = filePath.getFileName().toString().toLowerCase();
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return false;
        }
        String ext = fileName.substring(dot + 1);
        return FORMATTED_DOCUMENT_EXTENSIONS.contains(ext);
    }

    /**
     * 将 {@link DocumentMetadata} 转换为 LLM 友好的 Map，Optional 空值不写入键，
     * Instant 以 ISO 8601 字符串输出，extraProperties 原样透传。
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
