# 飞书 channel.feishu action 速查

`action` + `instanceId` 必填。`instanceId` 是用户在「渠道实例」配置过的飞书账号；未配置不要伪造。

## 消息收发

| action | 必填参数 | 说明 |
|---|---|---|
| send_message | targetId, content | 发文本 / 富文本到用户或群；`receiveIdType` 默认 chat_id，`msgType` 默认 text |
| send_card | targetId, cardJson | 发交互卡片 JSON；`receiveIdType` 默认 chat_id |
| reply_message | messageId, content | thread reply；`replyInThread=true` 用话题回复 |
| update_message | messageId, content | 改已发送消息的内容（见下方二次确认） |
| recall_message | messageId | 撤回消息（见下方二次确认） |

```
channel.feishu(action="send_message", instanceId="<feishu instance>", targetId="<chat_id>", content="<text>", receiveIdType="chat_id", msgType="text")
channel.feishu(action="send_card", instanceId="<feishu instance>", targetId="<chat_id>", cardJson="<json>")
channel.feishu(action="reply_message", instanceId="<feishu instance>", messageId="<msg_id>", content="<text>", replyInThread=true)
channel.feishu(action="update_message", instanceId="<feishu instance>", messageId="<msg_id>", content="<new text>")
channel.feishu(action="recall_message", instanceId="<feishu instance>", messageId="<msg_id>")
```

## 文件上传 / 下载

| action | 必填参数 | 说明 |
|---|---|---|
| upload_file | fileName, fileData | fileData 为 Base64；`fileType` image / file |
| download_file | fileToken | 拿到的飞书内部 token |

```
channel.feishu(action="upload_file", instanceId="<feishu instance>", fileName="<name>", fileData="<base64>", fileType="file")
channel.feishu(action="download_file", instanceId="<feishu instance>", fileToken="<token>")
```

## 群组管理（高风险）

| action | 必填参数 | 说明 |
|---|---|---|
| create_group | groupName | 创建群（见下方二次确认） |
| manage_members | chatId, memberIds, memberAction | 改群成员（见下方二次确认）；memberIds 逗号分隔；memberAction add / remove |

```
channel.feishu(action="create_group", instanceId="<feishu instance>", groupName="<name>", groupDescription="<desc>")
channel.feishu(action="manage_members", instanceId="<feishu instance>", chatId="<chat_id>", memberIds="<id1>,<id2>", memberAction="add")
```

## 任务 / 文档 / 日程

| action | 必填参数 | 说明 |
|---|---|---|
| create_task | taskSummary | `taskDueTimestamp` 毫秒时间戳 |
| create_document | documentTitle | `documentFolderToken` 留空则建在根 |
| create_calendar_event | eventSummary, eventStartTime, eventEndTime | 时间用 ISO 8601 含时区 |

```
channel.feishu(action="create_task", instanceId="<feishu instance>", taskSummary="<title>", taskDueTimestamp="<ms>")
channel.feishu(action="create_document", instanceId="<feishu instance>", documentTitle="<title>", documentFolderToken="<folder>")
channel.feishu(action="create_calendar_event", instanceId="<feishu instance>", eventSummary="<title>", eventStartTime="<ISO8601>", eventEndTime="<ISO8601>", calendarId="<cal>")
```

## 二次确认清单

发起以下 action 前必须先告诉用户做什么、收件方是谁、再等确认：

- `recall_message` —— 撤回是不可逆的，对方端可能仍有缓存
- `create_group` —— 会创建可见群、产生通知
- `manage_members` —— 影响群成员，且 add 会拉人入群发通知
- `update_message` —— 改已发送消息可能造成上下文混乱

发送含敏感信息（密码 / token / 内部数据）的 `send_message` / `send_card` 同样需要确认。

## 错误处理

| 现象 | 处理 |
|---|---|
| instanceId 不存在 / 未启用 | 提示用户去渠道实例配置页确认；不要重试 |
| 收件人 / 群 ID 无效 | 确认 receiveIdType 与实际 ID 类型匹配（chat_id / open_id / user_id） |
| 99991663 / 权限不足 | 飞书应用未授权对应能力；提示用户开通后重试 |
| 11200 / 频率超限 | 分批发送，每批间停顿；不要立即重试 |
| 消息 ID 过期（撤回 / 更新） | 飞书消息有时效（撤回 24h），过期不可操作 |
