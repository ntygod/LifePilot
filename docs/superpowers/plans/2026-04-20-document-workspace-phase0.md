# 文档工作空间 Phase 0 — 让 LLM 能"看到" docx/pdf 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **配套蓝图：** `docs/superpowers/specs/2026-04-20-document-workspace-design.md`（Phase 0 节）

**Goal：** 让 docx/pdf 附件上传到对话后，Agent 能通过 `document.parse` 工具读到结构化内容，前端附件卡片显示文档类型 + "AI 可读取" 标识。Phase 0 不引入 `documents`/`document_versions` 表，那是 Phase 2/3 的事。

**Architecture：**
1. 新增 `com.lifepilot.document.parser.DocumentParserService` —— 一个轻量 facade，按文件扩展名委托 `knowledge/parser/` 下现有的 `WordParser` / `PdfParser` / `MarkdownParser` / `PlainTextParser`（都已存在，无需改动）
2. 新增 `document.parse` Agent 工具：参数 `attachmentId` 或 `path` 二选一，返回结构化解析结果（content、metadata、truncated）
3. 修改 `BrowserIngressService`：用户消息构造阶段，对 docx/pdf 附件在 content 末尾追加一行系统提示（告诉 LLM 可调 `document.parse` 读内容）
4. 前端 `MessageBubble.vue`：docx/pdf/xlsx/pptx 附件卡片显示对应图标 + "AI 可读取" 徽标

**Tech Stack：** Spring Boot 3、Java 22、JUnit 5 + Mockito、JdbcTemplate（复用 AttachmentRepository）、Vue 3 + Vitest、lucide-vue-next 图标库、Tailwind CSS 命名尺度

---

## File Structure

| 文件 | 操作 | 责任 |
|---|---|---|
| `src/main/java/com/lifepilot/document/parser/DocumentParserService.java` | 新建 | 文档解析 facade，按扩展名路由到 knowledge/parser 下的具体 parser |
| `src/main/java/com/lifepilot/document/tool/DocumentParseToolExecutor.java` | 新建 | `document.parse` 工具的 Executor 实现 |
| `src/main/java/com/lifepilot/document/tool/DocumentToolProvider.java` | 新建 | 构建 `document.parse` BuiltinTool 描述（schema + 元数据） |
| `src/main/java/com/lifepilot/document/config/DocumentAutoConfiguration.java` | 新建 | Spring Boot AutoConfiguration，注册 service + tool 为 Bean |
| `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | 修改 | 追加 `DocumentAutoConfiguration` 入口 |
| `src/main/java/com/lifepilot/interaction/web/service/BrowserIngressService.java` | 修改 | `buildChatMessage` 时为 docx/pdf 附件追加系统提示 |
| `src/test/java/com/lifepilot/document/parser/DocumentParserService_文档解析路由测试.java` | 新建 | service 单元测试 |
| `src/test/java/com/lifepilot/document/tool/DocumentParseToolExecutor_附件解析测试.java` | 新建 | tool executor 单元测试 |
| `src/test/java/com/lifepilot/interaction/web/service/BrowserIngressService_文档附件提示测试.java` | 新建 | 验证 docx/pdf 附件的 content 提示注入 |
| `zhiwei-web/src/components/chat/MessageBubble.vue` | 修改 | 文档附件卡片增强（图标 + 徽标） |
| `zhiwei-web/src/components/chat/MessageBubble.spec.ts` | 修改 | 新增文档卡片渲染断言 |

不动：现有 `knowledge/parser/` 下所有文件、`AttachmentRepository`、数据库结构、Tauri 端代码。

---

## Task 1：创建 DocumentParserService（解析器路由 facade）

**目的：** 提供一个统一入口，按扩展名分发到正确的 parser。Service 持有 `List<DocumentParser>`，由 Spring 注入（knowledge 模块下的 4 个 parser bean）。

**Files:**
- Create: `src/main/java/com/lifepilot/document/parser/DocumentParserService.java`
- Test: `src/test/java/com/lifepilot/document/parser/DocumentParserService_文档解析路由测试.java`

**前置阅读：**
- `src/main/java/com/lifepilot/knowledge/parser/DocumentParser.java`（sealed interface，方法 `supportedExtensions()` / `parse()` / `extractMetadata()` / `canParse(Path)`）
- `src/main/java/com/lifepilot/knowledge/parser/ParseResult.java`（解析结果 record）
- `src/main/java/com/lifepilot/knowledge/parser/DocumentParseException.java`

- [ ] **Step 1：写失败测试**

新建 `src/test/java/com/lifepilot/document/parser/DocumentParserService_文档解析路由测试.java`：

```java
package com.lifepilot.document.parser;

import com.lifepilot.knowledge.parser.DocumentParseException;
import com.lifepilot.knowledge.parser.DocumentParser;
import com.lifepilot.knowledge.parser.MarkdownParser;
import com.lifepilot.knowledge.parser.ParseResult;
import com.lifepilot.knowledge.parser.PlainTextParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentParserService_文档解析路由测试 {

    @Test
    void 按扩展名命中对应解析器(@TempDir Path tmp) throws IOException, DocumentParseException {
        DocumentParserService service = new DocumentParserService(
                List.of(new MarkdownParser(), new PlainTextParser()));

        Path md = tmp.resolve("hello.md");
        Files.writeString(md, "# 标题\n\n正文");

        ParseResult result = service.parse(md);

        assertThat(result.content()).contains("标题");
        assertThat(result.content()).contains("正文");
    }

    @Test
    void 不支持的扩展名抛出异常(@TempDir Path tmp) throws IOException {
        DocumentParserService service = new DocumentParserService(
                List.of(new MarkdownParser()));

        Path bin = tmp.resolve("data.bin");
        Files.writeString(bin, "binary");

        assertThatThrownBy(() -> service.parse(bin))
                .isInstanceOf(DocumentParseException.class)
                .hasMessageContaining("bin");
    }

    @Test
    void supports_报告是否能解析(@TempDir Path tmp) throws IOException {
        DocumentParserService service = new DocumentParserService(
                List.of(new MarkdownParser()));

        Path md = tmp.resolve("a.md");
        Path bin = tmp.resolve("a.bin");
        Files.createFile(md);
        Files.createFile(bin);

        assertThat(service.supports(md)).isTrue();
        assertThat(service.supports(bin)).isFalse();
    }
}
```

- [ ] **Step 2：跑测试确认失败**

```bash
mvn -q test -Dtest=DocumentParserService_文档解析路由测试
```

预期：编译失败（`DocumentParserService` 类不存在）。

- [ ] **Step 3：实现 DocumentParserService**

新建 `src/main/java/com/lifepilot/document/parser/DocumentParserService.java`：

```java
package com.lifepilot.document.parser;

import com.lifepilot.knowledge.parser.DocumentParseException;
import com.lifepilot.knowledge.parser.DocumentParser;
import com.lifepilot.knowledge.parser.ParseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 文档解析路由 facade —— 按文件扩展名分发到对应的 {@link DocumentParser}。
 *
 * <p>持有所有可用 parser（由 Spring 注入），对外提供统一的 {@link #parse(Path)} 入口。
 * Knowledge 模块的 parser 实现保持原位，本 service 仅做路由。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
public class DocumentParserService {

    private static final Logger log = LoggerFactory.getLogger(DocumentParserService.class);

    private final List<DocumentParser> parsers;

    public DocumentParserService(List<DocumentParser> parsers) {
        this.parsers = List.copyOf(parsers);
        log.info("文档解析服务已就绪：parsers={}", parsers.size());
    }

    /**
     * 解析指定文件。按 parser.canParse() 顺序匹配，命中第一个即停止。
     *
     * @throws DocumentParseException 无可用 parser 或解析失败
     */
    public ParseResult parse(Path filePath) throws DocumentParseException {
        return findParser(filePath)
                .orElseThrow(() -> new DocumentParseException(
                        "不支持的文档类型：" + filePath.getFileName()))
                .parse(filePath);
    }

    /**
     * 检查文件是否有可用的解析器。
     */
    public boolean supports(Path filePath) {
        return findParser(filePath).isPresent();
    }

    private Optional<DocumentParser> findParser(Path filePath) {
        return parsers.stream().filter(p -> p.canParse(filePath)).findFirst();
    }
}
```

- [ ] **Step 4：跑测试确认通过**

```bash
mvn -q test -Dtest=DocumentParserService_文档解析路由测试
```

预期：3 个测试全部 PASS。

- [ ] **Step 5：提交**

```bash
git add src/main/java/com/lifepilot/document/parser/DocumentParserService.java \
        src/test/java/com/lifepilot/document/parser/DocumentParserService_文档解析路由测试.java
git commit -m "$(cat <<'EOF'
feat(document): 新增 DocumentParserService 路由 facade

按文件扩展名分发到 knowledge/parser 下现有的 Word/Pdf/Markdown/PlainText 解析器，
为后续 document.parse 工具提供统一入口。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2：创建 DocumentParseToolExecutor（document.parse 工具的执行体）

**目的：** 实现工具运行逻辑：参数取 `attachmentId` 或 `path`；前者从 `AttachmentRepository` 查路径，后者直接用；统一交给 `DocumentParserService` 解析；按 `maxChars` 截断；返回 `content / metadata / truncated`。

**Files:**
- Create: `src/main/java/com/lifepilot/document/tool/DocumentParseToolExecutor.java`
- Test: `src/test/java/com/lifepilot/document/tool/DocumentParseToolExecutor_附件解析测试.java`

**前置阅读：**
- `src/main/java/com/lifepilot/tool/model/ToolInput.java`（`getParam`、`getOptionalParam`）
- `src/main/java/com/lifepilot/tool/model/ToolResult.java`（`success(Map)`、`error(String)`）
- `src/main/java/com/lifepilot/interaction/web/repository/AttachmentRepository.java`（`findById(String) -> AttachmentRecord`，含 `filePath()`）

- [ ] **Step 1：写失败测试**

新建 `src/test/java/com/lifepilot/document/tool/DocumentParseToolExecutor_附件解析测试.java`：

```java
package com.lifepilot.document.tool;

import com.lifepilot.document.parser.DocumentParserService;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.interaction.web.repository.AttachmentRepository.AttachmentRecord;
import com.lifepilot.knowledge.parser.MarkdownParser;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentParseToolExecutor_附件解析测试 {

    @Mock AttachmentRepository attachmentRepository;

    @Test
    void 按 attachmentId 解析返回结构化内容(@TempDir Path tmp) throws IOException {
        Path md = tmp.resolve("note.md");
        Files.writeString(md, "# 测试标题\n\n这是正文。");

        when(attachmentRepository.findById("att-1")).thenReturn(new AttachmentRecord(
                "att-1", "session-1", "note.md", md.toString(),
                Files.size(md), "text/markdown", null));

        var executor = newExecutor();
        var input = newInput(Map.of("attachmentId", "att-1"));

        ToolResult result = executor.execute(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.data()).containsKeys("content", "fileName", "truncated");
        assertThat((String) result.data().get("content")).contains("测试标题");
        assertThat(result.data().get("fileName")).isEqualTo("note.md");
    }

    @Test
    void 按 path 解析也支持(@TempDir Path tmp) throws IOException {
        Path md = tmp.resolve("a.md");
        Files.writeString(md, "hello");

        var executor = newExecutor();
        var input = newInput(Map.of("path", md.toString()));

        ToolResult result = executor.execute(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat((String) result.data().get("content")).contains("hello");
    }

    @Test
    void attachmentId 与 path 都未提供时报错() {
        var executor = newExecutor();
        var input = newInput(Map.of());

        ToolResult result = executor.execute(input);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("attachmentId");
    }

    @Test
    void attachmentId 不存在时报错() {
        when(attachmentRepository.findById("missing")).thenReturn(null);

        var executor = newExecutor();
        var input = newInput(Map.of("attachmentId", "missing"));

        ToolResult result = executor.execute(input);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("附件不存在");
    }

    @Test
    void maxChars 截断生效(@TempDir Path tmp) throws IOException {
        Path md = tmp.resolve("long.md");
        Files.writeString(md, "a".repeat(1000));

        var executor = newExecutor();
        var input = newInput(Map.of("path", md.toString(), "maxChars", 100));

        ToolResult result = executor.execute(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat((Boolean) result.data().get("truncated")).isTrue();
        assertThat(((String) result.data().get("content")).length()).isLessThanOrEqualTo(100 + 50);
    }

    private DocumentParseToolExecutor newExecutor() {
        var service = new DocumentParserService(List.of(new MarkdownParser()));
        return new DocumentParseToolExecutor(service, attachmentRepository, 30000);
    }

    private ToolInput newInput(Map<String, Object> params) {
        return new ToolInput("document.parse", params,
                JsonSchema.of(Map.of("type", "object")), null, null);
    }
}
```

- [ ] **Step 2：跑测试确认失败**

```bash
mvn -q test -Dtest=DocumentParseToolExecutor_附件解析测试
```

预期：编译失败（`DocumentParseToolExecutor` 不存在）。

- [ ] **Step 3：实现 DocumentParseToolExecutor**

新建 `src/main/java/com/lifepilot/document/tool/DocumentParseToolExecutor.java`：

```java
package com.lifepilot.document.tool;

import com.lifepilot.document.parser.DocumentParserService;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.knowledge.parser.DocumentParseException;
import com.lifepilot.knowledge.parser.ParseResult;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * document.parse 工具的执行体。
 *
 * <p>解析对话附件或本地路径的文档，返回 LLM 可读的文本内容 + 元数据。
 * 参数 attachmentId 与 path 二选一。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
public class DocumentParseToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(DocumentParseToolExecutor.class);
    private static final String TRUNCATION_HINT = "\n...[内容已截断，maxChars=%d]";

    private final DocumentParserService parserService;
    private final AttachmentRepository attachmentRepository;
    private final int defaultMaxChars;

    public DocumentParseToolExecutor(DocumentParserService parserService,
                                     AttachmentRepository attachmentRepository,
                                     int defaultMaxChars) {
        this.parserService = parserService;
        this.attachmentRepository = attachmentRepository;
        this.defaultMaxChars = defaultMaxChars;
    }

    public ToolResult execute(ToolInput input) {
        String attachmentId = input.getOptionalParam("attachmentId", String.class).orElse(null);
        String path = input.getOptionalParam("path", String.class).orElse(null);
        if ((attachmentId == null || attachmentId.isBlank())
                && (path == null || path.isBlank())) {
            return ToolResult.error("需要提供 attachmentId 或 path 之一");
        }

        int maxChars = input.getOptionalParam("maxChars", Number.class)
                .map(Number::intValue).map(v -> Math.max(1, v))
                .orElse(defaultMaxChars);

        Path filePath;
        String fileName;
        if (attachmentId != null && !attachmentId.isBlank()) {
            var record = attachmentRepository.findById(attachmentId);
            if (record == null) {
                return ToolResult.error("附件不存在：attachmentId=" + attachmentId);
            }
            filePath = Paths.get(record.filePath());
            fileName = record.fileName();
        } else {
            filePath = Paths.get(path);
            fileName = filePath.getFileName().toString();
        }

        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            return ToolResult.error("文件不存在或不是普通文件：" + filePath);
        }
        if (!parserService.supports(filePath)) {
            return ToolResult.error("不支持的文档类型：" + fileName);
        }

        try {
            ParseResult parsed = parserService.parse(filePath);
            String content = parsed.content();
            boolean truncated = false;
            if (content.length() > maxChars) {
                content = content.substring(0, maxChars) + TRUNCATION_HINT.formatted(maxChars);
                truncated = true;
            }

            var data = new LinkedHashMap<String, Object>();
            data.put("content", content);
            data.put("fileName", fileName);
            data.put("totalChars", parsed.content().length());
            data.put("truncated", truncated);
            if (parsed.metadata() != null) {
                data.put("metadata", Map.of(
                        "format", parsed.metadata().format(),
                        "wordCount", parsed.metadata().wordCount()));
            }

            log.info("document.parse 成功：fileName={}, chars={}, truncated={}",
                    fileName, parsed.content().length(), truncated);
            return ToolResult.success(Map.copyOf(data));
        } catch (DocumentParseException e) {
            log.warn("document.parse 失败：fileName={}, error={}", fileName, e.getMessage());
            return ToolResult.error("文档解析失败：" + e.getMessage());
        }
    }
}
```

> ⚠️ **校验点：** 写完后用 IDE 检查 `parsed.metadata().format()` / `wordCount()` 字段名是否真实存在。如果 `DocumentMetadata` 字段名不同，按 `src/main/java/com/lifepilot/knowledge/parser/DocumentMetadata.java` 实际定义调整 Map 内容。

- [ ] **Step 4：跑测试确认通过**

```bash
mvn -q test -Dtest=DocumentParseToolExecutor_附件解析测试
```

预期：5 个测试全部 PASS。如果 `metadata` 字段名不匹配编译失败，按 `DocumentMetadata.java` 真实结构调整 `data.put("metadata", ...)` 部分（不是测试期望的关键，可以先简化为只放 content/fileName/truncated，待 review 时补全）。

- [ ] **Step 5：提交**

```bash
git add src/main/java/com/lifepilot/document/tool/DocumentParseToolExecutor.java \
        src/test/java/com/lifepilot/document/tool/DocumentParseToolExecutor_附件解析测试.java
git commit -m "$(cat <<'EOF'
feat(document): 新增 DocumentParseToolExecutor — document.parse 工具实现

支持按 attachmentId（查 AttachmentRepository）或 path 解析文档，
按 maxChars 截断后返回结构化结果。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3：创建 DocumentToolProvider 构建 BuiltinTool 元数据

**目的：** 把 `DocumentParseToolExecutor` 包成 `BuiltinTool`（含 schema、风险等级、调度语义），后续被 BuiltinToolRegistrar 自动注册。

**Files:**
- Create: `src/main/java/com/lifepilot/document/tool/DocumentToolProvider.java`

**前置阅读：**
- `src/main/java/com/lifepilot/meta/infra/file/FileToolProvider.java`（参照 `buildFileReadTool` 写法）
- `src/main/java/com/lifepilot/tool/BuiltinTool.java`（builder API）
- `src/main/java/com/lifepilot/tool/schema/JsonSchema.java`（`JsonSchema.of(Map)`）

> 此 Task 不写单元测试 —— Provider 是配置型代码，正确性由 Task 4 的集成测试 + Task 7 的 e2e 验证覆盖。

- [ ] **Step 1：实现 DocumentToolProvider**

新建 `src/main/java/com/lifepilot/document/tool/DocumentToolProvider.java`：

```java
package com.lifepilot.document.tool;

import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档工具提供者 —— 当前阶段仅含 document.parse。
 *
 * <p>后续 Phase 将扩展 document.create_*、document.patch_* 等工具，
 * 都通过本 Provider 集中构建。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
public class DocumentToolProvider {

    private static final List<String> DOCUMENT_TAGS = List.of("infrastructure", "document");

    private final DocumentParseToolExecutor parseExecutor;

    public DocumentToolProvider(DocumentParseToolExecutor parseExecutor) {
        this.parseExecutor = parseExecutor;
    }

    public List<BuiltinTool> buildDocumentTools() {
        return List.of(buildParseTool());
    }

    private BuiltinTool buildParseTool() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("attachmentId", Map.of("type", "string",
                "description", "对话附件 ID（与 path 二选一）。优先使用此参数。"));
        properties.put("path", Map.of("type", "string",
                "description", "本地文件路径（与 attachmentId 二选一）"));
        properties.put("maxChars", Map.of("type", "integer",
                "description", "返回内容最大字符数，默认 30000，超出截断"));

        return BuiltinTool.builder()
                .id("document.parse")
                .category(ToolCategory.PERCEPTION)
                .name("解析文档")
                .description("解析 docx / pdf / md / txt 等文档为可读文本。" +
                        "用户上传 docx/pdf 附件后可调此工具读取内容。" +
                        "attachmentId 和 path 二选一。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "properties", properties
                )))
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.READ_FILE,
                        ToolSchedulingMode.RESOURCE_SERIALIZED,
                        ToolScopeResolvers.pathTrees("path")
                ))
                .tags(DOCUMENT_TAGS)
                .executor(parseExecutor::execute)
                .build();
    }
}
```

- [ ] **Step 2：跑编译确认通过**

```bash
mvn -q -DskipTests compile
```

预期：BUILD SUCCESS。

- [ ] **Step 3：提交**

```bash
git add src/main/java/com/lifepilot/document/tool/DocumentToolProvider.java
git commit -m "$(cat <<'EOF'
feat(document): 新增 DocumentToolProvider 构建 document.parse BuiltinTool

参照 FileToolProvider 模式，将 DocumentParseToolExecutor 包装成
BuiltinTool（schema + 风险等级 LOW + READ_FILE 权限）。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4：创建 DocumentAutoConfiguration 装配 + 注册到 AutoConfiguration.imports

**目的：** Spring Boot 自动配置：注入 4 个 knowledge parser → 构造 DocumentParserService → 构造 DocumentParseToolExecutor → 构造 DocumentToolProvider → 暴露 BuiltinTool list 为 Bean（BuiltinToolRegistrar 会自动注册）。

**Files:**
- Create: `src/main/java/com/lifepilot/document/config/DocumentAutoConfiguration.java`
- Modify: `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

**前置阅读：**
- `src/main/java/com/lifepilot/meta/config/MetaAutoConfiguration.java`（autoconfig 写法 + `@AutoConfiguration` + `@Bean` 模式）
- `src/main/java/com/lifepilot/tool/registry/BuiltinToolRegistrar.java`（确认它收集 `List<BuiltinTool>` Bean）

- [ ] **Step 1：先验证 BuiltinToolRegistrar 是否真的收集 BuiltinTool Bean**

```bash
grep -rn "List<BuiltinTool>" src/main/java/com/lifepilot/tool/ | head -5
```

预期：`BuiltinToolRegistrar` 构造器接收 `List<BuiltinTool> builtinTools`，Spring 会自动注入所有 `BuiltinTool` Bean。

如果验证失败（找不到这种自动注入），改用 InfraToolProvider 的手动注册风格——在 AutoConfiguration 里监听 `ApplicationReadyEvent`，调用 `DynamicToolRegistry.registerBuiltinTool()` 注册。本计划默认走 Bean 注入，验证失败时按上面的备用方案。

- [ ] **Step 2：实现 DocumentAutoConfiguration**

新建 `src/main/java/com/lifepilot/document/config/DocumentAutoConfiguration.java`：

```java
package com.lifepilot.document.config;

import com.lifepilot.document.parser.DocumentParserService;
import com.lifepilot.document.tool.DocumentParseToolExecutor;
import com.lifepilot.document.tool.DocumentToolProvider;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.knowledge.parser.DocumentParser;
import com.lifepilot.knowledge.parser.MarkdownParser;
import com.lifepilot.knowledge.parser.PdfParser;
import com.lifepilot.knowledge.parser.PlainTextParser;
import com.lifepilot.knowledge.parser.WordParser;
import com.lifepilot.tool.BuiltinTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * 文档工作空间自动配置。
 *
 * <p>装配 DocumentParserService（路由 facade）+ document.parse 工具。
 * Knowledge 模块的 4 个 parser 实现被注入为 List。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
@AutoConfiguration
@ConditionalOnProperty(name = "lifepilot.document.enabled", havingValue = "true", matchIfMissing = true)
public class DocumentAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DocumentAutoConfiguration.class);

    /** 复用 knowledge 模块的 parser 实现 —— 它们都是无状态的，可直接 new。 */
    @Bean
    DocumentParserService documentParserService() {
        List<DocumentParser> parsers = List.of(
                new MarkdownParser(),
                new PlainTextParser(),
                new WordParser(),
                new PdfParser());
        return new DocumentParserService(parsers);
    }

    @Bean
    @ConditionalOnBean(AttachmentRepository.class)
    DocumentParseToolExecutor documentParseToolExecutor(DocumentParserService parserService,
                                                        AttachmentRepository attachmentRepository) {
        return new DocumentParseToolExecutor(parserService, attachmentRepository, 30000);
    }

    @Bean
    @ConditionalOnBean(DocumentParseToolExecutor.class)
    DocumentToolProvider documentToolProvider(DocumentParseToolExecutor parseExecutor) {
        return new DocumentToolProvider(parseExecutor);
    }

    /** 暴露 document 工具为 List<BuiltinTool> Bean，供 BuiltinToolRegistrar 自动注册。 */
    @Bean
    @ConditionalOnBean(DocumentToolProvider.class)
    List<BuiltinTool> documentBuiltinTools(DocumentToolProvider provider) {
        var tools = provider.buildDocumentTools();
        log.info("已构建 document 工具：count={}", tools.size());
        return tools;
    }
}
```

> ⚠️ **关于 `MarkdownParser` / `PlainTextParser` / `WordParser` / `PdfParser` 的构造器：**
> 在写这些 `new XxxParser()` 之前，先快速看一眼对应类的构造器签名（`grep -n "public .*Parser(" src/main/java/com/lifepilot/knowledge/parser/*.java`）。
> 如果某个 parser 需要参数（如 `WordParser` 可能依赖配置），改成从 Spring 注入对应实例：将构造逻辑移出 service 内联，改为方法签名 `documentParserService(List<DocumentParser> parsers)`。
> 默认假设全部无参可直接 new（基于刚刚读过的 `WordParser.java` 是无参的）。

- [ ] **Step 3：注册到 AutoConfiguration.imports**

修改 `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`，在 `com.lifepilot.knowledge.config.KnowledgeRuntimeAutoConfiguration` 下面追加一行：

```
com.lifepilot.document.config.DocumentAutoConfiguration
```

- [ ] **Step 4：编译验证**

```bash
mvn -q -DskipTests compile
```

预期：BUILD SUCCESS。

- [ ] **Step 5：跑全量单测确保没破坏其他模块**

```bash
mvn -q test
```

预期：所有测试 PASS（已存在的 + 新加的 Task 1/2 的）。

- [ ] **Step 6：启动 backend smoke 验证工具注册**

```bash
mvn -q spring-boot:run
```

启动后查看日志，搜：

```
工具注册统计
```

应该能看到 document 工具被列入（如果有 layer 分类），或者：

```
开始注册内置工具: count=N
```

N 比修改前 +1（多了 document.parse）。

按 Ctrl+C 停止 backend。

- [ ] **Step 7：提交**

```bash
git add src/main/java/com/lifepilot/document/config/DocumentAutoConfiguration.java \
        src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
git commit -m "$(cat <<'EOF'
feat(document): 新增 DocumentAutoConfiguration 装配文档工具链

注入 knowledge parser → DocumentParserService → DocumentParseToolExecutor →
DocumentToolProvider → List<BuiltinTool> Bean，由 BuiltinToolRegistrar 自动注册到
DynamicToolRegistry。新增 lifepilot.document.enabled 配置开关（默认开）。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5：修改 BrowserIngressService — 文档附件追加系统提示

**目的：** 用户上传 docx/pdf/md/txt 附件后，在用户消息 content 末尾追加一行系统提示，告诉 LLM 可以调 `document.parse(attachmentId=...)` 读取内容。Agent 读到提示后会按需调工具。

**Files:**
- Modify: `src/main/java/com/lifepilot/interaction/web/service/BrowserIngressService.java`
- Create: `src/test/java/com/lifepilot/interaction/web/service/BrowserIngressService_文档附件提示测试.java`

**前置阅读：**
- `src/main/java/com/lifepilot/interaction/web/service/BrowserIngressService.java` 的 `buildChatMessage` 方法（L87-128）— 在 `MessageContent.TextMessage` 构造之前注入提示

- [ ] **Step 1：写失败测试**

新建 `src/test/java/com/lifepilot/interaction/web/service/BrowserIngressService_文档附件提示测试.java`：

```java
package com.lifepilot.interaction.web.service;

import com.lifepilot.interaction.gateway.GatewayMessage;
import com.lifepilot.interaction.model.MessageContent;
import com.lifepilot.interaction.web.model.ChatRequest;
import com.lifepilot.interaction.web.model.ChatTurnAction;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.interaction.web.repository.AttachmentRepository.AttachmentRecord;
import com.lifepilot.interaction.web.service.ChatTurnService.ResolvedTurnRequest;
import com.lifepilot.media.MediaProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 验证：用户上传 docx/pdf 附件时，BrowserIngressService 在消息 content 末尾
 * 追加 document.parse 提示。
 */
@ExtendWith(MockitoExtension.class)
class BrowserIngressService_文档附件提示测试 {

    @Mock AttachmentRepository attachmentRepository;
    @Mock ChatTurnService chatTurnService;

    @Test
    void docx_附件触发 document_parse 系统提示(@TempDir Path tmp) throws Exception {
        Path docx = tmp.resolve("contract.docx");
        Files.createFile(docx);

        when(chatTurnService.prepare(anyString(), any())).thenReturn(new ResolvedTurnRequest(
                "turn-1", ChatTurnAction.SEND, "请帮我读这份合同",
                List.of("att-doc"), null));
        when(attachmentRepository.findById("att-doc")).thenReturn(new AttachmentRecord(
                "att-doc", "session-1", "contract.docx", docx.toString(),
                Files.size(docx),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "/api/attachments/att-doc"));

        var service = newService();
        var request = new ChatRequest("turn-1", ChatTurnAction.SEND,
                "请帮我读这份合同", "session-1", List.of("att-doc"), null);

        GatewayMessage msg = service.buildChatMessage(request, null,
                com.lifepilot.interaction.gateway.DeliveryMode.SYNC);

        var text = ((MessageContent.TextMessage) msg.content()).text();
        assertThat(text).contains("请帮我读这份合同");
        assertThat(text).contains("contract.docx");
        assertThat(text).contains("document.parse");
        assertThat(text).contains("att-doc");
    }

    @Test
    void 图片附件不触发文档提示(@TempDir Path tmp) throws Exception {
        Path img = tmp.resolve("photo.png");
        Files.createFile(img);

        when(chatTurnService.prepare(anyString(), any())).thenReturn(new ResolvedTurnRequest(
                "turn-2", ChatTurnAction.SEND, "看这张图",
                List.of("att-img"), null));
        when(attachmentRepository.findById("att-img")).thenReturn(new AttachmentRecord(
                "att-img", "session-1", "photo.png", img.toString(),
                Files.size(img), "image/png", "/api/attachments/att-img"));

        var service = newService();
        var request = new ChatRequest("turn-2", ChatTurnAction.SEND,
                "看这张图", "session-1", List.of("att-img"), null);

        GatewayMessage msg = service.buildChatMessage(request, null,
                com.lifepilot.interaction.gateway.DeliveryMode.SYNC);

        var text = ((MessageContent.TextMessage) msg.content()).text();
        assertThat(text).doesNotContain("document.parse");
    }

    private BrowserIngressService newService() {
        BooleanSupplier audioProbe = () -> false;
        return new BrowserIngressService(
                attachmentRepository, chatTurnService, null, null,
                new MediaProperties(), audioProbe);
    }
}
```

> ⚠️ **关于 `BrowserIngressService` 构造器签名：** 写完 newService() 后 IDE 会报错（构造器参数顺序/数量不匹配）。打开 `BrowserIngressService.java:57` 看真实构造器，按真实签名调整 `newService()`。`MediaProperties` 字段不重要（不走音频路径），传默认实例即可。

- [ ] **Step 2：跑测试确认失败**

```bash
mvn -q test -Dtest=BrowserIngressService_文档附件提示测试
```

预期：测试 FAIL —— `text` 不包含 `document.parse`（说明改造前的 BrowserIngressService 不会注入提示）。

- [ ] **Step 3：在 BrowserIngressService 注入文档提示**

打开 `src/main/java/com/lifepilot/interaction/web/service/BrowserIngressService.java`，找到 `buildChatMessage` 方法（约 L87-128）。在 `var content = new MessageContent.TextMessage(transcription.content());` 之前**新增**：

```java
        // 文档附件提示注入：对 docx/pdf/md/txt 附件，告诉 LLM 可调 document.parse 读取
        String contentWithDocHint = appendDocumentParseHint(
                transcription.content(), effectiveAttachmentsForHint(attachments, transcription));
```

把下一行的 content 改用拼接后的文本：

```java
        var content = new MessageContent.TextMessage(contentWithDocHint);
```

注意：`effectiveAttachmentsForHint` 应该用**未做音频过滤**的原始 attachments，因为它判断的是文档而不是音频。如果直接用 `effectiveAttachments` 也可以——`audio` 已被剔除不影响。简化起见**直接用 `attachments`**。

调整为：

```java
        String contentWithDocHint = appendDocumentParseHint(
                transcription.content(), attachments);
        var content = new MessageContent.TextMessage(contentWithDocHint);
```

在类底部添加私有方法：

```java
    /** 文档类附件的 MIME 类型前缀/精确匹配集合 —— 这些类型 LLM 看不到内容，需要 document.parse */
    private static final List<String> DOCUMENT_MIME_PREFIXES = List.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/msword",
            "text/markdown",
            "text/plain"
    );

    /**
     * 对包含文档类附件的消息，在末尾追加一段系统提示，告诉 LLM 可调 document.parse 读取。
     */
    private String appendDocumentParseHint(String originalContent,
                                           List<GatewayMessage.Attachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return originalContent;
        }
        var docs = attachments.stream()
                .filter(this::isDocumentAttachment)
                .toList();
        if (docs.isEmpty()) {
            return originalContent;
        }
        var hint = new StringBuilder("\n\n[系统提示] 用户上传了以下文档附件，可调用 document.parse 工具读取内容：\n");
        for (var doc : docs) {
            hint.append("- ").append(doc.fileName())
                .append("（attachmentId=").append(doc.id()).append("）\n");
        }
        return originalContent + hint;
    }

    private boolean isDocumentAttachment(GatewayMessage.Attachment att) {
        if (att.mimeType() == null) return false;
        return DOCUMENT_MIME_PREFIXES.stream().anyMatch(att.mimeType()::startsWith);
    }
```

> ⚠️ **关于 `GatewayMessage.Attachment` 字段名：** 上面用了 `att.fileName()`、`att.id()`、`att.mimeType()`。这些是基于 `AttachmentRecord` 字段的猜测；实际看 `GatewayMessage.Attachment` 结构（grep 一下：`grep -n "record Attachment" src/main/java/com/lifepilot/interaction/gateway/GatewayMessage.java`）。如果字段叫 `name()` 或 `attachmentId()` 就按实际改。

- [ ] **Step 4：跑测试确认通过**

```bash
mvn -q test -Dtest=BrowserIngressService_文档附件提示测试
```

预期：2 个测试全部 PASS。

- [ ] **Step 5：跑相关回归测试**

```bash
mvn -q test -Dtest='BrowserIngressService*'
```

预期：所有 BrowserIngressService 相关测试 PASS（包括既有的）。

- [ ] **Step 6：提交**

```bash
git add src/main/java/com/lifepilot/interaction/web/service/BrowserIngressService.java \
        src/test/java/com/lifepilot/interaction/web/service/BrowserIngressService_文档附件提示测试.java
git commit -m "$(cat <<'EOF'
feat(document): BrowserIngressService 为文档附件注入 document.parse 提示

当用户消息携带 docx/pdf/md/txt 附件时，在 content 末尾追加一段系统提示，
列出附件名 + attachmentId，引导 LLM 调用 document.parse 工具读取内容。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6：前端 MessageBubble 文档附件卡片增强

**目的：** Web 对话里 docx/pdf/xlsx/pptx 附件目前只显示文件名 + 大小，加文件类型图标 + "AI 可读取" 徽标，让用户感知"AI 能看见这个文档"。

**Files:**
- Modify: `zhiwei-web/src/components/chat/MessageBubble.vue`
- Modify: `zhiwei-web/src/components/chat/MessageBubble.spec.ts`

**前置阅读：**
- `zhiwei-web/src/components/chat/MessageBubble.vue:448-470`（现有 `fileAttachments` 渲染区）
- 项目规范：Tailwind 仅用命名尺度（xs/sm/md/lg/xl/2xl），不用 `p-3` `mt-7` 等任意值

- [ ] **Step 1：在 MessageBubble.vue 新增文档类型识别**

打开 `zhiwei-web/src/components/chat/MessageBubble.vue`，在 `<script setup>` 段（约 L119 附近 `fileAttachments` 计算属性下方）新增：

```ts
import { FileText, FileType, Sheet, Presentation, FileCode2 } from 'lucide-vue-next'

/** 判断附件是否是 AI 可解析的文档类型 */
function isParseableDocument(att: { type?: string; filename: string }): boolean {
  const t = att.type?.toLowerCase() ?? ''
  if (t === 'application/pdf') return true
  if (t.includes('wordprocessingml')) return true  // docx
  if (t.includes('spreadsheetml')) return true     // xlsx
  if (t.includes('presentationml')) return true    // pptx
  if (t === 'text/markdown' || t === 'text/plain') return true
  // 兜底按扩展名
  const ext = att.filename.split('.').pop()?.toLowerCase()
  return ['pdf', 'docx', 'xlsx', 'pptx', 'md', 'txt'].includes(ext ?? '')
}

/** 按文件类型返回 lucide 图标组件 */
function documentIcon(att: { type?: string; filename: string }) {
  const ext = att.filename.split('.').pop()?.toLowerCase() ?? ''
  if (ext === 'pdf') return FileType
  if (ext === 'xlsx' || ext === 'xls' || ext === 'csv') return Sheet
  if (ext === 'pptx' || ext === 'ppt') return Presentation
  if (ext === 'md') return FileCode2
  return FileText  // docx / txt / 其他
}
```

> 检查：`lucide-vue-next` 是否已在 `package.json` 依赖中？应该已经有（项目规范里写了图标库）。如果没有这些具体名字（lucide 图标命名偶尔变动），用 `FileText` 兜底所有类型也可以。

- [ ] **Step 2：修改 fileAttachments 渲染区（约 L448-470）**

把现有的：

```vue
<div v-if="fileAttachments.length > 0" class="mt-3 flex flex-col gap-sm">
  <div
    v-for="attachment in fileAttachments"
    :key="attachment.fileId"
    ...
  >
    <span class="truncate">{{ attachment.filename }}</span>
    ...
  </div>
</div>
```

改造为含图标 + 徽标版本：

```vue
<div v-if="fileAttachments.length > 0" class="mt-md flex flex-col gap-sm">
  <div
    v-for="attachment in fileAttachments"
    :key="attachment.fileId"
    class="flex items-center gap-sm rounded-md border border-border bg-muted/40 p-sm"
  >
    <component :is="documentIcon(attachment)" class="h-md w-md shrink-0 text-muted-foreground" />
    <div class="min-w-0 flex-1">
      <div class="flex items-center gap-xs">
        <span class="truncate text-sm">{{ attachment.filename }}</span>
        <span
          v-if="isParseableDocument(attachment)"
          class="shrink-0 rounded-md bg-primary/10 px-xs py-[1px] text-xs text-primary"
          title="AI 可调用 document.parse 读取此文档内容"
        >AI 可读取</span>
      </div>
      <div class="text-xs text-muted-foreground">
        {{ (attachment.size / 1024).toFixed(1) }} KB
      </div>
    </div>
    <a
      :href="attachment.url"
      :download="attachment.filename"
      class="shrink-0 text-xs text-primary hover:underline"
    >下载</a>
  </div>
</div>
```

> ⚠️ **样式细节：** 上面用了 `gap-xs / gap-sm / p-sm / mt-md / h-md / w-md` 等命名尺度（合规）。`px-xs py-[1px]` 中 `[1px]` 是为徽标紧凑写法 —— 不合规则，改为 `py-xs` 即可（视觉差异极小）。
> 颜色变量 `bg-muted/40` `text-muted-foreground` `text-primary` 都是项目已有的 CSS 变量，沿用即可。

- [ ] **Step 3：在 MessageBubble.spec.ts 新增渲染断言**

打开 `zhiwei-web/src/components/chat/MessageBubble.spec.ts`，添加测试：

```ts
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import MessageBubble from './MessageBubble.vue'

describe('MessageBubble — 文档附件卡片', () => {
  it('docx 附件显示「AI 可读取」徽标和文件图标', () => {
    const wrapper = mount(MessageBubble, {
      props: {
        message: {
          id: 'm1',
          role: 'user',
          content: '请看这份合同',
          attachments: [{
            fileId: 'att-1',
            url: '/api/attachments/att-1',
            filename: '合同.docx',
            size: 102400,
            type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
          }],
        },
      },
    })

    expect(wrapper.text()).toContain('合同.docx')
    expect(wrapper.text()).toContain('AI 可读取')
  })

  it('未知二进制附件不显示「AI 可读取」徽标', () => {
    const wrapper = mount(MessageBubble, {
      props: {
        message: {
          id: 'm2',
          role: 'user',
          content: '',
          attachments: [{
            fileId: 'att-2',
            url: '/api/attachments/att-2',
            filename: 'data.bin',
            size: 2048,
            type: 'application/octet-stream',
          }],
        },
      },
    })

    expect(wrapper.text()).toContain('data.bin')
    expect(wrapper.text()).not.toContain('AI 可读取')
  })
})
```

> ⚠️ 现有 `MessageBubble.spec.ts` 可能已有 `describe` 块；把上面两个 `it` 追加到现有 describe 里或新增一个 describe。检查现有结构后选择最少改动的方式。

- [ ] **Step 4：跑前端测试**

```bash
cd zhiwei-web && npm run test:run -- MessageBubble.spec.ts
```

预期：所有 MessageBubble 测试 PASS（包括既有 + 新增的两个）。

- [ ] **Step 5：跑前端构建确认无类型错误**

```bash
cd zhiwei-web && npm run build
```

预期：BUILD SUCCESS。

- [ ] **Step 6：提交**

```bash
git add zhiwei-web/src/components/chat/MessageBubble.vue \
        zhiwei-web/src/components/chat/MessageBubble.spec.ts
git commit -m "$(cat <<'EOF'
feat(web): MessageBubble 文档附件卡片显示类型图标 + AI 可读取徽标

docx / pdf / xlsx / pptx / md / txt 附件在对话中显示对应文件图标，
并加「AI 可读取」徽标提示用户 LLM 能解析此文档。新增下载链接。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7：端到端手测验证

**目的：** 启动完整环境，拖一份 docx 进对话，验证 LLM 能调 `document.parse` 读到内容。

**Files:** 无代码改动；执行手测 + 记录结果。

- [ ] **Step 1：启动后端**

```bash
mvn -q spring-boot:run
```

观察日志确认：
- `开始注册内置工具: count=N` 中 N 比基线 +1
- 没有 `DocumentAutoConfiguration` 相关错误

- [ ] **Step 2：启动前端**

新开 terminal：

```bash
cd zhiwei-web && npm run dev
```

打开 `http://localhost:5173`。

- [ ] **Step 3：手测主路径**

1. 准备一份测试用 docx（任意短文档，含一段可识别的文字，比如"测试文档：本文档是 Phase 0 e2e 验证"）
2. 在对话里拖入 docx
3. 验证消息卡片：显示 docx 图标、文件名、"AI 可读取"徽标、下载链接
4. 发送提问："这份文档说了什么？"
5. 观察 Agent 是否调用 `document.parse`（前端可以看 trace 面板，或后端日志搜 `document.parse`）
6. 验证 Agent 回答里包含 docx 真实内容

- [ ] **Step 4：手测异常路径**

7. 上传一个 .bin 二进制文件，发"读这个"
8. 期望：Agent 看到 attachmentId 但 `document.parse` 返回"不支持的文档类型"，Agent 给出合理解释（不会胡编内容）

- [ ] **Step 5：记录结果 + 关停**

如果一切正常：

```bash
# Ctrl+C 停后端 + 前端
echo "Phase 0 e2e 验证通过 — $(date '+%Y-%m-%d %H:%M')"
```

如果有问题：把 issue 写下来，回到对应 Task 修。**不要绕过失败用 mock 数据掩盖。**

- [ ] **Step 6：（可选）顺手修一下 stale 文档**

`.claude/rules/database-rules.md` 还写"当前最新版本：V8"，实际是 V11。本计划不涉及新建 Flyway 迁移，但既然手在 repo 上可以顺便修：

```bash
# 修 database-rules.md 把 V8 改成 V11
# 修 CLAUDE.md "Flyway migration scripts (V1–V8" → "(V1–V11"
git add .claude/rules/database-rules.md CLAUDE.md
git commit -m "docs: 更新数据库迁移最新版本号 V8 → V11"
```

仅当未发现其他遗漏时执行此 step；如果 Phase 0 实施过程中已发现新的 stale 文档，一起在这里修。

---

## Self-Review 注记

写完后 author 已自检以下问题：

**1. Spec 覆盖**：spec Phase 0 列了 5 项 —— ① 抽 parser 到通用模块、② 新增 document.parse、③ 改 BrowserIngressService、④ 改 MessageBubble、⑤ 新建 documents/document_versions 表。本 plan 实施 ①（轻量 facade 而非物理移动文件，理由：减少 Phase 0 风险）+ ②③④。**第 ⑤ 项（数据库表）暂缓**到 Phase 2 — 理由：Phase 0 不引入文档实体生命周期，附件已通过 message_attachments 表持久化；过早建表会引入未消费的字段。Spec 第 8 节 Phase 0 说明对应调整建议在 Phase 0 验收后同步修订（顺手提 PR 修 spec）。

**2. 占位扫描**：无 TBD/TODO；每个 Step 都有可执行命令或完整代码。两处 ⚠️ 标注为"实施时校验真实字段名"（Task 2 的 metadata 字段、Task 5 的 GatewayMessage.Attachment 字段），属于必要的灵活点而非占位。

**3. 类型一致性**：
- `DocumentParserService` 在 Task 1/2/4 都是同一签名（`List<DocumentParser>` 注入，`parse(Path)` / `supports(Path)` 两个公有方法）
- `DocumentParseToolExecutor` 在 Task 2/3/4 都是同一构造器（`(DocumentParserService, AttachmentRepository, int defaultMaxChars)`）
- 工具 ID `document.parse` 在 Task 3/5/7 一致
- 配置开关 `lifepilot.document.enabled` 在 Task 4 引入，无后续 Task 引用，OK

**4. 风险提示**：
- Task 4 的 BuiltinTool Bean 自动注册路径未实际验证；准备了备选方案（手动 ApplicationReadyEvent 注册）
- Task 5 的字段名 `att.fileName()` / `att.id()` 是基于 AttachmentRecord 推测的 GatewayMessage.Attachment 字段；实施时第一步就要 grep 验证

---

**完结**。后续 Phase 1（解析完整覆盖：xlsx/pptx parser + 前端 docx/pdf 预览）再走一次 writing-plans。
