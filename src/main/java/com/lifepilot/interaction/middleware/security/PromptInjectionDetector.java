package com.lifepilot.interaction.middleware.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 基于正则模式匹配的 Prompt 注入检测器。
 * <p>
 * 内置两类检测模式：
 * <ul>
 *   <li>直接注入模式（CRITICAL）：检测试图覆盖系统指令的攻击</li>
 *   <li>越狱尝试模式（HIGH）：检测试图绕过安全限制的攻击</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class PromptInjectionDetector {

    private static final Logger log = LoggerFactory.getLogger(PromptInjectionDetector.class);

    /** 预编译的注入检测模式列表（英文 + 中文） */
    private static final List<InjectionPattern> PATTERNS = List.of(
            // ── 直接注入模式（CRITICAL） ──
            new InjectionPattern(
                    Pattern.compile("ignore.*(?:previous|above|all).*instructions", Pattern.CASE_INSENSITIVE),
                    "CRITICAL",
                    "直接注入"
            ),
            new InjectionPattern(
                    Pattern.compile("(?:忽略|无视|忘记|丢弃).*(?:之前|以上|所有|上面|上述).*(?:指令|指示|要求|规则|设定|约束)"),
                    "CRITICAL",
                    "直接注入（中文）"
            ),
            new InjectionPattern(
                    Pattern.compile("(?:system|initial)\\s*prompt", Pattern.CASE_INSENSITIVE),
                    "CRITICAL",
                    "系统提示词探测"
            ),
            new InjectionPattern(
                    Pattern.compile("(?:系统|初始)\\s*(?:提示词|指令|prompt)"),
                    "CRITICAL",
                    "系统提示词探测（中文）"
            ),
            new InjectionPattern(
                    Pattern.compile("(?:reveal|show|print|output).*(?:system|hidden|secret).*(?:prompt|instructions)", Pattern.CASE_INSENSITIVE),
                    "CRITICAL",
                    "提示词泄露"
            ),
            new InjectionPattern(
                    Pattern.compile("(?:输出|显示|打印|泄露|告诉我).*(?:系统|隐藏|内部|原始).*(?:提示词|指令|prompt)"),
                    "CRITICAL",
                    "提示词泄露（中文）"
            ),

            // ── 越狱尝试模式（HIGH） ──
            new InjectionPattern(
                    Pattern.compile("you\\s+are\\s+now", Pattern.CASE_INSENSITIVE),
                    "HIGH",
                    "角色覆盖"
            ),
            new InjectionPattern(
                    Pattern.compile("你现在是|从现在起你是|你的新身份是"),
                    "HIGH",
                    "角色覆盖（中文）"
            ),
            new InjectionPattern(
                    Pattern.compile("(?:DAN|jailbreak|bypass)\\s*mode", Pattern.CASE_INSENSITIVE),
                    "HIGH",
                    "越狱尝试"
            ),
            new InjectionPattern(
                    Pattern.compile("(?:越狱|解锁|破解).*(?:模式|限制)"),
                    "HIGH",
                    "越狱尝试（中文）"
            ),
            new InjectionPattern(
                    Pattern.compile("(?:pretend|act)\\s+(?:as|like)\\s+(?:a|an)", Pattern.CASE_INSENSITIVE),
                    "HIGH",
                    "角色扮演注入"
            ),
            new InjectionPattern(
                    Pattern.compile("(?:假装|扮演|模拟|充当)\\s*(?:你是|自己是|一个)"),
                    "HIGH",
                    "角色扮演注入（中文）"
            )
    );

    /**
     * 检测内容中的 Prompt 注入模式。
     *
     * @param content 待检测的文本内容
     * @return 检测到的安全违规列表，无违规时返回空列表
     */
    public List<SecurityViolation> detect(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        var violations = new ArrayList<SecurityViolation>();
        for (var pattern : PATTERNS) {
            var matcher = pattern.compiled().matcher(content);
            if (matcher.find()) {
                String matched = matcher.group();
                log.warn("检测到 Prompt 注入: severity={}, description={}, matched={}",
                        pattern.severity(), pattern.description(), matched);
                violations.add(new SecurityViolation(
                        "prompt_injection",
                        pattern.severity(),
                        pattern.description(),
                        matched
                ));
            }
        }
        return List.copyOf(violations);
    }

    /**
     * 注入检测模式。
     *
     * @param compiled    预编译的正则表达式
     * @param severity    严重程度（CRITICAL / HIGH）
     * @param description 模式描述
     */
    private record InjectionPattern(Pattern compiled, String severity, String description) {
    }
}
