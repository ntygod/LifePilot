# 渠道文件下发 — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：跨模块（`tool` / `agent` / `interaction.runtime` / `conversation.artifact`）
> **最后更新**：2026-05-17
> **关联架构**：#[[file:docs/architecture/channel-file-delivery.md]]

---

## 1. 功能概述

知微在任何会话中产生文件类型的产物（docx / xlsx / pdf / png / zip 等任意扩展名），用户在所属渠道里都能立即看到这个文件，并能预览或下载。

具体到不同渠道的体验：

| 场景 | 用户能看到 | 用户能做 |
|---|---|---|
| 桌面客户端（Tauri） | 聊天气泡里的文件卡片：图标 + 文件名 + 大小 | 点击「打开文件」唤起系统默认应用；点击「显示位置」唤起文件管理器定位；点击「下载」复制到浏览器下载目录 |
| 本地 Web / 远程 Web | 同上文件卡片 | 「复制路径」（本机用户用）、「下载到浏览器」（远程用户用） |
| 飞书 | AI 回复文本之后紧接一条原生文件消息 / 图片消息 | 飞书自动按 mimeType 渲染：PDF / Office / 图片自带预览，其他显示为可下载附件 |
| 企微 / 钉钉 | 同飞书 | 各平台原生预览能力，按 mimeType 自适应 |
| Telegram | 文件消息（sendDocument）；图片可显式声明 inline 走 sendPhoto | Telegram 客户端自带预览（PDF、图片、视频）|

预览能力**完全交给各平台自己**根据 mimeType / 扩展名决定，知微的职责是保证文件以「文件消息」或「图片消息」的姿态送到，并且文件名 + mimeType 正确。

---

## 2. 用户价值

### 2.1 端到端闭环

在没有此特性之前，知微在飞书等渠道里生成文件后，用户只看到一段含本地路径的文本（如 `已生成报告：C:\Users\xxx\.zhiwei\workspace\sess-1\report.docx`），文件实际上躺在主服务所在机器的本地磁盘，**用户在飞书侧拿不到任何能下载的链接**。这导致渠道场景下「让 AI 帮我做个文档」这条核心使用路径直接断掉。

此特性让任何渠道下产生的文件产物自动以原生文件消息姿态送达，端到端闭环。

### 2.2 跨渠道一致性

无论用户在桌面客户端、Web 还是飞书企微钉钉聊天，看到的体验是**统一的「文件卡片 + 平台原生预览」**，不因渠道差异而退化。

### 2.3 产物可追溯

文件产物作为 `session_artifact` 持久化，未来：
- Web 端的「会话产物」列表可以集中查看会话历史中所有产物
- ContextEngine 已有的 artifact 注入逻辑会让后续轮次 LLM「看到」自己之前产生过哪些文件，避免重复劳动
- 记忆压缩 checkpoint 自动引用产物 ID

---

## 3. 核心特性列表

### 3.1 工具产物自动登记

凡是 `file.write` / `code` / `shell.exec` 等工具执行后产生的文件，自动登记为会话产物，无需 Skill 显式调用「保存为产物」之类的辅助操作。

| 工具 | 探测方式 |
|---|---|
| `file.write` | 写入成功后直接登记目标路径 |
| `code` | 同 file.write，登记 Python/JS 脚本写出的文件路径 |
| `shell.exec` | 默认 diff 当前工作目录（cwd）执行前后的文件列表，新增 + 修改的文件登记为产物；可选参数 `expectedOutputs` 显式声明产物清单（绕过 diff）|

**过滤规则**（自动排除噪声）：
- 大小为 0 的空文件
- 单文件超过 50MB（投递成本过高）
- `.tmp` / `.log` / `.swp` / 隐藏文件（`.` 开头）
- `.git` / `node_modules` / `__pycache__` / `.venv` / `target` 目录内的文件
- 单次工具调用产物数超过 20 个，按 mtime 取最新 20 个

### 3.2 路径白名单

所有产物路径必须在 `~/.zhiwei/workspace/` 之下；越界路径在工具执行器层直接丢弃，不进入产物列表。这防止：
- prompt injection 让 LLM 写文件到 `/etc/passwd` 然后让 dispatcher 读出来下发
- 误把 `/tmp/cache.bin` 这种系统临时文件当成产物

### 3.3 渠道差异化适配

#### 3.3.1 桌面客户端（Tauri）

利用既有 `tauri-plugin-shell` 实现「打开文件」「显示位置」两个原生动作：
- Windows：`explorer.exe /select, <path>` 唤起资源管理器并定位文件
- macOS：`open -R <path>` 唤起 Finder 并定位
- Linux：退化为打开父目录（`xdg-open` 没有定位能力）

#### 3.3.2 Web 端

浏览器 sandbox 不允许直接访问本地文件路径，提供两个降级动作：
- 「复制路径」：把绝对路径放入剪贴板，本机用户可直接粘到资源管理器地址栏
- 「下载到浏览器」：调 `/api/artifacts/{id}/download` 把字节流推回前端

#### 3.3.3 飞书 / 企微 / 钉钉

复用既有 connector RPC 架构：dispatcher 把每个产物拆成独立的 `ChannelRuntimeDeliveryRequest`（content.type = `file` 或 `image`），各家 connector 内部完成「上传换 media_id → 发文件消息」两步操作。各平台原生 file/image 消息支持 PDF / Office / 图片预览。

#### 3.3.4 Telegram

通过 metadata 携带 `inline=true` 标记可让图片走 `sendPhoto`（自带客户端预览，但有压缩降质）；默认走 `sendDocument` 保留原文件质量（业界推荐做法，参考 grammy / python-telegram-bot 文档）。

### 3.4 大小超限优雅降级

各 IM 平台对单文件大小有上限（飞书 30MB、企微 20MB、钉钉 30MB、Telegram 50MB）。超限时：
- 不投递文件消息
- 主消息文本末尾追加降级提示：`文件 xxx (52MB) 超过 [平台] 30MB 限制，本地路径：[绝对路径]`
- 用户在桌面端 / Web 端依然能通过本地路径或下载端点拿到文件

### 3.5 SSE 流式推送 ArtifactRef

Web 端在 AI 回复流式渲染过程中，工具一旦产生文件就立即推送 `artifact-ref` SSE 事件，前端立即渲染产物卡片，不需要等 AI 主回复结束。

### 3.6 文件产物作为会话产物一等公民

文件产物登记到 `session_artifacts` 表（artifactType=file/image），自动获得：
- ContextEngine 在 prompt 中注入「最近产物」section，让后续轮次 LLM 知道自己产生过哪些文件
- 记忆压缩 checkpoint 自动收集为 `ArtifactRef`，跨压缩边界可追溯
- 未来 Web 端「会话产物」列表的数据源

---

## 4. 使用场景与示例

### 4.1 场景：飞书群里让 AI 生成月度报告

**用户操作**：
> @AI 帮我把 sales.csv 转成月度销售报告 docx

**AI 行为**：
1. 调 `doc-processor` Skill
2. Skill 内部调 `code` 工具跑 Python 脚本：`python-docx` 生成 `monthly_report.docx`
3. `code` 工具执行器登记产物 → `ToolResult.artifacts: [ToolArtifact(path=.../monthly_report.docx, kind=FILE, mime=.../wordprocessingml.document, size=45KB)]`
4. Agent 流程把 artifact 写入 `session_artifacts` → 出现 `ArtifactRef` 在 `GatewayResponse.artifactRefs`
5. AI 主消息：「已生成月度销售报告，包含同比环比分析、Top 10 客户排行、滞销品预警三部分。」
6. 紧接一条飞书 file 消息：`monthly_report.docx`（45KB）
7. 用户在飞书里直接看到主文本 + docx 文件附件，点击 docx 飞书自动出 Office 预览

### 4.2 场景：桌面客户端生成图表

**用户操作**：
> 帮我画个最近 30 天的访客趋势图

**AI 行为**：
1. `code` 工具跑 matplotlib 生成 `traffic_trend.png`
2. 产物登记，kind 推断为 IMAGE（mime 以 `image/` 开头）
3. SSE 推送 `artifact-ref` 事件
4. Tauri 桌面客户端聊天气泡渲染图片卡片：缩略图 + 文件名 + 大小 + 操作按钮
5. 用户点击「打开文件」→ Windows 默认图片查看器打开
6. 或点击「显示位置」→ 资源管理器定位到 `~\.zhiwei\workspace\sess-2\traffic_trend.png`

### 4.3 场景：批量产物（多文件同时产生）

**用户操作**：
> 把这个 PDF 拆成单页 PNG

**AI 行为**：
1. `shell.exec` 跑 `pdftoppm input.pdf page -png -r 150` 产出 `page-1.png ~ page-12.png` 共 12 张
2. shell.exec 执行器 diff cwd 发现 12 个新增文件，全部登记
3. 12 个 artifactRef 跟随单条 GatewayResponse 一并推送
4. dispatcher 在飞书侧拆成 1 条主消息 + 12 条 image 消息按顺序投递（每条独立 responseId 后缀 `:part1`-`:part12`）
5. 用户在飞书里看到 12 张顺序排列的图片消息

### 4.4 场景：超大文件降级

**用户操作**：
> 帮我把这个长视频转码

**AI 行为**：
1. `shell.exec` 跑 `ffmpeg ...` 产出 `output.mp4` (250MB)
2. 产物登记成功（路径白名单内、大小 < 50MB 上限……等等，250MB 超过 50MB 上限，**直接被工具执行器过滤丢弃**）

**实际行为**：
- 单文件超过 50MB 在「工具执行器登记」环节就被丢弃，不进入产物列表
- AI 主消息保持原样，LLM 自己在文本里给出本地路径供桌面端用户使用
- 飞书等 IM 渠道用户得不到文件，只看到文本路径（飞书侧无 50MB 文件可发）
- 这是产品决策：超大文件不应通过 IM 渠道下发，避免 connector 长时间阻塞

如果用户确实需要把超大文件发到 IM 渠道，得通过云存储（OSS / 飞书云文档）链接形式，**不在本特性范围内**。

---

## 5. 配置项说明

| 配置键 | 默认值 | 说明 |
|---|---|---|
| `lifepilot.gateway.delivery.artifact-max-size-mb` | `50` | 单产物文件大小上限（超过则在工具执行器层过滤丢弃）|
| `lifepilot.gateway.delivery.artifact-max-count-per-tool` | `20` | 单次工具调用最多登记产物数（超过按 mtime 取最新 N 个）|
| `lifepilot.gateway.delivery.artifact-excluded-dirs` | `.git,node_modules,__pycache__,.venv,target` | shell.exec diff 时排除的目录名（逗号分隔，命中任何路径段都排除）|
| `lifepilot.gateway.delivery.artifact-excluded-extensions` | `tmp,log,swp,swo` | 排除的文件扩展名（不含点）|
| `lifepilot.gateway.delivery.platform-max-size.feishu` | `30` | 飞书单文件大小上限 MB（超限触发降级提示）|
| `lifepilot.gateway.delivery.platform-max-size.wecom` | `20` | 企微 MB |
| `lifepilot.gateway.delivery.platform-max-size.dingtalk` | `30` | 钉钉 MB |
| `lifepilot.gateway.delivery.platform-max-size.telegram` | `50` | Telegram MB |

**说明**：渠道侧 max-size 仅用于「超限降级提示」决策，不影响产物登记本身（artifact 已经登记成功，只是不投递这一渠道）。

---

## 6. Agent 行为原则

### 6.1 无需 Skill 显式声明产物

`file.write` / `code` 写文件 → 自动登记。`shell.exec` 默认 diff cwd → 自动登记。Skill 不需要在每次工具调用后说「请记下这个文件」之类的辅助步骤。

### 6.2 expectedOutputs 仅用于精确控制

只在 Skill 自己确切知道工具会产出哪些文件（且 diff 不准确）时才使用 `expectedOutputs`。常规场景信任默认 diff 行为。

### 6.3 路径必须在 workspace 之下

Skill 必须把工具的 cwd / output path 设置在 `~/.zhiwei/workspace/{sessionId}/...` 之下；越界路径会被丢弃，且不会有错误反馈给 LLM（避免 LLM 重试浪费 token）。`workspaceResolver` 已有的目录约定继续使用。

### 6.4 大文件不应通过 IM 渠道下发

如果产物预计 > 50MB，Skill 应主动给出云存储链接或分片处理建议，不要依赖 IM 文件下发。

---

## 7. 限制与未来扩展方向

### 7.1 当前限制

- 不覆盖外部脚本（不通过 `shell.exec` / `code` 工具）独立写入的文件
- 不做 media_id 长期缓存：同一文件多次发到飞书会重复上传（飞书 file_key 长期有效但我们不缓存）
- Linux 桌面端「显示位置」退化为打开父目录（无定位能力）
- 单文件 50MB 硬上限，超大文件需要其他链路

### 7.2 未来扩展

- **云存储集成**：超大文件自动上传 OSS / 飞书云文档，IM 渠道发链接；Skill 透明调用
- **media_id 缓存**：飞书 file_key 长期有效，可建短期缓存表避免同会话重发文件时重复上传
- **产物 GC 策略**：workspace 文件按会话生命周期 / 用户配额自动清理
- **产物级权限**：跨用户共享会话时的 artifact 可见性控制
- **产物批量打包**：> 5 个产物时自动 zip 打包发送（替代逐条 file 消息）
- **预览生成**：对不支持预览的文件类型（如 zip）生成内容摘要预览图

### 7.3 不在本特性范围

- 桌面客户端的「文件管理器集成」高级能力（如显示文件历史、版本对比）
- 用户上传文件到知微（入站方向，已由 channel attachment 链路覆盖）
- LLM 协议层「看到」自己产生过哪些文件（已由 `ContextEngine` artifact section 注入覆盖）
- IM 平台的群聊广播 / @ 提及等消息修饰（属于 channel adapter 层职责）
