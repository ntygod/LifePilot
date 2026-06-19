package com.lifepilot.memory.store.scope;

import org.springframework.lang.Nullable;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 记忆读取过滤条件。
 *
 * @author zsg
 * @since 2026-03-27
 */
public record MemoryReadFilter(
        Set<String> spaceIds,
        Set<MemoryScope> scopes
) {

    public MemoryReadFilter {
        spaceIds = Set.copyOf(normalizeSpaceIds(spaceIds));
        scopes = scopes != null ? Set.copyOf(scopes) : Set.of();
    }

    public static MemoryReadFilter all() {
        return new MemoryReadFilter(Set.of(), Set.of());
    }

    public static MemoryReadFilter userMemory() {
        return new MemoryReadFilter(Set.of(), Set.of(MemoryScope.USER_PROFILE, MemoryScope.USER_FACT));
    }

    public static MemoryReadFilter userProfile() {
        return new MemoryReadFilter(Set.of(), Set.of(MemoryScope.USER_PROFILE));
    }

    public static MemoryReadFilter agentExperience() {
        return new MemoryReadFilter(Set.of(), Set.of(MemoryScope.AGENT_EXPERIENCE));
    }

    public boolean restrictsSpaces() {
        return !spaceIds.isEmpty();
    }

    public boolean restrictsScopes() {
        return !scopes.isEmpty();
    }

    public boolean isUnrestricted() {
        return !restrictsSpaces() && !restrictsScopes();
    }

    public static MemoryReadFilter of(@Nullable Collection<String> spaceIds,
                                      @Nullable Collection<MemoryScope> scopes) {
        return new MemoryReadFilter(
                normalizeSpaceIds(spaceIds),
                scopes != null ? new LinkedHashSet<>(scopes) : Set.of()
        );
    }

    /**
     * 构造项目上下文的读取过滤器。
     *
     * <p>语义（Plan 1 基础版本）：
     * <ul>
     *   <li>隔离项目：允许读取 [项目 space + 主账户 personal + 主账户 experience]</li>
     *   <li>不隔离项目：等同主账户读取（项目 space 不加入）</li>
     *   <li>主账户对话（projectSpaceId = null）：只读主账户 space</li>
     * </ul>
     *
     * <p><b>注</b>：Plan 1 先做 space-level 合并，不做 key-level override；
     * L3 用户偏好 / L4 程序记忆的"项目级覆盖主账户同键"留给后续 plan。</p>
     *
     * @param projectSpaceId    项目 MemorySpace id（主账户对话时为 null）
     * @param personalSpaceId   主账户 personal MemorySpace id
     * @param experienceSpaceId 主账户 experience MemorySpace id
     * @param isolated          当前项目是否 ISOLATED
     */
    public static MemoryReadFilter buildForProject(
            @Nullable String projectSpaceId,
            String personalSpaceId,
            String experienceSpaceId,
            boolean isolated) {
        Set<String> spaces = new LinkedHashSet<>();
        if (projectSpaceId != null && isolated) {
            spaces.add(projectSpaceId);
        }
        spaces.add(personalSpaceId);
        spaces.add(experienceSpaceId);
        return new MemoryReadFilter(spaces, Set.of());
    }

    /**
     * 构造项目上下文 + scope 过滤器的组合。
     *
     * <p>等价于在 {@link #buildForProject(String, String, String, boolean)} 基础上再限定 scopes。
     * 当 userProfile / userMemory / agentExperience 路径依赖 scope 约束时使用。</p>
     *
     * @param projectSpaceId    项目 MemorySpace id（主账户对话时为 null）
     * @param personalSpaceId   主账户 personal MemorySpace id
     * @param experienceSpaceId 主账户 experience MemorySpace id
     * @param isolated          当前项目是否 ISOLATED
     * @param scopes            需要限定的 scope 集合；为 null 时等同 {@code Set.of()}（不限定 scope）
     */
    public static MemoryReadFilter buildForProject(
            @Nullable String projectSpaceId,
            String personalSpaceId,
            String experienceSpaceId,
            boolean isolated,
            @Nullable Set<MemoryScope> scopes) {
        MemoryReadFilter base = buildForProject(projectSpaceId, personalSpaceId, experienceSpaceId, isolated);
        return new MemoryReadFilter(base.spaceIds(), scopes != null ? scopes : Set.of());
    }

    private static Set<String> normalizeSpaceIds(@Nullable Collection<String> spaceIds) {
        if (spaceIds == null || spaceIds.isEmpty()) {
            return Set.of();
        }
        return spaceIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }
}
