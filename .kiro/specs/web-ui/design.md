# Design Document: Web UI

## Overview

Web UI 模块（Phase 5 — 模块 18）为 LifePilot 提供浏览器端交互界面，采用前后端彻底分离架构。

- **后端**（`com.lifepilot.interaction.web` 包）：Spring Boot REST Controller + SSE 流式端点 + WebChannelAdapter，复用 MessageGateway 中间件管道
- **前端**（独立项目 `lifepilot-web/`）：Vue 3 + Vite + Pinia + shadcn-vue SPA，独立构建部署

核心交付物：
1. WebChannelAdapter 通道适配器 — 桥接 REST 请求与 MessageGateway
2. ChatController / SettingsController — REST + SSE API 端点
3. A2UI 后端数据模型 — Java record 定义，Jackson 序列化
4. WebAutoConfiguration — 条件化自动配置
5. 前端 SPA — 对话页、设置页、A2UI 渲染器、SSE 客户端

参考文档：
- 架构设计：#[[file:docs/architecture/web-ui.md]]
- 特性设计：#[[file:docs/features/web-ui.md]]
- 编码规范：#[[file:.kiro/steering/coding-standards.md]]
- 集成检查：#[[file:.kiro/steering/integration-checklist.md]]

---

## Architecture

### 整体分层

```
浏览器 (Vue 3 SPA — lifepilot-web)
  ├── ChatView / SettingsView（页面）
  ├── StreamingText / A2uiRenderer（渲染组件）
  ├── Pinia Store（chatStore / a2uiStore / settingsStore）
  └── useChat / useA2uiSignal（composable，fetch + ReadableStream）
        │
        │ HTTP / SSE
        ▼
Spring Boot 后端 (lifepilot Java 项目)
  ├── ChatController / SettingsController（REST + SSE 端点）
  ├── WebChannelAdapter（extends AbstractChannelAdapter）
  ├── A2UI 数据模型（record：A2uiComponent / A2uiSignal / A2uiComponentTree）
  ├── WebProperties（@ConfigurationProperties）
  ├── WebAutoConfiguration（条件化 Bean 注册）
  └── MessageGateway（已有中间件管道：Auth → RateLimit → Security → Router → Execution → Audit）
```

### 后端包结构

```
com.lifepilot.interaction.web
├── config/
│   ├── WebAutoConfiguration.java    # 自动配置
│   └── WebProperties.java           # 配置属性
├── controller/
│   ├── ChatController.java          # 对话 REST + SSE 端点
│   └── SettingsController.java      # 设置 REST 端点
├── adapter/
│   └── WebChannelAdapter.java       # Web 通道适配器
├── model/
│   ├── A2uiComponent.java           # A2UI 组件节点
│   ├── A2uiSignal.java              # A2UI 信号
│   ├── A2uiComponentTree.java       # A2UI 组件树（邻接表）
│   ├── ChatRequest.java             # 发送消息请求体
│   ├── ChatResponse.java            # 非流式响应体
│   ├── SignalRequest.java           # 信号回传请求体
│   ├── SessionInfo.java             # 会话摘要
│   ├── MessageInfo.java             # 消息摘要
│   ├── UserSettings.java            # 用户设置
│   └── SseEvent.java                # SSE 事件封装
└── sse/
    └── SseSessionManager.java       # SSE 连接管理（SseEmitter 生命周期 + 心跳）
```

### 前端项目结构

```
lifepilot-web/
├── index.html
├── package.json
├── vite.config.ts
├── tsconfig.json
├── src/
│   ├── main.ts
│   ├── App.vue
│   ├── router/index.ts
│   ├── stores/
│   │   ├── chat.ts
│   │   ├── a2ui.ts
│   │   └── settings.ts
│   ├── views/
│   │   ├── ChatView.vue
│   │   └── SettingsView.vue
│   ├── components/
│   │   ├── chat/
│   │   │   ├── MessageList.vue
│   │   │   ├── MessageBubble.vue
│   │   │   ├── ChatInput.vue
│   │   │   └── StreamingText.vue
│   │   ├── layout/
│   │   │   ├── AppLayout.vue
│   │   │   └── Sidebar.vue
│   │   ├── a2ui/
│   │   │   ├── A2uiRenderer.vue
│   │   │   ├── A2uiText.vue
│   │   │   ├── A2uiCard.vue
│   │   │   ├── A2uiButton.vue
│   │   │   ├── A2uiTextField.vue
│   │   │   ├── A2uiList.vue
│   │   │   ├── A2uiListItem.vue
│   │   │   ├── A2uiDatePicker.vue
│   │   │   ├── A2uiChip.vue
│   │   │   ├── A2uiDivider.vue
│   │   │   ├── A2uiImage.vue
│   │   │   └── componentCatalog.ts
│   │   └── ui/                      # shadcn-vue 组件
│   ├── composables/
│   │   ├── useChat.ts
│   │   ├── useA2uiSignal.ts
│   │   └── useSettings.ts
│   ├── api/
│   │   └── client.ts
│   ├── types/
│   │   └── index.ts
│   └── assets/styles/main.css
└── public/favicon.ico
```


### 关键技术决策

| # | 决策 | 理由 |
|---|------|------|
| 1 | SSE（SseEmitter）而非 WebSocket/WebFlux | 与现有 Spring MVC 栈一致；AI 对话只需服务端→客户端单向推送；行业标准（OpenAI/DeepSeek 均采用 SSE） |
| 2 | fetch + ReadableStream 而非 EventSource | EventSource 只支持 GET，无法在请求体中携带消息内容；fetch 支持 POST + 自定义请求头 |
| 3 | SseSessionManager 管理 SseEmitter 生命周期 | 集中管理超时、心跳、异常处理；避免 Controller 中散落 SseEmitter 管理逻辑 |
| 4 | A2UI JSON 邻接表协议 | Google 标准化方案（v0.8）；邻接表模型简洁；Vue `<component :is>` 天然适配 |
| 5 | WebChannelAdapter 不直接处理 HTTP 请求 | Controller 负责 HTTP 协议层，WebChannelAdapter 负责 GatewayMessage 转换和 Gateway 调用，职责分离 |

### 依赖接口验证

| 接口 | 源码位置 | 验证状态 |
|------|---------|---------|
| `MessageGateway.process(GatewayMessage)` → `GatewayResponse` | `com.lifepilot.interaction.gateway.MessageGateway` | ✅ 已核对 |
| `AbstractChannelAdapter(MessageGateway, GatewayProperties)` 构造器 | `com.lifepilot.interaction.channel.AbstractChannelAdapter` | ✅ 已核对 |
| `AbstractChannelAdapter.doStart()` / `doStop()` / `doSendResponse(String, GatewayResponse)` | `com.lifepilot.interaction.channel.AbstractChannelAdapter` | ✅ 已核对 |
| `ChannelAdapter.channelType()` / `normalize(Object)` | `com.lifepilot.interaction.channel.ChannelAdapter` | ✅ 已核对 |
| `ChannelType.WEB` 枚举值 | `com.lifepilot.interaction.model.ChannelType` | ✅ 已核对（`WEB("web", false)`） |
| `ResponseContent.StreamingContent(String streamId)` | `com.lifepilot.interaction.model.ResponseContent` | ✅ 已核对 |
| `GatewayMessage` record（messageId, channelType, userId, sessionId, content, attachments, channelMetadata, timestamp, traceHeaders） | `com.lifepilot.interaction.model.GatewayMessage` | ✅ 已核对 |
| `GatewayResponse` record（responseId, channelType, content, attachments, metadata, latency, tokenUsage, statusCode, errorMessage） | `com.lifepilot.interaction.model.GatewayResponse` | ✅ 已核对 |
| `ChannelMetadata.WebMetadata(userAgent, remoteAddr, sessionToken, acceptsSse)` | `com.lifepilot.interaction.model.ChannelMetadata` | ✅ 已核对 |
| `TokenUsage(promptTokens, completionTokens, totalTokens, modelId)` | `com.lifepilot.interaction.model.TokenUsage` | ✅ 已核对 |
| `GatewayAutoConfiguration` — 注册 MiddlewarePipeline + MessageGateway，ApplicationReady 时注册 ChannelAdapter | `com.lifepilot.interaction.config.GatewayAutoConfiguration` | ✅ 已核对 |
| `GatewayProperties.ChannelsProperties.WebChannelProperties(enabled)` | `com.lifepilot.interaction.config.GatewayProperties` | ✅ 已核对（`@DefaultValue("false") boolean enabled`） |
| `MessageContent.TextMessage(text)` / `MessageContent.EventMessage(eventType, payload)` | `com.lifepilot.interaction.model.MessageContent` | ✅ 已核对 |

---

## Components and Interfaces

### 1. WebChannelAdapter

继承 `AbstractChannelAdapter`，桥接 REST Controller 与 MessageGateway。

```java
/**
 * Web 通道适配器，桥接 REST 请求与 MessageGateway 中间件管道。
 */
public class WebChannelAdapter extends AbstractChannelAdapter {

    public WebChannelAdapter(MessageGateway gateway, GatewayProperties properties) {
        super(gateway, properties);
    }

    @Override
    public ChannelType channelType() {
        return ChannelType.WEB;
    }

    @Override
    public GatewayMessage normalize(Object rawMessage) {
        // rawMessage 为 ChatRequest 或 SignalRequest
        // 转换为 GatewayMessage（channelType=WEB, content=TextMessage/EventMessage）
    }

    @Override
    protected void doStart() {
        // Web 通道无需特殊启动逻辑（HTTP 端点由 Spring MVC 管理）
    }

    @Override
    protected void doStop() {
        // 关闭所有活跃 SseEmitter
    }

    @Override
    protected void doSendResponse(String userId, GatewayResponse response) {
        // Web 通道的响应通过 Controller 直接返回，此方法用于异步场景
    }

    /**
     * 同步处理消息（Controller 直接调用）。
     */
    public GatewayResponse processMessage(ChatRequest request, HttpServletRequest httpRequest) {
        var message = buildGatewayMessage(request, httpRequest, false);
        return submitSync(message);
    }

    /**
     * 流式处理消息（Controller 调用，返回 streamId 用于关联 SseEmitter）。
     */
    public GatewayResponse processMessageStreaming(ChatRequest request, HttpServletRequest httpRequest) {
        var message = buildGatewayMessage(request, httpRequest, true);
        return submitSync(message);
    }

    /**
     * 处理 A2UI 信号回传。
     */
    public GatewayResponse processSignal(SignalRequest request, HttpServletRequest httpRequest) {
        var message = buildSignalMessage(request, httpRequest);
        return submitSync(message);
    }
}
```

### 2. ChatController

REST + SSE 端点，处理对话相关请求。

```java
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final WebChannelAdapter adapter;
    private final SseSessionManager sseManager;

    // POST /api/chat/messages — 非流式发送消息
    @PostMapping("/messages")
    public ResponseEntity<ChatResponse> sendMessage(@RequestBody ChatRequest request,
                                                     HttpServletRequest httpRequest);

    // POST /api/chat/messages/stream — SSE 流式发送消息
    @PostMapping("/messages/stream")
    public SseEmitter sendMessageStream(@RequestBody ChatRequest request,
                                         HttpServletRequest httpRequest);

    // GET /api/chat/sessions — 获取会话列表
    @GetMapping("/sessions")
    public ResponseEntity<List<SessionInfo>> listSessions();

    // GET /api/chat/sessions/{id}/messages — 获取会话历史消息
    @GetMapping("/sessions/{id}/messages")
    public ResponseEntity<List<MessageInfo>> getSessionMessages(@PathVariable String id);

    // DELETE /api/chat/sessions/{id} — 删除会话
    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<Void> deleteSession(@PathVariable String id);

    // POST /api/chat/signals — A2UI 信号回传
    @PostMapping("/signals")
    public ResponseEntity<?> handleSignal(@RequestBody SignalRequest request,
                                           HttpServletRequest httpRequest);
}
```

### 3. SettingsController

```java
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    // GET /api/settings — 获取用户设置
    @GetMapping
    public ResponseEntity<UserSettings> getSettings();

    // PUT /api/settings — 更新用户设置
    @PutMapping
    public ResponseEntity<UserSettings> updateSettings(@RequestBody UserSettings settings);
}
```

### 4. SseSessionManager

集中管理 SseEmitter 生命周期、心跳和超时。

```java
/**
 * SSE 会话管理器，管理 SseEmitter 生命周期。
 */
public class SseSessionManager {

    private final ConcurrentHashMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final WebProperties properties;
    private final ScheduledExecutorService heartbeatScheduler;

    /**
     * 创建新的 SseEmitter 并注册到管理器。
     *
     * @param streamId 流式传输标识
     * @return 新创建的 SseEmitter
     */
    public SseEmitter createEmitter(String streamId);

    /**
     * 向指定 SseEmitter 发送事件。
     *
     * @param streamId  流式传输标识
     * @param eventType 事件类型（token / ui / done / error）
     * @param data      事件数据
     */
    public void sendEvent(String streamId, String eventType, Object data);

    /**
     * 关闭并移除指定 SseEmitter。
     */
    public void closeEmitter(String streamId);

    /**
     * 启动心跳调度（按 heartbeat-interval 配置发送心跳事件）。
     */
    public void startHeartbeat();

    /**
     * 停止所有心跳并关闭所有 SseEmitter。
     */
    public void shutdown();
}
```

### 5. WebAutoConfiguration

```java
@AutoConfiguration
@ConditionalOnProperty(name = "lifepilot.gateway.channels.web.enabled", havingValue = "true")
@EnableConfigurationProperties(WebProperties.class)
public class WebAutoConfiguration {

    @Bean
    public WebChannelAdapter webChannelAdapter(MessageGateway gateway,
                                                GatewayProperties gatewayProperties) {
        return new WebChannelAdapter(gateway, gatewayProperties);
    }

    @Bean
    public SseSessionManager sseSessionManager(WebProperties properties) {
        return new SseSessionManager(properties);
    }

    @Bean
    public ChatController chatController(WebChannelAdapter adapter,
                                          SseSessionManager sseManager) {
        return new ChatController(adapter, sseManager);
    }

    @Bean
    public SettingsController settingsController() {
        return new SettingsController();
    }

    @Bean
    public WebMvcConfigurer corsConfigurer(WebProperties properties) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                var cors = properties.cors();
                if (cors.allowedOrigins() == null || cors.allowedOrigins().isEmpty()) {
                    return; // 空配置 = 拒绝所有跨域
                }
                registry.addMapping("/api/**")
                        .allowedOrigins(cors.allowedOrigins().toArray(String[]::new))
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        .allowCredentials(cors.allowCredentials());
            }
        };
    }
}
```

### 6. WebProperties

```java
@ConfigurationProperties(prefix = "lifepilot.web")
public record WebProperties(
        @DefaultValue SseProperties sse,
        @DefaultValue CorsProperties cors
) {
    public record SseProperties(
            @DefaultValue("300000") long timeout,
            @DefaultValue("30000") long heartbeatInterval
    ) {}

    public record CorsProperties(
            @DefaultValue("http://localhost:5173") List<String> allowedOrigins,
            @DefaultValue("true") boolean allowCredentials
    ) {}
}
```

### 7. 前端核心组件接口

#### useChat composable

```typescript
interface UseChatReturn {
  sendMessage: (content: string) => Promise<void>
  isStreaming: Ref<boolean>
  error: Ref<string | null>
  abort: () => void
}

function useChat(): UseChatReturn
```

核心流程：
1. `fetch POST /api/chat/messages/stream`，body 携带 `{ content, sessionId }`
2. 通过 `ReadableStream` 逐行读取 SSE 事件
3. 按 `event:` 行解析事件类型，按 `data:` 行解析 JSON 数据
4. `token` 事件 → `chatStore.streamingContent += content`
5. `ui` 事件 → `a2uiStore.updateComponents(components)`
6. `done` 事件 → 将完整消息存入 `chatStore.messages`，重置流式状态
7. `error` 事件 → 设置 `error` ref，重置流式状态

#### A2uiRenderer

```vue
<!-- 递归渲染 A2UI 组件树 -->
<template>
  <template v-for="component in rootComponents" :key="component.id">
    <component
      :is="resolveComponent(component.type)"
      v-bind="component.properties"
      :signal="component.signal"
    >
      <!-- 递归渲染子节点 -->
      <A2uiRenderer
        v-if="component.children?.length"
        :components="components"
        :root-ids="component.children"
      />
    </component>
  </template>
</template>
```

#### componentCatalog

```typescript
import type { Component } from 'vue'

const catalog: Record<string, Component> = {
  Text: A2uiText,
  Card: A2uiCard,
  Button: A2uiButton,
  TextField: A2uiTextField,
  List: A2uiList,
  ListItem: A2uiListItem,
  DatePicker: A2uiDatePicker,
  Chip: A2uiChip,
  Divider: A2uiDivider,
  Image: A2uiImage,
}

export function resolveComponent(type: string): Component {
  return catalog[type] ?? A2uiFallback // 未注册类型渲染占位符
}
```

#### useA2uiSignal composable

```typescript
interface UseA2uiSignalReturn {
  emitSignal: (signal: A2uiSignal, sessionId: string) => Promise<void>
}

function useA2uiSignal(): UseA2uiSignalReturn
```

调用 `POST /api/chat/signals`，body 携带 `{ name, payload, sessionId }`。


---

## Data Models

### 后端数据模型（Java record）

#### A2UI 核心模型

```java
/**
 * A2UI 组件节点，邻接表中的单个节点。
 *
 * @param id         组件唯一标识
 * @param type       组件类型（对应 componentCatalog 中的注册名）
 * @param properties 组件属性（键值对）
 * @param children   子节点 ID 列表
 * @param signal     用户交互信号（可为 null）
 * @author zsg
 * @since 2026-02-27
 */
public record A2uiComponent(
        String id,
        String type,
        Map<String, Object> properties,
        List<String> children,
        @Nullable A2uiSignal signal
) {
    public A2uiComponent {
        properties = properties != null ? Map.copyOf(properties) : Map.of();
        children = children != null ? List.copyOf(children) : List.of();
    }
}

/**
 * A2UI 信号，用户与组件交互时产生。
 *
 * @param name    信号名称（如 "todo.complete"）
 * @param payload 信号负载数据
 * @author zsg
 * @since 2026-02-27
 */
public record A2uiSignal(
        String name,
        Map<String, Object> payload
) {
    public A2uiSignal {
        payload = payload != null ? Map.copyOf(payload) : Map.of();
    }
}

/**
 * A2UI 组件树，邻接表表示。
 *
 * @param components 组件节点列表（扁平数组，通过 children 引用子节点 ID）
 * @author zsg
 * @since 2026-02-27
 */
public record A2uiComponentTree(
        List<A2uiComponent> components
) {
    public A2uiComponentTree {
        components = components != null ? List.copyOf(components) : List.of();
    }
}
```

#### REST 请求/响应模型

```java
/**
 * 发送消息请求体。
 *
 * @param content   消息文本内容
 * @param sessionId 会话 ID（可为 null，新会话时自动创建）
 * @author zsg
 * @since 2026-02-27
 */
public record ChatRequest(
        String content,
        @Nullable String sessionId
) {}

/**
 * 非流式消息响应体。
 *
 * @param messageId  消息 ID
 * @param content    文本内容
 * @param a2ui       A2UI 组件树（可为 null）
 * @param tokenUsage Token 消耗统计（可为 null）
 * @author zsg
 * @since 2026-02-27
 */
public record ChatResponse(
        String messageId,
        String content,
        @Nullable A2uiComponentTree a2ui,
        @Nullable TokenUsage tokenUsage
) {}

/**
 * A2UI 信号回传请求体。
 *
 * @param name      信号名称
 * @param payload   信号负载数据
 * @param sessionId 会话 ID
 * @author zsg
 * @since 2026-02-27
 */
public record SignalRequest(
        String name,
        Map<String, Object> payload,
        String sessionId
) {
    public SignalRequest {
        payload = payload != null ? Map.copyOf(payload) : Map.of();
    }
}

/**
 * 会话摘要信息。
 *
 * @param id        会话 ID
 * @param title     会话标题（取首条消息摘要）
 * @param createdAt 创建时间
 * @param updatedAt 最后更新时间
 * @author zsg
 * @since 2026-02-27
 */
public record SessionInfo(
        String id,
        String title,
        Instant createdAt,
        Instant updatedAt
) {}

/**
 * 消息摘要信息。
 *
 * @param id        消息 ID
 * @param role      角色（user / assistant）
 * @param content   文本内容
 * @param a2ui      A2UI 组件树（可为 null）
 * @param timestamp 消息时间戳
 * @author zsg
 * @since 2026-02-27
 */
public record MessageInfo(
        String id,
        String role,
        String content,
        @Nullable A2uiComponentTree a2ui,
        Instant timestamp
) {}

/**
 * 用户设置。
 *
 * @param theme       主题（light / dark / system）
 * @param language    语言
 * @param llmProvider LLM Provider 标识
 * @author zsg
 * @since 2026-02-27
 */
public record UserSettings(
        String theme,
        String language,
        String llmProvider
) {}

/**
 * SSE 事件封装。
 *
 * @param eventType 事件类型（token / ui / done / error）
 * @param data      事件数据
 * @author zsg
 * @since 2026-02-27
 */
public record SseEvent(
        String eventType,
        Object data
) {}
```

#### SSE 事件数据模型

| 事件类型 | data JSON 结构 | 说明 |
|---------|---------------|------|
| `token` | `{"content": "增量文本", "index": 0}` | LLM 生成的增量文本片段 |
| `ui` | `{"components": [...]}` | A2UI 组件树 JSON |
| `done` | `{"messageId": "uuid", "tokenUsage": {"promptTokens": 100, "completionTokens": 50, "totalTokens": 150, "modelId": "gpt-4"}}` | 流式传输完成 |
| `error` | `{"code": 500, "message": "错误描述"}` | 处理过程中发生错误 |
| `heartbeat` | `""` | 心跳事件，防止连接被代理断开 |

### 前端 TypeScript 类型

```typescript
// types/index.ts

interface ChatSession {
  id: string
  title: string
  createdAt: string   // ISO 8601
  updatedAt: string
}

interface Message {
  id: string
  role: 'user' | 'assistant'
  content: string
  a2uiComponents?: A2uiComponent[]
  timestamp: number
}

interface A2uiComponent {
  id: string
  type: string
  properties: Record<string, unknown>
  children: string[]
  signal?: A2uiSignal
}

interface A2uiSignal {
  name: string
  payload: Record<string, unknown>
}

interface TokenUsage {
  promptTokens: number
  completionTokens: number
  totalTokens: number
  modelId: string
}

interface UserSettings {
  theme: 'light' | 'dark' | 'system'
  language: string
  llmProvider: string
}

// SSE 事件类型
interface SseTokenEvent {
  content: string
  index: number
}

interface SseDoneEvent {
  messageId: string
  tokenUsage: TokenUsage
}

interface SseErrorEvent {
  code: number
  message: string
}
```

### 配置模型（application.yml 新增）

```yaml
lifepilot:
  web:
    sse:
      timeout: 300000              # SSE 连接超时（毫秒），默认 5 分钟
      heartbeat-interval: 30000    # SSE 心跳间隔（毫秒），默认 30 秒
    cors:
      allowed-origins:
        - "http://localhost:5173"  # Vite 默认开发端口
      allow-credentials: true      # 允许携带 Cookie
```


---

## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of a system — essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.*

### Property 1: ChatRequest 标准化保持通道类型和内容

*For any* valid ChatRequest（非空 content），WebChannelAdapter.normalize() 生成的 GatewayMessage 应满足：channelType == WEB，且 contentAsText() 包含原始 content 文本。

**Validates: Requirements 1.2**

### Property 2: A2uiComponentTree 序列化 round-trip

*For any* valid A2uiComponentTree 实例（包含任意数量的 A2uiComponent 节点，节点可含或不含 signal），Jackson 序列化为 JSON 再反序列化应产生等价的对象。

**Validates: Requirements 4.5**

### Property 3: SignalRequest 标准化保持信号数据

*For any* valid SignalRequest（非空 name、任意 payload、有效 sessionId），WebChannelAdapter 转换为 GatewayMessage 后，消息内容应包含信号的 name 和 payload 数据。

**Validates: Requirements 5.1**

### Property 4: 消息列表按时间顺序排列

*For any* 消息列表（包含任意数量的 user 和 assistant 消息），ChatView 渲染后的消息 DOM 顺序应与消息 timestamp 升序一致。

**Validates: Requirements 9.1**

### Property 5: Token 事件增量拼接

*For any* 有序的 token 事件序列（每个事件包含 content 字符串），useChat composable 处理后的 chatStore.streamingContent 应等于所有 token content 按顺序拼接的结果。

**Validates: Requirements 10.2**

### Property 6: 会话切换清空并重载消息

*For any* chatStore 状态，当 activeSessionId 从 A 变更为 B 时，messages 应被清空后重新加载为会话 B 的历史消息（不包含会话 A 的消息）。

**Validates: Requirements 11.4**

### Property 7: A2UI 组件树更新与渲染完整性

*For any* valid A2uiComponentTree（包含 N 个组件节点，所有 type 均在 componentCatalog 中注册），调用 a2uiStore.updateComponents() 后 A2uiRenderer 应渲染恰好 N 个组件节点。

**Validates: Requirements 11.5, 12.1**

### Property 8: 用户设置 round-trip

*For any* valid UserSettings（theme ∈ {light, dark, system}，非空 language 和 llmProvider），PUT /api/settings 保存后 GET /api/settings 应返回等价的设置对象。

**Validates: Requirements 2.6, 13.3**

### Property 9: 非流式消息处理完整性

*For any* valid ChatRequest（非空 content），POST /api/chat/messages 应返回 HTTP 200 且响应体包含非空 messageId 和 content。

**Validates: Requirements 2.1**

---

## Error Handling

### 后端错误处理

| 场景 | 处理方式 | HTTP 状态码 |
|------|---------|------------|
| 会话 ID 不存在 | 返回 404 + 描述性错误消息 | 404 |
| 信号 name 为空 | 返回 400 + 参数校验错误消息 | 400 |
| 信号 payload 格式无效 | 返回 400 + 参数校验错误消息 | 400 |
| MessageGateway 处理超时 | 返回 504 + 超时错误消息 | 504 |
| LLM 服务不可用 | SSE 发送 error 事件（code=503）后关闭 SseEmitter | 503 |
| SSE 连接超时 | SseEmitter 自动关闭，客户端收到连接断开 | — |
| SseEmitter 发送失败 | 记录 WARN 日志，关闭 SseEmitter | — |
| WebChannelAdapter 启动失败 | 记录 ERROR 日志，触发 AbstractChannelAdapter 指数退避重连 | — |
| CORS 源不在白名单 | 浏览器拒绝请求（后端不返回 CORS 头） | — |

### 前端错误处理

| 场景 | 处理方式 |
|------|---------|
| fetch 网络错误 | useChat 重置流式状态，UI 显示"网络连接失败"错误提示 |
| SSE error 事件 | useChat 将错误信息传递给 UI，显示错误提示 toast |
| SSE 连接中断 | ChatView 显示"连接已断开"提示，提供重试按钮 |
| 设置保存失败 | SettingsView 显示错误提示，保留修改前的值（乐观更新回滚） |
| A2UI 组件 type 未注册 | A2uiRenderer 渲染占位符组件，console.warn 输出警告 |
| A2UI 信号发送失败 | useA2uiSignal 显示错误提示 toast |
| 会话加载失败 | chatStore 保持当前状态，显示错误提示 |

### 全局错误处理策略

后端通过 `@RestControllerAdvice` 统一处理异常，返回标准化错误响应：

```java
public record ErrorResponse(int code, String message, Instant timestamp) {}
```

前端通过 api/client.ts 统一拦截 HTTP 错误响应，非 2xx 状态码自动抛出包含 ErrorResponse 的异常。

---

## Testing Strategy

### 测试框架

| 层 | 框架 | 说明 |
|----|------|------|
| 后端单元测试 | JUnit 5 + Mockito | Mock MessageGateway、Mock SseEmitter |
| 后端属性测试 | JUnit 5 + jqwik | A2UI 序列化 round-trip、请求标准化属性 |
| 后端集成测试 | @SpringBootTest + MockMvc | REST 端点、SSE 端点、自动配置条件 |
| 前端单元测试 | Vitest + @vue/test-utils | Pinia Store、composable、组件渲染 |
| 前端属性测试 | Vitest + fast-check | Token 拼接、消息排序、组件树渲染 |

### 属性测试配置

- 后端：jqwik（Java 属性测试库），每个属性测试最少 100 次迭代
- 前端：fast-check（TypeScript 属性测试库），每个属性测试最少 100 次迭代
- 每个属性测试必须以注释引用 design document 中的 Property 编号
- 标签格式：`Feature: web-ui, Property {number}: {property_text}`

### 后端测试计划

#### 单元测试

- `WebChannelAdapter` 标准化逻辑（ChatRequest → GatewayMessage、SignalRequest → GatewayMessage）
- `SseSessionManager` 生命周期管理（创建、发送事件、关闭、心跳）
- A2UI 数据模型不可变性（record 紧凑构造器防御性拷贝）

#### 属性测试

- **Property 1**：`WebChannelAdapter_normalize_保持通道类型和内容()`
  - 生成随机 ChatRequest（随机非空 content、随机 sessionId）
  - 验证 normalize 输出的 channelType == WEB 且 contentAsText() 包含原始 content
  - Tag: `Feature: web-ui, Property 1: ChatRequest 标准化保持通道类型和内容`

- **Property 2**：`A2uiComponentTree_序列化反序列化_roundTrip()`
  - 生成随机 A2uiComponentTree（随机数量节点、随机 type/properties/children/signal）
  - Jackson serialize → deserialize，验证 equals
  - Tag: `Feature: web-ui, Property 2: A2uiComponentTree 序列化 round-trip`

- **Property 3**：`SignalRequest_标准化_保持信号数据()`
  - 生成随机 SignalRequest（随机 name、随机 payload、随机 sessionId）
  - 验证转换后的 GatewayMessage 包含信号数据
  - Tag: `Feature: web-ui, Property 3: SignalRequest 标准化保持信号数据`

- **Property 8**：`UserSettings_PUT_GET_roundTrip()`（集成测试）
  - 生成随机 UserSettings（theme ∈ {light, dark, system}、随机 language、随机 llmProvider）
  - PUT → GET，验证返回值等价
  - Tag: `Feature: web-ui, Property 8: 用户设置 round-trip`

- **Property 9**：`ChatController_非流式消息_返回有效响应()`（集成测试）
  - 生成随机非空 content
  - POST /api/chat/messages，验证 200 + 非空 messageId + 非空 content
  - Tag: `Feature: web-ui, Property 9: 非流式消息处理完整性`

#### 集成测试

- `WebAutoConfiguration` 条件化 Bean 注册（enabled=true / enabled=false）
- ChatController REST 端点（MockMvc）
- SSE 流式端点（MockMvc + SseEmitter 验证）
- CORS 配置验证
- WebProperties 默认值验证

### 前端测试计划

#### 单元测试

- chatStore：会话切换、消息管理、流式状态
- a2uiStore：updateComponents、clearComponents
- settingsStore：设置读写
- componentCatalog：组件注册、未注册类型回退

#### 属性测试

- **Property 4**：`消息列表_按时间顺序渲染()`
  - 生成随机 Message 数组（随机 timestamp）
  - 渲染 MessageList，验证 DOM 顺序与 timestamp 升序一致
  - Tag: `Feature: web-ui, Property 4: 消息列表按时间顺序排列`

- **Property 5**：`Token事件_增量拼接()`
  - 生成随机 token 事件序列（随机 content 字符串）
  - 依次处理，验证 streamingContent == 所有 content 拼接
  - Tag: `Feature: web-ui, Property 5: Token 事件增量拼接`

- **Property 6**：`会话切换_清空并重载()`
  - 生成随机初始消息列表和目标会话 ID
  - 切换 activeSessionId，验证 messages 被清空后重载
  - Tag: `Feature: web-ui, Property 6: 会话切换清空并重载消息`

- **Property 7**：`A2UI组件树_更新后渲染完整()`
  - 生成随机 A2uiComponentTree（N 个节点，type 均在 catalog 中）
  - updateComponents → 渲染 A2uiRenderer，验证渲染节点数 == N
  - Tag: `Feature: web-ui, Property 7: A2UI 组件树更新与渲染完整性`

#### 组件测试

- ChatView：消息发送、流式显示、错误提示
- SettingsView：设置加载、修改、保存
- A2uiRenderer：递归渲染、占位符回退、信号触发
- StreamingText：增量 Markdown 渲染
- Sidebar：会话列表、切换、删除

### 跨模块集成测试

```java
class WebChannel_MessageGateway_集成测试 {
    @Test
    void Web通道消息_经过完整中间件管道处理() { ... }
}
```

验证 WebChannelAdapter 注册到 MessageGateway 后，消息经过完整中间件管道（Auth → RateLimit → Security → Router → Execution → Audit）处理。
