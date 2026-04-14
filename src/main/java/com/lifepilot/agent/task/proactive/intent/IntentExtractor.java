package com.lifepilot.agent.task.proactive.intent;

import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 意图提取器 — 从对话文本中识别用户目标、关注点和触发条件。
 *
 * <p>使用规则化模式匹配，不调用 LLM，确保在 Gate 2 阶段可安全调用。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
public class IntentExtractor {

    /** "我想/我要/打算/计划/准备/希望/想要" + 后续内容 → GOAL */
    private static final Pattern GOAL_PATTERN = Pattern.compile(
            "(?:我想|我要|打算|计划|准备|希望|想要)(.{2,40}?)(?:[，。！？,\\.!?]|$)");

    /** "帮我盯着/关注/留意/跟踪" → MONITORING */
    private static final Pattern MONITORING_PATTERN = Pattern.compile(
            "(?:帮我|请|麻烦)(?:盯着|关注|留意|跟踪|追踪)(.{2,40}?)(?:[，。！？,\\.!?]|$)");

    /** "等...告诉我/提醒我" 或 "...的时候通知我" → CONDITIONAL */
    private static final Pattern CONDITIONAL_PATTERN = Pattern.compile(
            "(?:等|当|如果)(.{2,50}?)(?:告诉我|提醒我|通知我|叫我)");

    /** "每天/每周/每月" + 动作 → RECURRING */
    private static final Pattern RECURRING_PATTERN = Pattern.compile(
            "(?:每天|每周|每月|每个?早上|每个?晚上)(.{2,30}?)(?:[，。！？,\\.!?]|$)");

    /**
     * 从对话消息列表中提取意图。
     *
     * @param userId    用户 ID
     * @param sessionId 对话 ID
     * @param messages  用户消息文本列表
     * @return 提取到的意图列表
     */
    public List<IntentRecord> extract(String userId, String sessionId, List<String> messages) {
        var results = new LinkedHashMap<String, IntentRecord>();
        Instant now = Instant.now();
        Instant defaultExpiry = now.plusSeconds(90L * 86400);

        for (String msg : messages) {
            // 按优先级：条件 > 监控 > 习惯 > 目标
            extractByPattern(msg, CONDITIONAL_PATTERN, IntentType.CONDITIONAL, userId, sessionId, now, defaultExpiry, results);
            extractByPattern(msg, MONITORING_PATTERN, IntentType.MONITORING, userId, sessionId, now, defaultExpiry, results);
            extractByPattern(msg, RECURRING_PATTERN, IntentType.RECURRING, userId, sessionId, now, defaultExpiry, results);
            extractByPattern(msg, GOAL_PATTERN, IntentType.GOAL, userId, sessionId, now, defaultExpiry, results);
        }
        return List.copyOf(results.values());
    }

    private void extractByPattern(String text, Pattern pattern, IntentType type,
                                   String userId, String sessionId, Instant now, Instant expiry,
                                   Map<String, IntentRecord> results) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String goal = matcher.group(1).strip();
            if (goal.length() < 2) continue;

            String dedupeKey = normalizeForDedupe(goal);
            if (results.containsKey(dedupeKey)) continue;

            String triggerCondition = type == IntentType.CONDITIONAL ? goal : null;
            String goalText = type == IntentType.CONDITIONAL ? text.strip() : goal;

            results.put(dedupeKey, new IntentRecord(
                    UUID.randomUUID().toString(), userId, type, goalText,
                    triggerCondition, sessionId, IntentStatus.ACTIVE, 0,
                    now, expiry, null, null, now));
        }
    }

    /** 取前 6 个字符作为去重键（同一对话内粗粒度去重）。 */
    private static String normalizeForDedupe(String goal) {
        String cleaned = goal.replaceAll("[\\s，。！？,\\.!?一个好的了吗呢啊]", "");
        return cleaned.length() > 6 ? cleaned.substring(0, 6) : cleaned;
    }
}
