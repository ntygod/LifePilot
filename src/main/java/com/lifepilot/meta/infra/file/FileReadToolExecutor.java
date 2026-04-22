package com.lifepilot.meta.infra.file;

import com.lifepilot.document.parser.DocumentParserService;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.knowledge.parser.DocumentMetadata;
import com.lifepilot.knowledge.parser.DocumentParseException;
import com.lifepilot.knowledge.parser.ParseResult;
import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文件读取工具 —— 按扩展名自动路由的统一读取入口。
 *
 * <p>参数互斥顺序：{@code attachmentId} 优先 &gt; {@code path} &gt; {@code skill}。</p>
 *
 * <ul>
 *   <li>{@code attachmentId}:对话附件 handle,依赖 {@link AttachmentRepository} 解析实际路径。</li>
 *   <li>{@code path}:本机文件绝对路径(主入口),按扩展名路由到纯文本或结构化文档 reader。</li>
 *   <li>{@code skill}:加载一个或多个技能指南 SKILL.md(保留原行为)。</li>
 * </ul>
 *
 * <p>扩展名路由:docx / pdf / md / markdown / mkd / txt / text / log / csv / tsv
 * 等通过 {@link DocumentParserService} 解析;其他扩展名走 {@link BufferedReader} 按行读取。
 * {@code startLine} / {@code endLine} 仅对纯文本生效,结构化文档路径忽略。</p>
 *
 * <p>安全机制:通过 {@link PathSecurityChecker} 校验白名单/黑名单。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class FileReadToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(FileReadToolExecutor.class);

    /** 结构化文档截断提示后缀模板。 */
    private static final String FORMATTED_TRUNCATION_HINT = "\n...[内容已截断,maxChars=%d]";

    /**
     * 结构化文档扩展名白名单 —— 命中则走 {@link DocumentParserService},
     * 获得 metadata / 结构抽取等能力;未命中则按纯文本 BufferedReader 读取(cat 心智,
     * {@code startLine} / {@code endLine} 保留原行为)。
     *
     * <p>只收录 parser 能真正提供结构价值的扩展名:</p>
     * <ul>
     *   <li>md / markdown / mkd —— MarkdownParser 抽取标题、代码块、表格</li>
     *   <li>csv / tsv —— PlainTextParser 段落识别对表格友好</li>
     *   <li>docx / pdf —— 二进制格式,必须 parser 才能拿到文本</li>
     *   <li>xlsx —— ExcelParser 按工作表/行/单元格结构化抽取</li>
     *   <li>pptx —— PowerpointParser 按幻灯片/文本占位符抽取</li>
     * </ul>
     *
     * <p>{@code .txt} / {@code .log} / {@code .text} / {@code .java} / {@code .json} 等
     * 无视觉结构的纯文本类型,直接走 BufferedReader 保留行号、行范围和"已截断"文案,
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
    /** Skill 目录路径（如 ~/.zhiwei/skills），为 null 时不支持 skill 参数。 */
    @org.springframework.lang.Nullable
    private final String skillDirectory;
    /** 动态工具注册中心，用于解析 mcp: 前缀的 Skill 加载请求。 */
    @org.springframework.lang.Nullable
    private final DynamicToolRegistry toolRegistry;
    /** 附件仓储 —— 用于 attachmentId 分支查询真实文件路径,Web 未启用时为 null。 */
    @org.springframework.lang.Nullable
    private final AttachmentRepository attachmentRepository;
    /** 文档解析路由 facade —— 按扩展名分发到对应 parser。 */
    private final DocumentParserService documentParserService;

    /**
     * 便捷构造器 —— 仅用于单元测试,不支持 skill / attachmentId / 结构化文档路径。
     */
    public FileReadToolExecutor(MetaProperties properties) {
        this(properties, DocumentParserService.buildDefault());
    }

    /**
     * 便捷构造器 —— 支持纯文本读取 + 结构化文档路径,不支持 skill / attachmentId。
     */
    public FileReadToolExecutor(MetaProperties properties,
                                DocumentParserService documentParserService) {
        var fileConfig = properties.getInfra().getFile();
        this.securityChecker = new PathSecurityChecker(fileConfig);
        this.defaultMaxChars = fileConfig.getDefaultMaxChars();
        this.skillDirectory = null;
        this.toolRegistry = null;
        this.attachmentRepository = null;
        this.documentParserService = documentParserService;
    }

    /**
     * 完整构造器 —— 允许注入全部可选依赖(用于生产装配和测试）。
     *
     * @param securityChecker       路径安全校验器
     * @param defaultMaxChars       默认最大字符数
     * @param skillDirectory        Skill 目录路径,null 表示不支持 skill 参数
     * @param toolRegistry          动态工具注册中心,null 表示不支持 mcp: 前缀
     * @param attachmentRepository  附件仓储,null 表示不支持 attachmentId 参数
     * @param documentParserService 文档解析服务,必选(覆盖纯文本 + 结构化文档路由）
     */
    FileReadToolExecutor(PathSecurityChecker securityChecker, int defaultMaxChars,
                         @org.springframework.lang.Nullable String skillDirectory,
                         @org.springframework.lang.Nullable DynamicToolRegistry toolRegistry,
                         @org.springframework.lang.Nullable AttachmentRepository attachmentRepository,
                         DocumentParserService documentParserService) {
        this.securityChecker = securityChecker;
        this.defaultMaxChars = defaultMaxChars;
        this.skillDirectory = skillDirectory;
        this.toolRegistry = toolRegistry;
        this.attachmentRepository = attachmentRepository;
        this.documentParserService = documentParserService;
    }

    /**
     * 执行读取 —— 按 skill / attachmentId / path 三分支互斥路由。
     *
     * @param input 工具输入,三个参数之一必填,可选 encoding / startLine / endLine / maxChars
     * @return 成功时含 content + 元数据的结构化结果,失败时 error
     */
    public ToolResult execute(ToolInput input) {
        // ★ 1. skill 分支 —— skill 参数存在时,自动拼接路径读取 SKILL.md
        var skillParam = input.getOptionalParam("skill", String.class).orElse(null);
        if (skillParam != null && !skillParam.isBlank()) {
            return executeSkillRead(skillParam);
        }

        int maxChars = input.getOptionalParam("maxChars", Number.class)
                .map(Number::intValue)
                .map(value -> Math.max(1, value))
                .orElse(defaultMaxChars);

        // ★ 2. attachmentId 分支 —— 对话附件 handle,查库拿真实路径
        String attachmentId = input.getOptionalParam("attachmentId", String.class).orElse(null);
        if (attachmentId != null && !attachmentId.isBlank()) {
            return executeAttachmentRead(attachmentId, maxChars);
        }

        // ★ 3. path 分支 —— 本机文件绝对路径(主入口）
        String pathStr;
        try {
            pathStr = input.getParam("path", String.class);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("需要提供 path、attachmentId 或 skill 之一");
        }

        String encoding = input.getOptionalParam("encoding", String.class)
                .orElse("UTF-8");
        var startLineOpt = input.getOptionalParam("startLine", Number.class)
                .map(Number::intValue);
        var endLineOpt = input.getOptionalParam("endLine", Number.class)
                .map(Number::intValue);

        Path filePath = Path.of(pathStr);

        // 路径安全检查
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

        // 按扩展名分流:结构化文档走 DocumentParserService,其他走 BufferedReader
        if (isFormattedDocument(filePath)) {
            return executeFormattedRead(filePath, filePath.getFileName().toString(), maxChars);
        }
        return executePlainTextRead(filePath, pathStr, encoding, startLineOpt, endLineOpt, maxChars);
    }

    /**
     * 纯文本读取分支 —— 按字节读取,支持行范围 / 编码 / maxChars 截断。
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
                content += "\n...[内容已截断,maxChars=" + maxChars
                        + ",显示行 " + actualStart + "-" + actualEnd + "/" + totalLines + "]";
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
     * 结构化文档读取分支 —— 走 {@link DocumentParserService},附加 metadata 和 fileName。
     *
     * <p>startLine / endLine 对此路径无效(文档格式按语义解析,不是按行)。</p>
     */
    private ToolResult executeFormattedRead(Path filePath, String fileName, int maxChars) {
        if (!documentParserService.supports(filePath)) {
            return ToolResult.error("不支持的文档类型:" + fileName);
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

            log.info("结构化文档读取成功:fileName={}, chars={}, truncated={}",
                    fileName, originalLength, truncated);
            return ToolResult.success(Map.copyOf(data));
        } catch (DocumentParseException e) {
            log.warn("结构化文档读取失败:fileName={}, phase={}, error={}",
                    fileName, e.getPhase(), e.getMessage());
            return ToolResult.error("文档解析失败:" + e.getMessage());
        }
    }

    /**
     * attachmentId 读取分支 —— 通过 {@link AttachmentRepository} 查询真实文件路径,
     * 然后复用 {@link #executeFormattedRead} / {@link #executePlainTextRead} 路由。
     */
    private ToolResult executeAttachmentRead(String attachmentId, int maxChars) {
        if (attachmentRepository == null) {
            return ToolResult.error("附件功能未启用");
        }
        var record = attachmentRepository.findById(attachmentId);
        if (record == null) {
            return ToolResult.error("附件不存在:attachmentId=" + attachmentId);
        }
        Path filePath = Path.of(record.filePath());
        String fileName = record.fileName();

        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            return ToolResult.error("附件文件不存在或不是普通文件:" + filePath);
        }
        // 附件分支不做 securityChecker 检查 —— 附件物理位置由 AttachmentRepository 控制,
        // 属于受信目录(~/.zhiwei/data/attachments),无需再与用户侧白名单对齐。

        if (isFormattedDocument(filePath)) {
            return executeFormattedRead(filePath, fileName, maxChars);
        }
        return executePlainTextRead(filePath, filePath.toString(),
                "UTF-8", java.util.Optional.empty(), java.util.Optional.empty(), maxChars);
    }

    /**
     * 按扩展名判断是否为结构化文档 —— 命中则走 {@link DocumentParserService},
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
     * 将 {@link DocumentMetadata} 转换为 LLM 友好的 Map,Optional 空值不写入键,
     * Instant 以 ISO 8601 字符串输出,extraProperties 原样透传。
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

    /**
     * 读取一个或多个 Skill 的 SKILL.md 文件,拼接返回。
     * 在返回的 data 中放 {@code _skillIds} 供 ReactAgentLoop 检测并激活工具。
     */
    private ToolResult executeSkillRead(String skillParam) {
        List<String> skillIds = Arrays.stream(skillParam.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        // mcp: 前缀不需要 skillDirectory；纯内置 Skill 需要
        boolean hasNonMcpSkill = skillIds.stream().anyMatch(id -> !id.startsWith("mcp:"));
        if (hasNonMcpSkill && (skillDirectory == null || skillDirectory.isBlank())) {
            return ToolResult.error("Skill 目录未配置,无法加载技能");
        }
        if (skillIds.isEmpty()) {
            return ToolResult.error("skill 参数为空");
        }
        if (skillIds.size() > 3) {
            return ToolResult.error("单次最多加载 3 个技能");
        }

        var sb = new StringBuilder();
        var loadedIds = new java.util.ArrayList<String>();
        var errors = new java.util.ArrayList<String>();

        for (String skillId : skillIds) {
            // MCP Server 加载分支 —— mcp: 前缀
            if (skillId.startsWith("mcp:")) {
                String serverName = skillId.substring(4);
                if (toolRegistry == null) {
                    errors.add(skillId + ": 工具注册表不可用");
                    continue;
                }
                var serverTools = toolRegistry.getToolsByServer(serverName);
                if (serverTools.isEmpty()) {
                    errors.add(serverName + ": MCP server 未连接或无工具");
                    continue;
                }
                // 拼接 server 的工具描述作为"虚拟 SKILL.md"
                var toolDescriptions = new StringBuilder();
                toolDescriptions.append("# MCP Server: ").append(serverName).append("\n\n");
                toolDescriptions.append("## 可用工具\n\n");
                for (var tool : serverTools) {
                    toolDescriptions.append("### ").append(tool.id()).append("\n");
                    toolDescriptions.append(tool.description()).append("\n\n");
                }
                if (!sb.isEmpty()) sb.append("\n\n---\n\n");
                sb.append(toolDescriptions);
                loadedIds.add(skillId);  // 保留 "mcp:serverName" 前缀
                log.info("MCP Server 工具指南已生成: server={}, toolCount={}", serverName, serverTools.size());
                continue;
            }

            // 安全校验：skill ID 只允许字母、数字、连字符、下划线
            if (!skillId.matches("[a-zA-Z0-9_-]+")) {
                errors.add(skillId + ": ID 格式非法");
                continue;
            }
            Path skillFile = Path.of(skillDirectory, skillId, "SKILL.md");
            if (!Files.exists(skillFile) || !Files.isRegularFile(skillFile)) {
                errors.add(skillId + ": 技能不存在");
                continue;
            }
            try {
                String content = Files.readString(skillFile);
                if (!sb.isEmpty()) {
                    sb.append("\n\n---\n\n");
                }
                sb.append(content);
                loadedIds.add(skillId);
                log.info("Skill 指南已读取: skillId={}, path={}", skillId, skillFile);
            } catch (IOException e) {
                errors.add(skillId + ": " + e.getMessage());
            }
        }

        if (loadedIds.isEmpty()) {
            return ToolResult.error("所有技能加载失败: " + String.join("; ", errors));
        }

        var data = new LinkedHashMap<String, Object>();
        data.put("content", sb.toString());
        data.put("_skillIds", List.copyOf(loadedIds));
        if (!errors.isEmpty()) {
            data.put("errors", List.copyOf(errors));
        }
        return ToolResult.success(Map.copyOf(data));
    }

}
