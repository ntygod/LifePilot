package com.lifepilot.agent.task.proactive.schedule;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量日程提取器 — 从对话文本中识别日程/事件/约定。
 *
 * <p>使用规则化模式匹配提取时间 + 事件，替代外部日历集成。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
public class ScheduleExtractor {

    /** 事件关键词 — 用于过滤非日程文本。 */
    private static final Pattern EVENT_KEYWORD = Pattern.compile(
            "开会|面试|出差|聚会|活动|培训|预约|复诊|考试|答辩|上课|演讲|飞|火车|航班|出发");

    /** 时间+事件模式：明天下午3点开会 / 周五去杭州 / 下周一面试 */
    private static final Pattern EVENT_PATTERN = Pattern.compile(
            "(今天|今晚|明天|后天|大后天|下?周[一二三四五六日天]|下个?月|\\d{1,2}[月号日]\\d{0,2}[日号]?)\\s*" +
            "(?:上午|下午|晚上)?\\s*(?:(\\d{1,2})[点时:：](\\d{0,2}))?\\s*" +
            "(?:要|去|有|参加|约了?)?\\s*(.{2,20}?)(?:[，。！？,\\.!?]|$)");

    /** 纯事件模式：有个会议/面试/预约 */
    private static final Pattern SIMPLE_EVENT_PATTERN = Pattern.compile(
            "(?:有个?|安排了?|约了?)\\s*(会议|面试|预约|活动|聚会|出差|培训|复诊|考试|答辩|演讲)");

    private final Clock clock;

    public ScheduleExtractor() {
        this(Clock.systemDefaultZone());
    }

    public ScheduleExtractor(Clock clock) {
        this.clock = clock;
    }

    /**
     * 从对话消息中提取日程事件。
     */
    public List<ScheduleEvent> extract(String userId, String sessionId, List<String> messages, ZoneId zoneId) {
        var results = new ArrayList<ScheduleEvent>();
        Instant now = Instant.now(clock);

        for (String msg : messages) {
            Matcher matcher = EVENT_PATTERN.matcher(msg);
            while (matcher.find()) {
                String dateRef = matcher.group(1);
                String hourStr = matcher.group(2);
                String eventDesc = matcher.group(4);
                if (eventDesc == null || eventDesc.length() < 2) continue;
                // 必须包含事件关键词，排除 "今天天气不错" 这类非日程文本
                if (!EVENT_KEYWORD.matcher(eventDesc).find()
                        && !EVENT_KEYWORD.matcher(msg).find()) continue;

                Instant eventTime = resolveDateTime(dateRef, hourStr, matcher.group(3), zoneId);
                results.add(new ScheduleEvent(
                        UUID.randomUUID().toString(), userId,
                        eventDesc.strip(), eventTime, sessionId, now));
            }

            Matcher simpleMatcher = SIMPLE_EVENT_PATTERN.matcher(msg);
            while (simpleMatcher.find()) {
                String eventType = simpleMatcher.group(1);
                // 避免和 EVENT_PATTERN 重复
                boolean alreadyExtracted = results.stream()
                        .anyMatch(e -> e.title().contains(eventType));
                if (!alreadyExtracted) {
                    results.add(new ScheduleEvent(
                            UUID.randomUUID().toString(), userId,
                            eventType, null, sessionId, now));
                }
            }
        }
        return results;
    }

    /** 将相对日期引用解析为 Instant。 */
    private Instant resolveDateTime(String dateRef, String hourStr, String minuteStr, ZoneId zoneId) {
        LocalDate date = resolveDate(dateRef, zoneId);
        if (date == null) return null;

        int hour = 9; // 默认上午 9 点
        if (hourStr != null && !hourStr.isEmpty()) {
            try { hour = Integer.parseInt(hourStr); } catch (NumberFormatException ignored) {}
        }
        int minute = 0;
        if (minuteStr != null && !minuteStr.isEmpty()) {
            try { minute = Integer.parseInt(minuteStr); } catch (NumberFormatException ignored) {}
        }
        return date.atTime(hour, minute).atZone(zoneId).toInstant();
    }

    private LocalDate resolveDate(String ref, ZoneId zoneId) {
        LocalDate today = LocalDate.now(clock);
        return switch (ref) {
            case "今天", "今晚" -> today;
            case "明天" -> today.plusDays(1);
            case "后天" -> today.plusDays(2);
            case "大后天" -> today.plusDays(3);
            case "周一", "下周一" -> today.with(java.time.temporal.TemporalAdjusters.next(DayOfWeek.MONDAY));
            case "周二", "下周二" -> today.with(java.time.temporal.TemporalAdjusters.next(DayOfWeek.TUESDAY));
            case "周三", "下周三" -> today.with(java.time.temporal.TemporalAdjusters.next(DayOfWeek.WEDNESDAY));
            case "周四", "下周四" -> today.with(java.time.temporal.TemporalAdjusters.next(DayOfWeek.THURSDAY));
            case "周五", "下周五" -> today.with(java.time.temporal.TemporalAdjusters.next(DayOfWeek.FRIDAY));
            case "周六", "下周六" -> today.with(java.time.temporal.TemporalAdjusters.next(DayOfWeek.SATURDAY));
            case "周日", "周天", "下周日", "下周天" -> today.with(java.time.temporal.TemporalAdjusters.next(DayOfWeek.SUNDAY));
            default -> null; // 月/日格式暂不解析
        };
    }
}
