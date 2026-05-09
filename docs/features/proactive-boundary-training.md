# 主动引擎 Boundary / Focus / Training 特性说明（面向开发者）

> 架构文档：#[[file:docs/architecture/proactive-boundary-training.md]]

## 核心改动

本特性让主动引擎三方面能力升级：

| 子能力 | 用户可感知表现 | 实现位置 |
|---|---|---|
| Boundary 感知 | 用户刚完成一轮对话/工作流的 10 分钟内，打扰门槛变低；其他时段变高 | `BoundarySignalCollector` + `DecisionGate` |
| Focus 识别 | 全屏、IDE、持续高密度输入状态下，NOTIFY 自动降级为 QUEUE（但 INTERRUPT 保留） | `FocusStateDetector` + `DecisionGate` |
| 训练样例库 | 周级把历史反馈编译为 Gate 3 可用的 few-shot 正负例 | `ProactiveTrainingReplayService` + `ProactiveFewShotLibrary`（文件持久化） |

## 开启与关闭

全部三个子系统默认状态如下：

```yaml
lifepilot:
  agent:
    task:
      # Boundary：默认开启（低成本，纯内存）
      boundary-signal-enabled: true
      boundary-window-minutes: 10

      # Focus：默认开启
      focus-detection-enabled: true
      focus-message-density-threshold: 5
      focus-message-interval-seconds: 40

      # Training：默认关闭（避免冷启动阶段样本不足时污染 library）
      proactive-training-enabled: false
      proactive-training-replay-interval-days: 7
```

## Boundary 行为矩阵

| 候选分数 | boundary=UNKNOWN（默认） | IN_BOUNDARY | OUT_OF_BOUNDARY |
|---:|---|---|---|
| 0.25 | 插件 suggest level 为上限 | 无调整 / SILENT | 无调整 / SILENT |
| 0.40 | 插件 suggest level 为上限 | NOTIFY | QUEUE |
| 0.60 | 插件 suggest level 为上限 | INTERRUPT | QUEUE |
| 0.80 | 插件 suggest level 为上限 | INTERRUPT | NOTIFY |

注意：最终级别永远不会超过 `ProactiveAction.suggestedLevel`（插件级上限），只会降级。

## Focus 行为

FOCUS_MODE 下：
- NOTIFY → QUEUE
- INTERRUPT 保留（真紧急场景）
- QUEUE / SILENT 不变

FOCUS_MODE 触发条件（短路，任一命中即 FOCUS_MODE）：
1. 桌面 `fullscreen=true`
2. 桌面 `focusTitle` 命中 IDE 正则（`VS Code|IntelliJ|WebStorm|...`）
3. 桌面 `idleMinutes<1` 且上报时间距 now ≤ 60 秒
4. 对话端 5 分钟窗口内消息数 ≥ 阈值且平均间隔 ≤ 阈值

## Training 库使用

当 `proactive-training-enabled=true` 时：

1. 启动 60 秒后首次运行，之后每 7 天运行一次
2. 从 `ReminderExecutionRepository` 查近 14 天样本，分层采样
3. 保存到 `target/cache/proactive-few-shot/{defaultUserId}.json`

查询样本（下一 spec `proactive-timing-cot` 在 Gate 3 prompt 中调用）：

```java
@Autowired ProactiveFewShotLibrary library;

List<ProactiveFewShotSample> positives = library.getSamples("default-user", "DUE_SOON", 5);
// 拼接到 LLM system prompt 作为 few-shot examples
```

## 调试技巧

### 查看 boundary 事件队列

`BoundarySignalCollector.snapshot(userId)` 返回该用户最近边界事件列表，便于诊断。

### 本地快速触发 boundary 场景

1. 发送一轮对话 → 10 分钟内即处于 IN_BOUNDARY
2. 完成一个工作流（任意触发）→ defaultUserId 进入 IN_BOUNDARY

### 本地快速触发 FOCUS_MODE

最简单方式：任何全屏视频/游戏（fullscreen=true 上报）

或通过 Tauri 桌面端的 `/api/context/focus` 端点 POST 模拟：

```json
{
  "focusApp": "code.exe",
  "focusTitle": "main.ts - VS Code",
  "fullscreen": false,
  "idleMinutes": 0,
  "timestamp": "2026-05-09T10:00:00Z"
}
```

## 与既有组件的关系

- **不替代** `ImplicitSignalCollector`（事后观察），二者互补
- **不影响** `ReminderReplayService`（LinUCB/阈值调参），二者独立
- **不改动** `proactive_reminder_delivery` / `reminder_feedback` 等数据库表
- **不新增** 数据库迁移（few-shot 库用文件持久化）

## 测试覆盖

- `BoundarySignalCollector_单元测试`：8 个场景（事件接收、窗口判定、容量截断、多用户隔离等）
- `FocusStateDetector_单元测试`：10 个场景（四档信号分别触发、叠加、缺失回退）
- `DecisionGate_单元测试`：既有 11 个测试 + 新增 8 个 boundary/focus 场景
- `ProactiveFewShotLibrary_单元测试`：6 个场景（save/load/filter/limit/错误输入）
- `ProactiveTrainingReplayService_单元测试`：5 个场景（分层采样、top-K、空输入、摘要生成）
- `ProactiveTrainingScheduler_单元测试`：4 个场景（开关/空配置/空用户/正常流）
- `ProactiveAutoConfiguration_集成测试`：已更新签名，新 Bean 装配验证

## 回退策略

所有子系统支持独立关闭：

```yaml
lifepilot:
  agent:
    task:
      boundary-signal-enabled: false
      focus-detection-enabled: false
      proactive-training-enabled: false
      # 阈值偏移也可置 0
      proactive-engine-boundary-notify-delta: 0
      proactive-engine-boundary-interrupt-delta: 0
      proactive-engine-out-of-boundary-delta: 0
```

关闭所有配置后行为等价于本 spec 合并前。
