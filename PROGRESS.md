## [2026-05-08] 每日工作总结

### 🚀 核心进展
- **对话页重设计（Phase B）**：空态改为 Wordmark + 欢迎语 + 2×2 示例卡片 + 精简 Composer；头部 ... 菜单从 4 项砍到 2 项（会话概览 / 当前会话设置），停止生成迁到 Composer 顶部浮窗 Pill；右侧面板从"流式自动占位 340px"改为显式触发的 420px overlay（遮罩 + Esc 关闭），不再挤压主列。
- **设计 token 基线**：新增 chat-tokens.css（20+ 变量），固化主列 720px、气泡 640px、行高 1.7、圆角 18px、字号 15px 等，去除魔法值。
- **MessageActions hover 披露**：默认 opacity:0，父级 hover / 键盘 focus 时浮现，降低噪声。
- **后端假开关修复（Phase A）**：
  - `GET /chat/sessions/{id}` 返回真实 sessionConfig（原来永远空）
  - 新增 `POST /chat/sessions/{id}/clear`（前端"清空消息"终于能用）
  - 新增 `POST /chat/sessions/{sessionId}/cancel` 驱动 SseSessionManager.cancelBySessionId → CancellationToken.cancel，真实中断 Agent 循环
  - `ChatRequest.singleTurnOverride` 单轮临时覆盖语义：替换"发送前 PATCH → 发送后 PATCH 还原"双 PATCH race 实现，@ 叠加知识库 / 单轮模型切换走请求体
  - `forkFromTranscript` 继承原会话 sessionConfig
  - `ChatSessionDetail.totalTokens` 从 traces 表按 session_id 聚合真实值
- **Interaction 死代码清理**：前端删除 respondInteraction / activeInteraction / handleInteractionRequest 等整套未落地协议，共减 ~200 行。
- **根路由**：`/` 直达对话页，LandingView 迁到 `/landing` 保留。

### 📝 详细提交记录
- **e3029d2e**: chore(web): 删除 ChatRightPanel 与 ChatView 残留未用的 computed
- **12728fef**: refactor(web): 根路由直达对话页，Landing 迁到 /landing 保留
- **805bf354**: feat(web): MessageActions / MessageFeedback hover 披露（默认 opacity:0）
- **abae34a8**: feat(web): 对话页重设计 - 空态 PromptGallery + Overlay 面板 + 精简头部
- **121a4af0**: refactor(web): useChat/ChatInput 对齐后端 singleTurnOverride + cancelTurn + 删除 interaction 死代码
- **1959ef30**: test(chat): 对齐 ChatRequest/AgentRequest 新字段的测试构造
- **2988f886**: feat(chat): 支持 singleTurnOverride 单轮临时覆盖会话配置
- **74fe73e6**: feat(chat): SseSessionManager 暴露 cancelBySessionId 供 stop endpoint 驱动
- **4e310b07**: feat(chat): 新增清空会话消息 + 真实停止生成 endpoint
- **47bfa08f**: fix(chat): SessionDetail 回显真实 sessionConfig + 聚合 totalTokens + fork 继承配置

---
## [2026-04-14] 每日工作总结

### 🚀 核心进展
- **Memory 模块强化**：重构了 Memory Bridge，优化了记忆决策引导与经验注入机制；实现了语义巩固中的重要性提升更新策略；补充并修复了实体和经验的提取逻辑。
- **A2UI 渲染体系重构**：全面从 XML 标签方式迁移至 Markdown 与 ui.emit 工具调用方式；修复了相关持久化、状态泄漏及样式问题；移除了大量的遗留冗余代码。
- **UI/UX 改进**：启用了 KaTeX 数学公式渲染和 GitHub 告警块展示，修复了因对话标题未实时刷新与新对话创建导致的路由/状态不同步问题。

### 📝 详细提交记录
- **7939054**：fix(web): 修复对话标题不实时刷新 + 新建对话按钮无响应 (金陵雪)
- **effd23d**：feat(memory): Memory Bridge — 记忆决策引导 + 注入质量升级 + 经验分流 + L4 精简 (#87) (翟树贵)
- **8755058**：feat(a2ui): A2UI 渲染体系改造 — Markdown 优先 + Tool Call 迁移 (#86) (翟树贵)
- **df1156c**：fix(memory): 代码审查修复 + 文档同步 (金陵雪)
- **4917e24**：docs：清理了一些文档 (金陵雪)
- **a79fc1b**：fix(memory): 语义巩固 importance boost 改为直接 UPDATE 避免版本冲突 (金陵雪)
- **720e83c**：fix(memory): EpisodicToProceduralConsolidator Bean 未注册 (金陵雪)
- **6fc0282**：fix(memory): 实体提取跳过语义缓存 + prompt 强化 SKILL/GOAL/PERSON (金陵雪)
- **f0fd987**：docs(a2ui): A2UI 渲染体系改造实施计划 (金陵雪)
- **5434915**：fix(memory): 修复实体提取管线 4 个 Bug + 优化提取提示词 (金陵雪)
- **83b44e3**：feat(memory): 添加记忆提取质量评估数据集 + 去重手动触发端点 (金陵雪)
- **dfcfbb4**：fix(memory): 巩固管线修复 + 实体去重事务保护 + 提示词优化 (#84) (翟树贵)

---
## [2026-04-13] 每日工作总结

### 🚀 核心进展
- **Skill 体系深度重构**��全量重写 25 个核心 Skill 指南，引入标准 Markdown 模板与确定性指令��移除冗余字段，优化搜索索引，��著提升 Agent 工具调用精准度。
- **Agent 运行机制增强**：实现 Skill ��南在运行时的动态注入与 Context ��离；新增工具失败反思机制，有��防止相同参数的死循环重试。
- **定时任务自动化**：支持 Cron 任务���特定 Skill 绑定，执行时自动加载所需指南。
- **鲁棒性提升**：全��补全内存模块的写入重试保护，��对 Git 系列工具实施强制路径校验，避免环境偏差。

### 📝 详细提���记录
- **50e5485**：feat(skill): Skill ���系全面打磨与 Agent 增强 (#81) (翟���贵)
- **1a3db52**：docs: 初始创建 PROGRESS.md 并记录 2026-04-12 总结 (翟树���)

---
## [2026-04-12] 每日工作总结

### 🚀 核心进展
- **UI/UX 质感重塑**：完��了从侧边栏到消息气泡��全方位��觉升级，采用中性灰��与系统字��，优化了滚动与动��体验，交互��趋向现代化。
- **SSE 流式输出优��**：引入 SseEventBuffer 机制，通过��适应排空速��平滑 Token 输出，解���了 LLM ��出时的视觉抖动问题。
- **架构与工程规范**：统一了应用���作���录至 ~/.zhiwei/workspace/，并重�����了数据库迁移逻辑，将增量脚本�����并为单一初始化脚本。
- **��能��全**：上线了会话自动命名服务、模型服务连通性测试及增强的附件上传逻辑。

### 📝 详细提���记录
- **fcf8c93**: feat(ui): 前端质��与体��优化 (金陵雪)
- **f439679**: feat(workspace): 统一工作目录，文��产出归��到 ~/.zhiwei/workspace/ (#79) (翟树贵)
- **ea28cd9**: chore(desktop): 桌面端构建暂时只支持 Windows (金��雪)
- **1a70196**: fix(desktop): regex 依���移至通用 dependencies (金陵雪)
- **7f8f4ef**: test: 适配今日代码改动的��试修复 (金陵雪)
- **0193690**: fix(chat): 新会话��次上传附件时先懒��建会话 (金陵雪)
- **142e383**: refactor(db): 合并 V1-V25 为干净的单一 V1 ��始化脚本 (金陵雪)
- **b63d8e0**: fix(models): 模型服务保存时不再覆盖用户修改 (金陵���)
- **8262a87**: docs(readme): 更新为桌面客户端截图 (���陵雪)
- **4e3c60b**: refactor(ui): 渠道设置页文案面向用户重写 (金陵雪)
- **723cf64**: fix(chat): 空对话不再立即创建会话，改为发消息时懒创建 (金陵雪)
- **8bd5f9b**: fix(desktop): 启动后首屏改为空对话页而非会话列表 (金陵雪)
- **a6ae9e6**: docs(readme): 以桌面客户端为主入口重组项目介绍 (金陵雪)
- **d26e031**: docs(readme): 重写项目介绍 + 更新全部界面截图 (金陵雪)
- **182b0b4**: fix(models): RERANK 服务测试连接支持 (金陵雪)
- **0e7cc30**: fix(memory): MemoryController 剩�� 10 个端点补充 ApiResponse 包装 (金���雪)
- **1cdcd8f**: fix(memory): 实体编���保存不生效 + ApiResponse 统一包装 (金陵雪)
- **4333fbf**: feat(models): 模���服务启用切换 + ���试连接 + ���置页布局修复 (金陵��)
- **662c649**: fix(marketplace): API 解��修复 + 界面优化 (金陵雪)
- **ec19504**: test(review): 新增 SseEventBuffer + SessionTitleGenerator 单元测试 (金陵雪)
- **6e13f4f**: fix(quality): 代码审查修��� — 后端安全性 + 前端滚动/样式/状态修复 (金陵雪)
- **3db7123**: fix(ui): 停止生成不再报错 + 用户消息���地编辑重新生成 (金陵雪)
- **651b4c4**: docs(sse): 补充架构图 + 设计决��� + 性能规划中的缓冲区描述 (金陵雪)
- **efeb013**: docs(sse): 同步 SSE 事件缓冲区配置文档 (金陵雪)
- **dc20db7**: feat(sse): 新增 SSE 事件缓冲������平滑流式输出速率 (金陵��)
- **15abc2f**: fix(ui): 滚动到底修复 + 消息动画优化 + 流式抖动缓解 (���陵雪)
- **5e67d5c**: refactor(ui): 质感提升 �� 纯白底色 + 中性灰气泡 + 系统字��� + 细节修复 (金陵��)
- **57fc030**: refactor(ui): 消息区细���优化 — 取��折叠 + 滚动到底 + 操作栏精简 (金陵雪)
- **8d784a4**: refactor(ui): 侧边��双Tab重构 + 消��区现代化 + 会话��动命名 (金陵雪)

---
