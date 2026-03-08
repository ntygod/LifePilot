## ZhiWei 对话系统重设计方案

> 版本：v0.1（草案，待迭代打磨）  
> 范围：对话呈现层（Web UI） + 与 AgentLoop / 记忆 / 多模态媒体模块的接口设计

---

### 1. 背景与目标

当前 Web 对话体验存在以下主要问题：

- **推理过程不可见**：当 AgentLoop 推理时间较长时，前端只显示「加载中」，用户无法感知系统在做什么，容易误以为卡死或失败。
- **对话仅支持纯文本**：与系统规划的多模态能力（图片、音频、文件等）不匹配，限制了典型使用场景（知识工作、日程/文档处理等）。
- **前端视图与后端记忆层割裂**：前端仅看到 `SessionSnapshot.recentTurns` 的文本快照，看不到工具调用、记忆检索等关键“轨迹”信息。

本重设计方案的目标：

- **G1 推理可视化**：让用户“看见” Agent 的主要推理阶段和进度，极大缓解长时间等待的焦虑感。
- **G2 多模态对话**：在不推翻现有后端架构的前提下，支持图片/音频/文件等模态的输入输出。
- **G3 架构对齐**：与现有 `AgentLoop`、四层记忆系统、多模态媒体模块无缝衔接，避免重复造轮子。

---

### 2. 总体设计原则

- **前后端职责清晰**  
  - 后端负责：推理事件流、工具调用与媒体处理、记忆读写。  
  - 前端负责：对话信息架构、推理过程展示、多模态交互体验。

- **对话历史与记忆物理分离**  
  - 对话历史（Conversation History）与记忆系统（Memory System）**使用不同的表/存储结构**，各自优化各自的访问与生命周期。  
  - 记忆可以从对话历史中异步/按需抽取信息，但不会与对话表结构强绑定。

- **轻量多模态**  
  - 媒体二进制统一走对象存储/媒体服务，仅在对话与记忆层中存引用和必要摘要，控制 token 与存储成本。

- **可渐进落地**  
  - 优先实现「推理事件流 + UI 展示」（P0），在此基础上分阶段引入图片、音频和文件模态。

---

### 3. 用户体验设计（前端视角）

#### 3.1 布局与信息架构

- **整体布局**（遵循 `docs/ui-design-optimization.md` 与 Web UI 规范）
  - 左侧：`Sidebar`（会话列表 / 预置 Agent），宽度 `w-[260px]`。
  - 右侧：对话区域（消息列表 + 推理过程）+ 底部多模态输入区。
  - 消息区域：`max-w-[768px] mx-auto px-lg py-lg`，始终居中，采用 `MessageBubble` 气泡样式。

- **每条 AI 消息结构**
  - 主体：
    - 文本内容（支持 Markdown / 富文本渲染）。
    - 媒体内容：图片缩略图、音频播放器、文件卡片。
  - 附加信息（可折叠）：
    - 「推理过程」摘要：如 `检索 3 条记忆 · 调用 2 个工具 · 生成 1 个计划`。
    - 展开后显示时间线（TimeLine）：按时间顺序展示关键推理步骤与工具调用。

#### 3.2 推理过程可视化

- **需求场景**
  - LLM 推理时间较长，包含多次工具调用、记忆检索或多轮内部规划。
  - 用户希望理解「系统为什么这么回答」或「系统现在在做什么」。

- **展示形态**
  - 顶部全局状态条（针对当前问题）：
    - 状态阶段示例：
      - `正在分析问题…`（Context / 思考阶段）
      - `正在检索相关记忆…`（记忆检索）
      - `正在调用工具：Web 搜索…`（工具阶段）
      - `正在整理最终答案…`（总结阶段）
  - 消息级时间线（每条 AI 消息内部）：
    - 以竖直时间线展示 `ReasoningEvent`：
      - Context / Memory：加载与检索上下文。
      - Thinking：中间思考与规划步骤。
      - Tool：工具调用（开始 / 结束），可展开查看入参与结果摘要。
      - Answer：起草答案 / 完成答案。
    - 默认折叠，仅展示一行摘要；点击可展开查看详细阶段列表。

- **流式交互**
  - 使用 SSE 或 WebSocket 推送推理事件与部分文本 token：
    - 事件优先：用户在正文 token 出现前，能先看到推理状态变化。
    - 在 token 流结束后，时间线保持可回放状态。

#### 3.3 多模态交互（输入与展示）

- **输入**
  - 文本输入框（支持 Enter 发送 / Shift+Enter 换行）。
  - 多模态入口：
    - 拖拽图片/文件到输入区域。
    - 点击「+」按钮，选择图片、音频或其他文件。
  - 附件列表：
    - 以小卡片形式展示已选附件（缩略图 / 文件名 + 大小）。
    - 支持删除单个附件。

- **展示**
  - 单条消息（用户或 AI）可以包含多条内容：
    - 文本：主内容区域。
    - 图片：固定高度缩略图，点击弹出大图预览。
    - 音频：内嵌播放器（播放 / 暂停 / 进度）。
    - 文件：文件卡片（图标 + 名称 + 大小），点击下载或新窗口打开。
  - 多模态消息与文本共同构成一个 `ConversationTurn`。

---

### 4. 数据与模型设计（后端对齐）

#### 4.1 消息内容模型（MessageContent）

引入统一的多模态消息内容模型，作为对话历史与记忆抽取的基础单元：

```text
MessageContent
- id: String
- type: TEXT | IMAGE | AUDIO | VIDEO | FILE
- text: String?              // 文本内容或媒体的描述/转录
- url: String?               // 媒体文件访问地址（对象存储/CDN）
- mimeType: String?          // image/png, audio/mpeg, application/pdf ...
- metadata: Map<String, Any> // 大小、时长、缩略图 URL 等
```

逻辑上的 `ConversationTurn` 定义为：

```text
ConversationTurn
- turnId: String
- userContents: List<MessageContent>   // 用户侧多模态内容
- agentContents: List<MessageContent>  // AI 侧多模态内容
- createdAt: Instant
- reasoningTraceId: String?            // （可选）关联推理轨迹
```

#### 4.2 对话历史存储模型（Conversation Storage）

在不考虑向后兼容的前提下，为对话历史重新设计一套清晰、可扩展的表结构（以关系型为例）：

```text
conversation_session
- id: BIGINT / UUID         // 会话 ID
- user_id: BIGINT / UUID    // 所属用户
- agent_id: BIGINT / UUID   // 使用的 Agent / 场景
- title: VARCHAR             // 会话标题（可由首轮消息或模型生成）
- status: ENUM(ACTIVE, ARCHIVED, DELETED)
- created_at: TIMESTAMP
- updated_at: TIMESTAMP

conversation_turn
- id: BIGINT / UUID
- session_id: FK -> conversation_session.id
- turn_index: INT            // 会话内顺序，从 1 递增
- role: ENUM(USER, ASSISTANT, TOOL, SYSTEM)
- reasoning_trace_id: VARCHAR? // 可选，关联推理轨迹
- reasoning_summary: TEXT?     // 可选，对本轮推理过程的摘要
- created_at: TIMESTAMP

conversation_message_content
- id: BIGINT / UUID
- turn_id: FK -> conversation_turn.id
- seq: INT                   // 同一 turn 内内容顺序
- type: ENUM(TEXT, IMAGE, AUDIO, VIDEO, FILE)
- text: TEXT?                // 文本内容/描述/转录
- url: VARCHAR?              // 媒体访问地址（CDN/Object Storage）
- mime_type: VARCHAR?        // image/png, audio/mpeg, application/pdf ...
- metadata_json: JSONB?      // 结构化元数据（大小、时长、缩略图等）

conversation_attachment_index  // 可选，用于跨会话按文件/媒体维度检索
- id: BIGINT / UUID
- media_url: VARCHAR
- media_hash: VARCHAR?        // 内容哈希，便于去重与引用计数
- first_turn_id: FK -> conversation_turn.id
- created_at: TIMESTAMP
```

说明：

- **会话维度**（`conversation_session`）承载用户/Agent 维度的信息与生命周期管理（归档、删除等）。  
- **轮次维度**（`conversation_turn`）体现角色与顺序，是与 `AgentLoop` / 记忆抽取管线对接的主要单元。  
- **内容维度**（`conversation_message_content`）为多模态消息提供统一抽象，是记忆与检索的原始语料来源。  
- 媒体文件二进制依然由 `media` 模块与对象存储管理，此处仅保存 URL 与元数据。

##### 4.2.1 索引与典型查询场景

**核心索引建议：**

- `conversation_session`
  - PK：`PRIMARY KEY (id)`
  - 用户维度：`INDEX idx_session_user (user_id, status, updated_at DESC)`
  - Agent 维度：`INDEX idx_session_agent (agent_id, status, updated_at DESC)`
- `conversation_turn`
  - PK：`PRIMARY KEY (id)`
  - 会话顺序：`INDEX idx_turn_session_order (session_id, turn_index)`（覆盖读取一整段会话）
  - 最近活动：`INDEX idx_turn_session_created (session_id, created_at DESC)`
- `conversation_message_content`
  - PK：`PRIMARY KEY (id)`
  - 按轮次读取：`INDEX idx_content_turn_seq (turn_id, seq)`
  - 媒体维度：`INDEX idx_content_type_url (type, url)`
  - 可选全文索引：`FULLTEXT INDEX fts_content_text (text)`（MySQL/InnoDB）或外置 FTS/向量库
- `conversation_attachment_index`
  - 媒体查重：`UNIQUE INDEX uniq_media_hash (media_hash)`（允许为 NULL）
  - 媒体维度查询：`INDEX idx_media_url (media_url)`

**典型访问路径：**

- **前端加载会话列表（Sidebar）**
  - 条件：`WHERE user_id = ? AND status IN ('ACTIVE', 'ARCHIVED')`
  - 排序：`ORDER BY updated_at DESC LIMIT ?, ?`
  - 索引：命中 `idx_session_user`

- **进入会话，加载完整对话历史**
  - Step 1：查 `conversation_session` 确认存在与权限；
  - Step 2：查轮次：`SELECT * FROM conversation_turn WHERE session_id = ? ORDER BY turn_index ASC LIMIT ?, ?`，命中 `idx_turn_session_order`
  - Step 3：批量查内容：
    - 方式 A（推荐）：一次性 `SELECT * FROM conversation_message_content WHERE turn_id IN (...) ORDER BY turn_id, seq`
    - 方式 B：按 turn 分批查询（适合分页加载/懒加载）

- **增量加载：只加载“最近 N 轮”**
  - 直接按 `ORDER BY turn_index DESC LIMIT N` 查询，再在应用层反转顺序；
  - 索引：`idx_turn_session_order`

- **按媒体维度回溯（例如：找出用户上传过某个文件/图片的所有会话）**
  - 通过 `conversation_attachment_index`：
    - `SELECT * FROM conversation_attachment_index WHERE media_hash = ?` 或 `media_url = ?`
  - 若需进一步展开为对话上下文：
    - 通过 `first_turn_id` → `conversation_turn.session_id` → 回到该会话的完整时间线。

- **后端离线任务：记忆抽取 Job 扫描新对话**
  - 通过轮次表增量扫描：
    - 例如维持一个“已处理到的最大 `turn_id`/`created_at`”，周期性执行：
      - `SELECT * FROM conversation_turn WHERE created_at > ? ORDER BY created_at ASC LIMIT ?`
    - 或按会话维度批量拉取“尚未抽取记忆的会话区间”（见 4.4）。

#### 4.3 推理事件模型（ReasoningEvent）

结合现有 `TraceRecorder` 和 `AgentLoop` 状态机，抽象推理事件：

```text
ReasoningEvent
- id: String
- sessionId: String
- turnId: String
- type: AGENT_START
        | CONTEXT_LOADING
        | MEMORY_RETRIEVAL
        | TOOL_CALL_START
        | TOOL_CALL_END
        | THINKING_STEP
        | PLAN_UPDATED
        | ANSWER_DRAFTING
        | ANSWER_FINALIZED
        | ERROR
- title: String           // 面向 UI 的简短标题
- description: String     // 详细描述（可裁剪 / 总结）
- toolName: String?       // TOOL_* 事件时使用
- createdAt: Instant
- extra: Map<String, Any> // Token 用量、检索命中数等
```

**来源与生成：**

- 在 `AgentLoop` / `StateReducer` 关键节点产生日志/trace：
  - 上下文组装 → `CONTEXT_LOADING` / `MEMORY_RETRIEVAL`
  - 工具调用开始 / 结束 → `TOOL_CALL_START` / `TOOL_CALL_END`
  - 规划与中间推理 → `THINKING_STEP` / `PLAN_UPDATED`
  - 生成与完成回答 → `ANSWER_DRAFTING` / `ANSWER_FINALIZED`
- 可复用现有 `TraceRecorder`，增加一个 `ReasoningEventPublisher` 组件，从 Trace 流中抽取出对 UI 有意义的事件。

**存储策略：**

- 短期（P0）：事件主要用于实时 streaming，不强制持久化。
- 中期：为调试和评估需要，可以：
  - 将关键节点压缩为 `reasoningSummary` 文本，保存在 `conversation_turn.reasoning_summary` 上；  
  - 或新建 `reasoning_event` 表，按 `session_id + turn_id` 可查询回放：

```text
reasoning_event
- id: BIGINT / UUID
- session_id: FK -> conversation_session.id
- turn_id: FK -> conversation_turn.id
- type: VARCHAR / ENUM
- title: VARCHAR
- description: TEXT
- tool_name: VARCHAR?
- extra_json: JSONB?
- created_at: TIMESTAMP
```

#### 4.4 记忆存储模型（Memory Storage，逻辑视图）

记忆系统的数据物理上独立于对话历史表，但可以从对话中抽取、引用信息。以下为逻辑模型（具体实现细节见 `agent-memory-lifecycle-impl.md` 与相关文档）：

```text
episodic_memory_entry (L2)
- id: BIGINT / UUID
- user_id: BIGINT / UUID
- agent_id: BIGINT / UUID
- source_session_id: FK -> conversation_session.id
- source_turn_id: FK -> conversation_turn.id
- source_content_ids: TEXT      // 关联的 content ID 列表（可 JSONB）
- summary_text: TEXT            // 针对该 episode 的浓缩描述
- embedding_vector: VECTOR?     // 用于相似度检索（若使用向量库则外置）
- importance_score: FLOAT       // 重要性/显著性分数
- created_at: TIMESTAMP

semantic_memory_entry (L3/L4，简化示意)
- id: BIGINT / UUID
- user_id: BIGINT / UUID
- agent_id: BIGINT / UUID
- source_type: ENUM(CONVERSATION, DOC, TOOL_RESULT, OTHER)
- source_ref: VARCHAR           // 可以是 session/turn/文档等引用
- fact_text: TEXT               // 抽取出的知识/偏好/规则
- embedding_vector: VECTOR?
- created_at: TIMESTAMP
```

核心设计要点：

- **物理分离**：对话历史与记忆表完全独立，各自根据访问模式进行索引与扩展。  
- **逻辑关联**：记忆表通过 `source_session_id`、`source_turn_id`、`source_content_ids` 等字段指向原始对话，支持溯源与回放。  
- **管线解耦**：记忆抽取可以是异步任务（如 Message 过后由后台 Job 按策略挑选部分 turn 进行摘要/嵌入），不会影响主对话链路的写入与响应时延。

##### 4.4.1 记忆抽取 Job 的触发策略与数据流

记忆抽取不再与在线请求强绑定，而是通过一条 **异步、可控的后台管线** 从对话历史中挑选“值得记住”的片段，写入 L2/Episodic 与 L3/L4 语义记忆。

**触发策略（可组合）：**

- **按时间窗口批处理**
  - 周期性任务（例如每分钟/每 5 分钟）：扫描自上次执行以来新增的 `conversation_turn`。
  - 适合均匀流量，易于控制批次大小与资源使用。
- **按会话归档事件**
  - 当会话被显式标记为 `status = ARCHIVED` 或用户点击“结束对话”时，触发对该会话所有轮次的记忆抽取。
  - 适合“项目型/任务型”对话，在任务结束时做一次更重的总结与抽象。
- **按重要性/标记驱动**
  - 允许前端或 Agent 在特定轮次上打标（例如“收藏”、“重要”、“待跟进”），将这些 turn 加入高优先级队列。
  - 抽取 Job 在批处理时优先处理这些高优先级 turn。

**数据流（文字流程）：**

1. **增量扫描对话历史**
   - 维护一个“高水位标记”（例如按 `conversation_turn.id` 或 `created_at`）：
     - Job 读取：`SELECT * FROM conversation_turn WHERE id > last_processed_id ORDER BY id ASC LIMIT batch_size`
   - 对每个 turn：
     - 将 `turn_id` 加入“候选记忆任务列表”（可存入内部队列 / Job 表）。

2. **加载多模态内容作为抽取语料**
   - 对每个候选 `turn_id`：
     - 查询 `conversation_message_content`：
       - 拼接/组合所有 `type = TEXT` 内容为抽取源文本；
       - 对非文本类型（IMAGE/AUDIO/FILE）：
         - 读取其已有的 `text` 描述/转录；
         - 或通过独立的多模态预处理管线（OCR/ASR/文件解析）先生成描述，再写回 `text` 字段后参与抽取。

3. **应用抽取策略（Episodic vs Semantic）**
   - **EpisodicMemory（L2）**：
     - 更偏向“完整回放”与“时间线保留”，通常逐 turn 写入或按小窗口聚合：
       - 例如：将连续的若干 user/assistant turn 合并为一个 episode summary。
     - 策略示例：
       - 对所有 turn 都写入 Episodic，但可设置压缩等级（原文 vs 摘要）。
   - **SemanticMemory（L3/L4）**：
     - 更偏向从对话中抽取“事实/偏好/规则”等长期稳定信息：
       - 例如：“用户偏好早上 9 点开会”、“项目 X 的目标是 Y”。
     - 策略示例：
       - 仅对满足条件的 turn 进行语义抽取（如对话中出现“以后默认…”、“我通常…” 等模式）。

4. **生成记忆条目并写入存储**
   - 对于 L2 Episodic：
     - 产出 `episodic_memory_entry`：
       - `source_session_id = conversation_turn.session_id`
       - `source_turn_id = conversation_turn.id`
       - `source_content_ids` = 该 turn 下所有内容 ID 列表
       - `summary_text` = 模型生成的简要摘要或合并文本
       - `importance_score` = 根据启发式/模型评分打分
   - 对于 L3/L4 Semantic：
     - 产出若干 `semantic_memory_entry`：
       - `source_type = CONVERSATION`
       - `source_ref = session:turn` 或类似复合引用
       - `fact_text` = 单条事实/偏好/规则陈述
       - 选配 `embedding_vector` 用于向量检索。

5. **更新 Job 状态与幂等保证**
   - 抽取成功后，记录“已处理”的高水位：
     - 例如在 `memory_job_state` 表中维护 `last_processed_turn_id`；
     - 或在 `episodic_memory_entry` / `semantic_memory_entry` 上冗余 `source_turn_id` 并加唯一索引，确保重复运行时不会生成重复记忆。

6. **与在线推理链路的衔接**
   - 在线请求通过 `HybridRetriever` 访问记忆：
     - L2：基于 `episodic_memory_entry.summary_text` 做向量/FTS 检索；
     - L3/L4：基于 `semantic_memory_entry.fact_text` 做更长期的语义检索。
   - 抽取 Job 的延迟会直接影响“记忆可用时间”：
     - 对时效性要求高的场景可缩短批处理周期或在特定事件上同步触发一次快速抽取；
     - 对大多数场景，几秒到几十秒的记忆延迟是可以接受的。

---

### 5. 通讯协议设计（前后端 API）

#### 5.1 请求格式（统一规范）

##### 5.1.1 HTTP 路径与方法

- `POST /api/chat/stream`：流式回答（推荐，默认入口）。
- `POST /api/chat/complete`：非流式回答（一次性返回完整结果）。

后端在入口 Controller 层统一将 HTTP 请求转换为内部的 `AgentRequest`，并交给 `AgentLoop` 处理。

##### 5.1.2 通用请求体结构

```json
{
  "sessionId": "sess-123",
  "agentId": "default-agent",
  "userId": "user-001",
  "contents": [
    {
      "type": "TEXT",
      "text": "帮我分析这张图片"
    },
    {
      "type": "IMAGE",
      "url": "upload://temp/123.png",
      "mimeType": "image/png",
      "metadata": {
        "width": 1024,
        "height": 768,
        "thumbnailUrl": "https://cdn.xx/thumb/123.png"
      }
    }
  ],
  "meta": {
    "client": "lifepilot-web",
    "locale": "zh-CN",
    "traceId": "trace-xxx"
  },
  "options": {
    "stream": true,
    "temperature": 0.3,
    "maxTokens": 1024
  }
}
```

字段说明：

- `sessionId`：
  - 为空时表示新会话，后端自动创建 `conversation_session` 记录并返回新的 `sessionId`。
  - 非空时追加到既有会话，需做权限校验（`userId` 与 `conversation_session.user_id` 一致）。
- `agentId`：可选，指定使用的 Agent / 场景；为空时走默认 Agent。
- `userId`：从认证体系中注入，前端一般不直接传递此字段（此处仅为逻辑模型）。
- `contents`：与 `MessageContent` 模型一一对应：
  - 服务端需要校验 `type` 与 `url/text/mimeType` 的组合是否合理。
  - 媒体类型的 `url` 必须来自受信任的上传接口（见 6.1）。
- `meta`：记录客户端环境信息、语言偏好、追踪 ID 等。
- `options`：推理配置（温度、最大 Token、stream 是否开启等），大部分字段可以有服务端缺省值。

#### 5.2 Streaming 响应（SSE / WebSocket）

以 SSE 为例，约定统一的事件类型与数据结构：

##### 5.2.1 事件类型总览

- `event: init`：可选，表示本轮推理开始（包含 turnId 占位信息）。
- `event: reasoning`：推理事件流，驱动时间线和状态条。
- `event: token`：正文 Token 流。
- `event: done`：本轮回答完成（结构化结果）。
- `event: error`：本轮请求失败或中断。

##### 5.2.2 事件数据结构

`reasoning` 事件（对应 `ReasoningEvent`）：

```text
event: reasoning
data: {
  "sessionId": "sess-123",
  "turnId": "turn-temp-1",
  "event": {
    "id": "re-001",
    "type": "MEMORY_RETRIEVAL",
    "title": "检索记忆",
    "description": "从最近 50 条对话中检索相关记忆",
    "toolName": null,
    "createdAt": "2025-03-01T10:00:00Z",
    "extra": {
      "hitCount": 3
    }
  }
}
```

`token` 事件：

```text
event: token
data: {
  "sessionId": "sess-123",
  "turnId": "turn-temp-1",
  "delta": "正在为你分析这张图片",
  "index": 0
}
```

`done` 事件：

```text
event: done
data: {
  "sessionId": "sess-123",
  "turnId": "turn-xxx",
  "usage": {
    "inputTokens": 1234,
    "outputTokens": 567
  },
  "reasoningSummary": "检索 3 条记忆，调用 1 个工具，完成图像分析。",
  "contents": [
    {
      "type": "TEXT",
      "text": "这是针对你上传图片的分析结果……"
    },
    {
      "type": "IMAGE",
      "url": "https://cdn.xx/thumb/123.png",
      "mimeType": "image/png",
      "metadata": {
        "thumbnailUrl": "https://cdn.xx/thumb/123.png"
      }
    }
  ]
}
```

`error` 事件：

```text
event: error
data: {
  "code": "AGENT_TOOL_TIMEOUT",
  "message": "工具调用超时，请稍后重试或简化问题。",
  "retryable": true
}
```

##### 5.2.3 非流式响应（/complete）

非流式接口 `/api/chat/complete` 在 HTTP Body 中直接返回与 `done` 事件相同的数据结构：

```json
{
  "sessionId": "sess-123",
  "turnId": "turn-xxx",
  "usage": {
    "inputTokens": 1234,
    "outputTokens": 567
  },
  "reasoningSummary": "检索 3 条记忆，调用 1 个工具，完成图像分析。",
  "contents": [
    { "type": "TEXT", "text": "这是针对你上传图片的分析结果……" }
  ]
}
```

服务端实现上，`/stream` 与 `/complete` 共享同一套 `AgentLoop` 调用逻辑，仅在输出形式（SSE vs 一次性 JSON）上有所差异。

---

### 6. 多模态能力与现有模块集成

#### 6.1 媒体上传与存储

- 前端：
  - 通过统一上传接口（如 `POST /api/media/upload`）提交二进制文件。
  - 上传成功后获得 `MediaDescriptor`：
    - `url`、`mimeType`、`size`、`duration`、`thumbnailUrl` 等。
- 后端：
  - 由 `com.lifepilot.media` 模块负责：
    - 将文件写入对象存储（MinIO/S3 等）；
    - 生成访问 URL 和缩略图（图片/视频）；
    - 返回给前端的仅为描述信息，不包含大体积数据。

#### 6.2 多模态 LLM 调用

- `LlmRouter` / `MultimodalRouter`：
  - 若场景配置为多模态且 Provider 支持（如 GPT‑4V / Claude 3.5）：
    - 将文本内容 + 图片/音频引用组装为多模态 Prompt。
  - 若当前 Provider 不支持多模态：
    - 走预处理管线：
      - 图片：OCR / 图像描述模型，生成文本描述。
      - 音频：转写为文本（如 Whisper）。
    - 最终仍以纯文本喂给主 LLM。

#### 6.3 记忆系统适配

- **L1 WorkingMemory**
  - `ConversationSlot` 持有：
    - 对话文本（包括媒体的描述/转录）；
    - 媒体引用 ID（用于 UI 展示与后续检索）。
  - `ContextAssembler` 在构造 Prompt 时：
    - 仅注入文本内容和必要的媒体描述，避免原始图片/音频数据进入上下文。

- **L2 EpisodicMemory**
  - `messages` 记录完整多模态对话轨迹：
    - 文本 + 媒体引用；
    - 支持基于文本和媒体描述的混合检索。
  - 冷启动时仍可从 L2 中回灌最近若干轮对话（文本 + 媒体提示）。

- **L3 / L4**
  - 从多模态对话中抽取长期知识与程序模式：
    - 例如：识别用户经常上传某类报表、偏好某种图表形式等。

---

### 7. 渐进式落地计划

> 实施进度（2026-03-02 更新）：  
> - Phase 1：**已完成（后端事件流 + reasoningSummary 持久化 + 消息级折叠面板已落地）**  
> - Phase 2：**已完成图片 / 文档多模态主链路（上传、消息附件、多模态 LLM 调用），待后续打磨配置与评估细节**  
> - Phase 3：**进行中（文件上传与展示已落地，音频上传与播放已接入，语音转文本与文件解析管线后续按多模态架构文档接入）**

#### Phase 1（P0）：推理过程可视化

- 后端：
  - [x] 在 `AgentLoop` / 工具调用处打点，通过 `sendReasoningEvent(...)` 生成 `ReasoningEvent`，并通过 SSE `reasoning` 事件推送到前端。
  - [x] 基于现有 streaming 通道扩展 `reasoning` 事件类型（SSE_EVENT_TYPES.REASONING），前端 `useChat` 已能消费并维护 `reasoningEvents` 与 `reasoningStatusText`。
  - [x] 为每个 `ConversationTurn` 生成并持久化简要 `reasoningSummary`（写入会话历史 / Trace 绑定，通过 `agent_sessions.recent_turns_json` 与 Web 会话历史对齐）。
- 前端：
  - [x] 在 Chat 页面顶部实现全局状态条，基于最新 `ReasoningEvent` 映射「分析中 / 调用工具 / 整理答案」等文案。
  - [x] 在右侧「调试视图」抽屉中实现最近一轮的推理时间线视图（`reasoningEvents`），用于开发者/高级用户查看工具调用与阶段进展。
  - [x] 在每条 AI 消息内部增加「推理过程」折叠面板，与该轮的 `reasoningSummary` 绑定（消息级时间线入口已具备，详细事件回放与 Trace 视图复用后端 ReasoningEvent 流）。

#### Phase 2（P1）：图片多模态

- 媒体上传：
  - [x] 实现图片 / 文件上传接口（`/api/chat/messages/upload`）与本地存储集成，通过 `message_attachments` 表记录附件元信息。
  - [x] 前端输入框支持拖拽/选择文件，消息气泡基于 `attachments` 渲染图片缩略图与大图预览。
- LLM 多模态：
  - [x] 通过 Web 通道的 `attachmentIds` → `GatewayMessage.attachments` → `ExecutionMiddleware.buildMediaContents` → `AgentRequest.mediaContents` → `MultimodalRouter` 打通图片多模态主链路。
  - [ ] 场景配置支持选择多模态 Provider 或走 OCR 文本化降级路径（当前基于 Provider 能力自动选择 VISION Provider，降级策略后续在多模态路由层完善）。

#### Phase 3（P2）：音频 / 文件

- 音频：
  - [x] 上传（复用 `/api/chat/messages/upload` 并扩展常见音频扩展名与 MIME 类型校验）。
  - [x] 简单播放器组件（在 `MessageBubble` 中基于 `attachments` 渲染 `<audio controls>`）。
  - [ ] 语音转文本（复用 `AudioTranscriber` 管线，将音频转录文本注入对话上下文与记忆抽取）。
- 文件：
  - [x] 上传 + 下载（通过 `message_attachments` 表与统一下载 URL 暴露）。
  - [ ] 根据文件类型选择性解析（如 PDF 文本抽取，并将摘要/全文注入记忆与 Prompt）。

---

### 8. 后续需要打磨的细节清单

> 后续讨论可以围绕以下细节逐条打磨与决策：

- **D1**：`ReasoningEvent` 的类型枚举与字段是否需要再精简或拆分为多层级视图（粗粒度 vs 细粒度）。  
- **D2**：`ConversationTurn` 多模态扩展采用「JSON 字段」还是「子表」的持久化方案。  
- **D3**：Streaming 协议选型：统一使用 SSE，还是兼容 WebSocket，两者在客户端 SDK 与负载均衡层的差异。  
- **D4**：多模态输入在 Token 成本与响应时延上的策略（是否允许用户配置「仅文本化处理」模式）。  
- **D5**：推理过程展示给终端用户的“可解释度”与“安全性”边界（哪些内部信息不应直接暴露）。  
- **D6**：与评估与可观测性模块（Eval / Trace）的数据复用与权限隔离。

以上为 v0.1 设计草案，后续可在本文件基础上，通过评审与迭代逐步细化为接口级与实现级设计文档。

---

### 9. 实现级设计（后端与前端落地草案）

> 本章节将前文的抽象设计，细化为可直接指导代码实现的模块划分、类/接口与关键流程伪代码。后端以 Java（Spring）+ 现有 `AgentLoop` 为基础，前端以 Vue 3 + Pinia + shadcn-vue 为基础。

#### 9.1 后端实现方案（Java / Spring）

##### 9.1.1 数据库表结构落地

基于 4.2 ~ 4.4 中的逻辑模型，建议在 DDL 层按如下方式落地（以 PostgreSQL 为例）——实际建表脚本可放在 `db/migration/Vxxx__conversation_history.sql` 中：

```sql
CREATE TABLE conversation_session (
  id              BIGSERIAL PRIMARY KEY,
  user_id         BIGINT      NOT NULL,
  agent_id        BIGINT      NOT NULL,
  title           VARCHAR(255),
  status          VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_session_user
  ON conversation_session (user_id, status, updated_at DESC);

CREATE TABLE conversation_turn (
  id                  BIGSERIAL PRIMARY KEY,
  session_id          BIGINT      NOT NULL REFERENCES conversation_session (id),
  turn_index          INT         NOT NULL,
  role                VARCHAR(32) NOT NULL,
  reasoning_trace_id  VARCHAR(128),
  reasoning_summary   TEXT,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_turn_session_order
  ON conversation_turn (session_id, turn_index);

CREATE TABLE conversation_message_content (
  id            BIGSERIAL PRIMARY KEY,
  turn_id       BIGINT      NOT NULL REFERENCES conversation_turn (id),
  seq           INT         NOT NULL,
  type          VARCHAR(32) NOT NULL,
  text          TEXT,
  url           VARCHAR(2048),
  mime_type     VARCHAR(255),
  metadata_json JSONB,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_content_turn_seq
  ON conversation_message_content (turn_id, seq);

CREATE TABLE reasoning_event (
  id          BIGSERIAL PRIMARY KEY,
  session_id  BIGINT      NOT NULL REFERENCES conversation_session (id),
  turn_id     BIGINT      NOT NULL REFERENCES conversation_turn (id),
  type        VARCHAR(64) NOT NULL,
  title       VARCHAR(255),
  description TEXT,
  tool_name   VARCHAR(255),
  extra_json  JSONB,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

> 说明：
> - 如现有系统已存在会话表，可通过迁移脚本新增字段/子表，并提供一次性数据迁移 Job。
> - 记忆相关表（`episodic_memory_entry` / `semantic_memory_entry`）遵循现有记忆模块的迁移策略，不在此重复。

##### 9.1.2 Java 域模型与 Repository 设计

在 `com.lifepilot.conversation` 包下新增领域模型与 Repository 接口（可基于 Spring Data JPA / MyBatis 等实现）：

- `ConversationSessionEntity`
- `ConversationTurnEntity`
- `ConversationMessageContentEntity`
- `ReasoningEventEntity`

以及对应 Repository：

- `ConversationSessionRepository`
  - `Optional<ConversationSessionEntity> findByIdAndUserId(Long id, Long userId)`
  - `Page<ConversationSessionEntity> findByUserIdAndStatusInOrderByUpdatedAtDesc(...)`
- `ConversationTurnRepository`
  - `List<ConversationTurnEntity> findBySessionIdOrderByTurnIndexAsc(Long sessionId)`
  - `List<ConversationTurnEntity> findTopNBySessionIdOrderByTurnIndexDesc(Long sessionId, int n)`
- `ConversationMessageContentRepository`
  - `List<ConversationMessageContentEntity> findByTurnIdInOrderByTurnIdAscSeqAsc(Collection<Long> turnIds)`
- `ReasoningEventRepository`
  - `List<ReasoningEventEntity> findBySessionIdAndTurnIdOrderByCreatedAtAsc(Long sessionId, Long turnId)`

实体与表字段一一对应，注意：

- `type` 字段建议映射为 Java `enum`（如 `MessageContentType`、`ReasoningEventType`）。
- `metadata_json` / `extra_json` 使用 JSON 映射（可用 Hibernate Types / Spring Data 的 JSON 支持）。

##### 9.1.3 对话服务层与会话管理

新增 `ConversationService`，负责：

- 创建新会话 / 追加轮次；
- 从数据库加载某会话的完整/部分对话历史；
- 生成/更新会话标题与 `updated_at` 字段；
- 将 `AgentLoop` 的输入/输出写回对话历史表。

核心方法示例：

```java
public interface ConversationService {

    ConversationSessionEntity getOrCreateSession(Long sessionId, Long userId, Long agentId);

    ConversationTurnEntity appendUserTurn(
            ConversationSessionEntity session,
            List<MessageContent> contents
    );

    ConversationTurnEntity appendAssistantTurn(
            ConversationSessionEntity session,
            List<MessageContent> contents,
            @Nullable String reasoningSummary,
            @Nullable String reasoningTraceId
    );

    ConversationHistory loadHistory(Long sessionId, Long userId, int limit);
}
```

`ConversationHistory` 为应用层 DTO，包含 `List<ConversationTurnDto>` 与其下的 `List<MessageContentDto>`，供 `AgentLoop` 的 `ContextAssembler` 与前端复用。

##### 9.1.4 AgentLoop 集成推理事件与 Streaming 协议

在现有 `AgentLoop.runStreaming(...)` 基础上，增加一个 `ReasoningEventPublisher` 组件，用于：

- 从 `TraceRecorder` / `StateReducer` 的状态变化中生成 `ReasoningEvent`；
- 通过 `SseSessionManager` 发送 `reasoning` 事件（遵循 5.2.2 协议）；
- 可选地将关键事件持久化到 `reasoning_event` 表。

伪代码示例：

```java
public final class ReasoningEventPublisher {

    private final SseSessionManager sseManager;
    private final ReasoningEventRepository repository;

    public void publishContextLoading(String streamId, String sessionId, String tempTurnId) {
        var payload = Map.of(
            "sessionId", sessionId,
            "turnId", tempTurnId,
            "event", Map.of(
                "id", UUID.randomUUID().toString(),
                "type", "CONTEXT_LOADING",
                "title", "正在分析问题",
                "description", "组装上下文与最近对话片段"
            )
        );
        sseManager.sendEvent(streamId, SseEventType.REASONING, payload);
        // 可选：落表 repository.save(...)
    }

    // TOOL_CALL_START / TOOL_CALL_END / ANSWER_DRAFTING 等类似
}
```

在 `AgentLoop` 中：

- 在上下文组装前后、记忆检索、工具调用前后、回答起草/完成时调用 `ReasoningEventPublisher`；
- 在最终 `DONE` 事件数据中填充：
  - `sessionId`：`request.sessionId()` 或新创建会话的 ID；
  - `turnId`：新建的 `conversation_turn.id`；
  - `reasoningSummary`：可由 `TraceRecorder` 或独立 summarizer 生成。

##### 9.1.5 HTTP Controller 与 DTO 映射

在 `com.lifepilot.interaction.web.chat` 包下新增控制器：

- `ChatController`
  - `POST /api/chat/stream`
  - `POST /api/chat/complete`

关键流程（简化）：

```java
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final AgentLoop agentLoop;
    private final ConversationService conversationService;
    private final SseSessionManager sseManager;

    @PostMapping("/stream")
    public SseEmitter stream(@RequestBody ChatRequestDto request, Authentication auth) {
        Long userId = extractUserId(auth);
        ConversationSessionEntity session =
            conversationService.getOrCreateSession(request.getSessionId(), userId, request.getAgentId());

        // 1) 写入用户轮次
        ConversationTurnEntity userTurn =
            conversationService.appendUserTurn(session, request.toMessageContents());

        // 2) 创建 SSE 流
        String streamId = UUID.randomUUID().toString();
        SseEmitter emitter = sseManager.createEmitter(streamId);

        // 3) 调用 AgentLoop（异步）
        AgentRequest agentRequest = request.toAgentRequest(session.getId(), userTurn.getId(), userId);
        CompletableFuture.runAsync(() -> agentLoop.runStreaming(agentRequest, streamId, sseManager));

        return emitter;
    }
}
```

DTO 设计：

- `ChatRequestDto`：与 5.1.2 中的 JSON 结构一一对应，提供 `toMessageContents()` 与 `toAgentRequest(...)` 帮助方法。
- `MessageContentDto`：映射 `type/text/url/mimeType/metadata`。
- `ChatResponseDto`：非流式 `/complete` 使用，结构与 `done` 事件一致。

#### 9.2 前端实现方案（Vue 3 + Pinia）

##### 9.2.1 TypeScript 类型与 Store 设计

在 `src/types/chat.ts` 中定义核心类型：

```ts
export type MessageContentType = 'TEXT' | 'IMAGE' | 'AUDIO' | 'VIDEO' | 'FILE';

export interface MessageContent {
  id?: string;
  type: MessageContentType;
  text?: string;
  url?: string;
  mimeType?: string;
  metadata?: Record<string, any>;
}

export type Role = 'USER' | 'ASSISTANT' | 'TOOL' | 'SYSTEM';

export interface ConversationTurn {
  id: string;
  sessionId: string;
  role: Role;
  contents: MessageContent[];
  createdAt: string;
  reasoningSummary?: string;
  reasoningEvents?: ReasoningEvent[];
}

export interface ReasoningEvent {
  id: string;
  type:
    | 'AGENT_START'
    | 'CONTEXT_LOADING'
    | 'MEMORY_RETRIEVAL'
    | 'TOOL_CALL_START'
    | 'TOOL_CALL_END'
    | 'THINKING_STEP'
    | 'PLAN_UPDATED'
    | 'ANSWER_DRAFTING'
    | 'ANSWER_FINALIZED'
    | 'ERROR';
  title: string;
  description?: string;
  toolName?: string;
  createdAt: string;
  extra?: Record<string, any>;
}
```

在 `src/stores/conversation.ts` 中定义 Pinia Store：

- `state`：
  - `sessions: Record<string, { id, title, status, turns: ConversationTurn[] }>`
  - `activeSessionId: string | null`
  - `streamingState: { loading: boolean; statusText: string; currentReasoningEvents: ReasoningEvent[] }`
- `actions`：
  - `sendMessage(payload: { sessionId?: string; contents: MessageContent[] })`
  - `handleSseEvent(event: SseEvent)`
  - `appendTurn(turn: ConversationTurn)`
  - `updateReasoningEvents(turnId: string, event: ReasoningEvent)`

##### 9.2.2 SSE 客户端实现

在 `src/lib/chatStream.ts` 中封装与后端 SSE 协议的交互：

```ts
export function openChatStream(request: ChatRequest, onEvent: (e: ServerSentEvent) => void) {
  const url = '/api/chat/stream';
  const es = new EventSourcePolyfill(url, {
    headers: { 'Content-Type': 'application/json' },
    heartbeatTimeout: 60_000,
  });

  es.addEventListener('reasoning', (ev) => onEvent({ type: 'reasoning', data: ev.data }));
  es.addEventListener('token', (ev) => onEvent({ type: 'token', data: ev.data }));
  es.addEventListener('done', (ev) => onEvent({ type: 'done', data: ev.data }));
  es.addEventListener('error', (ev) => onEvent({ type: 'error', data: ev.data }));

  // 发送请求体（如采用 POST + SSE 需使用 fetch + streamId 协议，此处根据实际实现调整）

  return es;
}
```

在 Store 的 `sendMessage` 中：

- 构造 `ChatRequest`（包含 `sessionId/agentId/contents/meta/options`）；
- 调用 `openChatStream`，并在回调中解析：
  - `reasoning`：更新当前轮次的 `reasoningEvents` 与顶部状态文案；
  - `token`：将增量文本拼接到“临时 AI 消息气泡”；
  - `done`：落地为最终 `ConversationTurn`，替换临时消息并关闭流；
  - `error`：展示错误提示，重置 Streaming 状态。

##### 9.2.3 组件拆分与样式约束

基于 `ZhiWei Web UI 设计规范`，推荐组件结构：

- `ChatPage.vue`：整体布局（Sidebar + 对话区域），使用 `flex flex-col h-screen`。
- `MessageList.vue`：渲染 `ConversationTurn[]`，控制 `max-w-[768px] mx-auto px-lg py-lg`。
- `MessageBubble.vue`：单条消息气泡，支持多模态内容插槽与「推理过程」折叠面板。
- `ReasoningTimeline.vue`：接收 `ReasoningEvent[]`，渲染时间线。
- `ChatInput.vue`：多行输入框 + 附件列表 + 发送按钮，遵循圆角与间距规范。

关键约束：

- 所有间距使用文档中定义的刻度（如 `gap-md` / `px-lg` / `py-lg`），避免任意 `p-3` 等。
- 消息气泡、输入框统一使用 `rounded-2xl`，用户/AI 气泡通过一侧 `rounded-sm` 制造尖角效果。
- 图标统一使用 `lucide-vue-next`，线性风格，尺寸 `w-4 h-4` 为主。

##### 9.2.4 推理过程 UI 行为逻辑

在 `ReasoningTimeline.vue` 中：

- 默认保持折叠，仅展示一行摘要（由 Store 根据最新 `ReasoningEvent` 生成，如「检索 3 条记忆 · 调用 1 个工具」）；
- 点击后展开完整列表，按 `createdAt` 升序展示；
- 对于 `TOOL_CALL_START/TOOL_CALL_END`，高亮展示工具名称与状态；
- 对于 `ERROR` 事件，使用 `text-destructive` + 图标强调。

顶部状态条逻辑：

- 当收到 `CONTEXT_LOADING` 事件 → 显示「正在分析问题…」。
- 当收到 `MEMORY_RETRIEVAL` 事件 → 显示「正在检索相关记忆…」。
- 当收到 `TOOL_CALL_START` 事件 → 显示「正在调用工具：{toolName}…」。
- 当收到 `ANSWER_DRAFTING` / `ANSWER_FINALIZED` 事件 → 更新为「正在整理最终答案… / 已完成」。

状态条组件可命名为 `ChatStatusBar.vue`，挂在 `ChatPage.vue` 中，订阅 Pinia Store 的 `streamingState.statusText`。

---

以上实现级设计为第一版草案，后续在实际编码过程中，可将各类/接口与字段名与现有代码库进一步对齐，并通过单独的 `api-contract.md` 与 `backend-conversation-impl.md` 文档沉淀最终实现细节。
