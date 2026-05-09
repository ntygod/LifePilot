# 统一检索编排器（面向开发者）

> 架构文档：#[[file:docs/architecture/retrieval-orchestrator.md]]

## 启用

```yaml
lifepilot:
  memory:
    retrieval-orchestrator:
      enabled: true
```

## 调用

```java
@Autowired RetrievalOrchestrator orchestrator;

EvidenceBundle bundle = orchestrator.retrieve(
    "Rust 学习",
    RetrievalIntent.EXPERIENCE,
    10);

for (EvidenceItem item : bundle.items()) {
    System.out.println(item.name() + " (score=" + item.score() + ", source=" + item.sourcePath() + ")");
}
```

## 回退

```yaml
lifepilot.memory.retrieval-orchestrator.enabled: false
```

关闭后所有 orchestrator Bean 不装配，现有 HybridRetriever / 工具链不受影响。
