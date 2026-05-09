# 记忆安全加固

> Spec: `.kiro/specs/memory-security-polish/`
> 关联 Gap: #[[file:docs/planned/memory-and-proactive-evolution-gaps.md]] §1 M-P2-6 / M-P2-7

## 1. 定位

对所有外部文本进入 L3 前做两层检测：
1. **Prompt injection 模式扫描**：已知注入正则库（中英各 7-10 条）
2. **Bayesian trust 异常**：同 space 的 trustScore 分布 Mahalanobis 距离检测

本 spec 只落地**可用组件 + 单元测试**，**不**强制注入到 RealtimeExtractor 等写入链路；接入节奏由未来 spec 按实际风险场景推进。

## 2. 组件

```
MemoryInjectionDetector.detect(spaceId, text, trustScore) → InjectionDetectionResult
    │
    ├─ PromptInjectionPatternScanner.scan(text) → Optional<MatchInfo>
    │     命中 → BLOCKED(PROMPT_INJECTION_PATTERN)
    │
    ├─ SpaceTrustDistribution.isOutlier(spaceId, trustScore, threshold)
    │     Mahalanobis 距离 > 3.0 → SUSPICIOUS(TRUST_SCORE_OUTLIER)
    │     blockOnSuspicious=true 时 → BLOCKED
    │
    └─ 其他 → PASS(CLEAN) + distribution.observe()
```

## 3. 数据结构

- `InjectionDetectionResult` record：Status（PASS/SUSPICIOUS/BLOCKED）+ InjectionReason + confidence + details
- `InjectionReason` 枚举：CLEAN / PROMPT_INJECTION_PATTERN / TRUST_SCORE_OUTLIER / SEMANTIC_OUTLIER(预留) / MANUAL_BLACKLIST(预留)

## 4. 配置

```yaml
lifepilot:
  memory:
    security:
      injection-detection-enabled: false  # 默认关闭
      outlier-threshold: 3.0
      sample-window-size: 1000
      block-on-suspicious: false  # true 时 SUSPICIOUS 也当 BLOCKED
```

## 5. M-P2-7 前端血缘展示（本 spec 不实施）

需求清单留给前端独立迭代：

- `EntityDetailDrawer` 新增"血缘" Tab：
  - Provenance 时间线（evidence_excerpt / trust_level / evidence_kind，按时间倒序）
  - Overlay / derivation 关系图（vue-flow 或 ECharts）
  - HotDigest 命中次数统计（按日期分桶）
- 后端复用既有 `MemoryController.findRelations` / `findProvenance` API

## 6. 前沿参考

| 来源 | 结论 | 应用 |
|---|---|---|
| MINJA（arXiv:2601.05504，2026） | 生产 Agent 注入成功率 95% | PromptInjectionPatternScanner 模式库 |
| OWASP LLM Top 10 - LLM01:2025 Prompt Injection | Prompt injection 是 LLM 应用头号风险 | 双层检测架构 |

## 7. 测试覆盖

- `PromptInjectionPatternScanner_单元测试`：10 场景（中英各覆盖）
- `SpaceTrustDistribution_单元测试`：6 场景（样本窗口 / 多 space 隔离 / 零方差）
- `MemoryInjectionDetector_单元测试`：6 场景（三种路径 + 开关 + 空输入）
- `InjectionDetectionResult_单元测试`：5 场景

## 8. 未来扩展

- 语义 outlier：用 embedding 做 KNN 距离
- Manual blacklist：运维可维护的屏蔽词表
- 接入 RealtimeExtractor：候选写入前强制过检测
- 前端血缘展示落地
