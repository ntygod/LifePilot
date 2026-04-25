# 飞书 channel.feishu action 速查

所有操作通过 `action` 参数路由，必须指定 `instanceId`（已配置的飞书渠道实例）。

## 发送消息

```
channel.feishu(action="send_message", instanceId="feishu.default", targetId="目标ID", receiveIdType="chat_id", content="消息内容", msgType="text")
```

## 回复消息

```
channel.feishu(action="reply_message", instanceId="feishu.default", messageId="消息ID", content="回复内容", replyInThread=true)
```

## 发送交互卡片

```
channel.feishu(action="send_card", instanceId="feishu.default", targetId="目标ID", cardJson="{卡片JSON}")
```

## 上传/下载文件

```
channel.feishu(action="upload_file", instanceId="feishu.default", fileName="文件名.pdf", fileData="Base64数据", fileType="file")
channel.feishu(action="download_file", instanceId="feishu.default", fileToken="文件token")
```

## 创建任务 / 文档 / 日程

```
channel.feishu(action="create_task", instanceId="feishu.default", taskSummary="任务标题", taskDueTimestamp="截止时间戳")
channel.feishu(action="create_document", instanceId="feishu.default", documentTitle="文档标题", documentFolderToken="文件夹token")
channel.feishu(action="create_calendar_event", instanceId="feishu.default", eventSummary="日程标题", eventStartTime="2026-04-02T10:00:00+08:00", eventEndTime="2026-04-02T11:00:00+08:00")
```

## 群组管理

```
channel.feishu(action="create_group", instanceId="feishu.default", groupName="群名称")
channel.feishu(action="manage_members", instanceId="feishu.default", chatId="群ID", memberIds="m1,m2", memberAction="add")
```

## 常见错误处理

- **instanceId 不存在** → 提示用户检查飞书渠道配置
- **目标 ID 无效** → 确认用户 ID 或群 ID 格式
- **权限不足** → 确认飞书应用的权限范围
- **频率超限** → 遵守飞书 API 频率限制，分批发送
</content>
</invoke>