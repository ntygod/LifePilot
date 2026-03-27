package com.lifepilot.memory.scope;

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

    private static Set<String> normalizeSpaceIds(@Nullable Collection<String> spaceIds) {
        if (spaceIds == null || spaceIds.isEmpty()) {
            return Set.of();
        }
        return spaceIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }
}
