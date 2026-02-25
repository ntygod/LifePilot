package com.lifepilot.skill.registry;

import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.event.SkillRegistryEvent;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skill 注册中心 — 线程安全，支持运行时并发注册和注销。
 *
 * <p>使用 {@link ConcurrentHashMap} 存储 {@link SkillDefinition}，
 * 注册前通过 {@link SkillDefinitionValidator} 校验合法性，
 * 注册/注销/更新时通过 {@link ApplicationEventPublisher} 发布事件，
 * 语义搜索委托给 {@link SkillSearchIndex}。</p>
 *
 * @author zsg
 * @since 2026-07-28
 */
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    /** 默认语义搜索返回的最大结果数（从配置读取）。 */
    private final int defaultSearchTopK;

    private final ConcurrentHashMap<String, SkillDefinition> skills = new ConcurrentHashMap<>();
    private final SkillDefinitionValidator validator;
    private final SkillSearchIndex searchIndex;
    private final ApplicationEventPublisher eventPublisher;

    public SkillRegistry(SkillDefinitionValidator validator,
                         SkillSearchIndex searchIndex,
                         ApplicationEventPublisher eventPublisher,
                         SkillConfigProperties skillConfig) {
        this.validator = validator;
        this.searchIndex = searchIndex;
        this.eventPublisher = eventPublisher;
        this.defaultSearchTopK = skillConfig.getSearch().getDefaultTopK();
    }

    /**
     * 注册 Skill，校验失败或覆盖 BUILTIN 时拒绝。
     *
     * <p>注册逻辑：
     * <ol>
     *   <li>通过 {@link SkillDefinitionValidator} 校验，失败则拒绝</li>
     *   <li>若已存在同 ID 的 BUILTIN 来源 Skill，拒绝覆盖</li>
     *   <li>若已存在同 ID 的非 BUILTIN 来源 Skill，允许更新并发布 SkillUpdated 事件</li>
     *   <li>新注册发布 SkillRegistered 事件</li>
     *   <li>同时在 {@link SkillSearchIndex} 中建立索引</li>
     * </ol>
     *
     * @param definition 待注册的 Skill 定义
     * @return true 注册成功，false 注册被拒绝
     */
    public boolean register(SkillDefinition definition) {
        // 1. 校验
        var result = validator.validate(definition);
        if (!result.valid()) {
            log.warn("Skill 注册被拒绝，校验失败: skillId={}, errors={}", definition.id(), result.errors());
            return false;
        }

        // 2. 检查是否已存在
        SkillDefinition existing = skills.get(definition.id());
        if (existing != null) {
            // BUILTIN 来源不允许被覆盖
            if (existing.source() instanceof SkillSource.Builtin) {
                log.warn("Skill 注册被拒绝，不允许覆盖 BUILTIN 来源: skillId={}", definition.id());
                return false;
            }
            // 非 BUILTIN 来源允许更新
            skills.put(definition.id(), definition);
            searchIndex.index(definition);
            eventPublisher.publishEvent(new SkillRegistryEvent.SkillUpdated(existing, definition));
            log.info("Skill 已更新: skillId={}", definition.id());
            return true;
        }

        // 3. 新注册
        skills.put(definition.id(), definition);
        searchIndex.index(definition);
        eventPublisher.publishEvent(new SkillRegistryEvent.SkillRegistered(definition));
        log.info("Skill 已注册: skillId={}", definition.id());
        return true;
    }

    /**
     * 注销 Skill。
     *
     * @param skillId Skill ID
     * @return true 注销成功，false 未找到
     */
    public boolean unregister(String skillId) {
        SkillDefinition removed = skills.remove(skillId);
        if (removed == null) {
            log.debug("Skill 注销失败，未找到: skillId={}", skillId);
            return false;
        }
        searchIndex.remove(skillId);
        eventPublisher.publishEvent(new SkillRegistryEvent.SkillUnregistered(skillId));
        log.info("Skill 已注销: skillId={}", skillId);
        return true;
    }

    /**
     * 按 ID 查找 Skill。
     *
     * @param skillId Skill ID
     * @return 包含 Skill 定义的 Optional，未找到时返回空
     */
    public Optional<SkillDefinition> find(String skillId) {
        return Optional.ofNullable(skills.get(skillId));
    }

    /**
     * 语义搜索，委托给 {@link SkillSearchIndex}。
     *
     * <p>将搜索结果中的 skillId 映射回 {@link SkillDefinition}，
     * 过滤掉已被注销但索引尚未清理的条目。</p>
     *
     * @param query 查询文本
     * @return 按相似度降序排列的 Skill 列表
     */
    public List<SkillDefinition> search(String query) {
        var searchResults = searchIndex.search(query, defaultSearchTopK);
        return searchResults.stream()
                .map(r -> skills.get(r.skillId()))
                .filter(d -> d != null)
                .toList();
    }

    /**
     * 返回所有已注册 Skill 的 Discovery 摘要列表。
     *
     * @return 摘要字符串列表（不可变）
     */
    public List<String> listSummaries() {
        return List.copyOf(
                skills.values().stream()
                        .map(SkillDefinition::toDiscoverySummary)
                        .toList()
        );
    }

    /**
     * 按来源类型批量注销 Skill。
     *
     * @param sourceType 来源类型的 Class
     * @return 被注销的 Skill 数量
     */
    public int unregisterBySource(Class<? extends SkillSource> sourceType) {
        // 收集匹配的 skillId
        var toRemove = skills.entrySet().stream()
                .filter(e -> sourceType.isInstance(e.getValue().source()))
                .map(e -> e.getKey())
                .toList();

        // 逐个注销（触发事件和索引清理）
        int count = 0;
        for (String skillId : toRemove) {
            if (unregister(skillId)) {
                count++;
            }
        }
        log.info("按来源类型批量注销完成: sourceType={}, count={}", sourceType.getSimpleName(), count);
        return count;
    }
}
