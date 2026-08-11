package com.lifepilot.agent.learning.conflict;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.memory.governance.lifecycle.ChangeSource;
import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.retrieval.VectorSearchResult;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.semantic.ConflictVerdict;
import com.lifepilot.memory.store.entity.ConflictDetector;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 语义冲突裁决服务 — {@link SemanticMemory#upsertWithConflictDetection} 产出新/合并实体
 * 后，异步调 LLM 判定新记忆与候选旧记忆的关系（REPLACE / COEXIST / TIMELINE），并根据
 * 结果转换旧实体的生命周期状态。
 *
 * <p>与 {@link ConflictDetector} 的分工：
 * <ul>
 *     <li>{@code ConflictDetector} 回答"新旧是否是同一实体"（严格阈值 0.92，命中则走
 *         VersionMerger 版本化合并，不进入本裁决）；</li>
 *     <li>{@code ConflictResolutionService} 回答"不同实体间的语义关系"（较宽阈值 0.85，
 *         高相似但被 ConflictDetector 判为不同实体，或者是新建实体时附带的语义邻居，
 *         进入本裁决以保留/标记/替换/并列）。</li>
 * </ul>
 *
 * <p>异步策略：用 Virtual Thread Executor（与 {@code RealtimeExtractor} 同范式），
 * 不阻塞 upsert 主事务。可入队后的失败会先写入 {@link ConflictResolutionRepository#markFailed}，
 * 随后继续抛出，避免裁决链路假成功。
 *
 * @author zsg
 * @since 2026-04-23
 */
public class ConflictResolutionService {

    private static final Logger log = LoggerFactory.getLogger(ConflictResolutionService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 进入裁决的相似度阈值 —— 高于此阈值的候选才调 LLM 裁决。
     *
     * <p>选 0.85 的依据：既低于 {@code MemoryProperties.semanticMatchThreshold=0.92}
     * （即不会与 ConflictDetector "同一实体"判定争抢），也与
     * {@code MemoryProperties.Merge.similarityThreshold=0.85} 对齐，符合"高相似、
     * 非同一实体、值得 LLM 仲裁"的语义边界。</p>
     */
    private static final double SIMILARITY_THRESHOLD = 0.85d;

    /** LLM 裁决超时（秒）—— 与 Merge / Contrastive 等其他后台 LLM 调用保持一致。 */
    private static final Duration LLM_TIMEOUT = Duration.ofSeconds(30);

    /** 异步执行器 — 每个裁决任务一个 Virtual Thread，与主事务解耦。 */
    private static final java.util.concurrent.ExecutorService VIRTUAL_EXECUTOR =
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    private final GenerationRouter generationRouter;
    private final PromptRegistry promptRegistry;
    private final VectorSearcher vectorSearcher;
    private final ConflictResolutionRepository queueRepository;
    private final SemanticMemory semanticMemory;

    public ConflictResolutionService(GenerationRouter generationRouter,
                                     PromptRegistry promptRegistry,
                                     VectorSearcher vectorSearcher,
                                     ConflictResolutionRepository queueRepository,
                                     SemanticMemory semanticMemory) {
        this.generationRouter = Objects.requireNonNull(generationRouter, "generationRouter");
        this.promptRegistry = Objects.requireNonNull(promptRegistry, "promptRegistry");
        this.vectorSearcher = Objects.requireNonNull(vectorSearcher, "vectorSearcher");
        this.queueRepository = Objects.requireNonNull(queueRepository, "queueRepository");
        this.semanticMemory = Objects.requireNonNull(semanticMemory, "semanticMemory");
    }

    /**
     * 异步执行裁决 —— 提交到 Virtual Thread Executor，并返回可观察的执行结果。
     *
     * <p>调用方（{@code SemanticMemory.upsertWithConflictDetection}）负责筛选候选集
     * 并排除新实体自己；本方法再做一次相似度过滤（防止调用方传入低相似候选）。
     *
     * @param newEntity  刚 upsert 产出的新实体
     * @param candidates 候选旧实体列表（调用方已预筛选，不含 newEntity 自己）
     */
    public CompletableFuture<Void> resolveAsync(TemporalEntity newEntity, List<TemporalEntity> candidates) {
        validateRequest(newEntity, candidates);
        if (candidates.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> resolveSync(newEntity, candidates), VIRTUAL_EXECUTOR);
    }

    /**
     * 同步裁决逻辑（供测试调用 + 异步入口复用）。
     *
     * <p>流程：相似度过滤 → 入队 → 调 LLM → 解析 verdict → 应用状态转换 →
     * markResolved / markFailed。
     */
    void resolveSync(TemporalEntity newEntity, List<TemporalEntity> candidates) {
        validateRequest(newEntity, candidates);

        // 1. 高相似度过滤：只有 ≥ 阈值的候选才进入 LLM 裁决
        var highSimilar = filterHighSimilar(newEntity, candidates);
        if (highSimilar.isEmpty()) {
            log.debug("冲突裁决: 无高相似候选，跳过, newEntityId={}", newEntity.id());
            return;
        }

        // 2. 入队（PENDING）
        String queueId = queueRepository.enqueue(
                newEntity.id(),
                highSimilar.stream().map(TemporalEntity::id).toList());
        if (queueId == null || queueId.isBlank()) {
            throw new IllegalStateException("冲突裁决入队返回 queueId 不能为空");
        }

        // 3. 调 LLM 获取 verdict
        ConflictVerdict verdict;
        try {
            verdict = askLlm(newEntity, highSimilar);
            validateVerdictTarget(verdict, highSimilar);
        } catch (Exception ex) {
            log.warn("冲突裁决: LLM 调用失败, newEntityId={}, queueId={}, error={}",
                    newEntity.id(), queueId, ex.getMessage());
            throw markFailedAndWrap(
                    queueId,
                    "LLM 调用失败: " + ex.getMessage(),
                    "冲突裁决 LLM 调用失败",
                    ex);
        }

        // 4. 应用 verdict → 改旧实体状态（事件由 SemanticMemory 代发，本服务不自发）
        try {
            applyVerdict(newEntity, verdict);
            queueRepository.markResolved(queueId, verdict);
            log.debug("冲突裁决完成: newEntityId={}, verdict={}, targetId={}",
                    newEntity.id(), verdict.verdict(), verdict.targetId());
        } catch (Exception ex) {
            log.warn("冲突裁决: verdict 应用失败, newEntityId={}, verdict={}, error={}",
                    newEntity.id(), verdict, ex.getMessage());
            throw markFailedAndWrap(
                    queueId,
                    "verdict 应用失败: " + ex.getMessage(),
                    "冲突裁决 verdict 应用失败",
                    ex);
        }
    }

    private IllegalStateException markFailedAndWrap(String queueId,
                                                   String reason,
                                                   String message,
                                                   Exception cause) {
        var wrapped = new IllegalStateException(message, cause);
        try {
            queueRepository.markFailed(queueId, reason);
        } catch (Exception markFailedEx) {
            wrapped.addSuppressed(markFailedEx);
        }
        return wrapped;
    }

    private static void validateRequest(TemporalEntity newEntity, List<TemporalEntity> candidates) {
        Objects.requireNonNull(newEntity, "冲突裁决新实体不能为空");
        requireCanonicalId(newEntity.id(), "冲突裁决新实体 id");
        Objects.requireNonNull(newEntity.type(), "冲突裁决新实体 type 不能为空");
        requireNonBlank(newEntity.name(), "冲突裁决新实体 name");
        Objects.requireNonNull(candidates, "冲突裁决候选列表不能为空");
        for (TemporalEntity candidate : candidates) {
            Objects.requireNonNull(candidate, "冲突裁决候选实体不能为空");
            requireCanonicalId(candidate.id(), "冲突裁决候选实体 id");
            Objects.requireNonNull(candidate.type(), "冲突裁决候选实体 type 不能为空");
            requireNonBlank(candidate.name(), "冲突裁决候选实体 name");
        }
    }

    /**
     * 按相似度阈值过滤候选；相似度数据取自 VectorSearcher 基于 newEntity 文本的
     * top-K 检索结果（以 entityId 查表拿到 similarity）。
     *
     * <p>向量检索失败表示冲突裁决前置索引不可用，直接暴露给调用方/重试机制。
     */
    private List<TemporalEntity> filterHighSimilar(TemporalEntity newEntity, List<TemporalEntity> candidates) {
        Map<String, Float> simByEntityId = lookupSimilarities(newEntity);
        if (simByEntityId.isEmpty()) {
            return List.of();
        }
        return candidates.stream()
                .filter(c -> {
                    Float sim = simByEntityId.get(c.id());
                    return sim != null && sim >= SIMILARITY_THRESHOLD;
                })
                .toList();
    }

    /** 借助 VectorSearcher 取得 newEntity 文本对应的 top-K 相似 entityId → similarity 映射。 */
    private Map<String, Float> lookupSimilarities(TemporalEntity newEntity) {
        List<VectorSearchResult> results = vectorSearcher.searchEntities(
                newEntity.textRepresentation(), 10, 0.0f);
        if (results == null) {
            throw new IllegalStateException("冲突裁决相似度查询结果不能为空");
        }
        Map<String, Float> simByEntityId = new LinkedHashMap<>();
        for (VectorSearchResult result : results) {
            if (result == null) {
                throw new IllegalStateException("冲突裁决相似度查询结果不能包含 null 元素");
            }
            requireCanonicalId(result.entityId(), "冲突裁决向量候选 entityId");
            if (!Float.isFinite(result.similarity())
                    || result.similarity() < 0.0f
                    || result.similarity() > 1.0f) {
                throw new IllegalStateException("冲突裁决向量候选 similarity 必须在 [0,1] 范围内: "
                        + result.similarity());
            }
            // 排除新实体自己，防止新实体出现在自己的相似邻居里（upsertWithConflictDetection
            // 已更新过它自己的向量）
            if (!newEntity.id().equals(result.entityId())) {
                simByEntityId.putIfAbsent(result.entityId(), result.similarity());
            }
        }
        return simByEntityId;
    }

    /** 调 LLM 并解析结构化 verdict。 */
    private ConflictVerdict askLlm(TemporalEntity newEntity, List<TemporalEntity> candidates) {
        String prompt = promptRegistry.render("semantic/memory-conflict-resolution", Map.of(
                "newType", newEntity.type().name(),
                "newName", newEntity.name(),
                "newDescription", newEntity.description() != null ? newEntity.description() : "",
                "candidates", renderCandidates(candidates)
        ));
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalStateException("冲突裁决 prompt 渲染结果不能为空");
        }
        LlmResponse response = generationRouter.call(
                LlmScene.KNOWLEDGE_EXTRACTION,
                prompt,
                null,
                null,
                null,
                GenerationCapability.CHAT,
                LLM_TIMEOUT);
        if (response == null) {
            throw new IllegalStateException("冲突裁决 LLM 响应不能为空");
        }
        return parseVerdict(response.content());
    }

    /** 将候选实体渲染为 prompt 中的人类可读列表，每条带 id/type/name/description。 */
    private String renderCandidates(List<TemporalEntity> candidates) {
        var sb = new StringBuilder();
        for (var c : candidates) {
            sb.append("- id=").append(c.id())
              .append(" | type=").append(c.type().name())
              .append(" | name=").append(c.name());
            if (c.description() != null && !c.description().isBlank()) {
                sb.append(" | description=").append(c.description());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** 解析 LLM 返回的严格 JSON。 */
    private ConflictVerdict parseVerdict(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("LLM 返回空内容");
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(raw);
            if (!node.isObject()) {
                throw new IllegalStateException("LLM 响应顶层必须是 JSON 对象: " + raw);
            }
            String verdictStr = requiredTextField(node, "verdict", raw);
            ConflictVerdict.Kind kind;
            try {
                kind = ConflictVerdict.Kind.valueOf(verdictStr);
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException("非法 verdict 值: " + verdictStr, ex);
            }
            String targetId = parseTargetId(node.get("target_id"), raw);
            if ((kind == ConflictVerdict.Kind.REPLACE || kind == ConflictVerdict.Kind.TIMELINE)
                    && targetId == null) {
                throw new IllegalStateException("LLM 响应缺 target_id 字段: " + raw);
            }
            if (kind == ConflictVerdict.Kind.COEXIST && targetId != null) {
                throw new IllegalStateException("LLM 响应 COEXIST 的 target_id 必须为空: " + raw);
            }
            String rationale = requiredTextField(node, "rationale", raw);
            if (rationale.length() > 60) {
                throw new IllegalStateException("LLM 响应 rationale 超过 60 字: " + raw);
            }
            return new ConflictVerdict(kind, targetId, rationale);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("verdict JSON 解析失败: " + raw, ex);
        }
    }

    private static String requiredTextField(JsonNode node, String field, String raw) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException("LLM 响应缺 " + field + " 字段: " + raw);
        }
        String text = value.asText();
        if (!text.equals(text.trim())) {
            throw new IllegalStateException("LLM 响应 " + field + " 不能包含首尾空白: " + raw);
        }
        return text;
    }

    private static String parseTargetId(JsonNode targetNode, String raw) {
        if (targetNode == null || targetNode.isNull()) {
            return null;
        }
        if (!targetNode.isTextual()) {
            throw new IllegalStateException("LLM 响应 target_id 必须是字符串: " + raw);
        }
        String targetId = targetNode.asText();
        if (targetId.isEmpty()) {
            return null;
        }
        requireCanonicalId(targetId, "LLM 响应 target_id");
        return targetId;
    }

    private static void validateVerdictTarget(ConflictVerdict verdict, List<TemporalEntity> candidates) {
        Objects.requireNonNull(verdict, "冲突裁决 verdict 不能为空");
        Objects.requireNonNull(verdict.verdict(), "冲突裁决 verdict 类型不能为空");
        Set<String> allowedIds = candidates.stream()
                .map(TemporalEntity::id)
                .collect(java.util.stream.Collectors.toSet());
        if ((verdict.verdict() == ConflictVerdict.Kind.REPLACE
                || verdict.verdict() == ConflictVerdict.Kind.TIMELINE)
                && !allowedIds.contains(verdict.targetId())) {
            throw new IllegalStateException("冲突裁决 target_id 不在候选集中: " + verdict.targetId());
        }
    }

    /**
     * 应用 verdict 到旧实体状态：
     * <ul>
     *     <li>REPLACE / TIMELINE：老实体必须存在且处于 {@code ACTIVE}，否则失败；</li>
     *     <li>TIMELINE 先 {@code updateSucceededBy} 记录继承链，再
     *         {@code updateLifecycleState} 转 SUPERSEDED（两步同事务由 SemanticMemory 保证）；</li>
     *     <li>COEXIST 无状态变化。</li>
     * </ul>
     *
     * <p>所有状态转换走 {@link SemanticMemory#updateLifecycleState(String, LifecycleState, String, ChangeSource)}
     * 4 参版，由其自动发 {@code EntityLifecycleChanged} 事件（source={@code CONFLICT_RESOLVE}），
     * 本服务不自发事件。
     */
    private void applyVerdict(TemporalEntity newEntity, ConflictVerdict verdict) {
        switch (verdict.verdict()) {
            case REPLACE -> applyReplace(newEntity, verdict);
            case TIMELINE -> applyTimeline(newEntity, verdict);
            case COEXIST -> log.debug("冲突裁决: COEXIST 无状态变化, newEntityId={}, targetId={}",
                    newEntity.id(), verdict.targetId());
        }
    }

    private void applyReplace(TemporalEntity newEntity, ConflictVerdict verdict) {
        if (verdict.targetId() == null) {
            throw new IllegalStateException("冲突裁决 REPLACE 缺 target_id: " + newEntity.id());
        }
        var target = semanticMemory.findById(verdict.targetId());
        if (target == null || target.isEmpty() || target.get().lifecycleState() != LifecycleState.ACTIVE) {
            throw new IllegalStateException("冲突裁决 REPLACE target 不存在或非 ACTIVE: " + verdict.targetId());
        }
        semanticMemory.updateLifecycleState(
                verdict.targetId(),
                LifecycleState.SUPERSEDED,
                "replaced-by:" + newEntity.id(),
                ChangeSource.CONFLICT_RESOLVE);
    }

    private void applyTimeline(TemporalEntity newEntity, ConflictVerdict verdict) {
        if (verdict.targetId() == null) {
            throw new IllegalStateException("冲突裁决 TIMELINE 缺 target_id: " + newEntity.id());
        }
        var target = semanticMemory.findById(verdict.targetId());
        if (target == null || target.isEmpty() || target.get().lifecycleState() != LifecycleState.ACTIVE) {
            throw new IllegalStateException("冲突裁决 TIMELINE target 不存在或非 ACTIVE: " + verdict.targetId());
        }
        semanticMemory.updateSucceededBy(verdict.targetId(), newEntity.id());
        semanticMemory.updateLifecycleState(
                verdict.targetId(),
                LifecycleState.SUPERSEDED,
                "succeeded-by:" + newEntity.id(),
                ChangeSource.CONFLICT_RESOLVE);
    }

    private static void requireCanonicalId(String value, String label) {
        requireNonBlank(value, label);
        if (!value.equals(value.trim())) {
            throw new IllegalArgumentException(label + "不能包含首尾空白: " + value);
        }
    }

    private static void requireNonBlank(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
    }
}
