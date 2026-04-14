# A2UI 渲染体系改造实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 AI 输出渲染质量提升到 Claude Desktop / ChatGPT 水准 — markdown 渲染做到极致，交互式组件改走 tool call 机制。

**Architecture:** 两条并行主线：(1) 前端 markdown 渲染增强，让 LLM 的 markdown 输出开箱即用地美观；(2) A2UI 从文本标签 `<a2ui>` 迁移到 tool call `ui.emit`，利用结构化函数调用的高合规性替代不可靠的文本标记。两条线独立推进，互不阻塞。

**Tech Stack:** marked 17.x + marked-gfm-heading-id + highlight.js 11.x（前端）；BuiltinTool + ToolExecutionPipeline + SseSessionManager（后端）

---

## 现状分析

### 问题根因

1. **LLM 合规性低** — A2UI 指令通过 Skill 两阶段披露传递，要求 LLM 在文本中插入 `<a2ui>...</a2ui>` 标签。即使 Skill 已加载，LLM 仍经常输出裸 JSON 而非标签包裹格式，导致 `StreamingA2uiParser` 无法提取组件，前端展示为原始 JSON 文本。
2. **Markdown 渲染基础** — 前端使用 `marked@17.0.5` + `marked-highlight@2.2.3`，未启用 GFM 扩展（删除线、任务列表）、无数学公式渲染、无图表支持。CSS 排版已有基础但部分细节可提升。

### 行业对标

| 能力 | Claude Desktop | ChatGPT | 知微现状 |
|------|---------------|---------|----------|
| Markdown 渲染 | 优秀（GFM 全量） | 优秀（GFM + LaTeX） | 基础（仅 highlight） |
| 结构化组件 | Artifacts（tool call） | Canvas（tool call） | `<a2ui>` 文本标签 |
| 代码块 | 行号 + 折叠 + 复制 | 复制 + 运行 | 复制 |
| 数学公式 | KaTeX | KaTeX | 无 |

### 改造策略

- **大部分场景**：LLM 输出 markdown → 前端渲染做到极致（占 90%+ 场景）
- **交互场景**：A2UI 改走 tool call（按钮、表单、signal 等需要前端回调的场景）
- **兼容过渡**：保留 `<a2ui>` 标签解析作为降级路径，后续逐步移除

---

## 文件结构

### 前端变更

| 文件 | 操作 | 职责 |
|------|------|------|
| `zhiwei-web/package.json` | 修改 | 添加 `marked-alert`、`katex`、`marked-katex-extension` 依赖 |
| `zhiwei-web/src/components/chat/StreamingText.vue` | 修改 | 注册 GFM 扩展、KaTeX 扩展、优化 CSS |
| `zhiwei-web/src/lib/highlight.ts` | 修改 | 无需改动（已覆盖主流语言） |

### 后端变更

| 文件 | 操作 | 职责 |
|------|------|------|
| `src/main/java/com/lifepilot/interaction/web/a2ui/UiEmitToolProvider.java` | 新建 | `ui.emit` 工具提供者 — 构建 BuiltinTool 实例 |
| `src/main/java/com/lifepilot/interaction/web/a2ui/UiEmitToolExecutor.java` | 新建 | `ui.emit` 执行逻辑 — 校验组件树 + 发送 SSE UI 事件 |
| `src/main/java/com/lifepilot/interaction/web/config/A2uiAutoConfiguration.java` | 修改 | 注册 `ui.emit` 工具 Bean |
| `src/main/resources/skills/a2ui/SKILL.md` | 修改 | 更新指南：markdown-first + tool call 方式 |

### 测试文件

| 文件 | 操作 | 职责 |
|------|------|------|
| `src/test/java/com/lifepilot/interaction/web/a2ui/UiEmitToolExecutor_单元测试.java` | 新建 | ui.emit 执行器单元测试 |

---

## 主线一：前端 Markdown 渲染增强

### Task 1: 启用 marked GFM 扩展 + KaTeX 数学公式

**Files:**
- Modify: `zhiwei-web/package.json`
- Modify: `zhiwei-web/src/components/chat/StreamingText.vue`

- [ ] **Step 1: 安装依赖**

```bash
cd zhiwei-web
npm install katex marked-katex-extension marked-alert
```

说明：
- `katex` — 数学公式渲染引擎
- `marked-katex-extension` — marked 的 KaTeX 插件，支持 `$...$` 行内和 `$$...$$` 块级公式
- `marked-alert` — GitHub 风格告警块（`> [!NOTE]`、`> [!WARNING]` 等）

- [ ] **Step 2: 在 StreamingText.vue 中注册扩展**

读取 `zhiwei-web/src/components/chat/StreamingText.vue`，在 `markedInstance` 初始化处添加扩展注册。

当前代码（约第 14-22 行）：
```typescript
const markedInstance = new Marked(
  markedHighlight({
    langPrefix: 'hljs language-',
    highlight(code: string, lang: string) {
      return highlightCode(code, lang)
    }
  })
)
```

改为：
```typescript
import markedKatex from 'marked-katex-extension'
import markedAlert from 'marked-alert'

const markedInstance = new Marked(
  markedHighlight({
    langPrefix: 'hljs language-',
    highlight(code: string, lang: string) {
      return highlightCode(code, lang)
    }
  }),
  markedKatex({ throwOnError: false }),
  markedAlert()
)
```

- [ ] **Step 3: 添加 KaTeX CSS 引用**

在 `StreamingText.vue` 的 `<style>` 块或全局样式中引入 KaTeX CSS：

```typescript
import 'katex/dist/katex.min.css'
```

- [ ] **Step 4: 验证 GFM 功能**

启动前端 dev server，在聊天中测试：

```bash
cd zhiwei-web && npm run dev
```

验证以下 markdown 语法渲染正确：
- 表格：`| col1 | col2 |`（marked 17.x 默认支持 GFM 表格）
- 删除线：`~~text~~`（marked 17.x 默认支持）
- 任务列表：`- [ ] todo`（marked 17.x 默认支持）
- 数学公式：`$E=mc^2$` 和 `$$\int_0^1 x^2 dx$$`
- 告警块：`> [!NOTE]\n> 内容`

Expected: 所有语法正确渲染为对应 HTML 元素。

- [ ] **Step 5: 提交**

```bash
git add zhiwei-web/package.json zhiwei-web/package-lock.json zhiwei-web/src/components/chat/StreamingText.vue
git commit -m "feat(web): 启用 KaTeX 数学公式 + GitHub 告警块渲染"
```

---

### Task 2: 优化 Markdown CSS 排版细节

**Files:**
- Modify: `zhiwei-web/src/components/chat/StreamingText.vue`（`<style>` 部分）

- [ ] **Step 1: 优化任务列表样式**

在 `StreamingText.vue` 的 `<style>` 块中添加任务列表（checkbox）样式。找到现有的 `ul, ol` 样式区域（约 211-228 行），在其后添加：

```css
.streaming-prose :deep(ul.contains-task-list),
.streaming-prose :deep(.task-list-item) {
  list-style: none;
  padding-left: 0;
}

.streaming-prose :deep(.task-list-item input[type="checkbox"]) {
  margin-right: 0.5rem;
  accent-color: hsl(from var(--primary) h s l / 0.82);
}
```

- [ ] **Step 2: 优化告警块样式**

在 `<style>` 块中添加 `marked-alert` 渲染出的告警块样式：

```css
.streaming-prose :deep(.markdown-alert) {
  padding: 0.8rem 1rem;
  margin: 0.65rem 0;
  border-left: 3px solid hsl(from var(--primary) h s l / 0.42);
  border-radius: 0 0.65rem 0.65rem 0;
  background: hsl(from var(--accent) h s l / 0.32);
}

.streaming-prose :deep(.markdown-alert-title) {
  font-weight: 600;
  margin-bottom: 0.35rem;
  display: flex;
  align-items: center;
  gap: 0.4rem;
}

.streaming-prose :deep(.markdown-alert-note) {
  border-left-color: hsl(210 80% 60% / 0.6);
}

.streaming-prose :deep(.markdown-alert-warning) {
  border-left-color: hsl(38 92% 55% / 0.6);
}

.streaming-prose :deep(.markdown-alert-caution) {
  border-left-color: hsl(0 72% 55% / 0.6);
}

.streaming-prose :deep(.markdown-alert-tip) {
  border-left-color: hsl(from var(--primary) h s l / 0.6);
}

.streaming-prose :deep(.markdown-alert-important) {
  border-left-color: hsl(280 60% 60% / 0.6);
}
```

- [ ] **Step 3: 优化 KaTeX 公式样式**

```css
.streaming-prose :deep(.katex-display) {
  margin: 0.8rem 0;
  overflow-x: auto;
  overflow-y: hidden;
  padding: 0.4rem 0;
}

.streaming-prose :deep(.katex) {
  font-size: 1.05em;
}
```

- [ ] **Step 4: 视觉验证**

在聊天中发送一条包含多种 markdown 元素的消息，确认排版美观、间距合理。关注：
- 告警块的边框颜色和背景是否与整体主题协调
- KaTeX 公式在暗色模式下是否可读
- 任务列表 checkbox 对齐

- [ ] **Step 5: 提交**

```bash
git add zhiwei-web/src/components/chat/StreamingText.vue
git commit -m "style(web): 优化任务列表、告警块、KaTeX 公式排版样式"
```

---

## 主线二：A2UI 迁移到 Tool Call

### Task 3: 创建 ui.emit 工具执行器

**Files:**
- Create: `src/main/java/com/lifepilot/interaction/web/a2ui/UiEmitToolExecutor.java`
- Test: `src/test/java/com/lifepilot/interaction/web/a2ui/UiEmitToolExecutor_单元测试.java`

- [ ] **Step 1: 编写 UiEmitToolExecutor 的失败测试**

```java
package com.lifepilot.interaction.web.a2ui;

import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.model.ToolResultStatus;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UiEmitToolExecutor_单元测试 {

    @Mock SseSessionManager sseManager;

    @Test
    void 合法组件树成功发送SSE事件() {
        var executor = new UiEmitToolExecutor(sseManager, 50);
        var components = List.of(
                Map.<String, Object>of(
                        "id", "text-1",
                        "type", "Text",
                        "properties", Map.of("text", "你好"),
                        "children", List.of()
                )
        );
        var input = new ToolInput(
                "ui.emit",
                Map.of("components", components),
                JsonSchema.empty(),
                null,
                Map.of("streamId", "stream-1", "sessionId", "sess-1", "turnId", "turn-1")
        );

        ToolResult result = executor.execute(input);

        assertThat(result.status()).isEqualTo(ToolResultStatus.SUCCESS);
        verify(sseManager).sendEvent(eq("stream-1"), any(), any());
    }

    @Test
    void 缺少streamId时返回错误() {
        var executor = new UiEmitToolExecutor(sseManager, 50);
        var input = new ToolInput(
                "ui.emit",
                Map.of("components", List.of()),
                JsonSchema.empty(),
                null,
                Map.of()
        );

        ToolResult result = executor.execute(input);

        assertThat(result.status()).isEqualTo(ToolResultStatus.ERROR);
    }

    @Test
    void 组件类型不在注册列表时返回错误() {
        var executor = new UiEmitToolExecutor(sseManager, 50);
        var components = List.of(
                Map.<String, Object>of(
                        "id", "x-1",
                        "type", "UnknownWidget",
                        "properties", Map.of(),
                        "children", List.of()
                )
        );
        var input = new ToolInput(
                "ui.emit",
                Map.of("components", components),
                JsonSchema.empty(),
                null,
                Map.of("streamId", "stream-1", "sessionId", "sess-1", "turnId", "turn-1")
        );

        ToolResult result = executor.execute(input);

        assertThat(result.status()).isEqualTo(ToolResultStatus.ERROR);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn test -Dtest=UiEmitToolExecutor_单元测试 -pl .
```

Expected: 编译失败 — `UiEmitToolExecutor` 类不存在。

- [ ] **Step 3: 实现 UiEmitToolExecutor**

```java
package com.lifepilot.interaction.web.a2ui;

import com.lifepilot.interaction.web.model.A2uiComponent;
import com.lifepilot.interaction.web.model.A2uiComponentTree;
import com.lifepilot.interaction.web.model.A2uiSignal;
import com.lifepilot.interaction.web.sse.SseEventType;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * ui.emit 工具执行器 — 将 A2UI 组件树通过 SSE 推送到前端。
 *
 * <p>接收 LLM 通过 tool call 提交的组件树 JSON，校验后作为 SSE UI 事件发送。
 * 返回精简确认给 LLM（组件数量），不返回完整 JSON 以节省 token。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
public class UiEmitToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(UiEmitToolExecutor.class);

    private final SseSessionManager sseManager;
    private final int maxComponentsPerTree;

    public UiEmitToolExecutor(SseSessionManager sseManager, int maxComponentsPerTree) {
        this.sseManager = sseManager;
        this.maxComponentsPerTree = maxComponentsPerTree;
    }

    /**
     * 执行 ui.emit 工具调用。
     *
     * @param input 工具输入，parameters 中包含 components 数组
     * @return 成功时返回组件数量确认，失败时返回错误描述
     */
    public ToolResult execute(ToolInput input) {
        // 1. 从 context 获取 SSE 流标识
        String streamId = input.getContextValue("streamId", String.class).orElse(null);
        if (streamId == null || streamId.isBlank()) {
            return ToolResult.error("ui.emit 需要流式模式（streamId 缺失）");
        }
        String sessionId = input.getContextValue("sessionId", String.class).orElse("");
        String turnId = input.getContextValue("turnId", String.class).orElse("");

        // 2. 提取并校验组件列表
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawComponents = input.getParam("components", List.class);
        if (rawComponents == null || rawComponents.isEmpty()) {
            return ToolResult.error("components 不能为空");
        }

        // 3. 转换并校验组件树
        List<A2uiComponent> components;
        try {
            components = rawComponents.stream().map(raw -> {
                @SuppressWarnings("unchecked")
                var signal = raw.get("signal") instanceof Map<?, ?> sigMap
                        ? new A2uiSignal((String) sigMap.get("name"),
                                sigMap.get("payload") instanceof Map<?, ?> p
                                        ? Map.copyOf((Map<String, Object>) p) : Map.of())
                        : null;
                @SuppressWarnings("unchecked")
                var children = raw.get("children") instanceof List<?> c
                        ? c.stream().map(Object::toString).toList() : List.<String>of();
                @SuppressWarnings("unchecked")
                var props = raw.get("properties") instanceof Map<?, ?> p
                        ? Map.copyOf((Map<String, Object>) p) : Map.<String, Object>of();
                return new A2uiComponent(
                        (String) raw.get("id"),
                        (String) raw.get("type"),
                        props, children, signal);
            }).toList();
        } catch (Exception e) {
            log.warn("ui.emit 组件转换失败: error={}", e.getMessage());
            return ToolResult.error("组件格式无效: " + e.getMessage());
        }

        var tree = new A2uiComponentTree(components);
        var validation = A2uiComponentValidator.validate(tree, maxComponentsPerTree);
        if (!validation.valid()) {
            String errors = String.join("; ", validation.errors());
            log.warn("ui.emit 组件校验失败: errors={}", errors);
            return ToolResult.error("组件校验失败: " + errors);
        }

        var validatedTree = validation.truncatedTree() != null ? validation.truncatedTree() : tree;

        // 4. 发送 SSE UI 事件
        var uiData = Map.<String, Object>of(
                "sessionId", sessionId,
                "turnId", turnId,
                "components", validatedTree.components()
        );
        sseManager.sendEvent(streamId, SseEventType.UI, uiData);

        log.info("ui.emit 组件已推送: streamId={}, count={}", streamId, validatedTree.components().size());
        return ToolResult.success(Map.of(
                "status", "emitted",
                "componentCount", validatedTree.components().size()
        ));
    }
}
```

注意：`A2uiComponent` 是简单 record（id, type, properties, children, signal），`A2uiSignal` 是 record（name, payload）。上述代码内联完成 `Map` → record 的转换，无需额外的 Converter 类。

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn test -Dtest=UiEmitToolExecutor_单元测试 -pl .
```

Expected: 3 个测试全部 PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/lifepilot/interaction/web/a2ui/UiEmitToolExecutor.java src/test/java/com/lifepilot/interaction/web/a2ui/UiEmitToolExecutor_单元测试.java
git commit -m "feat(a2ui): 实现 ui.emit 工具执行器 — 组件树校验 + SSE 推送"
```

---

### Task 4: 注册 ui.emit 为 BuiltinTool

**Files:**
- Create: `src/main/java/com/lifepilot/interaction/web/a2ui/UiEmitToolProvider.java`
- Modify: `src/main/java/com/lifepilot/interaction/web/config/A2uiAutoConfiguration.java`（或对应的配置类）

- [ ] **Step 1: 创建 UiEmitToolProvider**

参照 `NotifyToolProvider` 的模式，创建工具提供者：

```java
package com.lifepilot.interaction.web.a2ui;

import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;

import java.util.List;
import java.util.Map;

/**
 * A2UI 组件输出工具提供者。
 *
 * <p>注册 {@code ui.emit} 内置工具，供 LLM 通过 tool call 提交结构化组件树。
 * 该工具通过 a2ui skill 的 suggested-tools 按需激活，未加载 skill 时不暴露。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
public class UiEmitToolProvider {

    private final SseSessionManager sseManager;
    private final int maxComponentsPerTree;

    public UiEmitToolProvider(SseSessionManager sseManager, int maxComponentsPerTree) {
        this.sseManager = sseManager;
        this.maxComponentsPerTree = maxComponentsPerTree;
    }

    /**
     * 构建 ui.emit 工具。
     *
     * @return 包含单个 ui.emit 工具的列表
     */
    public List<BuiltinTool> buildTools() {
        var executor = new UiEmitToolExecutor(sseManager, maxComponentsPerTree);
        return List.of(
                BuiltinTool.builder()
                        .id("ui.emit")
                        .name("渲染交互组件")
                        .description("将结构化组件树推送到前端渲染为交互式 UI。" +
                                "仅在需要按钮、表单、signal 等交互能力时使用。" +
                                "纯展示内容（表格、列表、标题）应使用 Markdown。")
                        .category(ToolCategory.INTERACTION)
                        .inputSchema(buildInputSchema())
                        .riskLevel(RiskLevel.LOW)
                        .idempotent(false)
                        .executionSemantics(ToolExecutionSemantics.of(
                                PermissionActionType.GENERIC_TOOL_OPERATION,
                                ToolSchedulingMode.PARALLEL_SAFE,
                                ToolScopeResolvers.none()
                        ))
                        .tags(List.of("ui", "interaction"))
                        .executor(executor::execute)
                        .build()
        );
    }

    private JsonSchema buildInputSchema() {
        return JsonSchema.of(Map.of(
                "type", "object",
                "required", List.of("components"),
                "properties", Map.of(
                        "components", Map.of(
                                "type", "array",
                                "description", "A2UI 组件树节点数组（扁平邻接表）",
                                "items", Map.of(
                                        "type", "object",
                                        "required", List.of("id", "type", "properties"),
                                        "properties", Map.of(
                                                "id", Map.of("type", "string", "description", "组件唯一标识"),
                                                "type", Map.of("type", "string", "description",
                                                        "组件类型: Text, Card, Button, TextField, List, ListItem, " +
                                                        "DatePicker, Chip, Divider, Image, Table, CodeBlock, Progress"),
                                                "properties", Map.of("type", "object", "description", "组件属性"),
                                                "children", Map.of("type", "array",
                                                        "items", Map.of("type", "string"),
                                                        "description", "子组件 ID 列表"),
                                                "signal", Map.of("type", "object", "description",
                                                        "交互信号 {name, payload}（仅 Button/TextField/ListItem/Chip/DatePicker）",
                                                        "nullable", true)
                                        )
                                )
                        )
                )
        ));
    }
}
```

- [ ] **Step 2: 在 A2UI 配置类中注册 Bean**

读取 `A2uiAutoConfiguration.java`（或包含 A2UI Bean 的配置类），添加：

```java
@Bean
@ConditionalOnBean(SseSessionManager.class)
public UiEmitToolProvider uiEmitToolProvider(SseSessionManager sseManager,
                                              A2uiProperties a2uiProperties) {
    return new UiEmitToolProvider(sseManager, a2uiProperties.maxComponentsPerTree());
}

@Bean
public List<BuiltinTool> uiEmitTools(UiEmitToolProvider provider) {
    return provider.buildTools();
}
```

注意：`BuiltinToolRegistrar` 在启动时自动扫描所有 `List<BuiltinTool>` Bean，无需手动注册。但需确认 `BuiltinToolRegistrar` 是扫描 `BuiltinTool`（单个）还是 `List<BuiltinTool>`。检查 `BuiltinToolRegistrar` 的构造函数签名：它注入 `List<BuiltinTool>`，所以单个 BuiltinTool Bean 会被 Spring 自动收集。因此应注册为单个 Bean 而非 List：

```java
@Bean
@ConditionalOnBean(SseSessionManager.class)
public BuiltinTool uiEmitTool(SseSessionManager sseManager,
                                A2uiProperties a2uiProperties) {
    return new UiEmitToolProvider(sseManager, a2uiProperties.maxComponentsPerTree())
            .buildTools().get(0);
}
```

- [ ] **Step 3: 编译验证**

```bash
mvn compile -pl .
```

Expected: 编译通过，无错误。

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/lifepilot/interaction/web/a2ui/UiEmitToolProvider.java src/main/java/com/lifepilot/interaction/web/config/A2uiAutoConfiguration.java
git commit -m "feat(a2ui): 注册 ui.emit 为 BuiltinTool — skill 按需激活"
```

---

### Task 5: 确认 ToolContext 已传递 SSE 上下文

**Files:** 无需改动

已验证 `ToolContextKeys` 常量值与 `UiEmitToolExecutor` 中使用的 key 完全一致：
- `ToolContextKeys.SESSION_ID = "sessionId"` ✓
- `ToolContextKeys.TURN_ID = "turnId"` ✓
- `ToolContextKeys.STREAM_ID = "streamId"` ✓

`ToolBridgeAgentToolProvider.toToolCallback()` (line 156-186) 已将这三个字段写入 context map，
`ui.emit` 执行器通过 `input.getContextValue("streamId", String.class)` 即可获取。无需额外改动。

- [ ] **Step 1: 验证确认**

读取 `ToolContextKeys.java`，确认上述三个常量值未变。如果未来有人修改了常量值，需要同步修改 `UiEmitToolExecutor`。

---

### Task 6: 更新 A2UI Skill 内容

**Files:**
- Modify: `src/main/resources/skills/a2ui/SKILL.md`

- [ ] **Step 1: 重写 SKILL.md**

```markdown
---
id: a2ui
name: "结构化 UI 输出"
description: "将结构化信息渲染为交互式前端组件（按钮、表单、卡片等）。仅在需要用户交互（点击、输入、选择）时使用。纯展示内容应使用 Markdown。"
version: "3.0.0"
suggested-tools:
  - ui.emit
---

# A2UI 组件输出指南

## 核心原则：Markdown 优先

大部分结构化内容用 **Markdown** 即可美观呈现，无需 A2UI：

| 内容类型 | 推荐方式 | 示例 |
|----------|---------|------|
| 表格数据 | Markdown 表格 | `\| 列1 \| 列2 \|` |
| 有序/无序列表 | Markdown 列表 | `1. 步骤一` |
| 标题层级 | Markdown 标题 | `## 二级标题` |
| 代码展示 | Markdown 代码块 | ` ```java ` |
| 步骤路线图 | Markdown 有序列表 + 加粗 | `1. **阶段一：基础** — 详细描述` |
| 数学公式 | KaTeX | `$E=mc^2$` |
| 重要提示 | GitHub 告警块 | `> [!NOTE]` |

## 何时使用 A2UI

仅当需要**用户交互**（前端回调）时使用 A2UI：
- 待办列表（点击完成）→ ListItem + signal
- 操作按钮（触发动作）→ Button + signal
- 表单输入（提交数据）→ TextField + signal
- 选项切换（筛选标签）→ Chip + signal

**判断标准**：如果组件不需要 signal，就不需要 A2UI，用 Markdown。

## 使用方式

通过 `ui.emit` 工具调用提交组件树：

```json
{
  "components": [
    {
      "id": "list-1",
      "type": "List",
      "properties": {"ordered": true},
      "children": ["item-1"],
      "signal": null
    },
    {
      "id": "item-1",
      "type": "ListItem",
      "properties": {"text": "提交周报"},
      "children": [],
      "signal": {"name": "todo.complete", "payload": {"taskId": "1"}}
    }
  ]
}
```

先用文字说明上下文，再调用 `ui.emit` 渲染交互组件。

## 已注册组件

| 组件 | properties | signal |
|------|-----------|--------|
| **Text** | `{text, variant?: "caption"/"eyebrow"/"title"/"heading"}` | 无 |
| **Card** | `{title?, subtitle?, elevated?}` — 容器 | 无 |
| **Button** | `{label, variant?: "default"/"outline"/"ghost"/"destructive", disabled?}` | 有 |
| **TextField** | `{label?, placeholder?, value?}` | 有 |
| **List** | `{ordered?}` — 容器 | 无 |
| **ListItem** | `{text}` | 有 |
| **DatePicker** | `{label?, value?}` | 有 |
| **Chip** | `{label, selected?}` | 有 |
| **Divider** | `{orientation?: "horizontal"/"vertical"}` | 无 |
| **Image** | `{src, alt?, width?, height?}` | 无 |
| **Table** | `{columns: [{key, label}], rows: [{[key]: value}]}` | 无 |
| **CodeBlock** | `{code, language?}` | 无 |
| **Progress** | `{value, label?}` — value 0-100 | 无 |

## 规则

- 每次调用最多 50 个组件
- signal 放组件顶层字段，不放 properties 内
- 组件 id 必须唯一
```

- [ ] **Step 2: 编译验证 Skill 加载**

```bash
mvn compile -pl .
```

如果项目有 Skill 加载验证步骤（如 `SkillValidationPipeline`），确认新内容通过校验。

- [ ] **Step 3: 提交**

```bash
git add src/main/resources/skills/a2ui/SKILL.md
git commit -m "feat(a2ui): 重写 skill 指南 — markdown-first + tool call 方式"
```

---

### Task 7: 保留 `<a2ui>` 标签解析作为降级兼容

**Files:**
- 无需改动（保持现状）

- [ ] **Step 1: 确认 StreamingA2uiParser 保持不变**

当前 `StreamingA2uiParser` 和 `StreamOutputState` 中的 `<a2ui>` 标签解析逻辑保持不动。
这样即使 LLM 用了旧的标签格式（例如历史对话恢复、其他模型），仍能正常提取和渲染。

- [ ] **Step 2: 在 StreamingCallback 的注释中标记降级意图**

读取 `StreamingCallback.java:222-229`，更新注释说明当前状态：

将：
```java
// 提取 system 文本，通过 CallbackHelper 集中增强（流式约束）
// A2UI 组件文档已移至 a2ui skill，不再每次注入 system prompt
```

改为：
```java
// 提取 system 文本，通过 CallbackHelper 集中增强（流式约束）
// A2UI 主路径：LLM 通过 ui.emit tool call 提交组件树
// 降级路径：<a2ui> 标签解析保留兼容，StreamingA2uiParser 仍会检测标签
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/lifepilot/agent/callback/StreamingCallback.java
git commit -m "docs(a2ui): 更新 StreamingCallback 注释 — 标记 tool call 主路径与标签降级路径"
```

---

## 集成验证

### Task 8: 端到端验证

**Files:** 无新文件

- [ ] **Step 1: 启动后端，验证 ui.emit 工具注册**

```bash
mvn spring-boot:run
```

查看启动日志，确认：
- `开始注册内置工具: count=N`（N 应比改造前多 1）
- 没有 Bean 加载错误

- [ ] **Step 2: 启动前端，验证 Markdown 渲染**

```bash
cd zhiwei-web && npm run dev
```

在聊天中发送测试消息，要求 AI 输出包含：
- Markdown 表格
- 数学公式 `$E=mc^2$`
- 告警块 `> [!NOTE]`
- 有序列表 + 加粗标题

确认渲染美观、无原始标记泄露。

- [ ] **Step 3: 验证 A2UI tool call 路径**

在聊天中触发需要交互的场景（如"帮我创建一个待办列表，可以点击完成"），观察：
1. LLM 是否加载了 a2ui skill
2. LLM 是否调用了 `ui.emit` 工具（而非输出 `<a2ui>` 标签）
3. 前端是否正确渲染了 A2UI 组件
4. 组件的 signal 交互是否正常

- [ ] **Step 4: 验证降级路径**

手动在 LLM 输出中插入 `<a2ui>` 标签格式（可通过修改 skill 临时测试），确认旧格式仍能被 `StreamingA2uiParser` 正确提取和渲染。

- [ ] **Step 5: 最终提交（如有修复）**

```bash
git add -u
git commit -m "fix(a2ui): 端到端验证修复"
```
