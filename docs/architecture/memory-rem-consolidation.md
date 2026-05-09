# 记忆 REM 式联想巩固

> Spec: `.kiro/specs/memory-rem-consolidation/`
> 关联 Gap: #[[file:docs/planned/memory-and-proactive-evolution-gaps.md]] §1 M-P1-3
> 上位文档: #[[file:docs/architecture/memory-system.md]] / #[[file:docs/architecture/memory-advanced.md]]

## 1. 定位

在 `EpisodicToSemanticConsolidator` 已覆盖的"NREM 压缩/归档"（实体频次提升、归档、偏好重新评估）之上，补一条"REM 联想"通路：
- 巩固时段选 top-K 高 importance seed 实体
- 通过 HybridRetriever 找每个 seed 的 top-N 邻居
- LLM 判断 seed 与邻居之间是否存在**未被显式记录**的潜在语义关系
- 合格候选写文件审计，**不直改 L3 relations 主库**（保持候选/审计分离）

## 2. 核心设计决策

| 决策 | 原因 |
|---|---|
| 候选落**文件**而非 `memory_extraction_candidates` 表 | 既有表强绑定 AudnDecision 语义；文件方案零 schema 变更、零迁移风险 |
| 只生产候选、不直写 relations 主库 | 未来 spec 加"关联应用器"做人工/自动审阅再落主库，避免 LLM 错判污染 L3 图 |
| seed 选择限于 GOAL/TOPIC/PROJECT | 这三类通常概念密度高，联想价值高；其他类型按需后续扩展 |
| 硬编码 prompt 模板 | 本地个人助手场景，不需要 PromptRegistry 资源化 |
| 去重窗口 24h | 短期内重复生成同关系仅保留首次，避免 LLM 幻觉反复写入 |

## 3. 组件关系

```
ConsolidationPipeline.consolidate()
  │ 既有 1-6 步（语义/程序/偏好/经验/画像/模板提升）
  │
  └─ 7. REM 联想（新增）
        │
        ├─ AssociationCandidateGenerator.generate()
        │      │
        │      ├─ selectSeeds(): semanticMemory.findCurrentByType(GOAL/TOPIC/PROJECT)
        │      │                 .sortBy(importance desc).limit(10)
        │      │
        │      ├─ for each seed:
        │      │    fetchNeighbors(): hybridRetriever.retrieve(seed, topK=5)
        │      │    callLlmForAssociations(): GenerationRouter.call(CHAT) → JSON
        │      │    parseResponse(): JSON → List<AssociationCandidate>
        │      │
        │      └─ return all candidates
        │
        └─ AssociationConsolidator.consolidate(candidates)
              │
              ├─ 过滤 confidence < 0.65
              ├─ 内存去重（source:target:type，24h 窗口）
              │
              └─ AssociationCandidateStore.save(date, kept)
                    │
                    └─ target/cache/memory-rem-associations/{yyyy-MM-dd}.json
```

## 4. 数据格式

`AssociationCandidate` JSON 示例：

```json
[
  {
    "sourceEntityId": "neighbor-id",
    "targetEntityId": "seed-id",
    "relationType": "SUPPORTS",
    "confidence": 0.85,
    "evidence": "书籍是学习 Rust 的核心资源",
    "seedEntityId": "seed-id",
    "generatedAt": "2026-05-09T10:00:00Z"
  }
]
```

AssociationType 枚举：RELATED_TO / CAUSES / SIMILAR_TO / SUPPORTS / CONTRADICTS。

## 5. 配置

```yaml
lifepilot:
  memory:
    rem:
      enabled: false  # 默认关闭
      seed-limit: 10
      neighbor-limit: 5
      seed-types: [GOAL, TOPIC, PROJECT]
      min-confidence: 0.65
      llm-timeout-seconds: 20
      deduplication-window-hours: 24
```

## 6. 依赖核对

| 接口 | 源码位置 | 验证状态 |
|---|---|---|
| `SemanticMemory.findCurrentByType(EntityType)` | memory.semantic | ✅ |
| `HybridRetriever.retrieve(query, topK, weights)` | memory.retrieval | ✅ 返回 `List<RetrievalResult>` |
| `GenerationRouter.call(scene, prompt, ..., skipCache)` | generation.router | ✅ 使用 8 参重载 + skipCache=true |
| `TemporalEntity.importanceScore / description` | memory.semantic | ✅ |
| `MemoryProperties.Rem` 内部类 | memory.config | 本 spec 新增 |

## 7. 跨模块接口变更

| 变更 | 模块 | 兼容性 |
|---|---|---|
| `ConsolidationPipeline` 构造函数 +2 可空参数 | memory.consolidation | 保留旧 8 参构造（overload） |
| `MemoryProperties.Rem` 内部类 | memory.config | 向后兼容 |
| `MemoryAutoConfiguration` +3 Bean | memory.config | 向后兼容 |

## 8. 风险与回退

- LLM 输出格式不稳定 → `parseResponse` 宽容解析：提取第一个 JSON 数组、逐条宽容、整体失败返回空
- 调用成本 → 默认 10 seed × 5 neighbor = 50 次检索 + 10 次 LLM；`enabled=false` 完全不执行
- LLM 幻觉 → 候选仅写文件；人工/未来 spec 审阅后再落 L3 主库
- 关闭开关 → 行为等价于 spec 前

## 9. 验证策略

- `AssociationCandidate_单元测试`：record 构造、clamp、null 校验
- `AssociationCandidateStore_单元测试`（@TempDir）：save/load 往返、空输入、多次 save 合并
- `AssociationConsolidator_单元测试`：置信度过滤、去重窗口、空输入
- `AssociationCandidateGenerator_单元测试`：开关控制、seed 排序、LLM JSON 解析、异常降级
- 全量 `mvn test`：既有 3947+ 测试无回归

## 10. 前沿参考

| 来源 | 结论 | 应用 |
|---|---|---|
| SCM Sleep-Consolidated Memory | 类比睡眠 REM 阶段的联想记忆重组 | `AssociationCandidateGenerator` 的 seed+neighbor+LLM 流程 |
| A-MEM（NeurIPS 2025，arXiv:2502.12110） | 新事实应更新邻居 description 和关系 | 本 spec 补"邻居关系生成"一侧；"邻居 description 更新"由 memory-staleness spec 的 NeighborRefreshService 承担 |
| 本地个人助手定位 | 候选必须可审计、可回滚 | 文件持久化 + 不直改主库 |

## 11. 未来扩展

- **关联应用器**：读取 {date}.json，人工/自动审阅后真正写入 L3 relations 主库
- **Seed 类型扩展**：HABIT / PREFERENCE / EXPERIENCE 等
- **LLM 蒸馏**：把强模型输出的高 reward 联想作为小模型微调样本
- **Idle-Driven 触发**：屏幕锁/CLI 退出/长时间无对话时触发，而非 cron 定时
