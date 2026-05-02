# 飞书操作参考

> action 清单与参数见 channel.feishu tool schema。本文档仅含业务规则。

`instanceId` 是用户在渠道实例配置过的飞书账号；未配置不要伪造。

## 二次确认清单

以下 action 必须先告诉用户做什么、收件方是谁、再等确认：

- `recall_message` — 撤回不可逆，对方端可能仍有缓存
- `create_group` — 会创建可见群、产生通知
- `manage_members` — 影响群成员，add 会拉人入群发通知
- `update_message` — 改已发送消息可能造成上下文混乱

发送含敏感信息（密码/token/内部数据）的消息同样需确认。

## 常见错误

| 现象 | 处理 |
|------|------|
| instanceId 不存在/未启用 | 提示用户去渠道实例配置页确认 |
| 收件人/群 ID 无效 | 确认 receiveIdType 与实际 ID 类型匹配（chat_id/open_id/user_id） |
| 99991663/权限不足 | 飞书应用未授权对应能力 |
| 11200/频率超限 | 分批发送，每批间停顿 |
| 消息 ID 过期 | 飞书消息撤回 24h 内有效 |
