# 文档工作空间 — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.document`
> **最后更新**：2026-04-22
> **实现状态**：✅ Phase 0 → 3B 已完成

## 1. 功能概述

文档工作空间让 AI 帮用户修改已有的 docx / xlsx 文档，保留完整版本链和回滚能力。每次修改都落到一份只属于本次会话的工作副本上，源文件不动，直到用户明确点「应用到原路径」或「另存为」才落盘；改错了可以直接「丢弃」，或选择回滚到任意历史版本。

核心价值：把「AI 能不能改我这份合同 / 财报 / 项目计划」的能力从「不能」变成「能改且改得有迹可循」。

## 2. 支持格式矩阵

| 格式 | document.create（从零生成） | document.edit（锚点增量编辑） | rollback / commit / discard |
|------|:--:|:--:|:--:|
| `.docx` | ✅ 从 Markdown 生成（支持标题 / 列表 / 段落） | ✅ 4 种 op：replace_text / insert_paragraph_after / delete_paragraph / add_table_row | ✅ |
| `.xlsx` | ✅ 从结构化 sheets 生成 | ✅ 4 种 op：update_cell / insert_row / delete_row / set_range | ✅ |
| `.pptx` | ✅ 从幻灯片大纲生成（标题 + 要点 + 备注） | ❌ 当前版本不支持增量编辑 | ❌ |

需要修改 pptx 时，只能通过 `document.create` 重新从大纲生成一份新文件，不进入版本链管理。

## 3. 核心特性

### 3.1 三种文档来源

`document.edit` 的 `source` 参数决定 AI 对哪份文档做修改：

**`source.type = "path"`** — 用户给本机绝对路径
- 适用场景：用户说「改一下 `D:/project/proposal.docx` 第 2 段」
- 行为：首次调用会把源文件复制到会话私有的工作目录作为 v0 快照，之后所有 patch 都改工作副本
- 安全：扩展名必须是 `.docx` / `.xlsx`，路径走与 `file.read` 同一套白/黑名单校验

**`source.type = "attachment"`** — 用户前端拖拽上传的附件
- 适用场景：用户把一份 xlsx 拖到对话框，说「把第 5 行的数字改成 100」
- 行为：首次调用复制附件文件到工作目录作为 v0，后续 commit 只能走「另存为」（没有原路径可覆盖）
- 限制：附件 mime 或扩展名必须匹配 docx / xlsx

**`source.type = "document"`** — 本会话内的既有文档 ID
- 适用场景 1：AI 刚通过 `document.create` 生成了一份 docx，用户立即说「把标题改掉」
- 适用场景 2：同一会话里对同一份文档继续编辑
- 行为：若该文档首次进入编辑链路（latestVersion=0），把原始字节复制为 v0；否则直接在当前版本基础上继续

### 3.2 版本链与工作副本

- 每次 `patch` 成功生成一个新版本物理文件（`v1.docx` / `v2.xlsx` / …），并落 `document_versions` 表行
- `rollback` 不抹除后续版本，而是把目标版本的内容复制为新的 `v{latest+1}`，版本链只增不删
- `discard` 整体清理：删除会话工作目录 + 删除版本链 + 删除 session_documents 表行 + 按 `<documentId>_` 前缀扫清根目录的原始字节孤儿

### 3.3 LLM 熔断与重规划

- 同一文档连续 patch 失败达到 3 次自动熔断，工具返回 error 让 LLM 停下和用户确认，防止死循环
- patch 失败时返回结构里带 `documentOutline`（docx 段落预览）和明确的 `hint`，LLM 可以对照真实文本重写锚点
- 失败次数会透传 `failureCount` / `maxAllowedFailures`，接近上限时 hint 会提醒停下

### 3.4 前端 DiffCard 体验

AI 每次 patch 成功后，消息气泡上会挂一张 DiffCard：

- **Header**：文件名 + 当前版本号 + 「展开版本历史」开关 + 下载最新版按钮
- **Diff 渲染**：docx 用三色段（keep / delete / insert）逐段展示改动；xlsx 按 change 分组列 cell 地址、before / after 值、以及 row / range 批改预览
- **版本历史**：展开后按版本号升序列出，每项带「下载」和「回滚到此版本」按钮
- **底部三按钮**：
  - 「应用到原路径」：仅 `sourcePath` 非空（即 path 源）时出现，覆盖源文件并生成 `.bak` 备份
  - 「另存为…」：弹出路径输入对话框，保存到用户指定的绝对路径
  - 「丢弃」：二次确认后整体清理工作副本

所有 commit / discard / rollback 操作都由用户在 UI 上点击触发，LLM 不会主动调用。

### 3.5 原路径 commit 的自动备份

点「应用到原路径」时系统会自动做两件事：
1. 先把当前源文件复制为 `<sourcePath>.yyyyMMddHHmmss.bak`
2. 再把工作副本写入 `sourcePath`

同一源文件保留**最近 5 份**自动备份，超出按时间戳最旧的先删。用户自己手工命名的 `.bak`（不符合时间戳命名规律）不会被误删。

## 4. 典型对话示例

### 4.1 改本地 docx

```
用户：帮我把 D:/proposal.docx 里的"2024-Q4"改成"2025-Q1"，并在"风险分析"那段后面加一段补充说明
AI：我看一下文档…[调用 document.edit action=patch, source.type=path, operations=[
       {op:"replace_text", before_context:"预计于 ", target:"2024-Q4", after_context:" 交付"},
       {op:"insert_paragraph_after", anchor_paragraph_text:"风险分析", new_paragraphs:[...]}
     ]]
     已生成 v1 工作副本（共 2 处修改），请在下方卡片里预览；确认无误点"应用到原路径"我就帮你覆盖 D:/proposal.docx（会自动备份原文件为 .bak）。
```

### 4.2 改上传附件 xlsx

```
用户：[拖上来 report.xlsx] 把 Sheet1 的 B5 改成 72.5，再把第 10 行删了
AI：[调用 document.edit action=patch, source.type=attachment, operations=[
       {op:"update_cell", sheet:"Sheet1", cell:"B5", new_value:72.5},
       {op:"delete_row", sheet:"Sheet1", row:10}
     ]]
     已改 Sheet1.B5 和删除第 10 行，卡片里你可以看到具体变化。要保存的话点"另存为"选个位置。
```

### 4.3 回滚到历史版本

```
用户：改错了，回到上一版
AI：[调用 document.edit action=rollback, version=1]
     已回滚到 v1（生成新版本 v3 指向 v1 的内容），历史版本 v2 依然保留可以再切回去。
```

### 4.4 在本会话内连续编辑 AI 生成的产物

```
用户：帮我生成一份项目周报的 docx
AI：[调用 document.create action=docx, ...]
     已生成 weekly-report.docx，请看附件。

用户：把"本周进展"那段改得简短一点
AI：[调用 document.edit action=patch, source.type=document, id=<上一轮 documentId>, operations=[...]]
     已在原有版本基础上更新（v1 → v2），看下卡片。
```

## 5. 使用约束

- **不能跨格式混用 op**：同一次 patch 要么全是 docx op，要么全是 xlsx op；混用会被运行时拦截
- **锚点必须能唯一定位**：docx 的 before/after_context 建议各 10-30 字，xlsx 必须精确 sheet 名 + A1 地址
- **pptx 不走 patch 管线**：修改 pptx 只能 `document.create` 重做整份
- **不要用 `document.create` 覆盖 `document.edit`**：重新生成同名文件会产生新 documentId，丢失原版本链和回滚能力；覆盖已有文档的正确姿势是 `document.edit` + commit overwrite
- **commit / rollback / discard 是用户动作**：LLM 除非收到明确指令（「覆盖原文件」「回滚到 v1」「丢弃这份」），不应主动调用

## 6. 配置项

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.document.enabled` | `true` | 文档工作空间开关；关掉后 `document.create` / `document.edit` 都不装载 |
| `lifepilot.document.storage-dir` | `${user.home}/.zhiwei/documents` | 工作副本 / create 产物根目录 |
| `lifepilot.document.default-max-chars` | `30000` | `document.parse` 读文本时的最大字符数（Phase 0 遗留） |
| `lifepilot.gateway.channels.web.enabled` | `true` | 控制 `DocumentController` REST 端点是否装载 |
| `lifepilot.meta.infra.file.*` | 见 FileToolProvider | 路径安全校验白/黑名单，本模块与 `file.read` / `file.write` 共用 |

## 7. 限制与未来扩展

### 7.1 当前限制

- pptx 只能从零生成，不支持增量编辑
- docx 定位器只做段内匹配，跨段锚点需要 LLM 拆成多 op
- xlsx 暂不支持样式 / 条件格式 / 图表 / 数据透视表的编辑（patch 只改单元格值，保留原 CellStyle）
- docx 生成不支持表格、代码块、内联格式、图片
- `GET /api/documents/{id}/diff` 当前简化语义：只返回 `to` 版本缓存的 diffJson，不支持任意版本对比
- 没有跨会话共享工作副本的概念（工作副本按 sessionId 隔离）

### 7.2 未来扩展方向

- pptx 增量编辑（标题 / bullet / 备注级别锚点）
- docx 表格单元格的精确替换（当前只能整行追加）
- xlsx 样式编辑（字体、颜色、条件格式）
- 多端 diff 可视化（Word 批注式显示、双栏对比）
- 同一文档跨 session 继承工作副本
