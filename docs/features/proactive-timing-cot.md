# Proactive Timing / CoT / 分层激活（特性说明）

> 架构文档：#[[file:docs/architecture/proactive-timing-cot.md]]

## 核心改动

| 子能力 | 用户可感知表现 | 实现位置 |
|---|---|---|
| Goldilocks 时效窗口 | DUE_SOON/PREPARATION 类候选在 "suggestedAt + p80(latency)" 之后自动跳过，不会再推送"已经没用"的提醒 | `timing/GoldilocksWindowCalculator` + `ReminderDecisionEngine` |
| Gate 3 CoT 推理 | 高分候选（≥ 0.6）LLM 前注入真实反馈 few-shot + 四段结构化思考（observation → user-state → necessity → action） | `cot/GateThreeReasoner` |
| 行为分层激活 | 主动行为插件按依赖记忆层分 3 组（FACT/HABIT/STANDALONE），每次心跳只激活相关组，降低 detect 开销 | `behavior/BehaviorLayer` + `behavior/BehaviorActivationPolicy` |
| 偏好命名约定 | 主动引擎只读写 `proactive-*` 前缀类别，避免和通用用户偏好混用 | `ProactiveEngine` / `ProactiveMemoryBridge` |

## 配置

```yaml
lifepilot:
  agent:
    task:
      # Timing：默认开启，但样本不足时回退到 30 分钟默认延迟
      proactive-timing-window-enabled: true
      proactive-timing-default-response-latency-minutes: 30
      proactive-timing-response-latency-min-samples: 5
      proactive-timing-response-latency-p80-percentile: 0.8

      # CoT：默认关闭（等训练库积累样例后再开）
      proactive-cot-enabled: false
      proactive-cot-min-score: 0.6
      proactive-cot-few-shot-count: 4
```

## Goldilocks 行为矩阵

| 候选类型 | 是否走窗口检查 | 检查逻辑 |
|---|---|---|
| DUE_SOON | ✅ | `now > suggestedAt + latency × safety` → WINDOW_CLOSED |
| PREPARATION_WINDOW | ✅ | 同上 |
| COMMITMENT_GAP | ❌ | 无明确 deadline，不检查 |
| HABIT_WINDOW | ❌ | 周期性提醒不受窗口约束 |
| BEHAVIOR_ANOMALY | ❌ | 异常检测没有"过期"概念 |

关闭 `proactive-timing-window-enabled` 时全部跳过检查（等价关闭）。

## CoT Prompt 结构

当 `proactive-cot-enabled=true` 且候选分数 ≥ `proactive-cot-min-score` 时，`GateThreeReasoner.buildStructuredPrompt` 输出：

```
<few-shot 样例 priming>
- type=reminder, action=NORMAL_PUSH, reward=0.90, outcome=positive, context=score=0.80,outcome=ACTED
- type=reminder, action=SOFT_PUSH, reward=0.15, outcome=negative, context=score=0.40,outcome=DISMISSED

<observation>
候选标题：...
触发行为：...
候选分数：...
boundary=IN_BOUNDARY, focus=NORMAL, 今日已推送=0/5
</observation>

<user-state>
（推断用户当前状态）
</user-state>

<necessity>
（评估必要性）
</necessity>

<action>
（输出 SKIP / SOFT_PUSH / NORMAL_PUSH）
</action>
```

`parseAction` 解析 `<action>` 段；解析失败时调用方应降级为插件原 `suggestedLevel`。

## BehaviorLayer 分组

| 层 | 归属插件 | 激活条件 |
|---|---|---|
| FACT_DRIVEN | FollowUp, Insight, MemoryAttention | boundary ∈ {IN_BOUNDARY, UNKNOWN} 或（OUT_OF_BOUNDARY 且未推送 + 有画像） |
| HABIT_DRIVEN | Reminder, Report | boundary ∈ {IN_BOUNDARY, UNKNOWN} 或处于活跃时段（morning/afternoon/evening） |
| STANDALONE | Clipboard | 始终激活 |

## 偏好命名约定

| category | 用途 | 主动引擎是否消费 |
|---|---|---|
| `proactive-domain` | 行为插件领域偏好 | ✅ |
| `proactive-timing` | 时段偏好 | ✅ |
| `proactive-style` | 投递级别偏好 | ✅ |
| `user-preference` | 通用用户偏好（HotDigest 用） | ❌ |
| `schedule` / `output` | 其他领域 | ❌ |

主动引擎调用点直接使用固定的 `proactive-domain` / `proactive-timing` / `proactive-style` 类别，不保留额外的文档级工具类。

## 调试技巧

### 查看某用户估算的响应延迟

没有 API 暴露；可在 `GoldilocksWindowCalculator.estimateResponseLatency` 上加断点或 DEBUG 日志。

### 测试 CoT prompt

`GateThreeReasoner` 在 `proactive-cot-enabled=false` 时 `shouldApply` 返回 false，不生成 prompt。临时开启：

```yaml
lifepilot.agent.task.proactive-cot-enabled: true
```

然后在单测中直接调用 `buildStructuredPrompt` 查看输出。

### 分层激活调试

`BehaviorActivationPolicy.shouldActivate` 返回 false 时会输出 DEBUG 日志：

```
决策门控: 分层激活跳过 behavior=insight layer=FACT_DRIVEN
```

## 回退策略

Timing 与 CoT 可独立关闭；行为分层激活是主动引擎固定运行路径，不再保留全行为直通开关。

```yaml
lifepilot:
  agent:
    task:
      proactive-timing-window-enabled: false
      proactive-cot-enabled: false
```

## 测试覆盖

- `BehaviorActivationPolicy_单元测试`：5 场景
- `GateThreeReasoner_单元测试`：6 场景（prompt 拼接 / parseAction / 开关）
- `GoldilocksWindowCalculator_单元测试`：7 场景（默认延迟 / p80 估算 / 支持类型过滤等）
- `ReminderDecisionEngine_Goldilocks_集成测试`：2 场景（WINDOW_CLOSED 触发 / null calculator 兼容）
- `ProactiveAutoConfiguration_集成测试`：已更新签名
