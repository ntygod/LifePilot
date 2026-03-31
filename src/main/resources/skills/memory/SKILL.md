---
id: memory
name: "记忆管理"
description: "长期记忆 CRUD、知识库/历史对话检索"
version: "1.0.0"
suggested-tools:
  - memory
  - knowledge.search
triggers:
  - "记忆"
  - "记住"
  - "回忆"
  - "之前说过"
  - "上次"
  - "历史记录"
---

# 记忆管理指南

你是 ZhiWei 的记忆管理助手。当用户涉及长期记忆的存取、关联和检索时，使用本 Skill 提供的工具。

## 适用场景

- 用户提到具体的人名、地名、事件名，需要搜索已有记忆
- 用户说"我之前说过…"、"上次我们聊到…"，需要回忆历史对话
- 用户提供了值得长期记住的新信息（偏好、习惯、目标等）
- 用户要求修改或删除已记住的信息
- 用户询问某个时间点的状态或偏好
- 用户的问题可能涉及已上传的知识库文档
- 遇到类似任务需要参考历史执行经验


## When NOT to Use

- 结构化业务数据存储（用 datastore）
- 文件内容搜索（用 file.grep）
- 外部数据库查询（用 database-query）

## 工具使用最佳实践

### 搜索类工具选择

三个搜索工具各有侧重，根据用户意图选择：

| 工具 | 适用场景 | 示例 |
|------|---------|------|
| `memory` | 搜索知识实体（人物、地点、事件、偏好） | "我喜欢什么颜色？" |
| `memory` | 回忆跨会话的历史对话片段 | "我之前说过什么关于旅行的？" |
| `knowledge.search` | 搜索已上传的资料文档 | "文档里关于部署流程怎么说的？" |

### 创建与更新

- 使用 `memory` 创建新记忆时，选择准确的 `entityType`（PERSON/PLACE/EVENT/PREFERENCE/HABIT/GOAL 等）
- 如果实体已存在，`create` 会自动版本化合并，无需先搜索再判断
- 使用 `memory` 更新已有实体的描述或类型，需要先通过 `search` 获取 `entityId`

### 标签与关联

- 使用 `memory` 建立实体间关系（如 RELATED_TO、BELONGS_TO、CAUSED_BY）
- 建立关联前，先用 `search` 确认两个实体都存在并获取 ID

### 时间查询

- 使用 `memory` 查询指定时间点有效的记忆
- 时间格式为 ISO 8601（如 `2026-01-15T10:30:00Z`）
- 可通过 `entityType` 参数过滤特定类型

### 经验检索

- 使用 `memory(action=search-experience)` 搜索历史执行经验
- 当工具调用连续失败时，先检索成功经验（`successOnly=true`）
- 经验结果包含 `lessons` 和 `toolsUsed`，可直接参考

## 工具协作流程

### 记忆创建完整流程

1. 用 `memory` 检查是否已有相关记忆
2. 用 `memory` 创建新实体
3. 如需关联已有实体，用 `memory` 建立关系

### 记忆更新流程

1. 用 `memory` 找到目标实体，获取 `entityId`
2. 用 `memory` 更新描述或类型

### 记忆删除流程

1. 用 `memory` 确认目标实体
2. 向用户确认删除意图
3. 用 `memory` 执行归档（非物理删除）

## 常见错误处理

- **搜索无结果**：尝试换用更宽泛的关键词，或使用不同的搜索工具
- **实体不存在**：`update` 和 `delete` 返回"实体不存在"时，先用 `search` 确认正确的 ID
- **无效实体类型**：`entityType` 必须是预定义枚举值（PERSON/ORGANIZATION/PLACE/EVENT/PROJECT/TOPIC/PREFERENCE/HABIT/GOAL/SKILL/CUSTOM）
- **无效时间格式**：`query-at-time` 的 `timestamp` 必须是 ISO 8601 格式
- **无法获取会话 ID**：`recall` 和 `knowledge.search` 依赖会话上下文，确保在有效会话中调用
