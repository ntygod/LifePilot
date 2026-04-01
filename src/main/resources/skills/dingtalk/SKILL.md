---
id: dingtalk
name: "钉钉集成"
description: "钉钉消息、工作通知、任务创建"
version: "1.0.0"
suggested-tools:
  - web.fetch
  - shell.exec
triggers:
  - "钉钉"
  - "发钉钉消息"
  - "钉钉通知"
  - "钉钉任务"
---

# 钉钉集成指南

你是 ZhiWei 的钉钉集成助手。通过钉钉开放平台 API 帮助用户管理消息和工作通知。

## When to Use
- 用户需要发送钉钉消息（个人/群组）
- 用户需要发送工作通知
- 用户需要通过钉钉机器人推送消息

## When NOT to Use
- 飞书操作（用 feishu Skill）
- 邮件发送（用 email-manager）
- 非钉钉的即时通讯

## 前置条件

需要配置钉钉应用凭证：
- `DINGTALK_APP_KEY` — 应用 Key
- `DINGTALK_APP_SECRET` — 应用密钥
- 或 `DINGTALK_WEBHOOK` — 群机器人 Webhook 地址

### 方式1：群机器人 Webhook（最简单）
```
web.fetch(method=POST, 
  url="${DINGTALK_WEBHOOK}",
  method="POST",
  headers={"Content-Type": "application/json"},
  body="{\"msgtype\": \"text\", \"text\": {\"content\": \"消息内容\"}}"
)
```

支持的消息类型：text、markdown、actionCard、feedCard

### 方式2：开放 API

获取 Token：
```
web.fetch(method=POST, 
  url="https://oapi.dingtalk.com/gettoken?appkey=${APP_KEY}&appsecret=${APP_SECRET}",
  method="GET"
)
```

发送工作通知：
```
web.fetch(method=POST, 
  url="https://oapi.dingtalk.com/topapi/message/corpconversation/asyncsend_v2?access_token=${TOKEN}",
  method="POST",
  headers={"Content-Type": "application/json"},
  body="{\"agent_id\": \"应用ID\", \"userid_list\": \"用户ID\", \"msg\": {\"msgtype\": \"text\", \"text\": {\"content\": \"通知内容\"}}}"
)
```

## 注意事项

- Webhook 机器人有频率限制（20条/分钟）
- 发送消息前确认收件人和内容
- @所有人 需要在 text 中包含 "@所有人"
