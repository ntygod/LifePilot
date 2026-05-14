# 记忆模块手动测试流程（未覆盖场景）

> 前置条件：应用运行在 localhost:8080，embedding 模型在 8081，LLM 可用。
> 所有 curl 命令在 PowerShell 中执行，使用 Invoke-RestMethod。
> 对话接口统一使用 POST /api/chat/messages/stream。

---

## A. 遗忘引擎

### A.1 手动触发遗忘

遗忘引擎没有暴露 REST API，但可以通过修改 cron 配置让它立即触发。

**方法 1：修改 application.yml 后重启**

```yaml
lifepilot:
  memory:
    forgetting:
      cron: "0 * * * * *"  # 每分钟触发一次（测试完改回）
```

**方法 2：通过对话让 Agent 调用内部方法**（如果有 shell 工具）

**验证步骤：**

1. 先创建一个低重要度实体：
   ```
   对话："随便记一下，今天中午吃了麻辣烫"
   ```

2. 确认实体存在：
   ```
   GET http://localhost:8080/api/memories/search?q=麻辣烫
   ```
   期望：返回结果

3. 修改 cron 为每分钟触发，重启应用，等待 1 分钟

4. 再次搜索：
   ```
   GET http://localhost:8080/api/memories/search?q=麻辣烫
   ```
   期望：如果 importance 低且 accessCount=0，应被归档（搜不到）

5. 检查遗忘日志：
   ```
   GET http://localhost:8080/api/memories/forgetting-logs
   ```
   期望：有新的遗忘记录

### A.2 保护机制验证

1. 确认 PREFERENCE/HABIT/GOAL 类型实体不被遗忘：
   ```
   GET http://localhost:8080/api/memories/entities?type=PREFERENCE
   ```
   这些实体即使 accessCount=0 也不应出现在遗忘日志中

2. 确认高频实体不被遗忘：
   - 多次搜索某实体（让 accessCount 增加到 10+）
   - 触发遗忘后该实体仍存在

---

## B. 反馈闭环

### B.1 获取可反馈的 entryId

1. 发送一条对话，记录返回的 entryId：
   ```powershell
   $body = @{content="我最喜欢的颜色是蓝色";sessionId="<session-id>"} | ConvertTo-Json
   $resp = Invoke-WebRequest -Uri "http://localhost:8080/api/chat/messages/stream" -Method Post -ContentType "application/json" -Body $body -TimeoutSec 60
   # 从 done 事件中提取 entryId
   # 找 "entryId":"xxx" 字段
   ```

2. 从 SSE 流的 `event:done` 数据中提取 `entryId` 字段

### B.2 提交正反馈（点赞）

```powershell
$entryId = "<上一步获取的 entryId>"
Invoke-RestMethod -Uri "http://localhost:8080/api/chat/entries/$entryId/feedback" `
    -Method Post -ContentType "application/json" `
    -Body '{"type":"like","feedback":""}'
```

期望：返回 204

### B.3 提交负反馈（点踩）

```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/chat/entries/$entryId/feedback" `
    -Method Post -ContentType "application/json" `
    -Body '{"type":"dislike","feedback":"回答不准确"}'
```

期望：返回 204

### B.4 验证反馈效果

多次对同一实体相关回答点踩后：
```
GET http://localhost:8080/api/memories/entities/<entity-id>
```
期望：trustScore 应下降；累计负反馈达到阈值（3次或 score < -1.0）后实体应被 SUPERSEDED

---

## C. Overlay 语义

### C.1 准备

确保主账户有一个实体，比如"编程语言偏好: Rust"

### C.2 在隔离项目中更新继承实体

```powershell
# 1. 创建隔离项目（如果还没有）
$proj = Invoke-RestMethod -Uri "http://localhost:8080/api/projects" -Method Post `
    -ContentType "application/json" -Body '{"name":"overlay-test","isolation":"ISOLATED"}'
$projId = $proj.data.id

# 2. 创建项目会话
$sess = Invoke-RestMethod -Uri "http://localhost:8080/api/chat/sessions" -Method Post `
    -ContentType "application/json" -Body (@{title="overlay-test-session";projectId=$projId}|ConvertTo-Json)
$sessId = $sess.id

# 3. 在项目会话中更新继承实体
# 对话："在这个项目里，编程语言用 Java 17，不用 Rust"
```

### C.3 验证 Overlay 创建

```
GET http://localhost:8080/api/memories/entities?projectId=<projId>
```

期望：
- 项目视图中应有一个"编程语言"相关实体，值为 Java 17
- 这是一个 overlay 实体（overlay_entity_id 指向项目 space）

### C.4 验证主账户不受影响

在非项目会话中问："我用什么编程语言？"

期望：回答 Rust（主账户值未被修改）

---

## D. STALE_CANDIDATE 降权

### D.1 触发 Staleness 检测

1. 确保有一个旧事实，比如"住在杭州"
2. 写入一个与之冲突的新事实："我搬到广州了"
3. 等待 5-10 秒（StalenessCoordinator 异步执行）

### D.2 验证降权

```
GET http://localhost:8080/api/memories/search?q=居住地
```

期望：
- 新实体"广州"排名靠前
- 如果旧实体"杭州"仍存在，应标注 `isStale=true` 或 `lifecycleState=STALE_CANDIDATE`
- 旧实体的 fusedScore 应明显低于新实体

### D.3 通过实体详情验证

```
GET http://localhost:8080/api/memories/entities/<旧实体id>
```

期望：`lifecycleState` 为 `STALE_CANDIDATE` 或 `SUPERSEDED`

---

## E. 经验学习

### E.1 触发经验总结

经验总结在工具执行成功后异步触发。需要让 Agent 执行一个涉及工具调用的任务：

```
对话："帮我搜索一下今天杭州的天气"（如果有 web-search 工具）
或者："帮我创建一个待办事项：明天下午开会"（如果有 todo 工具）
```

### E.2 验证经验实体

等待 10-20 秒后：
```
GET http://localhost:8080/api/memories/entities?type=EXPERIENCE
```

期望：有新的 EXPERIENCE 类型实体，描述工具使用的经验

### E.3 经验合并

多次执行类似任务后触发巩固：
```
POST http://localhost:8080/api/memories/consolidate
```

等待 15 秒后检查：
```
GET http://localhost:8080/api/memories/entities?type=EXPERIENCE
```

期望：相似经验被合并为泛化元经验（实体数量减少，描述更泛化）

---

## F. 高频经验提升为 ProcedureTemplate

### F.1 前置条件

需要一个 EXPERIENCE 实体满足：
- importanceScore >= 0.8
- accessCount >= 3

可以通过多次搜索同一经验实体来增加 accessCount：
```
GET http://localhost:8080/api/memories/search?q=<经验关键词>
# 重复多次
```

### F.2 触发巩固

```
POST http://localhost:8080/api/memories/consolidate
```

### F.3 验证模板创建

```
GET http://localhost:8080/api/memories/templates
```

期望：有新的 ProcedureTemplate，其 sourceEntityId 指向原 EXPERIENCE 实体

---

## G. 提取边界

### G.1 计算请求不产生记忆

```
对话："帮我算 sqrt(144) + 37 * 2"
```

等待 5 秒后：
```
GET http://localhost:8080/api/memories/search?q=144
GET http://localhost:8080/api/memories/search?q=sqrt
```

期望：不应有与这次计算相关的长期记忆实体

### G.2 系统指令不产生记忆

```
对话："以后回答都用英文"
```

这是一个对话偏好，可能会被提取为 PREFERENCE。验证：
```
GET http://localhost:8080/api/memories/search?q=英文
```

期望：如果被提取，应标记为 PREFERENCE 类型（合理）；但不应被当作 USER_FACT

### G.3 工具结果不直接进入个人记忆

让 Agent 调用工具（如搜索天气），然后验证工具返回的具体数据（如"杭州 25°C"）不会被当作用户个人事实存入：

```
GET http://localhost:8080/api/memories/search?q=25度
```

期望：不应有"用户的温度是25度"这样的实体

---

## H. INFERRED vs EXPLICIT 消费差异

### H.1 模糊表述

```
对话："我好像对摄影有点兴趣吧"
```

### H.2 明确表述

```
对话："我确定喜欢打篮球，每周打两次"
```

### H.3 验证差异

```
GET http://localhost:8080/api/memories/entities?q=摄影
GET http://localhost:8080/api/memories/entities?q=篮球
```

期望：
- 摄影：evidenceKind=CHAT_INFERRED，trustLevel=INFERRED，trustScore 较低（~0.55）
- 篮球：evidenceKind=USER_EXPLICIT，trustLevel=EXPLICIT，trustScore 较高（~0.80）

### H.4 消费验证

新会话问："我有什么爱好？"

期望：
- 篮球应明确列出
- 摄影可能不出现（INFERRED 默认不注入热记忆），或标注"推断"

---

## I. 图遍历检索

### I.1 创建关系

通过对话建立实体间关系：
```
对话："李明是我的同事，我们一起在杭州的公司工作"
```

### I.2 验证关系存在

```
GET http://localhost:8080/api/memories/relations
```

期望：有 source=李华, target=李明, type=COLLEAGUE 或类似关系

### I.3 图遍历验证

```
GET http://localhost:8080/api/memories/entities/<李华id>/related
```

期望：通过图遍历能找到"李明"

---

## J. 多空间读取过滤

### J.1 创建多个项目

```powershell
# 项目 A
$projA = Invoke-RestMethod -Uri "http://localhost:8080/api/projects" -Method Post `
    -ContentType "application/json" -Body '{"name":"project-A","isolation":"ISOLATED"}'

# 项目 B
$projB = Invoke-RestMethod -Uri "http://localhost:8080/api/projects" -Method Post `
    -ContentType "application/json" -Body '{"name":"project-B","isolation":"ISOLATED"}'
```

### J.2 在各项目中写入不同记忆

- 项目 A 会话："这个项目用 React 前端"
- 项目 B 会话："这个项目用 Vue 前端"

### J.3 验证隔离

- 在项目 A 会话问："这个项目用什么前端框架？" → 期望：React
- 在项目 B 会话问同样问题 → 期望：Vue
- 在主账户会话问 → 期望：不提及 React 或 Vue（项目记忆不泄漏）

---

## 测试结果记录模板

| 场景 | 步骤 | 期望 | 实际 | 通过 |
|------|------|------|------|------|
| A.1 | 遗忘触发 | 低重要度实体被归档 | | |
| A.2 | 保护机制 | PREFERENCE 不被遗忘 | | |
| B.2 | 正反馈 | 返回 204 | | |
| B.4 | 负反馈效果 | trustScore 下降 | | |
| C.3 | Overlay 创建 | 项目有独立实体 | | |
| C.4 | 主账户不受影响 | 主账户值不变 | | |
| D.2 | Staleness 降权 | 旧实体分数低 | | |
| E.2 | 经验实体 | 有 EXPERIENCE 类型 | | |
| F.3 | 模板创建 | 有 ProcedureTemplate | | |
| G.1 | 计算不记忆 | 无相关实体 | | |
| H.3 | 质量差异 | INFERRED vs EXPLICIT | | |
| I.2 | 关系存在 | 有 relation 记录 | | |
| J.3 | 多项目隔离 | 各项目独立 | | |
