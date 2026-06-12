package com.lifepilot.agent.learning.extraction;

import org.springframework.lang.Nullable;

import java.time.LocalDate;
import java.time.Year;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 截止日期提取器（memory-deadline-awareness）—— 从文本中确定性提取**绝对日期**并归一为 ISO {@code yyyy-MM-dd}。
 *
 * <p>背景：AUDN LLM 不可靠地把硬截止日期写入结构化 {@code properties.dueAt}，但其归一后的
 * description/name 中通常已含绝对日期（如"2026-06-13"/"2026年6月13日"）。本提取器以确定性正则
 * 兜底，不依赖 LLM 合规性。只处理绝对日期；相对日期（"下周五"）不在范围内（交由 LLM 尽力而为）。</p>
 *
 * @author zsg
 * @since 2026-06-07
 */
public final class DueDateExtractor {

    private DueDateExtractor() {}

    // yyyy-MM-dd 或 yyyy/MM/dd
    private static final Pattern ISO_DATE = Pattern.compile(
            "(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})");
    // yyyy年M月D日/号
    private static final Pattern CN_FULL = Pattern.compile(
            "(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]");
    // M月D日/号（无年份，补参考年）
    private static final Pattern CN_NO_YEAR = Pattern.compile(
            "(?<!\\d)(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]");

    /**
     * 从文本提取首个可解析的绝对截止日期，归一为 ISO {@code yyyy-MM-dd}。
     *
     * @param text       待扫描文本（实体名/描述/证据）
     * @param referenceYear 无年份日期的参考年（通常取当前年）
     * @return ISO 日期串；未找到合法日期返回空
     */
    public static Optional<String> extractIsoDate(@Nullable String text, int referenceYear) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Optional<String> r = tryFullDate(ISO_DATE.matcher(text));
        if (r.isPresent()) return r;
        r = tryFullDate(CN_FULL.matcher(text));
        if (r.isPresent()) return r;
        Matcher m = CN_NO_YEAR.matcher(text);
        if (m.find()) {
            return toIso(referenceYear, parseInt(m.group(1)), parseInt(m.group(2)));
        }
        return Optional.empty();
    }

    /** 用当前年作为参考年的便捷重载。 */
    public static Optional<String> extractIsoDate(@Nullable String text) {
        return extractIsoDate(text, Year.now().getValue());
    }

    private static Optional<String> tryFullDate(Matcher m) {
        if (m.find()) {
            return toIso(parseInt(m.group(1)), parseInt(m.group(2)), parseInt(m.group(3)));
        }
        return Optional.empty();
    }

    private static Optional<String> toIso(int year, int month, int day) {
        try {
            return Optional.of(LocalDate.of(year, month, day).toString());
        } catch (Exception e) {
            return Optional.empty();  // 非法日期（如 13 月、32 日）
        }
    }

    private static int parseInt(String s) {
        return Integer.parseInt(s.trim());
    }
}
