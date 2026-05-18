# 渠道文件下发 — 架构设计

> **文档性质**：架构设计文档
> **模块归属**：跨模块（`tool` / `agent` / `interaction.runtime` / `conversation.artifact` / `meta.infra`）
> **最后更新**：2026-05-17
> **关联 spec**（待创建）：`channel-file-delivery`

---

## 1. 模块定位与职责边界

### 1.1 用户表现规约

> **当知微在任何会话里产生了文件类型的产物（不管是 docx / xlsx / pdf / png / zip 等任意扩展名），用户在所属渠道里都应该立即看到这个文件。**

具体到不同渠道的体验下限：

| 渠道 | 体验 | 备注 |
|---|---|---|
| 桌面客户端（Tauri）| 聊天气泡显示文件卡片；点击「打开文件」唤起系统默认应用；点击「显示位置」唤起文件管理器定位 | 利用既有 `tauri-plugin-shell` |
| 本地 / 远程 Web | 聊天气泡显示文件卡片；提供「复制路径」+「下载到浏览器」两个动作 | 浏览器 sandbox 不允许直接打开本地文件 |
| 飞书 / 企微 / 钉钉 | AI 回复文本之后，紧接一条原生「文件消息」/「图片消息」 | 平台按 mimeType 决定预览渲染（PDF/Office/图片自带预览，其他显示为可下载附件） |
| Telegram | 图片可显式声明 `inline=true` 走 sendPhoto；其他默认 sendDocument 保留原文件质量 | grammy / python-telegram-bot 推荐做法 |

预览能力**完全交给各 IM 平台**根据 mimeType 自动决定，知微只保证：①文件能以「文件消息」或「图片消息」的姿态送到，②文件名 + mimeType 正确。

### 1.2 模块职责边界

本模块由四层协作完成：

```
┌────────────────────────────────────────────────────────────────────┐
│  ① 工具执行器层（meta.infra）                                       │
│     file.write / shell.exec / code 等执行器                       │
│     ↓ 返回 ToolResult.artifacts                                   │
├────────────────────────────────────────────────────────────────────┤
│  ② Agent 流程层（agent + conversation.artifact）                   │
│     收集 ToolResult.artifacts → SessionArtifactRepository.save    │
│     ↓ GatewayResponse 携带 artifactRefs                           │
├────────────────────────────────────────────────────────────────────┤
│  ③ 投递分发层（interaction.runtime）                                │
│     ChannelDeliveryDispatcher 按渠道类型路由                       │
│     ↓ 渠道适配                                                     │
├────────────────────────────────────────────────────────────────────┤
│  ④ 渠道适配层                                                       │
│     ├ Web：SSE 事件携带 artifactRef                                │
│     ├ Tauri：复用 Web 链路 + 本地 plugin 唤起文件                  │
│     ├ Connector RPC：飞书/企微/钉钉/Telegram                       │
│     │   └ 调用既有 ChannelRuntimeOperationRequest 的 upload_file  │
│     │     操作上传 media_id，再发 file/image 消息                  │
└────────────────────────────────────────────────────────────────────┘
```

**职责边界**：

- **工具执行器**：只关心「我执行后产生了哪些文件」，不关心后续路由（精确登记）
- **Agent 流程**：把工具产物升级为「会话产物」一等公民（持久化），并在 `GatewayResponse` 上携带引用
- **投递分发层**：按渠道类型选择适配策略，不直接调用 IM SDK
- **渠道适配层**：Web 走 SSE，Tauri 复用 Web，IM 渠道全部走既有 connector RPC（飞书/企微/钉钉 connector 是独立 jar 进程，通过 `ChannelRuntimeOperationRequest` 完成上传 + 发送两步操作）

---

## 2. 核心概念与术语

### 2.1 ToolArtifact

工具产生的文件产物，是「工具执行器层」对外的标准结构。

```java
record ToolArtifact(
    String path,        // 绝对路径（必须在 workspace 白名单内）
    String fileName,    // 不含路径的文件名（含扩展名）
    String mimeType,    // RFC 6838 mimeType；未识别时填 application/octet-stream
    long size,          // 字节数
    ArtifactKind kind,  // FILE / IMAGE，按 mimeType 前缀自动推断
    @Nullable String summary  // 可选简短描述（"基于销售数据生成的月度报告"）
) {}

enum ArtifactKind { FILE, IMAGE }
```

**约束**：
- `path` 必须解析为绝对路径，且必须在 `~/.zhiwei/workspace/` 之下（白名单）
- `mimeType` 通过 `URLConnection.guessContentTypeFromName(fileName)` 自动推断，工具执行器可手动覆盖
- `kind` 自动推断：`mimeType.startsWith("image/")` → `IMAGE`，其他 → `FILE`
- `size > 0` 才登记，0 字节空文件认为是噪声

### 2.2 SessionArtifact（已存在，不改 schema）

会话级产物的持久层。`session_artifacts` 表已在 V1 schema 中存在，本架构**不修改表结构**，只补充写入入口与 payload 约定。

```sql
CREATE TABLE session_artifacts (
    id              TEXT PRIMARY KEY,
    session_id      TEXT NOT NULL,
    source_entry_id TEXT,        -- 关联的 transcript entry（工具调用结果）
    trace_id        TEXT,
    artifact_type   TEXT NOT NULL,  -- 'file' / 'image'
    title           TEXT,
    summary         TEXT,
    payload_json    TEXT NOT NULL,  -- {path, fileName, mimeType, size, kind, ...}
    status          TEXT NOT NULL,  -- ACTIVE / ARCHIVED / DELETED
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);
```

**payload_json 约定（file/image 类型）**：
```json
{
  "kind": "FILE",
  "path": "/Users/x/.zhiwei/workspace/sess-1/月度报告.docx",
  "fileName": "月度报告.docx",
  "mimeType": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  "size": 45678,
  "producedBy": "shell.exec",
  "toolCallId": "tc_xxx"
}
```

### 2.3 ArtifactRef（GatewayResponse 携带）

会话产物在响应链路上的轻量引用，避免在 SSE 事件流和 connector RPC 上塞 base64 字节。

```java
record ArtifactRef(
    String artifactId,    // session_artifacts.id
    String fileName,
    String mimeType,
    ArtifactKind kind,
    long size
) {}
```

`GatewayResponse` 增加字段：

```java
record GatewayResponse(
    ResponseContent content,
    List<GatewayMessage.Attachment> attachments,
    List<ArtifactRef> artifactRefs,   // 新增
    ...
) {}
```

### 2.4 工作目录与白名单

- **会话工作目录**：`~/.zhiwei/workspace/{sessionId}/...`，由 `WorkspaceResolver` 提供
- **下发白名单**：所有可下发的 artifact path 必须在 `~/.zhiwei/workspace/` 之下（不强制 sessionId 隔离，因为 Skill 可能跨会话共享脚本目录）
- **越界产物丢弃**：路径白名单外的 producedFiles 被工具执行器**直接丢弃**（不登记 artifact），日志 WARN

---

## 3. 架构设计方案

### 3.1 阶段一：工具执行器登记产物

#### 3.1.1 file.write 与 code 工具

这两个工具的 Java 执行器**明确知道目标路径**：

```java
// FileWriteToolExecutor.execute(...)
ToolArtifact artifact = ToolArtifact.fromFile(targetPath);  // 读 size + 推断 mime + 推断 kind
return ToolResult.success(data, meta, List.of(artifact));
```

**新增 ToolResult 工厂方法**（非破坏性扩展）：

```java
public static ToolResult success(Map<String, Object> data, ToolResultMeta meta,
                                  List<ToolArtifact> artifacts) {
    return new ToolResult(SUCCESS, data, null, meta, artifacts);
}
```

ToolResult record 增加 `artifacts` 字段，沿用既有 builder 模式（`@Builder(toBuilder = true)`）。既有 `success(...)` 重载默认 `artifacts = List.of()`，向后兼容。

#### 3.1.2 shell.exec 工具：diff workspace 目录

`ShellExecToolExecutor` 默认执行**前后 diff cwd 目录**：

```
执行前：
  Map<String, FileSnapshot> beforeSnapshot = snapshotCwd(cwd)
    // path → (mtime, size)，过滤 .git/node_modules/__pycache__/*.tmp/*.log

执行 shell 命令...

执行后：
  Map<String, FileSnapshot> afterSnapshot = snapshotCwd(cwd)
  List<ToolArtifact> diff = computeDiff(beforeSnapshot, afterSnapshot)
    // 新增 + mtime 变化 = 产物候选
    // 过滤 size = 0 的空文件
    // 产物上限 20 个，超过则只取最近 mtime 的前 20
```

**可选 expectedOutputs 参数**：

```yaml
# Skill 调用 shell.exec 时显式声明
- tool: shell.exec
  input:
    command: "pandoc input.md -o output.docx"
    expectedOutputs: ["output.docx"]  # 显式声明，diff 不再生效
```

显式声明时执行器只检查这些路径是否真实产生，跳过 diff。这是给精确控制的逃生口。

#### 3.1.3 过滤规则统一

```java
class ArtifactFilter {
    static final Set<String> EXCLUDED_DIRS =
        Set.of(".git", "node_modules", "__pycache__", ".venv", "target");
    static final Pattern EXCLUDED_FILE =
        Pattern.compile(".+\\.(tmp|log|swp|swo)$|^\\..*");
    static final long MAX_SIZE = 50 * 1024 * 1024;  // 50MB

    static boolean accept(Path path, long size) {
        if (size == 0 || size > MAX_SIZE) return false;
        if (EXCLUDED_FILE.matcher(path.getFileName().toString()).matches()) return false;
        // 任何路径段命中排除目录都拒绝
        for (Path segment : path) {
            if (EXCLUDED_DIRS.contains(segment.toString())) return false;
        }
        return true;
    }
}
```

### 3.2 阶段二：Agent 流程登记 SessionArtifact

工具执行完成后，Agent 主循环（`ReactAgentLoop` 或同等位置）在调用 `SessionTranscriptRepository.appendToolResult(...)` 的同时：

```java
if (!toolResult.artifacts().isEmpty()) {
    for (ToolArtifact a : toolResult.artifacts()) {
        // 1. 路径白名单校验
        if (!isInWorkspaceRoot(a.path())) {
            log.warn("artifact 路径越界，丢弃: {}", a.path());
            continue;
        }
        // 2. 入 session_artifacts
        String artifactId = sessionArtifactRepository.save(
            sessionId, sourceEntryId, traceId,
            a.kind() == FILE ? "file" : "image",
            a.fileName(), a.summary(),
            buildPayload(a),  // 含 path/mime/size 等
            "ACTIVE"
        );
        // 3. 收集到 GatewayResponse
        artifactRefs.add(new ArtifactRef(artifactId, a.fileName(),
            a.mimeType(), a.kind(), a.size()));
    }
}
```

**位置**：在既有的 `ToolExecutor` → `transcriptRepository.appendToolResult()` 流程后插入，不改动 ToolResult 流转主链路。

### 3.3 阶段三：渠道适配下发

#### 3.3.1 总分派逻辑（ChannelDeliveryDispatcher）

```java
public ChannelRuntimeEventResponse buildEventResponse(ChannelInstance instance,
                                                      ChannelRuntimeEventRequest request,
                                                      GatewayResponse response) {
    DeliveryMode mode = resolveDeliveryMode(request);
    Target target = buildTarget(request);

    // 1. 主消息（文本/markdown）
    ChannelRuntimeDeliveryRequest mainDelivery = buildMainDelivery(...);

    // 2. 每个 artifact 一条独立 file/image delivery
    List<ChannelRuntimeDeliveryRequest> artifactDeliveries =
        response.artifactRefs().stream()
            .map(ref -> buildArtifactDelivery(instance, response.responseId(), ref, target, mode))
            .toList();

    return new ChannelRuntimeEventResponse(true, response.responseId(),
        response.statusCode(), response.errorMessage(),
        Stream.concat(Stream.of(mainDelivery), artifactDeliveries.stream()).toList());
}
```

**关键**：每个 artifact 独立成一条 delivery（responseId 加 `:part{n}` 后缀），避免 connector 把它当成主消息的更新 PATCH。

#### 3.3.2 文件读字节 + 投递

`buildArtifactDelivery(...)` 内部：

```java
SessionArtifactRow artifact = sessionArtifactRepository.findById(ref.artifactId());
Path path = Paths.get(artifact.payloadJson().get("path"));
byte[] data = Files.readAllBytes(path);  // 进 connector RPC body 前 base64

ChannelRuntimeDeliveryRequest.Attachment attachment =
    new Attachment(ref.artifactId(), ref.fileName(), ref.mimeType(),
                   Base64.encode(data), data.length);

// content type 按 kind 区分
String contentType = ref.kind() == IMAGE ? "image" : "file";

return new ChannelRuntimeDeliveryRequest(
    instanceId, responseId + ":part" + index, mode, target,
    new Content(contentType, "[文件: " + ref.fileName() + "]", buildPayload(ref)),
    List.of(attachment),
    metadata
);
```

#### 3.3.3 connector 侧适配（不在主服务范围内）

各家 connector 独立 jar 进程接收 `ChannelRuntimeDeliveryRequest` 后：

| Connector | content.type=`file` 处理 | content.type=`image` 处理 |
|---|---|---|
| **feishu-connector** | 调 `/im/v1/files` 上传换 file_key → 发 `msg_type=file` 消息 | 调 `/im/v1/images` 上传换 image_key → 发 `msg_type=image` 消息 |
| **wecom-connector** | 调 `media/upload?type=file` 换 media_id → 发 `msgtype=file` 消息 | `media/upload?type=image` → `msgtype=image` |
| **dingtalk-connector** | `media/upload?type=file` → `msgtype=file` | `media/upload?type=image` → `msgtype=image` |
| **telegram-connector** | `sendDocument` 一步直发（不需要单独上传） | `sendPhoto`（仅当 metadata.inline=true）/ `sendDocument`（默认） |

**connector 改造由各 connector spec 单独跟进**，本架构只确保主服务侧吐出的 `ChannelRuntimeDeliveryRequest` 协议字段足够 connector 完成上传 + 发送两步操作。

#### 3.3.4 大小限制与降级

| 平台 | 单文件上限（业界值） | 处理 |
|---|---|---|
| 飞书 file | 30MB | 超限 → 不投递 file delivery，主消息文本里追加「文件 xxx (52MB) 超过飞书 30MB 限制，本地路径：...」 |
| 企微 file | 20MB | 同上 |
| 钉钉 media | 30MB | 同上 |
| Telegram document | 50MB | 同上 |

dispatcher 在 `buildArtifactDelivery(...)` 内做大小预检，超限直接 SKIP 该 artifact，把降级提示文本拼到主消息末尾。

### 3.4 阶段四：Web / Tauri 端体验

#### 3.4.1 SSE 事件携带 ArtifactRef

新增一个 SSE 事件类型 `artifact-ref`：

```typescript
// SSE event: artifact-ref
{
  "artifactId": "art_xxx",
  "fileName": "月度报告.docx",
  "mimeType": "application/...",
  "kind": "FILE",
  "size": 45678,
  "downloadUrl": "/api/artifacts/art_xxx/download"
}
```

事件在工具执行结果产生时立即推送（不等 AI 主回复结束），让 Web 端能边流式边渲染产物卡片。

#### 3.4.2 新增 REST 端点 `/api/artifacts/{id}/download`

```java
@RestController
@RequestMapping("/api/artifacts")
@ConditionalOnProperty(name = "lifepilot.gateway.channels.web.enabled", havingValue = "true")
class ArtifactController {

    @GetMapping("/{id}/download")
    ResponseEntity<ByteArrayResource> download(@PathVariable String id) {
        SessionArtifactRow row = artifactRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));

        Map<String, Object> payload = parsePayload(row.payloadJson());
        Path path = Paths.get((String) payload.get("path"));

        if (!isInWorkspaceRoot(path) || !Files.exists(path)) {
            throw new ResponseStatusException(NOT_FOUND);
        }

        byte[] data = Files.readAllBytes(path);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType((String) payload.get("mimeType")))
            .header(CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" +
                URLEncoder.encode((String) payload.get("fileName"), UTF_8))
            .contentLength(data.length)
            .body(new ByteArrayResource(data));
    }
}
```

#### 3.4.3 前端 ArtifactCard 组件

```vue
<!-- ArtifactCard.vue -->
<template>
  <div class="artifact-card" :data-kind="kind.toLowerCase()">
    <component :is="iconFor(mimeType)" />
    <div class="meta">
      <div class="name">{{ fileName }}</div>
      <div class="size">{{ formatSize(size) }}</div>
    </div>
    <div class="actions">
      <button v-if="isImage" @click="preview">预览</button>
      <button v-if="isTauri" @click="openLocal">打开</button>
      <button v-if="isTauri" @click="revealInExplorer">显示位置</button>
      <button v-else @click="copyPath">复制路径</button>
      <button @click="download">下载</button>
    </div>
  </div>
</template>
```

#### 3.4.4 Tauri 端「打开 / 显示位置」实现

利用既有 `tauri-plugin-shell` 的 `open` API：

```typescript
// 打开文件（系统默认应用）
import { open } from '@tauri-apps/plugin-shell'
await open(absolutePath)

// 显示位置（文件管理器定位）
// Windows: explorer.exe /select,
// macOS:   open -R
// Linux:   xdg-open（仅能打开父目录，无定位能力）
import { Command } from '@tauri-apps/plugin-shell'
const platform = await import('@tauri-apps/api/os').then(m => m.platform())
if (platform === 'win32') {
  await Command.create('explorer', ['/select,', absolutePath]).execute()
} else if (platform === 'darwin') {
  await Command.create('open', ['-R', absolutePath]).execute()
} else {
  // Linux 退化：打开父目录
  const parentDir = absolutePath.substring(0, absolutePath.lastIndexOf('/'))
  await open(parentDir)
}
```

权限配置在 `src-tauri/capabilities/default.json` 中开启 `shell:allow-open` 与 `shell:allow-execute`。

---

## 4. 关键设计决策及理由

### 4.1 为什么选「工具执行器登记」而非「文件系统 watcher」

**选择**：工具执行器层主动登记 producedFiles。

**对比**：

| 维度 | 工具执行器登记 | 文件系统 watcher |
|---|---|---|
| 精确性 | ✅ 精确，知道是哪个工具产生 | ❌ 异步，难关联到具体工具调用 |
| 误报率 | ✅ 几乎无 | ❌ 临时文件、缓存、IDE 索引文件都会触发 |
| 跨平台 | ✅ 纯 Java 路径操作 | ❌ Windows/macOS/Linux watcher 行为差异大 |
| 并发会话 | ✅ 天然按 ToolCall 隔离 | ❌ 多会话共享 watcher 难以归属 |
| 代码改动 | ⚠️ 需要触达 3-5 个工具执行器 | ✅ 可中心化新增 |
| 外部脚本/SDK 直接写文件 | ❌ 不覆盖 | ✅ 覆盖 |

**决策依据**：
- LangChain `ToolMessage.artifact`（[langchain.com](https://js.langchain.com/v0.2/docs/how_to/tool_artifacts/)）是业界标准做法，用 `response_format="content_and_artifact"` 让工具显式返回二元组 `(content, artifact)`
- OpenAI Code Interpreter 走容器路径监听是因为容器一次性销毁，不存在「垃圾文件污染」；我们的 workspace 长期复用，watcher 噪声不可控
- 「外部脚本/SDK 直接写文件」覆盖率劣势：实际场景中外部脚本最终也是 `shell.exec` 拉起的，覆盖度足够；纯独立写文件的场景不在 Agent 工具链路中，本来就不该出现在产物列表

### 4.2 为什么 shell.exec 用「diff cwd」而非强制要求 expectedOutputs

**选择**：默认 diff cwd，可选 `expectedOutputs` 显式声明覆盖。

**理由**：
- 强制 `expectedOutputs` 会增加 Skill 编写心智负担，而 LLM 调用 shell.exec 时也很难提前知道精确输出（pandoc 可能根据输入自动决定输出名）
- diff cwd 是 OpenAI Code Interpreter 容器机制的退化版，工程上验证可行
- 噪声问题用过滤规则（excluded dirs / size = 0 / *.tmp 等）兜底，OpenAI 也是类似策略
- 给 `expectedOutputs` 作为逃生口，精确控制场景（比如 doc-processor Skill 自己知道输出文件）可以走显式路径

### 4.3 为什么扩展 SessionArtifact 而非新建 delivery_files 表

**选择**：复用 `session_artifacts`（已存在）。

**理由**：
- `session_artifacts` 已经是「会话产物」一等公民，有 Repository、Context 注入（ContextEngine 把最近产物注入 prompt）、压缩 checkpoint 引用（`extractArtifactRefs`）
- 文件型产物加进去，对其他模块（记忆压缩 / 上下文注入 / Web 端历史会话「会话产物」列表）都有外溢正向收益
- 新建 delivery_files 表会重复造轮子，违背刚做完的 remove-document-workspace 清理精神
- payload_json 是 JSONB-style 字段，加 `path/mime/size/kind` 等不需要 schema 改动

### 4.4 为什么 Web 用独立 SSE 事件而非塞 base64 进现有事件流

**选择**：新增 `artifact-ref` SSE 事件 + 单独的 `/api/artifacts/{id}/download` 端点。

**理由**：
- AI 流式回复事件流（content/reasoning/tool_call_delta）需要保持低延迟，base64 字节会显著拖慢
- artifact-ref 是引用，几十字节，可以和 content chunk 自由交错推送
- 下载走独立端点，复用浏览器原生缓存 / Range 请求 / 断点续传能力

### 4.5 为什么 IM 渠道走 connector RPC 而非主服务直接集成 SDK

**选择**：复用既有 connector RPC 架构（`ChannelRuntimeOperationRequest` 的 `upload_file` 操作）。

**理由**：
- 既有架构已经是 connector 独立 jar 进程，主服务已经不直接调用飞书/企微/钉钉 SDK
- `upload_file` operation 已在 `BuiltinChannelCatalog` 中声明，参数 schema（`fileName / fileData(base64) / fileType`）和飞书/企微/钉钉的 media_upload 接口高度对齐
- 主服务只需吐出 `ChannelRuntimeDeliveryRequest`（带文件 base64 + content type），connector 内部自己完成「上传换 media_id → 发文件消息」两步骤
- 这意味着主服务侧改动是模式化的：dispatcher 增加 artifact 拆分逻辑 + 字节读取，不需要改 connector

### 4.6 为什么术语用 `artifact` 不用 `producedFile` / `output`

**选择**：对齐业界术语。

- LangChain `ToolMessage.artifact`
- Claude Artifacts
- OpenAI `container_file_citation`（虽然字段名不同，但概念都叫 artifact）
- 项目内 `session_artifacts` 表已存在

避免发明新词，降低读者认知成本。

---

## 5. 与已有模块的集成点

| 已有模块 | 集成方式 | 改动范围 |
|---|---|---|
| `tool.model.ToolResult` | 新增 `List<ToolArtifact> artifacts` 字段；既有工厂方法默认 `List.of()` 向后兼容 | 一个 record 加字段 |
| `meta.infra.file.FileWriteToolExecutor` | 写入成功后构造 ToolArtifact 加入 ToolResult | 加 ~10 行 |
| `meta.infra.shell.ShellExecToolExecutor` | 执行前后 diff cwd；可选 `expectedOutputs` 参数 | 新增 `WorkspaceDiffSnapshot` 辅助类 ~80 行 + 主逻辑 ~30 行 |
| `meta.infra.code.*`（如有 code 工具）| 同 file.write | 加 ~10 行 |
| `agent.*`（ToolExecutor / ReactAgentLoop）| 在调 `SessionTranscriptRepository.appendToolResult` 后增加 SessionArtifact 写入 | 加 ~30 行 |
| `conversation.artifact.SessionArtifactRepository` | 新增 `payloadJson.kind` / `payloadJson.path` 等约定（不改 schema） | 0 改动（约定层） |
| `interaction.model.GatewayResponse` | 新增 `List<ArtifactRef> artifactRefs` 字段 | 一个 record 加字段 |
| `interaction.runtime.ChannelDeliveryDispatcher` | 在 `buildEventResponse` 中按 artifactRefs 拆出独立 deliveries；新增 `buildArtifactDelivery(...)` 方法 | 加 ~80 行 |
| `interaction.web.controller`（新建 ArtifactController）| 新增 `/api/artifacts/{id}/download` REST 端点 | 一个 controller ~50 行 |
| `interaction.web.sse.SseEventType` | 新增 `ARTIFACT_REF` 事件类型 | 一行枚举 |
| `agent.context.ContextEngine` | 已有 artifact 注入逻辑，自动覆盖文件型产物（无需改动） | 0 改动 |
| `zhiwei-web/src/components/chat/ArtifactCard.vue` | 新建组件，渲染产物卡片 | 一个 vue 组件 ~120 行 |
| `zhiwei-web/src/composables/useTauri.ts` | 新增 `openFile(path)` / `revealInExplorer(path)` 方法 | 一个 composable ~50 行 |

**connector 侧改动**（不在本主服务 spec 范围内）：
- `feishu-connector`：实现 `upload_file` operation handler + file/image content delivery
- `wecom-connector`、`dingtalk-connector`、`telegram-connector`：同上

---

## 6. 调研参考

### 6.1 Agent 框架的产物机制

- **LangChain `ToolMessage.artifact`** — [langchain.com](https://js.langchain.com/v0.2/docs/how_to/tool_artifacts/) — 工具用 `response_format="content_and_artifact"` 返回 `(content, artifact)` 二元组，content 喂 LLM、artifact 给下游程序
- **OpenAI Code Interpreter container_file_citation** — [fast.io](https://about.fast.io/resources/code-interpreter-file-storage/) / [openai community](https://community.openai.com/t/troubleshooting-file-retrieval-in-openai-container-api/1362334) — Code Interpreter 在容器 `/mnt/data/` 下产生的文件通过 `container_file_citation` 注解携带 `container_id + file_id`，SDK 通过 `/v1/containers/{cid}/files/{fid}` 拉文件
- **Anthropic Claude Artifacts** — `<artifact>` 标签机制偏向 UI 渲染产物，与文件产物场景部分重合

### 6.2 IM 平台文件消息接口

| 平台 | 上传 API | 发送消息 API | 时效 |
|---|---|---|---|
| 飞书 Lark | `/im/v1/files`（文件）/ `/im/v1/images`（图片）| `/im/v1/messages` `msg_type=file` 或 `image` | file_key 长期有效 |
| 企微 | `media/upload?type=file/image/voice/video` | 应用消息 `msgtype=file/image` | media_id 3 天 |
| 钉钉 | `media/upload?type=file/image/voice/video` | 工作通知 `msgtype=file/image/voice` | media_id 30 天 |
| Telegram | 直接 `sendDocument` / `sendPhoto`（无独立 upload）| 一步到位 | file_id 长期有效 |
| Slack | `files.upload_v2`（旧版废弃，新版三步）| 在 chat 消息引用 file_id | 长期 |

参考：
- [grammy 官方 file 指南](https://grammy.dev/guide/files)
- [python-telegram-bot Working with Files and Media](https://github.com/python-telegram-bot/python-telegram-bot/wiki/Working-with-Files-and-Media)
- [Stack Overflow: Telegram sendPhoto vs sendDocument 质量差异](https://stackoverflow.com/questions/55793534/how-to-improve-quality-of-sent-images-with-telegram-bot-api)（推荐默认 sendDocument 保留原图质量）

### 6.3 桌面应用唤起本地文件

- **Tauri `tauri-plugin-shell`** — `shell.open(path)` 调用系统默认应用打开文件，跨 Windows/macOS/Linux
- **VS Code/Cursor 实现参考**：用 `process.platform` 判断 + `child_process.exec("explorer /select," + path)` / `open -R path` / `xdg-open`
- 浏览器 sandbox 不允许直接打开本地路径，Web 端必须降级为「复制路径 + 下载到浏览器」

---

## 7. 不在本设计范围

- 各 connector 内部实现（飞书/企微/钉钉/Telegram connector 的 file upload + send file_message 逻辑）属于各 connector 独立 spec
- 长期 media_id 缓存（避免重发文件时重复上传）属于优化项，本设计不覆盖
- 产物级权限控制（产物允许哪些用户看）属于 RBAC 范围，本设计不覆盖
- 产物 GC（workspace 文件多久过期、何时清理）按既有 workspace lifecycle 走，不引入新逻辑
- 通过协议层让 LLM「看到」自己产生了文件（让 LLM 在后续工具调用时引用 artifact）——这是 LLM context 注入问题，由 `ContextEngine` 已有的 artifact section 覆盖，不在本架构改动
