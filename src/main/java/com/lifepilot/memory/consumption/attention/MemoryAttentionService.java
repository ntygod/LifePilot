package com.lifepilot.memory.consumption.attention;

import com.lifepilot.memory.consumption.config.MemoryConsumptionProperties;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.memory.store.scope.MemoryReadFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 记忆注意力服务（memory-proactive-foundation）—— 把记忆从被动检索升级为主动浮现
 * "现在该关注什么、为什么"的统一出口。
 *
 * <p>聚合四类注意力信号：</p>
 * <ul>
 *   <li>EXPIRING：临近到期（GOAL/EVENT/PROJECT 的 expires_at 落入窗口）</li>
 *   <li>NEGLECTED：停滞高价值（importance 高但长期未关注）</li>
 *   <li>EVOLVING：演进活跃（近期持续多版本更新）</li>
 *   <li>CONNECTION：图联想连接机会（经桥实体两跳可达但无直接边）</li>
 * </ul>
 *
 * <p>全程只读、无副作用；单类计算异常被隔离，不影响其余产出。</p>
 *
 * @author zsg
 * @since 2026-06-07
 */
public class MemoryAttentionService {

    private static final Logger log = LoggerFactory.getLogger(MemoryAttentionService.class);

    /** 临近到期关注的实体类型（带 deadline 语义）。 */
    private static final Set<EntityType> EXPIRING_TYPES =
            EnumSet.of(EntityType.GOAL, EntityType.EVENT, EntityType.PROJECT);
    /** 截止日期（dueAt）关注的实体类型。 */
    private static final Set<EntityType> DUE_TYPES =
            EnumSet.of(EntityType.GOAL, EntityType.EVENT, EntityType.PROJECT);
    /** 停滞高价值关注的实体类型。 */
    private static final Set<EntityType> NEGLECTED_TYPES =
            EnumSet.of(EntityType.GOAL, EntityType.PROJECT, EntityType.TOPIC, EntityType.SKILL);
    /** 演进活跃关注的实体类型。 */
    private static final Set<EntityType> EVOLVING_TYPES =
            EnumSet.of(EntityType.GOAL, EntityType.HABIT, EntityType.TOPIC, EntityType.PROJECT);
    /** 作为连接机会种子的实体数量上限。 */
    private static final int MAX_CONNECTION_SEEDS = 5;

    private final SemanticMemory semanticMemory;
    private final GraphReasoner graphReasoner;
    private final MemoryConsumptionProperties properties;
    private final Clock clock;

    public MemoryAttentionService(SemanticMemory semanticMemory,
                                  GraphReasoner graphReasoner,
                                  MemoryConsumptionProperties properties,
                                  Clock clock) {
        this.semanticMemory = semanticMemory;
        this.graphReasoner = graphReasoner;
        this.properties = properties;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    /** 注意力信号类别。 */
    public enum AttentionKind { EXPIRING, DUE_SOON, NEGLECTED, EVOLVING, CONNECTION }

    /**
     * 一条注意力项。
     *
     * @param entityId   关注实体 id（CONNECTION 为目标实体 id）
     * @param name       实体名称
     * @param entityType 实体类型名（CONNECTION 可能为空）
     * @param kind       信号类别
     * @param score      综合分数（降序排列）
     * @param reason     人类可读原因
     * @param dueAt      EXPIRING 的到期时间
     * @param daysIdle   NEGLECTED 的空闲天数
     * @param pathLabels CONNECTION 的联想路径标签
     */
    public record AttentionItem(String entityId, String name, @Nullable String entityType,
                                AttentionKind kind, float score, String reason,
                                @Nullable Instant dueAt, @Nullable Long daysIdle,
                                @Nullable List<String> pathLabels) {}

    /**
     * 计算注意力清单。
     *
     * @param filter 读取过滤（空间/scope）；null 表示不限
     * @param topN   返回上限；≤0 时用配置默认
     * @return 按 score 降序的注意力项
     */
    public List<AttentionItem> computeAttention(@Nullable MemoryReadFilter filter, int topN) {
        var cfg = properties.getAttention();
        if (!cfg.isEnabled()) {
            return List.of();
        }
        Instant now = Instant.now(clock);
        int limit = topN > 0 ? topN : cfg.getTopN();
        List<AttentionItem> items = new ArrayList<>();

        int expiring = 0, neglected = 0, evolving = 0, connection = 0;

        // EXPIRING
        if (cfg.isExpiringEnabled()) {
            try {
                Instant until = now.plus(Duration.ofDays(cfg.getExpiringWindowDays()));
                var list = semanticMemory.findApproachingExpiry(until, EXPIRING_TYPES, filter);
                for (var e : capped(list, cfg.getMaxPerKind())) {
                    items.add(toExpiring(e, now, cfg));
                    expiring++;
                }
            } catch (Exception ex) {
                log.warn("记忆注意力: EXPIRING 计算失败，跳过, error={}", ex.getMessage());
            }
        }

        // DUE_SOON —— 硬截止日期临近（含逾期），dueAt 存于 properties，与 expires_at 解耦
        int dueSoon = 0;
        if (cfg.isDueSoonEnabled()) {
            try {
                Instant until = now.plus(Duration.ofDays(cfg.getDueSoonWindowDays()));
                for (var e : semanticMemory.findWithDueDate(DUE_TYPES, filter)) {
                    if (dueSoon >= cfg.getMaxPerKind()) break;
                    Instant due = parseDueAt(e.properties().get("dueAt"));
                    if (due == null) continue;
                    if (due.isAfter(until)) continue;  // 窗口外（含未来太远）；逾期(due<now)仍纳入
                    items.add(toDueSoon(e, due, now, cfg));
                    dueSoon++;
                }
            } catch (Exception ex) {
                log.warn("记忆注意力: DUE_SOON 计算失败，跳过, error={}", ex.getMessage());
            }
        }

        // NEGLECTED
        if (cfg.isNeglectedEnabled()) {
            try {
                Instant idleBefore = now.minus(Duration.ofDays(cfg.getNeglectDays()));
                var list = semanticMemory.findNeglected(
                        NEGLECTED_TYPES, idleBefore, cfg.getNeglectMinImportance(), filter);
                for (var e : capped(list, cfg.getMaxPerKind())) {
                    items.add(toNeglected(e, now, cfg));
                    neglected++;
                }
            } catch (Exception ex) {
                log.warn("记忆注意力: NEGLECTED 计算失败，跳过, error={}", ex.getMessage());
            }
        }

        // EVOLVING
        if (cfg.isEvolvingEnabled()) {
            try {
                Instant windowStart = now.minus(Duration.ofDays(cfg.getEvolvingWindowDays()));
                int count = 0;
                for (var type : EVOLVING_TYPES) {
                    if (count >= cfg.getMaxPerKind()) break;
                    for (var e : semanticMemory.findCurrentByType(type, filter)) {
                        if (count >= cfg.getMaxPerKind()) break;
                        if (e.version() >= cfg.getEvolvingMinVersions()
                                && e.updatedAt() != null && e.updatedAt().isAfter(windowStart)) {
                            items.add(toEvolving(e, cfg));
                            count++;
                            evolving++;
                        }
                    }
                }
            } catch (Exception ex) {
                log.warn("记忆注意力: EVOLVING 计算失败，跳过, error={}", ex.getMessage());
            }
        }

        // CONNECTION —— 以当前已关注实体为种子做图联想
        if (cfg.isConnectionEnabled()) {
            try {
                var seeds = collectConnectionSeeds(items);
                Map<String, AttentionItem> byTarget = new LinkedHashMap<>();
                int seedCount = 0;
                for (var seed : seeds.entrySet()) {
                    if (seedCount++ >= MAX_CONNECTION_SEEDS) break;
                    var opps = graphReasoner.connectionOpportunities(seed.getKey(), filter);
                    for (var opp : opps) {
                        if (connection >= cfg.getMaxPerKind()) break;
                        var item = toConnection(seed.getValue(), opp, cfg);
                        var existing = byTarget.get(opp.toId());
                        if (existing == null || item.score() > existing.score()) {
                            if (existing == null) connection++;
                            byTarget.put(opp.toId(), item);
                        }
                    }
                }
                items.addAll(byTarget.values());
            } catch (Exception ex) {
                log.warn("记忆注意力: CONNECTION 计算失败，跳过, error={}", ex.getMessage());
            }
        }

        items.sort(Comparator.comparing(AttentionItem::score).reversed());
        var result = items.size() > limit ? items.subList(0, limit) : items;
        log.debug("记忆注意力: expiring={}, dueSoon={}, neglected={}, evolving={}, connection={}, returned={}",
                expiring, dueSoon, neglected, evolving, connection, result.size());
        return List.copyOf(result);
    }

    // ── 评分与转换 ──

    private AttentionItem toExpiring(TemporalEntity e, Instant now, MemoryConsumptionProperties.Attention cfg) {
        long daysUntil = Math.max(0, Duration.between(now, e.expiresAt()).toDays());
        float windowDays = Math.max(1, cfg.getExpiringWindowDays());
        float remaining = (float) Duration.between(now, e.expiresAt()).toHours() / (windowDays * 24f);
        float urgency = clamp01(1f - remaining);
        float score = cfg.getWeightExpiring() * (0.5f + 0.5f * urgency) * (0.4f + 0.6f * e.importanceScore());
        String reason = "「%s」将在 %d 天后到期".formatted(e.name(), daysUntil);
        return new AttentionItem(e.id(), e.name(), e.type().name(), AttentionKind.EXPIRING,
                clamp01(score), reason, e.expiresAt(), null, null);
    }

    private AttentionItem toDueSoon(TemporalEntity e, Instant due, Instant now,
                                    MemoryConsumptionProperties.Attention cfg) {
        long daysUntil = Duration.between(now, due).toDays();  // 逾期为负
        float urgency;
        String reason;
        if (due.isBefore(now)) {
            urgency = 1.0f;  // 已逾期，最高紧迫度
            reason = "「%s」已逾期 %d 天未完成".formatted(e.name(), Math.max(0, -daysUntil));
        } else {
            float windowDays = Math.max(1, cfg.getDueSoonWindowDays());
            float remaining = (float) Duration.between(now, due).toHours() / (windowDays * 24f);
            urgency = clamp01(1f - remaining);
            reason = "「%s」将在 %d 天后到期".formatted(e.name(), Math.max(0, daysUntil));
        }
        float score = cfg.getWeightDueSoon() * (0.5f + 0.5f * urgency) * (0.4f + 0.6f * e.importanceScore());
        return new AttentionItem(e.id(), e.name(), e.type().name(), AttentionKind.DUE_SOON,
                clamp01(score), reason, due, daysUntil, null);
    }

    /** 解析 dueAt：容忍完整时间戳与纯日期；非法返回 null。 */
    @Nullable
    private static Instant parseDueAt(@Nullable Object raw) {
        if (raw == null) return null;
        String s = raw.toString().trim();
        if (s.isEmpty()) return null;
        try {
            return Instant.parse(s);
        } catch (Exception ignore) {
            try {
                return java.time.LocalDate.parse(s).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
            } catch (Exception ignore2) {
                return null;
            }
        }
    }

    private AttentionItem toNeglected(TemporalEntity e, Instant now, MemoryConsumptionProperties.Attention cfg) {
        Instant since = e.lastAccessedAt() != null ? e.lastAccessedAt() : e.createdAt();
        long daysIdle = Math.max(0, Duration.between(since, now).toDays());
        float idleFactor = clamp01((float) daysIdle / Math.max(1, cfg.getNeglectDays()));
        float score = cfg.getWeightNeglected() * e.importanceScore() * (0.5f + 0.5f * idleFactor);
        String reason = "「%s」重要但已 %d 天未关注".formatted(e.name(), daysIdle);
        return new AttentionItem(e.id(), e.name(), e.type().name(), AttentionKind.NEGLECTED,
                clamp01(score), reason, null, daysIdle, null);
    }

    private AttentionItem toEvolving(TemporalEntity e, MemoryConsumptionProperties.Attention cfg) {
        float versionFactor = clamp01((float) e.version() / Math.max(1, cfg.getEvolvingMinVersions() * 2));
        float score = cfg.getWeightEvolving() * (0.4f + 0.6f * e.importanceScore()) * (0.5f + 0.5f * versionFactor);
        String reason = "「%s」近期持续演进（%d 个版本）".formatted(e.name(), e.version());
        return new AttentionItem(e.id(), e.name(), e.type().name(), AttentionKind.EVOLVING,
                clamp01(score), reason, null, null, null);
    }

    private AttentionItem toConnection(AttentionItem seed, GraphReasoner.ConnectionOpportunity opp,
                                       MemoryConsumptionProperties.Attention cfg) {
        float score = cfg.getWeightConnection() * clamp01(opp.score());
        String reason = "可经「%s」建立「%s」与「%s」的关联".formatted(
                opp.bridgeName(), seed.name(), opp.toName());
        var labels = List.of(
                seed.name() + " --" + opp.firstRelation() + "--> " + opp.bridgeName(),
                opp.bridgeName() + " --" + opp.secondRelation() + "--> " + opp.toName());
        return new AttentionItem(opp.toId(), opp.toName(), null, AttentionKind.CONNECTION,
                clamp01(score), reason, null, null, labels);
    }

    /** 连接机会种子：已关注实体（EXPIRING/NEGLECTED/EVOLVING）去重，保留名称。 */
    private Map<String, AttentionItem> collectConnectionSeeds(List<AttentionItem> items) {
        Map<String, AttentionItem> seeds = new LinkedHashMap<>();
        for (var item : items) {
            if (item.kind() != AttentionKind.CONNECTION) {
                seeds.putIfAbsent(item.entityId(), item);
            }
        }
        return seeds;
    }

    private static <T> List<T> capped(List<T> list, int max) {
        return list.size() > max ? list.subList(0, max) : list;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
