package com.lifepilot.marketplace.security;

import com.lifepilot.marketplace.model.*;
import com.lifepilot.skill.marketplace.config.MarketplaceProperties;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 统一安全扫描器 — 支持 SKILL / AGENT / WORKFLOW 三种扩展类型的安装前安全检查。
 *
 * <p>扫描项目按类型分派：</p>
 * <table>
 *   <tr><th>扫描项</th><th>SKILL</th><th>AGENT</th><th>WORKFLOW</th></tr>
 *   <tr><td>危险 Tool 引用</td><td>✅ tools 列表</td><td>✅ allowedTools</td><td>✅ step actions</td></tr>
 *   <tr><td>Prompt 注入</td><td>✅ SKILL.md 内容</td><td>✅ systemPrompt</td><td>❌</td></tr>
 *   <tr><td>未知 Tool ID</td><td>✅</td><td>✅</td><td>✅</td></tr>
 *   <tr><td>YAML 结构校验</td><td>❌</td><td>❌</td><td>✅</td></tr>
 *   <tr><td>SemVer 版本校验</td><td>✅</td><td>✅</td><td>✅</td></tr>
 * </table>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class SecurityScanner {

    private static final Logger log = LoggerFactory.getLogger(SecurityScanner.class);

    /** SemVer 格式正则（支持可选 v 前缀和预发布后缀）。 */
    private static final Pattern SEMVER_PATTERN = Pattern.compile(
            "^v?\\d+\\.\\d+\\.\\d+(-[a-zA-Z0-9.]+)?$"
    );

    /** 危险工具名称模式 — 匹配包含这些关键词的工具 ID。 */
    private static final List<DangerousToolPattern> DANGEROUS_TOOL_PATTERNS = List.of(
            new DangerousToolPattern("shell", RiskLevel.HIGH, "Shell 执行工具"),
            new DangerousToolPattern("exec", RiskLevel.HIGH, "代码执行工具"),
            new DangerousToolPattern("execute", RiskLevel.HIGH, "代码执行工具"),
            new DangerousToolPattern("system", RiskLevel.HIGH, "系统命令工具"),
            new DangerousToolPattern("cmd", RiskLevel.HIGH, "命令行工具"),
            new DangerousToolPattern("bash", RiskLevel.HIGH, "Bash 执行工具"),
            new DangerousToolPattern("powershell", RiskLevel.HIGH, "PowerShell 执行工具"),
            new DangerousToolPattern("terminal", RiskLevel.HIGH, "终端执行工具"),
            new DangerousToolPattern("file-write", RiskLevel.MEDIUM, "文件写入工具"),
            new DangerousToolPattern("file-delete", RiskLevel.MEDIUM, "文件删除工具"),
            new DangerousToolPattern("http-request", RiskLevel.MEDIUM, "HTTP 请求工具")
    );

    private final DynamicToolRegistry toolRegistry;
    private final List<Pattern> compiledInjectionPatterns;

    public SecurityScanner(MarketplaceProperties properties,
                           DynamicToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
        this.compiledInjectionPatterns = compilePatterns(
                properties.getSecurity().getInjectionPatterns()
        );
    }

    /**
     * 扫描扩展内容，按类型执行不同的检查项。
     *
     * @param pkg         扩展包元数据
     * @param fileContent 扩展文件的原始文本内容
     * @return 安全扫描报告
     */
    public SecurityReport scan(ExtensionPackage pkg, String fileContent) {
        List<SecurityFinding> findings = new ArrayList<>();

        // 按扩展类型分派扫描逻辑
        switch (pkg.type()) {
            case SKILL -> scanSkill(pkg, fileContent, findings);
            case AGENT -> scanAgent(pkg, fileContent, findings);
            case WORKFLOW -> scanWorkflow(pkg, fileContent, findings);
        }

        // 所有类型共享：SemVer 版本校验
        checkVersionFormat(pkg.version(), findings);

        // 计算整体风险级别
        RiskLevel overall = findings.stream()
                .map(SecurityFinding::level)
                .max(Comparator.naturalOrder())
                .orElse(RiskLevel.LOW);

        var report = new SecurityReport(findings, overall);
        log.debug("安全扫描完成: id={}, type={}, findings={}, overallRisk={}",
                pkg.id(), pkg.type(), findings.size(), overall);
        return report;
    }

    // ─────────────────────────────────────────────
    //  SKILL 扫描
    // ─────────────────────────────────────────────

    /**
     * SKILL 类型扫描：危险 Tool 引用 + Prompt 注入 + 未知 Tool ID。
     */
    @SuppressWarnings("unchecked")
    private void scanSkill(ExtensionPackage pkg, String fileContent,
                           List<SecurityFinding> findings) {
        // 尝试解析 YAML 提取 tools 列表和 system-prompt
        Map<String, Object> yamlMap = parseYamlSafely(fileContent);

        // 提取 tools 列表（Skill Markdown 格式中 tools 在 skill 根节点下的 allowed-tools）
        List<String> toolIds = extractToolIds(yamlMap, "allowed-tools");
        if (toolIds.isEmpty()) {
            // 也尝试从 skill 子节点提取
            Object skillObj = yamlMap.get("skill");
            if (skillObj instanceof Map<?, ?> skillMap) {
                toolIds = extractToolIds((Map<String, Object>) skillMap, "allowed-tools");
            }
        }

        checkDangerousTools(toolIds, "allowed-tools", findings);
        checkUnknownTools(toolIds, "allowed-tools", findings);

        // Prompt 注入检测 — 扫描整个文件内容（SKILL.md 可能包含 system prompt）
        checkPromptInjection(fileContent, "SKILL.md 内容", findings);
    }

    // ─────────────────────────────────────────────
    //  AGENT 扫描
    // ─────────────────────────────────────────────

    /**
     * AGENT 类型扫描：allowedTools 危险 Tool + systemPrompt 注入 + 未知 Tool ID。
     */
    private void scanAgent(ExtensionPackage pkg, String fileContent,
                           List<SecurityFinding> findings) {
        Map<String, Object> yamlMap = parseYamlSafely(fileContent);

        // 提取 allowedTools 列表
        List<String> toolIds = extractToolIds(yamlMap, "allowedTools");
        if (toolIds.isEmpty()) {
            toolIds = extractToolIds(yamlMap, "allowed-tools");
        }

        checkDangerousTools(toolIds, "allowedTools", findings);
        checkUnknownTools(toolIds, "allowedTools", findings);

        // systemPrompt 注入检测
        Object promptObj = yamlMap.get("systemPrompt");
        if (promptObj == null) {
            promptObj = yamlMap.get("system-prompt");
        }
        if (promptObj instanceof String systemPrompt) {
            checkPromptInjection(systemPrompt, "systemPrompt", findings);
        }
    }

    // ─────────────────────────────────────────────
    //  WORKFLOW 扫描
    // ─────────────────────────────────────────────

    /**
     * WORKFLOW 类型扫描：step actions 危险 Tool + 未知 Tool ID + YAML 结构校验。
     */
    private void scanWorkflow(ExtensionPackage pkg, String fileContent,
                              List<SecurityFinding> findings) {
        // YAML 结构校验
        Map<String, Object> yamlMap = parseYamlForWorkflow(fileContent, findings);
        if (yamlMap.isEmpty()) {
            return; // YAML 解析失败，已添加发现
        }

        // 从 steps 中提取 action 引用的工具 ID
        List<String> toolIds = extractWorkflowToolIds(yamlMap);

        checkDangerousTools(toolIds, "step actions", findings);
        checkUnknownTools(toolIds, "step actions", findings);
    }

    /**
     * 解析 Workflow YAML 并校验基本结构。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseYamlForWorkflow(String fileContent,
                                                     List<SecurityFinding> findings) {
        Map<String, Object> yamlMap;
        try {
            var yaml = new Yaml();
            Object parsed = yaml.load(fileContent);
            if (!(parsed instanceof Map<?, ?> map)) {
                findings.add(new SecurityFinding(
                        RiskLevel.MEDIUM,
                        "YAML 结构",
                        "Workflow 文件不是有效的 YAML Map 结构"
                ));
                return Map.of();
            }
            yamlMap = (Map<String, Object>) map;
        } catch (Exception e) {
            findings.add(new SecurityFinding(
                    RiskLevel.MEDIUM,
                    "YAML 结构",
                    "Workflow YAML 解析失败: %s".formatted(e.getMessage())
            ));
            return Map.of();
        }

        // 校验必要的 workflow 结构字段
        if (!yamlMap.containsKey("id") && !yamlMap.containsKey("name")) {
            findings.add(new SecurityFinding(
                    RiskLevel.LOW,
                    "YAML 结构",
                    "Workflow 缺少 id 或 name 字段"
            ));
        }

        Object stepsObj = yamlMap.get("steps");
        if (stepsObj == null) {
            stepsObj = yamlMap.get("workflow");
            if (stepsObj instanceof Map<?, ?> workflowMap) {
                stepsObj = ((Map<String, Object>) workflowMap).get("steps");
            }
        }
        if (!(stepsObj instanceof List<?>)) {
            findings.add(new SecurityFinding(
                    RiskLevel.LOW,
                    "YAML 结构",
                    "Workflow 缺少 steps 列表"
            ));
        }

        return yamlMap;
    }

    /**
     * 从 Workflow YAML 中提取 step actions 引用的工具 ID。
     */
    @SuppressWarnings("unchecked")
    private List<String> extractWorkflowToolIds(Map<String, Object> yamlMap) {
        List<String> toolIds = new ArrayList<>();

        // 尝试从顶层 steps 提取
        Object stepsObj = yamlMap.get("steps");
        if (stepsObj == null) {
            // 尝试从 workflow.steps 提取
            Object workflowObj = yamlMap.get("workflow");
            if (workflowObj instanceof Map<?, ?> workflowMap) {
                stepsObj = ((Map<String, Object>) workflowMap).get("steps");
            }
        }

        if (stepsObj instanceof List<?> steps) {
            for (Object stepObj : steps) {
                if (stepObj instanceof Map<?, ?> step) {
                    // 提取 action / tool / toolId 字段
                    extractToolIdFromStep((Map<String, Object>) step, toolIds);
                }
            }
        }

        return toolIds;
    }

    /**
     * 从单个 step 中提取工具 ID。
     */
    private void extractToolIdFromStep(Map<String, Object> step, List<String> toolIds) {
        // 常见字段名：action、tool、toolId
        for (String key : List.of("action", "tool", "toolId", "tool-id")) {
            Object value = step.get(key);
            if (value instanceof String toolId && !toolId.isBlank()) {
                toolIds.add(toolId);
            }
        }
    }

    // ─────────────────────────────────────────────
    //  共享扫描逻辑
    // ─────────────────────────────────────────────

    /**
     * 检查工具列表中是否包含危险工具。
     */
    private void checkDangerousTools(List<String> toolIds, String source,
                                     List<SecurityFinding> findings) {
        for (String toolId : toolIds) {
            String lowerToolId = toolId.toLowerCase(Locale.ROOT);
            for (var pattern : DANGEROUS_TOOL_PATTERNS) {
                if (lowerToolId.contains(pattern.keyword())) {
                    findings.add(new SecurityFinding(
                            pattern.level(),
                            "危险工具",
                            "%s 包含%s: %s".formatted(source, pattern.description(), toolId)
                    ));
                    break; // 每个工具只报告一次
                }
            }
        }
    }

    /**
     * 检查文本内容中是否包含 Prompt 注入模式。
     */
    private void checkPromptInjection(String content, String source,
                                      List<SecurityFinding> findings) {
        if (content == null || content.isBlank()) {
            return;
        }
        for (Pattern pattern : compiledInjectionPatterns) {
            if (pattern.matcher(content).find()) {
                findings.add(new SecurityFinding(
                        RiskLevel.HIGH,
                        "Prompt 注入",
                        "%s 包含可疑注入模式: %s".formatted(source, pattern.pattern())
                ));
            }
        }
    }

    /**
     * 检查工具列表中未在 DynamicToolRegistry 注册的工具。
     */
    private void checkUnknownTools(List<String> toolIds, String source,
                                   List<SecurityFinding> findings) {
        for (String toolId : toolIds) {
            if (toolRegistry.resolve(toolId).isEmpty()) {
                findings.add(new SecurityFinding(
                        RiskLevel.MEDIUM,
                        "未知工具",
                        "%s 包含未注册的工具 ID: %s".formatted(source, toolId)
                ));
            }
        }
    }

    /**
     * 检查版本字段是否符合 SemVer 格式。
     */
    private void checkVersionFormat(String version, List<SecurityFinding> findings) {
        if (version == null || version.isBlank()) {
            findings.add(new SecurityFinding(
                    RiskLevel.LOW,
                    "版本格式",
                    "缺少 version 字段"
            ));
            return;
        }
        String trimmed = version.strip();
        if (!SEMVER_PATTERN.matcher(trimmed).matches()) {
            findings.add(new SecurityFinding(
                    RiskLevel.LOW,
                    "版本格式",
                    "version 格式不符合 SemVer 规范: %s".formatted(trimmed)
            ));
        }
    }

    // ─────────────────────────────────────────────
    //  工具方法
    // ─────────────────────────────────────────────

    /**
     * 安全解析 YAML 内容，解析失败返回空 Map。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseYamlSafely(String content) {
        try {
            var yaml = new Yaml();
            Object parsed = yaml.load(content);
            if (parsed instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
        } catch (Exception e) {
            log.debug("YAML 解析失败，将作为纯文本处理: {}", e.getMessage());
        }
        return Map.of();
    }

    /**
     * 从 Map 中提取指定键的字符串列表。
     */
    private List<String> extractToolIds(Map<String, Object> map, String key) {
        Object toolsObj = map.get(key);
        if (toolsObj instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }
        return List.of();
    }

    /**
     * 编译注入模式正则表达式列表。
     */
    private static List<Pattern> compilePatterns(List<String> patterns) {
        List<Pattern> compiled = new ArrayList<>();
        for (String regex : patterns) {
            try {
                compiled.add(Pattern.compile(regex));
            } catch (PatternSyntaxException e) {
                log.warn("注入模式正则编译失败，跳过: pattern={}, error={}", regex, e.getMessage());
            }
        }
        return List.copyOf(compiled);
    }

    /**
     * 危险工具匹配模式。
     */
    private record DangerousToolPattern(
            String keyword,
            RiskLevel level,
            String description
    ) {}
}
