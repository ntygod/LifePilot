# Web UI 架构设计

> **模块编号**：Phase 5 — 模块 18
> **依赖模块**：Gateway + Channel 适配器（模块 13）、Agent 引擎（模块 2）、LLM Router（模块 1）
> **最后更新**：2026-02-26

---

## 1. 模块定位与职责边界

Web UI 模块为 LifePilot 提供浏览器端交互界面，是 Phase 5 的核心交付物。模块分为两个层面：

- **后端 API 层**：Spring Boot REST Controller + SSE 流式端点，复用 MessageGateway 中间件管道
- **前端 SPA 层**：Vue 3 + Vite + Pinia 单页应用，构建产物打包进 JAR 静态资源

### 职责边界

| 属于本模块 | 不属于本模块 |
|-----------|------------|
| REST API 端点（对话、设置） | 知识库管理页面（模块 19） |
| SSE 流式对话传输 | Skill/MCP 管理页面（模块 19） |
| WebChannelAdapter 通道适配器 | 轨迹回放页面（模块 19） |
| 前端对话页 + 设置页 | 工作流管理页面（模块 19） |
| A2UI Generative UI 渲染器 + 组件目录 | Skill/MCP 管理页面（模块 19） |
| frontend-maven-plugin 构建集成 | 轨迹回放页面（模块 19） |
| 前端路由、状态管理、组件库基础 | CLI HTTP 客户端迁移（Phase 6） |

---

## 2. 核心概念与术语

| 术语 | 定义 |
|------|------|
| A2UI (Agent-to-UI) | Google 提出的声明式 Generative UI 协议（v0.8 Public Preview），Agent 以 JSON 描述 UI 组件树，客户端渲染为原生控件 |
| SSE (Server-Sent Events) | 服务端向客户端单向推送事件的 HTTP 协议，用于流式传输 LLM 生成的 Token 和 A2UI 组件描述 |
| SseEmitter | Spring MVC 提供的 SSE 发射器，支持异步逐块发送事件到客户端 |
| WebChannelAdapter | Web 通道适配器，实现 `ChannelAdapter` 接口，桥接 REST 请求与 MessageGateway |
| frontend-maven-plugin | Maven 插件，在构建阶段自动执行 `npm install` + `npm run build`，将前端产物输出到 `src/main/resources/static/` |
| Pinia | Vue 3 官方状态管理库，管理对话列表、消息流、用户设置等全局状态 |
| shadcn-vue | 基于 Radix Vue 的 Vue 3 组件库，提供无样式（headless）UI 原语 + Tailwind CSS 样式 |

---

## 3. 架构设计

### 3.1 整体架构

```
┌──────────────────────────────────────────────────────────────┐
│                      浏览器 (Vue 3 SPA)                       │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐                    │
│  │ ChatView │  │ Settings │  │  Layout  │                    │
│  │ 对话页面  │  │ 设置页面  │  │ 布局框架  │                    │
│  └────┬─────┘  └────┬─────┘  └──────────┘                    │
│       │              │                                        │
│  ┌────┴──────────────┴──────────────────────┐                │
│  │  ┌─────────────────┐  ┌────────────────┐ │                │
│  │  │ StreamingText   │  │ A2uiRenderer   │ │                │
│  │  │ Markdown 渲染    │  │ Generative UI  │ │                │
│  │  └─────────────────┘  └────────────────┘ │                │
│  │     Pinia Store 层                        │                │
│  │  chatStore / settingsStore / a2uiStore    │                │
│  └────┬──────────────────────────────────────┘                │
│       │  fetch / EventSource (token + ui 事件)                │
└───────┼──────────────────────────────────────────────────────┘
        │ HTTP / SSE
┌───────┼──────────────────────────────────────────────────────┐
│       ▼                                                       │
│  ┌─────────────────────┐                                     │
│  │  ChatController     │  REST + SSE 端点（token + ui 事件）  │
│  │  SettingsController │  设置 API                            │
│  └────┬────────────────┘                                     │
│       │                                                       │
│  ┌────▼────────────────┐                                     │
│  │ WebChannelAdapter   │  ChannelAdapter 实现                 │
│  └────┬────────────────┘                                     │
│       │                                                       │
│  ┌────▼────────────────┐                                     │
│  │  MessageGateway     │  已有中间件管道                       │
│  │  (Auth → RateLimit  │                                     │
│  │   → Security →      │                                     │
│  │   Router →          │                                     │
│  │   Execution →       │                                     │
│  │   Audit)            │                                     │
│  └─────────────────────┘                                     │
│              Spring Boot 后端                                 │
└──────────────────────────────────────────────────────────────┘
```

### 3.2 后端 API 层设计

#### 3.2.1 REST 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/chat/messages` | 发送消息（非流式，返回完整响应） |
| POST | `/api/chat/messages/stream` | 发送消息（SSE 流式响应） |
| GET | `/api/chat/sessions` | 获取会话列表 |
| GET | `/api/chat/sessions/{id}/messages` | 获取会话历史消息 |
| DELETE | `/api/chat/sessions/{id}` | 删除会话 |
| GET | `/api/settings` | 获取用户设置 |
| PUT | `/api/settings` | 更新用户设置 |
| GET | `/api/health` | 健康检查（复用 Actuator） |
| POST | `/api/chat/signals` | A2UI 信号回传（用户与 Generative UI 组件交互） |

#### 3.2.2 SSE 流式协议

SSE 端点返回 `text/event-stream`，事件格式：

```
event: token
data: {"content": "你好", "index": 0}

event: token
data: {"content": "，我是", "index": 1}

event: ui
data: {"components": [{"id": "c1", "type": "Card", "properties": {"title": "今日待办"}, "children": ["c2"]}, {"id": "c2", "type": "Text", "properties": {"value": "完成报告"}}]}

event: done
data: {"messageId": "uuid", "tokenUsage": {"input": 100, "output": 50}}

event: error
data: {"code": 500, "message": "LLM 服务不可用"}
```

事件类型：
- `token`：LLM 生成的增量文本片段
- `ui`：A2UI 组件描述 JSON，前端 A2uiRenderer 接收后渲染为原生 Vue 组件
- `done`：流式传输完成，携带完整消息 ID 和 Token 统计
- `error`：处理过程中发生错误

#### 3.2.3 WebChannelAdapter

```java
// 实现 ChannelAdapter 接口，桥接 REST 请求与 MessageGateway
public class WebChannelAdapter extends AbstractChannelAdapter {
    // channelType() → ChannelType.WEB
    // normalize() → 将 REST 请求体转换为 GatewayMessage
    // sendResponse() → 通过 SseEmitter 推送响应（流式场景）
}
```

WebChannelAdapter 的核心职责：
1. 将 HTTP 请求转换为 `GatewayMessage`（`channelType = WEB`）
2. 调用 `MessageGateway.process()` 走完整中间件管道
3. 流式场景下，通过 `SseEmitter` 逐 Token 推送响应

#### 3.2.4 SSE 实现方案选型

| 方案 | 优势 | 劣势 | 结论 |
|------|------|------|------|
| Spring MVC `SseEmitter` | 与现有 Servlet 栈一致、简单直接 | 需手动管理超时和异常 | ✅ 采用 |
| Spring WebFlux `Flux<ServerSentEvent>` | 响应式原生支持 | 需引入 WebFlux 依赖，与现有 MVC 栈冲突 | ❌ 不采用 |
| WebSocket | 双向通信 | AI 对话场景只需服务端→客户端单向推送，过度设计 | ❌ 不采用 |

选择 `SseEmitter` 的理由：LifePilot 已使用 Spring MVC（`spring-boot-starter-web`），SSE 是 AI 对话流式响应的行业标准方案（OpenAI、DeepSeek、通义千问等均采用 SSE），且 `SseEmitter` 在 Virtual Thread 环境下表现良好。

### 3.3 前端 SPA 层设计

#### 3.3.1 技术选型

| 技术 | 版本 | 用途 |
|------|------|------|
| Vue 3 | 3.5+ | 响应式 UI 框架，Composition API |
| Vite | 6.x | 开发服务器 + 构建工具 |
| Pinia | 3.x | 状态管理 |
| Vue Router | 4.x | 前端路由 |
| shadcn-vue | latest | UI 组件库（基于 Radix Vue + Tailwind CSS） |
| Tailwind CSS | 4.x | 原子化 CSS |
| vue-markdown-renderer | latest | AI 流式 Markdown 渲染（高性能增量 DOM 更新） |
| TypeScript | 5.x | 类型安全 |

#### 3.3.2 前端目录结构

```
src/main/frontend/
├── index.html
├── package.json
├── vite.config.ts
├── tsconfig.json
├── tailwind.config.ts
├── src/
│   ├── main.ts                 # 入口
│   ├── App.vue                 # 根组件
│   ├── router/
│   │   └── index.ts            # 路由配置
│   ├── stores/
│   │   ├── chat.ts             # 对话状态
│   │   ├── a2ui.ts             # A2UI 组件树状态
│   │   └── settings.ts         # 设置状态
│   ├── views/
│   │   ├── ChatView.vue        # 对话页
│   │   └── SettingsView.vue    # 设置页
│   ├── components/
│   │   ├── chat/
│   │   │   ├── MessageList.vue     # 消息列表
│   │   │   ├── MessageBubble.vue   # 消息气泡
│   │   │   ├── ChatInput.vue       # 输入框
│   │   │   └── StreamingText.vue   # 流式文本渲染
│   │   ├── layout/
│   │   │   ├── AppLayout.vue       # 应用布局
│   │   │   └── Sidebar.vue         # 侧边栏
│   │   ├── a2ui/
│   │   │   ├── A2uiRenderer.vue    # A2UI 递归渲染器
│   │   │   ├── A2uiText.vue        # 文本组件
│   │   │   ├── A2uiCard.vue        # 卡片组件
│   │   │   ├── A2uiButton.vue      # 按钮组件
│   │   │   ├── A2uiList.vue        # 列表组件
│   │   │   └── componentCatalog.ts # 组件目录注册表
│   │   └── ui/                     # shadcn-vue 组件
│   ├── composables/
│   │   ├── useChat.ts          # 对话 composable（SSE 连接管理）
│   │   ├── useA2uiSignal.ts    # A2UI 信号 composable
│   │   └── useSettings.ts      # 设置 composable
│   ├── api/
│   │   └── client.ts           # HTTP/SSE 客户端封装
│   ├── types/
│   │   └── index.ts            # TypeScript 类型定义
│   └── assets/
│       └── styles/
│           └── main.css        # 全局样式 + Tailwind 入口
└── public/
    └── favicon.ico
```

#### 3.3.3 状态管理设计

```typescript
// chatStore — 管理对话状态
interface ChatState {
  sessions: ChatSession[]        // 会话列表
  activeSessionId: string | null // 当前活跃会话
  messages: Message[]            // 当前会话消息
  isStreaming: boolean           // 是否正在流式接收
  streamingContent: string       // 流式接收中的内容缓冲
}

// settingsStore — 管理用户设置
interface SettingsState {
  theme: 'light' | 'dark' | 'system'
  language: string
  llmProvider: string
}
```

#### 3.3.4 SSE 客户端实现

前端通过 `EventSource` API 或 `fetch` + `ReadableStream` 消费 SSE 流：

```typescript
// useChat composable 核心逻辑
async function sendMessage(content: string) {
  // 1. 添加用户消息到本地状态
  // 2. 创建 EventSource 连接到 /api/chat/messages/stream
  // 3. 监听 token 事件，增量拼接到 streamingContent
  // 4. 监听 done 事件，将完整消息存入 messages
  // 5. 监听 error 事件，显示错误提示
}
```

选择 `fetch` + `ReadableStream` 而非 `EventSource` 的理由：
- `EventSource` 只支持 GET 请求，无法在请求体中携带消息内容
- `fetch` 支持 POST 请求 + 流式读取响应体
- 可自定义请求头（如 Session ID、认证 Token）

#### 3.3.5 流式 Markdown 渲染

AI 响应通常包含 Markdown 格式内容（代码块、列表、表格等），流式场景下需要增量渲染。

选型对比：

| 方案 | 优势 | 劣势 |
|------|------|------|
| vue-markdown-renderer | Vue 3 原生、增量 DOM 更新、支持 Mermaid/KaTeX、性能极优（100x 更少 DOM 节点） | 较新项目 |
| markdown-it + 手动渲染 | 成熟稳定 | 流式场景需自行处理增量更新，性能差 |
| marked + DOMPurify | 轻量 | 同上，且安全处理需额外配置 |

采用 `vue-markdown-renderer`：专为 AI 流式场景设计，最小化 DOM 更新，支持代码高亮、Mermaid 图表渐进渲染，Vue 3 生态原生支持。

### 3.4 构建集成

#### 3.4.1 frontend-maven-plugin 配置

```xml
<plugin>
    <groupId>com.github.eirslett</groupId>
    <artifactId>frontend-maven-plugin</artifactId>
    <version>1.15.1</version>
    <configuration>
        <workingDirectory>src/main/frontend</workingDirectory>
        <nodeVersion>v22.12.0</nodeVersion>
    </configuration>
    <executions>
        <execution>
            <id>install-node-and-npm</id>
            <goals><goal>install-node-and-npm</goal></goals>
        </execution>
        <execution>
            <id>npm-install</id>
            <goals><goal>npm</goal></goals>
            <configuration>
                <arguments>install</arguments>
            </configuration>
        </execution>
        <execution>
            <id>npm-build</id>
            <goals><goal>npm</goal></goals>
            <phase>generate-resources</phase>
            <configuration>
                <arguments>run build</arguments>
            </configuration>
        </execution>
    </executions>
</plugin>
```

#### 3.4.2 Vite 构建输出

Vite 配置 `build.outDir` 指向 `../resources/static/`，构建产物直接输出到 Spring Boot 静态资源目录：

```typescript
// vite.config.ts
export default defineConfig({
  build: {
    outDir: '../resources/static',
    emptyOutDir: true,
  },
  server: {
    proxy: {
      '/api': 'http://localhost:8080',  // 开发时代理后端 API
    },
  },
})
```

#### 3.4.3 SPA 路由支持

Spring Boot 需配置 SPA 路由回退，将非 API、非静态资源的请求转发到 `index.html`：

```java
// WebMvcConfigurer 配置 SPA 路由回退
@Override
public void addViewControllers(ViewControllerRegistry registry) {
    // 非 /api/** 和非静态资源的请求回退到 index.html
    registry.addViewController("/{path:[^\\.]*}")
            .setViewName("forward:/index.html");
}
```

---

## 4. A2UI Generative UI 架构

### 4.1 A2UI 协议概述

A2UI（Agent-to-UI）是 Google 提出的声明式 Generative UI 协议（v0.8 Public Preview），核心思想是 Agent 以结构化 JSON 描述 UI 组件树，客户端使用原生控件渲染。与传统模板渲染不同，A2UI 让 LLM 动态生成 UI 而非仅生成文本。

协议核心模型：
- **邻接表（Adjacency List）**：组件树以扁平数组表示，每个节点通过 `children` 引用子节点 ID
- **组件目录（Component Catalog）**：客户端声明支持的组件类型及其属性 schema，Agent 只能使用目录中的组件
- **信号（Signal）**：用户交互（点击按钮、输入文本等）通过信号回传给 Agent，触发后续推理

### 4.2 A2UI 数据模型

```json
{
  "components": [
    {
      "id": "c1",
      "type": "Card",
      "properties": { "title": "今日待办", "variant": "outlined" },
      "children": ["c2", "c3"]
    },
    {
      "id": "c2",
      "type": "Text",
      "properties": { "value": "完成项目报告" }
    },
    {
      "id": "c3",
      "type": "Button",
      "properties": { "label": "标记完成", "variant": "primary" },
      "signal": { "name": "todo.complete", "payload": { "todoId": "123" } }
    }
  ]
}
```

### 4.3 Vue A2UI 渲染器设计

Vue 3 的 `<component :is>` 动态组件机制天然适合实现 A2UI 渲染器：

```
A2UI JSON → a2uiStore (Pinia) → A2uiRenderer.vue → <component :is> → 原生 Vue 组件
                                       ↑
                              组件目录注册表
                          (componentCatalog.ts)
```

核心组件：

| 组件 | 职责 |
|------|------|
| `A2uiRenderer.vue` | 递归渲染 A2UI 组件树，根据 `type` 查找组件目录，通过 `<component :is>` 动态渲染 |
| `componentCatalog.ts` | 组件目录注册表，映射 A2UI type → Vue 组件，声明属性 schema |
| `a2uiStore` (Pinia) | 管理 A2UI 组件树状态，处理 SSE `ui` 事件的增量更新 |
| `useA2uiSignal.ts` | 信号 composable，将用户交互封装为信号发送回后端 |

### 4.4 组件目录（Phase 5 初始集合）

| A2UI Type | Vue 组件 | 用途 |
|-----------|---------|------|
| `Text` | `A2uiText.vue` | 纯文本 / Markdown 文本 |
| `Card` | `A2uiCard.vue` | 卡片容器（标题 + 内容 + 操作） |
| `Button` | `A2uiButton.vue` | 操作按钮，触发信号 |
| `TextField` | `A2uiTextField.vue` | 文本输入框 |
| `List` | `A2uiList.vue` | 列表（待办、日程等） |
| `ListItem` | `A2uiListItem.vue` | 列表项 |
| `DatePicker` | `A2uiDatePicker.vue` | 日期选择器 |
| `Chip` | `A2uiChip.vue` | 标签/状态标记 |
| `Divider` | `A2uiDivider.vue` | 分隔线 |
| `Image` | `A2uiImage.vue` | 图片展示 |

组件目录可扩展：后续模块（模块 19）可注册更多组件类型（Chart、Table、CodeBlock 等）。

### 4.5 A2UI 信号流

用户与 A2UI 组件交互时，信号通过 REST API 回传给 Agent：

```
用户点击按钮 → A2uiButton 触发 signal
    → useA2uiSignal composable
    → POST /api/chat/signals { name: "todo.complete", payload: { todoId: "123" }, sessionId: "..." }
    → ChatController → WebChannelAdapter → MessageGateway
    → Agent 处理信号 → 返回新的文本/A2UI 响应
```

### 4.6 A2UI 与流式文本的混合渲染

一条 Agent 响应可以同时包含文本 Token 和 A2UI 组件。SSE 流中 `token` 事件和 `ui` 事件交替出现，前端分别路由到 StreamingText 和 A2uiRenderer：

```
SSE 流:
  event: token → chatStore.streamingContent += content
  event: token → chatStore.streamingContent += content
  event: ui    → a2uiStore.updateComponents(components)
  event: token → chatStore.streamingContent += content
  event: done  → 最终消息包含 text + a2ui components
```

消息数据模型扩展：

```typescript
interface Message {
  id: string
  role: 'user' | 'assistant'
  content: string           // 文本内容（Markdown）
  a2uiComponents?: A2uiComponent[]  // A2UI 组件树（可选）
  timestamp: number
}
```

---

## 5. 关键设计决策

| # | 决策 | 备选方案 | 选择理由 |
|---|------|---------|---------|
| 1 | SSE 流式传输（SseEmitter） | WebSocket / WebFlux Flux | SSE 是 AI 对话流式响应的行业标准；与现有 Spring MVC 栈一致；单向推送足够 |
| 2 | Vue 3 + shadcn-vue | React + shadcn/ui | Phase 5 评估结论：中文社区生态强、Vercel AI SDK v6 支持 Vue composables、frontend-maven-plugin 集成成熟 |
| 3 | vue-markdown-renderer | markdown-it / marked | 专为 AI 流式 Markdown 设计，增量 DOM 更新性能极优，Vue 3 原生支持 |
| 4 | fetch + ReadableStream | EventSource API | EventSource 只支持 GET，无法携带 POST 请求体；fetch 支持自定义请求头和 POST |
| 5 | 前端源码放 `src/main/frontend/` | 独立仓库 / 项目根目录 | 单仓库管理，frontend-maven-plugin 统一构建，部署为单 JAR |
| 6 | Pinia 状态管理 | Vuex / 组件本地状态 | Vue 3 官方推荐、TypeScript 友好、Composition API 原生支持 |
| 7 | Tailwind CSS 4 | 传统 CSS / CSS Modules | 原子化 CSS 开发效率高、与 shadcn-vue 天然配合、构建产物体积小 |
| 8 | A2UI 协议 + 自建 Vue 渲染器 | 纯文本 SSE / 自定义 JSON 协议 / React Server Components | A2UI 是 Google 标准化方案（v0.8），邻接表模型简洁；Vue `<component :is>` 天然适配动态渲染；组件目录机制可扩展；后端无需感知前端框架 |
| 9 | A2UI 组件内嵌 SSE 流 | 独立 WebSocket 通道 / 轮询 | 复用已有 SSE 通道，`ui` 事件与 `token` 事件交替传输，无需额外连接；简化前端连接管理 |

---

## 6. 与已有模块的集成点

### 6.1 MessageGateway 集成

Web UI 的 REST Controller 通过 `WebChannelAdapter` 接入 `MessageGateway`，复用完整的中间件管道（Auth → RateLimit → Security → Router → Execution → Audit）。这意味着 Web UI 自动获得：

- 认证鉴权（Session 模式，`lifepilot.gateway.auth.web.session`）
- 请求限流
- 安全检查（Prompt 注入检测、敏感数据过滤）
- 智能路由（快速路径命令直接分发）
- 审计日志

### 6.2 ChannelType.WEB

`ChannelType` 枚举已包含 `WEB("web", false)`，无需修改。`WebChannelAdapter` 实现 `ChannelAdapter` 接口，注册到 `MessageGateway`。

### 6.3 ResponseContent 流式支持

`ResponseContent.StreamingContent(streamId)` 已定义，用于标识流式传输通道。SSE 端点在收到 `StreamingContent` 响应时，通过 `streamId` 关联 `SseEmitter`，逐 Token 推送内容。

### 6.4 配置集成

Web 通道配置已在 `application.yml` 中预留：

```yaml
lifepilot:
  gateway:
    channels:
      web:
        enabled: false  # Phase 5 实现后改为 true
```

---

## 7. 安全设计

### 7.1 认证方案

Phase 5 采用 Session 认证（已在 Gateway Auth 中间件中实现）：

- 本地部署场景：默认信任 localhost 请求，无需登录
- 远程访问场景：简单密码认证 + Session Cookie

### 7.2 CORS 配置

开发模式下 Vite 开发服务器（端口 5173）需要跨域访问后端 API（端口 8080）：

- 开发环境：Vite proxy 代理 `/api` 请求到后端，无需 CORS
- 生产环境：前端静态资源由 Spring Boot 提供，同源，无需 CORS

### 7.3 XSS 防护

- Markdown 渲染使用 `vue-markdown-renderer` 内置的 HTML 净化
- 用户输入在后端经过 Security 中间件的敏感数据过滤

---

## 8. 调研参考

### 8.1 开源项目参考

| 项目 | Stars | 技术栈 | 借鉴点 |
|------|-------|--------|--------|
| [Open WebUI](https://github.com/open-webui/open-webui) | 80k+ | SvelteKit + FastAPI | 对话 UI 交互模式、会话管理、流式渲染架构 |
| [LobeChat](https://lobehub.com) | 70k+ | React + Next.js | 多模型切换 UI、对话分支、插件系统 UI |
| [Chatbox](https://github.com/Bin-Huang/chatbox) | 25k+ | Electron + React | 桌面端 AI 对话 UI、多 Provider 配置界面 |
| [shadcn-vue](https://github.com/unovue/shadcn-vue) | 5k+ | Vue 3 + Radix Vue | 无样式组件库、Tailwind CSS 集成模式 |
| [vue-markdown-renderer](https://github.com/Simon-He95/vue-markdown-renderer) | 新兴 | Vue 3 | AI 流式 Markdown 增量渲染、100x 更少 DOM 节点 |

### 8.2 技术方案参考

| 方案 | 来源 | 借鉴点 |
|------|------|--------|
| Spring Boot SSE + SseEmitter | [Baeldung: Spring MVC SSE](https://www.baeldung.com/spring-mvc-sse-streams) | SseEmitter 超时管理、异常处理模式 |
| Vercel AI SDK Vue composables | [AI SDK Docs](https://sdk.vercel.ai/docs/reference/ai-sdk-ui/use-chat) | useChat composable 设计模式、SSE 流协议格式 |
| frontend-maven-plugin + Vite | [jessym.com](https://www.jessym.com/articles/bundling-react-vite-with-spring-boot) | 单 JAR 打包前端产物的 Maven 集成方案 |
| AI 对话流式最佳实践 | [proagenticworkflows.ai](https://proagenticworkflows.ai/best-practices-streaming-llm-responses-front-end-stack) | SSE vs WebSocket vs fetch streaming 选型分析 |
| Google A2UI 协议 | [Google A2UI Spec](https://github.com/anthropics/a2ui) | 声明式 Generative UI 协议，邻接表组件树模型，组件目录 + 信号机制 |

> 以上参考来源均为 2024-2026 年发表的技术文章和开源项目，内容已重新组织表述以符合许可要求。
