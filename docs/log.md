# 工具生态分析报告（2026-03-16）

## 一、当前工具清单（50+ 工具）

### 基础设施工具（InfraToolProvider，20 个）
- 时间日期：datetime
- 用户画像：user-profile
- 系统信息：system-info
- 网络搜索：web-search、web-fetch
- 思考辅助：think
- 数学计算：calculate
- Shell 执行：shell-exec
- 浏览器自动化（4 个）：browser-navigate、browser-click、browser-input、browser-screenshot
- 代码执行：code-execute
- 文件操作（4 个）：file-read、file-write、file-list、file-search
- 用户交互（3 个）：choose、input、notify

### 领域 Skill 工具
- Todo（6）、Schedule（6）、Habit（7）、Memory（5）、DataStore（7）、Sync（4）

### 元能力/自省工具（IntrospectionSkillProvider，5 个）
- list-capabilities、explain、status、suggest、runtime

### Skill 管理工具（1 个）
- skills（list_skills / activate_skill）

## 二、缺失能力

| 序号 | 能力 | 说明 | 优先级 |
|------|------|------|--------|
| 1 | 提醒/闹钟 | 基于时间的触发器，定时提醒用户 | 高 |
| 2 | 剪贴板读写 | 读取/写入系统剪贴板 | 中 |
| 3 | 邮件/外部消息 | 发送邮件、调用外部消息 API | 中 |
| 4 | 图片/PDF/图表生成 | 生成可视化内容 | 低 |
| 5 | 通用 HTTP 请求 | POST/PUT/DELETE，当前仅有只读的 web-fetch | 高 |
| 6 | 数据格式转换 | JSON/YAML/CSV/XML 互转 | 低 |
| 7 | 文本处理 | 正则匹配、编码/解码 | 低 |

## 三、设计问题

### 3.1 SkillTool.execute() 抛出 UnsupportedOperationException
- 位置：`com.lifepilot.tool.SkillTool`
- 问题：违反 ToolContract 接口契约，execute() 应该是可调用的
- 建议：委托给 SkillExecutionEngine 执行，或移除 ToolContract 实现

### 3.2 McpTool record 持有有状态的 McpClient 引用
- 位置：`com.lifepilot.tool.McpTool`
- 问题：record 的 equals/hashCode/toString 会包含 McpClient，破坏 record 语义
- 建议：将 McpClient 移到外部注册表，McpTool 仅持有 clientId

### 3.3 ToolResult.data 类型不安全
- 位置：`com.lifepilot.tool.model.ToolResult`
- 问题：`Map<String, Object>` 缺乏类型安全，与项目强调的类型安全理念不符
- 建议：引入 sealed interface ToolResultData，按工具类型细分

### 3.4 InfraToolProvider 注册 20 个工具，职责过重
- 建议：按类别拆分为 BrowserToolProvider、FileToolProvider、InteractionToolProvider 等

### 3.5 浏览器工具缺少会话生命周期管理
- 问题：无 browser-open / browser-close 工具，Agent 无法主动管理会话
- 建议：新增 browser-open（创建会话）和 browser-close（关闭会话）工具

### 3.6 搜索工具缺少分页
- 问题：file-search、web-search 无 offset/limit 参数
- 建议：添加分页参数支持

## 四、冗余/可合并项

1. **Todo/Schedule/Habit CRUD 工具高度同构**：可抽象统一的 CRUD 工具工厂
2. **skills.list_skills 与 introspection.list-capabilities 重叠**：合并为一个
3. **think 工具价值存疑**：在 ReAct 循环中，LLM 本身就在"思考"，独立 think 工具意义不大

## 五、架构建议

1. **工具数量膨胀问题**：需要分组/懒加载机制，避免每次对话注入 50+ 工具描述
2. **缺少工具组合/链式调用能力**：复杂任务需要多工具协作
3. **ToolResult 缺少中间状态**：如 partial_success、rate_limited

---

## 六、浏览器自动化能力完善方案   --已实现

### 6.1 现状

已有基于 Playwright 的浏览器自动化架构：
- `BrowserSessionManager`：懒初始化 Playwright，会话级 Page 复用（sessionId → Page），空闲超时自动清理
- `PlaywrightBridge`：隔离 Playwright API 调用，Playwright 作为可选依赖，classpath 缺失时优雅降级
- `PlaywrightPageWrapper`：封装 navigate、click、fill、screenshot、textContent 操作
- 4 个已注册工具：browser-navigate、browser-click、browser-input、browser-screenshot

### 6.2 不足

1. **缺少会话生命周期工具**：无 browser-open / browser-close，Agent 无法主动管理会话
2. **缺少高级操作工具**：
    - `browser-wait`：等待元素出现/消失（SPA 页面必需）
    - `browser-eval`：执行任意 JavaScript（灵活性兜底）
    - `browser-extract`：提取结构化数据（表格、列表等）
3. **缺少 Accessibility Tree 快照**：CSS 选择器对 Agent 不友好，Accessibility Tree 是 computer-use 方案的核心，能让 Agent 理解页面语义结构
4. **截图无多模态回传通路**：screenshot 返回 Base64，但当前 Agent 上下文组装未支持图片嵌入回传给 LLM
5. **无 Tab/多页面管理**：当前一个 session 只有一个 Page，无法同时操作多个页面

### 6.3 补齐计划

| 工具 | 功能 | 优先级 |
|------|------|--------|
| browser-open | 创建浏览器会话，返回 sessionId | 高 |
| browser-close | 关闭指定会话 | 高 |
| browser-wait | 等待选择器匹配元素出现，支持超时 | 高 |
| browser-eval | 在页面上下文执行 JavaScript，返回结果 | 中 |
| browser-extract | 提取页面结构化数据（表格/列表/表单） | 中 |
| browser-accessibility-snapshot | 获取页面 Accessibility Tree 快照 | 中 |
| browser-scroll | 页面滚动（上/下/到指定元素） | 低 |
| browser-select | 下拉框选择 | 低 |

### 6.4 架构调整建议

1. 将浏览器工具从 `InfraToolProvider` 拆分为独立的 `BrowserToolProvider`
2. `PlaywrightPageWrapper` 补充 waitForSelector、evaluate、accessibilitySnapshot 方法
3. 截图结果接入多模态上下文通路（配合 multimodal 模块），让 LLM 能"看到"页面
4. 考虑引入 TextSnapshotCleaner（已有该类）优化页面文本提取质量

---

## 七、桌面控制能力 — MCP 委托方案 --已实现

### 7.1 方案选型

| 方案 | 原理 | 优点 | 缺点 | 推荐度 |
|------|------|------|------|--------|
| Java AWT Robot | JDK 内置键鼠模拟 + 截屏 | 零依赖 | 盲操作，无法感知 UI 元素 | ⭐⭐ |
| Windows UI Automation | JNA/JNI 调用 UIAutomation API | 精准定位 UI 元素 | 仅 Windows，实现复杂 | ⭐⭐ |
| MCP 委托 | 接入 computer-use 类 MCP Server | 架构最干净，能力最强，跨平台 | 依赖外部 MCP Server | ⭐⭐⭐⭐⭐ |
| Playwright + Electron | 复用浏览器架构连接 Electron 应用 | 复用现有代码 | 仅限 Electron 应用 | ⭐⭐⭐ |

**结论：采用 MCP 委托方案。**

### 7.2 理由

1. ZhiWei 已有完整 MCP 集成架构（`McpToolAdapter`、`DynamicToolRegistry`），接入外部 MCP Server 几乎零成本
2. 桌面自动化涉及大量平台特定代码（Windows/macOS/Linux），自研维护成本极高
3. 社区已有成熟的 computer-use MCP Server（如 Anthropic computer-use、Open Interpreter 等），提供截屏 + Accessibility Tree + 键鼠操作完整能力
4. ZhiWei 专注 Agent 编排层，桌面控制通过 MCP 生态按需扩展

### 7.3 实施路径

**短期（内置轻量兜底）**：
- 基于 `java.awt.Robot` 实现 3 个基础工具：`desktop-screenshot`、`desktop-click`、`desktop-type`
- 作为 InfraToolProvider 的可选工具注册，headless 环境自动禁用
- 截图配合多模态能力回传给 LLM

**长期（MCP 生态接入）**：
- 在 MCP 配置中预置推荐的 computer-use MCP Server 配置模板
- 用户通过 `mcp.json` 一键启用桌面控制能力
- Agent 自动发现并使用 MCP 提供的桌面控制工具

### 7.4 与浏览器自动化的关系

- Web 应用：优先使用内置 Playwright 浏览器工具（更精准、更快）
- Electron 桌面应用：可通过 Playwright CDP 连接，复用浏览器工具
- 原生桌面应用：走 MCP 委托的 computer-use 方案
- Agent 路由层根据目标应用类型自动选择合适的工具集

---

## 八、本地文件操作能力分析   -已实现

### 8.1 当前工具清单（4 个）

| 工具 ID | 功能 | 风险等级 | 关键参数 |
|---------|------|---------|---------|
| `builtin.file.read` | 读取文件内容 | LOW | path, encoding（默认 UTF-8） |
| `builtin.file.write` | 原子写入文件 | MEDIUM | path, content, createDirectories（默认 true） |
| `builtin.file.list` | 列出目录内容 | LOW | path, maxDepth（默认 3）, pattern（glob） |
| `builtin.file.search` | 递归搜索文件内容 | LOW | path, pattern（正则）, filePattern（glob）, maxResults（默认 50） |

### 8.2 安全模型

`PathSecurityChecker` 实现白名单/黑名单双重校验：
- 白名单非空时：路径必须在白名单目录下
- 白名单为空时：默认允许用户 home 目录下所有路径
- 黑名单：默认拒绝 `/etc`、`/var`、`C:\Windows`
- 写入场景用 `toAbsolutePath().normalize()`（文件可能不存在），读取场景用 `toRealPath()`（解析符号链接）
- 配置通过 `MetaProperties.Infra.FileAccess` 外部化

### 8.3 实现亮点

1. **原子写入**：`FileWriteToolExecutor` 先写临时文件再 `Files.move(ATOMIC_MOVE)`，防止写入中断导致文件损坏
2. **大文件截断**：`FileReadToolExecutor` 超过 `maxReadSize`（默认 1MB）自动截断，附带截断提示
3. **编码支持**：读取支持指定编码（UTF-8、GBK 等）
4. **glob + 正则双模式**：list 用 glob 过滤文件名，search 用正则匹配内容 + glob 过滤文件名
5. **符号链接安全**：读取场景通过 `toRealPath()` 解析符号链接后再校验，防止符号链接绕过白名单

### 8.4 缺失能力

| 序号 | 能力 | 说明 | 优先级 |
|------|------|------|--------|
| 1 | **file-append** | 追加写入，当前只有全量覆盖写入，Agent 无法向日志/笔记追加内容 | 高 |
| 2 | **file-delete** | 删除文件/目录，当前无删除能力，Agent 无法清理临时文件 | 高 |
| 3 | **file-copy / file-move** | 复制/移动/重命名文件，基础文件管理操作 | 中 |
| 4 | **file-info** | 获取文件元信息（大小、修改时间、权限、MIME 类型），不读取内容 | 中 |
| 5 | **file-watch** | 监听文件/目录变化事件，支持自动化工作流触发 | 低 |
| 6 | **file-diff** | 比较两个文件差异，辅助 Agent 理解变更 | 低 |
| 7 | **file-patch** | 基于行号的局部编辑（插入/替换/删除指定行），避免大文件全量重写 | 高 |
| 8 | **archive** | 压缩/解压（zip/tar.gz），文件打包和分发 | 低 |

### 8.5 设计问题

#### 8.5.1 file-write 只支持全量覆盖，缺少追加和局部编辑

当前 `FileWriteToolExecutor` 只有一种模式：全量覆盖写入。这意味着：
- Agent 想往文件末尾追加一行，必须先 read 整个文件，拼接后再 write 回去 → Token 浪费严重
- Agent 想修改文件中间某几行，同样需要 read → 修改 → write 全量 → 大文件场景不可行
- 建议：新增 `file-append`（追加模式）和 `file-patch`（行级编辑模式）

#### 8.5.2 file-read 缺少行范围读取

当前只能读取整个文件（超过 1MB 截断）。Agent 经常只需要看文件的某几行（如日志尾部、配置文件某段），但不得不读取全部内容。
- 建议：增加 `startLine` / `endLine` 可选参数，支持按行范围读取
- 或增加 `tail` 参数，支持读取文件最后 N 行（日志场景常用）

#### 8.5.3 file-search 缺少上下文行

搜索结果只返回匹配行本身（`content` 字段），没有上下文。Agent 看到一行匹配结果往往无法理解含义，需要再调用 file-read 查看上下文。
- 建议：增加 `contextLines` 参数（默认 2），返回匹配行前后各 N 行

#### 8.5.4 file-list 无排序和分页

`FileListToolExecutor` 使用 `Files.walk()` 遍历，结果无排序、无分页。大目录可能返回大量条目。
- 建议：增加 `sortBy`（name/size/modified）和 `offset`/`limit` 参数
- 或增加 `maxEntries` 参数限制返回条目数

#### 8.5.5 file-search 对二进制文件处理粗糙

`FileSearchToolExecutor` 用 `Files.readAllLines()` 读取文件，遇到二进制文件会抛 IOException 然后跳过。但没有预先检测文件是否为二进制。
- 建议：在读取前通过文件头字节或 MIME 类型检测跳过二进制文件，避免无谓的 IO 和异常

#### 8.5.6 缺少文件删除能力

Agent 无法删除文件或目录。这在以下场景是必需的：
- 清理代码执行产生的临时文件
- 删除用户明确要求删除的文件
- 文件管理自动化
- 建议：新增 `file-delete` 工具，风险等级 HIGH（需用户确认），支持文件和空目录删除

#### 8.5.7 PathSecurityChecker 未考虑 Windows 路径大小写

Windows 文件系统大小写不敏感，但 `Path.startsWith()` 是大小写敏感的。如果白名单配置为 `C:\Users` 而实际路径为 `c:\users`，校验会失败。
- 建议：Windows 平台下统一转为小写后比较

### 8.6 改进优先级排序

**P0（核心缺失，影响日常使用）**：
1. `file-append` — 追加写入
2. `file-patch` — 行级局部编辑
3. `file-read` 增加行范围参数
4. `file-delete` — 文件删除

**P1（体验提升）**：
5. `file-search` 增加上下文行
6. `file-info` — 文件元信息
7. `file-copy` / `file-move` — 复制/移动
8. Windows 路径大小写修复

**P2（锦上添花）**：
9. `file-list` 排序和分页
10. `file-search` 二进制文件预检测
11. `file-diff` — 文件差异比较
12. `archive` — 压缩/解压

---

## 九、文件操作工具性能分析   --已优化

### 9.1 执行链路概览

工具调用的完整链路：
```
LLM 生成 function call → Spring AI 解析 → ToolBridgeAgentToolProvider.call()
  → JSON 反序列化参数 → ToolExecutionPipeline.execute()
    → 参数校验 → 护栏检查 → 幂等检查 → executeWithTimeout(Virtual Thread)
      → FileXxxToolExecutor.execute() → PathSecurityChecker → 实际 IO
    → 构建 ToolResultMeta → 返回 ToolResult
  → formatOutput(result) → JSON 序列化 → 回传 LLM 作为 ToolResponseMessage
```

### 9.2 各工具性能特征


#### 9.2.1 file-read（读取文件）

**耗时**：
- 小文件（<100KB）：<5ms（纯磁盘 IO + 字符串构建）
- 大文件（接近 1MB 截断线）：10-50ms（取决于磁盘速度）
- 管线开销（参数校验 + 护栏 + 幂等检查）：<1ms

**内存**：
- 小文件：文件大小 × 2（byte[] + String 双份）
- 大文件截断：固定 1MB byte[] + 1MB String ≈ 2MB 峰值
- 问题：`new byte[maxReadSize]` 直接分配 1MB 数组，即使文件只有 1KB 也分配 1MB（截断分支）

**CPU**：几乎可忽略，瓶颈在磁盘 IO

**Token 消耗**（回传 LLM 的最大开销）：
- 文件内容直接序列化为 JSON 字符串回传，无截断机制
- 1MB 文本 ≈ 250K-500K Token（取决于语言和编码）
- 这是最严重的性能问题：maxReadSize=1MB 的文件内容全量回传给 LLM，远超大多数模型的上下文窗口
- Trace 记录截断到 2000 字符，但回传 LLM 的 formatOutput() 无截断

**风险**：Agent 读取一个 800KB 的日志文件，内容全量注入 LLM 上下文，直接撑爆 Token 预算


#### 9.2.2 file-write（写入文件）

**耗时**：
- 小文件：5-20ms（创建临时文件 + 写入 + atomic move）
- 大内容：取决于内容大小，10-100ms
- 创建父目录：额外 1-5ms

**内存**：
- content 参数本身已在内存中（LLM 生成的字符串）
- `content.getBytes(UTF_8)` 额外分配一份 byte[]
- 临时文件写入后立即 move，不会长期占用

**CPU**：可忽略

**Token 消耗**：
- 输入侧：content 参数由 LLM 生成，大文件写入意味着 LLM 需要生成大量 Token（输出 Token 成本高）
- 输出侧：返回 `{path, bytesWritten}` 极小，几乎不消耗 Token
- 问题：缺少 append 模式，Agent 改一行就得让 LLM 重新生成整个文件内容

**磁盘**：原子写入需要临时文件，短暂占用双倍磁盘空间


#### 9.2.3 file-list（列出目录）

**耗时**：
- 浅层（depth=1）：<10ms
- 深层（depth=3，大目录）：100ms-数秒（取决于文件数量）
- Files.walk() 是惰性流，但 forEach 内部对每个文件调用 Files.size()

**内存**：
- 每个条目构建一个 LinkedHashMap（name + type + size）
- 1000 个文件 ≈ 几百 KB 的 Map 对象
- 问题：无条目数量限制，node_modules 等超大目录可能产生数万条目，内存和 Token 双爆

**CPU**：
- PathMatcher glob 匹配：每个文件一次，O(n)
- Files.size() 系统调用：每个普通文件一次

**Token 消耗**：
- 每个条目序列化为 `{name, type, size}` ≈ 50-100 字符
- 1000 个文件 ≈ 50K-100K 字符 ≈ 15K-30K Token
- 问题：无 maxEntries 限制，大目录直接撑爆上下文
- 这是 file-list 最大的性能隐患


#### 9.2.4 file-search（搜索文件内容）

**耗时**：
- 小目录（<100 文件）：<100ms
- 大目录（>1000 文件）：数秒到数十秒
- 瓶颈：Files.readAllLines() 逐文件读取全部内容到内存

**内存**：
- 每个文件全量读入内存（Files.readAllLines() 返回 List<String>）
- 搜索 1000 个文件，每个 100KB → 峰值 100MB（虽然是逐文件处理，但 GC 压力大）
- 匹配结果 List<Map> 本身很小（maxResults=50）

**CPU**：
- 正则编译：一次性，可忽略
- 正则匹配：每行一次 regex.matcher().find()，复杂正则可能很慢
- 问题：无并行处理，单线程逐文件扫描

**Token 消耗**：
- 每个匹配返回 `{file, line, content}` ≈ 100-200 字符
- maxResults=50 → 最多 5K-10K 字符 ≈ 1.5K-3K Token（可控）
- 这是 4 个文件工具中 Token 消耗控制最好的


### 9.3 管线层面的性能开销

#### 9.3.1 Virtual Thread 调度

每次工具调用都通过 `Executors.newVirtualThreadPerTaskExecutor()` 创建新的 Virtual Thread：
- 优点：不阻塞平台线程，适合 IO 密集型
- 问题：每次调用都创建新的 Executor 实例（`newVirtualThreadPerTaskExecutor()`），虽然 Virtual Thread 创建成本低，但 Executor 对象本身有 GC 开销
- 建议：复用一个全局的 Virtual Thread Executor

#### 9.3.2 JSON 序列化

`ToolBridgeAgentToolProvider` 使用手写的 `toJsonValue()` 递归序列化：
- 优点：无外部依赖
- 问题：对大型嵌套 Map（如 file-list 返回数千条目）性能较差，StringBuilder 频繁扩容
- 输入解析用 Jackson ObjectMapper（每次 new 一个），应复用

#### 9.3.3 Trace 记录 vs LLM 回传不一致

- Trace 记录：outputJson 超过 2000 字符截断（合理）
- LLM 回传：formatOutput() 无任何截断（危险）
- 这意味着 file-read 返回 1MB 内容会全量注入 LLM 上下文，但 Trace 中只记录前 2000 字符，调试时看不到实际回传了多少

### 9.4 Token 消耗是最大的性能瓶颈

综合分析，文件工具的 CPU 和内存开销都在可接受范围内（本地 IO 操作），真正的性能瓶颈是 Token 消耗：

| 工具 | 最坏情况 Token 消耗 | 风险等级 |
|------|-------------------|---------|
| file-read | 250K-500K Token（1MB 文件全量回传） | 🔴 极高 |
| file-list | 15K-30K Token（1000 文件目录） | 🔴 高 |
| file-write | 输出极小，但输入侧 LLM 需生成全量内容 | 🟡 中 |
| file-search | 1.5K-3K Token（50 条结果） | 🟢 低 |


### 9.5 优化建议

#### P0 — Token 消耗控制（最紧急）

1. **file-read 输出截断**：在 formatOutput() 或 Executor 层增加 Token 感知截断
    - 方案 A：在 FileReadToolExecutor 中将 maxReadSize 降低到合理值（如 50KB ≈ 15K Token）
    - 方案 B：在 ToolBridgeAgentToolProvider.formatOutput() 中增加全局输出截断（如 maxOutputChars=20000）
    - 方案 C：增加 startLine/endLine 参数，让 Agent 按需读取

2. **file-list 条目限制**：增加 maxEntries 参数（默认 200），超出时返回截断提示

3. **全局工具输出预算**：在 ToolBridgeAgentToolProvider 中增加输出字符数上限，超出自动截断并附带提示

#### P1 — 内存优化

4. **file-read 截断分支**：用 `Files.newInputStream()` + 按需读取，不预分配 1MB byte[]
5. **file-search**：用 `BufferedReader` 逐行读取替代 `Files.readAllLines()`，避免全文件加载
6. **file-list**：Files.walk() 结果用 limit() 限制条目数

#### P2 — 执行效率

7. **复用 Virtual Thread Executor**：全局单例替代每次 new
8. **复用 Jackson ObjectMapper**：注入共享实例替代每次 new
9. **file-search 并行化**：大目录搜索可用 parallelStream 或 Virtual Thread 并行扫描
10. **ToolResultMeta.tokensUsed 实际填充**：当前始终为 0，应在 formatOutput 后估算实际 Token 数

---


看完你的架构，其实知微已经有了自扩展的基础设施——SkillGapDetector + SkillGenerator + SkillFileWatcher 这条链路。但你说的"帮我写代码，自己扩展出写代码的能力"，这比当前的 Skill 自动生成要更深一层。

让我理清一下你的需求和现有能力之间的差距：

你现有的自扩展链路是：

SkillGapDetector 发现能力缺口
SkillGenerator 用 LLM 生成 SKILL.md（本质是 prompt 指令 + 推荐工具列表）
用户确认后持久化，热加载生效
但 Skill 本质上是"指令注入"——它告诉 Agent 怎么用已有工具去完成任务，并不能创造新的工具能力。如果 Agent 没有"写代码"的底层工具（文件读写 + shell 执行 + 代码理解），光靠 Skill 指令是做不到的。

好消息是，你的 meta 模块已经有了这些底层工具：

文件系统操作（read/write/patch/list/search）
Shell 执行（shell-exec）
代码执行（code-execute via SandboxBooter）
Web 搜索（web-search/web-fetch）
所以"写代码"这个能力，实际上可以通过一个高质量的 Skill 来编排这些已有工具实现。关键问题是：当前的 SkillGenerator 生成的 Skill 质量够不够？

我觉得你真正想要的自扩展架构分三个层次：

层次	能力	现状	缺口
L1: Skill 编排	用已有工具组合出新能力	SkillGenerator 已实现	生成质量需要提升，缺少 Skill 模板库
L2: 工具自造	Agent 自己写 Java 代码创建新 ToolContract	不存在	需要动态工具加载机制
L3: 能力自举	Agent 判断该用 L1 还是 L2，自动选择路径	不存在	需要能力规划器
你想做到哪个层次？我的建议是：

L1 是最实际的，投入产出比最高。把 SkillGenerator 做强（更好的 prompt 模板、Skill 模板库、多轮迭代验证），就能覆盖大部分"自扩展"场景。
L2 很酷但风险高——让 Agent 自己写 Java 工具代码并热加载，安全性和稳定性都是大问题。可以考虑用 Groovy/JavaScript 脚本引擎做轻量级工具扩展，降低风险。
L3 是终极目标，但依赖 L1 和 L2 都成熟之后。
你想先从哪个方向开始规划 spec？还是说你有更具体的场景想法？

明白了。L1 的思路很清晰——两条扩展路径：

Skill 自动生成：用已有工具（文件操作 + shell + 代码执行等）组合出新能力，通过 SkillGenerator 生成高质量 SKILL.md
MCP 服务器自动安装：发现能力缺口时，从 MCP 市场/索引搜索并安装合适的 MCP Server，获得全新的工具能力


1、对于P2 — CRUD 工具工厂抽象，当前项目我之前实现了datastore。这个datastore目标就是为了扩展工具实现统一的存储能力。2、工具分组和按需加载按照你的设计好，还是先向量化再索引好呢。如果真的分组，那我觉得需要整理完所有工具，再做分组。
