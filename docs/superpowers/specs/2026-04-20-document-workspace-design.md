---
title: 文档工作空间 — 完整需求设计
status: draft
owner: zsg
date: 2026-04-20
scope: 覆盖"读 / 写 / 改 / 协作"的全量文档能力规划，供后续拆解为多期子需求
---

# 文档工作空间（Document Workspace）— 完整需求设计

> 本文档是**蓝图级需求规划**，不是一期实施方案。目标是把"文档"这条场景主线在知微里的完整能力地图画清楚，避免逐点打磨导致全貌失焦。具体实施按第 8 节的分阶段路线拆单。

---

## 0. Phase 0 复盘修订（2026-04-20 收口）

> 本节为 Phase 0 实施完成后的**定位级修订**，是对下方原始方案的校正。读本文档以本节为准，下方 1-10 节保留原始论述作为演进痕迹。

### 0.1 核心定位校正：本地 AI 助手 ≠ SaaS 对话平台

原 spec 隐含一个错误前提："用户上传文档 → AI 处理"。实际上**知微是本地 AI Agent 助手**：
- 跑在用户电脑上（Tauri 桌面 + Spring Boot 本地后端）
- AI 有权直接访问用户电脑的文件系统
- **用户真实心智**是"帮我看看 `D:/合同/甲方.docx`"，不是"我把合同上传给你，你读一下"

**推论**：
- **主入口是本机路径**（`file.read(path=...)`），不是上传
- **附件上传是兜底**（Web 端拿不到真实路径时 / 跨机器临时分享 / 移动场景）
- UI 文案中 **"上传"→"选择"**（已在本次执行中改完对话场景；知识库的"上传"语义准确保留）

### 0.2 工具合并：`file.read` 统一承载所有"读路径"

**决策**：`document.parse` 作为独立工具是**设计债**（Phase 0 初期为绕开 `file.read` 读不了 docx/pdf 而新建），已合并回 `file.read`。

合并后的 `file.read` 能力（单一入口，内部按扩展名路由）：
- **纯文本类**（代码 / txt / json / yaml / log / config）→ 原 `BufferedReader` 按字节路径
- **结构化文档类**（md / csv / docx / pdf）→ 走 `DocumentParserService` 拿结构化解析
- **参数三选一**：`path`（主入口） / `attachmentId`（兜底） / `skill`（加载技能指南，与读路径无关但复用工具）

**`document.*` 命名空间不废弃** —— 保留给 Phase 2+ 做**需要结构化数据 + 样式/模板才能输出的生成/编辑能力**（docx/xlsx/pptx/pdf 生成、保留样式修改、diff、模板渲染）。这类能力和 `file.write`（原子覆写纯文本）本质不同。

### 0.3 document.\* 命名空间的边界（Phase 2+ 规划）

`document.*` **只收录**封装格式的文档**生成/编辑**能力：
- docx / xlsx / pptx / pdf（未来扩展 odt / rtf / epub）
- 原因：需要 POI / PDFBox 做格式编解码，涉及样式 / 模板 / 图表 / 版本

**不归属 `document.*`**（各归其位）：

| 格式 | 归属 | 理由 |
|---|---|---|
| md / txt / json / yaml / csv / html / xml | `file.write` / `file.edit` | 纯文本即产物 |
| 代码（py / js / sh / java / ...） | `file.write` / `file.edit` | 同上 |
| mermaid / plantuml 文本 | `file.write` + 独立 `chart.render` 工具族 | 图表是独立能力域 |
| png / jpg / svg 图表成品 | `chart.*` / `image.*` 独立工具族 | matplotlib / DALLE / FLUX 等 |
| zip / tar | `archive.*` 独立工具族 | 打包属独立操作 |

**原则**：一个工具域 = 一类心智模型 + 一类技术栈。避免 umbrella。

### 0.4 多模态路径架构修复（Phase 0 e2e 暴露）

触发源：`ExecutionRequestFactory.buildMediaContents` 原先把**所有附件类型**都塞 `MediaContent`（含 docx/pdf），导致 `StreamingCallback.callLlm` 误判进多模态路径，而 `MultimodalRequest` 架构上不承载 tools → LLM 收到 `tools=[]`，工具调用全部失效。

**已修**：
1. `ExecutionRequestFactory` 按 MIME 过滤，只 image/audio/video 进 MediaContent
2. `StreamingCallback` / `NonStreamingCallback` 的 `messagesHaveMedia` 按 Media.mimeType 精化
3. `MultimodalRequest` record 加 `@Nullable List<ToolCallback> toolCallbacks`（架构预留，为未来 vision + tools 同传给现代模型铺路）

**未了架构债**：现代模型（Claude 3.5 / GPT-4o / Gemini 2.5）支持 vision + function calling 同时用，但 `GenerationRouter` / vision adapter 签名不承载 tools。Phase 1 重构时修。

### 0.5 Phase 路线重排（覆盖原第 8 节）

**Tauri 深度集成（原 Phase 7）暂不必做**（2026-04-20 老板决策）—— 现有 Web 端"文件选择 + 拖拽到对话"已覆盖"本地 AI 助手"最小交互需求。Tauri 系统级拖拽 / 右键菜单 / 全局快捷键属锦上添花，按需再做不影响核心体验。新顺序：

| Phase | 目标 | 状态 |
|---|---|---|
| **Phase 0** | 读懂 docx/pdf（合并 file.read）+ 前端附件卡片 + 多模态路径修复 | ✅ 已完成 |
| **Phase 1** | **xlsx / pptx parser** 加入 `DocumentParserService`（延续 Phase 0） | 下一个 |
| **Phase 2** | 原生文档**生成**：`document.create_docx` / `create_xlsx` / `create_pptx` + 文档产物下载链路 + `documents` 表建立 | 按需 |
| **Phase 3** | **编辑与 diff**：`document.patch_*` 保留样式 + 版本管理 + diff UI | 按需 |
| **Phase 4** | **双栏 Artifact** 工作台：类 Claude Artifacts 的文档工作区 | 按需 |
| **Phase 5** | 模板与批量：`document.render_template` + 模板库 + 批量处理 | 按需 |
| **Phase 6** | 渠道集成：飞书 / 钉钉 / 企微文件流转 | 按需 |
| **Phase 7** | 在线文档：飞书文档 / 腾讯文档 / Notion API | 按需 |
| **Phase N** | **Tauri 桌面深度集成**（按需）：系统拖拽 / 右键菜单 / 全局快捷键 | 可能不做 |

原 spec 第 8 节中 Phase 0 提到的"数据库 `documents` 表"已确认**延期到 Phase 2**（Phase 0 无文档实体生命周期需求，过早建表无意义）。

### 0.6 Phase 0 实际交付清单

提交分两段：
- **主体（Phase 0 Task 1-7）**：`ed46d61f` 开始 `3cba3161` 结束，9 个 commit
- **追加修复与定位收口**：
  - `da357780`：`core-tool-ids` 加 `document.parse`（后被 Task 合并取代）
  - `70cc531f`：多模态路径工具剥离 bug 彻底修
  - `a9e6926c`：`file.read` 合并文档解析能力
  - `2c98ef44`：清理 `document.parse` 工具层
  - `728a6082`：前端"上传"文案去 SaaS 化

**验证**：
- `工具注册统计: JAVA_NATIVE=22`（从 23 降回 22，`document.parse` 合并进 `file.read`）
- e2e 实测：LLM 收到 `tools=[11 items 含 file.read]`，真调 `file.read(attachmentId=...)` 读出 md 真实内容"鲲鹏振翅-2077"

### 0.7 未了事项处理记录

| 事项 | 状态 | 备注 |
|---|---|---|
| Spec 第 3.2 / 4 节场景示例措辞校正 | ✅ 已改 | 从"上传"转为"本机路径主 + 附件兜底" |
| `application.yml` `core-tool-ids` 加注释 | ✅ 已改 | 说明此列表是 ReAct 默认工具白名单，新增需显式加入 |
| Surefire `**/*测试.java` include pattern | ⚠️ **已识别但延期** | 改完 test 发现从 1415 涨到 **3274**（中文测试从未被跑过），其中 **42 Failures + 59 Errors = 101 个失败**，集中在集成测试 / 属性测试 / Spring Context 测试 — 是历史长期未跑积累的债务，不在 Phase 0 范围内。Phase 1 可并行或作为 Phase 0.5 独立处理 |

---

## 1. 背景与目标

> ⚠️ 以下 1-10 节为 Phase 0 立项时的原始论述。**本文档以第 0 节为准**，下方保留演进历史。

### 1.1 背景

知微已完成 Agent / 记忆 / 工具 / 多通道 / 多 Agent 编排等底座建设。当前进入打磨期，需要针对高价值场景深挖。办公场景中**"文档"是出现频率最高、跨角色最通用、价值密度最大的载体** —— 合同、报告、方案、简历、台账、数据表、PPT、会议纪要几乎是所有知识工作者的日常产物。

现状下，知微对文档的支持是碎片化的：附件上传能接、知识库能解析、内容创作能写 Markdown，但**从用户端看**这些能力割裂不成闭环，典型场景跑不通：

- 扔一份 Word 合同进来，问"付款条款有风险吗" —— LLM 根本看不到内容
- 让知微改一段已有 Word，它改完样式全丢
- 让知微出一份带图表的 Excel，产出是 LLM 硬写 python 代码跑一遍，质量不稳
- PPT 完全不支持
- 产物怎么下载、怎么预览，都没有顺手交互

### 1.2 目标

建立"**文档工作空间**"作为知微的一等公民：

1. **读懂所有主流格式**：docx / xlsx / pptx / pdf / md 的结构化内容都能进入 Agent 上下文
2. **产出可用级文档**：生成的 Word / Excel / PPT 内容正确 + 样式可用，不只是草稿级
3. **打磨已有文档**：能按自然语言指令修改已有文档，保留原样式、支持 diff 确认
4. **顺手的交互**：拖拽上传、对话里预览、双栏工作台、产物一键下载
5. **跨环境一致**：Web、Tauri 桌面、IM 渠道都能无摩擦交付文档能力

### 1.3 非目标（本轮不做）

- 多人实时协作编辑（Google Docs 级）—— 工程量太大，Phase 3+ 再议
- 完整版式排版引擎（类 LaTeX / Adobe InDesign）—— 超出定位
- 离线版 WPS / 微软 Office 替代 —— 知微是 Agent，不是办公套件
- 图表/流程图的可视化设计器 —— 可调用专门工具生成图片，不自建

---

## 2. 现状盘点

### 2.1 能力矩阵

| 能力 | 现状 | 关键文件 |
|---|---|---|
| Web 拖拽上传 | ✅ 完整 | `zhiwei-web/src/composables/useDragDrop.ts`、`ChatInput.vue` |
| 附件后端存储 | ✅ 完整 | `AttachmentController`、`AttachmentRepository`、`message_attachments` 表 |
| 附件查看端点 | ✅ inline 预览 | `GET /api/attachments/{id}` |
| 附件类型白名单 | ✅ 图片/PDF/文本/Office/音频/视频 | `ChatController.uploadAttachment()` |
| 图片 multimodal | ✅ 视觉模型可见 | `llm/multimodal/` |
| 音频转录 | ✅ 自动转文字 | `BrowserIngressService.transcribeAudioAttachments()` |
| docx 解析 | ✅ 但孤立在知识库 | `knowledge/parser/WordParser.java`（基于 POI） |
| pdf 解析 | ✅ 但孤立在知识库 | `knowledge/parser/PdfParser.java` |
| md / txt 解析 | ✅ 但孤立在知识库 | `MarkdownParser`、`PlainTextParser` |
| **xlsx 解析** | ❌ 完全缺失 | — |
| **pptx 解析** | ❌ 完全缺失 | — |
| **docx/xlsx/pptx 进 LLM 上下文** | ❌ 对话附件流未调用 parser | `BrowserIngressService` |
| 纯文本读 | ✅ | `FileReadToolExecutor`（按行读，不支持二进制） |
| 纯文本写 | ✅ 原子写 + 历史 | `FileWriteToolExecutor`、`FileEditHistory` |
| **原生 docx 生成** | ❌ 靠 pandoc / python-docx 绕行 | — |
| **原生 xlsx 生成** | ❌ 同上 | — |
| **原生 pptx 生成** | ❌ 同上 | — |
| **保留样式修改 docx** | ❌ 全项目空白 | — |
| **Excel 精细编辑** | ❌ 空白 | — |
| 图片预览 | ✅ lightbox | `MessageBubble.vue` |
| 音频 / 视频播放 | ✅ inline | `MessageBubble.vue` |
| **docx/xlsx/pptx 对话内预览** | ❌ 只显示文件名 + 大小 | `MessageBubble.vue` |
| **PDF 对话内预览** | ❌ 同上 | — |
| **产物下载按钮** | ❌ 无专门链路 | — |
| **Diff 显示** | ❌ 无 | — |
| **双栏 Artifact 工作台** | ❌ 无 | — |
| Tauri 系统级拖拽 | ❌ | `zhiwei-web/src-tauri/` |
| 右键菜单 / 快捷键唤起 | ❌ | — |
| 飞书 / 钉钉 / 企微 文件流转 | ❌ 未见代码 | `interaction/middleware/router/` |
| 模板引擎 | ❌ | — |
| content-creator skill | ✅ 但只产 Markdown | `skills/content-creator/SKILL.md` |
| doc-processor skill | ⚠️ 依赖外部 CLI | `skills/doc-processor/SKILL.md` |
| summarizer / research-assistant 等 | ✅ 但未与文档直接协作 | `skills/*/SKILL.md` |

### 2.2 关键洞察

**洞察 1 — 能力孤岛**：`knowledge/parser/` 有完整的 Word/PDF/MD/Text 解析栈（基于 POI），但聊天附件流程没调用，LLM 看不到内容。**这是最大的送分题 —— 接一层桥就能让所有文本格式进上下文。**

**洞察 2 — 生成能力绕行且脆弱**：现在让 LLM 生成 Word，是让它写 Python 代码调 python-docx，错误率高、样式差。**需要一个稳定的 Agent 工具层：接收结构化指令，原生生成目标格式。**

**洞察 3 — "保留样式修改"是真正的技术挑战**：需要：
- 读入 docx → 内部模型（保留 run / style / numPr / 表格结构）
- LLM 产出修改指令（局部增删改，不是整篇重写）
- 按指令精确修改内部模型 → 写回 docx，样式不变
- 这是和文本 diff 完全不同的工程，是业界公认难点

**洞察 4 — 交互缺的是"预览 + 下载 + diff"三件套**：前端已有附件框架，补齐这三件就能把"可用级"拉起来。

**洞察 5 — 桌面端和 IM 渠道的空白** 短期不是刚需，但决定了产品能否扩张到"文档天然来自多渠道"的真实工作流。

**洞察 6 — "文档" vs "附件" 概念耦合**：当前附件是挂在 turn 上的瞬态资源。但"文档"是有生命周期、有版本、可被反复编辑的长期对象。**模型层需要把"文档实体"从"附件"里抽出来**。

---

## 3. 目标用户与核心价值主张

### 3.1 目标用户（优先级）

1. **主用户 — 知识工作者 / 创业者 / 小团队 leader**（老板自己即典型）：日常要出周报、方案、合同、数据表，追求"少动手、产出能直接用"
2. **次用户 — 产品经理 / 运营 / 咨询**：重样式、重模板、跨文档整合
3. **未来扩展 — 企业组织**：需要协作、版本、权限、审计

### 3.2 核心价值主张

> **本地文件路径 or 一句话说清需求 —— 知微负责读懂、写出来、改到位，产物能直接用。**
>
> （见 0.1：主流程是 AI 直接访问本机文件，不是 SaaS 式"上传后处理"）

和市面同类产品的差异：
- vs **通用 AI 聊天（ChatGPT / Claude / Gemini）**：它们只能处理用户上传的文档副本；知微能**直接读用户电脑上的任意文件**，不要求上传
- vs **WPS AI / 微软 Copilot**：它们绑定在办公套件里；知微是独立 Agent，可跨格式（含代码 / 日志 / 配置）、可编排工具链、可自定义 skill
- vs **Notion AI / 飞书智能伙伴**：它们绑定在各自云端平台；知微跑在本地，处理的是用户电脑上的真实文件

---

## 4. 用户场景地图

按"文档的一生"串联场景，覆盖从创建到归档的全链路。

### 4.1 场景 S1 — 读懂文档（输入理解）

**触发**（两种入口，路径优先）：
- **本机路径主流程**：用户说"看看 `D:/合同/甲方.docx`" / "`~/Documents/Q3/*.xlsx` 里第二季度的毛利" → AI 直接 `file.read(path=...)` 读
- **附件兜底**：Web 端跨机器 / 移动场景等拿不到路径时，用户拖文件到对话 → AI 用 `file.read(attachmentId=...)` 读

**期望流**：AI 直接解析 → 对话里能看到文档摘要卡片 + 可展开预览 → Agent 回答时能引用到具体段落 / 单元格 / 幻灯片。

**典型提问**："这份合同的付款条款有风险吗？""这份 Excel 第二季度毛利是多少？""这 PPT 第 5 页讲了什么？"

### 4.2 场景 S2 — 从零生成（产出）

**触发**：用户描述需求，让知微出一份文档。

**期望流**：收集需求（主题、结构、数据） → 先出大纲 → 确认后生成 docx/xlsx/pptx 产物 → 对话里可预览 → 一键下载 / 保存到本地 / 发到飞书。

**典型指令**："帮我起草一份技术方案，关于 XXX""把这组数据做成 Excel 月报模板""生成一份 5 页 PPT 讲 XXX"

### 4.3 场景 S3 — 打磨已有（编辑）

**触发**：用户给出现成文档的本机路径（或拖附件兜底），用自然语言要求修改。

**期望流**：AI 读入 → 预览 → 下指令（"把第 2 段改成 XXX""B 列改成 C 列乘 1.1""删掉第 3 页") → 知微产出修改计划 + diff 预览 → 用户确认 → 保存为新版本 / 覆盖原档。

**核心难点**：保留原文样式（字体、编号、表格、页眉页脚）。

### 4.4 场景 S4 — 批量处理

**触发**：一次性处理多份文档（多个本机路径 / 整个目录 / 或拖一批附件兜底）。

**期望流**：AI 枚举目标文件 → 说明动作（"汇总这 5 份合同的付款条款成一张表""合并这 10 份月报成年报""把所有 docx 转 pdf"）→ 进度可见 → 产物打包下载或保存回原目录。

### 4.5 场景 S5 — 模板填空

**触发**：用户有一份固定格式文档（合同 / 周报 / 台账 / 简历），希望填空式产出。

**期望流**：指定模板路径（或从模板库选）→ 识别变量槽 → 对话里收集变量值（或从记忆 / 结构化数据源拉）→ 渲染产物 → 保存到指定路径。

### 4.6 场景 S6 — 跨渠道协作

**触发**：文档从 IM 渠道（飞书 / 钉钉 / 企微）进入。

**期望流**：渠道收到文件 → 自动入附件库 → Agent 基于记忆/上下文处理 → 产物回发到原渠道（或私信用户）。

### 4.7 场景 S7 — 长期文档资产（知识库）

**触发**：文档需要长期检索、对比、引用。

**期望流**：上传到知识库 → 入库索引 → 跨会话可检索 → 回答问题时引用来源。**此场景已有基础设施（`knowledge` 模块），本文档不重复设计，但需要保证"对话附件" → "沉淀到知识库"的升迁路径通畅。**

---

## 5. 能力架构

按分层抽象，自底向上：

### 5.1 L1 — 解析层（Parsing）

**目标**：把任意支持格式的文档解析为统一的 `DocumentModel`（已有基础：`DocumentParser` / `DocumentElement` / `ParseResult`）。

**扩展项**：
- ✅ 已有：docx、pdf、md、txt
- 🆕 新增：xlsx（工作簿 → 工作表 → 单元格，含公式、合并单元格、批注）
- 🆕 新增：pptx（幻灯片 → 占位符 / 形状 / 表格）
- 🆕 新增：图片 OCR（可选，基于 tesseract 或视觉模型）
- 🆕 新增：旧版 .doc / .xls / .ppt（决定做不做，见决策点 D3）

**输出**：统一结构化 `DocumentModel`，含文本、结构、元数据、原始资源引用（保留样式时要用）。

### 5.2 L2 — 生成层（Authoring）

**目标**：从结构化 `DocumentModel`（或更高层的指令）生成文档文件。

**核心工具**（新增 Agent 工具）：
- `document.create_docx(structure)` — 原生 POI 生成 Word
- `document.create_xlsx(sheets)` — 原生 POI 生成 Excel
- `document.create_pptx(slides)` — 原生 POI 生成 PPT
- `document.create_from_markdown(md, target_format)` — MD → docx/xlsx/pptx 的受控转换（内部走 pandoc 或 POI，视质量决定）

**生成质量要求**：
- 支持标题层级、段落样式、项目列表、表格、基础图表
- 支持字体、颜色、对齐、缩进等基础样式
- 不依赖用户本机装外部 CLI（内置，开箱即用）

### 5.3 L3 — 编辑层（Editing）

**目标**：按指令精确修改已有文档，保留样式。

**两种模式**（见决策点 D4）：
- **模式 A：内容替换** — LLM 产出"查找-替换"风格指令（定位 + 新内容），保留原 run style
- **模式 B：结构化编辑** — LLM 产出类 JSON Patch 指令（"第 2 节第 3 段替换为""表格 1 新增行"），服务端执行

**工具**：
- `document.patch_docx(doc_id, operations)`
- `document.patch_xlsx(doc_id, operations)`
- `document.patch_pptx(doc_id, operations)`

**版本与 Diff**：
- 每次编辑生成新版本（`document_versions` 表）
- 支持 docx 的内容级 diff（按段落比较）
- 前端可展示"改了什么"给用户确认

### 5.4 L4 — 模板层（Templating）

**目标**：把常见文档类型沉淀为可复用模板。

**组件**：
- 模板库（内置 + 用户自定义）：简历、周报、合同、月报、方案模板等
- 模板引擎：基于 **poi-tl**（Word）/ **poi-tl-xlsx 扩展** / 自研（PPT）
- 模板变量填充工具：`document.render_template(template_id, variables)`

### 5.5 L5 — 工具层（Agent Tools）

Agent 可调用的 `document.*` 工具集（新增）：

| 工具 | 用途 |
|---|---|
| `document.parse` | 按附件 ID 或路径解析为 DocumentModel（LLM 可直接读内容） |
| `document.create_docx` / `create_xlsx` / `create_pptx` | 生成新文档 |
| `document.patch_*` | 局部修改 |
| `document.diff` | 对比两个版本 |
| `document.convert` | 格式转换（docx↔pdf 等） |
| `document.render_template` | 模板渲染 |
| `document.merge` | 合并多份 |

### 5.6 L6 — 交互层（Frontend）

**Web / Tauri 共享组件**：
- `DocumentAttachmentCard.vue` — 对话里的附件卡片，展示摘要 + 展开预览 + 下载按钮
- `DocumentPreview.vue` — 文档预览组件：
  - docx：`docx-preview.js`（浏览器渲染）
  - xlsx：`Univer` 或 `Handsontable`
  - pptx：截图式预览（Libreoffice headless 转图片）或简化 HTML 渲染
  - pdf：`pdf.js`
- `DocumentArtifactPane.vue` — 双栏工作台右侧面板（类 Claude Artifacts）：预览 + 编辑 + diff + 保存 / 下载
- `DocumentDiff.vue` — 版本对比 UI

**Tauri 增量**：
- 系统级文件拖拽（webview 层 drop handler）
- 右键菜单"用知微处理"（操作系统集成，平台特定）
- 全局快捷键唤起 + 当前选中文件带入

### 5.7 L7 — 跨切面（Cross-cutting）

- **数据模型**：
  - 新增 `documents` 表（实体化文档）：id / session_id / owner / title / format / latest_version_id / origin（附件升迁 / 新建 / 模板 / 渠道）
  - 新增 `document_versions` 表：id / document_id / file_path / created_at / source（human / agent） / diff_summary
  - `message_attachments` 保留（瞬态），增加 `document_id` 外键指向升迁后的文档
- **存储**：继续用 `~/.zhiwei/documents/`，文件物理隔离于 attachments 目录
- **权限 & 安全**：沿用 `PathSecurityChecker`；文档操作工具加 RiskLevel（生成 LOW / 修改 MEDIUM / 删除 HIGH），走 GuardrailEngine
- **渠道集成**：飞书 / 钉钉 / 企微附件消息 → 自动转存 → 挂到对应 session（见 Phase 5）

---

## 6. 关键决策点（需要老板拍板）

以下决策影响范围与工作量，**需要老板在 spec review 时逐项决定**：

### D1 — 产品形态：单栏 vs 双栏

- **A. 单栏对话附件**：所有文档交互都在消息流里（卡片、预览弹层、下载按钮）
- **B. 双栏 Artifact 工作台**：对话 + 右侧文档面板，文档作为一等公民
- **C. 两者并存**：默认单栏，用户主动"打开文档"进入双栏

推荐 **C**。理由：绝大多数轻交互在单栏完成够用，双栏为重度编辑留空间，避免所有对话都被双栏占掉。

### D2 — 目标格式覆盖优先级

- **A. 先 docx，PoC 验证后扩 xlsx/pptx**
- **B. docx + xlsx 一期做，pptx 二期**
- **C. 三件套一期全做**

推荐 **B**。理由：docx 和 xlsx 是办公高频 80%，pptx 工作量大且样式保真最难，先做前两者的深度，pptx 做"够用的生成"即可。

### D3 — 旧版 .doc/.xls/.ppt 是否支持

- **A. 支持，用 POI HSSF/HWPF**：兼容老数据
- **B. 不支持，只认 Office 2007+ (docx/xlsx/pptx)**：聚焦现代格式

推荐 **B**。理由：POI 对老版本支持质量显著差于新版；新项目无历史包袱，推用户升级。

### D4 — 编辑范式

- **A. 整篇重写**：LLM 输出完整新版本，样式用默认模板
- **B. 定位-替换**：LLM 输出"找到 X，替换为 Y"的列表，服务端按位置替换
- **C. 结构化 Patch**：LLM 输出 JSON Patch（针对 DocumentModel 路径），服务端精确执行

推荐 **C 为主 + B 作为兜底**。理由：C 最能保样式，是业界最佳实践（类 JSON Patch 思路）；B 在简单场景下更直接。

### D5 — 生成质量标准

- **A. 草稿级**：内容对，样式够用
- **B. 可提交级**：样式专业、排版到位，可直接发送
- **C. 定制级**：支持企业模板、品牌 VI

推荐 **B 为目标**，一期先 A 起步，二期上模板库达到 B，C 作为长期扩展。

### D6 — 在线文档集成优先级

- **A. 一期就做飞书文档 / 腾讯文档 API 集成**
- **B. 先打磨本地文档，在线文档作为 Phase 5+**
- **C. 完全不做在线文档（超出定位）**

推荐 **B**。本地文档是基础盘，在线文档依赖三方 API 稳定性，作为后期扩展。

### D7 — 桌面深度集成优先级

- **A. 一期就做 Tauri 系统拖拽 + 右键菜单**
- **B. 先 Web，桌面深度集成作为 Phase 6+**

推荐 **B**。Web 是交付基线，桌面集成回报周期长。

### D8 — 多人协作 / 版本

- **A. 一期就上单机版本管理（document_versions 表）**
- **B. 多人协作 Phase N+**

推荐 **A**。单机版本是 "diff 显示 / undo" 的前提，必须有；多人协作不做。

### D9 — 模板库运营

- **A. 自研预置 + 允许用户上传**
- **B. 只允许用户上传，官方不预置**
- **C. 接第三方模板市场**

推荐 **A**。预置一批高频（周报、会议纪要、简历、合同）做冷启，用户可扩展。

### D10 — AI 改写指令的权限门槛

改 Word / Excel 是有风险的（覆盖用户文件）。工具 RiskLevel 如何设？

- **A. LOW**：自动执行
- **B. MEDIUM**：guardrail 记录 + 自动执行
- **C. HIGH**：每次都用户确认

推荐 **B 默认 + 前端 diff 预览作为用户确认的自然卡点**。覆盖原文件走 MEDIUM；另存新版本走 LOW。

---

## 7. 开源组件候选

### 7.1 解析 / 生成（后端 Java）

| 组件 | 用途 | 评估 |
|---|---|---|
| **Apache POI 5.5.1**（已用） | docx/xlsx/pptx 读写 | API 啰嗦但深度可控、无偿、社区成熟。**主推。** |
| **poi-tl** | docx 模板填充（类 mustache 语法） | 模板场景首选，与 POI 同生态 |
| **docx4j** | docx 进阶处理 | 比 POI 更接近 OOXML 原生；但学习曲线陡；**备选** |
| **Aspose.Words / Cells / Slides** | 商业全家桶 | 质量最高、API 友好，但**收费 + JVM 体积膨胀**，非商业期避免 |
| **pandoc** | MD ↔ 任意格式 | 强大但依赖外部 CLI；**仅作为降级路径** |
| **Apache Tika 3.3.0**（已用） | MIME 检测 + 文本提取 | 已用于 MIME，文本提取可作为兜底 |
| **tesseract-ocr + tess4j** | 图片/扫描件 OCR | 离线可用；**Phase 2+ 考虑** |

### 7.2 前端预览 / 编辑

| 组件 | 用途 | 评估 |
|---|---|---|
| **docx-preview** | 浏览器渲染 docx | 轻量（~100KB），直接渲染 HTML，足够只读预览 |
| **pdf.js** | PDF 预览 | Mozilla 出品，事实标准 |
| **Univer**（国产，开源） | 在线 Excel/Word/PPT | Apache 2.0，字节系出品，能力最全 —— **值得重点评估** |
| **Handsontable** | Excel 编辑 | 社区版免费，商业版收费；如只要预览可用 Univer |
| **LuckySheet**（Univer 前身） | Excel 编辑 | 已停止更新，不推荐新用 |
| **TipTap / ProseMirror** | 富文本编辑（docx artifact 编辑时用） | Notion/飞书同底层；扩展性强 |
| **react-pdf / vue-pdf** | PDF 预览封装 | 基于 pdf.js |
| **OnlyOffice Document Server**（docker） | 完整 Office 在线 | 重量级（容器）、但能力最完整；作为"企业版"可选 |

### 7.3 模板引擎

| 组件 | 用途 | 评估 |
|---|---|---|
| **poi-tl** | docx 模板 | 与 POI 协同，**首选** |
| **docxtemplater**（JS） | docx 模板 | 前端渲染，适合纯浏览器场景 |
| **JxlsTemplate** | xlsx 模板 | 成熟，语法类 Velocity |
| **FreeMarker / Thymeleaf** | 通用模板 | 可用但需要二次封装成文档模板 |

### 7.4 Tauri 侧

| 组件 | 用途 |
|---|---|
| **tauri-plugin-drag** | 文件拖拽到窗口 |
| **tauri-plugin-fs** | 文件系统访问（已内置能力） |
| **tauri-plugin-shell** | 调用系统打开 / 右键菜单 |
| **tauri-plugin-global-shortcut** | 全局快捷键 |

---

## 8. 分阶段实施路线

采用"地基 → 深度 → 交互 → 扩展"的节奏，每个 Phase 可独立交付验证。

### Phase 0 — 地基打通（1 周）

**目标**：把现有孤立能力接起来，让 LLM 能"看到" docx/pdf。

- [ ] 把 `knowledge/parser/` 的 `WordParser` / `PdfParser` / `MarkdownParser` / `PlainTextParser` 抽到通用模块（如 `document/parser/`），供知识库和聊天附件共用
- [ ] 新增 `document.parse` 工具，入参附件 ID 或路径，出参结构化内容
- [ ] `BrowserIngressService` 识别 docx/pdf 附件时，在 effectiveAttachments 旁挂一份文本提取结果（或在 Agent 首轮自动调用 `document.parse`）
- [ ] 前端 `MessageBubble` 对 docx/pdf 附件卡片加"摘要预览"（展示 parser 抽的前 N 段 / 元数据）
- [ ] 数据库：新增 `documents` / `document_versions` 表（Flyway V12）

**产出验证**：拖一份 docx 进对话，问"这份文档讲了什么"，LLM 能答内容要点。

### Phase 1 — 解析完整覆盖（2 周）

**目标**：办公格式全覆盖，"读懂"能力闭环。

- [ ] 新增 `ExcelParser`（POI XSSF）：工作表 / 单元格 / 公式 / 合并单元格 / 批注
- [ ] 新增 `PowerpointParser`（POI XSLF）：幻灯片 / 占位符 / 表格 / 备注
- [ ] `document.parse` 扩展支持 xlsx / pptx
- [ ] 前端 PDF 预览（pdf.js 集成）
- [ ] 前端 docx 预览（docx-preview 集成）
- [ ] 附件卡片 UI 升级：文档类型图标 + 页数 / 字数摘要 + "展开预览"按钮

**产出验证**：四种格式（docx/xlsx/pptx/pdf）均能在对话中展开预览 + 被 LLM 读懂。

### Phase 2 — 生成能力原生化（3 周）

**目标**：告别外部 CLI 绕行，所有办公格式原生生成。

- [ ] 新增 `document.create_docx` 工具（入参结构化大纲 + 样式选项，出参文件）
- [ ] 新增 `document.create_xlsx` 工具（工作表 + 单元格数据 + 格式）
- [ ] 新增 `document.create_pptx` 工具（幻灯片大纲 + 基础模板）
- [ ] 产物链路：工具产出 → 文件存 `~/.zhiwei/documents/` → 入 `documents` 表 → SSE 推送给前端 → 前端展示"AI 产出的文档"卡片 + 下载按钮
- [ ] 后端下载端点：`GET /api/documents/{id}/download?version={v}`
- [ ] `content-creator` / `data-analyst` 等 skill 更新：增加调用 `document.create_*` 的推荐指引

**产出验证**：对话"帮我出一份周报 Word" → 收到可下载的 docx，打开样式正常。

### Phase 3 — 编辑与 Diff（4 周）

**目标**：打磨已有文档不丢样式，用户可控。

- [ ] 设计 `DocumentPatch` 协议（JSON Patch 风格，含路径定位 + 操作类型）
- [ ] 新增 `document.patch_docx` 工具（POI 实现 run-level 替换 + 段落增删 + 表格行列操作，保留样式）
- [ ] 新增 `document.patch_xlsx` 工具（单元格级修改 + 行列操作）
- [ ] `document.diff` 工具：比较两个版本的结构化差异
- [ ] 前端 `DocumentDiff.vue`：段落/单元格级 diff 可视化
- [ ] 保存策略：默认"另存新版本"，用户确认后"覆盖"
- [ ] 版本历史 UI：查看历次修改 + 回滚

**产出验证**：上传一份带格式的 Word → 说"把第 2 段改成 XXX" → 看到 diff → 确认 → 下载产物，原样式保留。

### Phase 4 — 双栏工作台（Artifact）（3 周）

**目标**：文档作为一等公民的交互升级。

- [ ] 新增 `DocumentArtifactPane.vue`：右侧面板承载单一文档的预览 / 编辑 / 历史 / diff
- [ ] ChatView 增加"打开文档"动作：对话中点击文档卡片 → 展开右侧面板
- [ ] 集成 TipTap（docx）/ Univer（xlsx）作为编辑器（可选编辑模式，默认只读预览）
- [ ] AI 输出编辑指令时，同步推送到右侧面板，用户实时看到变化 + 可回滚
- [ ] 产物下载按钮从消息气泡移到 Artifact 面板（更顺手）

**产出验证**：对话"改这份合同" → 右侧面板展开 → 边聊边改 → 满意后下载。

### Phase 5 — 模板与批量（2 周）

**目标**：高频场景零门槛复用。

- [ ] 引入 `poi-tl` 实现 docx 模板填充
- [ ] 新增 `document.render_template(template_id, variables)` 工具
- [ ] 模板库（后端管理）：id / 名称 / 描述 / 变量 schema / 文件
- [ ] 预置模板：周报、月报、会议纪要、简历、合同（若干）
- [ ] 前端"模板中心"入口：浏览 / 使用 / 上传自定义模板
- [ ] 批量处理：FileList 工具升级 + 批量任务 UI（进度条 + 结果包下载）

**产出验证**："用周报模板生成 4 月第三周周报" → 知微按模板填好并下载。

### Phase 6 — 渠道集成（2-3 周，按需）

**目标**：文档从 IM 渠道自然流转。

- [ ] 飞书消息监听：收到文件消息时自动下载 → 入附件/文档库
- [ ] 飞书回发文件能力：知微产出的文档一键发回飞书对话
- [ ] 钉钉 / 企微 同步实现（按优先级）

### Phase 7 — 桌面深度集成（2 周，按需）

**目标**：Tauri 端的顺手体验。

- [ ] `tauri-plugin-drag` 集成：系统文件拖入窗口自动成为附件
- [ ] 右键菜单"用知微处理"（Windows/macOS 平台注册）
- [ ] 全局快捷键（例 `Ctrl+Shift+D`）：唤起 + 当前选中文件带入
- [ ] 托盘菜单快速入口

### Phase 8 — 在线文档集成（按需）

**目标**：飞书文档 / 腾讯文档 / Notion 读写集成。

- [ ] 飞书文档 OpenAPI 适配：读 / 写 / 评论
- [ ] 腾讯文档 / Notion 按需增加
- [ ] 统一 `document.remote_*` 工具集

---

## 9. 风险与未决问题

### 9.1 技术风险

| 风险 | 影响 | 应对 |
|---|---|---|
| POI 对复杂 docx（嵌入对象、公式、图表）支持不足 | 样式保真失败 | 先做 PoC 验证主流模板（合同/报告），复杂场景早发现 |
| 前端 docx/xlsx 预览组件兼容性 | 部分文档渲染错乱 | 多方案并行评估（docx-preview vs Univer），必要时服务端转图片兜底 |
| 保留样式修改的正确率 | 用户信任度 | 强制 diff 预览 + 另存新版本 + 一键回滚 |
| LLM 生成的 patch 指令准确性 | 改错位置 | prompt 里强制让 LLM 输出"操作前/操作后"文本片段供校验；服务端做结构校验 |
| 大文件性能（上百页 docx、数万行 xlsx） | 慢、OOM | 解析 / 预览分页流式处理；设置上限（如 10MB） |

### 9.2 产品/体验风险

- **AI 味重**：生成的文档结构套路、表达机械。缓解：模板库 + 少量示例 + 允许用户事先设"文风记忆"。
- **编辑误伤**：改出用户不想要的结果。缓解：diff + 默认另存 + 版本回滚。
- **能力预期管理**：用户可能期待"AI 帮我排版出版级 PDF"，实际做不到。缓解：UI 文案明确能力边界。

### 9.3 未决问题（待后续讨论）

- **Q1**：`documents` 表和 `knowledge_documents`（如果有）的关系 —— 是否合并成一张文档实体表？需要对齐知识库数据模型。
- **Q2**：文档跨 session 共享的 ACL 模型（目前 session_id 绑定严）。
- **Q3**：多模态 PDF（含扫描件）是走 OCR 还是视觉模型直接理解？成本差异较大。
- **Q4**：生成的文档是否默认进知识库（方便以后检索），还是明确动作才入库？
- **Q5**：移动端（飞书小程序 / 微信小程序）是否作为未来入口？不在本轮范围但需留接口。

---

## 10. 附录：现有代码索引

供后续实施阶段快速定位：

### 附件与上传
- `src/main/java/com/lifepilot/interaction/web/controller/ChatController.java`（L530+：`POST /messages/upload`）
- `src/main/java/com/lifepilot/interaction/web/controller/AttachmentController.java`
- `src/main/java/com/lifepilot/interaction/web/repository/AttachmentRepository.java`
- `src/main/java/com/lifepilot/interaction/web/model/AttachmentInfo.java`
- `src/main/java/com/lifepilot/interaction/web/service/BrowserIngressService.java`

### 消息与 Agent
- `src/main/java/com/lifepilot/interaction/web/service/ChatTurnService.java`
- `src/main/java/com/lifepilot/agent/ReactAgentLoop.java`
- `src/main/java/com/lifepilot/llm/multimodal/`（图片多模态路由）

### 文件工具
- `src/main/java/com/lifepilot/meta/infra/file/FileReadToolExecutor.java`
- `src/main/java/com/lifepilot/meta/infra/file/FileWriteToolExecutor.java`
- `src/main/java/com/lifepilot/meta/infra/file/FilePatchToolExecutor.java`
- `src/main/java/com/lifepilot/meta/infra/file/history/FileEditHistory.java`
- `src/main/java/com/lifepilot/meta/infra/file/PathSecurityChecker.java`

### 已有 parser
- `src/main/java/com/lifepilot/knowledge/parser/DocumentParser.java`
- `src/main/java/com/lifepilot/knowledge/parser/WordParser.java`
- `src/main/java/com/lifepilot/knowledge/parser/PdfParser.java`
- `src/main/java/com/lifepilot/knowledge/parser/MarkdownParser.java`
- `src/main/java/com/lifepilot/knowledge/parser/PlainTextParser.java`
- `src/main/java/com/lifepilot/knowledge/parser/FormatDetector.java`

### 前端
- `zhiwei-web/src/composables/useDragDrop.ts`
- `zhiwei-web/src/components/chat/ChatInput.vue`
- `zhiwei-web/src/components/chat/MessageBubble.vue`
- `zhiwei-web/src/components/knowledge/DropZone.vue`
- `zhiwei-web/src/components/knowledge/UploadProgress.vue`
- `zhiwei-web/src/api/client.ts`（`uploadAttachment`）

### Skill
- `src/main/resources/skills/doc-processor/SKILL.md`
- `src/main/resources/skills/content-creator/SKILL.md`
- `src/main/resources/skills/summarizer/SKILL.md`
- `src/main/resources/skills/data-analyst/SKILL.md`

### 数据库
- `src/main/resources/db/migration/`（最新 V11；新迁移从 V12 起）

### 依赖
- `pom.xml`：Apache POI 5.5.1、Apache Tika 3.3.0、jsoup 1.22.1、Playwright 1.58.0 已在

---

**结束**。本文档为需求蓝图，正式实施前每个 Phase 应拆为独立的 implementation plan（走 `writing-plans` 流程）。
