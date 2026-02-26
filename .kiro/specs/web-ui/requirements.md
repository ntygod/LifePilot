# Requirements Document

## Introduction

Web UI 模块（Phase 5 — 模块 18）为 LifePilot AI 助手平台提供浏览器端交互界面。采用前后端彻底分离架构：后端（Java Spring Boot 项目）提供 REST/SSE API 层，前端（独立 Vue 3 项目 `lifepilot-web`）独立构建部署。

核心能力包括：
- SSE 流式对话（LLM Token 逐字推送）
- A2UI Generative UI（Agent 以 JSON 协议动态生成交互式 UI 组件）
- 会话管理与设置管理
- 复用 MessageGateway 中间件管道（认证、限流、安全、审计）

参考文档：
- 架构设计：#[[file:docs/architecture/web-ui.md]]
- 特性设计：#[[file:docs/features/web-ui.md]]
- 编码规范：#[[file:.kiro/steering/coding-standards.md]]
- 集成检查：#[[file:.kiro/steering/integration-checklist.md]]

## Glossary

- **WebChannelAdapter**：Web 通道适配器，继承 `AbstractChannelAdapter`，桥接 REST 请求与 MessageGateway 中间件管道
- **SSE (Server-Sent Events)**：服务端向客户端单向推送事件的 HTTP 协议，用于流式传输 LLM Token 和 A2UI 组件描述
- **SseEmitter**：Spring MVC 提供的 SSE 发射器，支持异步逐块发送事件到客户端
- **A2UI (Agent-to-UI)**：Google 提出的声明式 Generative UI 协议（v0.8），Agent 以 JSON 邻接表描述 UI 组件树，客户端渲染为原生控件
- **A2UI_Component**：A2UI 组件树中的单个节点，包含 id、type、properties、children、signal 字段
- **A2UI_Signal**：用户与 A2UI 组件交互时产生的信号，包含 name 和 payload，回传给 Agent 触发后续推理
- **Component_Catalog**：A2UI 组件目录注册表，映射组件 type 到 Vue 组件实现，声明属性 schema
- **ChatController**：后端 REST Controller，提供对话相关 API 端点（发送消息、会话管理、历史消息、信号回传）
- **SettingsController**：后端 REST Controller，提供用户设置 API 端点
- **MessageGateway**：已有的统一消息网关，提供 6 层中间件管道（Auth → RateLimit → Security → Router → Execution → Audit）
- **Pinia_Store**：Vue 3 状态管理单元，管理对话列表、消息流、A2UI 组件树、用户设置等全局状态
- **StreamingText**：前端流式文本渲染组件，支持增量 Markdown 渲染
- **A2uiRenderer**：前端 A2UI 递归渲染器组件，根据组件 type 查找 Component_Catalog 并通过 `<component :is>` 动态渲染

## Requirements

### Requirement 1: WebChannelAdapter 通道适配器

**User Story:** As a 后端开发者, I want Web 通道适配器桥接 REST 请求与 MessageGateway, so that Web UI 的所有请求自动经过认证、限流、安全、审计等中间件管道处理。

#### Acceptance Criteria

1. THE WebChannelAdapter SHALL 继承 AbstractChannelAdapter 并返回 ChannelType.WEB 作为通道类型
2. WHEN WebChannelAdapter 接收到 REST 请求体, THE WebChannelAdapter SHALL 将请求体转换为 GatewayMessage（channelType = WEB）
3. WHEN MessageGateway 返回 GatewayResponse, THE WebChannelAdapter SHALL 通过 SseEmitter 推送流式响应或直接返回非流式响应
4. WHILE `lifepilot.gateway.channels.web.enabled` 配置为 true, THE WebChannelAdapter SHALL 注册到 Spring 容器并启动
5. WHILE `lifepilot.gateway.channels.web.enabled` 配置为 false, THE WebChannelAdapter SHALL 不注册到 Spring 容器
6. IF WebChannelAdapter 启动失败, THEN THE WebChannelAdapter SHALL 记录错误日志并触发指数退避重连

### Requirement 2: REST API 端点

**User Story:** As a 前端开发者, I want 后端提供完整的 REST API 端点, so that 前端 SPA 可以通过 HTTP 调用实现对话、会话管理和设置管理功能。

#### Acceptance Criteria

1. WHEN 前端发送 POST 请求到 `/api/chat/messages`, THE ChatController SHALL 将消息提交到 MessageGateway 并返回完整响应（非流式）
2. WHEN 前端发送 GET 请求到 `/api/chat/sessions`, THE ChatController SHALL 返回当前用户的会话列表
3. WHEN 前端发送 GET 请求到 `/api/chat/sessions/{id}/messages`, THE ChatController SHALL 返回指定会话的历史消息列表
4. WHEN 前端发送 DELETE 请求到 `/api/chat/sessions/{id}`, THE ChatController SHALL 删除指定会话及其所有消息
5. WHEN 前端发送 GET 请求到 `/api/settings`, THE SettingsController SHALL 返回当前用户设置
6. WHEN 前端发送 PUT 请求到 `/api/settings`, THE SettingsController SHALL 更新用户设置并返回更新后的设置
7. IF 请求的会话 ID 不存在, THEN THE ChatController SHALL 返回 HTTP 404 状态码和描述性错误消息

### Requirement 3: SSE 流式对话端点

**User Story:** As a 用户, I want LLM 响应以流式方式逐字推送到浏览器, so that 我可以在 Agent 生成过程中实时看到响应内容，获得流畅的对话体验。

#### Acceptance Criteria

1. WHEN 前端发送 POST 请求到 `/api/chat/messages/stream`, THE ChatController SHALL 返回 `text/event-stream` 类型的 SseEmitter 响应
2. WHILE SseEmitter 处于活跃状态, THE ChatController SHALL 以 `token` 事件类型推送 LLM 生成的增量文本片段
3. WHEN Agent 生成 A2UI 组件描述, THE ChatController SHALL 以 `ui` 事件类型推送 A2UI 组件 JSON
4. WHEN 流式传输完成, THE ChatController SHALL 发送 `done` 事件（携带 messageId 和 tokenUsage）并关闭 SseEmitter
5. IF 流式传输过程中发生错误, THEN THE ChatController SHALL 发送 `error` 事件（携带错误码和错误消息）并关闭 SseEmitter
6. THE ChatController SHALL 从 WebProperties 读取 SSE 连接超时时间（默认 300000 毫秒）
7. WHILE SSE 连接处于空闲状态, THE ChatController SHALL 按配置的心跳间隔（默认 30000 毫秒）发送心跳事件以防止连接被代理断开

### Requirement 4: A2UI 后端数据模型

**User Story:** As a 后端开发者, I want A2UI 协议的数据模型以 Java record 定义, so that Agent 生成的 UI 组件描述可以类型安全地序列化为 JSON 并通过 SSE 推送到前端。

#### Acceptance Criteria

1. THE A2uiComponent record SHALL 包含 id（String）、type（String）、properties（Map）、children（List of String）、signal（A2uiSignal，可为 null）字段
2. THE A2uiSignal record SHALL 包含 name（String）和 payload（Map）字段
3. THE A2uiComponentTree record SHALL 包含 components（List of A2uiComponent）字段，表示邻接表组件树
4. WHEN A2uiComponentTree 序列化为 JSON, THE Jackson 序列化器 SHALL 生成符合 A2UI 协议规范的 JSON 结构
5. FOR ALL 有效的 A2uiComponentTree 实例, 序列化为 JSON 再反序列化 SHALL 产生等价的对象（round-trip 属性）

### Requirement 5: A2UI 信号回传端点

**User Story:** As a 用户, I want 与 Agent 生成的 UI 组件交互（点击按钮、输入文本等）时触发后续推理, so that 我可以通过 Generative UI 完成交互式操作而非仅限于文本对话。

#### Acceptance Criteria

1. WHEN 前端发送 POST 请求到 `/api/chat/signals`（携带 signal name、payload、sessionId）, THE ChatController SHALL 将信号转换为 GatewayMessage 并提交到 MessageGateway
2. WHEN 信号处理完成, THE ChatController SHALL 返回 Agent 的响应（支持流式和非流式两种模式）
3. IF 信号的 sessionId 不存在, THEN THE ChatController SHALL 返回 HTTP 404 状态码和描述性错误消息
4. IF 信号的 name 为空或 payload 格式无效, THEN THE ChatController SHALL 返回 HTTP 400 状态码和描述性错误消息

### Requirement 6: CORS 配置

**User Story:** As a 前端开发者, I want 后端正确配置 CORS, so that 前端 SPA 在不同端口或域名下可以正常调用后端 API。

#### Acceptance Criteria

1. THE WebAutoConfiguration SHALL 注册 CORS 配置，允许 `/api/**` 路径的跨域请求
2. THE CORS 配置 SHALL 从 WebProperties 读取 `lifepilot.web.cors.allowed-origins`（默认 `http://localhost:5173`）
3. THE CORS 配置 SHALL 允许 GET、POST、PUT、DELETE、OPTIONS 方法
4. THE CORS 配置 SHALL 允许携带凭证（Cookie），由 `lifepilot.web.cors.allow-credentials`（默认 true）控制
5. IF `lifepilot.web.cors.allowed-origins` 配置为空, THEN THE WebAutoConfiguration SHALL 拒绝所有跨域请求

### Requirement 7: Web 配置属性

**User Story:** As a 运维人员, I want Web 模块的所有业务可调参数通过 application.yml 外部化配置, so that 我可以在不修改代码的情况下调整 SSE 超时、心跳间隔、CORS 策略等参数。

#### Acceptance Criteria

1. THE WebProperties SHALL 通过 `@ConfigurationProperties("lifepilot.web")` 绑定配置
2. THE WebProperties SHALL 包含 SSE 超时时间配置（`lifepilot.web.sse.timeout`，默认 300000 毫秒）
3. THE WebProperties SHALL 包含 SSE 心跳间隔配置（`lifepilot.web.sse.heartbeat-interval`，默认 30000 毫秒）
4. THE WebProperties SHALL 包含 CORS 允许源配置（`lifepilot.web.cors.allowed-origins`，默认 `http://localhost:5173`）
5. THE WebProperties SHALL 包含 CORS 允许凭证配置（`lifepilot.web.cors.allow-credentials`，默认 true）
6. THE application.yml SHALL 显式声明所有 Web 配置项及默认值

### Requirement 8: 前端项目脚手架

**User Story:** As a 前端开发者, I want 独立的 Vue 3 前端项目（lifepilot-web）搭建完成, so that 我可以在此基础上开发对话页面和设置页面。

#### Acceptance Criteria

1. THE lifepilot-web 项目 SHALL 使用 Vue 3 + Vite + TypeScript + Pinia + Vue Router 初始化
2. THE lifepilot-web 项目 SHALL 集成 shadcn-vue 组件库和 Tailwind CSS
3. THE lifepilot-web 项目 SHALL 包含 AppLayout 布局组件（侧边栏 + 主内容区）
4. THE lifepilot-web 项目 SHALL 配置 Vue Router 路由（对话页 `/` 和设置页 `/settings`）
5. THE lifepilot-web 项目 SHALL 通过 `VITE_API_BASE` 环境变量配置后端 API 基地址（默认 `http://localhost:8080`）
6. THE lifepilot-web 项目 SHALL 在 vite.config.ts 中配置 `/api` 代理到后端地址

### Requirement 9: 对话页面

**User Story:** As a 用户, I want 在浏览器中与 AI Agent 进行流式对话, so that 我可以实时看到 Agent 的响应并管理多个对话会话。

#### Acceptance Criteria

1. THE ChatView SHALL 包含消息列表区域，按时间顺序显示用户消息和 Agent 响应
2. THE ChatView SHALL 包含消息输入框，支持按 Enter 发送消息
3. WHEN 用户发送消息, THE ChatView SHALL 通过 fetch + ReadableStream 调用 SSE 流式端点并逐字显示 Agent 响应
4. WHILE SSE 流式接收进行中, THE ChatView SHALL 显示加载指示器并禁用发送按钮
5. THE ChatView SHALL 使用 StreamingText 组件渲染 Agent 响应中的 Markdown 内容（支持代码高亮）
6. THE Sidebar SHALL 显示会话列表，支持切换当前会话和删除会话
7. WHEN 用户切换会话, THE ChatView SHALL 加载目标会话的历史消息
8. IF SSE 连接中断或收到 error 事件, THEN THE ChatView SHALL 显示错误提示信息

### Requirement 10: SSE 客户端封装

**User Story:** As a 前端开发者, I want 统一的 SSE 客户端封装, so that 对话页面可以通过简洁的 API 消费 SSE 流并正确处理 token、ui、done、error 四种事件类型。

#### Acceptance Criteria

1. THE useChat composable SHALL 封装 fetch + ReadableStream 逻辑，支持 POST 请求发送消息并流式读取响应
2. WHEN SSE 流中收到 `token` 事件, THE useChat composable SHALL 将增量文本拼接到 chatStore.streamingContent
3. WHEN SSE 流中收到 `ui` 事件, THE useChat composable SHALL 将 A2UI 组件 JSON 路由到 a2uiStore.updateComponents()
4. WHEN SSE 流中收到 `done` 事件, THE useChat composable SHALL 将完整消息（含文本和 A2UI 组件）存入 chatStore.messages 并重置流式状态
5. WHEN SSE 流中收到 `error` 事件, THE useChat composable SHALL 将错误信息传递给 UI 层显示
6. IF fetch 请求失败（网络错误）, THEN THE useChat composable SHALL 抛出可捕获的错误并重置流式状态

### Requirement 11: Pinia 状态管理

**User Story:** As a 前端开发者, I want 全局状态通过 Pinia Store 统一管理, so that 对话状态、A2UI 组件树状态和用户设置在组件间共享且响应式更新。

#### Acceptance Criteria

1. THE chatStore SHALL 管理会话列表（sessions）、当前活跃会话 ID（activeSessionId）、当前会话消息（messages）、流式状态（isStreaming）和流式内容缓冲（streamingContent）
2. THE a2uiStore SHALL 管理当前消息的 A2UI 组件树（components），提供 updateComponents 和 clearComponents 方法
3. THE settingsStore SHALL 管理主题（theme）、语言（language）和 LLM Provider（llmProvider）设置
4. WHEN chatStore.activeSessionId 变更, THE chatStore SHALL 自动清空 messages 并从后端加载目标会话的历史消息
5. WHEN a2uiStore.updateComponents 被调用, THE a2uiStore SHALL 替换当前组件树并触发 A2uiRenderer 重新渲染

### Requirement 12: A2UI 前端渲染器

**User Story:** As a 用户, I want Agent 生成的 UI 组件（卡片、按钮、列表等）在浏览器中渲染为可交互的原生 Vue 组件, so that 我可以通过 Generative UI 完成交互式操作。

#### Acceptance Criteria

1. THE A2uiRenderer SHALL 接收 A2UI 组件树 JSON，递归遍历邻接表并通过 Vue `<component :is>` 动态渲染每个节点
2. THE componentCatalog SHALL 维护 A2UI type 到 Vue 组件的映射注册表，初始包含 10 个基础组件：Text、Card、Button、TextField、List、ListItem、DatePicker、Chip、Divider、Image
3. WHEN A2UI 组件包含 signal 字段, THE 对应 Vue 组件 SHALL 在用户交互时调用 useA2uiSignal composable 将信号发送到后端 `/api/chat/signals` 端点
4. IF A2UI 组件的 type 在 componentCatalog 中未注册, THEN THE A2uiRenderer SHALL 渲染一个占位符组件并在控制台输出警告
5. THE A2uiRenderer SHALL 支持文本与 A2UI 组件的混合渲染（一条 Agent 响应同时包含 Markdown 文本和 A2UI 组件树）

### Requirement 13: 设置页面

**User Story:** As a 用户, I want 在设置页面管理主题、语言和 LLM Provider 偏好, so that 我可以自定义 Web UI 的外观和 AI 行为。

#### Acceptance Criteria

1. THE SettingsView SHALL 提供主题切换选项（亮色 / 暗色 / 跟随系统）
2. THE SettingsView SHALL 提供 LLM Provider 选择选项
3. WHEN 用户修改设置, THE SettingsView SHALL 调用 PUT `/api/settings` 持久化设置并更新 settingsStore
4. WHEN 设置页面加载, THE SettingsView SHALL 调用 GET `/api/settings` 获取当前设置并初始化表单
5. IF 设置保存失败, THEN THE SettingsView SHALL 显示错误提示并保留用户修改前的值

### Requirement 14: 后端自动配置

**User Story:** As a 后端开发者, I want Web 模块通过 Spring Boot 自动配置机制注册所有 Bean, so that 启用 Web 通道时所有组件自动就绪，禁用时不影响其他模块。

#### Acceptance Criteria

1. THE WebAutoConfiguration SHALL 在 `lifepilot.gateway.channels.web.enabled=true` 时注册 WebChannelAdapter、ChatController、SettingsController 和 CORS 配置
2. THE WebAutoConfiguration SHALL 注册 WebProperties 配置绑定
3. WHILE `lifepilot.gateway.channels.web.enabled` 为 false, THE WebAutoConfiguration SHALL 不注册任何 Web 相关 Bean（ChatController、SettingsController、WebChannelAdapter）
4. THE WebAutoConfiguration SHALL 依赖 GatewayAutoConfiguration 确保 MessageGateway 和中间件管道已就绪
