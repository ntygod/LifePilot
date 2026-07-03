---
name: feishu
description: 当用户要在飞书上发消息、创建任务、操作文档、管理日程或群组时使用。
version: 2.1.2
metadata:
  zhiwei:
    tags:
      - feishu
      - 飞书
      - lark
      - im
      - notification
    suggested_tools:
      - channel.feishu
    outputs:
      - notification
      - task
      - text
---
# 飞书集成指南

`channel.feishu` 工具按 `action` 路由飞书各类操作，必须传 `instanceId`（用户配置过的飞书渠道实例）。

## 触发判断
- 飞书消息发送（个人 / 群组 / 富文本卡片）
- 飞书任务创建与管理
- 飞书文档创建 / 分享 / 内容操作
- 飞书日程查询 / 创建
- 飞书群管理（创建 / 加成员 / 设置）

不要触发：

- 邮件发送 → `shell.exec` + curl
- 钉钉 / 企业微信 → 对应渠道 Skill
- 纯文档撰写不发飞书 → content-creator
- 知微对话内通知用户 → 直接说就行（或 `notify`）

## 决策路径

| 用户表达 | 路径 |
|---|---|
| 发消息 / 卡片 / 回复 / 改 / 撤 | `send_message` / `send_card` / `reply_message` / `update_message` / `recall_message` |
| 上传 / 下载文件 | `upload_file` / `download_file` |
| 任务 / 文档 / 日程 | `create_task` / `create_document` / `create_calendar_event` |
| 建群 / 改群成员 | `create_group` / `manage_members` |

各路径要点：

- **instanceId 优先**：未配置直接提示用户去渠道实例配置页，不要伪造、不要重试
- **收件人类型对齐**：`receiveIdType` 必须和 `targetId` 实际类型一致（chat_id / open_id / user_id），错配会直接 4xx
- **二次确认**：撤回消息、创建群、改群成员、含敏感信息的发送，发起前先把目标 + 内容摘要给用户、等确认
- **失败给原因**：限流 / 权限不足 / 收件人不存在分别有不同错误码，参考表里有对照


## 输出标准

- 输出飞书动作类型、目标会话/文档/日程/任务、发送或创建结果。
- 发送消息前展示摘要；批量或群发必须列出目标并等待确认。
- 创建任务/日程时返回标题、负责人、时间和链接或 ID。


## 失败策略

- 缺少 instanceId、权限或目标对象时先追问或引导配置。
- API 返回权限/限流错误时说明原因和重试条件。
- 涉及外发敏感内容时先脱敏并要求确认。

## 详细参考
- 完整 action 参数清单与示例：`{skill_dir}/references/actions-reference.md`
