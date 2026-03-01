# LifePilot

> **了解你生活全貌的 AI 伙伴**

[![Java](https://img.shields.io/badge/Java-22-orange.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.3-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1.2-blue.svg)](https://spring.io/projects/spring-ai)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

**LifePilot** 是一个本地运行的个人 AI Agent 助手，核心定位为"了解你生活全貌的 AI 伙伴"。它不只是被动执行用户命令，而是具备**主动智能能力**——观察用户的生活模式，主动提供建议和帮助。

## ✨ 核心特性

### 🧠 四层认知记忆系统
- **工作记忆**：当前对话上下文
- **情景记忆**：历史对话和事件
- **语义记忆**：提取的知识和概念
- **程序记忆**：学习到的技能和流程
- **时序知识图谱**：实体和关系带时间维度，支持时间旅行查询

### 🎯 Agent Skills 技能系统
- **统一架构**：Java 原生、YAML 声明式、MCP 外部工具三层混合
- **Skill 自扩展**：Agent 运行时自动创建 YAML Skill
- **零代码开发**：通过 YAML 文件定义技能，无需编程
- **运行时热加载**：修改 YAML 文件即时生效

### 🔀 多 LLM 服务商支持
- **智能路由**：根据场景自动选择最佳模型
- **故障转移**：自动切换到备用服务商
- **熔断保护**：防止服务异常影响系统稳定性
- **支持模型**：Ollama（本地）、DeepSeek、百度文心、通义千问、智谱 GLM 等

### 🔌 MCP 协议支持
- **标准协议**：支持 Model Context Protocol (MCP)
- **双向桥接**：内置工具可暴露为 MCP Tool
- **生态丰富**：接入 filesystem、browser、github 等 MCP Server

### 🔮 主动推理引擎
- **智能提醒**：在合适的时机主动提供建议
- **降频机制**：避免过度打扰用户
- **两阶段推理**：规则引擎快速过滤 + LLM 精细判断

### 🔍 Trace 级可观测性
- **完整追踪**：每个决策都可追溯和回放
- **护栏引擎**：自动检测和阻止高风险操作
- **数据脱敏**：敏感信息自动脱敏保护隐私

### 🏠 本地优先架构
- **隐私保护**：所有数据存储在本地 `~/.lifepilot/`
- **零外部依赖**：SQLite + sqlite-vec，无需额外服务
- **完全掌控**：你完全掌控自己的数据

## 🚀 快速开始

### 前置条件

- **Java 22+**（必需）
- **Maven 3.9.x**（必需）
- 至少一个 LLM 服务商的 API Key，或本地安装 [Ollama](https://ollama.ai)

### 安装步骤

1. **克隆项目**
```bash
git clone https://github.com/your-username/lifepilot.git
cd lifepilot
```

2. **配置 LLM 服务商**

编辑 `src/main/resources/application.yml`，配置至少一个 LLM 服务商：

```yaml
lifepilot:
  llm:
    providers:
      - id: deepseek-main
        type: deepseek
        api-url: https://api.deepseek.com/v1
        api-key: ${DEEPSEEK_API_KEY}
        model-name: deepseek-chat
        priority: 1
```

3. **设置环境变量**

```bash
# Linux / macOS
export DEEPSEEK_API_KEY=your-api-key

# Windows (PowerShell)
$env:DEEPSEEK_API_KEY="your-api-key"
```

4. **启动应用**

```bash
# 使用 Maven
mvn spring-boot:run

# 或构建后运行
mvn clean package
java -jar target/lifepilot-0.1.0-SNAPSHOT.jar
```

### 首次使用

启动后，你可以通过以下方式与 LifePilot 交互：

1. **CLI 模式**（默认）
```bash
java -jar lifepilot.jar --mode cli
```

2. **Web UI 模式**
```bash
java -jar lifepilot.jar --mode web
# 访问 http://localhost:8080
```

3. **系统托盘模式**
```bash
java -jar lifepilot.jar --mode tray
```

### 使用示例

```
你：你好，我是小明
LifePilot：你好小明！很高兴认识你。我是 LifePilot，你的 AI 伙伴。
         有什么我可以帮你的吗？

你：帮我创建一个明天下午3点的会议，和产品团队讨论Q2规划
LifePilot：✅ 已创建日程「产品团队Q2规划讨论」
         📅 明天 15:00-16:00
         🔔 明天 14:45 会提醒你

你：再帮我加个待办，会前准备Q1数据汇总
LifePilot：✅ 已创建待办「准备Q1数据汇总」
         🔴 优先级：高
         ⏰ 截止时间：明天 14:30（会议前 30 分钟）

你：看看我今天的安排
LifePilot：📊 今日概览：
         📅 日程：2 项
           09:00 团队周会
           14:00 客户回访
         📋 待办：4 项未完成（1 项高优先级）
         🎯 习惯：晨跑待打卡
```

## 📋 配置说明

### 基础配置

在 `src/main/resources/application.yml` 中配置：

```yaml
lifepilot:
  # 数据存储路径（默认 ~/.lifepilot/）
  data-dir: ~/.lifepilot

  # LLM 服务商配置
  llm:
    providers:
      - id: deepseek-main
        type: deepseek
        api-url: https://api.deepseek.com/v1
        api-key: ${DEEPSEEK_API_KEY}
        model-name: deepseek-chat
        timeout-seconds: 30
        priority: 1
        scenes: [intent_understanding, task_planning, chat]

  # MCP Server 配置
  mcp:
    servers:
      - name: filesystem
        command: npx
        args: ["-y", "@modelcontextprotocol/server-filesystem", "/home/user/documents"]
        transport: stdio

  # Skill 配置
  skills:
    user-skills-dir: ~/.lifepilot/skills
    auto-generated-require-confirmation: true
    max-activation-depth: 2
```

### 环境变量

敏感信息（API 密钥等）建议通过环境变量配置：

```bash
# LLM 服务商密钥
export DEEPSEEK_API_KEY=your-deepseek-api-key
export WENXIN_API_KEY=your-wenxin-api-key
export QWEN_API_KEY=your-qwen-api-key
export GLM_API_KEY=your-glm-api-key
```

> ⚠️ **安全提示**：不要将 API 密钥直接写在 `application.yml` 中，也不要提交到版本控制系统。

## 🛠️ 技术栈

| 层级 | 技术选型 | 版本 | 说明 |
|------|---------|------|------|
| **语言** | Java | 22 | Record/Sealed/Pattern Matching/Virtual Thread |
| **框架** | Spring Boot | 3.5.x | Web 框架、自动配置、Actuator |
| **AI 集成** | Spring AI | 1.1.2 | AI 原生集成、Advisor 模式、MCP 支持 |
| **构建工具** | Maven | 3.9.x | 标准化依赖管理 |
| **数据库** | SQLite | 3.51+ | 零运维、本地优先、WAL 模式 |
| **向量存储** | sqlite-vec | 0.1.x | SQLite 原生扩展、无额外进程 |
| **CLI** | JLine 3 | 3.28+ | 补全、高亮、历史记录 |
| **前端** | Vue 3 + Vite | 3.5 / 6.x | 轻量 SPA、响应式 |
| **状态管理** | Pinia | 3.x | Vue 3 状态管理 |
| **测试** | JUnit 5 + jqwik | 5.11+ / 1.9.x | 单元测试 + 属性测试 |

## 📁 项目结构

```
lifepilot/
├── src/
│   ├── main/
│   │   ├── java/com/lifepilot/
│   │   │   ├── agent/          # Agent 引擎
│   │   │   ├── memory/         # 记忆系统
│   │   │   ├── llm/            # LLM 路由
│   │   │   ├── skill/          # 技能插件
│   │   │   ├── mcp/            # MCP 协议
│   │   │   ├── interaction/    # 交互层（CLI/Web/Tray）
│   │   │   ├── knowledge/      # 知识库
│   │   │   ├── observability/  # 可观测性
│   │   │   └── workflow/       # 工作流引擎
│   │   └── resources/
│   │       ├── application.yml # 配置文件
│   │       └── db/migration/   # 数据库迁移脚本
│   └── test/                   # 测试代码
├── lifepilot-web/              # 前端项目
│   ├── src/
│   │   ├── components/         # Vue 组件
│   │   ├── views/              # 页面视图
│   │   ├── stores/             # Pinia 状态管理
│   │   └── router/             # 路由配置
│   └── package.json
├── docs/                       # 文档目录
│   ├── ARCHITECTURE.md         # 架构设计文档
│   ├── FEATURES.md             # 功能说明文档
│   ├── ROADMAP.md              # 路线图
│   ├── architecture/           # 架构详细设计
│   └── features/               # 功能详细说明
├── pom.xml                     # Maven 配置
└── README.md                   # 本文件
```

## 🎯 核心能力

### 内置 Skills

- **📋 待办管理**：创建、查询、完成待办事项
- **📅 日程管理**：安排会议、设置提醒
- **🎯 习惯追踪**：记录和追踪日常习惯
- **📚 知识库管理**：文档解析、知识提取、智能检索
- **🔍 记忆查询**：查询历史对话和知识

### 扩展能力

- **YAML Skill**：通过 YAML 文件定义新技能
- **MCP 工具**：接入 MCP Server 扩展能力
- **Java 插件**：开发原生 Java 插件

## 📚 文档

- [架构设计文档](docs/ARCHITECTURE.md) - 详细的系统架构设计
- [功能说明文档](docs/FEATURES.md) - 完整的功能特性说明
- [开发路线图](docs/ROADMAP.md) - 未来功能规划
- [Skill 开发指南](docs/features/skill-development.md) - 如何开发自定义 Skill

## 🧪 开发指南

### 本地开发

1. **克隆项目**
```bash
git clone https://github.com/your-username/lifepilot.git
cd lifepilot
```

2. **安装依赖**
```bash
# 后端依赖（Maven 自动下载）
mvn clean install

# 前端依赖
cd lifepilot-web
npm install
```

3. **运行测试**
```bash
# 后端测试
mvn test

# 前端测试
cd lifepilot-web
npm test
```

4. **启动开发服务器**
```bash
# 后端
mvn spring-boot:run

# 前端（新终端）
cd lifepilot-web
npm run dev
```

### 构建发布

```bash
# 构建后端 JAR
mvn clean package

# 构建前端
cd lifepilot-web
npm run build
```

## 🤝 贡献指南

我们欢迎所有形式的贡献！请遵循以下步骤：

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启 Pull Request

### 代码规范

- 遵循 Java 编码规范
- 使用 Lombok 减少样板代码
- 编写单元测试覆盖核心逻辑
- 提交前运行 `mvn clean test` 确保测试通过

## 📄 许可证

本项目采用 [MIT License](LICENSE) 许可证。

## 🙏 致谢

- [Spring AI](https://spring.io/projects/spring-ai) - AI 原生集成框架
- [OpenClaw](https://github.com/openclaw/openclaw) - 参考了部分架构设计
- [AstrBot](https://github.com/astrbot/astrbot) - 参考了部分设计理念
- [MCP](https://modelcontextprotocol.io/) - Model Context Protocol 标准

## 📞 联系方式

- **Issues**：[GitHub Issues](https://github.com/your-username/lifepilot/issues)
- **讨论**：[GitHub Discussions](https://github.com/your-username/lifepilot/discussions)

---

**LifePilot** — 了解你生活全貌的 AI 伙伴

本地运行 · 隐私优先 · 越用越懂你
