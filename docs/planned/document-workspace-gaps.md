# 文档工作空间 —— 遗留 Gap 清单

> 本文件列出"文档工作副本编辑"能力链路（前端 → Tool → Service → Engine → Repository → Channel）
> 截至 Phase 3B 收尾后的已知 gap。**P0 / P1 / P2 三档全部要解决**；P3 已确认搁置不在清单内。
>
> - **P0** = 能用性阻塞（影响"跑得起来"）
> - **P1** = 用户可见体验
> - **P2** = 健壮性 / 可观测性
>
> 每项含：问题描述 / 现状证据 / 建议方向 / 预估 / 依赖。

---

## P0 能用性

### P0-1 LLM 感知附件的 prompt 结构化信号未验证
- **问题**：用户发 docx 附件，LLM 收到的上下文里是否有结构化的 `attachmentId=xxx`？如果只是 `[附件] xxx.docx` 纯文本字样，LLM 没法准确构造 `source.type=attachment`。
- **证据**：`SKILL.md` source 表列了 attachment 用法，但依赖 LLM 能从 prompt 里拿到 attachment id；实际 prompt 拼装链路（`ReactAgentLoop` / `PromptBuilder`）没专门为 document 工作流做过契约验证。
- **方向**：写一个 e2e 集成测 —— mock 一条带 attachment 的 user message，断言 LLM 看到的 prompt 含 `attachmentId`；若不含，在 prompt builder 里补结构化注入。
- **预估**：S（~0.5d 调研 + 可能 0.5d 改 prompt 拼装）
- **依赖**：无；是飞书 A+C 的**前置**

### P0-2 SKILL 流程约束可能被 LLM 绕过
- **问题**：`document.edit` 已在 `application.yml` 的 `core-tool-ids` 默认可见，LLM 不触发 `document-workspace` skill 也能调工具 → skill 里的"不要主动 commit / 锚点 10-30 字 / 连续失败停下问用户 / 不要用 document.create 重建"全是软约束，没硬控住。违背 memory `feedback_hard_vs_soft_control`。
- **证据**：`src/main/resources/application.yml` core-tool-ids 含 `document.edit` / `document.create`；`DocumentEditActionDispatchExecutor` 只做熔断（硬）；其余规则只在 skill description 和 tool description 里（软）。
- **方向**：两选一。
  - (a) 从 core-tool-ids 摘掉，只靠 skill 激活 —— 简单但影响用户"无触发词也能改文档"的流畅度
  - (b) 把软约束中值得硬化的移到代码层：例如"commit 只在用户显式请求时允许"可以做成参数校验（要求 toolCall 附带 `userConfirmed=true` 标记）
- **预估**：M（~1-2d 设计 + 实施，要改契约）
- **依赖**：无；是飞书 A+C 的**前置**

### P0-3 commit saveAs 让用户手输绝对路径
- **问题**：`DocumentDiffCard` / `DocumentXlsxDiffCard` 点"另存为"弹 `PromptDialog` 让用户输绝对路径。Web 端有 `showSaveFilePicker`（Chrome），Tauri 有 native `dialog.save`，都没用上。用户输错路径 / 没权限 / 路径不存在等错误要失败后才能发现。
- **证据**：`zhiwei-web/src/composables/useDocumentDiffCard.ts` `onSaveAs` 现在用 `pendingPrompt`。
- **方向**：Web 端优先用 File System Access API（`showSaveFilePicker`），Tauri 走 `@tauri-apps/api/dialog.save()`，降级到当前 PromptDialog。
- **预估**：S（~0.5d）
- **依赖**：无

### P0-4 跨渠道 DiffCard 适配（飞书/钉钉无 Web UI）
- **问题**：飞书侧无 Web DiffCard；diff / 版本历史 / actions（overwrite/saveAs/discard）/ rollback 全要换种方式展示。`sourcePath` 在飞书附件场景不存在 → `commitOverwrite` 必败，必须降级到 `saveAs` 或"回传新文件"。
- **证据**：主服务只有 `ChannelType.FEISHU` 字符串映射，无飞书专属降级逻辑；Web 前端的 DiffCard 无法复用。
- **方向**：
  - 飞书侧用交互卡片（capabilities 已含 `card-update / card-action`）渲染 diff summary + 按钮
  - 增加通用 `CommitPolicy` 概念：无 `sourcePath` 时自动降级为"直接回传文件" / 隐藏 overwrite 按钮
  - 回滚用飞书卡片按钮触发，或命令式 `/rollback v2`
- **预估**：L（~3-5d，跨 connector + 主服务 + 契约设计）
- **依赖**：飞书 A+C 冒烟环境搭好之后；P0-1 / P0-2 做完后开工

---

## P1 用户可见体验

### P1-5 桌面端 Tauri 零文件原生集成
- **问题**：生成 docx 后，用户只能从下载链接下载到浏览器默认目录，不能"在应用内打开" / "在文件管理器中定位"。违背 `feedback_local_first_download` memory。
- **证据**：`zhiwei-web/src-tauri/src/` 无 document 相关 Command。
- **方向**：加 Tauri commands `open_document_path(path)` / `reveal_in_file_manager(path)`；DocumentDiffCard 在 Tauri 环境下额外显示"用系统默认程序打开" / "在资源管理器中显示"按钮。
- **预估**：S（~0.5-1d）
- **依赖**：无

### P1-6 "我编辑过的文档"独立面板缺失
- **问题**：用户关闭浏览器 / 切换 session 后，无法找到之前编辑过的文档；只能靠对话历史里残留的 DiffCard。
- **证据**：`SessionDocumentRepository.findBySessionId` 已有方法但无 REST 端点；前端无列表视图。
- **方向**：新增 `GET /api/documents?sessionId=xxx&status=working` 端点 + 左侧导航里加"文档工作区"面板。`session_documents.latest_version > 0` 视为"working"（有未 commit 的工作副本）。
- **预估**：M（~1-2d，前后端都要动）
- **依赖**：无

### P1-7 任意两版本 diff 对比缺失
- **问题**：`loadData` 固定取 `latestVersion-1 → latestVersion`；`GET /{id}/diff?from=X&to=Y` 的 `from` 参数实际是摆设（注释明说"仅取 to 版本缓存 diff 的简化语义"）。不能选 v0 ↔ v5 对比。
- **证据**：`DocumentController.java:178-194` + `DocumentDiffCard.vue` loadData 逻辑。
- **方向**：
  - 后端：实现真正的两版本 diff 计算（从 `filePath` 重新生成两文件的 diff，而非读缓存）
  - 前端：版本历史列表里可多选"对比 A ↔ B"
- **预估**：M（~2d，diff 生成逻辑 + UI）
- **依赖**：无

### P1-8 docx 没有样式 op / xlsx 不做公式重算
- **问题**：无法"把这段改成标题 2" / "加粗这段" / 改字号 / 改字体；xlsx `update_cell` 写公式不触发全量求值。
- **证据**：`DocxPatchEngine` 只有 `replace_text` / `insert_paragraph_after` / `delete_paragraph` / `add_table_row`，样式随原 run 继承；`XlsxPatchEngine` 未调 `FormulaEvaluator.evaluateAll()`。
- **方向**：
  - docx：加 `set_paragraph_style(target, style_id)` / `set_run_format(target, bold/italic/font/size)` op
  - xlsx：在 patch 结束时调 `FormulaEvaluator.evaluateAll()` 触发重算
- **预估**：M（~2-3d）
- **依赖**：无

### P1-9 xlsx set_range diff 渲染不显示真实内容
- **问题**：前端 `DocumentXlsxDiffCard` 对 `set_range` 只渲染 "rows × cols 批量"，看不到具体值。
- **证据**：`DocumentXlsxDiffCard.vue` v-else-if 分支。
- **方向**：`XlsxDiffBuilder` 让 change 携带 2D values；前端折叠展示（默认收起、点击展开表格）。
- **预估**：S（~1d）
- **依赖**：无

### P1-10 docx 锚点不跨段落
- **问题**：`TextAnchorLocator` 按 paragraph 遍历，如果 `before_context + target + after_context` 跨段落，必然匹配失败。
- **证据**：`src/main/java/com/lifepilot/document/patch/docx/TextAnchorLocator.java`。
- **方向**：可选改为按"document 全文 joined by \n\n"匹配，再把 match 位置反向映射回 paragraph 区间。需要考虑 target 横跨两段的替换语义（一段删除 + 一段插入）。
- **预估**：M（~2d，涉及 locator 算法 + op 语义重整）
- **依赖**：无

---

## P2 健壮性 / 可观测性

### P2-11 applyPatch 不是 @Transactional
- **问题**：写文件 → 保存 version 行 → updateLatestVersion → updateFilePath → updateAttachmentSize 共 5 步，中间任一步失败留孤儿文件或 DB 不一致。
- **证据**：`DocumentVersionService.applyDocxPatch` / `applyXlsxPatch`。
- **方向**：
  - DB 写操作包一个 `@Transactional`
  - 文件写用"写 temp → rename"原子模式；失败时 DB 回滚同时删 temp
- **预估**：M（~1-2d，事务边界 + 测试）
- **依赖**：无

### P2-12 同一 sourcePath 并发 checkout 语义未定义
- **问题**：V14 UNIQUE 约束让第二个 checkout 直接报错；UX 应该是"被占用"提示，还是"共享同一工作副本"？未设计。
- **证据**：`V14__session_documents_source_path_unique.sql` + `DocumentVersionService.checkoutFromPath`。
- **方向**：明确语义：同一 sourcePath 跨 session 共享 document_id（返回已有 document 的 id，而不是报错）；还是仅限本 session 独占（UX 提示"已在 session X 里打开"）。与 P1-6 面板联动设计。
- **预估**：S（~0.5d 设计 + 0.5d 实施）
- **依赖**：P1-6（"已编辑文档"面板）先有，才能展示占用提示

### P2-13 大文档无压测 + 无大小限制
- **问题**：POI 加载 50MB 的 docx/xlsx 会吃大量堆内存，可能 OOM；现在没有限制配置。
- **证据**：`DocumentProperties` 无 max size 字段；`applyPatch` 直接 `new XWPFDocument(in)`。
- **方向**：
  - 配置 `lifepilot.document.max-file-size`（默认 20MB）
  - `checkout` / `applyPatch` 先检查文件大小
  - 加压测用例（jqwik property 测试，跑 10MB+ 文档）
- **预估**：M（~1-2d）
- **依赖**：无

### P2-14 retention / GC 缺失
- **问题**：用户 commit 后忘记 discard → working 副本永久保留；文件丢失但 DB 行还在 → 无 GC。
- **证据**：`DocumentVersionService` 只有 `discard` 主动删除，无后台清理。
- **方向**：
  - `lifepilot.document.working-retention-days`（默认 30 天）+ SharedScheduler 启动定时清理 commit 超过 N 天的 working
  - GC 孤儿扫描：定期对比 `session_documents.file_path` 和文件系统，删除不一致的 DB 行
- **预估**：M（~1-2d）
- **依赖**：无

### P2-15 可观测性 0
- **问题**：patch 成功率 / 延迟 / failedOps 频次 / 熔断次数 / `.bak` 清理次数 / `patchFailCounter` 容量告警 —— 全无 MeterRegistry 接入，只有 log.warn。
- **证据**：grep `meterRegistry` 在 document 模块为 0。
- **方向**：`DocumentVersionService` 和 `DocumentEditActionDispatchExecutor` 注入 `MeterRegistry`，加：
  - `document.patch.count{mime,result=success|failed|circuit_broken}`
  - `document.patch.duration{mime}`
  - `document.commit.count{target=overwrite|saveAs}`
  - `document.bak.prune.count`
  - `document.fail_counter.size`（gauge）
- **预估**：S（~0.5-1d）
- **依赖**：无

---

## 总预估

| 档位 | 项数 | 工作量合计 |
|---|---|---|
| P0 | 4 | S + M + S + L ≈ 5-8d |
| P1 | 6 | S + M + M + M + S + M ≈ 8-11d |
| P2 | 5 | M + S + M + M + S ≈ 5-8d |
| **合计** | **15** | **~18-27d 单人日** |

## 推荐执行顺序

1. **Sprint 1（~1 周）**：P0-1 / P0-2 / P0-3 做完，作为飞书 A+C 的前置
2. **Sprint 2（~1-1.5 周）**：P0-4 + 飞书 A+C 冒烟 & 打磨
3. **Sprint 3（~2 周）**：P1 全部 6 项（顺序无强依赖，可按复杂度升序：5 → 9 → 6 → 7 → 8 → 10）
4. **Sprint 4（~1 周）**：P2 全部 5 项

---

## 变更记录

- 2026-04-22：初版，基于 PR #91 合并前的链路审计
