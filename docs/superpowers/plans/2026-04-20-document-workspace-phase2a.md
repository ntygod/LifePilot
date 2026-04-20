# 文档工作空间 Phase 2A — docx 垂直打穿实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **配套蓝图**：`docs/superpowers/specs/2026-04-20-document-workspace-design.md`（第 0.5 节 Phase 2）

**Goal：** 从 0 生成 Word 文档的垂直打穿 —— 新增 `document.create_docx` 工具接受 markdown → 用 POI XWPF 渲染成 docx → 落盘到本地 + 入 `documents` 表 + 挂到 assistant 消息 → 前端气泡产物卡片 + 下载端点可用。

**Architecture：**
1. **数据层**：Flyway V12 建 `documents` 表（entry-级元数据），新增 `DocumentRepository` JdbcTemplate 模式
2. **生成层**：`DocumentGenerator` 接口 + `MarkdownToDocxGenerator`（POI XWPF 逐行处理 markdown —— 标题/段落/有序/无序列表）
3. **工具层**：`DocumentCreateDocxToolExecutor` 作为 `document.create_docx` 工具；生成文件后落盘 `~/.zhiwei/documents/` + 入 `documents` 表 + 入 `message_attachments`（entry_id=null 占位）
4. **持久化桥接**：`AgentPersistenceHandler` 在 assistant entry 建成后回填 orphan attachment 的 entry_id
5. **下载端点**：`DocumentController.download` 按 `documentId` 返回二进制
6. **前端复用**：message_attachments 挂载后，前端已有附件卡片渲染（Phase 0/1B）+ "AI 可读取"徽标逻辑，零前端改动

**Tech Stack：** Apache POI 5.5.1（XWPF）、SQLite + Flyway、Spring Boot 3 + JdbcTemplate、JUnit 5 + AssertJ + Mockito + ApplicationContextRunner

---

## File Structure

| 文件 | 操作 | 责任 |
|---|---|---|
| `src/main/resources/db/migration/V12__add_documents_table.sql` | 新建 | documents 表 + 索引 |
| `src/main/java/com/lifepilot/document/model/DocumentRecord.java` | 新建 | record：id / sessionId / entryId / fileName / filePath / fileSize / mimeType / origin / createdAt |
| `src/main/java/com/lifepilot/document/repository/DocumentRepository.java` | 新建 | JdbcTemplate：save / findById / findBySessionId / deleteBySessionId |
| `src/main/java/com/lifepilot/document/config/DocumentProperties.java` | 新建（Phase 0 清理后重建） | `@ConfigurationProperties(prefix="lifepilot.document")`，含 `enabled` / `defaultMaxChars` / `storageDir` |
| `src/main/java/com/lifepilot/document/generator/DocumentGenerator.java` | 新建 | 接口：`generate(String markdown, String fileName) -> byte[]` |
| `src/main/java/com/lifepilot/document/generator/MarkdownToDocxGenerator.java` | 新建 | POI XWPF 逐行 markdown → docx |
| `src/main/java/com/lifepilot/document/tool/DocumentCreateDocxToolExecutor.java` | 新建 | `document.create_docx` 工具执行体 |
| `src/main/java/com/lifepilot/document/tool/DocumentToolProvider.java` | 新建（Phase 0 清理后重建） | BuiltinTool 元数据包装 |
| `src/main/java/com/lifepilot/document/config/DocumentAutoConfiguration.java` | 新建（Phase 0 清理后重建） | Spring 装配链条 |
| `src/main/java/com/lifepilot/interaction/web/controller/DocumentController.java` | 新建 | `GET /api/documents/{id}/download` + `GET /api/documents/{id}` 元数据 |
| `src/main/java/com/lifepilot/interaction/web/repository/AttachmentRepository.java` | 修改 | 新增 `backfillOrphanEntryIds(sessionId, entryId)` 回填方法 |
| `src/main/java/com/lifepilot/agent/persistence/AgentPersistenceHandler.java` | 修改 | assistant entry 持久化后调用 `backfillOrphanEntryIds` |
| `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | 修改 | 追加 DocumentAutoConfiguration |
| `src/main/resources/application.yml` | 修改 | `core-tool-ids` 加 `document.create_docx` + `lifepilot.document` 配置节 |
| `src/test/java/com/lifepilot/document/generator/MarkdownToDocxGenerator_基础生成测试.java` | 新建 | 5+ 单元测试 |
| `src/test/java/com/lifepilot/document/tool/DocumentCreateDocxToolExecutor_工具调用测试.java` | 新建 | 4+ 单元测试（Mock Repository） |
| `src/test/java/com/lifepilot/document/repository/DocumentRepository_持久化测试.java` | 新建 | JdbcTemplate 集成测试（H2 或 SQLite 内存模式） |
| `src/test/java/com/lifepilot/interaction/web/controller/DocumentController_下载端点测试.java` | 新建 | MockMvc 端点测试 |

不动：`knowledge/parser/` 任意文件、`FileReadToolExecutor`、前端任意文件（复用现有 message_attachments 渲染）。

---

## Task 1：数据层（Flyway V12 + DocumentRecord + DocumentRepository + DocumentProperties）

**目的**：建 `documents` 表 + JdbcTemplate Repository + 配置属性类，为后续 Task 提供持久化基础。

**Files:**
- Create: `src/main/resources/db/migration/V12__add_documents_table.sql`
- Create: `src/main/java/com/lifepilot/document/model/DocumentRecord.java`
- Create: `src/main/java/com/lifepilot/document/repository/DocumentRepository.java`
- Create: `src/main/java/com/lifepilot/document/config/DocumentProperties.java`
- Test: `src/test/java/com/lifepilot/document/repository/DocumentRepository_持久化测试.java`

**前置阅读：**
- `src/main/java/com/lifepilot/interaction/web/repository/AttachmentRepository.java`（参照 JdbcTemplate 模式 + `AttachmentRecord` record 定义）
- `src/main/resources/db/migration/V11__add_external_cli_bash_path.sql`（Flyway SQL 风格）

- [ ] **Step 1：Flyway V12 迁移**

新建 `src/main/resources/db/migration/V12__add_documents_table.sql`：

```sql
-- Phase 2A: 文档产物元数据表
-- 与 message_attachments 的关系：
--   message_attachments 是 "消息附件挂载"（短生命周期，挂在 transcript entry 上）
--   documents 是 "文档产物资产"（长生命周期，跨会话可查询）
--   AI 生成的 docx 同时出现在两张表：documents 存元数据 + origin 来源，
--   message_attachments 负责让该 docx 在特定消息气泡里可见。

CREATE TABLE IF NOT EXISTS documents (
    id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL,
    entry_id TEXT,
    file_name TEXT NOT NULL,
    file_path TEXT NOT NULL,
    file_size INTEGER NOT NULL,
    mime_type TEXT NOT NULL,
    origin TEXT NOT NULL,
    created_at TEXT NOT NULL,
    FOREIGN KEY (session_id) REFERENCES session_store(session_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_documents_session_id ON documents(session_id);
CREATE INDEX IF NOT EXISTS idx_documents_entry_id ON documents(entry_id);
CREATE INDEX IF NOT EXISTS idx_documents_origin ON documents(origin);
```

- [ ] **Step 2：DocumentRecord 定义**

新建 `src/main/java/com/lifepilot/document/model/DocumentRecord.java`：

```java
package com.lifepilot.document.model;

import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * 文档产物记录。
 *
 * <p>代表本地落盘的一份文档（docx / xlsx / pptx 等），origin 标识来源。
 * 和 message_attachments 的区别：documents 是文档资产的长期元数据，
 * message_attachments 负责把资产挂到特定消息气泡上做 UI 展示。</p>
 *
 * @param id         文档 UUID
 * @param sessionId  所属会话
 * @param entryId    关联的 transcript 条目 ID（assistant 消息），可为 null
 * @param fileName   用户可见文件名（含扩展名）
 * @param filePath   本地绝对路径
 * @param fileSize   文件大小（字节）
 * @param mimeType   MIME
 * @param origin     来源：agent_generated / user_upload / template_rendered
 * @param createdAt  创建时间（ISO 8601）
 * @author zsg
 * @since 2026-04-20
 */
public record DocumentRecord(
        String id,
        String sessionId,
        @Nullable String entryId,
        String fileName,
        String filePath,
        long fileSize,
        String mimeType,
        String origin,
        Instant createdAt
) {

    public static final String ORIGIN_AGENT_GENERATED = "agent_generated";
    public static final String ORIGIN_USER_UPLOAD = "user_upload";
    public static final String ORIGIN_TEMPLATE_RENDERED = "template_rendered";
}
```

- [ ] **Step 3：DocumentProperties**

新建 `src/main/java/com/lifepilot/document/config/DocumentProperties.java`：

```java
package com.lifepilot.document.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 文档工作空间配置属性。
 *
 * <p>Phase 0 曾有同名类（已清理），Phase 2A 重建以承载 storageDir 与生成相关参数。
 * 显式 getter/setter 对齐项目 {@code @ConfigurationProperties} 主流写法（见 MultiAgentProperties）。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
@ConfigurationProperties(prefix = "lifepilot.document")
public class DocumentProperties {

    /** 是否启用文档工作空间，默认 true。 */
    private boolean enabled = true;

    /** document.parse 读取内容时的默认最大字符数（Phase 0 的遗留配置，保留兼容）。 */
    private int defaultMaxChars = 30000;

    /** 文档产物落盘目录，默认 ${zhiwei.data-dir}/documents。 */
    private String storageDir = System.getProperty("user.home") + "/.zhiwei/documents";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getDefaultMaxChars() { return defaultMaxChars; }
    public void setDefaultMaxChars(int defaultMaxChars) { this.defaultMaxChars = defaultMaxChars; }

    public String getStorageDir() { return storageDir; }
    public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
}
```

- [ ] **Step 4：DocumentRepository**

新建 `src/main/java/com/lifepilot/document/repository/DocumentRepository.java`：

```java
package com.lifepilot.document.repository;

import com.lifepilot.document.model.DocumentRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 文档产物元数据访问层。
 *
 * <p>落盘 +入表流程：工具层先 write 文件到 {@code DocumentProperties.storageDir}，
 * 再通过本 Repository {@link #save} 记录元数据。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
@Repository
public class DocumentRepository {

    private static final Logger log = LoggerFactory.getLogger(DocumentRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public DocumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 保存文档元数据。若 record.id 为 null 自动生成 UUID。
     *
     * @return 持久化后的 document id
     */
    public String save(DocumentRecord record) {
        String id = record.id() != null ? record.id() : UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO documents (id, session_id, entry_id, file_name, file_path, file_size, mime_type, origin, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, record.sessionId(), record.entryId(),
                record.fileName(), record.filePath(), record.fileSize(),
                record.mimeType(), record.origin(), record.createdAt().toString());
        log.debug("保存文档：id={}, fileName={}, origin={}", id, record.fileName(), record.origin());
        return id;
    }

    @Nullable
    public DocumentRecord findById(String id) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id, session_id, entry_id, file_name, file_path, file_size, mime_type, origin, created_at " +
                            "FROM documents WHERE id = ?",
                    (rs, rowNum) -> new DocumentRecord(
                            rs.getString("id"),
                            rs.getString("session_id"),
                            rs.getString("entry_id"),
                            rs.getString("file_name"),
                            rs.getString("file_path"),
                            rs.getLong("file_size"),
                            rs.getString("mime_type"),
                            rs.getString("origin"),
                            Instant.parse(rs.getString("created_at"))
                    ),
                    id);
        } catch (EmptyResultDataAccessException e) {
            log.warn("未找到文档：id={}", id);
            return null;
        }
    }

    public List<DocumentRecord> findBySessionId(String sessionId) {
        return jdbcTemplate.query(
                "SELECT id, session_id, entry_id, file_name, file_path, file_size, mime_type, origin, created_at " +
                        "FROM documents WHERE session_id = ? ORDER BY created_at DESC",
                (rs, rowNum) -> new DocumentRecord(
                        rs.getString("id"),
                        rs.getString("session_id"),
                        rs.getString("entry_id"),
                        rs.getString("file_name"),
                        rs.getString("file_path"),
                        rs.getLong("file_size"),
                        rs.getString("mime_type"),
                        rs.getString("origin"),
                        Instant.parse(rs.getString("created_at"))
                ),
                sessionId);
    }

    public int deleteBySessionId(String sessionId) {
        return jdbcTemplate.update("DELETE FROM documents WHERE session_id = ?", sessionId);
    }
}
```

- [ ] **Step 5：DocumentRepository 测试**

新建 `src/test/java/com/lifepilot/document/repository/DocumentRepository_持久化测试.java`：

```java
package com.lifepilot.document.repository;

import com.lifepilot.document.model.DocumentRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentRepository_持久化测试 {

    private EmbeddedDatabase dataSource;
    private DocumentRepository repository;

    @BeforeEach
    void setUp() {
        dataSource = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("docs_test_" + System.nanoTime())
                .build();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE session_store (session_id VARCHAR PRIMARY KEY)");
        jdbc.execute("CREATE TABLE documents (" +
                "id VARCHAR PRIMARY KEY, session_id VARCHAR NOT NULL, entry_id VARCHAR, " +
                "file_name VARCHAR NOT NULL, file_path VARCHAR NOT NULL, file_size BIGINT NOT NULL, " +
                "mime_type VARCHAR NOT NULL, origin VARCHAR NOT NULL, created_at VARCHAR NOT NULL, " +
                "FOREIGN KEY (session_id) REFERENCES session_store(session_id))");
        jdbc.update("INSERT INTO session_store (session_id) VALUES (?)", "sess-1");
        repository = new DocumentRepository(jdbc);
    }

    @Test
    void save_返回持久化的_id() {
        var rec = new DocumentRecord(null, "sess-1", null,
                "Q3 报表.docx", "/tmp/q3.docx", 1024L,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                DocumentRecord.ORIGIN_AGENT_GENERATED, Instant.now());

        String id = repository.save(rec);

        assertThat(id).isNotBlank();
    }

    @Test
    void findById_能取回已保存记录() {
        var rec = new DocumentRecord("doc-1", "sess-1", "entry-9",
                "报告.docx", "/tmp/r.docx", 2048L,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                DocumentRecord.ORIGIN_AGENT_GENERATED, Instant.parse("2026-04-20T10:00:00Z"));
        repository.save(rec);

        var found = repository.findById("doc-1");

        assertThat(found).isNotNull();
        assertThat(found.fileName()).isEqualTo("报告.docx");
        assertThat(found.origin()).isEqualTo("agent_generated");
        assertThat(found.entryId()).isEqualTo("entry-9");
    }

    @Test
    void findById_未找到返回_null() {
        assertThat(repository.findById("missing")).isNull();
    }

    @Test
    void findBySessionId_按创建时间倒序() {
        var r1 = new DocumentRecord("a", "sess-1", null, "a.docx", "/tmp/a", 1L, "x", "agent_generated",
                Instant.parse("2026-04-20T10:00:00Z"));
        var r2 = new DocumentRecord("b", "sess-1", null, "b.docx", "/tmp/b", 1L, "x", "agent_generated",
                Instant.parse("2026-04-20T11:00:00Z"));
        repository.save(r1);
        repository.save(r2);

        var list = repository.findBySessionId("sess-1");

        assertThat(list).hasSize(2);
        assertThat(list.get(0).id()).isEqualTo("b");  // 最新在前
        assertThat(list.get(1).id()).isEqualTo("a");
    }

    @Test
    void deleteBySessionId_级联清理() {
        repository.save(new DocumentRecord("x", "sess-1", null, "x.docx", "/t/x", 1L, "x",
                "agent_generated", Instant.now()));

        int deleted = repository.deleteBySessionId("sess-1");

        assertThat(deleted).isEqualTo(1);
        assertThat(repository.findBySessionId("sess-1")).isEmpty();
    }
}
```

> ⚠️ 本测试用 H2 嵌入式数据库，不依赖项目的 SQLite。H2 Flyway 兼容性好、无 `enable_load_extension` 等 SQLite 特化语法，适合隔离测试。

- [ ] **Step 6：跑测试确认通过**

```bash
mvn -q test -Dtest=DocumentRepository_持久化测试
```

预期：5 个测试全部 PASS。

- [ ] **Step 7：提交**

```bash
git add src/main/resources/db/migration/V12__add_documents_table.sql \
        src/main/java/com/lifepilot/document/ \
        src/test/java/com/lifepilot/document/repository/
git commit -m "$(cat <<'EOF'
feat(document): Phase 2A 数据层 — documents 表 + DocumentRepository + DocumentProperties

Flyway V12 建 documents 表，存文档产物元数据（与 message_attachments 分工：
前者是长生命周期资产，后者挂到消息气泡做 UI 展示）。DocumentRecord record
含 origin 字段区分 agent_generated / user_upload / template_rendered。
JdbcTemplate Repository 提供 save / findById / findBySessionId / deleteBySessionId。
DocumentProperties 重建（Phase 0 清理后），含 enabled / defaultMaxChars / storageDir，
显式 getter/setter 对齐项目 @ConfigurationProperties 主流风格。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2：MarkdownToDocxGenerator — POI XWPF 逐行生成

**目的**：实现 markdown → docx 渲染器，支持标题 1/2/3 级 + 段落 + 无序列表 + 有序列表，不依赖外部 markdown 库（自己逐行解析，Phase 2A 首版够用，不支持表格/内联格式/代码块 —— 延 Phase 2A+ 按需扩展）。

**Files:**
- Create: `src/main/java/com/lifepilot/document/generator/DocumentGenerator.java`（接口）
- Create: `src/main/java/com/lifepilot/document/generator/MarkdownToDocxGenerator.java`
- Test: `src/test/java/com/lifepilot/document/generator/MarkdownToDocxGenerator_基础生成测试.java`

**前置阅读：**
- `src/main/java/com/lifepilot/knowledge/parser/WordParser.java`（反向参照 —— WordParser 读 docx，这里写 docx，POI XWPF API 一致）

- [ ] **Step 1：DocumentGenerator 接口**

新建 `src/main/java/com/lifepilot/document/generator/DocumentGenerator.java`：

```java
package com.lifepilot.document.generator;

/**
 * 文档生成器契约 —— Phase 2A 单 docx，Phase 2B 扩展 xlsx / pptx。
 *
 * @author zsg
 * @since 2026-04-20
 */
public interface DocumentGenerator {

    /**
     * 从 markdown 生成文档字节。
     *
     * @param markdown 源 markdown 文本
     * @return 生成的文件字节
     */
    byte[] generate(String markdown);

    /** 返回生成器支持的 MIME 类型。 */
    String mimeType();
}
```

- [ ] **Step 2：写失败测试**

新建 `src/test/java/com/lifepilot/document/generator/MarkdownToDocxGenerator_基础生成测试.java`：

```java
package com.lifepilot.document.generator;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownToDocxGenerator_基础生成测试 {

    private final MarkdownToDocxGenerator generator = new MarkdownToDocxGenerator();

    @Test
    void mimeType_返回_wordprocessingml() {
        assertThat(generator.mimeType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    @Test
    void 一级标题与段落能被回读到() throws Exception {
        String md = "# Phase 2A 验证报告\n\n这是一段正文，含关键词 海豚登月计划。";

        byte[] bytes = generator.generate(md);

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            String allText = doc.getParagraphs().stream()
                    .map(p -> p.getText())
                    .collect(Collectors.joining("\n"));
            assertThat(allText).contains("Phase 2A 验证报告");
            assertThat(allText).contains("海豚登月计划");
        }
    }

    @Test
    void 二级三级标题都能识别() throws Exception {
        String md = "# 一级\n\n## 二级\n\n### 三级\n\n正文";

        byte[] bytes = generator.generate(md);

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            // 标题段落 style 应该不是 Normal
            var headingStyles = doc.getParagraphs().stream()
                    .map(p -> p.getStyle())
                    .filter(s -> s != null && s.toLowerCase().contains("heading"))
                    .toList();
            assertThat(headingStyles).hasSizeGreaterThanOrEqualTo(3);
        }
    }

    @Test
    void 无序列表条目保留文本(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        String md = "# 清单\n\n- 第一项\n- 第二项\n- 第三项\n";

        byte[] bytes = generator.generate(md);

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            String all = doc.getParagraphs().stream().map(p -> p.getText())
                    .collect(Collectors.joining("\n"));
            assertThat(all).contains("第一项");
            assertThat(all).contains("第二项");
            assertThat(all).contains("第三项");
        }
    }

    @Test
    void 有序列表条目保留文本() throws Exception {
        String md = "# 步骤\n\n1. 初始化\n2. 运行\n3. 收尾\n";

        byte[] bytes = generator.generate(md);

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            String all = doc.getParagraphs().stream().map(p -> p.getText())
                    .collect(Collectors.joining("\n"));
            assertThat(all).contains("初始化");
            assertThat(all).contains("运行");
            assertThat(all).contains("收尾");
        }
    }

    @Test
    void 空_markdown_生成最小可读的_docx() throws Exception {
        byte[] bytes = generator.generate("");

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            // 不抛异常就算通过；POI 读得出 XWPFDocument 说明是合法 docx
            assertThat(doc.getParagraphs()).isNotNull();
        }
    }
}
```

- [ ] **Step 3：跑测试确认失败**

```bash
mvn -q test -Dtest=MarkdownToDocxGenerator_基础生成测试
```

预期：编译失败（`MarkdownToDocxGenerator` 不存在）。

- [ ] **Step 4：实现 MarkdownToDocxGenerator**

新建 `src/main/java/com/lifepilot/document/generator/MarkdownToDocxGenerator.java`：

```java
package com.lifepilot.document.generator;

import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown → DOCX 生成器 —— 基于 Apache POI XWPF。
 *
 * <p>Phase 2A 首版支持的语法：
 * <ul>
 *   <li>{@code # / ## / ###} 一到三级标题</li>
 *   <li>{@code - } / {@code * } 无序列表</li>
 *   <li>{@code 1. } 有序列表</li>
 *   <li>普通段落（空行分段）</li>
 * </ul>
 * 不支持：表格、代码块、内联 bold/italic/link、图片 —— 延 Phase 2A+ 按需扩展。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
public class MarkdownToDocxGenerator implements DocumentGenerator {

    private static final Logger log = LoggerFactory.getLogger(MarkdownToDocxGenerator.class);
    private static final String MIME_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final Pattern ORDERED_LIST = Pattern.compile("^\\d+\\.\\s+(.+)$");
    private static final Pattern HEADING_L1 = Pattern.compile("^#\\s+(.+)$");
    private static final Pattern HEADING_L2 = Pattern.compile("^##\\s+(.+)$");
    private static final Pattern HEADING_L3 = Pattern.compile("^###\\s+(.+)$");
    private static final Pattern UNORDERED_LIST = Pattern.compile("^[-*]\\s+(.+)$");

    @Override
    public String mimeType() {
        return MIME_TYPE;
    }

    @Override
    public byte[] generate(String markdown) {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            if (markdown == null || markdown.isBlank()) {
                // 空 markdown 也生成最小有效 docx
                doc.createParagraph();
                doc.write(out);
                return out.toByteArray();
            }

            int orderedIdx = 0;
            boolean lastWasOrdered = false;

            for (String rawLine : markdown.split("\\R", -1)) {
                String line = rawLine.stripTrailing();

                if (line.isBlank()) {
                    // 空行作为段落分隔：有序列表序号重置
                    lastWasOrdered = false;
                    orderedIdx = 0;
                    continue;
                }

                Matcher h3 = HEADING_L3.matcher(line);
                Matcher h2 = HEADING_L2.matcher(line);
                Matcher h1 = HEADING_L1.matcher(line);
                Matcher ol = ORDERED_LIST.matcher(line);
                Matcher ul = UNORDERED_LIST.matcher(line);

                if (h3.matches()) {
                    addHeading(doc, h3.group(1), 3);
                    lastWasOrdered = false;
                } else if (h2.matches()) {
                    addHeading(doc, h2.group(1), 2);
                    lastWasOrdered = false;
                } else if (h1.matches()) {
                    addHeading(doc, h1.group(1), 1);
                    lastWasOrdered = false;
                } else if (ol.matches()) {
                    if (!lastWasOrdered) {
                        orderedIdx = 0;
                    }
                    orderedIdx++;
                    addListItem(doc, orderedIdx + ". " + ol.group(1));
                    lastWasOrdered = true;
                } else if (ul.matches()) {
                    addListItem(doc, "• " + ul.group(1));
                    lastWasOrdered = false;
                } else {
                    addParagraph(doc, line);
                    lastWasOrdered = false;
                }
            }

            doc.write(out);
            log.info("MarkdownToDocx 生成完成：inputChars={}, outputBytes={}",
                    markdown.length(), out.size());
            return out.toByteArray();

        } catch (IOException e) {
            throw new DocumentGenerationException("MarkdownToDocx 生成失败：" + e.getMessage(), e);
        }
    }

    private void addHeading(XWPFDocument doc, String text, int level) {
        XWPFParagraph p = doc.createParagraph();
        p.setStyle("Heading" + level);
        p.setAlignment(ParagraphAlignment.LEFT);
        XWPFRun run = p.createRun();
        run.setText(text);
        run.setBold(true);
        switch (level) {
            case 1 -> run.setFontSize(20);
            case 2 -> run.setFontSize(16);
            default -> run.setFontSize(14);
        }
    }

    private void addParagraph(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        XWPFRun run = p.createRun();
        run.setText(text);
        run.setFontSize(11);
    }

    private void addListItem(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setIndentationLeft(360);  // 约等于 0.25 inch
        XWPFRun run = p.createRun();
        run.setText(text);
        run.setFontSize(11);
    }
}
```

新建配套的异常类 `src/main/java/com/lifepilot/document/generator/DocumentGenerationException.java`：

```java
package com.lifepilot.document.generator;

/**
 * 文档生成失败异常。
 *
 * @author zsg
 * @since 2026-04-20
 */
public class DocumentGenerationException extends RuntimeException {
    public DocumentGenerationException(String message, Throwable cause) {
        super(message, cause);
    }

    public DocumentGenerationException(String message) {
        super(message);
    }
}
```

- [ ] **Step 5：跑测试确认通过**

```bash
mvn -q test -Dtest=MarkdownToDocxGenerator_基础生成测试
```

预期：6 个测试全部 PASS。

> ⚠️ **Heading style 依赖**：POI `setStyle("Heading1")` 依赖 docx 模板含对应 style 定义。如果 style 不存在，标题仍会创建但回退到默认样式 —— 这是为什么测试 3（二级三级标题都能识别）要用 `contains("heading")` 过滤的原因。如果 POI 5.5.1 对新建 XWPFDocument 没有 heading style 则 `p.getStyle()` 可能为 null 或空，测试会失败 —— 此时要么 (a) 先加载 POI 默认 XDocx template，要么 (b) 换断言为"标题段落的字号更大"。实施时先按现有代码跑，如果测试 3 失败改 (b) 方案断言。

- [ ] **Step 6：提交**

```bash
git add src/main/java/com/lifepilot/document/generator/ \
        src/test/java/com/lifepilot/document/generator/
git commit -m "$(cat <<'EOF'
feat(document): MarkdownToDocxGenerator — Phase 2A docx 生成核心

逐行处理 markdown 支持一到三级标题、有序/无序列表、普通段落，
不依赖外部 markdown 库。DocumentGenerator 接口为 Phase 2B 的 xlsx/pptx
生成器预留扩展点。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3：AttachmentRepository 扩展 orphan 回填 + AgentPersistenceHandler 挂钩

**目的**：Tool 执行时 entry_id 还没生成（assistant entry 还没持久化），Tool 先入 message_attachments 用 `entry_id=null`；assistant entry 建成后由 AgentPersistenceHandler 回填 entry_id，让前端能在消息气泡渲染附件。

**Files:**
- Modify: `src/main/java/com/lifepilot/interaction/web/repository/AttachmentRepository.java`（新增 `backfillOrphanEntryIds` 方法）
- Modify: `src/main/java/com/lifepilot/agent/persistence/AgentPersistenceHandler.java`（assistant 持久化后调用回填）
- Test: `src/test/java/com/lifepilot/interaction/web/repository/AttachmentRepository_孤儿回填测试.java`

- [ ] **Step 1：写失败测试**

新建 `src/test/java/com/lifepilot/interaction/web/repository/AttachmentRepository_孤儿回填测试.java`：

```java
package com.lifepilot.interaction.web.repository;

import com.lifepilot.interaction.web.repository.AttachmentRepository.AttachmentRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import static org.assertj.core.api.Assertions.assertThat;

class AttachmentRepository_孤儿回填测试 {

    private EmbeddedDatabase dataSource;
    private AttachmentRepository repository;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        dataSource = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("att_test_" + System.nanoTime())
                .build();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE session_store (session_id VARCHAR PRIMARY KEY)");
        jdbc.execute("CREATE TABLE message_attachments (" +
                "id VARCHAR PRIMARY KEY, entry_id VARCHAR, session_id VARCHAR NOT NULL, " +
                "file_name VARCHAR, file_path VARCHAR, file_size BIGINT, mime_type VARCHAR, " +
                "url VARCHAR, created_at VARCHAR)");
        jdbc.update("INSERT INTO session_store (session_id) VALUES (?)", "sess-1");
        repository = new AttachmentRepository(jdbc);
    }

    @Test
    void backfillOrphanEntryIds_把_session_内_entry_id_null_的记录填上新_entry_id() {
        repository.saveForEntry(null, "sess-1", "a.docx", "/t/a", 1L, "x", "/u/a");
        repository.saveForEntry(null, "sess-1", "b.docx", "/t/b", 2L, "x", "/u/b");

        int updated = repository.backfillOrphanEntryIds("sess-1", "entry-new");

        assertThat(updated).isEqualTo(2);
        Integer remainingNull = jdbc.queryForObject(
                "SELECT COUNT(*) FROM message_attachments WHERE session_id = ? AND entry_id IS NULL",
                Integer.class, "sess-1");
        assertThat(remainingNull).isZero();
    }

    @Test
    void backfillOrphanEntryIds_不影响其他_session() {
        jdbc.update("INSERT INTO session_store (session_id) VALUES (?)", "sess-2");
        repository.saveForEntry(null, "sess-1", "a.docx", "/t/a", 1L, "x", "/u/a");
        repository.saveForEntry(null, "sess-2", "b.docx", "/t/b", 1L, "x", "/u/b");

        repository.backfillOrphanEntryIds("sess-1", "entry-new");

        Integer sess2Null = jdbc.queryForObject(
                "SELECT COUNT(*) FROM message_attachments WHERE session_id = ? AND entry_id IS NULL",
                Integer.class, "sess-2");
        assertThat(sess2Null).isEqualTo(1);  // sess-2 的 orphan 不被回填
    }

    @Test
    void backfillOrphanEntryIds_不覆盖已有_entry_id() {
        repository.saveForEntry("entry-existing", "sess-1", "a.docx", "/t/a", 1L, "x", "/u/a");
        repository.saveForEntry(null, "sess-1", "b.docx", "/t/b", 2L, "x", "/u/b");

        repository.backfillOrphanEntryIds("sess-1", "entry-new");

        // 只有 orphan 被填，不动已有 entry_id
        Integer underExisting = jdbc.queryForObject(
                "SELECT COUNT(*) FROM message_attachments WHERE entry_id = ?",
                Integer.class, "entry-existing");
        Integer underNew = jdbc.queryForObject(
                "SELECT COUNT(*) FROM message_attachments WHERE entry_id = ?",
                Integer.class, "entry-new");
        assertThat(underExisting).isEqualTo(1);
        assertThat(underNew).isEqualTo(1);
    }
}
```

- [ ] **Step 2：跑测试确认失败**

```bash
mvn -q test -Dtest=AttachmentRepository_孤儿回填测试
```

预期：编译失败（`backfillOrphanEntryIds` 不存在）。

- [ ] **Step 3：实现 backfillOrphanEntryIds**

打开 `src/main/java/com/lifepilot/interaction/web/repository/AttachmentRepository.java`，找一个合适位置（如 `deleteBySessionId` 附近）新增方法：

```java
    /**
     * 回填孤儿附件的 entry_id —— 用于 Tool 生成产物时先入 entry_id=null 的场景，
     * Assistant entry 持久化后调用本方法把本会话所有 orphan 的 attachment 挂到该 entry。
     *
     * <p>仅影响 {@code entry_id IS NULL} 的记录，不覆盖已挂载的。
     * 设计假设：本方法在 assistant entry 刚持久化后立即调用，此时 session 内
     * 同轮 turn 的 orphan 仅来自该 turn 的工具产物。如果出现跨轮 orphan 遗留
     * （极少），也会被无害地挂到本轮 —— 可接受的近似，Phase 3 编辑链路引入
     * 更严格关联时可以加 turn_id 过滤。</p>
     *
     * @param sessionId 会话 ID
     * @param entryId   目标 assistant entry ID
     * @return 回填行数
     */
    public int backfillOrphanEntryIds(String sessionId, String entryId) {
        int rows = jdbcTemplate.update(
                "UPDATE message_attachments SET entry_id = ? " +
                        "WHERE session_id = ? AND entry_id IS NULL",
                entryId, sessionId);
        if (rows > 0) {
            log.debug("回填孤儿附件 entry_id：sessionId={}, entryId={}, rows={}",
                    sessionId, entryId, rows);
        }
        return rows;
    }
```

- [ ] **Step 4：跑测试确认通过**

```bash
mvn -q test -Dtest=AttachmentRepository_孤儿回填测试
```

预期：3 个测试 PASS。

- [ ] **Step 5：AgentPersistenceHandler 调用回填**

打开 `src/main/java/com/lifepilot/agent/persistence/AgentPersistenceHandler.java`，找到 `persistAssistantReplyReturningId`（或 `persistToolMediaAttachments` 附近的 assistant 持久化完成点 —— 约 L340 附近）。

在 assistant entryId 已建立后追加回填调用。具体位置看实际代码：最自然是在 `persistToolMediaAttachments` 方法体末尾再加一行回填（既已在处理 assistant-attached 资产）—— 或者在更外层 `persistAssistantReply`。

**实施时参考**：

1. 找 `persistToolMediaAttachments(assistantEntryId, sessionId, toolMediaItems)` 方法
2. 方法执行完 tool media 持久化后，增加一段：

```java
        // Phase 2A：回填本会话内由 Tool 生成的孤儿附件（如 document.create_docx 产物）
        if (attachmentRepository != null && assistantEntryId != null && sessionId != null) {
            int backfilled = attachmentRepository.backfillOrphanEntryIds(sessionId, assistantEntryId);
            if (backfilled > 0) {
                log.debug("Assistant entry 回填 orphan 附件：entryId={}, count={}",
                        assistantEntryId, backfilled);
            }
        }
```

放在 `persistToolMediaAttachments` 方法的 for 循环之后、方法闭括号之前。这样即使 toolMediaItems 为空（Phase 2A 不走 MediaDataExtractor 路径），回填也会执行。

- [ ] **Step 6：跑相关测试确保不破坏**

```bash
mvn -q test -Dtest='AttachmentRepository*,AgentPersistenceHandler*'
```

预期：所有相关测试 PASS。

- [ ] **Step 7：提交**

```bash
git add src/main/java/com/lifepilot/interaction/web/repository/AttachmentRepository.java \
        src/main/java/com/lifepilot/agent/persistence/AgentPersistenceHandler.java \
        src/test/java/com/lifepilot/interaction/web/repository/AttachmentRepository_孤儿回填测试.java
git commit -m "$(cat <<'EOF'
feat(attachment): 孤儿附件回填 — 支持 Tool 产物挂到 assistant 消息

Tool 生成产物（如 document.create_docx 输出 docx）时 assistant entry 还未建立，
附件以 entry_id=null 入库；AgentPersistenceHandler 持久化 assistant entry 后
调用 backfillOrphanEntryIds 把本 session 所有孤儿附件挂到新 entry。
最小侵入：只更新 NULL entry_id，不覆盖已挂载记录，不跨 session 影响。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4：DocumentCreateDocxToolExecutor — 工具执行体

**目的**：把"生成 → 落盘 → 入两张表 → 返回工具结果"完整链路实现为单个工具执行体。

**Files:**
- Create: `src/main/java/com/lifepilot/document/tool/DocumentCreateDocxToolExecutor.java`
- Test: `src/test/java/com/lifepilot/document/tool/DocumentCreateDocxToolExecutor_工具调用测试.java`

**前置阅读：**
- `src/main/java/com/lifepilot/meta/infra/file/FileReadToolExecutor.java`（参照工具 executor 结构）

- [ ] **Step 1：写失败测试**

新建 `src/test/java/com/lifepilot/document/tool/DocumentCreateDocxToolExecutor_工具调用测试.java`：

```java
package com.lifepilot.document.tool;

import com.lifepilot.document.generator.MarkdownToDocxGenerator;
import com.lifepilot.document.model.DocumentRecord;
import com.lifepilot.document.repository.DocumentRepository;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentCreateDocxToolExecutor_工具调用测试 {

    @Mock DocumentRepository documentRepository;
    @Mock AttachmentRepository attachmentRepository;

    @Test
    void 成功生成_docx_落盘入两张表返回_downloadUrl(@TempDir Path tmp) {
        when(documentRepository.save(any())).thenReturn("doc-1");
        when(attachmentRepository.saveForEntry(any(), anyString(), anyString(), anyString(),
                any(Long.class), anyString(), anyString())).thenReturn("att-1");

        var executor = newExecutor(tmp);
        var input = newInput(Map.of(
                "fileName", "报告",
                "markdown", "# 报告标题\n\n正文内容",
                "sessionId", "sess-1"
        ));

        ToolResult result = executor.execute(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.data()).containsEntry("documentId", "doc-1");
        assertThat((String) result.data().get("fileName")).endsWith(".docx");
        assertThat((String) result.data().get("downloadUrl")).isEqualTo("/api/documents/doc-1/download");
        assertThat(result.data()).containsKey("fileSize");

        // 落盘文件真的存在
        ArgumentCaptor<DocumentRecord> recordCaptor = ArgumentCaptor.forClass(DocumentRecord.class);
        verify(documentRepository).save(recordCaptor.capture());
        DocumentRecord saved = recordCaptor.getValue();
        assertThat(Files.exists(Path.of(saved.filePath()))).isTrue();
        assertThat(saved.origin()).isEqualTo("agent_generated");
        assertThat(saved.sessionId()).isEqualTo("sess-1");
        assertThat(saved.entryId()).isNull();  // Tool 执行时 entry_id 还没建
    }

    @Test
    void 缺少_fileName_参数报错(@TempDir Path tmp) {
        var executor = newExecutor(tmp);
        var input = newInput(Map.of(
                "markdown", "# 标题",
                "sessionId", "sess-1"
        ));

        ToolResult result = executor.execute(input);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("fileName");
    }

    @Test
    void 缺少_markdown_参数报错(@TempDir Path tmp) {
        var executor = newExecutor(tmp);
        var input = newInput(Map.of(
                "fileName", "报告",
                "sessionId", "sess-1"
        ));

        ToolResult result = executor.execute(input);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("markdown");
    }

    @Test
    void 缺少_sessionId_上下文报错(@TempDir Path tmp) {
        var executor = newExecutor(tmp);
        var input = newInput(Map.of(
                "fileName", "报告",
                "markdown", "# 标题"
        ));

        ToolResult result = executor.execute(input);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("sessionId");
    }

    @Test
    void fileName_自动追加_docx_扩展名(@TempDir Path tmp) {
        when(documentRepository.save(any())).thenReturn("doc-2");
        when(attachmentRepository.saveForEntry(any(), anyString(), anyString(), anyString(),
                any(Long.class), anyString(), anyString())).thenReturn("att-2");

        var executor = newExecutor(tmp);
        var input = newInput(Map.of(
                "fileName", "已带扩展名.docx",
                "markdown", "# 内容",
                "sessionId", "sess-1"
        ));

        ToolResult result = executor.execute(input);

        assertThat(result.isSuccess()).isTrue();
        // 不重复加 .docx
        assertThat((String) result.data().get("fileName")).isEqualTo("已带扩展名.docx");
    }

    private DocumentCreateDocxToolExecutor newExecutor(Path storageDir) {
        return new DocumentCreateDocxToolExecutor(
                new MarkdownToDocxGenerator(),
                documentRepository,
                attachmentRepository,
                storageDir.toString());
    }

    private ToolInput newInput(Map<String, Object> params) {
        return new ToolInput("document.create_docx", params,
                JsonSchema.of(Map.of("type", "object")), null, null);
    }
}
```

- [ ] **Step 2：跑测试确认失败**

```bash
mvn -q test -Dtest=DocumentCreateDocxToolExecutor_工具调用测试
```

预期：编译失败。

- [ ] **Step 3：实现 DocumentCreateDocxToolExecutor**

新建 `src/main/java/com/lifepilot/document/tool/DocumentCreateDocxToolExecutor.java`：

```java
package com.lifepilot.document.tool;

import com.lifepilot.document.generator.DocumentGenerationException;
import com.lifepilot.document.generator.DocumentGenerator;
import com.lifepilot.document.model.DocumentRecord;
import com.lifepilot.document.repository.DocumentRepository;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * document.create_docx 工具执行体。
 *
 * <p>流程：接收 {@code fileName + markdown + sessionId} → 调用 DocumentGenerator
 * 生成 docx 字节 → 落盘到 {@code storageDir}/{uuid}_{fileName} → 入 documents 表
 * （origin=agent_generated） + 入 message_attachments（entry_id=null 占位，由
 * AgentPersistenceHandler 后续回填）→ 返回 documentId / fileName / fileSize / downloadUrl。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
public class DocumentCreateDocxToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(DocumentCreateDocxToolExecutor.class);
    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String DOCX_EXT = ".docx";

    private final DocumentGenerator generator;
    private final DocumentRepository documentRepository;
    private final AttachmentRepository attachmentRepository;
    private final String storageDir;

    public DocumentCreateDocxToolExecutor(DocumentGenerator generator,
                                          DocumentRepository documentRepository,
                                          AttachmentRepository attachmentRepository,
                                          String storageDir) {
        this.generator = generator;
        this.documentRepository = documentRepository;
        this.attachmentRepository = attachmentRepository;
        this.storageDir = storageDir;
    }

    public ToolResult execute(ToolInput input) {
        String fileName;
        String markdown;
        String sessionId;
        try {
            fileName = input.getParam("fileName", String.class);
            markdown = input.getParam("markdown", String.class);
            sessionId = input.getParam("sessionId", String.class);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("参数校验失败：" + e.getMessage());
        }

        // fileName 自动追加 .docx（已带则不追加）
        String normalizedFileName = fileName.toLowerCase().endsWith(DOCX_EXT)
                ? fileName : fileName + DOCX_EXT;

        // 生成字节
        byte[] bytes;
        try {
            bytes = generator.generate(markdown);
        } catch (DocumentGenerationException e) {
            log.warn("docx 生成失败：fileName={}, error={}", normalizedFileName, e.getMessage());
            return ToolResult.error("文档生成失败：" + e.getMessage());
        }

        // 落盘
        Path storageRoot = Paths.get(storageDir);
        String documentId = UUID.randomUUID().toString();
        String storedName = documentId + "_" + normalizedFileName;
        Path filePath = storageRoot.resolve(storedName);
        try {
            Files.createDirectories(storageRoot);
            Files.write(filePath, bytes);
        } catch (IOException e) {
            log.error("docx 落盘失败：filePath={}", filePath, e);
            return ToolResult.error("文档落盘失败：" + e.getMessage());
        }

        // 入 documents 表
        var record = new DocumentRecord(
                documentId, sessionId, null, normalizedFileName, filePath.toString(),
                bytes.length, DOCX_MIME, DocumentRecord.ORIGIN_AGENT_GENERATED, Instant.now());
        documentRepository.save(record);

        // 入 message_attachments 表（entry_id=null，由 AgentPersistenceHandler 回填）
        String downloadUrl = "/api/documents/" + documentId + "/download";
        attachmentRepository.saveForEntry(
                null, sessionId, normalizedFileName, filePath.toString(),
                (long) bytes.length, DOCX_MIME, downloadUrl);

        log.info("document.create_docx 成功：documentId={}, fileName={}, size={}",
                documentId, normalizedFileName, bytes.length);

        var data = new LinkedHashMap<String, Object>();
        data.put("documentId", documentId);
        data.put("fileName", normalizedFileName);
        data.put("fileSize", (long) bytes.length);
        data.put("downloadUrl", downloadUrl);
        return ToolResult.success(Map.copyOf(data));
    }
}
```

- [ ] **Step 4：跑测试确认通过**

```bash
mvn -q test -Dtest=DocumentCreateDocxToolExecutor_工具调用测试
```

预期：5 个测试 PASS。

- [ ] **Step 5：提交**

```bash
git add src/main/java/com/lifepilot/document/tool/DocumentCreateDocxToolExecutor.java \
        src/test/java/com/lifepilot/document/tool/DocumentCreateDocxToolExecutor_工具调用测试.java
git commit -m "$(cat <<'EOF'
feat(document): DocumentCreateDocxToolExecutor — document.create_docx 执行体

接收 fileName + markdown + sessionId，生成 docx → 落盘 storageDir →
入 documents 表（origin=agent_generated）+ 入 message_attachments（entry_id=null）→
返回 documentId / fileSize / downloadUrl。entry_id 由 AgentPersistenceHandler
后续回填挂到 assistant 消息。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5：DocumentToolProvider + DocumentAutoConfiguration 装配

**目的**：重建 Phase 0 清理掉的 DocumentToolProvider / DocumentAutoConfiguration，装配 Phase 2A 的全部 Bean 链条 + 注册 `document.create_docx` 工具 + 扩展 `core-tool-ids` + application.yml 配置节。

**Files:**
- Create: `src/main/java/com/lifepilot/document/tool/DocumentToolProvider.java`
- Create: `src/main/java/com/lifepilot/document/config/DocumentAutoConfiguration.java`
- Modify: `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Modify: `src/main/resources/application.yml`

**前置阅读：**
- `src/main/java/com/lifepilot/meta/infra/file/FileToolProvider.java`（BuiltinTool 构建参考）

- [ ] **Step 1：DocumentToolProvider**

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
 * 文档工具提供者 —— Phase 2A 含 document.create_docx。
 *
 * <p>Phase 0 曾因工具合并而清理此类，Phase 2A 重建用于承载 create_* 工具族。
 * Phase 2B 将扩展 create_xlsx / create_pptx。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
public class DocumentToolProvider {

    private static final List<String> DOCUMENT_TAGS = List.of("infrastructure", "document");

    private final DocumentCreateDocxToolExecutor createDocxExecutor;

    public DocumentToolProvider(DocumentCreateDocxToolExecutor createDocxExecutor) {
        this.createDocxExecutor = createDocxExecutor;
    }

    public List<BuiltinTool> buildDocumentTools() {
        return List.of(buildCreateDocxTool());
    }

    private BuiltinTool buildCreateDocxTool() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("fileName", Map.of("type", "string",
                "description", "产物文件名（不含扩展名会自动追加 .docx）"));
        properties.put("markdown", Map.of("type", "string",
                "description", "文档正文的 Markdown 源。支持一到三级标题（# ## ###）、" +
                        "无序列表（- / *）、有序列表（1. / 2.）和普通段落。" +
                        "当前不支持表格、代码块、内联格式与图片。"));

        return BuiltinTool.builder()
                .id("document.create_docx")
                .category(ToolCategory.ACTION)
                .name("生成 Word 文档")
                .description("从 markdown 生成 Word 文档（.docx）并保存到本地 documents 目录。" +
                        "产物自动挂到当前 assistant 消息附件上并提供下载 URL。" +
                        "适用于生成周报 / 报告 / 简短方案等不要求复杂排版的文档。" +
                        "产物样式为基础级（标题 + 段落 + 列表，无表格）。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("fileName", "markdown"),
                        "properties", properties
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.WRITE_FILE,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.pathTrees()
                ))
                .tags(DOCUMENT_TAGS)
                .executor(createDocxExecutor::execute)
                .build();
    }
}
```

- [ ] **Step 2：DocumentAutoConfiguration**

新建 `src/main/java/com/lifepilot/document/config/DocumentAutoConfiguration.java`：

```java
package com.lifepilot.document.config;

import com.lifepilot.document.generator.DocumentGenerator;
import com.lifepilot.document.generator.MarkdownToDocxGenerator;
import com.lifepilot.document.repository.DocumentRepository;
import com.lifepilot.document.tool.DocumentCreateDocxToolExecutor;
import com.lifepilot.document.tool.DocumentToolProvider;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.tool.BuiltinTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 文档工作空间自动配置 —— Phase 2A 重建。
 *
 * <p>Phase 0 曾有同名 AutoConfiguration（文档解析合并到 file.read 后清理），
 * Phase 2A 重建以装配 create_* 工具链条。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
@AutoConfiguration
@ConditionalOnProperty(name = "lifepilot.document.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(DocumentProperties.class)
public class DocumentAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DocumentAutoConfiguration.class);

    @Bean
    DocumentGenerator markdownToDocxGenerator() {
        return new MarkdownToDocxGenerator();
    }

    @Bean
    @ConditionalOnBean({DocumentRepository.class, AttachmentRepository.class})
    DocumentCreateDocxToolExecutor documentCreateDocxToolExecutor(
            DocumentGenerator markdownToDocxGenerator,
            DocumentRepository documentRepository,
            AttachmentRepository attachmentRepository,
            DocumentProperties properties) {
        return new DocumentCreateDocxToolExecutor(
                markdownToDocxGenerator,
                documentRepository,
                attachmentRepository,
                properties.getStorageDir());
    }

    @Bean
    @ConditionalOnBean(DocumentCreateDocxToolExecutor.class)
    DocumentToolProvider documentToolProvider(DocumentCreateDocxToolExecutor executor) {
        return new DocumentToolProvider(executor);
    }

    @Bean
    @ConditionalOnBean(DocumentToolProvider.class)
    BuiltinTool documentCreateDocxTool(DocumentToolProvider provider) {
        var tools = provider.buildDocumentTools();
        if (tools.isEmpty()) {
            throw new IllegalStateException("DocumentToolProvider 未返回任何工具");
        }
        if (tools.size() != 1) {
            log.warn("DocumentToolProvider 返回 {} 个工具，Phase 2A 预期 1 个（document.create_docx）", tools.size());
        }
        var tool = tools.get(0);
        log.info("已装配 document 工具：id={}", tool.id());
        return tool;
    }
}
```

- [ ] **Step 3：注册 AutoConfiguration.imports**

编辑 `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`，在 `KnowledgeRuntimeAutoConfiguration` 附近追加：

```
com.lifepilot.document.config.DocumentAutoConfiguration
```

- [ ] **Step 4：core-tool-ids 加 `document.create_docx`**

编辑 `src/main/resources/application.yml`，在 `core-tool-ids` 列表追加：

```yaml
    core-tool-ids:
      - shell.exec
      - web.search
      - shell.process
      - web.fetch
      - file.read            # 统一读路径：纯文本 + docx/pdf/md/csv 等结构化文档 + 附件（attachmentId）
      - file.write
      - file.list
      - memory
      - knowledge.search
      - notify
      - document.create_docx # Phase 2A：从 markdown 生成 docx 产物，落盘 + 挂到 assistant 消息
```

如果 `lifepilot.document` 节没有 storageDir 等配置，加一节（可选 —— DocumentProperties 有默认值）：

```yaml
  document:
    enabled: true
    # storage-dir: "${zhiwei.data-dir}/documents"  # 默认值，不需要显式配置
```

- [ ] **Step 5：编译验证**

```bash
mvn -q -DskipTests compile
```

预期：BUILD SUCCESS。

- [ ] **Step 6：跑相关测试确保回归**

```bash
mvn -q test -Dtest='Document*,*Attachment*'
```

预期：所有 Phase 2A 测试 + 回归测试 PASS。

- [ ] **Step 7：提交**

```bash
git add src/main/java/com/lifepilot/document/tool/DocumentToolProvider.java \
        src/main/java/com/lifepilot/document/config/DocumentAutoConfiguration.java \
        src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports \
        src/main/resources/application.yml
git commit -m "$(cat <<'EOF'
feat(document): DocumentToolProvider + AutoConfiguration 装配 create_docx

重建 Phase 0 清理掉的 DocumentToolProvider / DocumentAutoConfiguration 用于承载
create_* 工具族。@ConditionalOnBean 级联保证 Web 未启用时优雅降级。
core-tool-ids 追加 document.create_docx 让默认 ReAct 可见此工具。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6：DocumentController 下载端点

**目的**：提供 `GET /api/documents/{id}/download` 端点按 documentId 直接返回文件二进制，前端消息气泡的下载按钮点击此 URL 即可下载。

**Files:**
- Create: `src/main/java/com/lifepilot/interaction/web/controller/DocumentController.java`
- Test: `src/test/java/com/lifepilot/interaction/web/controller/DocumentController_下载端点测试.java`

**前置阅读：**
- `src/main/java/com/lifepilot/interaction/web/controller/AttachmentController.java`（参照端点实现与 ByteArrayResource 下载模式）

- [ ] **Step 1：DocumentController**

新建 `src/main/java/com/lifepilot/interaction/web/controller/DocumentController.java`：

```java
package com.lifepilot.interaction.web.controller;

import com.lifepilot.document.model.DocumentRecord;
import com.lifepilot.document.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文档产物访问控制器。
 *
 * <p>仅在 Web 通道启用时装载（参照 AttachmentController 的 @ConditionalOnProperty）。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
@RestController
@RequestMapping("/api/documents")
@ConditionalOnProperty(name = "lifepilot.gateway.channels.web.enabled", havingValue = "true")
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private final DocumentRepository documentRepository;

    public DocumentController(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<ByteArrayResource> download(@PathVariable String id) {
        DocumentRecord record = documentRepository.findById(id);
        if (record == null) {
            log.warn("文档不存在：id={}", id);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        Path path = Paths.get(record.filePath());
        if (!Files.exists(path)) {
            log.warn("文档文件丢失：id={}, path={}", id, record.filePath());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        try {
            byte[] data = Files.readAllBytes(path);
            // 文件名编码以支持中文
            String encoded = URLEncoder.encode(record.fileName(), StandardCharsets.UTF_8)
                    .replace("+", "%20");
            MediaType mediaType;
            try {
                mediaType = MediaType.parseMediaType(record.mimeType());
            } catch (IllegalArgumentException e) {
                mediaType = MediaType.APPLICATION_OCTET_STREAM;
            }
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename*=UTF-8''" + encoded)
                    .contentLength(record.fileSize())
                    .body(new ByteArrayResource(data));
        } catch (IOException e) {
            log.error("读取文档字节失败：id={}, path={}", id, record.filePath(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
```

- [ ] **Step 2：DocumentController 测试**

新建 `src/test/java/com/lifepilot/interaction/web/controller/DocumentController_下载端点测试.java`：

```java
package com.lifepilot.interaction.web.controller;

import com.lifepilot.document.model.DocumentRecord;
import com.lifepilot.document.repository.DocumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentController_下载端点测试 {

    @Mock DocumentRepository documentRepository;

    @Test
    void 成功返回文档字节含正确_Content_Disposition(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("report.docx");
        byte[] payload = "fake docx bytes".getBytes();
        Files.write(file, payload);

        when(documentRepository.findById("doc-1")).thenReturn(new DocumentRecord(
                "doc-1", "sess-1", "entry-1", "Q3 报表.docx", file.toString(),
                payload.length,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                DocumentRecord.ORIGIN_AGENT_GENERATED, Instant.now()));

        var controller = new DocumentController(documentRepository);
        ResponseEntity<ByteArrayResource> response = controller.download("doc-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getByteArray()).isEqualTo(payload);
        String disposition = response.getHeaders().getFirst("Content-Disposition");
        assertThat(disposition).contains("attachment");
        assertThat(disposition).contains("Q3%20%E6%8A%A5%E8%A1%A8.docx");  // UTF-8 编码后的中文
        assertThat(response.getHeaders().getContentLength()).isEqualTo(payload.length);
    }

    @Test
    void 文档不存在返回_404() {
        when(documentRepository.findById("missing")).thenReturn(null);

        var controller = new DocumentController(documentRepository);

        assertThatThrownBy(() -> controller.download("missing"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 记录存在但物理文件丢失返回_404(@TempDir Path tmp) {
        Path deleted = tmp.resolve("gone.docx");
        // 文件故意不创建
        when(documentRepository.findById("doc-x")).thenReturn(new DocumentRecord(
                "doc-x", "sess-1", null, "gone.docx", deleted.toString(), 100L,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                DocumentRecord.ORIGIN_AGENT_GENERATED, Instant.now()));

        var controller = new DocumentController(documentRepository);

        assertThatThrownBy(() -> controller.download("doc-x"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
```

- [ ] **Step 3：跑测试确认通过**

```bash
mvn -q test -Dtest=DocumentController_下载端点测试
```

预期：3 个测试 PASS。

- [ ] **Step 4：提交**

```bash
git add src/main/java/com/lifepilot/interaction/web/controller/DocumentController.java \
        src/test/java/com/lifepilot/interaction/web/controller/DocumentController_下载端点测试.java
git commit -m "$(cat <<'EOF'
feat(document): DocumentController 下载端点 — GET /api/documents/{id}/download

按 documentId 返回文件字节，Content-Disposition 使用 filename*=UTF-8'' 编码
确保中文文件名不乱码。与 AttachmentController 模式一致，
@ConditionalOnProperty(lifepilot.gateway.channels.web.enabled) 仅 Web 启用时装载。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review 注记

**1. Spec 覆盖**：Phase 2 决策组合（**b1 垂直打穿 + 基础深度 + b3 只建 documents 表 + a4 消息气泡产物卡片**）覆盖如下：
- Phase 2A 的"**垂直打穿 docx**"（b1）：Task 1-6 完整链路
- "**基础深度**"：Task 2 MarkdownToDocxGenerator 明确覆盖标题/段落/列表，不做样式/表格/图表
- "**只建 documents 表**"（b3）：Task 1 V12 只建 documents，不建 document_versions（延 Phase 3）
- "**消息气泡产物卡片**"（a4）：通过 message_attachments 挂载（Task 4）+ orphan 回填（Task 3）复用前端既有 MessageBubble 附件卡片（Phase 0/1B 已建），**零前端改动**

**2. 占位扫描**：无 TBD/TODO；每 Step 都有可运行命令或完整代码。两处 ⚠️：
- Task 2 Step 5：POI heading style 可能不存在 → 提供备选断言方案
- Task 3 Step 5：`persistToolMediaAttachments` 方法位置需实施时定位（约 L340 附近）

**3. 类型一致性**：
- `DocumentRecord` 字段签名在 Task 1/4/5/6 一致（含 origin 字段）
- `DocumentGenerator.generate(String)` 签名 Task 2/4 一致（不传 fileName —— fileName 由工具层决定）
- `DocumentRepository.save/findById/findBySessionId` 在 Task 1/4/6 使用一致
- `AttachmentRepository.backfillOrphanEntryIds(sessionId, entryId)` Task 3 定义，Task 3 内被 AgentPersistenceHandler 使用
- 工具 ID `document.create_docx` 在 Task 4/5/8 一致

**4. Phase 2B 铺垫**：
- `DocumentGenerator` 接口为 xlsx/pptx 生成器预留扩展点
- `DocumentToolProvider.buildDocumentTools()` 返回 List，Phase 2B 可追加
- `DocumentRepository` / `DocumentController` 泛化覆盖所有 docx/xlsx/pptx 产物

**5. 风险点**：
- orphan 回填是近似策略：如果 session 内有跨轮残留 null attachment，会被本轮 assistant entry 误挂。Phase 2A 场景下不会发生（Tool 入库 → assistant 持久化之间无长间隙），Phase 3 编辑链路可能需要精化（用 turn_id）
- 前端 `MessageBubble.isParseableDocument` 对 docx 已返 true（Phase 0/1B 建立），徽标会自动点亮 —— 但徽标语义是"AI 可读取"，用户看到 AI 自己生成的 docx 上这个徽标可能疑惑。考虑 Phase 2A+ 加一个"AI 生成"徽标（识别 origin='agent_generated'）—— 不在本 plan，未来优化

---

**完结**。Phase 2A 后续：
1. subagent 驱动执行 Task 1-6（6 个 commit）
2. 完成后 **不做** Phase 2A 独立冒烟（按老板指示：Phase 2 整体做完统一冒烟）
3. 进入 Phase 2B（xlsx / pptx 横向复用）
4. Phase 2A + 2B 全部完成后统一 e2e 冒烟 + ship
