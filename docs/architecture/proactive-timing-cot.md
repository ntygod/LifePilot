# 主动引擎 Timing / CoT / 分层激活

> Spec: `.kiro/specs/proactive-timing-cot/`
> 关联 Gap: #[[file:docs/planned/memory-and-proactive-evolution-gaps.md]] §2 P-P1-4 / P-P1-5 / P-P1-6, §3 C-P1-2
> 前置 spec: `proactive-boundary-training`（boundary / focus / few-shot 库）
> 上位架构: #[[file:docs/architecture/proactive-reminder-engine.md]]

## 1. 定位

本模块在主动引擎现有三级门控基础上再叠加三条能力线：

1. **Goldilocks 时效窗口**（P-P1-4）：让 deadline 型候选知道"最晚多晚还有意义"。
2. **Gate 3 结构化推理 + few-shot 消费**（P-P1-5）：高分候选可选走 CoT prompt，结构化输出并参考历史反馈样例。
3. **行为插件分层激活**（P-P1-6）：按记忆层分组，每次心跳只激活相关组。
4. **偏好命名卫士**（C-P1-2）：文档 + 静态工具固化 `proactive-*` 前缀约定。

## 2. 核心组件

### 2.1 GoldilocksWindowCalculator

**包路径**：`com.lifepilot.agent.task.reminder.timing`

职责：
- `estimateResponseLatency(userId)` — 从 `ReminderExecutionRepository.findReplaySamplesByUserIdSince` 读连续 acted 样本，统计 p80 时间差；样本不足 fallback 配置默认
- `isWindowClosed(userId, candidate, now)` — 只对 DUE_SOON / PREPARATION_WINDOW 候选检查，判定 `now > candidate.suggestedAt + latency × safetyFactor`

设计决策：
- **使用 `suggestedAt` 而非 `relevantAt`**：`ReminderCandidate` 不直接暴露 `relevantAt`（在 signal 层），但 `suggestedAt` 是检测阶段写入的"建议提醒时机"。以 `suggestedAt` 为基准加响应延迟 grace 即可表达 Goldilocks 语义。
- **集成点**：`ReminderDecisionEngine.decide` 在硬边界（mute/quiet/fullscreen）之后、冷却检查之前插入；跳过返回 `SKIP(WINDOW_CLOSED)`。
- **向后兼容**：`ReminderDecisionEngine` 保留无参 / 单参 / 双参构造函数；新增四参构造函数接受 `@Nullable GoldilocksWindowCalculator + userIdSupplier`。

### 2.2 GateThreeReasoner

**包路径**：`com.lifepilot.agent.task.proactive.cot`

职责：
- 构造四段 `<observation>/<user-state>/<necessity>/<action>` 结构化 prompt
- 在 prompt 开头拼接来自 `ProactiveFewShotLibrary` 的真实反馈样例作为 priming
- 解析 LLM 响应中的 `<action>` 段为 `ReminderAction`；解析失败调用方降级

设计决策：
- **不直接调 LLM**：保持单一职责。LLM 调用仍在 `AbstractLlmBehavior.generateContent` 或各行为插件中。
- **本 spec 不强制接入任何行为插件**：仅交付 Reasoner + 单元测试。具体接入留给后续 spec 或运维级别的逐个切换。
- **shouldApply 过滤**：`proactiveCotEnabled=true` 且 `candidate.score() >= proactiveCotMinScore`（默认 0.6）。低分候选走原有快速生成链路。

### 2.3 BehaviorActivationPolicy + BehaviorLayer

**包路径**：`com.lifepilot.agent.task.proactive.behavior`

`BehaviorLayer` 枚举声明 4 层。`ProactiveBehavior` 接口新增 `default BehaviorLayer layer() { return STANDALONE; }`。8 个行为插件按归属 override：

| 插件 | Layer |
|---|---|
| FollowUpBehavior | FACT_DRIVEN |
| InsightBehavior | FACT_DRIVEN |
| ContextPrepBehavior | EXPERIENCE_DRIVEN |
| TaskExecutionBehavior | EXPERIENCE_DRIVEN |
| ReminderBehavior | HABIT_DRIVEN |
| ReportBehavior | HABIT_DRIVEN |
| ClipboardBehavior | STANDALONE |
| InfoSupplementBehavior | STANDALONE |

`ProactiveEngine.heartbeat` 在 Gate 2 遍历行为前先按 `policy.shouldActivate(layer, ctx)` 过滤。

### 2.4 ProactivePreferenceGuard

**包路径**：`com.lifepilot.agent.task.proactive`

静态工具类，提供 `isProactiveCategory(category)` 与 `isRecognizedCategory(category)`。
本 spec **不做运行期强制拦截**（避免破坏性变更）；留作未来严格化的入口。

## 3. 数据流

### 3.1 ReminderDecisionEngine 窗口检查插入点

```
candidate → detect → decide:
   muted? → SKIP(TOPIC_MUTED)
   quietHours? → SKIP(QUIET_HOURS)
   fullscreen? → SKIP(FULLSCREEN_APP)
   ▼
   NEW: goldilocks.isWindowClosed? → SKIP(WINDOW_CLOSED)
   ▼
   cooldown? → SKIP(COOLDOWN)
   score<min? → SKIP(LOW_SCORE)
   ...
```

### 3.2 Gate 3 CoT 消费路径（本 spec 不实施，仅留接入点）

```
behavior.reason(candidates, ctx):
   for each candidate:
     if reasoner.shouldApply(candidate):
       prompt = reasoner.buildStructuredPrompt(candidate, ctx, baseContent)
       response = llm.call(prompt)
       action = reasoner.parseAction(response).orElse(suggestedLevel)
     else:
       # 原有 AbstractLlmBehavior.generateContent 路径
```

### 3.3 分层激活路径

```
engine.heartbeat(ctx):
   for each behavior:
     if healthTracker.tryActivate(behavior.name()):
       if policy.shouldActivate(behavior.layer(), ctx):  # 新增
         candidates = behavior.detect(ctx)
         ...
```

## 4. 配置项

```java
// Timing
private boolean proactiveTimingWindowEnabled = true;
private int proactiveTimingDefaultResponseLatencyMinutes = 30;
private int proactiveTimingResponseLatencyMinSamples = 5;
private float proactiveTimingResponseLatencyP80Percentile = 0.8f;

// CoT
private boolean proactiveCotEnabled = false;
private float proactiveCotMinScore = 0.6f;
private int proactiveCotFewShotCount = 4;

// 分层激活
private boolean proactiveBehaviorLayeredActivationEnabled = false;
```

## 5. 跨模块接口变更

| 变更接口 | 模块 | 变更 | 兼容性 |
|---|---|---|---|
| `ReminderSkipReason` | agent.task.reminder | +WINDOW_CLOSED | 向后兼容 |
| `ProactiveBehavior` | agent.task.proactive | +`default BehaviorLayer layer()` | 向后兼容（default 方法） |
| 8 个行为插件 | agent.task.proactive.behavior + agent.task.reminder | override `layer()` | 向后兼容 |
| `ReminderDecisionEngine` | agent.task.reminder | +四参构造函数 | 保留无参/单参/双参 |
| `ProactiveEngine` | agent.task.proactive | +13 参构造函数 | 保留 10/12 参 |
| `AgentConfigProperties.TaskConfig` | agent.config | +8 字段 | 向后兼容 |

## 6. 依赖接口验证

| 接口 | 源码位置 | 验证状态 |
|---|---|---|
| `ReminderExecutionRepository.findReplaySamplesByUserIdSince` | com.lifepilot.agent.task.reminder | ✅ 已核对 |
| `ReminderReplaySample.decidedAt / acted` | com.lifepilot.agent.task.reminder | ✅ 已核对 |
| `ReminderCandidate.suggestedAt / type` | com.lifepilot.agent.task.reminder | ✅ 已核对 |
| `ProactiveFewShotLibrary.getSamples` | com.lifepilot.agent.task.proactive.training | ✅ 前置 spec 已落地 |
| `ContextPacket.boundaryState / focusMode / recentExperience / userProfile` | com.lifepilot.agent.task.proactive | ✅ 前置 spec 已添加 |
| `ReminderDecisionEngine.decide` 内部流程 | com.lifepilot.agent.task.reminder | ✅ 已核对 |

## 7. 风险与回退

- **Goldilocks 历史样本不足** → 回退到 30 分钟默认延迟（配置可调）
- **CoT 响应格式不稳定** → `parseAction` 失败降级为插件 suggestedLevel；`proactiveCotEnabled` 默认关闭
- **分层激活误跳过** → 默认关闭，生产前经运维验证
- **`ProactiveBehavior.layer()` 影响 Mockito mock** → 使用 default 方法避免破坏

所有子系统支持独立开关关闭。

## 8. 前沿参考

| 来源 | 结论 | 应用 |
|---|---|---|
| CHI 2025 Goldilocks Time Window（arXiv:2504.09332） | 时机错位比内容错位更伤害体验；`windowEnd = deadline − p80(latency)` | `GoldilocksWindowCalculator` |
| ContextAgent NeurIPS 2025（arXiv:2505.14668） | CoT 蒸馏 + think-before-action 让 Acc-P 从 77% → 87% | `GateThreeReasoner` 四段结构 |
| ProAgentBench（arXiv:2602.04482，2026） | 真实反馈 SFT 比 synthetic 数据多 +16.7pp | `GateThreeReasoner` 消费真实 few-shot 库 |
| ContextAgent & Small-Model CoT | 小模型上 CoT 在简单场景反而掉 10% | `shouldApply` 用 `proactiveCotMinScore=0.6` 限制仅高分候选启用 |

## 9. 未来扩展

- **Off-Policy Evaluation**（P-P2-7）：LinUCB OPE，基于 importance-sampled reward 计算 regret 曲线
- **Meta-check**（P-P2-8）：Gate 3 通过后插入"用户是否清楚自己需要"自检
- **行为插件 CoT 接入**：各行为插件在 `AbstractLlmBehavior.generateContent` 中按需调用 `GateThreeReasoner`；本 spec 未实施避免跨插件行为变更
- **Response latency 精细化**：当前用连续 acted 样本的时间差作为代理，未来扩展 `ReminderReplaySample` 字段加"响应 latency"精确值
