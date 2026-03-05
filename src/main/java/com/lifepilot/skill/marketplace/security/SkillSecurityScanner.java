package com.lifepilot.skill.marketplace.security;

import com.lifepilot.skill.marketplace.config.MarketplaceProperties;
import com.lifepilot.skill.marketplace.model.RiskLevel;
import com.lifepilot.skill.marketplace.model.SecurityFinding;
import com.lifepilot.skill.marketplace.model.SecurityReport;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Skill 安全扫描器 — 在安装前对 YAML Skill 定义执行安全检查。
 *
 * <p>扫描项目包括：</p>
 * <ul>
 *   <li>危险工具检测：检查 allowed-tools 中是否包含高风险工具 ID</li>
 *   <li>Prompt 注入模式检测：检查 system-prompt 中是否包含注入模式</li>
 *   <li>未知工具检测：检查 allowed-tools 中未在 DynamicToolRegistry 注册的工具</li>
 *   <li>版本格式校验：检查 version 字段是否符合 SemVer 格式</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-03-05
 */
public class SkillSecurityScanner {

    private static final Logger log = LoggerFactory.getLogger(SkillSecurityScanner.class);

    /** SemVer 格式正则（支持可选 v 前缀和预发布后缀）。 */
    private static final Pattern SEMVER_PATTERN = Pattern.compile(
            "^v?\\d+\\.\\d+\\.\\d+(-[a-zA-Z0-9.]+)?$"
    );

    /** 危险工具名称模式 — 匹配包含这些关键词的工具 ID。 */
    private static final List<DangerousToolPattern> DANGEROUS_TOOL_PATTERNS = List.of(
            new DangerousToolPattern("shell", RiskLevel.HIGH, "Shell 执行工具"),
            new DangerousToolPattern("exec", RiskLevel.HIGH, "代码执行工具"),
            new DangerousToolPattern("file-write", RiskLevel.MEDIUM, "文件写入工具"),
            new DangerousToolPattern("file-delete", RiskLevel.MEDIUM, "文件删除工具"),
            new DangerousToolPattern("http-request", RiskLevel.MEDIUM, "HTTP 请求工具")
    );

    private final DynamicToolRegistry toolRegistry;
    private final List<Pattern> compiledInjectionPatterns;

    public SkillSecurityScanner(MarketplaceProperties properties,
                                DynamicToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
        this.compiledInjectionPatterns = compilePatterns(
                properties.getSecurity().getInjectionPatterns()
        );
    }

    /**
     * 对 YAML Skill 定义执行安全扫描。
     *
     * @param yamlMap 解析后的 YAML 内容（键为 "id"、"system-prompt"、"allowed-tools"、"version" 等）
     * @return 安全扫描报告
     */
    public SecurityReport scan(Map<String, Object> yamlMap) {
        List<SecurityFinding> findings = new ArrayList<>();

        // 1. 检查 allowed-tools 中的危险工具
        checkDangerousTools(yamlMap, findings);

        // 2. 检查 system-prompt 中的注入模式
        checkPromptInjection(yamlMap, findings);

        // 3. 检查 allowed-tools 中未注册的工具 ID
        checkUnknownTools(yamlMap, findings);

        // 4. 检查 version 格式
        checkVersionFormat(yamlMap, findings);

        // 计算整体风险级别
        RiskLevel overall = findings.stream()
                .map(SecurityFinding::level)
                .max(Comparator.naturalOrder())
                .orElse(RiskLevel.LOW);

        var report = new SecurityReport(findings, overall);
        log.debug("安全扫描完成: skillId={}, findings={}, overallRisk={}",
                yamlMap.get("id"), findings.size(), overall);
        return report;
    }

    /**
     * 检查 allowed-tools 中是否包含危险工具。
     */
    private void checkDangerousTools(Map<String, Object> yamlMap,
                                     List<SecurityFinding> findings) {
        var allowedTools = extractAllowedTools(yamlMap);
        for (String toolId : allowedTools) {
            String lowerToolId = toolId.toLowerCase(Locale.ROOT);
            for (var pattern : DANGEROUS_TOOL_PATTERNS) {
                if (lowerToolId.contains(pattern.keyword())) {
                    findings.add(new SecurityFinding(
                            pattern.level(),
                            "危险工具",
                            "allowed-tools 包含%s: %s".formatted(pattern.description(), toolId)
                    ));
                    break; // 每个工具只报告一次
                }
            }
        }
    }

    /**
     * 检查 system-prompt 中是否包含 Prompt 注入模式。
     */
    private void checkPromptInjection(Map<String, Object> yamlMap,
                                      List<SecurityFinding> findings) {
        Object promptObj = yamlMap.get("system-prompt");
        if (promptObj == null) {
            return;
        }
        String prompt = promptObj.toString();
        for (Pattern pattern : compiledInjectionPatterns) {
            if (pattern.matcher(prompt).find()) {
                findings.add(new SecurityFinding(
                        RiskLevel.HIGH,
                        "Prompt 注入",
                        "system-prompt 包含可疑注入模式: %s".formatted(pattern.pattern())
                ));
            }
        }
    }

    /**
     * 检查 allowed-tools 中未在 DynamicToolRegistry 注册的工具。
     */
    private void checkUnknownTools(Map<String, Object> yamlMap,
                                   List<SecurityFinding> findings) {
        var allowedTools = extractAllowedTools(yamlMap);
        for (String toolId : allowedTools) {
            if (toolRegistry.resolve(toolId).isEmpty()) {
                findings.add(new SecurityFinding(
                        RiskLevel.MEDIUM,
                        "未知工具",
                        "allowed-tools 包含未注册的工具 ID: %s".formatted(toolId)
                ));
            }
        }
    }

    /**
     * 检查 version 字段是否符合 SemVer 格式。
     */
    private void checkVersionFormat(Map<String, Object> yamlMap,
                                    List<SecurityFinding> findings) {
        Object versionObj = yamlMap.get("version");
        if (versionObj == null) {
            findings.add(new SecurityFinding(
                    RiskLevel.LOW,
                    "版本格式",
                    "缺少 version 字段"
            ));
            return;
        }
        String version = versionObj.toString().strip();
        if (!SEMVER_PATTERN.matcher(version).matches()) {
            findings.add(new SecurityFinding(
                    RiskLevel.LOW,
                    "版本格式",
                    "version 格式不符合 SemVer 规范: %s".formatted(version)
            ));
        }
    }

    /**
     * 从 YAML Map 中提取 allowed-tools 列表。
     */
    private List<String> extractAllowedTools(Map<String, Object> yamlMap) {
        Object toolsObj = yamlMap.get("allowed-tools");
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
