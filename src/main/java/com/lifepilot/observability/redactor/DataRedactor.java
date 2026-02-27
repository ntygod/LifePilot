package com.lifepilot.observability.redactor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Function;

/**
 * 数据脱敏器 — 基于可扩展规则系统的 PII 脱敏引擎。
 *
 * <p>内置中国常见 PII 脱敏规则（手机号、身份证号、银行卡号、邮箱、IP 地址、API 密钥），
 * 支持动态注册/注销自定义规则。规则按优先级从高到低排序执行。</p>
 *
 * @author zsg
 * @since 2026-02-27
 */
public class DataRedactor {

    private static final Logger log = LoggerFactory.getLogger(DataRedactor.class);

    private final ConcurrentHashMap<String, RedactionRule> rules = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Function<String, String>> customReplacers = new ConcurrentHashMap<>();

    public DataRedactor() {
        registerBuiltinRules();
    }

    /**
     * 对文本中的敏感数据进行脱敏。
     *
     * @param text 原始文本，null 返回空字符串
     * @return 脱敏后的文本
     */
    public String redact(String text) {
        if (text == null) {
            return "";
        }
        String result = text;
        for (RedactionRule rule : sortedEnabledRules()) {
            result = applyRule(rule, result);
        }
        return result;
    }

    /**
     * 执行脱敏并记录应用的规则。
     *
     * @param text 原始文本，null 返回空字符串和空规则列表
     * @return 脱敏审计结果
     */
    public RedactionAudit redactWithAudit(String text) {
        if (text == null) {
            return new RedactionAudit("", List.of());
        }
        String result = text;
        List<String> applied = new ArrayList<>();
        for (RedactionRule rule : sortedEnabledRules()) {
            String before = result;
            result = applyRule(rule, result);
            if (!before.equals(result)) {
                applied.add(rule.name());
            }
        }
        return new RedactionAudit(result, applied);
    }

    /**
     * 检测文本是否包含敏感数据。
     *
     * @param text 待检测文本
     * @return 包含敏感数据返回 true
     */
    public boolean containsSensitiveData(String text) {
        if (text == null) {
            return false;
        }
        return !redact(text).equals(text);
    }

    /**
     * 检测文本中包含的敏感数据类型。
     *
     * @param text 待检测文本
     * @return 匹配的规则名称列表
     */
    public List<String> detectSensitiveTypes(String text) {
        if (text == null) {
            return List.of();
        }
        List<String> types = new ArrayList<>();
        for (RedactionRule rule : sortedEnabledRules()) {
            try {
                if (rule.pattern().matcher(text).find()) {
                    types.add(rule.name());
                }
            } catch (Exception e) {
                log.warn("脱敏规则匹配异常: rule={}, error={}", rule.name(), e.getMessage());
            }
        }
        return List.copyOf(types);
    }

    /**
     * 注册自定义脱敏规则。
     *
     * @param rule 脱敏规则
     */
    public void registerRule(RedactionRule rule) {
        rules.put(rule.name(), rule);
        log.info("脱敏规则注册: name={}, priority={}", rule.name(), rule.priority());
    }

    /**
     * 注册带自定义替换函数的脱敏规则。
     *
     * @param rule     脱敏规则
     * @param replacer 自定义替换函数（输入匹配文本，输出替换文本）
     */
    public void registerRule(RedactionRule rule, Function<String, String> replacer) {
        rules.put(rule.name(), rule);
        customReplacers.put(rule.name(), replacer);
        log.info("脱敏规则注册（自定义替换）: name={}, priority={}", rule.name(), rule.priority());
    }

    /**
     * 注销脱敏规则。
     *
     * @param ruleName 规则名称
     */
    public void unregisterRule(String ruleName) {
        RedactionRule removed = rules.remove(ruleName);
        customReplacers.remove(ruleName);
        if (removed != null) {
            log.info("脱敏规则注销: name={}", ruleName);
        }
    }

    // ─── 内部方法 ───

    /**
     * 应用单条规则，正则异常时跳过。
     */
    private String applyRule(RedactionRule rule, String text) {
        try {
            Function<String, String> replacer = customReplacers.get(rule.name());
            if (replacer != null) {
                return rule.pattern().matcher(text).replaceAll(m -> Matcher.quoteReplacement(replacer.apply(m.group())));
            }
            return rule.pattern().matcher(text).replaceAll(rule.replacement());
        } catch (Exception e) {
            log.warn("脱敏规则执行异常，跳过: rule={}, error={}", rule.name(), e.getMessage());
            return text;
        }
    }

    /**
     * 获取按优先级从高到低排序的启用规则列表。
     */
    private List<RedactionRule> sortedEnabledRules() {
        return rules.values().stream()
                .filter(RedactionRule::enabled)
                .sorted(Comparator.comparingInt(RedactionRule::priority).reversed())
                .toList();
    }

    /**
     * 注册内置脱敏规则。
     */
    private void registerBuiltinRules() {
        // API 密钥：sk-**** （优先级最高，避免被其他规则误匹配）
        registerRule(
                new RedactionRule("api_key", "API 密钥",
                        Pattern.compile("(?i)(?:sk-|api[_-]?key[=:]\\s*)[\\w-]{8,}"),
                        "", 110, true),
                match -> {
                    // 保留前缀标识，隐藏密钥内容
                    int dashIdx = match.indexOf('-');
                    if (dashIdx > 0 && dashIdx < 5) {
                        return match.substring(0, dashIdx + 1) + "****";
                    }
                    int eqIdx = Math.max(match.indexOf('='), match.indexOf(':'));
                    if (eqIdx > 0) {
                        String prefix = match.substring(0, eqIdx + 1);
                        if (match.length() > eqIdx + 1 && match.charAt(eqIdx + 1) == ' ') {
                            prefix += " ";
                        }
                        return prefix + "****";
                    }
                    return "****";
                }
        );

        // 手机号：138****5678
        registerRule(
                new RedactionRule("phone", "中国大陆手机号",
                        Pattern.compile("1[3-9]\\d{9}"),
                        "", 100, true),
                match -> match.substring(0, 3) + "****" + match.substring(7)
        );

        // 身份证号：110***********1234
        registerRule(
                new RedactionRule("id_card", "中国大陆身份证号",
                        Pattern.compile("\\d{17}[\\dXx]"),
                        "", 90, true),
                match -> match.substring(0, 3) + "***********" + match.substring(14)
        );

        // 银行卡号：6222****0123
        registerRule(
                new RedactionRule("bank_card", "银行卡号",
                        Pattern.compile("\\d{16,19}"),
                        "", 80, true),
                match -> match.substring(0, 4) + "****" + match.substring(match.length() - 4)
        );

        // 邮箱：u***@example.com
        registerRule(
                new RedactionRule("email", "电子邮箱",
                        Pattern.compile("[\\w.+-]+@[\\w.-]+\\.[a-zA-Z]{2,}"),
                        "", 70, true),
                match -> {
                    int atIdx = match.indexOf('@');
                    return match.charAt(0) + "***" + match.substring(atIdx);
                }
        );

        // IP 地址：***.***.***.***
        registerRule(
                new RedactionRule("ip_address", "IP 地址",
                        Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b"),
                        "***.***.***.***", 60, true)
        );
    }
}
