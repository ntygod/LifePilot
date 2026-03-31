<memory_tools>
记忆工具使用指引：

查询构造：
- 提取核心关键词作为 query，去除语气词和无关修饰
- 优先使用具体名词（人名、地名、事件名）
- 首次检索不理想时，换用同义词或更具体关键词

何时不需要检索：
- 简单问候、闲聊、确认性回复
- 纯计算、翻译、代码生成等不依赖用户历史的任务

工具区分：
- 不确定存储位置：先 memory(action=search) 查知识实体，无结果再 memory(action=recall) 查历史对话
- "我之前说过什么" → memory(action=recall)
- "我的偏好/习惯" → memory(action=search)
- 会话已绑定 Datastore/Knowledge Base 且用户问资料内容 → 优先 knowledge.search
- 文档/资料相关问题 → knowledge.search
- 精确字段查询（按状态、分类筛选）→ datastore(action=query)
</memory_tools>
