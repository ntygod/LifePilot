# 记忆老化与邻居回链 — 特性说明

> **面向读者**：开发者
> **上位**：`docs/architecture/memory-staleness.md`
> **gaps**：M-P0-2 / M-P1-5 / C-P0-1（`docs/planned/memory-and-proactive-evolution-gaps.md`）

## 功能

1. **新事实写入自动识别语义冲突邻居** → 把旧实体从 `ACTIVE` 迁移到新增的 `STALE_CANDIDATE`
2. **`STALE_CANDIDATE` 实体召回时显著降权但仍可召回**，允许 Agent 追问确认
3. **L3 生命周期事件通知主动引擎**，失效 bridge 缓存（本 spec 落 hook，真实缓存未来接入）
4. **邻居刷新候选 hook**，日志式记录（开关默认关）

## 使用方式

用户侧无需任何操作。记忆系统会在后台：

- 用户说"我现在喜欢绿茶" → RealtimeExtractor 写入新 PREFERENCE
- StalenessCoordinator 异步检测语义相似的历史 PREFERENCE（如"用户喜欢咖啡"）
- 旧 PREFERENCE 被标记 STALE_CANDIDATE
- 下次召回时旧偏好得分被扣 0.35（默认），新偏好排在前面
- 若用户日后再提"咖啡"相关，Agent 可追问确认

## 配置项

| 配置键 | 默认 | 说明 |
|---|---|---|
| `lifepilot.memory.staleness.enabled` | true | 总开关 |
| `lifepilot.memory.staleness.detection-similarity-threshold` | 0.85 | 邻居识别最低语义相似度 |
| `lifepilot.memory.staleness.max-neighbors-per-detection` | 3 | 单次最多标记 |
| `lifepilot.memory.staleness.detectable-types` | {PREFERENCE, HABIT, LOCATION, GOAL} | 触发类型白名单 |
| `lifepilot.memory.staleness.retrieval-penalty` | 0.35 | 召回惩罚比例 |
| `lifepilot.memory.staleness.neighbor-refresh-enabled` | false | 邻居刷新候选开关 |

## 开发指南

### 如何观察 staleness 是否在工作

开启 DEBUG 日志后：

```
staleness: entity=xxx marked=1 refreshCandidates=0
retrieval: STALE_CANDIDATE 降权 entity=yyy penalty=0.35
```

### 如何关闭

生产问题排查时可临时关闭：

```yaml
lifepilot.memory.staleness.enabled: false
```

关闭后旧有写入路径完全不变。

### 集成 memory-eval-harness

`mvn test -Pmemory-eval-quick` 会复用 staleness 能力：fixture 数据集虽然没有典型的"知识更新"场景多，但可以验证 staleness 未引入回归。

## 限制

- 依赖 EmbeddingRouter 可用（不然 VectorSearcher.searchEntities 走不通，staleness 静默失效）
- 阈值保守，可能漏判某些明显冲突；后续结合显式否定词检测补全
- NeighborRefreshService 首版只记日志，不直接写 candidate 表（等 Consolidator 侧消费能力就绪）
