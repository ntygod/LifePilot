# 知微（ZhiWei）飞书接入指南

> **文档性质**：集成指南  
> **适用范围**：飞书机器人渠道  
> **架构版本**：渠道插件架构（connector 模式）  
> **最后更新**：2026-04-03

---

## 1. 文档目标

本文面向实施、运维和开发同学，说明如何将知微接入飞书，实现从"飞书用户发消息"到"知微回复消息"的完整链路。

本指南覆盖：

- 飞书开放平台的应用创建与权限配置
- 知微主服务与飞书 connector 的配置
- 两种接入模式（WebSocket / Webhook）的选择与配置
- 启动验证与端到端联调
- 常见问题排查

---

## 2. 架构说明

飞书渠道基于知微的**插件化 connector 架构**，connector 作为独立进程运行：

```
飞书用户发消息
    ↓
飞书开放平台
    ↓ (WebSocket 推送 / Webhook 回调)
feishu-connector（独立进程，默认端口 19091）
    ↓ POST /api/channel-runtime/instances/{id}/events
知微主服务（网关 → 中间件管道 → Agent 引擎）
    ↓
feishu-connector
    ↓ 飞书 API
飞书用户收到回复
```

**关键特性**：

- 支持 **WebSocket**（推荐）和 **Webhook** 两种接入模式
- connector 由主服务自动托管（分配端口、启动进程、健康检查）
- 支持文本、富文本（post）、Markdown、卡片消息
- 支持图片、文件等附件处理
- 一个 connector 可管理多个飞书应用实例

---

## 3. 前置条件

### 3.1 创建飞书应用

1. 访问 [飞书开放平台](https://open.feishu.cn)
2. 创建「企业自建应用」
3. 在「凭证与基础信息」页面记录以下配置：

| 配置项 | 说明 | 是否必填 |
|--------|------|----------|
| **App ID** | 应用 ID | 必填 |
| **App Secret** | 应用密钥 | 必填 |
| **Verification Token** | 回调验证 Token | Webhook 模式必填 |
| **Encrypt Key** | 事件加密密钥 | Webhook 模式建议填写 |

### 3.2 开启机器人能力

在应用的「添加应用能力」中开启「机器人」，使应用可以接收用户消息并发送回复。

### 3.3 配置权限

确保应用已申请以下权限：

- `im:message` — 获取与发送单聊、群组消息
- `im:message:send_as_bot` — 以应用身份发送消息
- `im:resource` — 获取与上传图片或文件资源

### 3.4 订阅事件

在「事件订阅」中订阅以下事件：

- `im.message.receive_v1` — 接收消息

**WebSocket 模式**无需填写回调 URL，飞书平台会通过长连接推送事件。

**Webhook 模式**需要填写回调 URL：

```
https://你的公网域名/api/webhook/feishu
```

### 3.5 发布应用

完成配置后，提交版本审核并发布到目标租户。

---

## 4. 知微侧配置

### 4.1 接入模式选择

| 模式 | 优点 | 缺点 | 适用场景 |
|------|------|------|----------|
| **WebSocket**（默认） | 无需公网地址、配置简单 | 单连接，高并发受限 | 开发测试、中小规模 |
| **Webhook** | 高可用、支持负载均衡 | 需要公网 HTTPS 地址 | 生产高并发 |

### 4.2 通过管理界面创建实例（推荐）

1. 启动知微主服务
2. 打开 Web UI → 「设置」→「渠道管理」
3. 在「插件」标签页确认飞书插件已注册
4. 切换到「实例」标签页 → 点击「新建实例」
5. 选择插件：**飞书**
6. 填写配置：
   - **App ID**：飞书应用 ID
   - **App Secret**：飞书应用密钥（密钥字段）
   - **接入模式**：`websocket`（默认）或 `webhook`
   - **Verification Token**：Webhook 模式需填
   - **Encrypt Key**：Webhook 模式建议填写（密钥字段）
   - **Connector Base URL**：留空（自动托管）或填写自建 connector 地址
7. 点击「保存」→「启动」

### 4.3 通过环境变量配置

在 `.env` 或环境变量中设置：

```bash
FEISHU_ENABLED=true
FEISHU_APP_ID=cli_xxx
FEISHU_APP_SECRET=xxxxxxxxx
FEISHU_VERIFICATION_TOKEN=xxxxxxxxx    # Webhook 模式
FEISHU_ENCRYPT_KEY=xxxxxxxxx           # Webhook 模式
```

对应 `application.yml`：

```yaml
lifepilot:
  gateway:
    channels:
      feishu:
        enabled: ${FEISHU_ENABLED:false}
        app-id: ${FEISHU_APP_ID:}
        app-secret: ${FEISHU_APP_SECRET:}
        verification-token: ${FEISHU_VERIFICATION_TOKEN:}
        encrypt-key: ${FEISHU_ENCRYPT_KEY:}
        event-cache-max-size: 10000
```

### 4.4 通过 API 创建实例

```bash
curl -X POST http://localhost:8080/api/channels/instances \
  -H "Content-Type: application/json" \
  -d '{
    "instanceId": "feishu.prod",
    "pluginId": "feishu",
    "platform": "feishu",
    "displayName": "飞书生产机器人",
    "enabled": true,
    "config": {
      "appId": "cli_xxx",
      "connectionMode": "websocket"
    },
    "secretConfig": {
      "appSecret": "xxxxxxxxx"
    }
  }'

curl -X POST http://localhost:8080/api/channels/instances/feishu.prod/start
```

---

## 5. 启动与验证

### 5.1 启动服务

```bash
mvn spring-boot:run
```

观察日志，WebSocket 模式应看到类似输出：

```
飞书 WebSocket 连接已建立
飞书事件订阅已就绪
```

### 5.2 检查实例状态

```bash
curl http://localhost:8080/api/channels/instances/feishu.prod/health
```

正常响应：

```json
{
  "healthy": true,
  "status": "RUNNING",
  "instanceId": "feishu.prod",
  "platform": "feishu"
}
```

### 5.3 Webhook 模式：验证回调

如果使用 Webhook 模式，飞书平台在保存回调 URL 时会发送 challenge 验证请求。connector 会自动处理。

也可以手动测试：

```bash
curl -X POST http://localhost:19091/instances/feishu.prod/webhook \
  -H "Content-Type: application/json" \
  -d '{"challenge":"test123","token":"xxx","type":"url_verification"}'
```

期望返回 `{"challenge":"test123"}`。

---

## 6. 端到端联调

### 6.1 发送一条文本消息

在飞书中向机器人发送：

```
你好
```

### 6.2 检查日志

服务日志应依次出现：

1. 收到飞书事件
2. 事件提交至主服务
3. Agent 处理完成
4. 消息发送到飞书

### 6.3 联调检查清单

- [ ] 飞书应用已创建并发布
- [ ] App ID / App Secret 已正确配置
- [ ] 机器人能力已开启
- [ ] `im.message.receive_v1` 事件已订阅
- [ ] 知微主服务已启动，飞书实例状态健康
- [ ] 向机器人发消息能收到回复

---

## 7. 消息类型支持

### 7.1 接收（飞书 → 知微）

| 消息类型 | 支持状态 |
|----------|----------|
| 文本消息 | 已支持 |
| 富文本（post） | 已支持 |
| 图片 | 已支持 |
| 文件 | 已支持 |
| 卡片交互 | 已支持 |

### 7.2 发送（知微 → 飞书）

| 消息类型 | 支持状态 |
|----------|----------|
| 纯文本 | 已支持 |
| 富文本（post） | 已支持 |
| Markdown → post 转换 | 已支持 |
| 交互卡片 | 已支持 |
| 图片/文件 | 已支持 |

---

## 8. 常见问题排查

### 8.1 实例启动失败

- 检查 App ID 和 App Secret 是否正确
- 检查 connector 端口（默认 19091）是否被占用
- 查看 connector 日志（前缀 `[connector:feishu]`）

### 8.2 WebSocket 模式收不到消息

- 确认飞书平台的事件订阅中已订阅 `im.message.receive_v1`
- 确认应用已发布到目标租户
- 确认机器人被允许在当前会话中接收消息

### 8.3 Webhook 模式 challenge 验证失败

- 回调 URL 是否指向 connector 的 webhook 端点
- 公网是否能访问该 URL
- Encrypt Key 是否与飞书平台配置一致
- 反向代理是否改写了请求体

### 8.4 能收到消息但不回复

- 检查 App Secret 是否正确（tenant_access_token 获取依赖此配置）
- 检查应用是否具备 `im:message:send_as_bot` 权限
- 查看日志是否有 API 调用错误

### 8.5 connector 进程未启动

确认 connector manager 已启用：

```yaml
lifepilot:
  gateway:
    channels:
      connector-manager:
        enabled: true
        auto-manage-official: true
```

检查端口 19091 是否被占用。

---

## 9. 与其他渠道的对比

| 特性 | 飞书 | QQ | 企微 | 钉钉 |
|------|------|-----|------|------|
| 消息接收方式 | WebSocket / Webhook | WebSocket | Webhook | Webhook |
| 是否需要公网地址 | WebSocket 不需要 | 不需要 | 需要 | 需要 |
| 认证方式 | AppID + AppSecret | AppID + AppSecret | CorpID + Secret | AppKey + AppSecret |
| 消息加密 | 支持（Encrypt Key） | 无 | 支持 | 支持 |
| 卡片交互 | 支持 | 不支持 | 支持 | 支持 |
| 被动回复窗口 | 无限制 | 5 分钟 | 无限制 | 无限制 |

---

## 10. 附：API 参考

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

---

## 附录：从旧版迁移

如果你之前使用的是旧版 `ChannelAdapter` 架构（直接在 `application.yml` 中配置飞书参数），迁移步骤如下：

1. 保留 `application.yml` 中的飞书配置（作为环境变量默认值）
2. 启动主服务后，在管理界面创建飞书实例
3. 将原有的 App ID / App Secret 等配置填入实例的 config / secretConfig
4. 启动实例，验证消息链路
5. 旧版 `ChannelAdapter` 代码已废弃，不再需要关注 `FeishuChannelAdapter`、`WebhookController` 等类
