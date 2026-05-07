package com.lifepilot.memory.hot;

import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.experience.SubtaskReflector;
import com.lifepilot.memory.procedural.PreferenceRule;
import com.lifepilot.memory.procedural.ProceduralMemory;
import com.lifepilot.memory.quality.MemoryQualityPolicy;
import com.lifepilot.memory.quality.MemoryTrustLevel;
import com.lifepilot.memory.scope.MemoryReadFilter;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import com.lifepilot.observability.redactor.DataRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * L3.5 热记忆摘要服务。
 *
 * <p>当前实现按读取视图实时构建摘要快照：读取 L3 可消费实体，并把带可消费 L3 源实体
 * 的高置信 L4 偏好规则合并进用户画像分区。它不是新的事实主库，后续如需落表，
 * 也应只作为可重建投影。</p>
 *
 * @author zsg
 * @since 2026-05-05
 */
public class HotMemoryDigestService {

    private static final Logger log = LoggerFactory.getLogger(HotMemoryDigestService.class);
    private static final String CONSOLIDATED_PROFILE_NAME = "__consolidated_profile";
    private static final String USER_PREFERENCE_CATEGORY = "user-preference";

    private final SemanticMemory semanticMemory;
    private final MemoryProperties properties;
    private final ProceduralMemory proceduralMemory;
    private final Clock clock;
    private final DataRedactor dataRedactor;

    public HotMemoryDigestService(SemanticMemory semanticMemory,
                                  MemoryProperties properties,
                                  @Nullable ProceduralMemory proceduralMemory,
                                  @Nullable DataRedactor dataRedactor,
                                  Clock clock) {
        this.semanticMemory = semanticMemory;
        this.properties = properties;
        this.proceduralMemory = proceduralMemory;
        this.dataRedactor = dataRedactor != null ? dataRedactor : new DataRedactor();
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    /**
     * 构建当前读取视图的热记忆摘要。
     *
     * @param filter  L3 读取过滤器，必须由治理层或调用方按项目上下文生成
     * @param viewKey 读取视图 key
     * @return 热摘要快照
     */
    public HotMemoryDigest build(MemoryReadFilter filter, String viewKey) {
        var config = properties.getHotDigest();
        Instant now = Instant.now(clock);
        String resolvedViewKey = viewKey == null || viewKey.isBlank() ? "default" : viewKey;
        if (!config.isEnabled()) {
            return empty(resolvedViewKey, now, "disabled");
        }

        List<TemporalEntity> consumable = semanticMemory.findAllCurrent(filter).stream()
                .filter(MemoryQualityPolicy::isPromptConsumable)
                .filter(this::isHotDigestEligible)
                .toList();
        if (consumable.isEmpty()) {
            return empty(resolvedViewKey, now, "empty");
        }

        List<HotPreferenceRule> hotPreferences = selectHotPreferenceRules(consumable);
        String sourceRevision = sourceRevision(consumable, hotPreferences);
        List<HotMemoryDigest.HotMemorySection> sections = new ArrayList<>();
        addIfPresent(sections, buildUserProfileSection(consumable, hotPreferences, config));
        addIfPresent(sections, buildSection(
                HotMemorySectionKind.PROJECT_MEMORY,
                "L3.5 热记忆 - 项目约定:",
                selectProjectMemory(consumable),
                config.getProjectMemoryTokenBudget(),
                config.getProjectMemoryMaxEntries()));
        addIfPresent(sections, buildSection(
                HotMemorySectionKind.EXPERIENCE,
                "L3.5 热记忆 - 高价值经验:",
                selectExperience(consumable),
                config.getExperienceTokenBudget(),
                config.getExperienceMaxEntries()));
        addIfPresent(sections, buildSection(
                HotMemorySectionKind.FACTS,
                "L3.5 热记忆 - 常用事实:",
                selectFacts(consumable),
                config.getFactsTokenBudget(),
                config.getFactsMaxEntries()));

        return new HotMemoryDigest(
                "hot-" + sourceRevision.substring(0, Math.min(12, sourceRevision.length())),
                resolvedViewKey,
                now,
                sourceRevision,
                sections);
    }

    private HotMemoryDigest empty(String viewKey, Instant now, String reason) {
        return new HotMemoryDigest("hot-" + reason, viewKey, now, reason, List.of());
    }

    private void addIfPresent(List<HotMemoryDigest.HotMemorySection> sections,
                              @Nullable HotMemoryDigest.HotMemorySection section) {
        if (section != null && !section.content().isBlank()) {
            sections.add(section);
        }
    }

    @Nullable
    private HotMemoryDigest.HotMemorySection buildUserProfileSection(
            List<TemporalEntity> entities,
            List<HotPreferenceRule> hotPreferences,
            MemoryProperties.HotDigest config) {
        if (config.getUserProfileTokenBudget() <= 0 || config.getUserProfileMaxEntries() <= 0) {
            return null;
        }
        var consolidated = entities.stream()
                .filter(entity -> entity.type() == EntityType.CUSTOM)
                .filter(entity -> CONSOLIDATED_PROFILE_NAME.equals(entity.name()))
                .max(Comparator.comparingDouble(this::rank));
        if (consolidated.isPresent()) {
            TemporalEntity profile = consolidated.get();
            Set<String> profileSourceIds = new LinkedHashSet<>(sourceEntityIds(profile));
            List<HotPreferenceRule> incrementalHotPreferences = hotPreferences.stream()
                    .filter(preference -> !profileSourceIds.contains(preference.source().id())
                            || isNewerThanProfile(preference.source(), profile))
                    .toList();
            return buildUserProfileSectionFromEntries(
                    List.of(profile),
                    incrementalHotPreferences,
                    config);
        }
        Set<String> l4PreferenceKeys = hotPreferences.stream()
                .map(rule -> rule.rule().key())
                .collect(java.util.stream.Collectors.toSet());
        Set<String> l4SourceIds = hotPreferences.stream()
                .map(rule -> rule.source().id())
                .filter(id -> id != null && !id.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        List<TemporalEntity> fragments = new ArrayList<>(entities.stream()
                .filter(entity -> entity.type() == EntityType.PREFERENCE
                        || entity.type() == EntityType.HABIT
                        || entity.type() == EntityType.GOAL)
                .filter(entity -> !(entity.type() == EntityType.PREFERENCE
                        && (l4PreferenceKeys.contains(entity.name()) || l4SourceIds.contains(entity.id()))))
                .sorted(Comparator.comparingDouble(this::rank).reversed())
                .toList());
        return buildUserProfileSectionFromEntries(fragments, hotPreferences, config);
    }

    private boolean isNewerThanProfile(TemporalEntity source, TemporalEntity profile) {
        if (source.updatedAt() == null || profile.updatedAt() == null) {
            return false;
        }
        return source.updatedAt().isAfter(profile.updatedAt());
    }

    private List<TemporalEntity> selectProjectMemory(List<TemporalEntity> entities) {
        return entities.stream()
                .filter(entity -> entity.type() == EntityType.PROJECT)
                .toList();
    }

    private List<TemporalEntity> selectExperience(List<TemporalEntity> entities) {
        return entities.stream()
                .filter(entity -> entity.type() == EntityType.EXPERIENCE)
                .filter(this::notToolLevelGranularity)
                .toList();
    }

    private List<TemporalEntity> selectFacts(List<TemporalEntity> entities) {
        return entities.stream()
                .filter(entity -> entity.type() != EntityType.PREFERENCE)
                .filter(entity -> entity.type() != EntityType.HABIT)
                .filter(entity -> entity.type() != EntityType.GOAL)
                .filter(entity -> entity.type() != EntityType.EXPERIENCE)
                .filter(entity -> entity.type() != EntityType.PROJECT)
                .filter(entity -> !(entity.type() == EntityType.CUSTOM
                        && CONSOLIDATED_PROFILE_NAME.equals(entity.name())))
                .toList();
    }

    @Nullable
    private HotMemoryDigest.HotMemorySection buildUserProfileSectionFromEntries(
            List<TemporalEntity> l3Entries,
            List<HotPreferenceRule> hotPreferences,
            MemoryProperties.HotDigest config) {
        StringBuilder content = new StringBuilder("L3.5 热记忆 - 用户画像:\n");
        Set<String> sourceIds = new LinkedHashSet<>();
        int usedTokens = estimateTokens(content.toString());
        int added = 0;

        for (TemporalEntity entity : l3Entries) {
            if (added >= config.getUserProfileMaxEntries()) {
                break;
            }
            String entry = renderEntry(entity);
            if (entry == null || entry.isBlank()) {
                continue;
            }
            int entryTokens = estimateTokens(entry);
            if (usedTokens + entryTokens > config.getUserProfileTokenBudget()) {
                break;
            }
            content.append(entry);
            usedTokens += entryTokens;
            added++;
            sourceIds.addAll(sourceEntityIds(entity));
        }

        for (HotPreferenceRule preference : hotPreferences) {
            if (added >= config.getUserProfileMaxEntries()) {
                break;
            }
            String entry = renderPreferenceEntry(preference);
            if (entry == null || entry.isBlank()) {
                continue;
            }
            int entryTokens = estimateTokens(entry);
            if (usedTokens + entryTokens > config.getUserProfileTokenBudget()) {
                break;
            }
            content.append(entry);
            usedTokens += entryTokens;
            added++;
            sourceIds.add(preference.source().id());
        }

        if (added == 0) {
            return null;
        }
        return new HotMemoryDigest.HotMemorySection(
                HotMemorySectionKind.USER_PROFILE,
                content.toString().strip(),
                List.copyOf(sourceIds),
                config.getUserProfileTokenBudget());
    }

    @Nullable
    private HotMemoryDigest.HotMemorySection buildSection(HotMemorySectionKind kind,
                                                          String title,
                                                          List<TemporalEntity> candidates,
                                                          int tokenBudget,
                                                          int maxEntries) {
        if (candidates == null || candidates.isEmpty() || tokenBudget <= 0 || maxEntries <= 0) {
            return null;
        }
        StringBuilder content = new StringBuilder(title).append('\n');
        Set<String> sourceIds = new LinkedHashSet<>();
        int usedTokens = estimateTokens(content.toString());
        int added = 0;

        for (TemporalEntity entity : candidates.stream()
                .sorted(Comparator.comparingDouble(this::rank).reversed())
                .toList()) {
            if (added >= maxEntries) {
                break;
            }
            String entry = renderEntry(entity);
            if (entry == null || entry.isBlank()) {
                continue;
            }
            int entryTokens = estimateTokens(entry);
            if (usedTokens + entryTokens > tokenBudget) {
                break;
            }
            content.append(entry);
            usedTokens += entryTokens;
            added++;
            sourceIds.addAll(sourceEntityIds(entity));
        }

        if (added == 0) {
            return null;
        }
        return new HotMemoryDigest.HotMemorySection(
                kind,
                content.toString().strip(),
                List.copyOf(sourceIds),
                tokenBudget);
    }

    private List<HotPreferenceRule> selectHotPreferenceRules(List<TemporalEntity> consumableEntities) {
        if (proceduralMemory == null) {
            return List.of();
        }
        Map<String, TemporalEntity> sourceById = new HashMap<>();
        for (TemporalEntity entity : consumableEntities) {
            if (entity.id() != null && !entity.id().isBlank()) {
                sourceById.put(entity.id(), entity);
            }
        }
        try {
            return proceduralMemory.getPreferences(USER_PREFERENCE_CATEGORY).stream()
                    .filter(PreferenceRule::isHighConfidence)
                    .map(rule -> {
                        String sourceId = rule.sourceEntityId();
                        TemporalEntity source = sourceId == null ? null : sourceById.get(sourceId);
                        return source == null ? null : new HotPreferenceRule(rule, source);
                    })
                    .filter(java.util.Objects::nonNull)
                    .sorted(Comparator
                            .comparingDouble((HotPreferenceRule preference) -> preference.rule().confidence())
                            .reversed()
                            .thenComparing(preference -> preference.rule().key()))
                    .toList();
        } catch (Exception e) {
            log.warn("热记忆摘要加载 L4 偏好规则失败，跳过 L4 注入: error={}", e.getMessage());
            return List.of();
        }
    }

    @Nullable
    private String renderEntry(TemporalEntity entity) {
        String label = CONSOLIDATED_PROFILE_NAME.equals(entity.name())
                ? textOrEmpty(entity.description())
                : entity.name() + (entity.description() != null && !entity.description().isBlank()
                        ? ": " + entity.description()
                        : "");
        if (label.isBlank()) {
            return null;
        }
        String raw = "- [" + entity.type().label() + "|" + entity.trustLevel().name()
                + "/" + entity.evidenceKind().name() + "] " + label + "\n";
        try {
            String redacted = dataRedactor.redact(raw);
            return redacted == null || redacted.isBlank() ? null : redacted;
        } catch (Exception e) {
            log.warn("热记忆摘要脱敏失败，跳过条目: entityId={}, error={}", entity.id(), e.getMessage());
            return null;
        }
    }

    @Nullable
    private String renderPreferenceEntry(HotPreferenceRule preference) {
        PreferenceRule rule = preference.rule();
        TemporalEntity source = preference.source();
        String label = rule.key() + " = " + rule.value();
        if (label.isBlank()) {
            return null;
        }
        String raw = "- [L4偏好|" + source.trustLevel().name()
                + "/" + source.evidenceKind().name()
                + "|confidence=" + rule.confidence() + "] " + label + "\n";
        try {
            String redacted = dataRedactor.redact(raw);
            return redacted == null || redacted.isBlank() ? null : redacted;
        } catch (Exception e) {
            log.warn("热记忆摘要 L4 偏好脱敏失败，跳过规则: ruleId={}, error={}",
                    rule.ruleId(), e.getMessage());
            return null;
        }
    }

    private boolean isHotDigestEligible(TemporalEntity entity) {
        return entity != null
                && entity.isCurrent()
                && entity.validTo() == null
                && entity.trustLevel() != MemoryTrustLevel.INFERRED;
    }

    private boolean notToolLevelGranularity(TemporalEntity entity) {
        Object granularity = entity.properties().get("granularity");
        return granularity == null || !SubtaskReflector.TOOL_LEVEL.equals(granularity.toString());
    }

    private List<String> sourceEntityIds(TemporalEntity entity) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (entity.id() != null && !entity.id().isBlank()) {
            ids.add(entity.id());
        }
        if (entity.derivationSources() != null) {
            entity.derivationSources().stream()
                    .filter(id -> id != null && !id.isBlank())
                    .forEach(ids::add);
        }
        return List.copyOf(ids);
    }

    private double rank(TemporalEntity entity) {
        double trust = entity.trustScore();
        double importance = entity.importanceScore();
        double recency = 1.0d;
        if (entity.updatedAt() != null) {
            long days = Math.max(0, Duration.between(entity.updatedAt(), Instant.now(clock)).toDays());
            recency = Math.max(0.0d, 1.0d - days / 30.0d);
        }
        return trust * 0.45d + importance * 0.35d + recency * 0.20d;
    }

    private String sourceRevision(List<TemporalEntity> entities, List<HotPreferenceRule> hotPreferences) {
        String entityRevision = entities.stream()
                .sorted(Comparator.comparing(TemporalEntity::id))
                .map(entity -> String.join("|",
                        textOrEmpty(entity.id()),
                        entity.type().name(),
                        String.valueOf(entity.version()),
                        entity.lifecycleState().name(),
                        textOrEmpty(entity.updatedAt() != null ? entity.updatedAt().toString() : null)))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("empty");
        String preferenceRevision = hotPreferences.stream()
                .sorted(Comparator.comparing(preference -> preference.rule().ruleId()))
                .map(preference -> String.join("|",
                        textOrEmpty(preference.rule().ruleId()),
                        textOrEmpty(preference.rule().key()),
                        textOrEmpty(preference.rule().value()),
                        String.valueOf(preference.rule().confidence()),
                        textOrEmpty(preference.rule().updatedAt() != null
                                ? preference.rule().updatedAt().toString()
                                : null),
                        textOrEmpty(preference.source().id())))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("empty-l4");
        String raw = entityRevision + "\n--L4--\n" + preferenceRevision;
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    private int estimateTokens(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        long cjkChars = text.chars()
                .filter(ch -> Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN)
                .count();
        long otherChars = text.length() - cjkChars;
        return Math.max(1, (int) (cjkChars + otherChars / 4));
    }

    private String textOrEmpty(@Nullable String text) {
        return text == null ? "" : text;
    }

    private record HotPreferenceRule(PreferenceRule rule, TemporalEntity source) {}
}
