# 记忆安全加固（面向开发者）

> 架构：#[[file:docs/architecture/memory-security-polish.md]]

## 启用

```yaml
lifepilot:
  memory:
    security:
      injection-detection-enabled: true
```

## 调用

```java
@Autowired MemoryInjectionDetector detector;

var result = detector.detect(spaceId, candidate.description(), candidate.trustScore());
if (result.isBlocked()) {
    // 拒绝写入
    log.warn("记忆写入被阻断: {}", result.details());
}
```

## 回退

```yaml
lifepilot.memory.security.injection-detection-enabled: false
```

关闭后 `detector.detect()` 直接返回 PASS，不影响主链路。

## M-P2-7 前端血缘展示

后端 API 已就绪（`MemoryController.findRelations` / `findProvenance`），前端可直接对接实现血缘时间线与关系图。
