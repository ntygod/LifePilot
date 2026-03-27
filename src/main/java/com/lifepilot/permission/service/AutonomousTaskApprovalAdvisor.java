package com.lifepilot.permission.service;

import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自主任务高风险审批顾问。
 *
 * <p>基于已注册工具的元数据和风险等级，判断一个 Cron / Heartbeat / Workflow
 * 指令是否可能触发高风险工具，从而决定是否需要在任务创建阶段一次性完成预授权。</p>
 *
 * @author zsg
 * @since 2026-03-26
 */
@Component
public class AutonomousTaskApprovalAdvisor {

    private static final Pattern LATIN_TOKEN_PATTERN = Pattern.compile("[a-z][a-z0-9_-]{1,}");
    private static final Pattern HAN_SEQUENCE_PATTERN = Pattern.compile("[\\p{IsHan}]{2,}");
    private static final Set<String> STOP_TOKENS = Set.of(
            "tool", "high", "risk", "medium", "low", "critical",
            "支持", "用户", "当前", "每次", "工具", "操作", "系统", "功能",
            "能力", "任务", "定时", "创建", "更新", "执行", "处理", "自动",
            "文件", "目录", "路径", "内容"
    );

    private final DynamicToolRegistry toolRegistry;

    public AutonomousTaskApprovalAdvisor(DynamicToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    public boolean requiresApproval(@Nullable String instruction) {
        if (instruction == null || instruction.isBlank()) {
            return false;
        }
        Set<String> instructionTokens = tokenize(instruction);
        if (instructionTokens.isEmpty()) {
            return false;
        }
        return toolRegistry.getAllTools().stream()
                .filter(this::isHighRiskAutonomousCandidate)
                .map(this::buildToolTokenSet)
                .anyMatch(toolTokens -> overlaps(toolTokens, instructionTokens));
    }

    private boolean isHighRiskAutonomousCandidate(ToolContract tool) {
        if (tool.riskLevel() == null || tool.riskLevel().ordinal() < RiskLevel.HIGH.ordinal()) {
            return false;
        }
        return !tool.id().startsWith("cron.");
    }

    private Set<String> buildToolTokenSet(ToolContract tool) {
        StringBuilder builder = new StringBuilder();
        builder.append(tool.id()).append(' ')
                .append(tool.name()).append(' ')
                .append(tool.description()).append(' ');
        for (String tag : tool.tags()) {
            builder.append(tag).append(' ');
        }
        return tokenize(builder.toString());
    }

    private boolean overlaps(Set<String> toolTokens, Set<String> instructionTokens) {
        for (String token : instructionTokens) {
            if (toolTokens.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> tokenize(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> tokens = new LinkedHashSet<>();

        Matcher latinMatcher = LATIN_TOKEN_PATTERN.matcher(normalized);
        while (latinMatcher.find()) {
            addToken(tokens, latinMatcher.group());
        }

        Matcher hanMatcher = HAN_SEQUENCE_PATTERN.matcher(text);
        while (hanMatcher.find()) {
            addHanTokens(tokens, hanMatcher.group());
        }

        return Set.copyOf(tokens);
    }

    private void addHanTokens(Set<String> tokens, String text) {
        if (text.length() <= 4) {
            addToken(tokens, text);
        }
        for (int size = 2; size <= 3; size++) {
            if (text.length() < size) {
                continue;
            }
            for (int i = 0; i <= text.length() - size; i++) {
                addToken(tokens, text.substring(i, i + size));
            }
        }
    }

    private void addToken(Set<String> tokens, String token) {
        String normalized = token == null ? "" : token.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() < 2 || STOP_TOKENS.contains(normalized)) {
            return;
        }
        tokens.add(normalized);
    }
}
