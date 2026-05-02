5 种路径的真实场景示例与分流判据。所有"→"是建议步骤，根据用户实际表述判断要不要省。

## 1. 主动规划

判据：用户问"今天做什么 / 帮我列一下 / 安排一下"。

```
memory(action="search")  → 拿当前目标 / 已有待办
   ↓
按紧急-重要矩阵分档，列 3-5 句给用户对
   ↓ 用户确认
memory(action="create")  每条任务带截止日 / 优先级 / 依赖
```

错例：用户没说"规划"只说"我有件事"——这是声明意图，应该 `memory(action="create")` 记一笔不进规划流程。

## 2. 调整既有

判据：用户说"把 X 挪到明天 / 改成高优 / 这个不做了"。

```
memory(action="search", query="<目标任务关键词>")  → 找到任务实体
   ↓ 跟用户确认是哪一条
memory(action="update")  改字段 / memory(action="delete")  撤销
```

错例：用户没指明哪条但有歧义（同名多条）——必须先列出候选让用户选，不要默认改最近一条。

## 3. 进度回顾

判据：用户问"我做到哪了 / 昨天做了啥 / 本周完成了什么"。

```
memory(action="search", query="...")  +  memory(action="recall", query="...")
   ↓
按时间 / 优先级排版给用户，**不主动 write**（除非用户明说"记一下"）
```

注意：跨天回顾时 `memory(action="query-at-time")` 拿历史时点状态比 search 更准。

## 4. 跨领域协作

典型场景："调研 X 然后写一份报告 / 读这份数据然后画图"。

```
skill_load(names=["research-assistant", "content-creator"])
   ↓ 按依赖串行
research-assistant 出材料 → content-creator 写 → file_write 落盘
```

中途任意子步骤报错 → 停下问用户怎么处理，不要假装继续。
反馈环（审查→修→再审查）必须有硬上限（如 ≤2 轮），超限停下问用户。

## 5. 报告生成

典型场景："生成本周周报 / 整理这个月做了什么"。

```
memory(action="search", query="本周完成事项")  取记忆里的成就 / 项目进度
file_read  读用户笔记 / 项目文档（路径靠用户给或先 file_list 探）
按需 web_search 补外部背景（团队动态 / 行业事件）
   ↓ 整合
file_write(path="<reports/...>")  落盘并把路径告诉用户
```

## 常见错误处理

- 任务彼此冲突 / 优先级矛盾 → 列出冲突点让用户决断
- 被 load 的子 Skill 不可用（bin / env 不满足）→ 降级为手动步骤指导，告诉用户缺什么
- 信息明显不足 → 主动追问而不是凭猜继续
