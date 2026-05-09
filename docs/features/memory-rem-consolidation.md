# 记忆 REM 联想巩固（面向开发者）

> 架构文档：#[[file:docs/architecture/memory-rem-consolidation.md]]

## 核心能力

巩固管线第 7 步：基于 L3 高 importance 实体做"跨实体联想"，调 LLM 识别潜在语义关系，合格候选落文件审计。

## 如何启用

```yaml
lifepilot:
  memory:
    rem:
      enabled: true
      seed-limit: 10
      neighbor-limit: 5
      min-confidence: 0.65
```

启用后每次巩固管线执行（cron 或手动触发 `ConsolidationPipeline.consolidate()`）会额外执行一次 REM 联想。

## 输出位置

`target/cache/memory-rem-associations/{yyyy-MM-dd}.json`

每个日期一个文件，当天多次巩固**合并**到同一文件，不覆盖。

查询 API：

```java
@Autowired AssociationCandidateStore store;

var today = store.load(LocalDate.now());
// today 是 List<AssociationCandidate>，可自行筛选/可视化/导出
```

## 调试技巧

### 查看本轮生成了什么

1. 开启 DEBUG 日志：`logging.level.com.lifepilot.memory.consolidation.association=DEBUG`
2. 看日志中 `REM 联想: 生成 seeds=X, candidates=Y` 与 `REM 联想: 入库 input=X, saved=Y`

### 手动触发一轮

```java
@Autowired ConsolidationPipeline pipeline;
pipeline.consolidate(true);  // manualTrigger=true
```

### 为什么我没看到候选

- 确认 `enabled=true`
- 确认有满足条件的 seed：类型属于 `seed-types`、description 非空、importance ≥ 其他实体
- 确认 HybridRetriever 能返回邻居
- 确认 LLM 可用（看 router 日志）
- 确认 confidence 没被过滤（默认 0.65）

## 回退

```yaml
lifepilot.memory.rem.enabled: false
```

关闭后 REM 相关 Bean 不装配，ConsolidationPipeline 第 7 步直接跳过。

## 测试覆盖

- `AssociationCandidate_单元测试`（5 场景）
- `AssociationCandidateStore_单元测试`（5 场景，@TempDir）
- `AssociationConsolidator_单元测试`（4 场景）
- `AssociationCandidateGenerator_单元测试`（8 场景，mock SemanticMemory + HybridRetriever + GenerationRouter）
