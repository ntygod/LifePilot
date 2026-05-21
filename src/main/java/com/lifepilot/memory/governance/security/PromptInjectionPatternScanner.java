package com.lifepilot.memory.governance.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt 注入模式扫描器。
 *
 * <p>基于已知注入模式的中英混合正则库扫描文本。命中返回 {@link MatchInfo}，包含
 * 命中模式、位置、截取的上下文片段，供 {@link MemoryInjectionDetector} 决策。</p>
 *
 * <p>参考：
 * <ul>
 *   <li>MINJA（arXiv:2601.05504）：生产 Agent 注入成功率 95%</li>
 *   <li>OWASP LLM Top 10 - LLM01:2025 Prompt Injection</li>
 * </ul>
 * </p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public class PromptInjectionPatternScanner {

    private static final Logger log = LoggerFactory.getLogger(PromptInjectionPatternScanner.class);

    private static final int EXCERPT_RADIUS = 40;

    /**
     * 预编译模式库 — 中英混合。
     *
     * <p>新增模式时保持大小写不敏感，谨慎避免过拟合正常对话（如"请忽略这段"不应触发）。</p>
     */
    private static final List<Pattern> PATTERNS = List.of(
            // 英文：指令覆盖
            Pattern.compile("ignore\\s+(?:previous|prior|all|the\\s+above)\\s+(?:instructions?|rules?|prompts?)",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("disregard\\s+(?:all\\s+)?(?:prior|earlier|previous)\\s+(?:instructions?|rules?)",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("forget\\s+(?:everything|all|your\\s+instructions)", Pattern.CASE_INSENSITIVE),

            // 英文：角色劫持
            Pattern.compile("you\\s+are\\s+now\\s+(?:a\\s+)?[a-z]+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("act\\s+as\\s+(?:a\\s+)?(?:different|new|another)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("pretend\\s+(?:to\\s+be|you\\s+are)", Pattern.CASE_INSENSITIVE),

            // 英文：system prompt 伪装
            Pattern.compile("system\\s+prompt\\s*[:：]", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<\\s*(?:system|user|assistant)\\s*>", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\[\\s*INST\\s*\\]", Pattern.CASE_INSENSITIVE),

            // 中文：指令覆盖
            Pattern.compile("忽略(?:以上|前面|之前|所有|全部)(?:的)?(?:指令|规则|命令|提示|对话)"),
            Pattern.compile("忘(?:记|掉)(?:以前|之前|所有|全部)?(?:的)?(?:指令|规则|设定)"),
            Pattern.compile("不要(?:再)?(?:遵循|按照|听从)(?:以上|之前|前面)"),

            // 中文：角色劫持
            Pattern.compile("你(?:现在|从现在开始)?是(?:一个)?(?:新的|不同的|另一个)"),
            Pattern.compile("(?:扮演|假装|模拟)(?:一个|成)"),

            // 中文：system prompt 伪装
            Pattern.compile("系统(?:提示|指令)\\s*[:：]"),
            Pattern.compile("【\\s*(?:系统|用户|助手)\\s*】")
    );

    /** 扫描文本；命中返回首个匹配的 {@link MatchInfo}。 */
    public Optional<MatchInfo> scan(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        try {
            for (Pattern p : PATTERNS) {
                Matcher m = p.matcher(text);
                if (m.find()) {
                    int pos = m.start();
                    String excerpt = extractExcerpt(text, pos, m.end());
                    log.debug("注入扫描命中: pattern={}, position={}", p.pattern(), pos);
                    return Optional.of(new MatchInfo(p.pattern(), pos, excerpt));
                }
            }
        } catch (Exception e) {
            log.debug("注入扫描异常: {}", e.getMessage());
        }
        return Optional.empty();
    }

    private String extractExcerpt(String text, int start, int end) {
        int from = Math.max(0, start - EXCERPT_RADIUS);
        int to = Math.min(text.length(), end + EXCERPT_RADIUS);
        String prefix = from == 0 ? "" : "…";
        String suffix = to == text.length() ? "" : "…";
        return prefix + text.substring(from, to) + suffix;
    }

    /**
     * 命中信息。
     *
     * @param pattern  匹配的正则模式
     * @param position 起始位置
     * @param excerpt  文本片段（前后 40 字符）
     */
    public record MatchInfo(String pattern, int position, String excerpt) {}
}
