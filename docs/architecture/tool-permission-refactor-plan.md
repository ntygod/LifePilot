# 工具权限模型重构方案

## 背景

当前工具权限模型存在三类核心问题：

1. 默认访问控制过紧  
   `GuardrailEngine.checkToolCall(...)` 会先检查 `allowedTools`，不在白名单内直接拦截。对于个人助手这种通用场景，这会把“能不能调用工具”的复杂度暴露给用户。

2. 高风险确认模型只适合交互式会话  
   `ToolExecutionPipeline` 在护栏阶段统一走 `UserConfirmationService.requestConfirmation(...)`。  
   这对 Web 对话可行，但对 `cron` / `heartbeat` 这类自主执行场景天然不成立。

3. 访问控制、风险控制、定时任务授权混在一起  
   当前系统把“工具是否可见”“工具是否需要确认”“定时任务是否允许自动执行”混在同一条链里，导致：
   - 复杂任务频繁弹确认
   - 定时任务遇到高风险工具卡住
   - 白名单配置难以理解

本次重构目标不是“完全无保护地放开”，而是改成：

**默认开放工具访问 + 基于风险和作用域授权 + 自主任务预授权**

这套模型更符合个人助手定位，也更适合长期演进。

### 实施约束

本次实施明确采用“最终态直改”原则：

- 不考虑向后兼容
- 不保留过渡态接口
- 不保留双链路判定
- 不引入长期适配层
- 以最终效果和代码整洁为第一目标

因此，后续实施中如果出现：

- 旧白名单与新权限模型并存
- 旧确认服务与新授权服务并存
- 旧事件名和新事件名双写
- 旧 API 和新 API 双维护

都应视为不符合本方案约束，除非只是同一提交中的极短暂编辑过程，且在提交前已清理干净。

---

## 当前实现问题定位

### 1. 访问控制过早、过粗

当前关键代码：

- `ToolExecutionPipeline`
- `GuardrailEngine.checkToolCall(...)`
- `ToolBridgeAgentToolProvider.getToolCallbacks(...)`
- `AgentRequest.allowedToolIds`

现状：

- `GuardrailEngine` 维护全局 `allowedTools`
- 工具不在白名单中时直接 `Blocked`
- `ToolBridgeAgentToolProvider` 还会按 `state.allowedToolIds()` 做一轮过滤

结果：

- “工具不可用”与“工具高风险”被混成一件事
- 用户必须理解工具 ID 和工具白名单
- Agent 很难自然地完成复杂任务

### 2. 确认机制只支持即时交互

当前关键代码：

- `UserConfirmationService`
- `WebUserConfirmationService`
- `ToolExecutionPipeline`

现状：

- `HIGH / CRITICAL` 风险工具会触发 `NeedsConfirmation`
- Web 通过 SSE 推送确认请求，再阻塞等待用户响应

结果：

- 复杂任务会频繁打断
- `cron / heartbeat` 没有人可以点击确认
- 工具权限无法沉淀为长期授权

### 3. 风险控制没有绑定资源作用域

当前风险控制主要依赖：

- `RiskLevel`
- `ToolRiskPolicy`
- `trustedWorkspace` 降级

现状：

- 风险等级主要按工具 ID 或工具自身声明决定
- 很少区分“对哪个目录 / 仓库 / 网站 / 资源”执行

结果：

- “允许读当前项目文件”和“允许删任意路径文件”都容易落在同一工具层判断
- 用户真正关心的“范围”没有成为一等公民

---

## 最终形态

### 总体原则

最终形态明确采用以下 5 条原则：

1. **工具默认可见**
   普通用户不再维护工具白名单。

2. **权限判断按“操作 + 作用域 + 风险”进行**
   不是按工具 ID 生硬放行/阻止。

3. **交互式对话使用“授权”而不是“每次确认”**
   对高风险工具，默认先创建授权，再执行。

4. **自主执行必须预授权**
   `cron / heartbeat / workflow` 运行时不能依赖即时确认。

5. **CRITICAL 风险默认禁止自主执行**
   即使用户授权，也不直接进入完全无人值守模式，除非后续引入非常明确的特例机制。

---

## 新权限模型

### 1. 工具访问模型

默认情况下：

- 所有注册工具都可以被 Agent 看见
- 不再要求用户配置白名单
- “工具是否暴露给模型”不再由用户设置控制

保留的能力：

- 系统内部仍可做工具范围裁剪
- 但只用于内部编排，不再作为普通用户设置

因此：

- `AgentRequest.allowedToolIds` 不再承担用户权限职责
- 它仅保留为内部执行作用域限制
  - 子 Agent
  - 测试 Agent
  - 受限系统任务

如果后续命名允许调整，建议最终改名为：

- `toolScope`
- 或 `visibleToolIds`

避免和“授权”语义混淆。

### 2. 风险模型

保留现有 `RiskLevel` 四级结构，但改变执行语义：

- `LOW`
  - 直接执行
  - 无需授权

- `MEDIUM`
  - 直接执行
  - 记录审计日志

- `HIGH`
  - 交互模式：需要授权
  - 自主模式：只有存在有效预授权时才允许执行

- `CRITICAL`
  - 交互模式：强授权
  - 自主模式：默认禁止

重点变化：

- 不再把 `HIGH / CRITICAL` 统一做成“临时确认弹窗”
- 改成“是否存在有效授权”

### 3. 授权模型

引入正式的一等公民：`ExecutionGrant`

建议新增核心模型：

- `PermissionRequest`
  - 本次工具调用的权限请求
- `ExecutionGrant`
  - 已批准的授权记录
- `ExecutionGrantScope`
  - 授权适用范围

#### PermissionRequest

运行时从以下信息构造：

- `toolId`
- `riskLevel`
- `channel`
- `actionType`
- `resourceScope`
- `sessionId`
- `traceId`
- `workspaceId`
- `taskId`（如 cron / workflow）

#### actionType

不要直接暴露工具 ID 给用户，而是归一到可理解的操作类型，例如：

- `READ_FILE`
- `WRITE_FILE`
- `DELETE_FILE`
- `EXECUTE_SHELL`
- `BROWSER_AUTOMATION`
- `HTTP_REQUEST`
- `WRITE_MEMORY`
- `MODIFY_DATASTORE`
- `CREATE_SCHEDULE`

#### resourceScope

作用域必须成为权限判断的核心输入。典型作用域：

- 文件路径
- 工作区根目录
- Git 仓库路径
- 外部域名
- 第三方集成标识
- datastore collection
- cron task id

示例：

- Shell：`workspacePath = D:\WorkSpace\Project\News`
- File：`path = D:\WorkSpace\Project\News\src\...`
- HTTP：`host = api.github.com`
- Browser：`origin = https://github.com`

#### ExecutionGrant

授权记录至少包含：

- `id`
- `subjectType`
- `subjectId`
- `actionType`
- `riskCeiling`
- `scopeJson`
- `channels`
- `autonomousAllowed`
- `expiresAt`
- `createdAt`
- `createdBy`
- `sourceEntryId`
- `reason`

#### subjectType

建议支持：

- `SESSION`
- `WORKSPACE`
- `TASK`
- `USER`

最终使用策略：

- 日常对话默认用 `SESSION` 或 `WORKSPACE`
- 定时任务必须绑定 `TASK`
- 极少数长期偏好才使用 `USER`

---

## 执行语义

### 1. 交互式对话

对 `web / feishu / wecom / dingtalk` 等交互式渠道：

- `LOW / MEDIUM`：直接执行
- `HIGH / CRITICAL`：
  - 先检查是否已有匹配授权
  - 有授权：直接执行
  - 无授权：发起授权请求

授权请求不再只是“是否确认这一次”，而是要求用户选择授权范围：

- 仅本次执行
- 当前会话
- 当前工作区 / 当前项目
- 当前类操作

默认推荐项：

- `HIGH`：当前会话或当前工作区
- `CRITICAL`：仅本次执行

### 2. 定时任务 / 心跳巡检

对 `cron / heartbeat`：

- 不允许进入阻塞式人工确认
- 所有高风险执行必须命中预授权

执行规则：

- `LOW / MEDIUM`：直接执行
- `HIGH`：仅在存在 `autonomousAllowed=true` 的匹配授权时执行
- `CRITICAL`：默认拒绝执行

如果没有授权：

- 任务本轮执行失败
- 写入清晰的结果和审计日志
- 可以通知用户“任务缺少授权，需要重新授权”

### 3. 工作流执行

对 workflow：

- 交互式 workflow：跟随当前会话授权
- 无人值守 workflow：等同自主执行

如果 workflow 未来支持后台运行，也应复用同一套授权模型，而不是另起一套审批配置。

---

## 渠道适配策略

权限核心逻辑必须对所有渠道统一生效，但不同渠道的交互能力不同，因此只允许在“授权交互方式”上做适配，不允许在“权限判断规则”上分叉。

### 渠道分类

建议将渠道划分为三类：

- `interactive-rich`
  - `web`
- `interactive-lite`
  - `feishu`
  - `wecom`
  - `dingtalk`
- `autonomous`
  - `cron`
  - `heartbeat`
  - 后台 workflow

### 统一规则

所有渠道都必须共用：

- 同一套 `PermissionRequest`
- 同一套 `ExecutionGrant`
- 同一套风险等级与审计模型
- 同一套 `PermissionEvaluator`

不能出现：

- Web 走新权限模型
- 飞书 / 企微继续走旧确认模型
- cron 单独走另一套降级逻辑

这会重新制造权限语义分裂。

### 1. Web

`web` 是完整交互渠道，应作为权限交互的主实现。

支持：

- 本次授权
- 当前会话授权
- 当前工作区 / 当前项目授权
- 当前任务授权
- 查看、撤销既有授权

因此：

- Web 负责完整授权管理页
- Web 负责最完整的授权弹窗
- 其他渠道在需要复杂授权时可以引导回 Web

### 2. 飞书 / 企微 / 钉钉

这些渠道也必须执行同样的权限判断，但交互上应做轻量化。

建议支持：

- 本次授权
- 当前会话授权

不建议在 IM 渠道中直接支持：

- 复杂工作区选择
- 大范围长期授权编辑
- 授权列表管理

这些操作更适合回到 Web。

因此：

- 三方渠道收到高风险请求时，先检查 grant
- 无授权时，可在消息中发起轻量批准动作
- 若需要复杂授权范围，则返回“请到 Web 完成授权”

### 3. cron / heartbeat

这些渠道不允许进入交互式授权。

执行规则固定为：

- `LOW / MEDIUM`：直接执行
- `HIGH`：仅命中预授权时执行
- `CRITICAL`：默认拒绝

没有授权时：

- 本轮任务失败
- 写清晰日志
- 生成可追踪的权限缺失事件
- 可通知用户去 Web 补授权

### 4. workflow

workflow 不单独发明新规则，而是跟随运行方式：

- 前台交互式 workflow
  - 继承当前会话授权
- 后台 / 定时 / 无人值守 workflow
  - 视为 `autonomous`

如果 workflow 的执行环境切换了，授权模式也随之切换，不允许保持旧授权语义。

### 5. 渠道实现边界

建议新增一个轻量抽象：

- `PermissionInteractionAdapter`

按渠道提供不同实现：

- `WebPermissionInteractionAdapter`
- `FeishuPermissionInteractionAdapter`
- `WecomPermissionInteractionAdapter`
- `DingtalkPermissionInteractionAdapter`
- `NoopAutonomousPermissionInteractionAdapter`

它们只负责：

- 如何向用户发起授权请求
- 如何接收授权结果
- 如何提示用户去 Web 完成复杂授权

它们不负责权限判定本身。

### 6. 最终约束

最终必须满足：

1. 任意渠道触发同一高风险操作，风险判断结果一致。
2. 不同渠道只允许授权交互能力不同，不允许授权语义不同。
3. `cron / heartbeat` 永远不等待即时确认。
4. Web 是唯一完整授权管理入口。

---

## 前端与用户体验

### 1. 删除白名单式工具设置

普通用户侧不再暴露：

- 工具 ID 白名单
- `allowedToolIds`
- 逐工具显式可见性配置

这类配置过于工程化，不符合个人助手产品定位。

### 2. 新的用户权限设置

前端设置改成“能力与范围”视图，而不是工具视图。

建议展示为以下几类：

- 文件与项目
  - 允许修改当前项目文件
  - 允许删除文件
  - 允许执行 Shell

- 网络与浏览器
  - 允许访问外部网站
  - 允许自动化浏览器操作
  - 允许发送 HTTP 请求

- 自主执行
  - 允许定时任务联网
  - 允许定时任务修改文件
  - 禁止定时任务执行高危操作

- 数据与记忆
  - 允许创建/修改 datastore 数据
  - 允许写入记忆

这些设置本质上是创建或修改 `ExecutionGrant` 模板，而不是直接开关工具。

### 3. 授权弹窗

授权弹窗必须从“确认一次”改成“选择授权范围”。

示例文案：

- 微微想执行 Shell 命令修改当前项目文件
- 作用范围：`D:\WorkSpace\Project\News`
- 风险等级：`HIGH`
- 请选择授权方式：
  - 仅本次
  - 当前会话
  - 当前项目

对定时任务：

- 在“创建任务”或“保存任务”时统一收集授权
- 不在运行时弹确认

---

## 后端重构方案

### 阶段 1：引入权限领域模型

新增模块建议：

- `permission/model`
- `permission/repository`
- `permission/service`

建议新增类：

- `PermissionRequest`
- `ExecutionGrant`
- `ExecutionGrantScope`
- `PermissionDecision`
- `PermissionService`
- `PermissionEvaluator`

数据库建议新增：

- `execution_grants`
- `permission_decisions`

### 阶段 2：替换当前确认链

当前：

- `ToolExecutionPipeline -> GuardrailEngine -> UserConfirmationService`

目标：

- `ToolExecutionPipeline -> PermissionEvaluator`

新流程：

1. 工具解析
2. 参数校验
3. 风险识别
4. 构造 `PermissionRequest`
5. 检查现有 grant
6. 决定：
   - 通过
   - 发起授权请求
   - 阻止

`UserConfirmationService` 最终应退出主语义位，替换为：

- `PermissionApprovalService`

Web 端现有 `WebUserConfirmationService` 应重构为：

- `WebPermissionApprovalService`

### 阶段 3：从护栏引擎移除工具白名单

当前问题点：

- `GuardrailEngine.allowedTools`
- `addAllowedTools(...)`
- `removeAllowedTools(...)`
- `checkToolCall(...)` 里“工具不在白名单中，禁止调用”

最终处理：

- 删除面向用户的白名单访问控制
- `GuardrailEngine` 保留“风险 / 预算 / 内容安全 / 速率限制”
- 工具可见性收回到内部执行层，不再当作用户权限模型的一部分

### 阶段 4：收口 Agent 侧工具作用域

当前：

- `AgentRequest.allowedToolIds`
- `ToolBridgeAgentToolProvider` 根据 `allowedToolIds` 过滤工具

最终：

- 仅内部场景保留该能力
- 不再与用户权限混用
- 所有用户权限由 `PermissionEvaluator` 负责

### 阶段 5：任务预授权

需要补一条正式链：

- 创建 / 编辑 cron 任务时
- 分析任务涉及的潜在高风险操作
- 绑定 `task-scoped grants`

实现方式建议：

1. `cron.create / cron.update` 时接收授权配置
2. 持久化到 `execution_grants`
3. 运行时按 `taskId + channel=cron` 匹配授权

如果任务脚本或 prompt 发生重大变化，应重新校验授权有效性。

---

## 与现有风险控制的关系

### 保留的部分

- `RiskLevel`
- `ContentSafetyPolicy`
- `BudgetLimitPolicy`
- `RateLimitPolicy`
- 审计日志与 trace 记录

### 要调整的部分

- `ToolRiskPolicy`
  - 继续用于“判定风险”
  - 不再直接等价于“是否弹确认”

- `trustedWorkspace`
  - 可以保留
  - 但它只能作为风险降级因子
  - 不能替代正式授权模型

---

## 最终 API 形态

建议新增独立接口：

- `GET /api/permissions/grants`
- `POST /api/permissions/check`
- `POST /api/permissions/approve`
- `DELETE /api/permissions/grants/{id}`

任务授权相关：

- `POST /api/tasks/{id}/grants`
- `GET /api/tasks/{id}/grants`

当前确认接口未来应逐步退役，不再以“tool confirmation”命名暴露给前端。

---

## 迁移与清理

本次重构不考虑兼容性，最终需要做干净：

### 需要删除或退位的旧概念

- 用户工具白名单
- `GuardrailEngine.allowedTools`
- “不在白名单直接拦截”逻辑
- 运行时高风险工具逐次确认模型
- 定时任务运行时确认

### 需要保留但重命名或降级的概念

- `allowedToolIds`
  - 退为内部可见工具范围
- `trustedWorkspace`
  - 退为风险辅助因子

---

## 验收标准

重构完成后，必须满足以下标准：

1. 普通用户无需配置工具白名单，也能完成复杂任务。
2. 高频复杂任务不会因为多次 `HIGH` 工具调用被连续打断确认。
3. 定时任务不再在执行时等待人工确认。
4. 高风险自主执行必须依赖预授权。
5. `CRITICAL` 风险默认不进入无人值守执行。
6. 前端权限设置以“能力/范围”表达，而不是工具 ID。
7. 审计日志仍然能完整追踪：
   - 风险判断
   - 授权来源
   - 执行结果

---

## 建议执行顺序

1. 新建权限领域模型和数据库表
2. 引入 `PermissionEvaluator`
3. 替换 `UserConfirmationService` 主链
4. 删除 `GuardrailEngine.allowedTools` 白名单拦截
5. 打通任务预授权
6. 重做前端权限设置和授权弹窗
7. 清理旧确认接口、旧白名单配置、旧文案

---

## 实施计划

以下实施计划按“先打权限主链，再收 UI 和任务授权”的顺序执行。每个阶段都要求代码可运行、测试可回归，不保留长期兼容层。

### 阶段 1：权限领域建模

目标：

- 新建权限领域模型与存储
- 不接主执行链
- 先把数据结构和判定抽象建立起来

改动范围：

- 新增 `permission/model`
- 新增 `permission/repository`
- 新增 `permission/service`
- 新增数据库表：
  - `execution_grants`
  - `permission_decisions`

建议新增类：

- `PermissionRequest`
- `ExecutionGrant`
- `ExecutionGrantScope`
- `PermissionDecision`
- `PermissionService`
- `PermissionEvaluator`

完成标准：

- 可以创建、查询、撤销 grant
- 可以对一条 `PermissionRequest` 做纯内存 / 数据库判定
- 不影响现有工具执行链

测试：

- repository 集成测试
- `PermissionEvaluator` 单元测试
- grant 匹配规则测试

### 阶段 2：接入工具执行主链

目标：

- 在 `ToolExecutionPipeline` 中引入权限判定
- 直接替换旧确认判定主链

改动范围：

- `ToolExecutionPipeline`
- `GuardrailEngine`
- `ToolBridgeAgentToolProvider`

实施方式：

1. 工具解析后构造 `PermissionRequest`
2. 由 `PermissionEvaluator` 返回：
   - `PASSED`
   - `NEEDS_APPROVAL`
   - `BLOCKED`
3. 删除旧的逐次确认主语义，主链只保留新的权限决策

完成标准：

- `LOW / MEDIUM` 能直接执行
- `HIGH / CRITICAL` 可以命中已有 grant 后执行
- 主链中不再保留旧确认语义分支

测试：

- `ToolExecutionPipeline` 集成测试
- 风险等级到权限决策映射测试
- 带 context 的工具执行测试

### 阶段 3：替换确认服务为授权服务

目标：

- 正式淘汰“每次确认”语义
- 改为“授权请求 / 授权结果”

改动范围：

- `UserConfirmationService`
- `WebUserConfirmationService`
- 相关 controller / SSE 事件
- 前端确认弹窗与 signal

重构方向：

- `UserConfirmationService` 替换为 `PermissionApprovalService`
- `WebUserConfirmationService` 替换为 `WebPermissionApprovalService`
- transcript 中记录 `permission-approval` 事件，而不是 `tool-confirmation`

完成标准：

- Web 可以对高风险操作创建授权
- 授权可以按作用域保存
- 工具执行命中授权后不再重复弹窗
- 主代码中不再存在旧 `tool-confirmation` 主语义

测试：

- 授权请求/批准/拒绝链路测试
- transcript 持久化测试
- 前端授权交互测试

### 阶段 4：删除白名单访问控制

目标：

- 移除面向用户的工具白名单模型
- 工具默认可见

改动范围：

- `GuardrailEngine.allowedTools`
- `addAllowedTools(...)`
- `removeAllowedTools(...)`
- `checkToolCall(...)` 中白名单拦截分支

同时收口：

- `AgentRequest.allowedToolIds`
- `ToolBridgeAgentToolProvider` 的过滤逻辑

处理原则：

- 用户侧白名单删除
- 内部 `allowedToolIds` 仅保留给受限执行场景

完成标准：

- 普通会话不再因未配置白名单而阻止工具调用
- 子 Agent / 测试 Agent 仍可内部限制工具可见范围

测试：

- 默认全工具可见测试
- 内部受限工具范围测试
- 白名单逻辑删除后的回归测试

### 阶段 5：定时任务预授权

目标：

- `cron / heartbeat / autonomous workflow` 只认预授权
- 运行时不再等待确认

改动范围：

- `cron.create / cron.update`
- 任务存储与任务执行链
- 权限匹配逻辑

实施方式：

1. 创建/编辑任务时写入 `task-scoped grant`
2. 任务执行时带 `taskId` 构造 `PermissionRequest`
3. `PermissionEvaluator` 只认 `autonomousAllowed=true`

完成标准：

- `HIGH` 风险任务在有预授权时自动执行
- 无授权时明确失败并记录原因
- `CRITICAL` 风险默认拒绝

测试：

- cron 授权创建测试
- cron 运行时命中 grant 测试
- 缺失授权失败测试

### 阶段 6：渠道适配

目标：

- Web、飞书、企微、钉钉、自主渠道全部接入同一套权限主链

改动范围：

- 新增 `PermissionInteractionAdapter`
- 各渠道 adapter 实现

建议实现：

- `WebPermissionInteractionAdapter`
- `FeishuPermissionInteractionAdapter`
- `WecomPermissionInteractionAdapter`
- `DingtalkPermissionInteractionAdapter`
- `NoopAutonomousPermissionInteractionAdapter`

完成标准：

- 各渠道风险判定一致
- 非 Web 渠道仅做轻量授权
- 自主渠道不发起交互授权

测试：

- 渠道适配单元测试
- 不同渠道同一请求权限结果一致性测试

### 阶段 7：前端权限管理与清理

目标：

- 完成新权限 UI
- 删除旧白名单 / 旧确认遗留

改动范围：

- 设置页
- 聊天授权弹窗
- 三方渠道授权回调前端约定
- 类型定义与 API client

完成标准：

- 设置页只展示“能力与范围”，不展示工具 ID 白名单
- 高风险请求可选择授权范围
- 不再出现旧的 `tool-confirmation` 用户概念

测试：

- 前端权限管理测试
- API 合约测试
- 构建与端到端主链回归

### 阶段 8：最终清理

目标：

- 删除所有旧权限模型残留
- 文案、事件名、表字段统一到新模型

需要清理：

- `tool-confirmation`
- `UserConfirmationService`
- 用户工具白名单相关设置
- 逐次确认旧文案

完成标准：

- 主代码中不再保留旧权限模型残留
- 权限主链只有一套实现
- 不存在兼容分支、双写逻辑或双接口维护

---

## 结论

对这个项目来说，“工具默认放开”是对的方向，但必须配合新的授权模型一起落地。

最终应从：

**白名单 + 每次高风险确认**

切换为：

**默认可用 + 风险分级 + 作用域授权 + 自主任务预授权**

这能同时解决：

- 复杂任务被频繁确认打断
- 定时任务无法确认
- 白名单配置复杂

并且不会把系统带到完全裸奔的状态。
