package com.lifepilot.agent.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.conversation.transcript.SessionStoreRepository;
import com.lifepilot.conversation.transcript.SessionTranscriptRepository;
import com.lifepilot.conversation.transcript.TranscriptEntryType;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.*;

/**
 * CompactionEngine 负责在 transcript-first 架构下生成会话压缩摘要。
 * <p>该组件只追加 {@code compaction_summary} 条目，不改写旧 transcript；
 * 真正的“让位”通过 {@code firstKeptEntryId} 在读取上下文时生效。</p>
 *
 * @author zsg
 * @since 2026-03-23
 */
public class CompactionEngine {

    private static final Logger log = LoggerFactory.getLogger(CompactionEngine.class);

    /** 触发压缩的上下文窗口占用百分比阈值 */
    private static final int DEFAULT_TRIGGER_THRESHOLD_PERCENT = 75;
    /** 压缩后保留的最近完整轮次数 */
    private static final int DEFAULT_KEEP_RECENT_TURNS = 2;
    /** 满足此轮次数后才允许触发压缩 */
    private static final int DEFAULT_MIN_TURN_COUNT = 6;
    /** 单次压缩最多纳入的源条目数量，超出时截断为最近 N 条 */
    private static final int DEFAULT_MAX_SOURCE_ENTRIES = 80;
    /** 生成摘要的最大字符数 */
    private static final int DEFAULT_SUMMARY_MAX_CHARS = 500;
    /** payload 预览截取的最大字符数 */
    private static final int DEFAULT_PAYLOAD_PREVIEW_CHARS = 400;
    /** 从摘要中提取的关键要点上限 */
    private static final int DEFAULT_KEYPOINT_LIMIT = 8;
    /** checkpoint 中关联产物引用上限 */
    private static final int DEFAULT_ARTIFACT_REF_LIMIT = 5;
    /** 恢复计划条目上限 */
    private static final int DEFAULT_RESUME_PLAN_LIMIT = 3;

    private final AgentConfigProperties config;
    private final SessionTranscriptRepository transcriptRepository;
    private final SessionStoreRepository sessionStoreRepository;
    /** 负责确定上一次压缩边界和活跃条目范围 */
    private final TranscriptCompactionBoundaryResolver boundaryResolver;
    private final PromptRegistry promptRegistry;
    /** 调用 LLM 生成压缩摘要和关键要点 */
    private final GenerationRouter generationRouter;
    private final ObjectMapper objectMapper;
    /** 压缩前将即将被"让位"的条目刷入长期记忆，可选 */
    @Nullable
    private final PreCompactionMemoryFlushEngine memoryFlushEngine;

    public CompactionEngine(AgentConfigProperties config,
                            SessionTranscriptRepository transcriptRepository,
                            SessionStoreRepository sessionStoreRepository,
                            TranscriptCompactionBoundaryResolver boundaryResolver,
                            PromptRegistry promptRegistry,
                            GenerationRouter generationRouter,
                            ObjectMapper objectMapper) {
        this(config, transcriptRepository, sessionStoreRepository, boundaryResolver,
                promptRegistry, generationRouter, objectMapper, null);
    }

    public CompactionEngine(AgentConfigProperties config,
                            SessionTranscriptRepository transcriptRepository,
                            SessionStoreRepository sessionStoreRepository,
                            TranscriptCompactionBoundaryResolver boundaryResolver,
                            PromptRegistry promptRegistry,
                            GenerationRouter generationRouter,
                            ObjectMapper objectMapper,
                            @Nullable PreCompactionMemoryFlushEngine memoryFlushEngine) {
        this.config = Objects.requireNonNull(config);
        this.transcriptRepository = Objects.requireNonNull(transcriptRepository);
        this.sessionStoreRepository = Objects.requireNonNull(sessionStoreRepository);
        this.boundaryResolver = Objects.requireNonNull(boundaryResolver);
        this.promptRegistry = Objects.requireNonNull(promptRegistry);
        this.generationRouter = Objects.requireNonNull(generationRouter);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.memoryFlushEngine = memoryFlushEngine;
    }

    /**
     * 判断指定会话是否需要压缩，如需要则执行完整压缩流程。
     * <p>流程：检查启用状态 → 解析边界与活跃条目 → token 阈值判定 →
     * 分组完整轮次 → 刷出即将淘汰的条目到长期记忆 → LLM 生成摘要与关键要点 →
     * 构建 checkpoint → 追加 compaction_summary 条目。</p>
     *
     * @param sessionId           会话 ID
     * @param traceId             可选的链路追踪 ID
     * @param preferredProviderId 首选 LLM 提供商 ID，用于查询上下文窗口大小
     * @return 是否成功执行了压缩
     */
    public boolean compactIfNeeded(@Nullable String sessionId,
                                   @Nullable String traceId,
                                   @Nullable String preferredProviderId) {
        // ── 前置校验：会话 ID 为空或功能未启用时直接跳过 ──
        if (sessionId == null || sessionId.isBlank() || !compactionConfig().isEnabled()) {
            return false;
        }
        try {
            // ── 第 1 步：加载全量 transcript 条目 ──
            List<SessionTranscriptRepository.SessionTranscriptEntryRow> allRows =
                    transcriptRepository.findBySessionId(sessionId);
            if (allRows.isEmpty()) {
                return false;
            }

            // ── 第 2 步：解析上一次压缩边界，确定当前活跃条目范围 ──
            // boundary 为 null 表示该会话从未压缩过
            TranscriptCompactionBoundaryResolver.CompactionBoundary boundary =
                    boundaryResolver.resolveLatest(allRows).orElse(null);
            List<SessionTranscriptRepository.SessionTranscriptEntryRow> activeRows =
                    boundaryResolver.filterRowsForActiveContext(allRows, boundary);
            if (activeRows.isEmpty()) {
                return false;
            }

            // ── 第 3 步：Token 阈值判定 ──
            // 只统计对模型可见且非 compaction_summary 类型的条目
            List<SessionTranscriptRepository.SessionTranscriptEntryRow> activeVisibleRows = activeRows.stream()
                    .filter(SessionTranscriptRepository.SessionTranscriptEntryRow::visibleToModel)
                    .filter(row -> !TranscriptEntryType.COMPACTION_SUMMARY.value().equals(row.entryType()))
                    .toList();
            if (!shouldCompact(activeVisibleRows, preferredProviderId)) {
                return false;
            }

            // ── 第 4 步：按完整对话轮次分组，检查轮次数是否达到最低要求 ──
            List<List<SessionTranscriptRepository.SessionTranscriptEntryRow>> completeTurns =
                    groupCompleteTurns(activeRows);
            if (completeTurns.size() < resolveMinTurnCount()) {
                return false;
            }

            // ── 第 5 步：计算保留边界 ──
            // 最近 keepRecentTurns 个完整轮次保留在上下文中不被压缩，
            // firstKeptEntryId 标记保留区域的起点
            int keepRecentTurns = resolveKeepRecentTurns();
            if (completeTurns.size() <= keepRecentTurns) {
                return false;
            }
            List<List<SessionTranscriptRepository.SessionTranscriptEntryRow>> keptTurns =
                    completeTurns.subList(Math.max(0, completeTurns.size() - keepRecentTurns), completeTurns.size());
            String firstKeptEntryId = keptTurns.getFirst().getFirst().id();

            // ── 第 6 步：收集待压缩条目（firstKeptEntryId 之前的可见条目） ──
            List<SessionTranscriptRepository.SessionTranscriptEntryRow> compactableRows =
                    selectCompactableRows(activeRows, firstKeptEntryId);
            if (compactableRows.isEmpty()) {
                return false;
            }

            // ── 第 7 步：压缩前记忆刷出 ──
            // 将即将被"让位"的条目先刷入长期记忆，防止信息永久丢失
            List<String> flushedDocumentIds = memoryFlushEngine != null
                    ? memoryFlushEngine.flush(sessionId, traceId, compactableRows)
                    : List.of();

            // ── 第 8 步：拼装 prompt 并调用 LLM 生成压缩摘要 ──
            String promptInput = buildPromptInput(boundary != null ? boundary.summary() : null, compactableRows);
            if (promptInput.isBlank()) {
                return false;
            }

            String prompt = promptRegistry.render("memory/compression-summary", Map.of(
                    "conversation", promptInput
            ));
            LlmResponse response = generationRouter.call(
                    LlmScene.MEMORY_COMPRESSION,
                    prompt,
                    null,
                    null,
                    null,
                    GenerationCapability.CHAT,
                    null);
            String summary = normalizeSummary(response.content());
            if (summary.isBlank()) {
                return false;
            }

            // ── 第 9 步：从摘要中提取关键要点，并构建结构化 checkpoint ──
            List<String> keyPoints = extractKeyPoints(summary);
            TaskCheckpoint checkpoint = buildCheckpoint(
                    summary,
                    keyPoints,
                    compactableRows,
                    flushedDocumentIds
            );

            // ── 第 10 步：组装 payload 并追加 compaction_summary 条目 ──
            Instant now = Instant.now();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("summary", summary);
            payload.put("firstKeptEntryId", firstKeptEntryId);
            payload.put("sourceEntryCount", compactableRows.size());
            payload.put("sourceStartEntryId", compactableRows.getFirst().id());
            payload.put("sourceEndEntryId", compactableRows.getLast().id());
            // 如果存在前一次压缩摘要，记录链路以便回溯
            if (boundary != null && boundary.summaryEntryId() != null) {
                payload.put("previousSummaryEntryId", boundary.summaryEntryId());
            }
            if (!keyPoints.isEmpty()) {
                payload.put("keyPoints", keyPoints);
            }
            if (checkpoint.hasContent()) {
                payload.put("checkpoint", checkpoint.toPayload());
            }
            if (!flushedDocumentIds.isEmpty()) {
                payload.put("memoryDocumentIds", flushedDocumentIds);
            }

            // 追加 compaction_summary 条目到 transcript（不改写旧条目）
            transcriptRepository.appendEntry(
                    sessionId,
                    TranscriptEntryType.COMPACTION_SUMMARY,
                    null,
                    null,
                    normalizeBlank(traceId),
                    false,
                    false,
                    payload,
                    now
            );
            // 递增会话的压缩计数器
            sessionStoreRepository.incrementCompactionCount(sessionId, now);
            log.info("会话压缩完成: sessionId={}, sourceEntryCount={}, firstKeptEntryId={}",
                    sessionId, compactableRows.size(), firstKeptEntryId);
            return true;
        } catch (Exception e) {
            // 压缩为非关键路径，失败后静默降级，不影响正常对话
            log.warn("会话压缩失败: sessionId={}, error={}", sessionId, e.getMessage());
            return false;
        }
    }

    /**
     * 判断当前活跃可见条目的 token 总量是否已超过触发阈值。
     * <p>阈值 = 有效上下文窗口 × 触发百分比（默认 75%），
     * 超过该阈值说明上下文即将溢出，需要压缩腾出空间。</p>
     */
    private boolean shouldCompact(List<SessionTranscriptRepository.SessionTranscriptEntryRow> activeVisibleRows,
                                  @Nullable String preferredProviderId) {
        if (activeVisibleRows.isEmpty()) {
            return false;
        }
        // 根据 Provider 实际窗口和配置百分比计算触发阈值
        int triggerThresholdTokens = resolveTriggerThresholdTokens(preferredProviderId);
        if (triggerThresholdTokens <= 0) {
            return false;
        }
        // 累加所有可见条目的 token 估算值，与阈值比较
        int tokenEstimate = activeVisibleRows.stream()
                .mapToInt(row -> Math.max(0, row.tokenEstimate()))
                .sum();
        return tokenEstimate >= triggerThresholdTokens;
    }

    /**
     * 将可见条目按"用户消息 → 助手回复"的结构分组为完整轮次。
     * <p>一个完整轮次以 USER_MESSAGE 开头、至少包含一条 ASSISTANT_MESSAGE。
     * 不完整的轮次（如只有用户消息但无助手回复）不纳入结果，
     * 保证压缩时不会拆断正在进行中的对话轮。</p>
     */
    private List<List<SessionTranscriptRepository.SessionTranscriptEntryRow>> groupCompleteTurns(
            List<SessionTranscriptRepository.SessionTranscriptEntryRow> rows
    ) {
        // 过滤出对模型可见且非压缩摘要的条目，按时间排序
        List<SessionTranscriptRepository.SessionTranscriptEntryRow> visibleRows = rows.stream()
                .filter(SessionTranscriptRepository.SessionTranscriptEntryRow::visibleToModel)
                .filter(row -> !TranscriptEntryType.COMPACTION_SUMMARY.value().equals(row.entryType()))
                .sorted(Comparator.comparing(SessionTranscriptRepository.SessionTranscriptEntryRow::createdAt))
                .toList();
        if (visibleRows.isEmpty()) {
            return List.of();
        }

        List<List<SessionTranscriptRepository.SessionTranscriptEntryRow>> turns = new ArrayList<>();
        List<SessionTranscriptRepository.SessionTranscriptEntryRow> current = new ArrayList<>();
        boolean hasReply = false;

        for (SessionTranscriptRepository.SessionTranscriptEntryRow row : visibleRows) {
            TranscriptEntryType entryType = TranscriptEntryType.fromValue(row.entryType());
            if (entryType == TranscriptEntryType.USER_MESSAGE) {
                // 遇到新的用户消息时，将前一个已有回复的轮次收入结果
                if (!current.isEmpty() && hasReply) {
                    turns.add(List.copyOf(current));
                }
                // 开始新轮次
                current = new ArrayList<>();
                current.add(row);
                hasReply = false;
                continue;
            }

            // 非用户消息且当前轮次为空，说明是脱离轮次的孤立条目，跳过
            if (current.isEmpty()) {
                continue;
            }
            // 将 tool_call / tool_result / assistant_message 等归入当前轮次
            current.add(row);
            if (entryType == TranscriptEntryType.ASSISTANT_MESSAGE) {
                hasReply = true;
            }
        }

        // 收尾：最后一个轮次如果有回复也要收入
        if (!current.isEmpty() && hasReply) {
            turns.add(List.copyOf(current));
        }
        return List.copyOf(turns);
    }

    /**
     * 从活跃条目中选取 firstKeptEntryId 之前的可见条目作为压缩源。
     * <p>遍历时遇到 firstKeptEntryId 即停止——该条目及之后的属于保留区域。
     * 超过 maxSourceEntries 时丢弃最早的条目，只保留最近的部分，
     * 保证送入 LLM 的 prompt 不会过长。</p>
     */
    private List<SessionTranscriptRepository.SessionTranscriptEntryRow> selectCompactableRows(
            List<SessionTranscriptRepository.SessionTranscriptEntryRow> activeRows,
            String firstKeptEntryId
    ) {
        List<SessionTranscriptRepository.SessionTranscriptEntryRow> rows = new ArrayList<>();
        for (SessionTranscriptRepository.SessionTranscriptEntryRow row : activeRows) {
            // 到达保留区域起点，停止收集
            if (firstKeptEntryId.equals(row.id())) {
                break;
            }
            // 只收集对模型可见且非压缩摘要的条目
            if (row.visibleToModel() && !TranscriptEntryType.COMPACTION_SUMMARY.value().equals(row.entryType())) {
                rows.add(row);
            }
        }
        // 超出上限时截断为最近 N 条，避免 prompt 过长
        int maxSourceEntries = resolveMaxSourceEntries();
        if (rows.size() <= maxSourceEntries) {
            return List.copyOf(rows);
        }
        log.info("会话压缩源条目过多，截断为最近 {} 条: sourceEntryCount={}", maxSourceEntries, rows.size());
        return List.copyOf(rows.subList(rows.size() - maxSourceEntries, rows.size()));
    }

    /**
     * 拼装送入 LLM 的压缩 prompt 输入。
     * <p>结构分两段：
     * <ol>
     *   <li>[已压缩历史摘要] — 上一次压缩的摘要（增量压缩场景），首次压缩时为空</li>
     *   <li>[新增待压缩内容] — 本次需要纳入摘要的各条目，按类型格式化为可读文本</li>
     * </ol></p>
     */
    private String buildPromptInput(@Nullable String previousSummary,
                                    List<SessionTranscriptRepository.SessionTranscriptEntryRow> rows) {
        StringBuilder buffer = new StringBuilder();
        // 增量压缩：将上一次摘要作为背景信息放在前面
        if (previousSummary != null && !previousSummary.isBlank()) {
            buffer.append("[已压缩历史摘要]\n")
                    .append(previousSummary.trim())
                    .append("\n\n");
        }
        // 逐条格式化待压缩条目
        buffer.append("[新增待压缩内容]\n");
        rows.stream()
                .map(this::formatRow)
                .filter(line -> !line.isBlank())
                .forEach(line -> buffer.append(line).append('\n'));
        return buffer.toString().trim();
    }

    /**
     * 将单条 transcript 条目格式化为 prompt 可读文本。
     * <p>按条目类型分派到不同的格式化方法，输出格式为 {@code [类型标签] 内容预览}。</p>
     */
    private String formatRow(SessionTranscriptRepository.SessionTranscriptEntryRow row) {
        Map<String, Object> payload = readPayload(row.payloadJson());
        return switch (TranscriptEntryType.fromValue(row.entryType())) {
            case USER_MESSAGE, ASSISTANT_MESSAGE, SYSTEM_EVENT -> formatMessageRow(row, payload);
            case TOOL_CALL -> formatToolCallRow(payload);
            case TOOL_RESULT -> formatToolResultRow(payload);
            case ARTIFACT_REF -> formatArtifactRow(payload);
            default -> formatGenericRow(row, payload);
        };
    }

    /** 格式化用户/助手/系统消息条目，输出 {@code [角色] 消息内容}。 */
    private String formatMessageRow(SessionTranscriptRepository.SessionTranscriptEntryRow row,
                                    Map<String, Object> payload) {
        String role = normalizeRole(row.role());
        String content = stringValue(payload.get("content"));
        if (content == null || content.isBlank()) {
            return "";
        }
        return "[" + (role.isBlank() ? "message" : role) + "] " + normalizeWhitespace(content);
    }

    /** 格式化工具调用条目，输出 {@code [tool_call:工具ID] 输入: 参数预览}。 */
    private String formatToolCallRow(Map<String, Object> payload) {
        String toolId = stringValue(payload.get("toolId"));
        String inputJson = stringValue(payload.get("inputJson"));
        String preview = abbreviate(extractPayloadPreview(inputJson), DEFAULT_PAYLOAD_PREVIEW_CHARS);
        if ((toolId == null || toolId.isBlank()) && preview.isBlank()) {
            return "";
        }
        return "[tool_call:" + (toolId != null ? toolId : "tool") + "] 输入: " + preview;
    }

    /** 格式化工具执行结果条目，输出 {@code [tool_result:工具ID] 成功/失败: 结果预览}。 */
    private String formatToolResultRow(Map<String, Object> payload) {
        String toolId = stringValue(payload.get("toolId"));
        boolean success = booleanValue(payload.get("success"));
        String outputJson = stringValue(payload.get("outputJson"));
        String preview = abbreviate(extractPayloadPreview(outputJson), DEFAULT_PAYLOAD_PREVIEW_CHARS);
        if ((toolId == null || toolId.isBlank()) && preview.isBlank()) {
            return "";
        }
        return "[tool_result:" + (toolId != null ? toolId : "tool") + "] "
                + (success ? "成功" : "失败") + ": " + preview;
    }

    /** 格式化产物引用条目，输出 {@code [artifact:类型] 标题: 摘要}。 */
    private String formatArtifactRow(Map<String, Object> payload) {
        String artifactType = stringValue(payload.get("artifactType"));
        String title = stringValue(payload.get("title"));
        String summary = stringValue(payload.get("summary"));
        if (artifactType == null && title == null && summary == null) {
            return "";
        }
        StringBuilder buffer = new StringBuilder("[artifact:");
        buffer.append(artifactType != null ? artifactType : "artifact").append("] ");
        if (title != null) {
            buffer.append(title);
        }
        if (summary != null && !summary.isBlank()) {
            if (title != null && !title.isBlank()) {
                buffer.append(": ");
            }
            buffer.append(normalizeWhitespace(summary));
        }
        return buffer.toString().trim();
    }

    /** 兜底格式化：对未识别类型的条目直接序列化 payload 并截断。 */
    private String formatGenericRow(SessionTranscriptRepository.SessionTranscriptEntryRow row,
                                    Map<String, Object> payload) {
        String preview = abbreviate(normalizeWhitespace(payload.toString()), DEFAULT_PAYLOAD_PREVIEW_CHARS);
        if (preview.isBlank()) {
            return "";
        }
        return "[" + row.entryType() + "] " + preview;
    }

    /** 反序列化 payload JSON 字符串为 Map，解析失败时返回空 Map 而非抛异常。 */
    private Map<String, Object> readPayload(@Nullable String payloadJson) {
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

    /**
     * 从 JSON 或纯文本中提取可读预览。
     * <p>如果输入是合法 JSON，先解析为对象再通过 {@link #summarizeObject} 提取语义字段；
     * 解析失败则直接当作纯文本返回。</p>
     */
    private String extractPayloadPreview(@Nullable String jsonOrText) {
        if (jsonOrText == null || jsonOrText.isBlank()) {
            return "";
        }
        try {
            Object parsed = objectMapper.readValue(jsonOrText, Object.class);
            return normalizeWhitespace(summarizeObject(parsed));
        } catch (Exception e) {
            // 非 JSON，当作纯文本处理
            return normalizeWhitespace(jsonOrText);
        }
    }

    /**
     * 递归提取对象中最具语义的文本表示。
     * <p>优先级：
     * <ul>
     *   <li>Map 类型 → 按优先级查找 summary/content/message/result 等语义字段</li>
     *   <li>List 类型 → 取前 4 个元素用分号拼接</li>
     *   <li>String/其他 → 直接转字符串</li>
     * </ul></p>
     */
    private String summarizeObject(@Nullable Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Map<?, ?> map) {
            // 按优先级查找最具语义的字段
            for (String key : List.of("summary", "content", "message", "result", "text", "output", "title")) {
                Object candidate = map.get(key);
                if (candidate != null) {
                    return summarizeObject(candidate);
                }
            }
            // 无语义字段，序列化整个 Map 作为兜底
            try {
                return objectMapper.writeValueAsString(map);
            } catch (JsonProcessingException e) {
                return map.toString();
            }
        }
        if (value instanceof List<?> list) {
            // 列表取前 4 个元素，避免预览过长
            return list.stream()
                    .limit(4)
                    .map(this::summarizeObject)
                    .filter(item -> !item.isBlank())
                    .reduce((left, right) -> left + "; " + right)
                    .orElse("");
        }
        return value.toString();
    }

    /**
     * 规范化摘要文本：合并连续空白为单个空格，并截断到最大字符数。
     * <p>超长时在末尾追加 "..." 标识截断，保证摘要体积可控。</p>
     */
    private String normalizeSummary(@Nullable String summary) {
        if (summary == null || summary.isBlank()) {
            return "";
        }
        String normalized = normalizeWhitespace(summary);
        int maxChars = resolveSummaryMaxChars();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    /** 将连续空白字符（空格、换行、制表符等）合并为单个空格。 */
    private String normalizeWhitespace(@Nullable String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    /** 截断文本到指定最大字符数，超长时追加 "..." 后缀。 */
    private String abbreviate(String text, int maxChars) {
        if (text == null || text.isBlank()) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    /** 安全地将 payload 中的值转换为 boolean，null 或无法解析时返回 false。 */
    private boolean booleanValue(@Nullable Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(value.toString());
    }

    /** 安全地将 payload 中的值转换为 String，null 或空白时返回 null。 */
    @Nullable
    private String stringValue(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    /** 规范化角色名称为小写，null 时返回空字符串。 */
    private String normalizeRole(@Nullable String role) {
        return role == null ? "" : role.trim().toLowerCase();
    }

    /** 获取压缩配置，配置为 null 时返回默认实例以避免空指针。 */
    private AgentConfigProperties.ContextConfig.CompactionConfig compactionConfig() {
        AgentConfigProperties.ContextConfig.CompactionConfig compaction = config.getContext().getCompaction();
        return compaction != null ? compaction : new AgentConfigProperties.ContextConfig.CompactionConfig();
    }

    /**
     * 计算触发压缩的 token 阈值。
     * <p>公式：有效上下文窗口 × 触发百分比（默认 75%）。
     * 例如窗口为 128K、阈值 75% 时，token 总量达到 96K 即触发压缩。</p>
     */
    private int resolveTriggerThresholdTokens(@Nullable String preferredProviderId) {
        int percent = compactionConfig().getTriggerThresholdPercent();
        if (percent <= 0) {
            percent = DEFAULT_TRIGGER_THRESHOLD_PERCENT;
        }
        return Math.max(1, resolveEffectiveContextWindow(preferredProviderId) * percent / 100);
    }

    /**
     * 确定有效上下文窗口大小。
     * <p>取用户配置的窗口和 Provider 实际支持的窗口中的较小值，
     * 保证阈值计算不会超出 Provider 的真实能力。
     * Provider 查询失败时静默降级为配置值。</p>
     */
    private int resolveEffectiveContextWindow(@Nullable String preferredProviderId) {
        int configuredWindow = Math.max(1024, config.getContext().getMaxContextTokens());
        try {
            int providerWindow = generationRouter.resolveMaxContextWindow(
                    config.getLoop().getLlmScene(),
                    preferredProviderId,
                    null
            );
            if (providerWindow > 0) {
                return Math.min(configuredWindow, providerWindow);
            }
        } catch (Exception e) {
            log.debug("读取 Provider 上下文窗口失败，回退默认配置: provider={}, error={}",
                    preferredProviderId, e.getMessage());
        }
        return configuredWindow;
    }

    // ──────────────────────────────────────────────────────
    // 以下 resolve* 方法统一从配置读取值，无效时回退到默认常量
    // ──────────────────────────────────────────────────────

    private int resolveKeepRecentTurns() {
        int configured = compactionConfig().getKeepRecentTurns();
        return configured > 0 ? configured : DEFAULT_KEEP_RECENT_TURNS;
    }

    private int resolveMinTurnCount() {
        int configured = compactionConfig().getMinTurnCount();
        return configured > 0 ? configured : DEFAULT_MIN_TURN_COUNT;
    }

    private int resolveMaxSourceEntries() {
        int configured = compactionConfig().getMaxSourceEntries();
        return configured > 0 ? configured : DEFAULT_MAX_SOURCE_ENTRIES;
    }

    private int resolveSummaryMaxChars() {
        int configured = compactionConfig().getSummaryMaxChars();
        return configured > 0 ? configured : DEFAULT_SUMMARY_MAX_CHARS;
    }

    // ──────────────────────────────────────────────────────
    // 通用工具方法
    // ──────────────────────────────────────────────────────

    /** 将空白字符串规范化为 null，用于可选字段的存储。 */
    @Nullable
    private String normalizeBlank(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * 从摘要中通过 LLM 提取关键要点列表。
     * <p>两级降级策略：
     * <ol>
     *   <li>调用 LLM 用专门的 keypoints prompt 提取结构化要点</li>
     *   <li>LLM 调用失败或返回空时，回退为启发式句子切分</li>
     * </ol></p>
     */
    private List<String> extractKeyPoints(String summary) {
        if (summary.isBlank()) {
            return List.of();
        }
        try {
            // 使用专门的 keypoints prompt 模板调用 LLM
            String prompt = promptRegistry.render("memory/compression-keypoints", Map.of(
                    "summary", summary
            ));
            if (prompt == null || prompt.isBlank()) {
                return fallbackKeyPoints(summary);
            }
            LlmResponse response = generationRouter.call(
                    LlmScene.MEMORY_COMPRESSION,
                    prompt,
                    null,
                    null,
                    null,
                    GenerationCapability.CHAT,
                    null
            );
            List<String> keyPoints = parseKeyPoints(response.content());
            // LLM 返回空时降级到启发式切分
            return keyPoints.isEmpty() ? fallbackKeyPoints(summary) : keyPoints;
        } catch (Exception e) {
            log.debug("提取压缩关键要点失败，回退到启发式摘要切分: error={}", e.getMessage());
            return fallbackKeyPoints(summary);
        }
    }

    /**
     * 解析 LLM 返回的关键要点文本。
     * <p>预期格式为每行一个要点，支持 {@code -} 和 {@code •} 前缀。
     * 解析后去重并限制为最多 {@link #DEFAULT_KEYPOINT_LIMIT} 条。</p>
     */
    private List<String> parseKeyPoints(@Nullable String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        return content.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                // 剥离常见的列表前缀符号
                .map(line -> {
                    if (line.startsWith("-")) {
                        return line.substring(1).trim();
                    }
                    if (line.startsWith("•")) {
                        return line.substring(1).trim();
                    }
                    return line;
                })
                .filter(line -> !line.isEmpty())
                .limit(DEFAULT_KEYPOINT_LIMIT)
                .distinct()
                .toList();
    }

    /** 降级方案：将摘要按句子切分，取前 N 句作为关键要点。 */
    private List<String> fallbackKeyPoints(String summary) {
        return splitSentences(summary).stream()
                .limit(DEFAULT_KEYPOINT_LIMIT)
                .toList();
    }

    /**
     * 构建任务检查点（TaskCheckpoint），为压缩后的 Agent 恢复执行提供结构化上下文。
     * <p>构建过程：
     * <ol>
     *   <li>将关键要点按关键词分类到 已完成/待办/决策/风险 四个维度</li>
     *   <li>从 transcript 条目中提取约束性提示（如"必须"、"不能"等表述）</li>
     *   <li>收集关联的产物引用和记忆文档引用</li>
     *   <li>基于待办项和决策生成恢复计划</li>
     *   <li>推断会话目标、阶段和领域状态快照</li>
     * </ol></p>
     */
    private TaskCheckpoint buildCheckpoint(
            @Nullable String summary,
            List<String> keyPoints,
            List<SessionTranscriptRepository.SessionTranscriptEntryRow> rows,
            List<String> memoryDocumentIds
    ) {
        if ((summary == null || summary.isBlank()) && keyPoints.isEmpty() && rows.isEmpty()) {
            return TaskCheckpoint.empty();
        }

        // ── 分类关键要点到四个维度 ──
        LinkedHashSet<String> decisions = new LinkedHashSet<>();
        LinkedHashSet<String> openItems = new LinkedHashSet<>();
        LinkedHashSet<String> completedItems = new LinkedHashSet<>();
        LinkedHashSet<String> risks = new LinkedHashSet<>();
        for (String keyPoint : keyPoints) {
            classifyKeyPoint(keyPoint, decisions, openItems, completedItems, risks);
        }
        // 如果没有提取到已完成项，用整段摘要作为兜底
        if (completedItems.isEmpty() && summary != null && !summary.isBlank()) {
            completedItems.add(summary);
        }

        // ── 提取约束提示和关联产物 ──
        LinkedHashSet<String> constraints = extractConstraintHints(rows);
        LinkedHashSet<TaskCheckpoint.ArtifactRef> artifacts = extractArtifactRefs(rows, memoryDocumentIds);

        // ── 汇总所有需要的上下文引用（memory:xxx 和 artifact:xxx） ──
        // 供 Agent 恢复时按需回查完整细节
        LinkedHashSet<String> neededContextRefs = new LinkedHashSet<>();
        memoryDocumentIds.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .map(id -> "memory:" + id)
                .forEach(neededContextRefs::add);
        artifacts.stream()
                .map(TaskCheckpoint.ArtifactRef::refId)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .map(id -> "artifact:" + id)
                .forEach(neededContextRefs::add);

        // ── 生成恢复计划并组装最终 checkpoint ──
        LinkedHashSet<String> resumePlan = buildResumePlan(openItems, decisions, artifacts);
        return new TaskCheckpoint(
                inferGoal(rows),
                inferPhase(rows),
                List.copyOf(completedItems),
                List.copyOf(openItems),
                List.copyOf(decisions),
                List.copyOf(constraints),
                List.copyOf(artifacts),
                List.copyOf(resumePlan),
                List.copyOf(risks),
                List.copyOf(neededContextRefs),
                buildDomainState(rows)
        );
    }

    /**
     * 根据关键词将单个关键要点分类到对应维度。
     * <p>分类优先级：风险 > 待办 > 决策 > 已完成。
     * 即一个要点如果同时包含风险和待办关键词，归入风险。
     * 决策类要点同时计入 decisions 和 completedItems，因为决策本身也是已达成的结论。</p>
     */
    private void classifyKeyPoint(
            String keyPoint,
            LinkedHashSet<String> decisions,
            LinkedHashSet<String> openItems,
            LinkedHashSet<String> completedItems,
            LinkedHashSet<String> risks
    ) {
        if (keyPoint == null || keyPoint.isBlank()) {
            return;
        }
        // 优先级 1：包含风险/异常相关关键词 → 风险项
        if (containsAny(keyPoint, "风险", "失败", "异常", "冲突", "问题", "注意")) {
            risks.add(keyPoint);
            return;
        }
        // 优先级 2：包含待办/未完成相关关键词 → 待办项
        if (containsAny(keyPoint, "待", "未", "剩余", "后续", "下一步", "需要", "阻塞", "TODO")) {
            openItems.add(keyPoint);
            return;
        }
        // 优先级 3：包含决策相关关键词 → 决策 + 已完成
        if (containsAny(keyPoint, "决定", "采用", "改为", "选择", "确认", "统一", "约定")) {
            decisions.add(keyPoint);
            completedItems.add(keyPoint);
            return;
        }
        // 默认归入已完成项
        completedItems.add(keyPoint);
    }

    /**
     * 从用户/助手消息中提取包含约束性关键词的句子。
     * <p>约束提示用于在压缩摘要中保留用户的硬性要求（如"必须用 xxx"、"不能删除 xxx"），
     * 防止压缩后 Agent 遗忘这些重要约束。最多提取 4 条以控制 checkpoint 体积。</p>
     */
    private LinkedHashSet<String> extractConstraintHints(
            List<SessionTranscriptRepository.SessionTranscriptEntryRow> rows
    ) {
        LinkedHashSet<String> constraints = new LinkedHashSet<>();
        for (SessionTranscriptRepository.SessionTranscriptEntryRow row : rows) {
            // 只从用户和助手的消息中提取，跳过工具调用等
            TranscriptEntryType type = TranscriptEntryType.fromValue(row.entryType());
            if (type != TranscriptEntryType.USER_MESSAGE && type != TranscriptEntryType.ASSISTANT_MESSAGE) {
                continue;
            }
            String content = stringValue(readPayload(row.payloadJson()).get("content"));
            if (content == null || content.isBlank()) {
                continue;
            }
            // 逐句扫描，匹配约束性关键词
            for (String sentence : splitSentences(content)) {
                if (containsAny(sentence, "必须", "不能", "不要", "仅", "限制", "约束", "优先", "避免")) {
                    constraints.add(sentence);
                }
                if (constraints.size() >= 4) {
                    return constraints;
                }
            }
        }
        return constraints;
    }

    /**
     * 从 transcript 条目和记忆文档 ID 中收集关联产物引用。
     * <p>三类来源：
     * <ol>
     *   <li>ARTIFACT_REF 类型条目 — 直接提取产物元信息</li>
     *   <li>其他条目中携带 artifactId 的 — 作为间接产物引用</li>
     *   <li>压缩前刷出的记忆文档 — 作为 memory_document 类型引用</li>
     * </ol>
     * 总数量受 {@link #DEFAULT_ARTIFACT_REF_LIMIT} 约束。</p>
     */
    private LinkedHashSet<TaskCheckpoint.ArtifactRef> extractArtifactRefs(
            List<SessionTranscriptRepository.SessionTranscriptEntryRow> rows,
            List<String> memoryDocumentIds
    ) {
        LinkedHashSet<TaskCheckpoint.ArtifactRef> artifacts = new LinkedHashSet<>();
        // 来源 1 & 2：从 transcript 条目中提取
        for (SessionTranscriptRepository.SessionTranscriptEntryRow row : rows) {
            if (artifacts.size() >= DEFAULT_ARTIFACT_REF_LIMIT) {
                break;
            }
            Map<String, Object> payload = readPayload(row.payloadJson());
            TranscriptEntryType type = TranscriptEntryType.fromValue(row.entryType());
            // 来源 1：显式的产物引用条目
            if (type == TranscriptEntryType.ARTIFACT_REF) {
                artifacts.add(new TaskCheckpoint.ArtifactRef(
                        stringValue(payload.get("artifactType")),
                        stringValue(payload.get("title")),
                        stringValue(payload.get("summary")),
                        stringValue(payload.get("artifactId"))
                ));
                continue;
            }
            // 来源 2：其他条目中携带 artifactId 的间接引用
            String artifactId = stringValue(payload.get("artifactId"));
            if (artifactId != null) {
                artifacts.add(new TaskCheckpoint.ArtifactRef(
                        "artifact",
                        stringValue(payload.get("toolId")),
                        abbreviate(formatToolResultRow(payload), 120),
                        artifactId
                ));
            }
        }
        // 来源 3：压缩前刷出的记忆文档
        for (String memoryDocumentId : memoryDocumentIds) {
            if (artifacts.size() >= DEFAULT_ARTIFACT_REF_LIMIT) {
                break;
            }
            if (memoryDocumentId == null || memoryDocumentId.isBlank()) {
                continue;
            }
            artifacts.add(new TaskCheckpoint.ArtifactRef(
                    "memory_document",
                    "压缩前会话片段",
                    "如需完整细节，可回看预刷新的 transcript 片段文档。",
                    memoryDocumentId
            ));
        }
        return artifacts;
    }

    /**
     * 基于待办项和决策生成压缩后的恢复计划。
     * <p>恢复计划告诉 Agent 压缩之后应该从哪里继续：
     * <ul>
     *   <li>有待办项 → 将待办项转化为"继续跟进"指令</li>
     *   <li>无待办项但有决策 → 提示沿用最新决策继续执行</li>
     *   <li>有关联产物 → 提示优先回查产物/记忆引用获取细节</li>
     * </ul></p>
     */
    private LinkedHashSet<String> buildResumePlan(
            LinkedHashSet<String> openItems,
            LinkedHashSet<String> decisions,
            LinkedHashSet<TaskCheckpoint.ArtifactRef> artifacts
    ) {
        LinkedHashSet<String> resumePlan = new LinkedHashSet<>();
        // 将待办项转化为恢复行动指令，最多取 RESUME_PLAN_LIMIT 条
        openItems.stream()
                .limit(DEFAULT_RESUME_PLAN_LIMIT)
                .map(item -> item.startsWith("继续") ? item : "继续跟进: " + item)
                .forEach(resumePlan::add);
        // 无待办项时，引导 Agent 沿用最近的决策继续
        if (resumePlan.isEmpty() && !decisions.isEmpty()) {
            String latestDecision = decisions.stream().reduce((first, second) -> second).orElse(null);
            resumePlan.add("沿用已确认决策继续执行: " + latestDecision);
        }
        // 提示 Agent 按需回查产物，避免重放整段历史
        if (!artifacts.isEmpty()) {
            resumePlan.add("需要细节时优先回看关联产物或记忆引用，而不是重放整段历史。");
        }
        return resumePlan;
    }

    /**
     * 构建领域状态快照，记录压缩源的元信息。
     * <p>包含：条目数量、起止 ID、最后条目类型、最后轮次 ID、
     * 涉及的工具 ID 列表（去重后取前 5 个）。
     * 这些信息帮助 Agent 了解被压缩掉的对话范围和复杂度。</p>
     */
    private Map<String, Object> buildDomainState(List<SessionTranscriptRepository.SessionTranscriptEntryRow> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> domainState = new LinkedHashMap<>();
        SessionTranscriptRepository.SessionTranscriptEntryRow first = rows.getFirst();
        SessionTranscriptRepository.SessionTranscriptEntryRow last = rows.getLast();
        // 记录压缩范围的起止边界
        domainState.put("sourceEntryCount", rows.size());
        domainState.put("sourceStartEntryId", first.id());
        domainState.put("sourceEndEntryId", last.id());
        domainState.put("lastEntryType", last.entryType());
        if (last.turnId() != null && !last.turnId().isBlank()) {
            domainState.put("lastTurnId", last.turnId());
        }
        // 收集压缩范围内使用过的工具 ID，帮助 Agent 快速了解涉及了哪些能力
        List<String> toolIds = rows.stream()
                .map(row -> stringValue(readPayload(row.payloadJson()).get("toolId")))
                .filter(Objects::nonNull)
                .distinct()
                .limit(5)
                .toList();
        if (!toolIds.isEmpty()) {
            domainState.put("toolIds", toolIds);
        }
        return Map.copyOf(domainState);
    }

    /** 从第一条用户消息推断会话目标，截取前 160 字符。 */
    @Nullable
    private String inferGoal(List<SessionTranscriptRepository.SessionTranscriptEntryRow> rows) {
        for (SessionTranscriptRepository.SessionTranscriptEntryRow row : rows) {
            if (TranscriptEntryType.fromValue(row.entryType()) != TranscriptEntryType.USER_MESSAGE) {
                continue;
            }
            String content = stringValue(readPayload(row.payloadJson()).get("content"));
            if (content == null || content.isBlank()) {
                continue;
            }
            return abbreviate(normalizeWhitespace(content), 160);
        }
        return null;
    }

    /**
     * 根据最后一条条目的类型推断当前会话阶段标签。
     * <p>阶段标签反映压缩时对话停在了哪个环节，
     * 帮助 Agent 恢复时理解当前处于对话周期的哪个位置。</p>
     */
    @Nullable
    private String inferPhase(List<SessionTranscriptRepository.SessionTranscriptEntryRow> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        TranscriptEntryType lastType = TranscriptEntryType.fromValue(rows.getLast().entryType());
        return switch (lastType) {
            case USER_MESSAGE -> "user_input";
            case ASSISTANT_MESSAGE -> "assistant_response";
            case TOOL_CALL -> "tool_call";
            case TOOL_RESULT -> "tool_result";
            case ARTIFACT_REF -> "artifact_sync";
            default -> "conversation";
        };
    }

    /** 按中文句号、分号和换行符切分文本为句子列表。 */
    private List<String> splitSentences(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String[] parts = normalizeWhitespace(text).split("[。；;\\n]");
        List<String> sentences = new ArrayList<>();
        for (String part : parts) {
            String sentence = part.trim();
            if (!sentence.isEmpty()) {
                sentences.add(sentence);
            }
        }
        return List.copyOf(sentences);
    }

    /** 检查文本是否包含任意一个关键词。 */
    private boolean containsAny(String text, String... keywords) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
