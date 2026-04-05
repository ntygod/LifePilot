# 知微（ZhiWei）QQ 机器人接入指南

> **文档性质**：集成指南  
> **适用范围**：QQ 群聊/私聊机器人渠道  
> **架构版本**：渠道插件架构（connector 模式）  
> **最后更新**：2026-04-03

---

## 1. 文档目标

本文面向实施、运维和开发同学，说明如何将知微接入 QQ 机器人，实现以下两条消息链路：

- **群聊**：QQ 群中 @机器人 → 知微处理 → 机器人回复群消息
- **私聊**：用户直接给机器人发消息 → 知微处理 → 机器人私聊回复

本指南覆盖：

- QQ 开放平台的应用创建与权限配置
- 知微主服务与 QQ connector 的配置
- 启动验证与端到端联调
- 常见问题排查

---

## 2. 架构说明

QQ 渠道基于知微的**插件化 connector 架构**，整体链路如下：

```
QQ 用户发消息
    ↓
QQ 机器人网关（WebSocket）
    ↓
qq-connector（独立进程，端口 19094）
    ↓ POST /api/channel-runtime/instances/{id}/events
知微主服务（网关 → 中间件管道 → Agent 引擎）
    ↓
qq-connector
    ↓ QQ REST API
QQ 用户收到回复
```

**关键特性**：

- QQ connector 通过 **WebSocket** 连接 QQ 网关，不需要公网回调地址
- connector 由主服务自动托管（分配端口、启动进程、健康检查）
- 支持断线自动重连、access_token 自动刷新
- 支持群聊和私聊两种场景
- 支持文本、Markdown、图片等消息类型

---

## 3. 前置条件

### 3.1 创建 QQ 机器人应用

1. 访问 [QQ 开放平台](https://q.qq.com)，使用 QQ 号登录
2. 进入「机器人」→「创建机器人」
3. 填写机器人名称、头像等基本信息
4. 创建完成后，在「开发设置」页面记录以下两个配置：

| 配置项 | 说明 | 示例 |
|--------|------|------|
| **AppID** | 机器人应用 ID | `102012345` |
| **AppSecret** | 机器人应用密钥 | `a1b2c3d4e5f6...` |

### 3.2 开启消息权限

在 QQ 开放平台的机器人管理后台：

1. 进入「功能配置」→「消息列表」
2. 确保以下 intent 已开启：
   - **群聊 @消息**（`GROUP_AT_MESSAGE_CREATE`）— 机器人在群中被 @时收到消息
   - **私聊消息**（`C2C_MESSAGE_CREATE`）— 用户私聊机器人时收到消息
3. 如果需要接收群内主动推送的能力，还需开启：
   - **群消息推送开关**（`GROUP_MSG_RECEIVE` / `GROUP_MSG_REJECT`）

> 这些权限对应 QQ WebSocket 协议中的 `intents` 位掩码，connector 默认请求 `GROUP_AND_C2C_EVENT`（`1 << 25`）。

### 3.3 发布机器人

在 QQ 开放平台完成审核并发布机器人：

1. 进入「发布设置」
2. 提交审核（首次需要等待平台审核通过）
3. 审核通过后，机器人才能在 QQ 中被搜索和使用

### 3.4 将机器人加入群聊

- 在 QQ 群中搜索机器人名称，将其添加到群
- 或通过机器人的「添加到群」链接邀请入群

---

## 4. 知微侧配置

### 4.1 环境变量配置

在 `.env` 文件（或部署平台的环境变量）中设置：

```bash
QQ_ENABLED=true
QQ_APP_ID=你的AppID
QQ_APP_SECRET=你的AppSecret
```

这些环境变量映射到 `application.yml` 中的：

```yaml
lifepilot:
  gateway:
    channels:
      qq:
        enabled: ${QQ_ENABLED:false}
        app-id: ${QQ_APP_ID:}
        app-secret: ${QQ_APP_SECRET:}
```

### 4.2 通过管理界面创建实例（推荐）

环境变量方式适合快速验证。生产环境推荐使用管理界面：

1. 启动知微主服务
2. 打开 Web UI → 「设置」→「渠道管理」
3. 在「插件」标签页确认 QQ 插件已注册
4. 切换到「实例」标签页 → 点击「新建实例」
5. 选择插件：**QQ 机器人**
6. 填写配置：
   - **实例 ID**：如 `qq.prod`（可自动生成）
   - **显示名称**：如 `QQ 生产机器人`
   - **App ID**：QQ 平台的 AppID
   - **App Secret**：QQ 平台的 AppSecret（密钥字段，不会明文展示）
7. 点击「保存」创建实例
8. 点击「启动」按钮启动连接

### 4.3 通过 API 创建实例

```bash
# 创建实例
curl -X POST http://localhost:8080/api/channels/instances \
  -H "Content-Type: application/json" \
  -d '{
    "instanceId": "qq.prod",
    "pluginId": "qq",
    "platform": "qq",
    "displayName": "QQ 生产机器人",
    "enabled": true,
    "config": {
      "appId": "你的AppID"
    },
    "secretConfig": {
      "appSecret": "你的AppSecret"
    }
  }'

# 启动实例
curl -X POST http://localhost:8080/api/channels/instances/qq.prod/start
```

---

## 5. 启动与验证

### 5.1 启动服务

```bash
# 启动主服务
mvn spring-boot:run

# 或使用 Docker
docker compose up -d
```

启动后观察日志，应看到类似输出：

```
QQ access_token 已获取，有效期 7200s，将在 5760s 后刷新
正在连接 QQ WebSocket 网关: wss://api.sgroup.qq.com/websocket
QQ WebSocket 连接已建立
已发送 Identify 请求
QQ WebSocket 鉴权成功: sessionId=xxx
心跳定时器已启动: 间隔=41250ms
```

### 5.2 检查实例状态

```bash
# 查询健康状态
curl http://localhost:8080/api/channels/instances/qq.prod/health
```

正常响应示例：

```json
{
  "healthy": true,
  "status": "CONNECTED",
  "instanceId": "qq.prod",
  "connectedDurationSeconds": 120,
  "totalWsEventsReceived": 3,
  "totalWsReconnects": 0,
  "tokenAcquiredAt": "2026-04-03T10:00:00Z",
  "tokenExpiresAt": "2026-04-03T12:00:00Z",
  "inboundCount": 3,
  "deliveryCount": 3,
  "errorCount": 0
}
```

关注字段：

| 字段 | 含义 | 正常值 |
|------|------|--------|
| `healthy` | 整体健康 | `true` |
| `status` | WebSocket 状态 | `CONNECTED` |
| `totalWsReconnects` | 累计重连次数 | 0 或极少 |
| `errorCount` | 累计错误数 | 0 |

### 5.3 查看实例事件日志

```bash
curl http://localhost:8080/api/channels/instances/qq.prod/events?limit=20
```

---

## 6. 端到端联调

### 6.1 群聊测试

1. 在已添加机器人的 QQ 群中，发送：`@机器人名称 你好`
2. 观察知微服务日志，应看到：
   ```
   事件已提交至主服务: eventType=GROUP_AT_MESSAGE_CREATE, responseId=xxx
   发送群消息: groupOpenId=xxx
   ```
3. QQ 群中应收到机器人的回复

### 6.2 私聊测试

1. 直接向机器人发送私聊消息：`你好`
2. 观察知微服务日志，应看到：
   ```
   事件已提交至主服务: eventType=C2C_MESSAGE_CREATE, responseId=xxx
   发送私聊消息: userOpenId=xxx
   ```
3. 应收到机器人的私聊回复

### 6.3 联调检查清单

- [ ] QQ 开放平台应用已创建并发布
- [ ] AppID 和 AppSecret 已正确配置
- [ ] 群聊和私聊消息权限已开启
- [ ] 知微主服务已启动，QQ 实例状态为 CONNECTED
- [ ] 群聊 @机器人能收到回复
- [ ] 私聊机器人能收到回复

---

## 7. 消息类型支持

### 7.1 接收（QQ → 知微）

| 消息类型 | 支持状态 | 说明 |
|----------|----------|------|
| 文本消息 | 已支持 | 群聊需 @机器人 |
| 图片/文件 | 已支持 | 以附件 URL 形式传入 |

### 7.2 发送（知微 → QQ）

| 消息类型 | QQ msg_type | 支持状态 | 说明 |
|----------|-------------|----------|------|
| 纯文本 | 0 | 已支持 | 默认类型 |
| Markdown | 2 | 已支持 | 需在内容类型中标记 `markdown` |
| 富媒体（图片等） | 7 | 已支持 | 需先上传获取 `file_info` |

### 7.3 被动回复与主动推送

QQ 机器人有两种消息发送模式：

- **被动回复**：收到用户消息后 **5 分钟内**，携带原消息的 `msg_id` 回复，不受主动推送频率限制
- **主动推送**：超过 5 分钟后，使用 `msg_seq` 发送，受平台频率限制

connector 自动跟踪消息接收时间，在 5 分钟窗口内优先使用被动回复，超时后自动降级为主动推送。

---

## 8. 运维监控

### 8.1 关键日志关键字

| 关键字 | 含义 |
|--------|------|
| `QQ access_token 已获取` | Token 获取/刷新成功 |
| `QQ WebSocket 鉴权成功` | WebSocket 连接并鉴权完成 |
| `事件已提交至主服务` | 收到消息并成功转发 |
| `发送群消息` / `发送私聊消息` | 消息发送动作 |
| `连续 N 次未收到心跳 ACK` | 心跳超时，即将重连 |
| `QQ API 错误` | API 调用失败，含错误码 |
| `QQ WebSocket 已关闭` | 连接断开 |

### 8.2 自动恢复机制

| 场景 | 处理方式 |
|------|----------|
| WebSocket 断线 | 指数退避重连（1s → 2s → 4s → ... → 60s） |
| 会话无效（Opcode 9） | 1-5 秒随机延迟后重连，优先尝试 Resume |
| 服务端要求重连（Opcode 7） | 立即重连 |
| access_token 过期 | 80% TTL 时自动刷新，401 时立即刷新 |
| API 调用 429/5xx | 自动重试（最多 2 次），支持 Retry-After |
| 心跳 ACK 超时 | 连续 3 次无 ACK 触发重连 |

### 8.3 多实例支持

同一个 connector 进程可以管理多个 QQ 机器人实例（不同 AppID），每个实例独立维护 WebSocket 连接和 token。通过管理界面创建多个实例即可。

---

## 9. 常见问题排查

### 9.1 实例启动失败，提示"缺少 appId 或 appSecret"

- 检查创建实例时 `config` 或 `secretConfig` 中是否正确填写了 `appId` 和 `appSecret`
- 通过 API 创建时，`appSecret` 建议放在 `secretConfig` 而非 `config` 中

### 9.2 WebSocket 连接失败，反复重连

- 检查 AppID 和 AppSecret 是否正确
- 检查机器人应用是否已在 QQ 开放平台发布
- 检查网络是否能访问 `api.sgroup.qq.com`（需要外网访问）
- 查看日志中的具体错误信息

### 9.3 连接成功但收不到群消息

- 确认机器人已被添加到目标 QQ 群
- 确认发送消息时 **@了机器人**（群聊必须 @才能触发）
- 确认 QQ 开放平台的消息权限（GROUP_AND_C2C_EVENT intent）已开启
- 检查机器人是否被群管理员禁言

### 9.4 收到消息但不回复

- 检查健康接口的 `errorCount` 和 `lastError` 字段
- 查看 connector 日志是否有 `QQ API 错误` 记录
- 确认 AppSecret 正确（Token 获取依赖此配置）
- 如果刚收到消息就回复失败，可能是 QQ API 瞬时限流，connector 会自动重试

### 9.5 回复消息被 QQ 拦截

QQ 机器人平台对消息内容有审核机制：

- 不要发送包含外链的消息（会被拦截）
- 不要发送敏感词汇
- Markdown 格式需要平台侧开启对应权限

### 9.6 connector 进程未启动

检查 connector manager 配置：

```yaml
lifepilot:
  gateway:
    channels:
      connector-manager:
        enabled: true
        auto-manage-official: true
```

确认端口 19094 未被占用：

```bash
netstat -ano | findstr :19094
```

---

## 10. 与飞书/企微/钉钉的对比

| 特性 | QQ | 飞书 | 企微 | 钉钉 |
|------|-----|------|------|------|
| 消息接收方式 | WebSocket | WebSocket / Webhook | Webhook | Webhook |
| 是否需要公网地址 | 不需要 | WebSocket 不需要 | 需要 | 需要 |
| 认证方式 | AppID + AppSecret | AppID + AppSecret | CorpID + Secret | AppKey + AppSecret |
| 群消息触发条件 | @机器人 | @机器人 | @机器人 | @机器人 |
| 私聊支持 | 支持 | 支持 | 支持 | 支持 |
| 被动回复窗口 | 5 分钟 | 无限制 | 无限制 | 无限制 |
| 消息审核 | 平台审核 | 无 | 无 | 无 |

---

## 11. 附：API 参考

### 实例管理

```
GET    /api/channels/plugins              # 查看已注册插件
GET    /api/channels/instances             # 查看所有实例
POST   /api/channels/instances             # 创建实例
GET    /api/channels/instances/{id}        # 查看实例详情
PUT    /api/channels/instances/{id}        # 更新实例配置
DELETE /api/channels/instances/{id}        # 删除实例
POST   /api/channels/instances/{id}/start  # 启动实例
POST   /api/channels/instances/{id}/stop   # 停止实例
POST   /api/channels/instances/{id}/reload # 重载实例配置
GET    /api/channels/instances/{id}/health # 健康检查
GET    /api/channels/instances/{id}/events # 事件日志
PATCH  /api/channels/instances/{id}/enabled?enabled=true  # 启用/禁用
```
