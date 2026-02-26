# Implementation Plan: Web UI

## Overview

Web UI 模块（Phase 5 — 模块 18）采用前后端分离架构实现。后端先行：先完成数据模型、配置属性、通道适配器、SSE 管理器、REST Controller 和自动配置；再搭建前端项目脚手架，实现类型定义、API 客户端、Pinia Store、composable、页面组件和 A2UI 渲染器。每个任务按依赖顺序递增构建，最终通过集成测试验证端到端流程。

## Tasks

- [x] 1. 实现后端 A2UI 数据模型和 REST 请求/响应模型
  - [x] 1.1 创建 A2UI 核心 record（A2uiComponent、A2uiSignal、A2uiComponentTree）
    - 在 `com.lifepilot.interaction.web.model` 包下创建三个 record
    - A2uiComponent 包含 id、type、properties（Map）、children（List）、signal（@Nullable A2uiSignal）
    - A2uiSignal 包含 name、payload（Map）
    - A2uiComponentTree 包含 components（List<A2uiComponent>）
    - 紧凑构造器中使用 Map.copyOf() / List.copyOf() 防御性拷贝
    - _Requirements: 4.1, 4.2, 4.3_

  - [ ]* 1.2 编写 A2uiComponentTree 序列化 round-trip 属性测试
    - **Property 2: A2uiComponentTree 序列化 round-trip**
    - 使用 jqwik 生成随机 A2uiComponentTree，Jackson serialize → deserialize 验证 equals
    - Tag: `Feature: web-ui, Property 2: A2uiComponentTree 序列化 round-trip`
    - **Validates: Requirements 4.5**

  - [x] 1.3 创建 REST 请求/响应 record（ChatRequest、ChatResponse、SignalRequest、SessionInfo、MessageInfo、UserSettings、SseEvent）
    - 在 `com.lifepilot.interaction.web.model` 包下创建所有 REST 模型 record
    - SignalRequest 紧凑构造器中 payload 使用 Map.copyOf() 防御性拷贝
    - 创建 ErrorResponse record 用于统一错误响应
    - _Requirements: 2.1, 2.2, 2.3, 2.7, 5.1, 13.1, 13.2_

- [x] 2. 实现 WebProperties 配置属性和 application.yml 更新
  - [x] 2.1 创建 WebProperties record
    - 在 `com.lifepilot.interaction.web.config` 包下创建 WebProperties
    - 使用 `@ConfigurationProperties("lifepilot.web")` 绑定
    - 嵌套 SseProperties（timeout=300000, heartbeatInterval=30000）
    - 嵌套 CorsProperties（allowedOrigins=["http://localhost:5173"], allowCredentials=true）
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5_

  - [x] 2.2 更新 application.yml 声明 Web 配置项
    - 在 application.yml 中添加 `lifepilot.web.sse` 和 `lifepilot.web.cors` 配置段
    - 显式声明所有配置项及默认值
    - _Requirements: 7.6_

- [x] 3. 实现 WebChannelAdapter 通道适配器
  - [x] 3.1 创建 WebChannelAdapter 类
    - 在 `com.lifepilot.interaction.web.adapter` 包下创建 WebChannelAdapter
    - 继承 AbstractChannelAdapter，channelType() 返回 ChannelType.WEB
    - 实现 normalize() 方法：ChatRequest → GatewayMessage（TextMessage）、SignalRequest → GatewayMessage（EventMessage）
    - 实现 processMessage()、processMessageStreaming()、processSignal() 方法
    - 实现 doStart()（无特殊逻辑）、doStop()（关闭活跃 SseEmitter）、doSendResponse()
    - _Requirements: 1.1, 1.2, 1.3, 1.6, 5.1_

  - [ ]* 3.2 编写 ChatRequest 标准化属性测试
    - **Property 1: ChatRequest 标准化保持通道类型和内容**
    - 使用 jqwik 生成随机 ChatRequest，验证 normalize 输出 channelType==WEB 且 contentAsText() 包含原始 content
    - Tag: `Feature: web-ui, Property 1: ChatRequest 标准化保持通道类型和内容`
    - **Validates: Requirements 1.2**

  - [ ]* 3.3 编写 SignalRequest 标准化属性测试
    - **Property 3: SignalRequest 标准化保持信号数据**
    - 使用 jqwik 生成随机 SignalRequest，验证转换后的 GatewayMessage 包含信号 name 和 payload
    - Tag: `Feature: web-ui, Property 3: SignalRequest 标准化保持信号数据`
    - **Validates: Requirements 5.1**

  - [ ]* 3.4 编写 WebChannelAdapter 单元测试
    - 测试 normalize() 对 ChatRequest 和 SignalRequest 的转换逻辑
    - 测试 processMessage() 调用 MessageGateway 并返回响应
    - Mock MessageGateway 验证交互
    - _Requirements: 1.1, 1.2, 1.3_

- [x] 4. 实现 SseSessionManager
  - [x] 4.1 创建 SseSessionManager 类
    - 在 `com.lifepilot.interaction.web.sse` 包下创建 SseSessionManager
    - 使用 ConcurrentHashMap 管理 streamId → SseEmitter 映射
    - 实现 createEmitter()（从 WebProperties 读取 timeout）、sendEvent()、closeEmitter()
    - 实现 startHeartbeat()（ScheduledExecutorService 按 heartbeatInterval 发送心跳）
    - 实现 shutdown()（停止心跳、关闭所有 SseEmitter）
    - _Requirements: 3.1, 3.6, 3.7_

  - [ ]* 4.2 编写 SseSessionManager 单元测试
    - 测试 createEmitter 创建并注册 SseEmitter
    - 测试 sendEvent 向指定 SseEmitter 发送事件
    - 测试 closeEmitter 关闭并移除 SseEmitter
    - 测试心跳调度逻辑
    - _Requirements: 3.6, 3.7_

- [x] 5. Checkpoint — 后端核心组件验证
  - Ensure all tests pass, ask the user if questions arise.

- [x] 6. 实现 ChatController 和 SettingsController
  - [x] 6.1 创建 ChatController
    - 在 `com.lifepilot.interaction.web.controller` 包下创建 ChatController
    - POST /api/chat/messages — 非流式发送消息，调用 WebChannelAdapter.processMessage()
    - POST /api/chat/messages/stream — SSE 流式端点，返回 SseEmitter，调用 processMessageStreaming()
    - GET /api/chat/sessions — 获取会话列表
    - GET /api/chat/sessions/{id}/messages — 获取会话历史消息
    - DELETE /api/chat/sessions/{id} — 删除会话
    - POST /api/chat/signals — A2UI 信号回传，调用 processSignal()
    - 会话 ID 不存在返回 404，信号参数无效返回 400
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.7, 3.1, 3.2, 3.3, 3.4, 3.5, 5.1, 5.2, 5.3, 5.4_

  - [x] 6.2 创建 SettingsController
    - 在 `com.lifepilot.interaction.web.controller` 包下创建 SettingsController
    - GET /api/settings — 获取用户设置
    - PUT /api/settings — 更新用户设置
    - _Requirements: 2.5, 2.6_

  - [x] 6.3 创建全局异常处理器 WebExceptionHandler
    - 使用 @RestControllerAdvice 统一处理异常
    - 返回标准化 ErrorResponse（code, message, timestamp）
    - _Requirements: 2.7, 5.3, 5.4_

  - [ ]* 6.4 编写 ChatController 集成测试（MockMvc）
    - 测试 POST /api/chat/messages 非流式端点
    - 测试 GET /api/chat/sessions 会话列表
    - 测试 GET /api/chat/sessions/{id}/messages 历史消息
    - 测试 DELETE /api/chat/sessions/{id} 删除会话
    - 测试 POST /api/chat/signals 信号回传
    - 测试 404 和 400 错误场景
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.7, 5.1, 5.3, 5.4_

  - [ ]* 6.5 编写 SettingsController 集成测试（MockMvc）
    - 测试 GET /api/settings 获取设置
    - 测试 PUT /api/settings 更新设置
    - _Requirements: 2.5, 2.6_

  - [ ]* 6.6 编写用户设置 round-trip 属性测试
    - **Property 8: 用户设置 round-trip**
    - 使用 jqwik 生成随机 UserSettings（theme ∈ {light, dark, system}），MockMvc PUT → GET 验证等价
    - Tag: `Feature: web-ui, Property 8: 用户设置 round-trip`
    - **Validates: Requirements 2.6, 13.3**

  - [ ]* 6.7 编写非流式消息处理完整性属性测试
    - **Property 9: 非流式消息处理完整性**
    - 使用 jqwik 生成随机非空 content，MockMvc POST /api/chat/messages 验证 200 + 非空 messageId + 非空 content
    - Tag: `Feature: web-ui, Property 9: 非流式消息处理完整性`
    - **Validates: Requirements 2.1**

- [x] 7. 实现 WebAutoConfiguration 和 CORS 配置
  - [x] 7.1 创建 WebAutoConfiguration
    - 在 `com.lifepilot.interaction.web.config` 包下创建 WebAutoConfiguration
    - @ConditionalOnProperty(name = "lifepilot.gateway.channels.web.enabled", havingValue = "true")
    - 注册 WebChannelAdapter、SseSessionManager、ChatController、SettingsController Bean
    - 注册 WebMvcConfigurer Bean 配置 CORS（从 WebProperties 读取 allowedOrigins）
    - CORS 允许 GET/POST/PUT/DELETE/OPTIONS 方法，允许凭证
    - 空 allowedOrigins 时不注册 CORS 映射（拒绝所有跨域）
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 14.1, 14.2, 14.3, 14.4_

  - [ ]* 7.2 编写 WebAutoConfiguration 集成测试
    - 测试 enabled=true 时所有 Bean 注册成功
    - 测试 enabled=false 时无 Web 相关 Bean 注册
    - 测试 CORS 配置生效（MockMvc OPTIONS 预检请求）
    - 测试 WebProperties 默认值正确绑定
    - _Requirements: 1.4, 1.5, 6.1, 6.5, 14.1, 14.3_

- [x] 8. Checkpoint — 后端完整验证
  - Ensure all tests pass, ask the user if questions arise.

- [x] 9. 搭建前端项目脚手架（lifepilot-web）
  - [x] 9.1 初始化 Vue 3 + Vite + TypeScript 项目
    - 在工作区根目录创建 lifepilot-web/ 项目
    - 配置 package.json（Vue 3、Vite、TypeScript、Pinia、Vue Router）
    - 配置 vite.config.ts（/api 代理到 VITE_API_BASE，默认 http://localhost:8080）
    - 配置 tsconfig.json
    - 创建 .env 文件声明 VITE_API_BASE 环境变量
    - _Requirements: 8.1, 8.5, 8.6_

  - [x] 9.2 集成 Tailwind CSS 和 shadcn-vue
    - 安装 Tailwind CSS 及依赖，配置 tailwind.config.ts 和 main.css
    - 安装 shadcn-vue 组件库，初始化配置
    - _Requirements: 8.2_

  - [x] 9.3 创建路由和布局组件
    - 配置 Vue Router（/ → ChatView，/settings → SettingsView）
    - 创建 AppLayout.vue（侧边栏 + 主内容区）
    - 创建 Sidebar.vue（会话列表占位）
    - 创建 App.vue 和 main.ts 入口
    - _Requirements: 8.3, 8.4_

- [ ] 10. 实现前端类型定义和 API 客户端
  - [ ] 10.1 创建 TypeScript 类型定义
    - 在 src/types/index.ts 中定义所有接口：ChatSession、Message、A2uiComponent、A2uiSignal、TokenUsage、UserSettings、SseTokenEvent、SseDoneEvent、SseErrorEvent
    - _Requirements: 4.1, 4.2, 9.1, 10.1, 11.1, 13.1_

  - [ ] 10.2 创建 API 客户端
    - 在 src/api/client.ts 中封装 fetch 请求
    - 统一拦截非 2xx 响应，抛出包含 ErrorResponse 的异常
    - 提供 chatApi（sendMessage、sendMessageStream、listSessions、getSessionMessages、deleteSession、sendSignal）
    - 提供 settingsApi（getSettings、updateSettings）
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 5.1_

- [ ] 11. 实现 Pinia Store
  - [ ] 11.1 创建 chatStore
    - 在 src/stores/chat.ts 中定义 chatStore
    - 管理 sessions、activeSessionId、messages、isStreaming、streamingContent
    - activeSessionId 变更时清空 messages 并从后端加载历史消息
    - _Requirements: 11.1, 11.4_

  - [ ] 11.2 创建 a2uiStore
    - 在 src/stores/a2ui.ts 中定义 a2uiStore
    - 管理 components（A2uiComponent[]），提供 updateComponents 和 clearComponents 方法
    - _Requirements: 11.2, 11.5_

  - [ ] 11.3 创建 settingsStore
    - 在 src/stores/settings.ts 中定义 settingsStore
    - 管理 theme、language、llmProvider 设置
    - _Requirements: 11.3_

  - [ ]* 11.4 编写 chatStore 单元测试
    - 测试会话切换清空并重载消息
    - 测试流式状态管理
    - _Requirements: 11.1, 11.4_

- [ ] 12. 实现 composable（useChat、useA2uiSignal、useSettings）
  - [ ] 12.1 创建 useChat composable
    - 在 src/composables/useChat.ts 中封装 fetch + ReadableStream SSE 逻辑
    - POST /api/chat/messages/stream，逐行解析 SSE 事件
    - token 事件 → chatStore.streamingContent 增量拼接
    - ui 事件 → a2uiStore.updateComponents()
    - done 事件 → 完整消息存入 chatStore.messages，重置流式状态
    - error 事件 → 设置 error ref，重置流式状态
    - 提供 abort() 方法取消流式请求
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6_

  - [ ] 12.2 创建 useA2uiSignal composable
    - 在 src/composables/useA2uiSignal.ts 中封装信号发送逻辑
    - 调用 POST /api/chat/signals，携带 name、payload、sessionId
    - _Requirements: 12.3_

  - [ ] 12.3 创建 useSettings composable
    - 在 src/composables/useSettings.ts 中封装设置读写逻辑
    - 调用 GET/PUT /api/settings，同步 settingsStore
    - _Requirements: 13.3, 13.4_

  - [ ]* 12.4 编写 Token 事件增量拼接属性测试
    - **Property 5: Token 事件增量拼接**
    - 使用 fast-check 生成随机 token 事件序列，验证 streamingContent == 所有 content 拼接
    - Tag: `Feature: web-ui, Property 5: Token 事件增量拼接`
    - **Validates: Requirements 10.2**

- [ ] 13. Checkpoint — 前端核心逻辑验证
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 14. 实现对话页面组件
  - [ ] 14.1 创建 ChatInput 组件
    - 在 src/components/chat/ChatInput.vue 中实现消息输入框
    - 支持 Enter 发送消息，Shift+Enter 换行
    - 流式进行中禁用发送按钮
    - _Requirements: 9.2, 9.4_

  - [ ] 14.2 创建 StreamingText 组件
    - 在 src/components/chat/StreamingText.vue 中实现流式 Markdown 渲染
    - 支持增量 Markdown 渲染和代码高亮
    - _Requirements: 9.5_

  - [ ] 14.3 创建 MessageBubble 和 MessageList 组件
    - MessageBubble：根据 role 渲染用户消息或 Agent 响应（含 StreamingText + A2uiRenderer）
    - MessageList：按 timestamp 升序渲染消息列表
    - _Requirements: 9.1_

  - [ ]* 14.4 编写消息列表按时间顺序排列属性测试
    - **Property 4: 消息列表按时间顺序排列**
    - 使用 fast-check 生成随机 Message 数组，渲染 MessageList 验证 DOM 顺序与 timestamp 升序一致
    - Tag: `Feature: web-ui, Property 4: 消息列表按时间顺序排列`
    - **Validates: Requirements 9.1**

  - [ ] 14.5 创建 ChatView 页面
    - 在 src/views/ChatView.vue 中组装 MessageList + ChatInput + useChat
    - 发送消息时调用 useChat.sendMessage()
    - 显示加载指示器和错误提示
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.7, 9.8_

  - [ ] 14.6 完善 Sidebar 组件
    - 显示会话列表（从 chatStore.sessions 读取）
    - 支持切换当前会话和删除会话
    - _Requirements: 9.6, 9.7_

- [ ] 15. 实现 A2UI 前端渲染器
  - [ ] 15.1 创建 10 个 A2UI 基础组件
    - 在 src/components/a2ui/ 下创建：A2uiText、A2uiCard、A2uiButton、A2uiTextField、A2uiList、A2uiListItem、A2uiDatePicker、A2uiChip、A2uiDivider、A2uiImage
    - 每个组件接收 properties prop 和可选 signal prop
    - 含 signal 的组件在用户交互时调用 useA2uiSignal
    - _Requirements: 12.2, 12.3_

  - [ ] 15.2 创建 componentCatalog 和 A2uiRenderer
    - componentCatalog.ts：维护 type → Vue 组件映射注册表
    - A2uiRenderer.vue：递归遍历邻接表，通过 `<component :is>` 动态渲染
    - 未注册 type 渲染 A2uiFallback 占位符组件 + console.warn
    - 支持文本与 A2UI 组件混合渲染
    - _Requirements: 12.1, 12.2, 12.4, 12.5_

  - [ ]* 15.3 编写 A2UI 组件树更新与渲染完整性属性测试
    - **Property 7: A2UI 组件树更新与渲染完整性**
    - 使用 fast-check 生成随机 A2uiComponentTree（N 个节点，type 均在 catalog 中），验证渲染节点数 == N
    - Tag: `Feature: web-ui, Property 7: A2UI 组件树更新与渲染完整性`
    - **Validates: Requirements 11.5, 12.1**

- [ ] 16. 实现设置页面
  - [ ] 16.1 创建 SettingsView 页面
    - 在 src/views/SettingsView.vue 中实现设置表单
    - 主题切换（亮色 / 暗色 / 跟随系统）
    - LLM Provider 选择
    - 页面加载时调用 GET /api/settings 初始化表单
    - 修改后调用 PUT /api/settings 持久化
    - 保存失败时显示错误提示并回滚
    - _Requirements: 13.1, 13.2, 13.3, 13.4, 13.5_

- [ ] 17. 跨模块集成测试
  - [ ]* 17.1 编写 WebChannel_MessageGateway 集成测试
    - 验证 WebChannelAdapter 注册到 MessageGateway 后，消息经过完整中间件管道处理
    - @SpringBootTest 加载完整 ApplicationContext
    - _Requirements: 1.1, 1.2, 1.3, 14.4_

  - [ ]* 17.2 编写会话切换清空并重载属性测试
    - **Property 6: 会话切换清空并重载消息**
    - 使用 fast-check 生成随机初始消息和目标会话 ID，验证切换后 messages 被清空并重载
    - Tag: `Feature: web-ui, Property 6: 会话切换清空并重载消息`
    - **Validates: Requirements 11.4**

- [ ] 18. Final checkpoint — 全量验证
  - Ensure all tests pass, ask the user if questions arise.
  - 后端：`mvn test` 全量测试通过
  - 前端：`npm run test -- --run` 全量测试通过
  - 后端 `mvn compile` 全量编译无错误
  - 前端 `npm run build` 构建无错误

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties from design document (9 properties)
- Backend tasks (1-8) must complete before frontend tasks (9-17) that depend on API contracts
- Frontend tasks 9-10 (scaffolding + types + API client) are prerequisites for all other frontend tasks
- 后端包路径：`com.lifepilot.interaction.web`
- 前端项目路径：`lifepilot-web/`（工作区根目录）
