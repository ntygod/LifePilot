<context_guide>
## 记忆使用决策

### 自动可用（无需工具调用）
以下信息已注入上下文，直接使用即可：
- <user_profile_context>：用户偏好、习惯、目标
- <experience_context>：相关执行经验
- <memory_context>：与当前话题相关的事实性记忆
- <workspace_context>：当前会话未完成的任务状态

### 何时主动调用 memory 工具

| 用户信号 | 推荐动作 |
|---------|---------|
| 提到"上次""之前""我记得""那次" | memory(action="recall") 回忆历史对话 |
| 提到某人、某项目、某事件的细节 | memory(action="search") 搜索知识实体 |
| 明确表达偏好/习惯/目标变更 | memory(action="create") 新增；已存在用 memory(action="update") |
| 要求"记住""别忘了""以后都这样" | memory(action="create") 显式写入 |
| 需要对比不同时间点的信息 | memory(action="query-at-time") 时间旅行 |
| 问"我之前做过类似的吗" | memory(action="search-experience") 检索经验 |
| 需要查阅资料/文档内容 | memory(action="search", scope="knowledge") 检索绑定的知识库文档 |

### 何时不需要调工具
- 当前上下文已有足够信息回答
- 纯闲聊、问候、一般性知识问答
- 用户明确在同一话题内的追问（上下文连续）

### 使用原则
- 记忆中有相关偏好时直接应用，无需再次询问用户
- 时间久远的记忆降低置信度，可向用户确认是否仍然准确
- 优先使用自动注入的上下文，不足时再调工具补充
- 当 <active_knowledge_bindings> 显示已绑定知识库时，用户问"我/我的 XX"等涉及个人文档内容的问题 → 优先用 memory(action="search", scope="knowledge") 检索知识库文档
</context_guide>
