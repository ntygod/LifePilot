# 记忆生命周期闭环 · 图代码对账审计报告

- **日期**：2026-04-23
- **审计对象**：`feature/memory-lifecycle-closure` 分支（Phase 0-4 全部 43 个实施 Task）
- **对账依据**：`docs/architecture/memory-data-flow.md` 的 ~51 条校验清单
- **产出**：Task 44（最终验收）

---

## 总结

- 清单条目总数：**51 条**（§7.1 30 条原 ✅ + §7.2 21 条原 🕐）
- ✅ 验证通过：**45 条**
- ❌ 不一致：**5 条**（均为登记漂移未修）
- ⚠️ 无法机械验证：**1 条**（L4SyncListener 订阅集合是否合理 — 属设计口径）
- **7 处预登记漂移修复情况：已修 2（#6 / #7）/ 未修 5（#1 / #2 / #3 / #4 / #5）**

---

## 分节验证结果

### 1. 14 写入源（§2 表）

| # | 条目 | 证据 | 结论 |
|---|---|---|---|
| 1 | RealtimeExtractor `executeAdd` → `upsertWithConflictDetection` | `RealtimeExtractor.java:332` | ✅ |
| 2 | RealtimeExtractor `executeDelete` → `semanticMemory.archive` | `RealtimeExtractor.java:389` | ✅ |
| 3 | ExperienceSummarizer 去重命中 3-arg `updateImportanceScore(..., EFFECTIVENESS)` | `ExperienceSummarizer.java:252-253` | ✅ |
| 4 | ExperienceSummarizer 新建路径 upsert | `ExperienceSummarizer.java:307` | ✅ |
| 5 | SubtaskReflector 走 `upsert(..., "subtask-reflection")` | `SubtaskReflector.java:266` | ✅ |
| 6 | ContrastiveLearner `upsert(..., "contrastive-learning")` | `ContrastiveLearner.java:215` | ✅ |
| 7 | UserProfileConsolidator 新建/更新均经 upsert | `UserProfileConsolidator.java:227, :250` | ✅ |
| 8 | EntityDeduplicator.mergePair 归档 secondary 显式传 `CONFLICT_RESOLVE` | `EntityDeduplicator.java:198` | ✅ |
| 9 | ProactiveMemoryBridge.syncInsightToL3 走 upsert | `ProactiveMemoryBridge.java:385` | ✅ |
| 10 | FeedbackProcessor.applyDelta 3-arg `updateImportanceScore(..., USER_FEEDBACK)` | `FeedbackProcessor.java:92-93` | ✅ |
| 11 | EffectivenessTracker.adjustScore 3-arg `updateImportanceScore(..., EFFECTIVENESS)` | `EffectivenessTracker.java:126-127` | ✅ |
| 12 | ForgettingEngine 三处 archive 全部传 `CRON_EXPIRE` | `ForgettingEngine.java:215, :221, :227` | ✅ |
| 13 | MemoryController.deleteEntity → archive | `MemoryController.java:430` | ✅ |
| 14 | MemoryController.createEntity / updateEntity 走 upsert | `MemoryController.java:477, :524` | ✅ |
| 15 | MemoryController.updateProfile UPDATE 分支走 updateDescription | `MemoryController.java:869` | ✅ |
| 15b | MemoryController.updateProfile **新建分支**传 `"manual-edit"` 而非 `"user-edit-description"` | `MemoryController.java:882` | ⚠️ 轻度漂移 — `resolveChangeSource("manual-edit")`→default→`LLM_SEMANTIC`，按语义应为 `UI_EDIT` |

### 2. 4 事件契约（§3 表）

- `EntityLifecycleChanged` record 6 字段：`EntityLifecycleChanged.java:13-20` ✅
- `SourceInvalidated` record 3 字段：存在 ✅
- `EntityWeightChanged` 4 字段（含 source）：`EntityWeightChanged.java:21-26` ✅
- `ProactiveTaskCancelled` compact ctor 做 `List.copyOf`：`ProactiveTaskCancelled.java:18-20` ✅
- `ChangeSource` 8 值（TOOL_EXPLICIT / LLM_SEMANTIC / CRON_EXPIRE / CONFLICT_RESOLVE / NEGATIVE_FEEDBACK / PROACTIVE_CANCEL / UI_EDIT / DERIVATION_TRIGGER）：✅
- `LifecycleState` 7 态 + `canTransitionTo` + `isRetrievable`：`LifecycleState.java:9-33` ✅

**发布点**：
- 新建：`SemanticMemory.java:212-218` 发 `EntityLifecycleChanged(null→state)` ✅
- `archive(entity, source)`：`SemanticMemory.java:408-414` ✅
- `updateLifecycleState`：`SemanticMemory.java:607-608` ✅
- `updateImportanceScore`：`SemanticMemory.java:695` ✅
- `publishAfterCommit`：`SemanticMemory.java:531-553` ✅

### 3. 7 个监听器（§4 表）

**所有 7 个监听器全部实装**，均用 `@TransactionalEventListener(phase = AFTER_COMMIT)`：

| # | 监听器 | 文件 | 关键逻辑验证 | 结论 |
|---|---|---|---|---|
| 1 | L4SyncListener | `L4SyncListener.java:37-83` | INACTIVATING = {CANCELLED, EXPIRED, SUPERSEDED, ARCHIVED, REGENERATION_NEEDED}；调 `deactivateBySourceEntity` | ✅ |
| 2 | VectorListener | `VectorListener.java:30-63` | KEEP_VECTOR = {ACTIVE, COMPLETED}；非保留态清向量 | ✅ |
| 3 | DerivedEntityListener | `DerivedEntityListener.java:44-163` | TRIGGER_STATES = {SUPERSEDED, CANCELLED, EXPIRED}；防递归 `source==DERIVATION_TRIGGER` 短路；画像 20% 阈值 | ✅ |
| 4 | ProvenanceStaleListener | `ProvenanceStaleListener.java:31-58` | 调 `repo.markStale(...)` | ✅ |
| 5 | ReValidationListener | `ReValidationListener.java:36-85` | `findEntityIdsBySource` → `queue.enqueue` | ✅ |
| 6 | NegativeFeedbackListener | `NegativeFeedbackListener.java:40-125` | 写账本 → 阈值判定（count ≥ 3 OR cumulativeScore < -1.0）→ 仅 ACTIVE 转 SUPERSEDED | ✅ |
| 7 | ProactiveTaskCancelListener | `ProactiveTaskCancelListener.java:36-84` | 幂等：ACTIVE 才转 CANCELLED；调 `updateLifecycleState(..., PROACTIVE_CANCEL)` | ✅ |

### 4. 4 个 Cron 扫描器

| 扫描器 | 文件 | Cron | 结论 |
|---|---|---|---|
| ExpirationScanner | `ExpirationScanner.java:52` | `0 0 * * * *`（每小时） | ✅ |
| OrphanProvenanceScanner | `OrphanProvenanceScanner.java:58` | `0 0 3 * * *`（每日 03:00） | ✅ |
| ConflictResolutionRetry | `ConflictResolutionRetry.java:60` | `0 0 4 * * *`（每日 04:00） | ✅ |
| DerivationRegenerator | `DerivationRegenerator.java:97` | `0 0 */2 * * *`（每 2 小时） | ✅ |

### 5. 派生实体机制

- `SemanticMemory.findDerivedBySourceEntity` 在 `SemanticMemory.java:1019` ✅
- `SemanticMemory.findExpiredActive` 在 `SemanticMemory.java:996` ✅
- `TemporalEntity` 支持 `isDerived` / `derivationSources`（`TemporalEntity.java:42-43`）✅
- 23 参 canonical ctor 支持 V15 全部字段；16 参兼容 ctor 默认 `isDerived=false / derivationSources=List.of()`（`TemporalEntity.java:60-84`）

### 6. 检索层过滤（Task 30）

- `HybridRetriever` 实装 SQL 侧 `lifecycle_state` 过滤 + annotateLifecycle 3 标注（`HybridRetriever.java:399-618`）✅
- `isRetrievable()` 过滤在 `HybridRetriever.java:431, :501-506` ✅
- `isHistorical = COMPLETED` / `isStale = REGENERATION_NEEDED` / `needsRevalidation = 存在 STALE provenance`（`HybridRetriever.java:611-618`）✅
- `MemoryProvenanceRepository.markStale` 在 `:287-294` ✅
- 批量 `findStaleEntityIds`（FAQ: :311-340 区域）✅

### 7. 7 处预登记漂移回查

| # | 漂移点 | 证据 | 结论 |
|---|---|---|---|
| 1 | UserProfileConsolidator 产出 `__consolidated_profile` **未**标 `isDerived=true / derivationSources` | `UserProfileConsolidator.java:209-225, :232-249` 均用 **16 参兼容 ctor**，默认 `isDerived=false` | **未修** ❌ |
| 2 | EntityDeduplicator 合并后 primary **未**标 `isDerived` | `EntityDeduplicator.java:178-192` 只 SQL UPDATE 无 `is_derived` / `derivation_sources` 赋值 | **未修** ❌ |
| 3 | ContrastiveLearner **未**产出独立 `CONTRASTIVE_INSIGHT` 实体 | `ContrastiveLearner.java:113-114, :191-216` 仍原地增强 `successExp` 的 `properties.lessons` | **未修** ❌ |
| 4 | ProceduralMemory.savePreference / save **未**填 `source_entity_id` | `ProceduralMemory.java:62-75`（save）、`:194-203`（savePreference）INSERT 列不含 V15 新列 | **未修** ❌ |
| 5 | ProactiveMemoryBridge.markGoalFulfilled 直接 `publishEvent`（非 AFTER_COMMIT） | `ProactiveMemoryBridge.java:120-141` 无 `@Transactional` 无 `publishAfterCommit` | **未修** ❌ |
| 6 | RealtimeExtractor **未**输出 `temporality / expires_at` | `RealtimeExtractor.java:317-334, :358-377` 走 23 参 ctor 带入 temporality / expires_at | **已修** ✅ |
| 7 | upsertWithConflictDetection 合并分支**不**发 `EntityLifecycleChanged` | `ConflictResolutionService.java:272-314` 借 Task 24 接管：裁决 REPLACE/TIMELINE 时调 `updateLifecycleState(..., CONFLICT_RESOLVE)` 间接发事件 | **已修（借道 Task 24）** ✅ |

**修复率：2/7 = 29%**。

---

## 图代码不一致清单

### 图要求 / 代码未做（影响业务闭环）

1. **漂移 #1 / #2**：UserProfileConsolidator / EntityDeduplicator 派生产出不标 `isDerived=true` → DerivedEntityListener `findDerivedBySourceEntity` **查询无法命中**，导致 `__consolidated_profile` / MERGED 实体源失效时不会触发 REGENERATION_NEEDED 级联
2. **漂移 #4（阻断级）**：ProceduralMemory.savePreference / save 不填 `source_entity_id` → L4SyncListener 通过 `source_entity_id` 反查的失活逻辑**永不命中** → L3 偏好 SUPERSEDED 后 L4 `preference_rules` 不同步 inactive
3. **漂移 #5**：markGoalFulfilled 非 AFTER_COMMIT 事件发布 → 事务回滚时 listener 幻觉级联风险（窄但违反"事件最终一致性"约束）
4. **漂移 #3**：ContrastiveLearner 仍原地增强；DerivationRegenerator 已对此留"no-regenerate-api"降级分支，功能可用但未贴合 spec
5. **条目 15b**：`MemoryController.updateProfile:882` 的 `"manual-edit"` 字符串未命中 `resolveChangeSource` 映射 → 该路径事件 source 归到 LLM_SEMANTIC 而非 UI_EDIT

### 代码实现 / 图未写（需补文档）

1. **新增 4 个 feedback 包类**：`FeedbackLedgerRepository` / `RegenerationQueueRepository` / `RevalidationQueueRepository` / `FeedbackThresholdConfig`（`memory/lifecycle/feedback/`）
2. **新增 2 个 procedural 包 Repository**：`PreferenceRuleRepository` / `ProceduralMemoryRepository`（`memory/procedural/`）—— Phase 1 Task 15 专为 L4SyncListener 新增
3. **新增 3 个 semantic 包类**：`ConflictResolutionService` / `ConflictResolutionRepository` / `ConflictVerdict` record
4. **V17、V18 迁移**：`proactive_task_insight_links`（V17，Task 13）+ `proactive_reminder_feedback.insight_entity_id` 列（V18，Task 25）—— §6 Schema 快照未包含
5. **TrustUpgradeService 3-arg `recordNegativeFeedback(userId, behaviorName, notificationId)`** —— 条目 41 列为 🕐 planned，实际已完整实装 insight 反向溯源
6. **MemoryController.updateProfile 新建分支用 `"manual-edit"`** —— 非约定的 `"user-edit-description"`
7. **MemoryToolProvider.enum 已扩到 `cancel/complete/supersede/tag/query-at-time/search-experience`**（Task 22，条目 39 从 🕐 转 ✅）
8. **ConflictResolutionService 经 `triggerConflictResolution` 挂 upsertWithConflictDetection**（`SemanticMemory.java:455-498`）—— upsert 后 AFTER_COMMIT 自动触发裁决
9. **DerivedEntityListener 画像 20% 阈值特化**（`DerivedEntityListener.java:59-141`）—— 文档未描述该策略
10. **V16 `temporal_entities` 视图重建投影生命周期字段 + `me.status <> 'DELETED'` 语义变化** —— §6 已说明但 `WHERE` 过滤变动未显式标注

---

## 验收结论

**CONDITIONAL PASS**（有条件通过）

### 通过的理由

- 51 条清单中 **45 条** ✅ 通过（含原 🕐 Phase 1-4 的 21 条中 19 条已转 ✅）
- 核心事件总线架构完整闭环：7 监听器 + 4 Cron + SemanticMemory 单一发布点
- 关键路径（冲突裁决、expiration 扫描、负反馈累计、派生实体级联）齐备
- 最关键的两处漂移 **#6（temporality 输出）** 与 **#7（upsert 合并分支事件，借 Task 24）** 已修

### 有条件的理由（后续 TODO）

**必须补齐的功能性漂移**（阻断业务完整闭环的"最后一公里"）：

1. **漂移 #4（阻断级，最严重）**：`ProceduralMemory.savePreference` / `save` 的 INSERT 语句添加 `source_entity_id` / `deactivated_reason` 列。否则 L3→L4 失活联动失效 —— 场景 S01 的偏好级联不走。**建议作为 Phase 4 收尾 hotfix**
2. **漂移 #1 & #2**：UserProfileConsolidator 与 EntityDeduplicator 使用 23 参 canonical ctor，传入 `isDerived=true` + `derivationSources`。否则 DerivedEntityListener 永不会给它们派发级联
3. **漂移 #5**：`ProactiveMemoryBridge.markGoalFulfilled` 加 `@Transactional`，改走 `publishAfterCommit`
4. **漂移 #3**：ContrastiveLearner 按 spec 产出独立 `CONTRASTIVE_INSIGHT` 实体（可延后）

**必须补齐的文档漂移**（图落后于代码）：

5. §6 Schema 快照增补 V17 / V18 详述
6. §2 表 TrustUpgradeService 状态从"planned"更新为"✅ S14 已实装"
7. §7.2 条目批量更新 🕐 → ✅ + 记录 commit hash
8. 新增附录：`feedback/` 4 类、`procedural/` 2 Repository、`semantic/ConflictResolution*` 3 类
9. §2 表第 15b 行：修复 `MemoryController.updateProfile:882` 的 `"manual-edit"` 映射（代码改 or 映射表补）

### Critical 不一致

**无真正 Critical 阻断**。漂移 #4 是最严重的功能性缺口，但由 L4SyncListener 的容错（`WHERE source_entity_id = ? AND deactivated_reason IS NULL`，空列不命中 no-op）保证系统不崩，只是失去 L3→L4 级联能力。

**建议**：将 4 条未修漂移（#1/#2/#4/#5）纳入 Phase 4 收尾 hotfix PR；漂移 #3 作为后续独立 Task 推进。当前 Phase 0-4 验收可以通过；**但 scenario S01/S03/S07（L3 ARCHIVE → L4 同步失活）的完整闭环验证应在漂移 #4 修复后再跑一次完整 E2E**。

---

## 附：文档行号偏移

部分条目（如 RealtimeExtractor 文档说 299 实际 332）不影响机制判定，只说明文档最后更新后代码有局部 refactor。建议下一次文档刷新时批量校正，可用脚本 `grep -nE "file:line" memory-data-flow.md` 批量核对。
