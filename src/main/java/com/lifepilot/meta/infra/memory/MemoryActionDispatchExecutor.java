package com.lifepilot.meta.infra.memory;

import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.scope.MemoryReadFilter;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.dispatch.ActionDispatchExecutor;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;
import jakarta.annotation.Nullable;

/**
 * 记忆工具 action 路由执行器。
 *
 * <p>统一承接 search / recall / create / update / delete / tag / query-at-time / search-experience。
 * 具体逻辑仍委托给 MemoryToolProvider 的现有实现方法。</p>
 *
 * @author zsg
 * @since 2026-03-31
 */
public class MemoryActionDispatchExecutor extends ActionDispatchExecutor {

    public MemoryActionDispatchExecutor(MemoryToolProvider provider,
                                        HybridRetriever hybridRetriever,
                                        SemanticMemory semanticMemory,
                                        @Nullable EpisodicMemory episodicMemory,
                                        @Nullable SessionKnowledgeBaseRepository sessionKbRepo,
                                        @Nullable MemoryProperties memoryProperties) {
        register("search",
                RiskLevel.LOW,
                ToolExecutionSemantics.generic(ToolSchedulingMode.PARALLEL_SAFE),
                provider::executeSearch);
        if (episodicMemory != null) {
            register("recall",
                    RiskLevel.LOW,
                    ToolExecutionSemantics.generic(ToolSchedulingMode.PARALLEL_SAFE),
                    provider::executeRecall);
        }
        register("create",
                RiskLevel.LOW,
                ToolExecutionSemantics.of(
                        PermissionActionType.WRITE_MEMORY,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.exactValues("entityNames", false, "name")
                ),
                provider::executeCreate);
        register("update",
                RiskLevel.LOW,
                ToolExecutionSemantics.of(
                        PermissionActionType.WRITE_MEMORY,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.exactValues("entityIds", "entityId")
                ),
                provider::executeUpdate);
        register("delete",
                RiskLevel.MEDIUM,
                ToolExecutionSemantics.of(
                        PermissionActionType.WRITE_MEMORY,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.exactValues("entityIds", "entityId")
                ),
                provider::executeDelete);
        register("tag",
                RiskLevel.LOW,
                ToolExecutionSemantics.of(
                        PermissionActionType.WRITE_MEMORY,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.exactValues("entityIds", "sourceEntityId", "targetEntityId")
                ),
                provider::executeTag);
        register("query-at-time",
                RiskLevel.LOW,
                ToolExecutionSemantics.generic(ToolSchedulingMode.PARALLEL_SAFE),
                provider::executeQueryAtTime);
        register("search-experience",
                RiskLevel.LOW,
                ToolExecutionSemantics.generic(ToolSchedulingMode.PARALLEL_SAFE),
                provider::executeSearchExperience);
    }
}
