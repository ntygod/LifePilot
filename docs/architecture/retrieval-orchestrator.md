# 统一检索编排层

> Spec: `.kiro/specs/retrieval-orchestrator/`
> 关联 Gap: #[[file:docs/planned/memory-and-proactive-evolution-gaps.md]] §1 M-P1-4
> 上位规划: #[[file:docs/architecture/memory-system.md]] §7 Phase H
> 数据流: #[[file:docs/architecture/memory-data-flow.md]] §0.14

## 1. 定位

把 HybridRetriever / L3 EXPERIENCE 检索 / 知识库检索统一到 `RetrievalOrchestrator` 入口，返回统一的 `EvidenceBundle`。本 spec 作为**上层可选接口**，不替换现有工具；真正 Agent tool 化留给后续 spec。

## 2. 组件

```
RetrievalOrchestrator.retrieve(query, intent, topK)
    │
    ├─ QueryPlanner.plan(query, intent) → List<SourceAdapter>
    │       ├─ FACT       → hybrid + knowledge-base
    │       ├─ EXPERIENCE → experience + hybrid
    │       └─ GENERAL    → 三路全走
    │
    ├─ for each adapter (isAvailable=true):
    │       adapter.retrieve(query, perSourceTopK) → List<EvidenceItem>
    │
    ├─ 合并 items → sort by score desc → limit(topK)
    │
    └─ return EvidenceBundle{items, sources, latencyMs, strategy}
```

### SourceAdapter (sealed)
- `HybridRetrievalSource` → 包装 `HybridRetriever`
- `ExperienceRetrievalSource` → `SemanticMemory.findCurrentByType(EXPERIENCE)` + 文本匹配
- `KnowledgeBaseSource` → 占位（isAvailable=false）

## 3. 配置

```yaml
lifepilot:
  memory:
    retrieval-orchestrator:
      enabled: false  # 默认关闭
      default-top-k: 10
      per-source-top-k: 5
```

## 4. 依赖验证

| 接口 | 位置 | 状态 |
|---|---|---|
| `HybridRetriever.retrieve` | memory.retrieval | ✅ |
| `SemanticMemory.findCurrentByType` | memory.semantic | ✅ |
| `RetrievalResult` | memory.retrieval | ✅ |

## 5. 跨模块接口变更

| 变更 | 模块 | 兼容性 |
|---|---|---|
| 新增 orchestrator 子包 | memory.retrieval | 新增 |
| `MemoryProperties.RetrievalOrchestrator` | memory.config | 向后兼容 |
| `MemoryAutoConfiguration` +5 Bean | memory.config | 向后兼容（conditional） |

## 6. 测试覆盖

- `QueryPlanner_单元测试`：5 场景
- `RetrievalOrchestrator_单元测试`：6 场景
- `EvidenceBundle_单元测试`：6 场景

## 7. 未来扩展

- Agent tool 化：新增 `memory.retrieve` 工具调用 orchestrator
- 并行检索：本 spec 顺序调用，未来可用虚拟线程并行
- 语义路由：基于 query 语义而非 intent 标签的动态路由
- KnowledgeBaseSource 真实实现
