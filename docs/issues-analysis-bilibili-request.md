# Bilibili 首页截图与总结请求 — 问题分析报告

基于前端截图与后端日志（`docs/log.md`）的整理，涵盖你已提到的问题及从日志和代码中发现的其它问题。**回复使用简体中文。**

---

## 一、你已指出的问题

### 1. 「上下文组装异常，降级为基础版」

**现象**：日志多次出现  
`ContextAssembler - 上下文组装异常，降级为基础版: ... error=The template string is not valid.`

**根因**：系统提示词使用 **StringTemplate（.st）** 渲染。`planning.st` 第 79–81 行有：

```text
必须且只能以单字符 `{` 作为第一个字符开始，以 `}` 作为最后一个字符结束。
```

在 ST 中，反引号 `` ` `` 表示**表达式**，因此 `` `{` `` 被解析为「名为 `{` 的变量」，后面的中文「作为第一个字符开始，以」等被当成表达式内容，触发 `invalid character '作'/'为'/...` 和 `doesn't look like an expression`，最终导致「The template string is not valid」，进而触发降级。

**结论**：模板里用反引号包裹的 `{`/`}` 与 ST 语法冲突，导致每次渲染 PLANNING 等阶段系统提示词时模板报错，只能走紧急兜底提示词，上下文组装降级为基础版。

---

### 2. 「系统提示词渲染失败，使用紧急兜底提示词」

**现象**：  
`ContextAssembler - 系统提示词渲染失败，使用紧急兜底提示词: phase=PLANNING, error=The template string is not valid.`

**根因**：与上一条是同一链条。ST 解析失败 → 系统提示词渲染失败 → 使用紧急兜底。  
因此**修复 planning.st 中的反引号用法**即可同时缓解「上下文组装异常」和「系统提示词渲染失败」。

---

### 3. 多模态未生效、图片未显示

**现象**：  
- 后端 step 4 已成功执行 `builtin.browser.screenshot`，返回了 Base64 PNG。  
- 前端说明「当前交互环境为纯文本通道，无法直接向你发送图片文件」。  
- 有「下载文件 media.octet-stream 1053.0 KB」，但聊天区域内没有内联展示截图。

**结论**：  
- 多模态未生效主要体现在：**会话通道或前端未把工具返回的图片当作可内联展示的媒体**，只作为附件下载。  
- 需要检查：  
  1. 工具结果（含 Base64 图片）在 SSE/API 中的结构是否标明为 image。  
  2. 前端是否根据类型渲染图片（如 `<img>` 或 A2UI 图片组件），而不是仅提供下载。

---

### 4. 需要授权时前端没有弹出 A2UI

**现象**：  
子 Agent（writer）曾返回「请问是否授权我：1）自动打开 bilibili 首页…」。若 HIGH 风险工具（如 `builtin.browser.navigate` / `builtin.browser.screenshot`）需要用户确认，理论上应弹出授权/确认 UI。

**后端逻辑**：  
- `ToolExecutionPipeline` 在护栏返回 `NeedsConfirmation` 时会调用 `confirmationService.requestConfirmation()`。  
- `WebUserConfirmationService` 会通过 `SseSessionManager.broadcastByPrefix("chat-", TOOL_CONFIRMATION_REQUEST, request)` 推送 **tool-confirmation-request**。  
- 前端需订阅该 SSE 事件并渲染 A2UI 确认组件（或等价 UI），用户确认后调用 `POST /tool-confirmations/{requestId}`。

**可能原因**：  
1. 前端未订阅或未处理 `tool-confirmation-request`，导致不弹框。  
2. Agent 调用路径下护栏未返回 `NeedsConfirmation`（例如策略上对 Agent 放行），因此后端从未发送该事件。  
3. SSE 连接前缀或 session 不匹配，导致收不到广播。

**建议**：  
- 在需要确认时打日志（如「工具确认请求已发送」），确认后端是否发出。  
- 前端确认对 `tool-confirmation-request` 的订阅与 A2UI 渲染逻辑，并确认与当前会话/连接一致。

---

### 5. 专业领域子 Agent 与主 Agent 使用同一套流程导致提示词混杂

**现象**：  
- 第一次 `handoff_to_writer`（step 5）时，主 Agent 已执行 navigate + screenshot，并把「工具结果摘要」注入给 writer。  
- Writer 仍回复「当前无法直接访问或截图 bilibili 网页…请问是否授权我…」，像不知道父级已做完截图。

**原因归纳**：  
1. **子 Agent 的 system prompt / 角色设定** 若与主 Agent 共用同一套「规划 + 执行」流程，容易缺少「当前你是被委托方，父级已提供 context」的明确说明，导致子 Agent 仍按「自己从头执行」来理解任务。  
2. **注入的 context 格式** 是「工具名 + 文本摘要」。若子 Agent 的提示里没有教它「优先使用传入的 context 中的截图/文本摘要完成任务，而不是再请求授权或重复执行」，就容易出现与主 Agent 意图不一致的回复。  
3. **Writer 没有图像理解能力**：即使把截图 Base64 塞进 context，若子 Agent 没有视觉模型或图像输入约定，它也无法「看」图，只能看到文本摘要，行为上会退化为「基于文字」的总结，与「把截图发给我并总结」的预期有差。

**结论**：  
- 子 Agent 与主 Agent 共用流程时，需要在**角色与指令**上区分「主控方」与「被委托方」，并明确「context 即父级已给的结果，请基于此完成 task」。  
- 若希望「截图 + 总结」真正多模态，需要为子 Agent（或主 Agent）提供图像输入与多模态模型，而不是仅传文本摘要。

---

### 6. JSON 解析失败（日志约 1250 行）

**现象**：  
`ActionParser - JSON 解析失败: phase=REFLECTING, error=Unexpected character ('｛' (code 65371 / 0xff5b)): expected a valid value ...`

**原因**：  
- REFLECTING 阶段要求输出 JSON（如 `satisfied` / `adjustmentPlan` / `summary` / `needsReplanning`）。  
- 模型有时输出**全角花括号** `｛`/`｝`（U+FF5B / U+FF5C），而标准 JSON 需要**半角** `{`/`}`，导致首轮解析失败。

**与模板的关系**：  
- `planning.st` / `reflecting.st` 中为「避免模板引擎把花括号当占位符」使用了**全角示例**（如 `｛ "steps": ... ｝`）。  
- 模型可能模仿示例，在**实际输出**中也用了全角，从而触发解析错误。

**当前缓解**：  
- `ActionParser.fixJson()` 中已有全角→半角替换（如 `｛`→`{`），因此会「JSON 修复后解析成功」，但首轮仍会打解析失败日志，且依赖修复逻辑。

**建议**：  
- 在 REFLECTING（及 PLANNING）的**输出说明**中明确写：「请使用半角英文花括号 `{` `}` 输出 JSON，不要使用全角符号。」  
- 可选：在解析前统一做一次全角→半角规范化，减少对模型「学全角」的依赖。

---

## 二、从日志与代码中发现的其它问题

### 7. Handoff 重试时「task 缺失」导致参数校验失败

**现象**：  
- 日志：`参数校验失败: toolId=handoff_to_writer ... task: 缺少必需参数`  
- 同一轮中，主 Agent 把「父级工具结果摘要」自动注入为 `context`，但第二次调用 `handoff_to_writer` 时传参里**没有** `task`。

**原因**：  
- `HandoffToolFactory` 定义的 schema 要求 **`task` 必填**，`context` 选填。  
- `AgentLoop` 在 handoff 时若发现 `context` 为空或空白，会**自动注入** `context`（父级工具结果摘要），但**不会自动补 `task`**。  
- 若 LLM 在重规划时只生成了带 `context` 的步骤（或只写了 `step_N.output` 之类的引用），而没有显式写 `task`，校验就会报「task: 缺少必需参数」。

**建议**：  
- 在 handoff 自动注入逻辑中：当已有 `context` 且来自「上一步执行结果摘要」时，若 `task` 为空，可根据当前计划或上一轮意图自动补一个默认 `task`（例如「请根据上述 context 完成总结」），或在校验前由 AgentLoop 统一补全，避免因漏写 `task` 导致整步失败。

---

### 8. Writer 首次返回的「授权」话术被当成成功结果

**现象**：  
- Step 5 handoff_to_writer 返回：  
  「当前无法直接访问或截图 bilibili 网页…请问是否授权我：1）…2）…3）…」  
- 该输出被记为**成功**（success），主 Agent 据此认为「writer 未处理图像」而切换到 HTML 文本路径。

**问题**：  
- 从用户意图看，用户希望「截图并发给我且总结」；writer 这段回复实质是**澄清/请求授权**，而非「已完成的总结」。  
- 若系统设计上「需要授权时应走 A2UI 确认」，则该输出应触发确认流程，而不是当作普通成功结果继续规划。  
- 当前行为导致：主 Agent 误判 writer 未处理图像 → 改用 web.fetch + 文本总结，最终用户拿到的是「文字总结 + 下载文件」，没有在对话里直接看到截图。

**建议**：  
- 对 handoff 子 Agent 的返回做简单分类：若包含「是否授权」「请确认」等话术，可视为「待用户确认」分支，触发确认 UI 或至少标记为未完成，由主 Agent 或流程决定是否等待用户确认后再继续。

---

### 9. 工具调用次数与展示不一致

**现象**：  
- 前端展示「思考了 1 分 33 秒 6 次工具调用」，但下方列出的工具调用（navigate、screenshot、handoff_to_writer、web.fetch 等）在列表中**出现次数**看起来多于 6 次（例如同一工具多次重试、或子步骤也被列出）。

**可能原因**：  
- 后端统计的是「某一层级的工具调用次数」，而前端展示的是「所有可见步骤」或「含重试/子步骤」的列表，统计口径不一致。  
- 或前端对「步骤」的计数方式（例如只计主步骤、不计重试）与后端不一致。

**建议**：  
- 统一「工具调用次数」的定义（例如：仅主 Agent 直接发起的调用次数，不含子 Agent 内部调用），并在后端返回的 trace/summary 中带出该数字，前端只做展示；或前端与后端约定同一套计数规则。

---

### 10. 全角 JSON 与模板示例的连锁反应

**现象**：  
- 模板里用全角 `｛` `｝` 本意是避免 ST 把 `{` `}` 当占位符。  
- 但 REFLECTING 阶段模型输出也用了全角 → 触发 JSON 解析失败（见第 6 点）。  
- 同时，**PLANNING 阶段**的「必须且只能以单字符 `{` 开始」又用了反引号，触发了 ST 语法错误（见第 1、2 点）。

**结论**：  
- 两处问题同源：**模板写法**既导致渲染失败，又间接鼓励模型输出全角 JSON。  
- 建议：  
  1. 去掉或改写 planning.st 中「以 `{` 开始、以 `}` 结束」的反引号表述（例如改为「以半角左花括号开始」或使用 ST 的转义/非表达式写法）。  
  2. 在 REFLECTING/PLANNING 的说明中明确「输出请使用半角 ASCII 花括号」，并保留 ActionParser 中的全角→半角修复作为兜底。

---

### 11. 截图结果未作为「对话中的媒体」回传

**现象**：  
- 截图已生成且可下载（如 1053 KB media.octet-stream），但对话流中未把该截图作为「本条回复的媒体」展示。

**原因**：  
- 工具结果（含 Base64 或文件 URL）若没有通过「消息内容」或「A2UI」的结构回传给前端，前端只能从「附件/下载」看到，无法内联展示。  
- 多模态不仅依赖模型能理解图像，也依赖**通道和前端**支持在消息中携带并渲染 image 类型。

**建议**：  
- 在 Agent 或工具层，当工具返回截图时，除写入 trace 外，将图片以「消息附件」或 A2UI 图片组件的形式随回复一起下发给前端，前端根据类型渲染为图片或下载链接。

---

## 三、修复优先级建议

| 优先级 | 问题 | 建议动作 |
|--------|------|----------|
| P0 | 系统提示词渲染失败 / 上下文组装降级 | 修改 `planning.st`（及同类 .st）中第 79–81 行，去掉或改写反引号包裹的 `{`/`}`，避免 ST 解析为表达式。 |
| P0 | 全角 JSON 导致解析失败 | 在 REFLECTING/PLANNING 输出说明中明确「仅使用半角 `{` `}`」；保留 fixJson 全角→半角兜底。 |
| P1 | Handoff 缺少 task 导致校验失败 | AgentLoop 在注入 context 时，若缺少 task 则自动补默认 task，或在校验前由调用方补全。 |
| P1 | 子 Agent 与主 Agent 提示词混杂 | 为 handoff 子 Agent 单独设计「被委托方」系统提示，明确基于传入 context 完成 task，不重复请求授权。 |
| P1 | 多模态 / 图片未在对话中显示 | 定义工具返回图片的协议（如 message.attachments 或 A2UI），前端根据类型渲染图片。 |
| P2 | 需要授权时前端未弹 A2UI | 确认护栏在 Agent 路径会返回 NeedsConfirmation；前端订阅并处理 tool-confirmation-request，渲染确认 UI。 |
| P2 | 工具调用次数展示不一致 | 统一后端与前端对「工具调用次数」的统计口径并在接口中返回。 |
| P2 | Writer 的「授权」回复被当成功 | 对子 Agent 返回做简单意图分类，将「请求授权」类回复走确认流程或标记未完成。 |

---

## 四、小结

- **上下文组装异常**和**系统提示词渲染失败**来自同一根因：**planning.st 中反引号与 ST 语法冲突**，修模板即可同时缓解。  
- **多模态未生效**主要体现在：截图未作为对话内媒体下发给前端，需要协议与前端渲染支持。  
- **授权 A2UI 未弹出**需要同时确认：护栏是否在 Agent 路径要求确认、SSE 是否发送、前端是否订阅并渲染。  
- **子 Agent 与主 Agent 提示词混杂**需要通过「被委托方」专用提示和 context 使用说明来理顺。  
- **JSON 解析失败**与** handoff task 缺失**可通过「明确半角输出 + 自动补 task/context」和现有 fixJson 一起解决。  

按上表 P0 → P1 → P2 推进，能系统性缓解本次请求中暴露的各类问题。
