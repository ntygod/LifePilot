package com.lifepilot.meta.infra.file;

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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件读取工具 — 读取文件内容，支持行范围、maxChars 截断和编码检测。
 *
 * <p>安全机制：通过 {@link PathSecurityChecker} 校验路径白名单/黑名单。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class FileReadToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(FileReadToolExecutor.class);

    private final PathSecurityChecker securityChecker;
    private final int defaultMaxChars;
    /** Skill 目录路径（如 ~/.zhiwei/skills），为 null 时不支持 skill 参数。 */
    @org.springframework.lang.Nullable
    private final String skillDirectory;
    /** 动态工具注册中心，用于解析 mcp: 前缀的 Skill 加载请求。 */
    @org.springframework.lang.Nullable
    private final DynamicToolRegistry toolRegistry;

    public FileReadToolExecutor(MetaProperties properties) {
        var fileConfig = properties.getInfra().getFile();
        this.securityChecker = new PathSecurityChecker(fileConfig);
        this.defaultMaxChars = fileConfig.getDefaultMaxChars();
        this.skillDirectory = null;
        this.toolRegistry = null;
    }

    /**
     * 构造函数 — 允许注入自定义 PathSecurityChecker、Skill 目录和工具注册中心（用于测试）。
     */
    FileReadToolExecutor(PathSecurityChecker securityChecker, int defaultMaxChars,
                         @org.springframework.lang.Nullable String skillDirectory,
                         @org.springframework.lang.Nullable DynamicToolRegistry toolRegistry) {
        this.securityChecker = securityChecker;
        this.defaultMaxChars = defaultMaxChars;
        this.skillDirectory = skillDirectory;
        this.toolRegistry = toolRegistry;
    }

    /**
     * 读取文件内容，支持行范围和 maxChars 截断。
     *
     * @param input 工具输入，必需参数 path，可选 encoding、startLine、endLine、maxChars
     * @return 包含 content、path、size、truncated、totalLines 的结构化结果
     */
    public ToolResult execute(ToolInput input) {
        // ★ Skill 加载分支 — skill 参数存在时，自动拼接路径读取 SKILL.md
        var skillParam = input.getOptionalParam("skill", String.class).orElse(null);
        if (skillParam != null && !skillParam.isBlank()) {
            return executeSkillRead(skillParam);
        }

        String pathStr;
        try {
            pathStr = input.getParam("path", String.class);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("需要提供 path（读文件）或 skill（加载技能）参数");
        }

        String encoding = input.getOptionalParam("encoding", String.class)
                .orElse("UTF-8");
        var startLineOpt = input.getOptionalParam("startLine", Number.class)
                .map(Number::intValue);
        var endLineOpt = input.getOptionalParam("endLine", Number.class)
                .map(Number::intValue);
        int maxChars = input.getOptionalParam("maxChars", Number.class)
                .map(Number::intValue)
                .map(value -> Math.max(1, value))
                .orElse(defaultMaxChars);

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
     * 读取一个或多个 Skill 的 SKILL.md 文件，拼接返回。
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
            return ToolResult.error("Skill 目录未配置，无法加载技能");
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
            // MCP Server 加载分支 — mcp: 前缀
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
