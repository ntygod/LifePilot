---
name: feishu
description: 当用户要在飞书上发消息、创建任务、操作文档、管理日程或群组时使用。关键词：飞书、发飞书消息、飞书通知、飞书群、飞书任务、飞书文档、飞书日程、lark。钉钉操作不在本 Skill 范围，纯邮件用 shell.exec + curl。
version: 2.0.0
metadata:
  zhiwei:
    category: external-integration
    priority: normal
    tags:
      - feishu
      - 飞书
      - lark
      - im
      - notification
    suggested_tools:
      - channel.feishu
---

# 飞书集成指南

通过 `channel.feishu` 工具管理飞书消息、任务、文档和日程。所有操作通过 `action` 参数路由，必须指定 `instanceId`。

## 适用场景

- 发送飞书消息（个人/群组）
- 创建或管理飞书任务
- 操作飞书文档（创建/分享）
- 查询或创建日程
- 管理飞书群组

## 不适用场景

- 发送邮件 → 直接用 `shell.exec` + curl
- 非飞书的即时通讯 → 使用对应渠道 Skill
- 纯文档编辑不涉及飞书 → 用 content-creator

## 工作流

1. **确认 instanceId**：必须是已配置的飞书渠道实例，未配置先提示用户去配置页
2. **选 action**：消息 / 卡片 / 任务 / 文档 / 日程 / 群组，参数清单见参考
3. **发送前**：确认收件人和内容
4. **高风险操作**（创建群聊、管理群成员、撤回消息）必须用户二次确认

## 详细参考

- 完整 action 参数清单与示例：`{skill_dir}/references/actions-reference.md`
</content>
</invoke>