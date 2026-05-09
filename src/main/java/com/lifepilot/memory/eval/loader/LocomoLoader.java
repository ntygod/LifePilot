package com.lifepilot.memory.eval.loader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * LoCoMo 数据集加载器（snap-research/locomo，Apache-2.0）。
 *
 * <p>数据集结构：</p>
 * <pre>
 * [
 *   {
 *     "sample_id": "conv-1",
 *     "conversation": {
 *       "session_1": [ {"speaker": "Alice", "text": "..."}, ... ],
 *       "session_1_date_time": "2023-01-01 10:00:00",
 *       "session_2": [...],
 *       "session_2_date_time": "...",
 *       ...
 *     },
 *     "qa": [
 *       {"question": "...", "answer": "...", "category": 1, "evidence": ["session_1/3", ...]}
 *     ]
 *   },
 *   ...
 * ]
 * </pre>
 *
 * <p>LoCoMo 的 QA category 映射：</p>
 * <ul>
 *     <li>1 = single-hop</li>
 *     <li>2 = multi-hop</li>
 *     <li>3 = temporal</li>
 *     <li>4 = open-ended</li>
 *     <li>5 = adversarial（归入 ABSTENTION）</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-05-09
 */
public final class LocomoLoader implements BenchmarkLoader {

    private static final Logger log = LoggerFactory.getLogger(LocomoLoader.class);
    private static final DateTimeFormatter LOCOMO_DT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 相对于 {@code datasetCacheDir} 的 LoCoMo 默认文件路径。 */
    private static final List<String> DEFAULT_RELATIVE_PATHS = List.of(
            "locomo/data/locomo10.json",
            "locomo/locomo10.json",
            "locomo10.json"
    );

    private final Path datasetCacheDir;
    private final ObjectMapper objectMapper;

    public LocomoLoader(Path datasetCacheDir, ObjectMapper objectMapper) {
        this.datasetCacheDir = datasetCacheDir;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "locomo";
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
        for (JsonNode convNode : root) {
            if (maxCases > 0 && cases.size() >= maxCases) break;
            BenchmarkCase parsed = parseConversation(convNode);
            if (parsed != null) cases.add(parsed);
        }
        log.info("LoCoMo 加载完成: file={}, cases={}, maxCases={}",
                datasetFile, cases.size(), maxCases);
        return Collections.unmodifiableList(cases);
    }

    private Path resolveDatasetFile() {
        for (String relative : DEFAULT_RELATIVE_PATHS) {
            Path candidate = datasetCacheDir.resolve(relative);
            if (Files.exists(candidate)) return candidate;
        }
        throw new BenchmarkDatasetNotFoundException(name(),
                "未在 " + datasetCacheDir + " 下找到 locomo10.json");
    }

    private BenchmarkCase parseConversation(JsonNode convNode) {
        String caseId = optText(convNode, "sample_id", "conversation_id", "id");
        if (caseId == null) caseId = "locomo-" + convNode.hashCode();

        JsonNode conversation = convNode.get("conversation");
        List<BenchmarkSession> sessions = new ArrayList<>();
        if (conversation != null && conversation.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = conversation.fields();
            // 收集 session_N 及其日期，按 N 排序
            java.util.TreeMap<Integer, JsonNode> sessionNodes = new java.util.TreeMap<>();
            java.util.Map<Integer, Instant> sessionTimes = new java.util.HashMap<>();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String key = entry.getKey();
                if (key.startsWith("session_")) {
                    if (key.endsWith("_date_time")) {
                        Integer idx = parseSessionIdx(key.substring("session_".length(),
                                key.length() - "_date_time".length()));
                        if (idx != null) {
                            sessionTimes.put(idx, parseLocomoDateTime(entry.getValue().asText()));
                        }
                    } else {
                        Integer idx = parseSessionIdx(key.substring("session_".length()));
                        if (idx != null && entry.getValue().isArray()) {
                            sessionNodes.put(idx, entry.getValue());
                        }
                    }
                }
            }

            for (Map.Entry<Integer, JsonNode> entry : sessionNodes.entrySet()) {
                int idx = entry.getKey();
                List<BenchmarkMessage> messages = new ArrayList<>();
                Instant sessionTime = sessionTimes.get(idx);
                for (JsonNode msgNode : entry.getValue()) {
                    String speaker = optText(msgNode, "speaker", "role");
                    String text = optText(msgNode, "text", "content", "dia_id");
                    if (text == null) continue;
                    String role = mapSpeakerToRole(speaker);
                    messages.add(new BenchmarkMessage(role, text, sessionTime));
                }
                if (!messages.isEmpty()) {
                    sessions.add(new BenchmarkSession(
                            "session_" + idx, sessionTime, messages));
                }
            }
        }

        JsonNode qa = convNode.get("qa");
        List<BenchmarkQuestion> questions = new ArrayList<>();
        if (qa != null && qa.isArray()) {
            int qIdx = 0;
            for (JsonNode qNode : qa) {
                String qid = optText(qNode, "qa_id", "id");
                if (qid == null) qid = caseId + "-q" + (++qIdx);
                String question = optText(qNode, "question");
                String answer = optText(qNode, "answer", "adversarial_answer");
                if (question == null || question.isBlank()) continue;
                int category = qNode.path("category").asInt(4);
                List<String> evidence = parseEvidenceList(qNode.get("evidence"));
                questions.add(new BenchmarkQuestion(
                        qid, question,
                        answer == null ? "" : answer,
                        mapLocomoCategoryToType(category),
                        evidence));
            }
        }

        return new BenchmarkCase(caseId, name(), sessions, questions, null);
    }

    private static BenchmarkQuestion.QuestionType mapLocomoCategoryToType(int category) {
        return switch (category) {
            case 1 -> BenchmarkQuestion.QuestionType.SINGLE_HOP;
            case 2 -> BenchmarkQuestion.QuestionType.MULTI_HOP;
            case 3 -> BenchmarkQuestion.QuestionType.TEMPORAL;
            case 5 -> BenchmarkQuestion.QuestionType.ABSTENTION;
            default -> BenchmarkQuestion.QuestionType.OPEN_ENDED;
        };
    }

    private static String mapSpeakerToRole(String speaker) {
        // LoCoMo 对话两个 speaker 都是"用户"，不做 assistant 区分。
        // 为兼容 ChatTurnService（user/assistant 交替），按出现顺序分配。
        // 这里简单策略：第一个出现的归为 user，其它归 assistant —— 由 Replayer 再做 alternation。
        // 本 loader 统一标 user，由下游根据索引切换。
        return "user";
    }

    private static Integer parseSessionIdx(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Instant parseLocomoDateTime(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDateTime.parse(raw.trim(), LOCOMO_DT_FORMAT)
                    .atZone(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException e) {
            log.debug("LoCoMo 日期解析失败: raw={}", raw);
            return null;
        }
    }

    private static List<String> parseEvidenceList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonNode n : node) {
            if (n.isTextual()) out.add(n.asText());
        }
        return out;
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
