# ZhiWei 已知限制（v0.2.0）

本文档列出 ZhiWei v0.2.0 内测版本的已知限制和约束条件，帮助用户了解当前版本的能力边界。

---

## 1. LLM 依赖

- 核心对话功能依赖至少一个 LLM 服务商（DeepSeek / OpenAI / 通义千问 / Ollama 等），未配置时系统可启动但对话不可用
- LLM 服务商通过 Web UI 管理页面配置，API Key 通过环境变量注入
- 本地 Ollama 需用户自行安装和管理模型，ZhiWei 不内置模型下载功能
- Embedding 模型不可用时，语义记忆（L3）、程序记忆（L4）、知识库向量检索等功能降级
- 多模态能力（图片/文档/音频理解）依赖 LLM 服务商的多模态模型支持

## 2. 存储与并发

- 使用 SQLite 作为唯一存储引擎，WAL 模式支持并发读但写入串行化
- 单用户场景下无性能问题；多进程并发写入可能触发 `SQLITE_BUSY`（busy_timeout=5000ms）
- 向量索引使用 sqlite-vec 扩展，大规模向量数据（>100 万条）的检索性能未经充分测试
- 数据文件存储在 `~/.zhiwei/` 目录，无内置备份和恢复机制，建议用户自行定期备份该目录

## 3. 部署环境要求

- 后端运行需要 Java 22 或更高版本（使用了 Record、Sealed Class、Pattern Matching、Virtual Thread 等特性）
- Docker 部署需要 Docker Engine 20.10+，docker compose v2+
- 前端 Web UI 为独立 Vue 3 SPA 项目（zhiwei-web），需单独构建部署或使用 Docker Compose 一键启动
- 单实例部署，不支持集群或水平扩展
- 单用户设计，不支持多用户账户体系和权限隔离

## 4. Channel 适配器

- 企业微信、钉钉、飞书 Channel 适配器需要用户自行在对应平台申请开发者凭证
- 需要配置回调地址（Callback URL），要求服务可被外网访问
- Telegram 适配器需要自行创建 Bot 并获取 Token
- Channel 适配器的消息格式转换可能不完全覆盖所有富文本类型

## 5. 代码执行沙箱

- Process 模式：依赖操作系统进程隔离，隔离级别有限，不建议执行不可信代码
- Docker 模式：提供更好的隔离，但需要用户安装 Docker 并确保 Docker daemon 运行
- 支持的语言运行时取决于沙箱环境中安装的工具链
- CodeValidator 预检可拦截部分危险操作，但无法保证 100% 安全

## 6. 外部数据源同步

- CalDAV 同步：需要用户提供 CalDAV 服务器地址和凭证，兼容性取决于服务端实现
- Todoist 同步：需要用户自行获取 API Token
- 滴答清单同步：需要用户自行获取 API Token（OAuth 流程需手动完成）
- Obsidian 同步：读取本地 Vault 目录，需要用户指定 Vault 路径
- 同步冲突采用 Last-Write-Wins 策略或用户确认，可能存在数据覆盖风险

## 7. 浏览器兼容性

- 推荐使用 Chrome 90+ / Firefox 90+ / Edge 90+ / Safari 15+
- 不支持 Internet Explorer
- SSE（Server-Sent Events）流式响应需要浏览器支持 EventSource API
- 部分 UI 组件使用了较新的 CSS 特性，旧版浏览器可能显示异常

## 8. 其他限制

- 工作流引擎的 Cron 触发器精度为分钟级，不支持秒级调度
- MCP Server 进程管理依赖 stdio 传输，长时间运行的 MCP Server 可能因进程异常退出而中断
- A2A 协议处于早期实现阶段，跨系统互操作的兼容性有限
- 插件市场为本地索引模式，暂无中心化的插件仓库
- 日志默认输出到控制台，生产环境建议配置文件输出和日志轮转
