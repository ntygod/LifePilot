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

### 发送消息

```
channel.feishu(action="send_message", instanceId="feishu.default", targetId="目标ID", receiveIdType="chat_id", content="消息内容", msgType="text")
```

### 回复消息

```
channel.feishu(action="reply_message", instanceId="feishu.default", messageId="消息ID", content="回复内容", replyInThread=true)
```

### 发送交互卡片

```
channel.feishu(action="send_card", instanceId="feishu.default", targetId="目标ID", cardJson="{卡片JSON}")
```

### 上传/下载文件

```
channel.feishu(action="upload_file", instanceId="feishu.default", fileName="文件名.pdf", fileData="Base64数据", fileType="file")
channel.feishu(action="download_file", instanceId="feishu.default", fileToken="文件token")
```

### 创建任务 / 文档 / 日程

```
channel.feishu(action="create_task", instanceId="feishu.default", taskSummary="任务标题", taskDueTimestamp="截止时间戳")
channel.feishu(action="create_document", instanceId="feishu.default", documentTitle="文档标题", documentFolderToken="文件夹token")
channel.feishu(action="create_calendar_event", instanceId="feishu.default", eventSummary="日程标题", eventStartTime="2026-04-02T10:00:00+08:00", eventEndTime="2026-04-02T11:00:00+08:00")
```

### 群组管理

```
channel.feishu(action="create_group", instanceId="feishu.default", groupName="群名称")
channel.feishu(action="manage_members", instanceId="feishu.default", chatId="群ID", memberIds="m1,m2", memberAction="add")
```

## 规则

- 发送消息前必须确认收件人和内容
- `instanceId` 必须是已配置的飞书渠道实例
- 创建群聊和管理群成员需用户确认（高风险操作）
- 撤回消息需用户确认
- 遵守飞书 API 频率限制

## 常见错误处理

- **instanceId 不存在** → 提示用户检查飞书渠道配置
- **目标 ID 无效** → 确认用户 ID 或群 ID 格式
- **权限不足** → 确认飞书应用的权限范围
