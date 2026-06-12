package com.lifepilot.memory.governance.policy;

import com.lifepilot.memory.store.scope.ChatTurnMemorySnapshot;
import com.lifepilot.memory.store.scope.MemoryOriginType;
import com.lifepilot.memory.store.scope.MemoryReadFilter;
import com.lifepilot.memory.store.scope.MemoryRealityType;
import com.lifepilot.memory.store.scope.MemoryScope;
import com.lifepilot.memory.store.scope.MemoryWriteContext;
import com.lifepilot.project.context.ProjectContext;
import org.springframework.lang.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 记忆访问策略（数据治理层）。
 *
 * <p>本类是记忆模块读写边界的唯一策略层：负责根据项目上下文/轮次快照生成
 * read filter、write context 与可写范围。调用方不得自行拼装 spaceIds/scopes，避免
 * 单向隔离语义在不同入口发生漂移。</p>
 *
 * <p>设计原则：</p>
 * <ul>
 *   <li>读取可继承：ISOLATED 项目允许读取项目 space + 主账户 personal + experience。</li>
 *   <li>写入强约束：ISOLATED 项目写入只能落在项目 space；主账户/SHARED 写入默认空间。</li>
 *   <li>domain learning 必须有明确的 domain write space；否则 fail-closed。</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-05-05
 */
public class MemoryAccessPolicy {

    /**
     * 构造项目上下文读取过滤器。
     *
     * <p>仅做空间合并与 scope 限定；overlay 遮蔽由检索/查询层在 SQL 中实现。</p>
     */
    public MemoryReadFilter buildProjectReadFilter(@Nullable ProjectContext ctx, Set<MemoryScope> scopes) {
        return MemoryReadFilter.fromProjectContextOrFallback(
                ctx != null,
                ctx != null ? ctx.projectSpaceId() : null,
                ctx != null ? ctx.personalSpaceId() : null,
                ctx != null ? ctx.experienceSpaceId() : null,
                ctx != null && ctx.isolated(),
                scopes
        );
    }

    /**
     * 构造显式写操作的可写实体范围过滤器。
     *
     * <p>ISOLATED：仅项目 space；主账户/SHARED：personal + experience。</p>
     */
    public MemoryReadFilter buildWritableEntityFilter(@Nullable ProjectContext ctx) {
        if (ctx == null) {
            return MemoryReadFilter.all();
        }
        if (ctx.isolated()) {
            return MemoryReadFilter.of(List.of(ctx.projectSpaceId()), Set.of());
        }
        return MemoryReadFilter.of(List.of(ctx.personalSpaceId(), ctx.experienceSpaceId()), Set.of());
    }

    /**
     * 基于 ProjectContext 构造工具/接口写入上下文。
     *
     * <p>ISOLATED 项目：spaceId=projectSpaceId；主账户/SHARED：spaceId=null（由
     * SemanticMemory 按 entity type 推断默认空间）。</p>
     */
    public MemoryWriteContext buildProjectWriteContext(@Nullable ProjectContext ctx,
                                                       @Nullable String sessionId,
                                                       @Nullable String turnId,
                                                       @Nullable String entryId,
                                                       @Nullable String sourceReference) {
        return buildProjectWriteContext(
                ctx,
                sessionId,
                turnId,
                entryId,
                sourceReference,
                MemoryOriginType.TOOL,
                MemoryRealityType.UNKNOWN);
    }

    /**
     * 基于 ProjectContext 构造指定来源类型的写入上下文。
     *
     * <p>用于 Web 手动编辑等非工具入口复用同一项目写入边界，同时保留正确
     * provenance origin。</p>
     */
    public MemoryWriteContext buildProjectWriteContext(@Nullable ProjectContext ctx,
                                                       @Nullable String sessionId,
                                                       @Nullable String turnId,
                                                       @Nullable String entryId,
                                                       @Nullable String sourceReference,
                                                       MemoryOriginType originType,
                                                       MemoryRealityType realityType) {
        String spaceId = (ctx != null && ctx.isolated()) ? ctx.projectSpaceId() : null;
        return new MemoryWriteContext(
                spaceId,
                null,
                originType,
                realityType,
                sourceReference != null ? sourceReference : sessionId,
                sessionId,
                sessionId,
                turnId,
                entryId,
                null,
                null
        );
    }

    /**
     * 基于轮次快照解析自动学习写入上下文（RealtimeExtractor）。
     *
     * <p>缺快照或快照禁用时返回 null，调用方应 fail-closed。</p>
     */
    @Nullable
    public MemoryWriteContext resolveAutoLearningWriteContext(@Nullable ChatTurnMemorySnapshot snapshot,
                                                             String sessionId,
                                                             @Nullable String turnId) {
        if (snapshot == null) {
            return null;
        }
        if (snapshot.personalLearningEnabled()) {
            // projectSpaceId 非空代表 ISOLATED 对话归属；为空则交由 SemanticMemory 推断默认空间
            return new MemoryWriteContext(
                    snapshot.projectSpaceId(),
                    null,
                    MemoryOriginType.CHAT,
                    MemoryRealityType.UNKNOWN,
                    sessionId,
                    sessionId,
                    sessionId,
                    turnId,
                    null,
                    null,
                    null
            );
        }
        if (snapshot.domainLearningEnabled()
                && snapshot.domainWriteSpaceId() != null
                && !snapshot.domainWriteSpaceId().isBlank()) {
            return new MemoryWriteContext(
                    snapshot.domainWriteSpaceId(),
                    MemoryScope.DOMAIN_MEMORY,
                    MemoryOriginType.CHAT,
                    MemoryRealityType.UNKNOWN,
                    sessionId,
                    sessionId,
                    sessionId,
                    turnId,
                    null,
                    null,
                    null
            );
        }
        return null;
    }

    /**
     * 读取摘要时的继承读取空间组合：targetSpace + 主账户 personal + experience。
     *
     * <p>当前快照已持久化 personal/experience space id；若缺失则回退为目标 space 自身。</p>
     */
    public MemoryReadFilter buildSummaryReadFilter(MemoryWriteContext writeContext,
                                                   @Nullable ChatTurnMemorySnapshot snapshot) {
        if (writeContext.memoryScope() != null) {
            return MemoryReadFilter.of(
                    writeContext.spaceId() != null ? List.of(writeContext.spaceId()) : List.of(),
                    Set.of(writeContext.memoryScope())
            );
        }
        if (writeContext.spaceId() == null) {
            return MemoryReadFilter.userMemory();
        }
        Set<String> spaces = new LinkedHashSet<>();
        spaces.add(writeContext.spaceId());
        if (snapshot != null) {
            if (snapshot.personalSpaceId() != null) {
                spaces.add(snapshot.personalSpaceId());
            }
            if (snapshot.experienceSpaceId() != null) {
                spaces.add(snapshot.experienceSpaceId());
            }
        }
        return MemoryReadFilter.of(spaces, Set.of());
    }

    /**
     * 构造 ChatTurnMemorySnapshot 中固化的 readSpaceIds。
     *
     * <p>快照是后续自动学习的治理凭证，因此这里也由策略层统一维护读取空间顺序：
     * 项目 space 优先，其次 personal / experience，最后 domain read spaces。</p>
     */
    public List<String> buildSnapshotReadSpaceIds(@Nullable String projectSpaceId,
                                                  String personalSpaceId,
                                                  String experienceSpaceId,
                                                  List<String> domainReadSpaceIds) {
        Set<String> spaces = new LinkedHashSet<>();
        if (projectSpaceId != null && !projectSpaceId.isBlank()) {
            spaces.add(projectSpaceId);
        }
        spaces.add(personalSpaceId);
        spaces.add(experienceSpaceId);
        if (domainReadSpaceIds != null) {
            spaces.addAll(domainReadSpaceIds);
        }
        return List.copyOf(spaces);
    }
}
