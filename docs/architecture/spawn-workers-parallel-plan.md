# `spawn_workers` 临时并行分身完整实施计划

> **文档性质**：完整实施计划  
> **模块归属**：`com.lifepilot.multiagent`  
> **适用前提**：本次改造明确不考虑兼容性，允许直接调整工具协议、内部执行链路与调试展示  
> **最后更新**：2026-03-23

## 1. 背景

`spawn_workers` 的初始目标非常明确：不是构建一套长期维护的“子 Agent 平台”，而是在主 Agent 遇到复杂任务时，提供一个临时并行执行能力，用于缩短总耗时、提高吞吐。

当前实现虽然具备并发能力，但仍存在以下问题：

- Worker 的执行身份过于粗糙，只是“通用 Worker Prompt + 独立 AgentRequest”。
- Worker 的输入协议不够明确，主 Agent 难以稳定生成高质量子任务。
- Worker 的工具范围、上下文范围、预算模型还不够清晰。
- Worker 的结果虽然能聚合，但缺少稳定的 Artifact / 调试 / 观测闭环。
- 现有实现容易被误解为“简化版 handoff”，设计边界不够明确。

因此，本次改造的目标不是把 `spawn_workers` 做成具名子 Agent 资产体系，而是把它做成“主 Agent 的临时并行分身执行器”。

---

## 2. 设计定位

### 2.1 最终定位

`spawn_workers` 是主 Agent 的内部并行能力：

- Worker 只在当前一次工具调用内存在
- Worker 不在 `AgentRegistry` 中注册
- Worker 不具备独立资产生命周期
- Worker 不暴露独立入口
- Worker 不接管主任务控制权
- Worker 完成后立即销毁

### 2.2 与其他概念的边界

| 概念 | 定位 | 是否适合 `spawn_workers` |
|------|------|--------------------------|
| 主 Agent | 唯一用户代理，负责理解需求、规划、聚合、最终回答 | 是 |
| 临时 Worker | 主 Agent 的一次性并行分身 | 是 |
| `AgentDefinition` | 长期存在的具名 Agent 资产，适合 A2A / 管理后台 / 市场 | 否 |
| `handoff` | 把控制权交给另一个执行者 | 否 |
| `Skill` | 方法论、提示增强、工具建议 | 仅作为增强层 |

### 2.3 核心结论

1. `spawn_workers` 不绑定 `AgentDefinition`。
2. `spawn_workers` 不恢复 `handoff` 语义。
3. `Skill` 可以增强 Worker，但不能把 Worker 变成长期具名 Agent。
4. 主 Agent 始终是唯一控制者，Worker 只负责完成拆分出来的子任务。

---

## 3. 目标

### 3.1 功能目标

当主 Agent 遇到复杂任务时，可以：

- 将任务拆成多个相互独立的子任务
- 并行拉起多个临时 Worker
- 让每个 Worker 在受控预算、受控工具范围、受控上下文下运行
- 汇总多个 Worker 的结果
- 以摘要和引用形式返回主 Agent

### 3.2 效率目标

- 对可并行任务，墙钟时间明显低于串行执行
- 避免多个 Worker 重复读取整段对话历史
- 降低主 Agent 自身在大量调研、比对、写作子任务上的串行阻塞

### 3.3 质量目标

- Worker 的行为稳定、可预测
- 子任务协议明确，减少主 Agent 乱拆任务
- 调试视图能够看清每个 Worker 做了什么、用了多少资源、产出了什么

---

## 4. 非目标

- 不构建“子 Agent 商店”或“子 Agent 管理界面”
- 不为 `spawn_workers` 预置一批长期可选的专用 AgentDefinition
- 不让用户直接把 `spawn_workers` 当作角色选择器使用
- 不支持 Worker 之间直接通信
- 不支持 Worker 成为新的主控制者
- 不把远程 A2A Agent 并入本地 `spawn_workers`

---

## 5. 总体架构

### 5.1 执行模型

主 Agent 在推理中调用 `spawn_workers`，`spawn_workers` 内部完成：

1. 接收结构化子任务列表
2. 校验并行规模、深度、预算、工具范围
3. 为每个子任务生成一个临时 Worker 上下文
4. 并行执行多个 Worker
5. 将 Worker 完整结果写入 Artifact
6. 返回轻量聚合结果给主 Agent

### 5.2 Worker 的本质

Worker 不是“系统里另一种 Agent 资产”，而是：

- 一次性执行实例
- 由 `mode + task + context + skillIds + budget + toolScope` 定义
- 生命周期限定在当前工具调用内

### 5.3 新增内部组件

建议新增以下内部组件：

- `ParallelWorkerExecutor`
- `WorkerPromptComposer`
- `ParentTaskBriefBuilder`
- `WorkerArtifactStore`
- `WorkerTaskParser`
- `WorkerTaskValidator`

这些组件都属于 `spawn_workers` 的内部基础设施，不对外暴露为独立产品概念。

---

## 6. Worker 模式设计

### 6.1 不使用具名 Agent，而使用有限的执行模式

为了适应个人助手的杂项任务，又避免退化成“无限角色集合”，建议 Worker 只提供少量内部模式：

- `general`
- `research`
- `analysis`
- `write`

### 6.2 各模式职责

| 模式 | 适合任务 | 默认倾向 |
|------|----------|----------|
| `general` | 通用子任务、杂项执行、无法明确归类的任务 | 最保守 |
| `research` | 搜索、整理、交叉验证、收集信息 | 偏检索与事实提炼 |
| `analysis` | 对比、统计、推断、结构化分析 | 偏计算与归纳 |
| `write` | 起草文案、压缩表达、整理总结 | 偏写作与结构化输出 |

### 6.3 模式的本质

模式不是长期资产，只是一组内部 Prompt 模板和默认工具偏好。

模式不意味着：

- 独立注册
- 可管理元数据
- 长期身份
- 独立配置文件

模式只决定 Worker 的执行风格，不决定系统中的“角色体系”。

---

## 7. `Skill` 的使用边界

`Skill` 仍然可以参与 `spawn_workers`，但只作为增强层：

- 通过 `skillIds` 注入方法论和提示增强
- 可附带建议工具
- 不作为权限边界
- 不作为长期身份边界

换句话说：

- `mode` 决定执行风格
- `skillIds` 决定方法增强
- `task/context` 决定具体子任务

三者组合后形成一个临时 Worker，但不会形成新的系统级 AgentDefinition。

---

## 8. 新输入协议

### 8.1 顶层结构

```json
{
  "tasks": [
    {
      "task": "分别调研 A/B/C 方案的优缺点",
      "context": "重点看成本、实施周期、主要风险",
      "mode": "research",
      "skillIds": ["web-research"],
      "allowedTools": ["web-search", "web-fetch"],
      "inputArtifactIds": ["ws-1", "ws-2"],
      "budget": {
        "max_tokens": 3000,
        "max_steps": 6,
        "timeout_seconds": 45
      },
      "outputMode": "artifact"
    }
  ]
}
```

### 8.2 字段说明

| 字段 | 必填 | 说明 |
|------|------|------|
| `task` | 是 | 子任务描述 |
| `context` | 否 | 附加背景、约束、判断标准 |
| `mode` | 否 | `general / research / analysis / write`，默认 `general` |
| `skillIds` | 否 | Worker 需要注入的方法增强 |
| `allowedTools` | 否 | 任务级工具范围限制，只能收紧不能扩大 |
| `inputArtifactIds` | 否 | 供 Worker 消费的已有产物引用 |
| `budget` | 否 | 任务级预算覆盖，只能收紧 |
| `outputMode` | 否 | `artifact / inline / both`，默认 `artifact` |

### 8.3 破坏式调整

- 不再支持旧版弱结构任务格式。
- 工具描述改为强调“并行分身执行”，不再暗示具名子 Agent。
- 不再围绕 `agentId` 设计协议。

---

## 9. 新输出协议

### 9.1 顶层结果

```json
{
  "successCount": 2,
  "failureCount": 1,
  "totalTokensUsed": 5420,
  "wallClockMs": 18320,
  "workers": [
    {
      "workerIndex": 0,
      "taskId": "worker-0",
      "mode": "research",
      "success": true,
      "summary": "A 方案成本最低，但上线风险偏高",
      "artifactRef": {
        "workspaceItemId": "ws-123",
        "sourceTraceId": "trace-abc",
        "title": "research: 调研 A 方案"
      },
      "output": null,
      "tokensUsed": 1820,
      "stepsUsed": 5,
      "durationMs": 14320,
      "traceId": "trace-abc",
      "terminationReason": null
    }
  ]
}
```

### 9.2 结果约束

- 默认返回 `summary + artifactRef`
- `inline` 只适用于较短结果
- `both` 用于主 Agent 既要即时消费，又要可回溯留档
- 至少一个 Worker 成功时，应使用 `SUCCESS` 或 `PARTIAL_SUCCESS`
- 每个 Worker 都必须带 `traceId / tokensUsed / stepsUsed / durationMs / terminationReason`

---

## 10. 上下文模型

### 10.1 核心原则

Worker 默认不继承父会话完整历史。

原因：

- 成本高
- 冗余大
- 容易让 Worker 偏离子任务
- 会严重削弱并行带来的效率收益

### 10.2 默认上下文组成

每个 Worker 的上下文由以下几部分构成：

1. 模式对应的基础 System Prompt
2. 父任务简报
3. 当前子任务描述
4. 当前子任务的附加约束
5. Skill 注入的补充方法论
6. 输入 Artifact 摘要

### 10.3 需要新增的内部组件

- `ParentTaskBriefBuilder`
  从父任务中抽取足够但不冗长的简报

- `WorkerPromptComposer`
  组合 `mode + task + context + skill + artifactSummary`

- `ArtifactSummaryLoader`
  从工作区中读取 `inputArtifactIds` 的摘要

### 10.4 父任务简报应包含的内容

- 原始任务目标
- 当前拆分原因
- 本 Worker 需要关注的边界
- 输出预期

不应包含：

- 全量会话历史
- 与当前子任务无关的长篇中间推理
- 大段重复材料

---

## 11. Worker Prompt 体系

### 11.1 模式模板

建议为四种模式分别维护模板：

- `worker/general`
- `worker/research`
- `worker/analysis`
- `worker/write`

### 11.2 模板的共同约束

所有 Worker Prompt 都应包含以下约束：

- 只完成当前子任务
- 不主动扩展任务范围
- 不主动再次拆分为更多 Worker，除非未来显式允许
- 尽量输出结构化结果
- 遇到缺失信息时说明阻塞点
- 不写寒暄和面向用户的包装性表达

### 11.3 Prompt 组合顺序

建议顺序：

1. 模式基础 Prompt
2. 任务简报
3. 子任务正文
4. 补充上下文
5. Skill 指令
6. 输入 Artifact 摘要
7. 输出要求

---

## 12. 工具权限模型

### 12.1 总原则

Worker 的工具权限必须受双重约束：

1. 父 Agent 当前可用工具范围
2. 任务级 `allowedTools` 收紧范围

### 12.2 计算规则

Worker 最终工具集应按以下规则产生：

1. 从父 Agent 当前可见工具开始
2. 如指定 `allowedTools`，则取交集
3. 如模式存在默认建议工具，可进一步做排序提示，但不自动放权
4. infrastructure 工具按系统既有规则保留

### 12.3 为什么不绑定模式默认白名单

因为项目定位是个人助手，任务变化大，模式只是执行风格，不应成为新的硬角色权限层。工具权限仍应以当前主 Agent 作用域为准。

---

## 13. 执行链路设计

### 13.1 主流程

1. 主 Agent 调用 `spawn_workers`
2. `SpawnWorkersToolFactory` 解析请求
3. `WorkerTaskValidator` 校验任务列表
4. 从工具上下文读取父 `sessionId / traceId / depth / budget`
5. 按父剩余额度派生 Worker 池预算
6. 为每个任务生成临时 Worker 规范
7. `WorkerPromptComposer` 组装每个 Worker 的 Prompt
8. `ParallelWorkerExecutor` 并行执行各 Worker
9. `WorkerArtifactStore` 写入完整结果
10. 聚合轻量结果返回主 Agent

### 13.2 执行器设计

建议新增：

- `ParallelWorkerExecutor`

职责：

- 构造临时 `AgentRequest`
- 生成独立 `sessionId`
- 调用 `AgentOrchestrator.run()`
- 收集 `AgentResponse`
- 转换为 `SpawnWorkerResult`

### 13.3 为什么不复用 `AgentExecutor`

`AgentExecutor` 当前的职责是“执行具名 AgentDefinition”。  
本次 `spawn_workers` 的 Worker 明确不是具名 Agent，因此不建议硬复用 `AgentExecutor`，避免概念再次混淆。

如果后续 `AgentExecutor` 被抽象为更通用的“子执行器”，再考虑合并；本轮不强行统一。

---

## 14. 预算模型

### 14.1 预算原则

Worker 预算必须来自父请求剩余额度，而不是重新拿一套默认预算起炉灶。

### 14.2 分配规则

对每个 Worker 的预算，取以下三者最小值：

1. 父请求剩余额度按池比例切分后的结果
2. 任务级 `budget` 覆盖
3. 系统为 Worker 模式设置的软上限

### 14.3 模式软上限

建议为模式保留软上限配置，但仅用于防止异常膨胀：

- `general`：中等
- `research`：偏高
- `analysis`：中等偏高
- `write`：中等

这些软上限不是具名 Agent 预算，只是内部保护阈值。

### 14.4 记账要求

Worker 执行完成后，必须回传：

- `tokensUsed`
- `stepsUsed`
- `durationMs`

并在主 Agent 的工具结果处理中计入聚合统计。

---

## 15. Artifact 设计

### 15.1 目标

Worker 的完整结果默认不直接塞回主 Agent，而是写入 Artifact，再只把摘要返回给主 Agent。

### 15.2 存储方案

复用 [SessionWorkspaceService.java](../../src/main/java/com/lifepilot/memory/workspace/SessionWorkspaceService.java)，新增一层适配：

- `WorkerArtifactStore`
- `WorkspaceWorkerArtifactStore`

### 15.3 建议的 Artifact 结构

```json
{
  "mode": "research",
  "task": "调研 A 方案",
  "summary": "A 方案成本低，但集成风险高",
  "fullOutput": "...",
  "traceId": "trace-abc",
  "tokensUsed": 1820,
  "stepsUsed": 5,
  "durationMs": 14320,
  "sources": [],
  "metadata": {}
}
```

### 15.4 主 Agent 默认可见信息

- `summary`
- `artifactRef`
- 资源消耗
- 成功/失败状态

---

## 16. 观测与调试

### 16.1 Trace

每个 Worker 都应有独立 Trace，并与父 Trace 关联：

- `parentTraceId`
- `workerIndex`
- `mode`
- `taskId`
- `artifactRef`

### 16.2 SSE / Debug 信息

建议增加：

- `spawn_workers` 的 Worker 数量
- 每个 Worker 的完成状态
- 成功数、失败数、总耗时、总 Token
- Artifact 引用

### 16.3 前端展示

前端调试面板可增加：

- Worker 列表
- 每个 Worker 的模式、任务摘要、耗时、Token、终止原因
- Artifact 入口

不需要增加“子 Agent 管理界面”。

---

## 17. 后端改造清单

### 17.1 必改文件

- `src/main/java/com/lifepilot/multiagent/execution/SpawnWorkersToolFactory.java`
- `src/main/java/com/lifepilot/multiagent/execution/SubAgentBudgetAllocator.java`
- `src/main/java/com/lifepilot/multiagent/config/MultiAgentAutoConfiguration.java`
- `src/main/java/com/lifepilot/tool/bridge/ToolBridgeAgentToolProvider.java`
- `src/main/java/com/lifepilot/agent/model/Budget.java`
- `src/main/java/com/lifepilot/agent/ReactAgentLoop.java`
- `src/main/java/com/lifepilot/tool/model/ToolResultMeta.java`

### 17.2 新增文件

- `src/main/java/com/lifepilot/multiagent/execution/ParallelWorkerExecutor.java`
- `src/main/java/com/lifepilot/multiagent/execution/WorkerPromptComposer.java`
- `src/main/java/com/lifepilot/multiagent/execution/ParentTaskBriefBuilder.java`
- `src/main/java/com/lifepilot/multiagent/execution/WorkerArtifactStore.java`
- `src/main/java/com/lifepilot/multiagent/execution/WorkspaceWorkerArtifactStore.java`
- `src/main/java/com/lifepilot/multiagent/execution/WorkerTaskValidator.java`
- `src/main/java/com/lifepilot/multiagent/execution/model/SpawnWorkersRequest.java`
- `src/main/java/com/lifepilot/multiagent/execution/model/SpawnWorkerTaskSpec.java`
- `src/main/java/com/lifepilot/multiagent/execution/model/SpawnWorkerResult.java`
- `src/main/java/com/lifepilot/multiagent/execution/model/WorkerArtifactRef.java`
- `src/main/java/com/lifepilot/multiagent/execution/model/WorkerMode.java`
- `src/main/java/com/lifepilot/multiagent/execution/model/WorkerOutputMode.java`

### 17.3 不再需要的方向

- 不扩展 `AgentDefinition`
- 不扩展 Agent Markdown 元数据用于 `spawn_workers`
- 不让 `spawn_workers` 依赖 `AgentRegistry`
- 不在本轮引入 `workerEligible` 等字段

---

## 18. 前端改造清单

- Trace / Debug 面板展示 Worker 执行结果
- `toolsSummary` 能表达 `spawn_workers` 聚合信息
- Artifact 可视化入口

不需要：

- 子 Agent 选择器
- 子 Agent 编辑页
- 子 Agent Marketplace

---

## 19. 测试计划

### 19.1 单元测试

- `SpawnWorkersToolFactoryTest`
  - 任务列表为空时报错
  - 超过并行上限时报错
  - 不同 `mode` 生成不同 Prompt
  - 任务级 `allowedTools` 正确收紧
  - `skillIds` 能正确注入 Prompt
  - `outputMode=artifact/inline/both` 行为正确
  - 父预算与任务预算裁剪正确

- `ParallelWorkerExecutor` 相关测试
  - 为每个 Worker 生成独立 `sessionId`
  - 正确收集 `AgentResponse`
  - 异常时生成失败结果而不拖垮整批执行

- `Budget / ReactAgentLoop` 相关测试
  - Worker 消耗统计正确汇总
  - 多 Worker 并发后聚合预算信息正确

### 19.2 集成测试

- 主 Agent 调用 `spawn_workers`
- 多个 Worker 并行执行不同子任务
- 完整结果写入工作区
- 主 Agent 收到摘要与 Artifact 引用
- 部分成功场景可继续汇总

### 19.3 回归测试

- A2A 具名 Agent 执行不受影响
- AgentRegistry 相关功能不受影响
- `system.list-capabilities` / `system.explain` 保持原有语义

---

## 20. 实施顺序

### 第一批：协议与模型

- 重写 `spawn_workers` 输入输出协议
- 引入强类型请求/响应模型
- 增加 `WorkerMode` 与 `WorkerOutputMode`

### 第二批：执行链路

- 新增 `ParallelWorkerExecutor`
- 拆出 `WorkerPromptComposer` 与 `WorkerTaskValidator`
- `SpawnWorkersToolFactory` 改为只做编排

### 第三批：Artifact 与调试

- 引入 `WorkerArtifactStore`
- 接通工作区 Artifact
- 增加聚合调试结果

### 第四批：预算与观测

- 完善 Worker 资源统计
- 补齐 Trace 父子关联
- 前端调试面板支持 Worker 展示

### 第五批：测试与文档

- 补齐单测、集成测试、回归测试
- 更新 `multi-agent` 架构与特性文档

---

## 21. 最终验收标准

满足以下条件，才视为方案完成：

1. `spawn_workers` 的语义明确是临时并行分身，而不是具名子 Agent。
2. 主 Agent 调用 `spawn_workers` 时只需指定任务规格，不需要指定 `agentId`。
3. Worker 可在受控预算、受控工具、受控上下文下并行执行。
4. Worker 完整结果默认写入 Artifact，主 Agent 默认只拿摘要与引用。
5. Worker 的 `traceId / tokensUsed / durationMs / terminationReason` 均可观测。
6. 设计上不再依赖 `AgentDefinition`，从而避免重新滑回 `handoff` 模型。

---

## 22. 结论

`spawn_workers` 的正确方向，不是“把 handoff 换个名字做回来”，而是让主 Agent 在复杂任务中拥有受控、可调试、可观测的临时并行执行能力。

完成后，系统中的边界会更清晰：

- 主 Agent：唯一用户代理与最终控制者
- `spawn_workers`：临时并行分身能力
- `Skill`：方法论增强
- `AgentDefinition`：独立长期 Agent 资产，仅服务 A2A / 后台 / 市场

这更符合个人助手项目的定位，也更符合你最初做 `spawn_workers` 的目的。
