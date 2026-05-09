package com.lifepilot.memory.eval.loader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * LongMemEval 数据集加载器（xiaowu0162/LongMemEval，ICLR 2025，MIT）。
 *
 * <p>数据集结构（longmemeval_s.json）：</p>
 * <pre>
 * [
 *   {
 *     "question_id": "...",
 *     "question": "...",
 *     "answer": "...",
 *     "question_type": "single-session-user" | "multi-session" |
 *                      "temporal-reasoning" | "knowledge-update" | "abstention",
 *     "haystack_sessions": [
 *       [ {"role": "user", "content": "..."}, {"role": "assistant", "content": "..."} ],
 *       ...
 *     ],
 *     "haystack_dates": ["2023-01-01 ...", ...],
 *     "haystack_session_ids": ["s-1", "s-2", ...],
 *     "answer_session_ids": ["s-3", ...]
 *   },
 *   ...
 * ]
 * </pre>
 *
 * <p>LongMemEval 每条 question 独立对应一段完整的 haystack_sessions，所以本 Loader
 * 把每条 question 映射为一个 {@link BenchmarkCase}（sessions + 单 question）。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public final class LongMemEvalLoader implements BenchmarkLoader {

    private static final Logger log = LoggerFactory.getLogger(LongMemEvalLoader.class);

    /** 相对于 datasetCacheDir 的候选文件路径。 */
    private static final List<String> DEFAULT_RELATIVE_PATHS = List.of(
            "longmemeval/longmemeval_s.json",
            "longmemeval/data/longmemeval_s.json",
            "longmemeval_s.json"
    );

    private final Path datasetCacheDir;
    private final ObjectMapper objectMapper;

    public LongMemEvalLoader(Path datasetCacheDir, ObjectMapper objectMapper) {
        this.datasetCacheDir = datasetCacheDir;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "longmemeval";
    }

    @Override
    public List<BenchmarkCase> load(int maxCases) {
        Path datasetFile = resolveDatasetFile();
        JsonNode root;
        try {
            root = objectMapper.readTree(Files.newInputStream(datasetFile));
        } catch (IOException e) {
            throw new BenchmarkDatasetNotFoundException(
                    name(), "读取 " + datasetFile + " 失败", e);
        }
        if (!root.isArray()) {
            throw new BenchmarkDatasetNotFoundException(
                    name(), "期望顶层是 JSON 数组，实际: " + root.getNodeType());
        }

        List<BenchmarkCase> cases = new ArrayList<>();
        for (JsonNode qNode : root) {
            if (maxCases > 0 && cases.size() >= maxCases) break;
            BenchmarkCase parsed = parseQuestion(qNode);
            if (parsed != null) cases.add(parsed);
        }
        log.info("LongMemEval 加载完成: file={}, cases={}, maxCases={}",
                datasetFile, cases.size(), maxCases);
        return Collections.unmodifiableList(cases);
    }

    private Path resolveDatasetFile() {
        for (String relative : DEFAULT_RELATIVE_PATHS) {
            Path candidate = datasetCacheDir.resolve(relative);
            if (Files.exists(candidate)) return candidate;
        }
        throw new BenchmarkDatasetNotFoundException(name(),
                "未在 " + datasetCacheDir + " 下找到 longmemeval_s.json");
    }

    private BenchmarkCase parseQuestion(JsonNode qNode) {
        String questionId = optText(qNode, "question_id", "id");
        if (questionId == null) return null;
        String question = optText(qNode, "question");
        String answer = optText(qNode, "answer");
        if (question == null || question.isBlank()) return null;
        String qType = optText(qNode, "question_type");

        JsonNode haystack = qNode.get("haystack_sessions");
        JsonNode sessionIds = qNode.get("haystack_session_ids");
        JsonNode dates = qNode.get("haystack_dates");
        List<BenchmarkSession> sessions = new ArrayList<>();
        if (haystack != null && haystack.isArray()) {
            for (int i = 0; i < haystack.size(); i++) {
                JsonNode sessionNode = haystack.get(i);
                if (!sessionNode.isArray()) continue;
                String sid = sessionIds != null && sessionIds.isArray() && i < sessionIds.size()
                        ? sessionIds.get(i).asText("session-" + i)
                        : "session-" + i;
                Instant ts = dates != null && dates.isArray() && i < dates.size()
                        ? tryParseInstant(dates.get(i).asText())
                        : null;
                List<BenchmarkMessage> messages = new ArrayList<>();
                for (JsonNode msgNode : sessionNode) {
                    String role = optText(msgNode, "role");
                    String content = optText(msgNode, "content");
                    if (role == null || content == null) continue;
                    messages.add(new BenchmarkMessage(role, content, ts));
                }
                if (!messages.isEmpty()) {
                    sessions.add(new BenchmarkSession(sid, ts, messages));
                }
            }
        }

        List<String> evidenceSessionIds = new ArrayList<>();
        JsonNode answerSessions = qNode.get("answer_session_ids");
        if (answerSessions != null && answerSessions.isArray()) {
            for (JsonNode n : answerSessions) {
                if (n.isTextual()) evidenceSessionIds.add(n.asText());
            }
        }

        BenchmarkQuestion benchmarkQuestion = new BenchmarkQuestion(
                questionId, question, answer == null ? "" : answer,
                mapQuestionType(qType), evidenceSessionIds);

        return new BenchmarkCase(
                questionId, name(), sessions, List.of(benchmarkQuestion), null);
    }

    private static BenchmarkQuestion.QuestionType mapQuestionType(String raw) {
        if (raw == null) return BenchmarkQuestion.QuestionType.OPEN_ENDED;
        String lower = raw.toLowerCase();
        if (lower.contains("single")) return BenchmarkQuestion.QuestionType.SINGLE_HOP;
        if (lower.contains("multi")) return BenchmarkQuestion.QuestionType.MULTI_HOP;
        if (lower.contains("temporal")) return BenchmarkQuestion.QuestionType.TEMPORAL;
        if (lower.contains("knowledge-update") || lower.contains("knowledge_update"))
            return BenchmarkQuestion.QuestionType.KNOWLEDGE_UPDATE;
        if (lower.contains("abstention")) return BenchmarkQuestion.QuestionType.ABSTENTION;
        return BenchmarkQuestion.QuestionType.OPEN_ENDED;
    }

    private static Instant tryParseInstant(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Instant.parse(raw.trim());
        } catch (Exception ignored) {
            try {
                return java.time.LocalDateTime.parse(raw.trim(),
                                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        .atZone(java.time.ZoneId.systemDefault()).toInstant();
            } catch (Exception ignored2) {
                log.debug("LongMemEval 日期解析失败: raw={}", raw);
                return null;
            }
        }
    }

    private static String optText(JsonNode node, String... keys) {
        if (node == null) return null;
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && !v.isNull() && v.isValueNode()) {
                String s = v.asText();
                if (s != null && !s.isBlank()) return s;
            }
        }
        return null;
    }
}
