# LLM 适配器层重构 — E2E 冒烟检查清单

执行环境：dev 后端（`mvn spring-boot:run`，端口 8080）+ 前端（`cd zhiwei-web && npm run dev`，端口 5173）。

> 关联 plan：[`2026-04-27-llm-adapter-refactor.md`](./2026-04-27-llm-adapter-refactor.md) Phase 10 Task 10.4
>
> 关联自动化测试：
> - `mvn test -Dtest=DeepSeekProviderContractTest` — DeepSeek 协议契约（thinking_mode 三态、同步/流式 reasoning 提取、多轮 history 注入、HTTP 路由打通）
> - `mvn test -Dtest='ChatSessionService_ReasoningContent暴露测试'` — MessageInfo 暴露 reasoning_content 字段，历史会话回看链路畅通

冒烟覆盖**真人对话**链路，自动化测试无法替代。配置过程一并冒烟探测端点 + 推理模型 healthCheck 行为。

---

## 1. DeepSeek V4 系列（核心场景：本次重构的触发用例）

- [ ] 进入「设置 → 模型路由」
- [ ] 新建 model service：profile 选 "DeepSeek 官方"，填 baseUrl（默认 `https://api.deepseek.com`）+ apiKey
- [ ] 点"拉取可用模型"按钮 → 下拉应出现 deepseek-v4-pro / deepseek-chat 等
- [ ] 选 deepseek-v4-pro，勾选"这是推理模型"，thinking_mode 选 auto
- [ ] 保存 → 设为主对话模型
- [ ] 进入对话页，提问"分析下列数学题：从 1 到 100 的所有偶数之和是多少？"
  - [ ] 流式期间：reasoning section 实时展开，文字滚动
  - [ ] 流式结束：reasoning section 自动折叠，content 区显示最终答案
- [ ] **多轮关键 case**：继续提问"再展开第 3 步" → **不应出现 400** 错误（验证 Phase 5 reasoning_content 多轮回传契约）
- [ ] 切换 thinking_mode = disabled 重新提问 → reasoning section 不出现，响应明显加快
- [ ] **历史回看 case**：刷新页面或重启应用进入同一会话 → 之前的 reasoning section 应能展开，文本完整保留（验证 Phase 10 follow-up MessageInfo.reasoningContent 暴露）

## 2. OpenAI o-系列

- [ ] 配 model service：profile "OpenAI 官方"，model 选 gpt-5（或 o3 / o4-mini，按账号可用模型）
- [ ] 勾"这是推理模型"，thinking_mode 选 auto
- [ ] 提问简单问题 → reasoning section 不展示（OpenAI API 不返回 reasoning_content），但 ZhiWei 不应报错
- [ ] thinking_mode 切 enabled 后再次提问 → 后台日志（INFO 级别）应能看到 reasoning_effort=medium 字段下发
  > 注：当前 `ProviderChatOptionsFactory` 还未把 thinking_mode 经 ChatOptions 路径下发到 OpenAI（见 plan Phase 10 placeholder），此项观察仅作冒烟参考；实测若日志没看到 reasoning_effort 字段不算 P0 阻断

## 3. Anthropic Claude Opus 4.7

- [ ] 配 model service：profile "Anthropic 官方"，model 选 claude-opus-4-7 或 claude-sonnet-4-5
- [ ] 勾推理模型，thinking_mode 选 auto
- [ ] 提问需要思考的问题（例如"设计一个 LRU 缓存"）
  - [ ] 流式 reasoning 滚动（Anthropic thinking block 协议）
  - [ ] 多轮无 sign 错误（Anthropic 强制 reasoning_signature 回传，需要 Phase 5 ChatHistoryAssembler.anthropicThinkingBlock 规则）

## 4. Qwen3

- [ ] 配 model service：profile "通义千问 (DashScope)"，model 选 qwen3-max 或 qwen3-plus
- [ ] 勾推理模型，thinking_mode 选 auto
- [ ] 多轮提问 → 不应 400（Qwen3 同样要求 reasoning_content 回传契约）

## 5. 探测端点（拉取可用模型）

- [ ] 配置每家 provider 时点"拉取可用模型"按钮
  - [ ] DeepSeek 官方：成功返回 deepseek-* 模型列表
  - [ ] OpenAI 官方：成功返回 gpt-* / o-* 模型列表
  - [ ] Anthropic 官方：成功返回 claude-* 模型列表（注意 Anthropic 的 /v1/models 端点用 OpenAI 兼容路径）
  - [ ] Qwen DashScope：成功返回 qwen* 模型列表
  - [ ] Ollama 本地：成功返回本地已 pull 的模型列表（走 /api/tags）
- [ ] 故意填错 apiKey → toast 显示 HTTP 401 / 403 等具体错误，不静默吞错
- [ ] 故意填错 baseUrl（如 `http://invalid.local`） → toast 显示连接错误

## 6. 推理模型 healthCheck（不再误判）

- [ ] 进入 `/admin/health` 端点（或对应运维页）
- [ ] 推理模型对应的 model service 状态应为 healthy
  - 此前症状：DeepSeek V4 healthCheck 走 chat ping，推理模型生成时间长 → 23s+ 触发超时被 cancel → healthy=false
  - 修复：Phase 7 healthCheck 优先走 /v1/models 探测端点（毫秒级），非推理模型 fallback 才走 chat ping with max_tokens=10

## 7. 历史会话回看（Phase 10 follow-up 关键场景）

- [ ] 关闭并重启应用（或刷新前端页面）
- [ ] 进入此前已有 reasoning 内容的历史对话
  - [ ] reasoning section 默认折叠
  - [ ] 点击展开 → 显示完整推理过程文本
  - [ ] 字体、空白、换行格式与流式时一致

> 此条对应本次 PR 的 Phase 10 follow-up：MessageInfo 暴露 reasoningContent / reasoningDurationMs 字段。
> 修复前：流式结束后 `chatStore.loadMessages` 重拉历史，后端 MessageInfo 不返 reasoning_content → 前端 `mapBackendMessage` 把 buffer 覆盖为 undefined → 历史会话进入后 reasoning section 完全消失。
> 修复后：MessageInfo 携带 reasoning_content 字段，前端透明衔接，刷新/重启不丢内容。

---

## 已知未实施项（Phase 10 placeholder，留作未来扩展）

下列三条**当前不阻断**冒烟，但实测时若发现行为不对，需要按 follow-up PR 单独跟进：

1. **thinking_mode 经 HTTP body 实际下发** — `ProviderChatOptionsFactory` 当前未读 `ThinkingProtocol.applyToRequest`，DeepSeek extra_body.thinking / Qwen chat_template_kwargs.enable_thinking / Anthropic thinking 字段均未真正注入到出站 JSON。**实测若用户切 thinking_mode=disabled 仍看到 reasoning，则需补这条**（实现：复用 `AbstractJsonBodyRewritingStrategy` 模式给 OpenAiApi 挂 RestClient 拦截器）。
2. **流式实时 LlmResponse.reasoningContent / ReasoningChunk 提取** — `AbstractProviderAdapter.toLlmResponse` 与 `chunkToEvents` 当前都通过 Spring AI `ChatResponse` 间接拿不到原始 `reasoning_content` 字段；前端 reasoning section 仅在 SSE 通道单独 push 时才有内容。**实测若 DeepSeek/Qwen 流式 reasoning 不出现**，需要补 SSE 旁路解析（candidate：自定义 OpenAI HTTP 客户端绕开 Spring AI ChatModel.stream）。
3. **reasoning_content 写入 payload_json 通道** — `ChatTurnService.buildAssistantPayload` 已就位（Phase 5），但 `JdbcTranscriptStore.appendAssistantMessage` / `ReactAgentLoop` 落库路径**当前未读取 LlmResponse.reasoningContent 透传给 buildAssistantPayload**，所以即使 LLM 返回了 reasoning_content，session_transcript_entries.payload_json 里也不会出现该字段。本次 follow-up 解决的是 **read 通道**（payload_json → MessageInfo → 前端），write 通道需独立 PR 接（建议在 ReactAgentLoop 把 LlmResponse 透传给落库点 + 扩展 appendAssistantMessage 接收 reasoningContent 参数）。

参考 `DeepSeekProviderContractTest` 中的 `@Disabled` 测试，已在测试代码层标注精确缺口。

## 验收标准

- [ ] 上述 1~7 节全部 ✅，未发现 P0/P1 阻断
- [ ] 已知未实施项不影响主线（thinking_mode auto + 历史会话回看 + 多轮无 400 三大核心 case 全 ✅）
