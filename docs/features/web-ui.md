# Web UI 特性说明

> **模块编号**：Phase 5 — 模块 18
> **最后更新**：2026-02-26

---

## 1. 功能概述

Web UI 为 ZhiWei 提供浏览器端交互界面，用户通过 Web 页面与 AI Agent 进行对话、管理设置。

**前后端彻底分离**：后端（Java 项目）提供纯 REST/SSE API，前端（独立 Vue 3 项目）独立构建部署。所有客户端（Web、未来的 CLI HTTP、移动端）共享同一套 API。

核心价值：
- 零安装访问：浏览器打开即用，无需安装客户端
- 流式对话体验：SSE 实时推送 LLM 生成的 Token，逐字显示
- Generative UI：Agent 通过 A2UI 协议动态生成交互式 UI 组件（卡片、按钮、表单等），超越纯文本对话
- 多端就绪：后端纯 API 设计，为 Phase 6 多端接入奠定基础

---

## 2. 核心特性

### 2.1 后端 REST/SSE API

- 完整的对话 API（发送消息、会话管理、历史消息）
- SSE 流式端点，支持 `token`、`ui`、`done`、`error` 四种事件类型
- A2UI 信号回传端点（用户与 Generative UI 组件交互）
- 设置管理 API
- WebChannelAdapter 接入 MessageGateway 中间件管道
- CORS 配置支持跨域前端访问

### 2.2 流式对话（前端）

- SSE 实时推送 LLM 响应
- 增量 Markdown 渲染（代码高亮、Mermaid 图表、KaTeX 公式）
- 对话历史持久化，支持多会话管理
- 会话列表侧边栏，快速切换上下文

### 2.3 A2UI Generative UI

- Agent 以 A2UI JSON 协议描述 UI 组件树，前端渲染为原生 Vue 组件
- 10 个基础组件：Text、Card、Button、TextField、List、ListItem、DatePicker、Chip、Divider、Image
- 信号回传：用户与 A2UI 组件交互（点击按钮、输入文本）触发信号，回传给 Agent 继续推理
- 文本与 UI 混合渲染：一条响应可同时包含 Markdown 文本和 A2UI 组件
- 组件目录可扩展：模块 19 可注册更多组件类型

### 2.4 设置管理（前端）

- 主题切换（亮色 / 暗色 / 跟随系统）
- LLM Provider 选择与配置
- 语言偏好设置

---

## 3. 使用场景

### 3.1 日常对话

用户在浏览器中打开 ZhiWei Web UI，输入自然语言消息，Agent 以流式文本 + Generative UI 组件响应。例如：

- 用户："帮我看看今天的待办"
- Agent 返回：文本摘要 + A2UI 待办列表卡片（含"标记完成"按钮）

### 3.2 交互式操作

Agent 通过 A2UI 生成表单组件，用户直接在 UI 中填写信息：

- Agent 返回日期选择器 → 用户选择日期 → 信号回传 → Agent 创建日程

### 3.3 多会话管理

用户可创建多个对话会话，每个会话独立上下文。侧边栏显示会话列表，支持切换、删除。

---

## 4. 配置项

### 4.1 后端配置（application.yml）

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.gateway.channels.web.enabled` | `false` | 是否启用 Web 通道（Phase 5 实现后改为 `true`） |
| `lifepilot.web.sse.timeout` | `300000` | SSE 连接超时时间（毫秒），默认 5 分钟 |
| `lifepilot.web.sse.heartbeat-interval` | `30000` | SSE 心跳间隔（毫秒），防止连接被代理断开 |
| `lifepilot.web.cors.allowed-origins` | `http://localhost:5173` | CORS 允许的前端源 |
| `lifepilot.web.cors.allow-credentials` | `true` | 是否允许携带 Cookie |

### 4.2 前端配置（环境变量）

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `VITE_API_BASE` | `http://localhost:8080` | 后端 API 基地址 |

---

## 5. 部署方式

### 5.1 开发环境

```bash
# 启动后端（端口 8080）
java -jar lifepilot.jar

# 启动前端（端口 5173，独立项目目录）
cd lifepilot-web
npm run dev
```

### 5.2 生产环境

```bash
# 后端
java -jar lifepilot.jar

# 前端（构建后部署静态文件）
cd lifepilot-web
npm run build
# 将 dist/ 部署到 Nginx / Caddy / 任意静态服务器
```

推荐使用 Nginx 反向代理统一入口：
- `/` → 前端静态文件
- `/api/` → 后端 API（此时无需 CORS）

---

## 6. 限制与未来扩展

### 6.1 当前限制

- 仅支持单用户本地部署场景，不含多用户认证体系
- A2UI 组件目录为初始集合（10 个基础组件），复杂组件在模块 19 扩展
- 不含文件上传功能（多模态文件输入在后续迭代中支持）

### 6.2 未来扩展方向

- **模块 19**：知识库管理页、Skill/MCP 管理页、轨迹回放页、工作流管理页
- **模块 19**：A2UI 组件目录扩展（Chart、Table、CodeBlock、Progress 等）
- **Phase 6**：CLI HTTP 客户端（复用同一套 REST/SSE API）
- **Phase 6**：多用户认证 + 权限管理
- **Phase 6**：移动端响应式适配 / 原生客户端
