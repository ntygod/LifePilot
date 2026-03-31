package com.lifepilot.agent.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.conversation.transcript.SessionTranscriptRepository;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * SessionPruningEngine 负责 transcript-first 上下文中的临时裁剪逻辑。
 * <p>当前只裁剪历史 {@code tool_result}，不会改写 transcript 事实源。</p>
 *
 * @author zsg
 * @since 2026-03-23
 */
public class SessionPruningEngine {

    private static final int DEFAULT_TOOL_RESULT_LIMIT = 4;
    private static final int DEFAULT_FAILED_TOOL_RESULT_LIMIT = 2;
    private static final int DEFAULT_TOOL_RESULT_PREVIEW_CHARS = 240;
    private static final int SOFT_TRIM_MIN_THRESHOLD = 240;
    private static final int SOFT_TRIM_MAX_THRESHOLD = 1200;
    private static final String HARD_CLEAR_TEMPLATE =
            "结果过长已省略（原始约 %d 字符），如需细节请重新调用工具或查看产物。";

    private final AgentConfigProperties config;
    private final ObjectMapper objectMapper;

    public record PruningSnapshot(
            Set<String> selectedEntryIds,
            boolean pruningApplied,
            int tokenCount,
            int selectedCount,
            int totalCount
    ) {
        public static PruningSnapshot empty() {
            return new PruningSnapshot(Set.of(), false, 0, 0, 0);
        }
    }

    public SessionPruningEngine(AgentConfigProperties config, ObjectMapper objectMapper) {
        this.config = Objects.requireNonNull(config);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    public PruningSnapshot pruneToolResults(
            @Nullable List<SessionTranscriptRepository.SessionTranscriptEntryRow> rows,
            int totalContextTokens
    ) {
        if (rows == null || rows.isEmpty()) {
            return PruningSnapshot.empty();
        }

        List<SessionTranscriptRepository.SessionTranscriptEntryRow> candidates = rows.stream()
                .filter(SessionTranscriptRepository.SessionTranscriptEntryRow::visibleToModel)
                .filter(row -> "tool_result".equals(row.entryType()))
                .toList();
        if (candidates.isEmpty()) {
            return PruningSnapshot.empty();
        }

        TokenBudget budget = TokenBudget.allocateDefault(
                Math.max(0, totalContextTokens),
                config.getContext().getTokenAllocation()
        );
        int toolResultBudget = budget.toolResultBudget();
        if (toolResultBudget <= 0) {
            return new PruningSnapshot(Set.of(), true, 0, 0, candidates.size());
        }

        List<String> selectedEntryIds = new ArrayList<>();
        int usedTokens = 0;
        int selectedCount = 0;
        boolean pruned = false;
        boolean recentOnlyMode = isRecentOnlyMode();
        int resultLimit = resolveRecentToolResultLimit();
        Set<String> pinnedFailureEntryIds = selectPinnedFailureEntryIds(candidates);

        for (int i = candidates.size() - 1; i >= 0; i--) {
            SessionTranscriptRepository.SessionTranscriptEntryRow candidate = candidates.get(i);
            Map<String, Object> payload = readPayload(candidate.payloadJson());
            String preview = formatToolResultPreview(payload);
            if (preview.isBlank()) {
                continue;
            }
            int lineTokens = estimateTokens(preview);
            boolean pinnedFailure = pinnedFailureEntryIds.contains(candidate.id());
            boolean overRecentLimit = recentOnlyMode && selectedCount >= resultLimit && !pinnedFailure;
            boolean overTokenBudget = usedTokens + lineTokens > toolResultBudget;
            if (overRecentLimit || overTokenBudget) {
                pruned = true;
                continue;
            }
            selectedEntryIds.add(0, candidate.id());
            usedTokens += lineTokens;
            selectedCount++;
        }

        if (selectedEntryIds.isEmpty()) {
            return new PruningSnapshot(Set.of(), !candidates.isEmpty(), 0, 0, candidates.size());
        }

        return new PruningSnapshot(
                Set.copyOf(new LinkedHashSet<>(selectedEntryIds)),
                pruned || selectedCount < candidates.size(),
                usedTokens,
                selectedCount,
                candidates.size()
        );
    }

    String formatToolResultPreview(Map<String, Object> payload) {
        boolean success = booleanValue(payload.get("success"));
        String preview = extractPreview(resolvePreviewSource(payload));
        if (preview.isBlank()) {
            preview = success ? "工具执行成功" : "工具执行失败";
        }
        String reference = buildReferenceSuffix(payload);
        if (!reference.isBlank()) {
            preview = preview.isBlank() ? reference : preview + " " + reference;
        }
        if (!success && !preview.startsWith("失败")) {
            preview = "失败: " + preview;
        }
        return normalizeWhitespace(preview);
    }

    String formatCurrentObservationPreview(String toolId,
                                          boolean success,
                                          @Nullable String outputJson) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolId", toolId);
        payload.put("success", success);
        if (outputJson != null && !outputJson.isBlank()) {
            payload.put("outputJson", outputJson);
        }
        return formatToolResultPreview(payload);
    }

    Map<String, Object> readPayload(@Nullable String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(payloadJson, new TypeReference<>() { });
            return payload != null ? payload : Map.of();
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    @Nullable
    String stringValue(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private boolean isRecentOnlyMode() {
        String mode = config.getContext().getPruning().getToolResultMode();
        if (mode == null || mode.isBlank()) {
            return true;
        }
        return !"off".equalsIgnoreCase(mode.trim());
    }

    private int resolveRecentToolResultLimit() {
        int configured = config.getContext().getPruning().getRecentToolResultLimit();
        return configured > 0 ? configured : DEFAULT_TOOL_RESULT_LIMIT;
    }

    private int resolveFailedToolResultLimit() {
        AgentConfigProperties.ContextConfig.PruningConfig pruning = config.getContext().getPruning();
        int configured = pruning != null ? pruning.getFailedToolResultLimit() : 0;
        return configured > 0 ? configured : DEFAULT_FAILED_TOOL_RESULT_LIMIT;
    }

    private int resolveToolResultPreviewChars() {
        int configured = config.getContext().getPruning().getToolResultPreviewChars();
        return configured > 0 ? configured : DEFAULT_TOOL_RESULT_PREVIEW_CHARS;
    }

    private Set<String> selectPinnedFailureEntryIds(
            List<SessionTranscriptRepository.SessionTranscriptEntryRow> candidates
    ) {
        int failureLimit = resolveFailedToolResultLimit();
        if (failureLimit <= 0) {
            return Set.of();
        }
        List<String> entryIds = new ArrayList<>();
        for (int i = candidates.size() - 1; i >= 0; i--) {
            SessionTranscriptRepository.SessionTranscriptEntryRow row = candidates.get(i);
            if (!isFailurePayload(readPayload(row.payloadJson()))) {
                continue;
            }
            entryIds.add(row.id());
            if (entryIds.size() >= failureLimit) {
                break;
            }
        }
        return Set.copyOf(entryIds);
    }

    private boolean isFailurePayload(Map<String, Object> payload) {
        if (payload.isEmpty()) {
            return false;
        }
        if (!booleanValue(payload.get("success"))) {
            return true;
        }
        return stringValue(payload.get("error")) != null;
    }

    @Nullable
    private Object resolvePreviewSource(Map<String, Object> payload) {
        boolean success = booleanValue(payload.get("success"));
        if (!success) {
            Object error = payload.get("error");
            if (error != null) {
                return error;
            }
        }
        Object outputJson = payload.get("outputJson");
        if (outputJson != null) {
            return outputJson;
        }
        return payload.get("message");
    }

    private String buildReferenceSuffix(Map<String, Object> payload) {
        List<String> refs = new ArrayList<>();
        String artifactId = stringValue(payload.get("artifactId"));
        if (artifactId != null) {
            refs.add("产物:" + artifactId);
        }
        Object artifactIds = payload.get("artifactIds");
        if (artifactIds instanceof List<?> list && !list.isEmpty()) {
            refs.add("产物数:" + list.size());
        }
        Object documentIds = payload.get("documentIds");
        if (documentIds instanceof List<?> list && !list.isEmpty()) {
            refs.add("记忆文档数:" + list.size());
        }
        return String.join("，", refs);
    }

    private String extractPreview(@Nullable Object outputJsonValue) {
        if (outputJsonValue == null) {
            return "";
        }

        String outputJson = outputJsonValue.toString();
        if (outputJson.isBlank()) {
            return "";
        }

        String normalized;
        try {
            Object parsed = objectMapper.readValue(outputJson, Object.class);
            normalized = normalizeWhitespace(summarizeObject(parsed));
        } catch (Exception e) {
            normalized = normalizeWhitespace(outputJson);
        }
        return trimForPrompt(normalized, resolveToolResultPreviewChars());
    }

    private String summarizeObject(@Nullable Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Map<?, ?> map) {
            String specialized = summarizeSpecializedPayload(map);
            if (!specialized.isBlank()) {
                return specialized;
            }
            for (String key : List.of("error", "summary", "content", "message", "result", "text", "output")) {
                Object candidate = map.get(key);
                if (candidate != null) {
                    return summarizeObject(candidate);
                }
            }
            try {
                return objectMapper.writeValueAsString(map);
            } catch (JsonProcessingException e) {
                return map.toString();
            }
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .limit(3)
                    .map(this::summarizeObject)
                    .filter(item -> !item.isBlank())
                    .reduce((left, right) -> left + "; " + right)
                    .orElse("");
        }
        return value.toString();
    }

    private String summarizeSpecializedPayload(Map<?, ?> map) {
        if (looksLikeWebSearchPayload(map)) {
            return summarizeWebSearchPayload(map);
        }
        if (looksLikeWebFetchPayload(map)) {
            return summarizeWebFetchPayload(map);
        }
        return "";
    }

    private boolean looksLikeWebSearchPayload(Map<?, ?> map) {
        return map.containsKey("results") && (map.containsKey("query") || map.containsKey("resultCount"));
    }

    private boolean looksLikeWebFetchPayload(Map<?, ?> map) {
        return map.containsKey("url") && map.containsKey("content");
    }

    private String summarizeWebSearchPayload(Map<?, ?> map) {
        var parts = new ArrayList<String>();
        String query = stringValue(map.get("query"));
        if (query != null) {
            parts.add("搜索“" + compactText(query, 80) + "”");
        }

        int resultCount = numberValue(map.get("resultCount"));
        if (resultCount <= 0 && map.get("results") instanceof List<?> results) {
            resultCount = results.size();
        }
        if (resultCount > 0) {
            parts.add("命中 " + resultCount + " 条");
        }

        String answer = stringValue(map.get("answer"));
        if (answer != null) {
            parts.add("答案: " + compactText(answer, 160));
        }

        String topResults = summarizeSearchResults(map.get("results"));
        if (!topResults.isBlank()) {
            parts.add("结果: " + topResults);
        }

        return String.join("；", parts);
    }

    private String summarizeSearchResults(@Nullable Object resultsValue) {
        if (!(resultsValue instanceof List<?> results) || results.isEmpty()) {
            return "";
        }
        return results.stream()
                .limit(3)
                .map(this::summarizeSearchResult)
                .filter(item -> !item.isBlank())
                .reduce((left, right) -> left + "；" + right)
                .orElse("");
    }

    private String summarizeSearchResult(Object result) {
        if (!(result instanceof Map<?, ?> item)) {
            return "";
        }
        var parts = new ArrayList<String>();
        String title = stringValue(item.get("title"));
        if (title != null) {
            parts.add(compactText(title, 60));
        }
        String snippet = stringValue(item.get("snippet"));
        if (snippet != null) {
            parts.add(compactText(snippet, 120));
        }
        String url = stringValue(item.get("url"));
        if (url != null) {
            parts.add(url);
        }
        return String.join(" | ", parts);
    }

    private String summarizeWebFetchPayload(Map<?, ?> map) {
        var parts = new ArrayList<String>();
        String title = stringValue(map.get("title"));
        if (title != null) {
            parts.add("页面: " + compactText(title, 80));
        }
        String url = stringValue(map.get("url"));
        if (url != null) {
            parts.add("URL: " + url);
        }
        String content = stringValue(map.get("content"));
        if (content != null) {
            parts.add("正文摘要: " + compactText(content, 180));
        }
        if (booleanValue(map.get("truncated"))) {
            parts.add("正文已截断");
        }
        return String.join("；", parts);
    }

    private String compactText(@Nullable String text, int maxChars) {
        String normalized = normalizeWhitespace(text);
        if (normalized.isBlank() || normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars) + "...";
    }

    private int numberValue(@Nullable Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String trimForPrompt(String text, int maxChars) {
        if (text == null || text.isBlank()) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }

        int softTrimThreshold = Math.min(
                Math.max(maxChars * 4, SOFT_TRIM_MIN_THRESHOLD),
                SOFT_TRIM_MAX_THRESHOLD
        );
        if (text.length() > softTrimThreshold) {
            return HARD_CLEAR_TEMPLATE.formatted(text.length());
        }
        return softTrim(text, maxChars);
    }

    private String softTrim(String text, int maxChars) {
        int tailLength = Math.max(12, Math.min(maxChars / 4, 48));
        int headLength = Math.max(24, maxChars - tailLength - 18);
        if (headLength + tailLength >= text.length()) {
            return text;
        }

        int omittedChars = text.length() - headLength - tailLength;
        if (omittedChars <= 0) {
            return text;
        }

        String head = text.substring(0, headLength).stripTrailing();
        String tail = text.substring(text.length() - tailLength).stripLeading();
        return head + " …（中间省略" + omittedChars + "字符）… " + tail;
    }

    private String normalizeWhitespace(@Nullable String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private int estimateTokens(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        long cjkChars = text.chars()
                .filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)
                .count();
        long otherChars = text.length() - cjkChars;
        return Math.max(1, (int) (cjkChars + otherChars / 4));
    }

    private boolean booleanValue(@Nullable Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(value.toString());
    }
}
