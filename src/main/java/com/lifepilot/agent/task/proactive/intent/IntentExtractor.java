package com.lifepilot.agent.task.proactive.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 意图提取器 — LLM 优先 + 正则兜底。
 *
 * <p>每轮对话结束后用轻量模型提取意图（覆盖中英文、隐式意图），
 * LLM 不可用时回退到正则模式匹配。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
public class IntentExtractor {

    private static final Logger log = LoggerFactory.getLogger(IntentExtractor.class);
    private static final String PROMPT_KEY = "generation/intent-extraction";
    private static final Duration LLM_TIMEOUT = Duration.ofSeconds(10);

    private final ObjectMapper objectMapper;

    // ── 正则兜底模式 ──
    private static final Pattern GOAL_PATTERN = Pattern.compile(
            "(?:我想|我要|打算|计划|准备|希望|想要)(.{2,40}?)(?:[，。！？,\\.!?]|$)");
    private static final Pattern MONITORING_PATTERN = Pattern.compile(
            "(?:帮我|请|麻烦)(?:盯着|关注|留意|跟踪|追踪)(.{2,40}?)(?:[，。！？,\\.!?]|$)");
    private static final Pattern CONDITIONAL_PATTERN = Pattern.compile(
            "(?:等|当|如果)(.{2,50}?)(?:告诉我|提醒我|通知我|叫我|提醒)");
    private static final Pattern RECURRING_PATTERN = Pattern.compile(
            "(?:每天|每周|每月|每个?早上|每个?晚上)(.{2,30}?)(?:[，。！？,\\.!?]|$)");
    // 扩展：支持"记得提醒我"、"别忘了"、"到时候通知"等口语化表达
    private static final Pattern REMINDER_PATTERN = Pattern.compile(
            "(?:记得|别忘了?|到时候)(?:提醒我|通知我|告诉我)?(.{2,40}?)(?:[，。！？,\\.!?]|$)");

    @Nullable private final GenerationRouter generationRouter;
    @Nullable private final PromptRegistry promptRegistry;

    public IntentExtractor(@Nullable GenerationRouter generationRouter,
                           @Nullable PromptRegistry promptRegistry,
                           @Nullable ObjectMapper objectMapper) {
        this.generationRouter = generationRouter;
        this.promptRegistry = promptRegistry;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    /**
     * 从对话消息中提取意图 — LLM 优先，正则兜底。
     */
    public List<IntentRecord> extract(String userId, String sessionId, List<String> messages) {
        if (messages == null || messages.isEmpty()) return List.of();

        // LLM 提取优先
        if (generationRouter != null && promptRegistry != null) {
            try {
                var llmResult = extractByLlm(userId, sessionId, messages);
                if (!llmResult.isEmpty()) {
                    log.debug("IntentExtractor: LLM 提取成功, intents={}", llmResult.size());
                    return llmResult;
                }
            } catch (Exception e) {
                log.debug("IntentExtractor: LLM 提取失败，回退正则: {}", e.getMessage());
            }
        }

        // 正则兜底
        return extractByRegex(userId, sessionId, messages);
    }

    /** LLM 提取 — 调用轻量模型解析 JSON 输出。 */
    private List<IntentRecord> extractByLlm(String userId, String sessionId, List<String> messages) {
        String joined = String.join("\n", messages);
        if (joined.length() > 2000) {
            joined = joined.substring(joined.length() - 2000); // 只取最近 2000 字符
        }

        String prompt = promptRegistry.render(PROMPT_KEY, Map.of("messages", joined));
        LlmResponse response = generationRouter.call("chat", prompt, null, null, null,
                GenerationCapability.CHAT, LLM_TIMEOUT);

        if (response == null || response.content().isBlank()) return List.of();

        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(90L * 86400);
        var results = new ArrayList<IntentRecord>();

        for (String line : response.content().strip().split("\n")) {
            line = line.strip();
            if (line.isEmpty() || !line.startsWith("{")) continue;
            try {
                JsonNode node = objectMapper.readTree(line);
                String type = node.path("type").asText("");
                String goal = node.path("goal").asText("");
                String trigger = node.path("trigger").isNull() ? null : node.path("trigger").asText();

                if (goal.length() < 2) continue;
                IntentType intentType = parseIntentType(type);
                if (intentType == null) continue;

                // Jaccard 去重
                if (isDuplicateByJaccard(goal, results)) continue;

                results.add(new IntentRecord(
                        UUID.randomUUID().toString(), userId, intentType, goal,
                        trigger, sessionId, IntentStatus.ACTIVE, 0,
                        now, expiry, null, null, now));
            } catch (Exception e) {
                log.debug("IntentExtractor: JSON 行解析失败: {}", line);
            }
        }
        return results;
    }

    /** 正则兜底 — 覆盖基础中文模式。 */
    List<IntentRecord> extractByRegex(String userId, String sessionId, List<String> messages) {
        var results = new LinkedHashMap<String, IntentRecord>();
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(90L * 86400);

        for (String msg : messages) {
            extractByPattern(msg, CONDITIONAL_PATTERN, IntentType.CONDITIONAL, userId, sessionId, now, expiry, results);
            extractByPattern(msg, MONITORING_PATTERN, IntentType.MONITORING, userId, sessionId, now, expiry, results);
            extractByPattern(msg, RECURRING_PATTERN, IntentType.RECURRING, userId, sessionId, now, expiry, results);
            extractByPattern(msg, REMINDER_PATTERN, IntentType.CONDITIONAL, userId, sessionId, now, expiry, results);
            extractByPattern(msg, GOAL_PATTERN, IntentType.GOAL, userId, sessionId, now, expiry, results);
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
            if (isDuplicateByJaccard(goal, results.values())) continue;

            String triggerCondition = type == IntentType.CONDITIONAL ? goal : null;
            String goalText = type == IntentType.CONDITIONAL ? text.strip() : goal;

            results.put(goal, new IntentRecord(
                    UUID.randomUUID().toString(), userId, type, goalText,
                    triggerCondition, sessionId, IntentStatus.ACTIVE, 0,
                    now, expiry, null, null, now));
        }
    }

    @Nullable
    private static IntentType parseIntentType(String type) {
        return switch (type.toUpperCase()) {
            case "GOAL" -> IntentType.GOAL;
            case "MONITORING" -> IntentType.MONITORING;
            case "CONDITIONAL" -> IntentType.CONDITIONAL;
            case "RECURRING" -> IntentType.RECURRING;
            default -> null;
        };
    }

    private static boolean isDuplicateByJaccard(String goal, Collection<IntentRecord> existing) {
        var goalBigrams = charBigrams(goal);
        for (var record : existing) {
            if (jaccardSimilarity(goalBigrams, charBigrams(record.goal())) > 0.5) return true;
        }
        return false;
    }

    private static Set<String> charBigrams(String text) {
        String cleaned = text.replaceAll("[\\s，。！？,\\.!?]", "");
        var bigrams = new HashSet<String>();
        for (int i = 0; i < cleaned.length() - 1; i++) {
            bigrams.add(cleaned.substring(i, i + 2));
        }
        return bigrams;
    }

    private static double jaccardSimilarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        var intersection = new HashSet<>(a);
        intersection.retainAll(b);
        var union = new HashSet<>(a);
        union.addAll(b);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }
}
