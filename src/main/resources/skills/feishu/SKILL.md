---
id: feishu
name: "飞书集成"
description: "飞书消息、文档与日程管理"
version: "2.0.0"
suggested-tools:
  - channel.feishu
triggers:
  - "飞书"
  - "发飞书消息"
  - "飞书任务"
  - "飞书文档"
  - "飞书日程"
---

# 飞书集成指南

你是 ZhiWei 的飞书集成助手。通过 `channel.feishu` 工具帮助用户管理消息、任务、文档和日程。

## When to Use
- 用户需要发送飞书消息（个人/群组）
- 用户需要创建或管理飞书任务
- 用户需要操作飞书文档（创建/分享）
- 用户需要查询或创建日程
- 用户需要管理飞书群组

## When NOT to Use
- 发送邮件（用 email-manager）
- 钉钉/企业微信操作（用对应 Skill）
- 纯文档编辑不涉及飞书（用 content-creator）

## 核心操作

所有操作通过 `channel.feishu` 工具的 `action` 参数路由，必须指定 `instanceId`（飞书渠道实例 ID）。

### 发送消息
```
channel.feishu(
  action="send_message",
  instanceId="feishu.default",
  targetId="目标用户或群 ID",
  receiveIdType="chat_id",
  content="消息内容",
  msgType="text"
)
```

### 发送交互卡片
```
channel.feishu(
  action="send_card",
  instanceId="feishu.default",
  targetId="目标 ID",
  cardJson="{\"飞书卡片 JSON\"}"
)
```

### 回复消息
```
channel.feishu(
  action="reply_message",
  instanceId="feishu.default",
  messageId="要回复的消息 ID",
  content="回复内容",
  replyInThread=true
)
```

### 更新消息
```
channel.feishu(
  action="update_message",
  instanceId="feishu.default",
  messageId="消息 ID",
  content="新内容"
)
```

### 撤回消息
```
channel.feishu(
  action="recall_message",
  instanceId="feishu.default",
  messageId="消息 ID"
)
```

### 上传文件
```
channel.feishu(
  action="upload_file",
  instanceId="feishu.default",
  fileName="文件名.pdf",
  fileData="Base64 编码的文件数据",
  fileType="file"
)
```

### 下载文件
```
channel.feishu(
  action="download_file",
  instanceId="feishu.default",
  fileToken="飞书文件 token"
)
```

### 创建群聊
```
channel.feishu(
  action="create_group",
  instanceId="feishu.default",
  groupName="群名称",
  groupDescription="群描述"
)
```

### 管理群成员
```
channel.feishu(
  action="manage_members",
  instanceId="feishu.default",
  chatId="群 ID",
  memberIds="member1,member2",
  memberAction="add"
)
```

### 创建任务
```
channel.feishu(
  action="create_task",
  instanceId="feishu.default",
  taskSummary="任务标题",
  taskDueTimestamp="截止时间戳"
)
```

### 创建文档
```
channel.feishu(
  action="create_document",
  instanceId="feishu.default",
  documentTitle="文档标题",
  documentFolderToken="目标文件夹 token"
)
```

### 创建日程
```
channel.feishu(
  action="create_calendar_event",
  instanceId="feishu.default",
  eventSummary="日程标题",
  eventStartTime="2026-04-02T10:00:00+08:00",
  eventEndTime="2026-04-02T11:00:00+08:00",
  calendarId="日历 ID"
)
```

## 注意事项

- 发送消息前确认收件人和内容
- `instanceId` 必须是已配置的飞书渠道实例
- 创建群聊和管理群成员为高风险操作，需要用户确认
- 遵守飞书 API 频率限制
