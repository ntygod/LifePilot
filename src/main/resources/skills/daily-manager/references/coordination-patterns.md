- 三种协调模式的典型链路与边界。所有"->"是建议的步骤顺序，**不是必须照搬**——根据用户实际表述判断要不要省步骤。

## 任务规划

典型场景："今天做什么 / 帮我安排一下 / 列下本周要干的事"

步骤大致：
1. `memory(action="search")` 拿当前目标 / 已有待办（如果用户没特别提，按用户全局 profile 取）
2. 给用户 1-3 句拆解结果（"今天我看到 3 件事，按紧急排是 ..."）等点头
3. 用户确认后 `memory(action="create")` 把每条任务记下来（含截止日 / 优先级）

## 跨 Skill 协调

典型场景："调研 X 技术 + 写报告 + 保存到文件"

步骤大致：
1. 拆出涉及的专业 Skill（这里：research-assistant + content-creator）
2. `skill.load(names=["research-assistant","content-creator"])` 一次加载
3. 按依赖跑：先调研产出材料 → 让 content-creator 写 → 落盘 → 必要时通知用户
4. 中途任意子步骤报错就停下问用户怎么处理，不要假装继续

工具调用形态参考：
- `web.search(...)` 取调研材料
- `file.write(path="reports/<topic>.md", content=...)` 落盘
- `notify.send_message(message=...)` 仅在用户已离开当前会话时用，会话内回复直接说就够

## 信息汇总（日报/周报/月报）

典型场景："生成本周周报 / 整理这个月做了什么"

步骤大致：
1. `memory(action="search", query="本周完成事项")` 取记忆里的成就 / 项目进度
2. `file.read` 读用户的笔记 / 项目文档（路径靠用户给或先 file.list 探）
3. 必要时 `web.search` 补外部背景（如团队动态、行业事件）
4. 整合后 `file.write` 写汇总文件，把路径告诉用户

## 常见错误处理

- **任务彼此冲突 / 优先级矛盾** → 列出冲突点让用户决断
- **被 Load 的子 Skill 不可用**（依赖 bin / env 不满足）→ 降级为手动步骤指导，告诉用户缺什么
- **信息明显不足** → 主动追问而不是凭猜继续
