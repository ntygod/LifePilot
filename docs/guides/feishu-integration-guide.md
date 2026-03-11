# 知微（ZhiWei）飞书接入指南

> **文档性质**：集成指南
> **适用范围**：`com.lifepilot.interaction.channel.feishu`
> **最后更新**：2026-03-11

---

## 1. 文档目标

本文面向实施、运维和开发同学，说明如何把当前项目接入飞书，并完成从“飞书用户发消息”到“知微回复消息”的整条链路联调。

本文同时覆盖两部分内容：

- 平台侧需要在飞书开放平台完成的配置
- 项目侧需要在 ZhiWei 中打开和填写的配置

如果你只想快速验证链路是否可用，可以直接看：

1. [第 3 章：平台侧准备](#3-平台侧准备)
2. [第 4 章：项目侧配置](#4-项目侧配置)
3. [第 5 章：启动与回调验证](#5-启动与回调验证)
4. [第 6 章：端到端联调](#6-端到端联调)

---

## 2. 当前实现能力与边界

当前项目中的飞书渠道已经具备基础接入能力，不是占位代码。核心入口和实现如下：

- Webhook 入口：[`/api/webhook/feishu`](../API_ENDPOINTS.md)
- 事件处理：[`FeishuChannelAdapter`](../../src/main/java/com/lifepilot/interaction/channel/feishu/FeishuChannelAdapter.java)
- 消息发送：[`FeishuApiClient`](../../src/main/java/com/lifepilot/interaction/channel/feishu/FeishuApiClient.java)
- 自动注册：[`ChannelAdapterAutoConfiguration`](../../src/main/java/com/lifepilot/interaction/config/ChannelAdapterAutoConfiguration.java)
- 默认配置：[`application.yml`](../../src/main/resources/application.yml)

当前已实现的能力：

- 支持飞书事件订阅回调接入
- 支持 `challenge` 回调验证
- 支持加密事件解密
- 支持基于 `event_id` 的简单去重
- 支持把飞书文本消息标准化后送入 Gateway
- 支持把知微回复回发到飞书
- 支持文本回复和 `post` 富文本回复

当前需要注意的边界：

- 当前代码主要按文本消息处理，消息体中的 `content` 预期为 `{"text":"..."}` 结构
- 图片、文件、音视频、真正的交互式卡片回调，目前没有看到完整支持
- 出站发送固定使用 `receive_id_type=chat_id`，因此联调时应优先确保事件里能拿到有效 `chat_id`
- 配置中预留了 `verification-token`，但当前实现没有在回调入口显式校验该字段，主要依赖 `encrypt-key` 解密和后续 `appId` 匹配

如果你要做生产接入，建议把“当前实现边界”作为联调前提写进实施说明，避免把它当成全能力飞书机器人接入。

---

## 3. 平台侧准备

### 3.1 创建飞书应用

在飞书开放平台创建一个应用，并为应用开启机器人能力。

结合当前项目的出站实现：

- 项目会调用租户级 `tenant_access_token` 接口获取访问令牌
- 项目会主动调用飞书消息发送接口给用户或会话回消息

因此更适合使用企业内部自建应用的接入方式。

建议在飞书平台侧记录以下 4 个配置项，稍后要填到项目配置中：

- `App ID`
- `App Secret`
- `Verification Token`
- `Encrypt Key`

### 3.2 开启机器人与消息能力

在应用能力中开启机器人，使应用可以接收用户消息并发送回复。

根据当前项目代码和飞书官方近期公开材料，至少需要保证两件事：

- 机器人能接收消息事件
- 应用具备“以应用身份发送消息”的能力

参考资料：

- [飞书开放平台官方文档：自建应用获取 tenant_access_token](https://open.feishu.cn/document/server-docs/authentication-management/access-token/tenant_access_token_internal)
- [飞书官方内容：应用机器人发送消息所需权限说明](https://open.feishu.cn/content/7gprunv5)

### 3.3 配置事件订阅

在飞书开放平台的“事件订阅”中填写回调地址：

```text
https://你的公网域名/api/webhook/feishu
```

当前项目飞书 Webhook 的固定入口在：

- [`WebhookController`](../../src/main/java/com/lifepilot/interaction/channel/webhook/WebhookController.java)

平台配置时建议：

- 使用公网可访问的 HTTPS 地址
- 反向代理不要改写请求体
- 如果有 WAF 或 API 网关，确保允许飞书回调访问该路径

### 3.4 订阅消息事件

当前代码期望处理的核心消息事件是 `im.message.receive_v1`。这一点可以从测试样例中直接看到：

- [`FeishuChannelAdapter_单元测试`](../../src/test/java/com/lifepilot/interaction/channel/feishu/FeishuChannelAdapter_单元测试.java)

建议至少订阅：

- 消息接收类事件

如果后续要扩展欢迎语、群事件或卡片回调，需要再补相应事件与代码适配。

### 3.5 发布应用

完成能力、权限和事件订阅后，确认应用版本已发布到目标租户。

如果应用仍处于未发布或配置未生效状态，常见现象是：

- `challenge` 能过，但实际消息收不到
- 消息能收到，但发送接口报权限或范围错误

---

## 4. 项目侧配置

### 4.1 打开飞书渠道

在 [`application.yml`](../../src/main/resources/application.yml) 中找到：

```yaml
lifepilot:
  gateway:
    channels:
      feishu:
        enabled: false
        app-id:
        app-secret:
        verification-token:
        encrypt-key:
        event-cache-max-size: 10000
```

把它改成类似下面这样：

```yaml
lifepilot:
  gateway:
    channels:
      feishu:
        enabled: true
        app-id: cli_xxx
        app-secret: xxxxxxxxx
        verification-token: xxxxxxxxx
        encrypt-key: xxxxxxxxx
        event-cache-max-size: 10000
```

各字段含义：

- `enabled`：飞书渠道开关
- `app-id`：飞书应用 ID
- `app-secret`：飞书应用密钥
- `verification-token`：飞书回调验证 Token
- `encrypt-key`：飞书事件加密密钥
- `event-cache-max-size`：事件去重缓存容量

字段定义位于：

- [`GatewayProperties`](../../src/main/java/com/lifepilot/interaction/config/GatewayProperties.java)

### 4.2 启用条件说明

飞书通道不是只要 `enabled=true` 就一定注册成功。

自动配置逻辑是：

1. `lifepilot.gateway.channels.feishu.enabled=true`
2. `encrypt-key` 非空
3. 才会创建 `FeishuCrypto`
4. `FeishuCrypto` 创建成功后，才会注册 `FeishuChannelAdapter`

对应代码在：

- [`ChannelAdapterAutoConfiguration`](../../src/main/java/com/lifepilot/interaction/config/ChannelAdapterAutoConfiguration.java)

所以如果出现“配置已经打开，但飞书完全不生效”，第一优先检查：

- `encrypt-key` 是否为空
- 配置文件是否真的被当前环境加载

### 4.3 环境变量建议

如果不希望把敏感配置直接写在仓库配置文件里，建议用环境变量或部署平台密钥注入。

推荐做法：

- `app-secret` 使用环境变量注入
- `encrypt-key` 使用环境变量注入
- `verification-token` 使用环境变量注入

无论使用哪种注入方式，都要确保最终 Spring 读取到的配置值不为空。

---

## 5. 启动与回调验证

### 5.1 启动服务

启动项目后，优先观察日志中是否出现飞书适配器注册信息。

关键注册点在：

- [`ChannelAdapterAutoConfiguration`](../../src/main/java/com/lifepilot/interaction/config/ChannelAdapterAutoConfiguration.java)

正常情况下应看到类似含义的日志：

```text
注册 FeishuChannelAdapter
```

如果没有这条日志，说明飞书适配器大概率没有被真正装配。

### 5.2 本地 challenge 验证

在飞书平台验证回调 URL 之前，可以先用本地请求模拟：

```bash
curl -X POST http://localhost:8080/api/webhook/feishu \
  -H "Content-Type: application/json" \
  -d "{\"challenge\":\"test123\",\"token\":\"test-token\",\"type\":\"url_verification\"}"
```

期望返回：

```json
{"challenge":"test123"}
```

当前 `challenge` 处理逻辑在：

- [`FeishuChannelAdapter.handleEvent`](../../src/main/java/com/lifepilot/interaction/channel/feishu/FeishuChannelAdapter.java)

### 5.3 加密 challenge 验证

如果飞书平台启用了加密回调，项目会优先尝试解密 `encrypt` 字段，再继续处理 challenge。

这一点在单测中有完整样例：

- [`FeishuChannelAdapter_单元测试`](../../src/test/java/com/lifepilot/interaction/channel/feishu/FeishuChannelAdapter_单元测试.java)

如果平台回调验证失败，常见原因通常只有三类：

- 回调 URL 不通
- `encrypt-key` 配错
- 代理层改写了请求体

---

## 6. 端到端联调

### 6.1 发送一条纯文本消息

让飞书用户给机器人发一条简单文本，例如：

```text
你好
```

当前项目对飞书文本消息的预期格式，是消息体中的 `content` 能解析成：

```json
{"text":"你好"}
```

解析逻辑位于：

- [`FeishuChannelAdapter.extractMessageText`](../../src/main/java/com/lifepilot/interaction/channel/feishu/FeishuChannelAdapter.java)

### 6.2 检查知微是否收到事件

消息发出后，检查服务日志中是否出现飞书事件处理相关日志或异常。

当前飞书事件处理过程大致是：

1. 读取原始 JSON
2. 处理 `challenge`
3. 如有 `encrypt`，先解密
4. 读取 `header.event_id`
5. 去重
6. 标准化为 `GatewayMessage`
7. 异步提交给 Gateway

对应实现：

- [`FeishuChannelAdapter.handleEvent`](../../src/main/java/com/lifepilot/interaction/channel/feishu/FeishuChannelAdapter.java)
- [`FeishuChannelAdapter.normalize`](../../src/main/java/com/lifepilot/interaction/channel/feishu/FeishuChannelAdapter.java)

### 6.3 检查知微是否回消息

如果知微已经处理完成，飞书侧应收到一条机器人回复。

出站发送路径是：

1. Adapter 根据响应内容决定发 `text` 还是 `post`
2. `FeishuApiClient` 获取 `tenant_access_token`
3. 调用飞书消息发送接口

对应代码：

- [`FeishuChannelAdapter.doSendResponse`](../../src/main/java/com/lifepilot/interaction/channel/feishu/FeishuChannelAdapter.java)
- [`FeishuMessageConverter`](../../src/main/java/com/lifepilot/interaction/channel/feishu/FeishuMessageConverter.java)
- [`FeishuApiClient`](../../src/main/java/com/lifepilot/interaction/channel/feishu/FeishuApiClient.java)

### 6.4 如何判断回复类型

当前回复格式规则如下：

- `TextContent`：发送普通 `text`
- `MarkdownContent`：转换为 `post`
- `CardContent`：转换为 `post`
- `StreamingContent`：降级为纯文本

这意味着当前飞书渠道并不是真正发送飞书“交互卡片”，而是把部分富内容转换为 `post` 文本样式。

---

## 7. 推荐联调顺序

建议严格按下面顺序联调，不要一上来就排查整条链路：

1. 先确认公网地址和 HTTPS 可访问
2. 再确认飞书平台 challenge 验证通过
3. 再发一条最简单的文本消息
4. 再确认服务日志里已经收到事件
5. 再确认飞书发送接口是否成功回消息
6. 最后再验证 Markdown / 富文本等增强能力

这样做的好处是每一层问题都能快速隔离，不会把“平台配置错”和“业务逻辑错”混在一起。

---

## 8. 常见问题排查

### 8.1 平台提示回调验证失败

优先检查：

- 回调 URL 是否写成了 `/api/webhook/feishu`
- 服务是否真的能被公网访问
- `encrypt-key` 是否和飞书平台完全一致
- 网关或反向代理是否改写了请求体

### 8.2 启动后没有任何飞书日志

优先检查：

- `lifepilot.gateway.channels.feishu.enabled` 是否为 `true`
- `encrypt-key` 是否为空
- 部署环境是否加载了正确配置

### 8.3 challenge 能通过，但收不到聊天消息

优先检查：

- 飞书平台是否真的订阅了消息接收事件
- 应用是否已发布到当前租户
- 机器人是否被允许在当前会话中接收消息

### 8.4 能收到消息，但不回消息

优先检查：

- `app-id` / `app-secret` 是否正确
- 飞书应用是否具备发送消息权限
- 日志中是否出现 `tenant_access_token` 获取失败
- 日志中是否出现发送消息接口异常

出站 Token 获取和发送逻辑在：

- [`FeishuApiClient`](../../src/main/java/com/lifepilot/interaction/channel/feishu/FeishuApiClient.java)

### 8.5 文本能处理，富媒体不行

这是当前实现边界，不一定是配置问题。当前代码主要适配文本消息和简单富文本回复。

如果你需要支持：

- 图片消息
- 文件消息
- 音视频消息
- 真正的飞书卡片交互

需要继续扩展 `FeishuChannelAdapter` 和 `FeishuMessageConverter`。

---

## 9. 生产接入建议

生产环境建议至少做到下面几点：

- 使用独立公网域名和 HTTPS
- 将 `app-secret`、`encrypt-key` 等配置移出仓库文件
- 为飞书回调路径配置单独访问日志
- 为飞书出站调用配置错误日志与告警
- 对消息发送失败做补偿或重试策略验证

当前项目已经有失败消息重试调度器基础设施，但是否启用、是否完全覆盖飞书场景，还需要结合实际部署再确认。

---

## 10. 当前实现备注

为了避免实施时误解，这里补充两个和代码实现强相关的说明。

### 10.1 `verification-token` 当前未形成完整验签闭环

配置项和字段已经存在：

- [`application.yml`](../../src/main/resources/application.yml)
- [`GatewayProperties`](../../src/main/java/com/lifepilot/interaction/config/GatewayProperties.java)
- [`FeishuChannelAdapter`](../../src/main/java/com/lifepilot/interaction/channel/feishu/FeishuChannelAdapter.java)

但当前回调入口里没有直接基于 `token` 字段做显式校验。`FeishuAuthStrategy` 中的说明写的是“通过 verification token 验证”，但实际代码使用的是 `appId` 匹配：

- [`FeishuAuthStrategy`](../../src/main/java/com/lifepilot/interaction/middleware/auth/FeishuAuthStrategy.java)

因此：

- 平台侧仍建议配置 `Verification Token`
- 但不要把它理解成当前项目已经完整实现了飞书官方推荐的 Token 校验流程

### 10.2 出站发送固定按 `chat_id` 发送

当前消息发送接口固定使用：

```text
receive_id_type=chat_id
```

对应实现：

- [`FeishuApiClient`](../../src/main/java/com/lifepilot/interaction/channel/feishu/FeishuApiClient.java)

因此联调时建议优先验证以下场景：

- 能从入站事件中稳定拿到 `chat_id`
- 回消息的目标会话确实允许按 `chat_id` 发送

---

## 11. 附：最小联调清单

实施时可以直接照着这张清单逐项打勾：

- 已创建飞书应用
- 已开启机器人能力
- 已记录 `App ID / App Secret / Verification Token / Encrypt Key`
- 已配置事件订阅 URL：`https://你的域名/api/webhook/feishu`
- 已订阅消息接收事件
- 已发布应用到目标租户
- 已在项目中打开 `lifepilot.gateway.channels.feishu.enabled`
- 已正确填入 `app-id / app-secret / verification-token / encrypt-key`
- 启动日志已出现 `注册 FeishuChannelAdapter`
- challenge 验证通过
- 纯文本消息能进项目
- 知微能成功回消息

如果上面 12 项都通过，说明飞书基础接入已经完成。

