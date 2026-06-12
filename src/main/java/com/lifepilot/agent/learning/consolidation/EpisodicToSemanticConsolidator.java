package com.lifepilot.agent.learning.consolidation;

import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.memory.episodic.ConversationRecord;
import com.lifepilot.memory.store.episodic.EpisodicMemory;
import com.lifepilot.memory.episodic.MessageRecord;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.support.SqliteBusyRetry;
import com.lifepilot.memory.store.entity.TemporalEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 情景→语义巩固器 — 分析近期对话中的实体提及频率，高频实体提升重要度，长对话触发知识提取。
 *
 * <p>巩固流程：
 * <ol>
 *   <li>获取增量窗口内的对话（通过 memory_consolidation_log 记录上次巩固时间戳）</li>
 *   <li>统计已有 L3 实体在对话文本中的提及频率</li>
 *   <li>高频实体（≥ 阈值）提升 importanceScore（步长可配置，上限 1.0）</li>
 *   <li>长对话不再触发新增知识提取，避免把对话内容误当作文档知识写入长期记忆</li>
 *   <li>记录巩固日志到 memory_consolidation_log</li>
 * </ol>
 *
 * @author zsg
 * @since 2026-03-01
 */
public class EpisodicToSemanticConsolidator {

    private static final Logger log = LoggerFactory.getLogger(EpisodicToSemanticConsolidator.class);
    private static final String CONSOLIDATION_TYPE = "EPISODIC_TO_SEMANTIC";
    private final EpisodicMemory episodicMemory;
    private final SemanticMemory semanticMemory;
    private final JdbcTemplate jdbcTemplate;
    private final AgentLearningProperties properties;

    /**
     * 构造情景→语义巩固器。
     *
     * @param episodicMemory     L2 情景记忆
     * @param semanticMemory     L3 语义记忆
     * @param jdbcTemplate       JDBC 模板
     * @param properties         记忆配置
     */
    public EpisodicToSemanticConsolidator(EpisodicMemory episodicMemory,
                                          SemanticMemory semanticMemory,
                                          JdbcTemplate jdbcTemplate,
                                          AgentLearningProperties properties) {
        this.episodicMemory = episodicMemory;
        this.semanticMemory = semanticMemory;
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        log.info("EpisodicToSemanticConsolidator 初始化完成（知识提取管线按需获取）");
    }

    /**
     * 执行情景→语义巩固。
     *
     * @return 巩固统计结果
     */
    public ConsolidationStats consolidate() {
        long startMs = System.currentTimeMillis();
        var config = properties.getConsolidation();

        // 1. 获取增量窗口起始时间
        Instant windowStart = getLastConsolidationTime()
                .orElse(Instant.now().minus(Duration.ofDays(config.getLookbackDays())));

        // 2. 获取窗口内的对话
        var allRecent = episodicMemory.getRecent(1000);
        var conversations = allRecent.stream()
                .filter(c -> c.createdAt().isAfter(windowStart))
                .toList();

        if (conversations.isEmpty()) {
            long elapsed = System.currentTimeMillis() - startMs;
            log.info("语义巩固: 无新对话需要巩固, windowStart={}", windowStart);
            var stats = new ConsolidationStats(CONSOLIDATION_TYPE, 0, 0, 0, 0, 0, 0, elapsed);
            logConsolidation(stats);
            return stats;
        }

        log.info("语义巩固: 发现 {} 个新对话, windowStart={}", conversations.size(), windowStart);

        // 3. 获取所有当前 L3 实体
        var currentEntities = semanticMemory.findAllCurrent();

        // 4. 拼接所有对话文本，统计实体提及频率
        String allText = buildConversationText(conversations);
        Map<String, Integer> mentionCounts = countEntityMentions(allText, currentEntities);

        // 5. 高频实体提升 importanceScore
        int entitiesBoosted = boostHighFrequencyEntities(currentEntities, mentionCounts, config);

        // 6. 长对话不再触发新增知识提取，巩固阶段只强化已有实体
        int extractionsTriggered = 0;

        // 7. 记录巩固日志
        long elapsed = System.currentTimeMillis() - startMs;
        var stats = new ConsolidationStats(
                CONSOLIDATION_TYPE,
                conversations.size(),
                mentionCounts.size(),
                entitiesBoosted,
                extractionsTriggered,
                0, 0,
                elapsed);
        logConsolidation(stats);

        log.info("语义巩固完成: conversations={}, entities={}, boosted={}, extractions={}, elapsed={}ms",
                stats.conversationsAnalyzed(), stats.entitiesFound(),
                stats.entitiesBoosted(), stats.extractionsTriggered(), stats.elapsedMs());

        return stats;
    }

    /**
     * 拼接对话中所有消息的文本内容。
     */
    private String buildConversationText(List<ConversationRecord> conversations) {
        return conversations.stream()
                .flatMap(c -> c.messages().stream())
                .map(MessageRecord::content)
                .filter(Objects::nonNull)
                .collect(Collectors.joining(" "));
    }

    /**
     * 统计每个实体在文本中的提及次数。
     *
     * <p>纯英文名称使用 {@code \b} 词边界正则匹配，中文/混合名称使用子串匹配。
     * 短名称（长度 ≤ shortNameThreshold）且为纯英文时强制使用词边界匹配。</p>
     *
     * @param text     全部对话文本
     * @param entities 当前 L3 实体列表
     * @return 实体 ID → 提及次数映射（仅包含提及次数 > 0 的实体）
     */
    private Map<String, Integer> countEntityMentions(String text, List<TemporalEntity> entities) {
        Map<String, Integer> mentionCounts = new HashMap<>();
        String lowerText = text.toLowerCase();
        int shortNameThreshold = properties.getConsolidation().getShortNameThreshold();

        // 为需要正则匹配的实体缓存编译后的 Pattern
        Map<String, java.util.regex.Pattern> patternCache = new HashMap<>();

        for (var entity : entities) {
            String name = entity.name().toLowerCase();
            if (name.isEmpty()) continue;

            boolean latin = isLatinName(name);
            boolean forceWordBoundary = latin && name.length() <= shortNameThreshold;

            // 纯英文名称或短名称强制词边界匹配
            if (latin || forceWordBoundary) {
                var pattern = patternCache.computeIfAbsent(name, n -> {
                    try {
                        return java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(n) + "\\b");
                    } catch (Exception e) {
                        log.warn("词边界正则编译失败，降级为子串匹配: name={}, error={}", n, e.getMessage());
                        return null;
                    }
                });
                int count = (pattern != null) ? countWithPattern(lowerText, pattern) : countSubstring(lowerText, name);
                if (count > 0) mentionCounts.put(entity.id(), count);
            } else {
                int count = countSubstring(lowerText, name);
                if (count > 0) mentionCounts.put(entity.id(), count);
            }
        }
        return mentionCounts;
    }

    /**
     * 提升高频实体的 importanceScore。
     *
     * @return 被提升的实体数量
     */
    private int boostHighFrequencyEntities(List<TemporalEntity> entities,
                                            Map<String, Integer> mentionCounts,
                                            AgentLearningProperties.Consolidation config) {
        int boosted = 0;
        for (var entity : entities) {
            int mentions = mentionCounts.getOrDefault(entity.id(), 0);
            if (mentions >= config.getHighFrequencyThreshold()) {
                float boost = Math.min(
                        config.getImportanceBoostStep() * mentions,
                        config.getImportanceBoostMax());
                float newImportance = Math.min(1.0f, entity.importanceScore() + boost);

                if (newImportance > entity.importanceScore()) {
                    // 仅更新分数，不创建新版本 — 两个原因：
                    // 1. 避免与 RealtimeExtractor 并发写入时的 UNIQUE(entity_id) WHERE is_current=1 约束冲突
                    // 2. importance boost 是统计调整，不涉及实体内容变更，不需要版本化
                    // 代价：绕过了 SemanticMemory 的 writeCallback 和审计事件，importance 变化历史不可追溯
                    SqliteBusyRetry.run(() -> jdbcTemplate.update(
                            "UPDATE memory_entity_versions SET importance_score = ?, updated_at = ? WHERE entity_id = ? AND is_current = 1",
                            newImportance, Instant.now().toString(), entity.id()));
                    boosted++;
                    log.debug("语义巩固: 实体重要度提升, name={}, oldScore={}, newScore={}, mentions={}",
                            entity.name(), entity.importanceScore(), newImportance, mentions);
                }
            }
        }
        return boosted;
    }

    /**
     * 获取上次巩固的时间戳。
     *
     * @return 上次巩固时间，无记录时返回 Optional.empty()
     */
    private Optional<Instant> getLastConsolidationTime() {
        var results = jdbcTemplate.queryForList(
                "SELECT created_at FROM memory_consolidation_log WHERE consolidation_type = ? ORDER BY created_at DESC LIMIT 1",
                String.class, CONSOLIDATION_TYPE);
        if (results.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(results.getFirst()));
        } catch (Exception e) {
            log.warn("语义巩固: 解析上次巩固时间失败, raw={}", results.getFirst());
            return Optional.empty();
        }
    }

    /**
     * 记录巩固日志到 memory_consolidation_log 表。
     */
    private void logConsolidation(ConsolidationStats stats) {
        jdbcTemplate.update(
                "INSERT INTO memory_consolidation_log (id, consolidation_type, conversations_analyzed, entities_found, entities_boosted, extractions_triggered, templates_created, templates_updated, elapsed_ms, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(),
                stats.consolidationType(),
                stats.conversationsAnalyzed(),
                stats.entitiesFound(),
                stats.entitiesBoosted(),
                stats.extractionsTriggered(),
                stats.templatesCreated(),
                stats.templatesUpdated(),
                stats.elapsedMs(),
                Instant.now().toString());
    }

    /**
     * 统计 name 在 text 中出现的次数。
     * 纯英文名称使用词边界正则匹配，中文/混合名称使用子串匹配。
     * 正则编译失败时降级为子串匹配。
     */
    /**
     * 判断名称是否为纯英文（仅含 ASCII 字母、数字、空格、连字符）。
     */
    private boolean isLatinName(String name) {
        return name.chars().allMatch(c -> (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9') || c == ' ' || c == '-');
    }

    /**
     * 使用词边界正则匹配统计出现次数，编译失败时降级为子串匹配。
     */
    private int countWithWordBoundary(String text, String name) {
        try {
            var pattern = java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(name) + "\\b");
            return countWithPattern(text, pattern);
        } catch (Exception e) {
            log.warn("词边界正则编译失败，降级为子串匹配: name={}, error={}", name, e.getMessage());
            return countSubstring(text, name);
        }
    }

    /**
     * 使用已编译的 Pattern 统计匹配次数。
     */
    private int countWithPattern(String text, java.util.regex.Pattern pattern) {
        var matcher = pattern.matcher(text);
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    /**
     * 子串匹配统计出现次数（原始逻辑，用于中文/混合名称）。
     */
    private int countSubstring(String text, String name) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(name, idx)) != -1) {
            count++;
            idx += name.length();
        }
        return count;
    }
}
