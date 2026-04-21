# 文档工作空间 Phase 3A — docx 编辑与 Diff 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **配套 spec**：`docs/superpowers/specs/2026-04-21-document-workspace-phase3-design.md`
> **前置依赖**：Phase 2B 已完成 —— `document.create` 工具、`session_documents` 表、`SessionDocumentRepository`、`AttachmentRepository`、`DocumentController` 下载端点、`AgentPersistenceHandler` orphan 回填

**Goal：** 实现 `document.edit` 工具 + 5 种 action（patch/diff/commit/rollback/list_versions），文本锚点 patch 协议，工作副本生命周期与版本管理；前端对话气泡内挂 `DocumentDiffCard` 展示 diff + 三按钮（应用到原路径 / 另存为 / 丢弃）。

**Architecture：**
1. **协议层**：`DocumentPatchOperation` sealed interface + 4 种 op record（`ReplaceTextOp` / `InsertParagraphAfterOp` / `DeleteParagraphOp` / `AddTableRowOp`）
2. **执行层**：`DocxPatchEngine` 纯内存操作 POI XWPFDocument + `TextAnchorLocator` 定位 + `DocxDiffBuilder` 生成 diff JSON
3. **版本层**：`DocumentVersionService`（checkout/patch/commit/rollback/discard/listVersions）+ `DocumentVersionRepository`（JdbcTemplate）
4. **工具层**：`DocumentEditToolProvider` + `DocumentEditActionDispatchExecutor`（对齐 Phase 2B `document.create` 模式）
5. **端点层**：`DocumentController` 扩展 5 个新端点（`/`、`/versions`、`/diff`、`/commit`、`/rollback`、`/working-copy`）+ `download` 支持 `version=` 参数
6. **前端**：`DocumentDiffCard.vue` 对话气泡内折叠卡 + `api/documents.ts` REST 封装 + `MessageBubble.vue` 集成

**Tech Stack：** Apache POI 5.5.1（XWPF）、SQLite + Flyway V13、Spring Boot 3 + JdbcTemplate、Java 22（record / sealed / pattern matching）、Vue 3 + Reka UI 2.x + Tailwind 命名尺度、JUnit 5 + AssertJ + Mockito + ApplicationContextRunner

---

## File Structure

### 后端新建

| 路径 | 责任 |
|---|---|
| `src/main/resources/db/migration/V13__document_patch_and_versions.sql` | V13 迁移：扩展 session_documents + 建 document_versions |
| `src/main/java/com/lifepilot/document/patch/DocumentPatchOperation.java` | sealed interface，permits 4 种 op record |
| `src/main/java/com/lifepilot/document/patch/ReplaceTextOp.java` | record：before_context / target / after_context / new_text / reason |
| `src/main/java/com/lifepilot/document/patch/InsertParagraphAfterOp.java` | record：anchor_paragraph_text / new_paragraphs / reason |
| `src/main/java/com/lifepilot/document/patch/DeleteParagraphOp.java` | record：paragraph_text / reason |
| `src/main/java/com/lifepilot/document/patch/AddTableRowOp.java` | record：table_anchor_text / position / cells / reason |
| `src/main/java/com/lifepilot/document/patch/NewParagraph.java` | record：text / style（供 InsertParagraphAfterOp 使用） |
| `src/main/java/com/lifepilot/document/patch/DocumentPatchResult.java` | record：success / newVersion / diffJson / failedOps |
| `src/main/java/com/lifepilot/document/patch/docx/TextAnchorLocator.java` | 文本锚点定位辅助，被 4 种 op 复用 |
| `src/main/java/com/lifepilot/document/patch/docx/ParagraphRunRange.java` | record：paragraphIndex / startRunIndex / startCharOffset / endRunIndex / endCharOffset |
| `src/main/java/com/lifepilot/document/patch/docx/DocxPatchEngine.java` | POI 内存 patch 执行器，支持 4 种 op |
| `src/main/java/com/lifepilot/document/patch/docx/DocxDiffBuilder.java` | 按 op + 定位结果构造 diff JSON |
| `src/main/java/com/lifepilot/document/model/DocumentVersionRecord.java` | record：id / documentId / versionNo / filePath / source / patchSummary / diffJson / createdAt |
| `src/main/java/com/lifepilot/document/repository/DocumentVersionRepository.java` | JdbcTemplate Repository（对齐 SessionDocumentRepository） |
| `src/main/java/com/lifepilot/document/version/DocumentVersionService.java` | checkout / applyPatch / commit / rollback / discard / listVersions |
| `src/main/java/com/lifepilot/document/version/SourceRef.java` | sealed interface：PathSource / AttachmentSource / DocumentSource |
| `src/main/java/com/lifepilot/document/tool/DocumentEditActionDispatchExecutor.java` | 5 个 action 路由分发，对齐 DocumentCreateActionDispatchExecutor |
| `src/main/java/com/lifepilot/document/tool/DocumentEditToolProvider.java` | `document.edit` BuiltinTool 构造 + schema |

### 后端修改

| 路径 | 改动 |
|---|---|
| `src/main/java/com/lifepilot/document/model/SessionDocumentRecord.java` | 加 `sourcePath`（可空）+ `latestVersion` 字段；加 origin 常量 `ORIGIN_USER_LOCAL_FILE` / `ORIGIN_USER_ATTACHMENT_EDITED` |
| `src/main/java/com/lifepilot/document/repository/SessionDocumentRepository.java` | SELECT_COLUMNS / RowMapper / INSERT 带新字段；加 `updateLatestVersion(id, version)` + `updateFilePath(id, path)` + `findBySessionAndSourcePath(sessionId, sourcePath)` |
| `src/main/java/com/lifepilot/interaction/web/repository/AttachmentRepository.java` | 加 `updateSizeByFilePath(filePath, newSize)` |
| `src/main/java/com/lifepilot/document/config/DocumentAutoConfiguration.java` | 新增 Bean：DocumentVersionRepository / DocumentVersionService / DocxPatchEngine / TextAnchorLocator / DocxDiffBuilder / DocumentEditActionDispatchExecutor / DocumentEditToolProvider / BuiltinTool documentEditTool |
| `src/main/java/com/lifepilot/interaction/web/controller/DocumentController.java` | 加 5 个端点 + 扩展 download 支持 version 参数 |
| `src/main/resources/application.yml` | `core-tool-ids` 追加 `document.edit` |

### 前端

| 路径 | 操作 | 职责 |
|---|---|---|
| `zhiwei-web/src/api/documents.ts` | 新建 | REST API 封装 |
| `zhiwei-web/src/components/chat/DocumentDiffCard.vue` | 新建 | 对话气泡内 diff 卡片 |
| `zhiwei-web/src/components/chat/MessageBubble.vue` | 修改 | 按附件元数据决定渲染 DocumentDiffCard 还是普通附件卡片 |

### 测试

| 路径 | 操作 |
|---|---|
| `src/test/resources/fixtures/document/sample-contract.docx` | 新建（运行一次性 fixture 生成器产出） |
| `src/test/resources/fixtures/document/sample-with-styles.docx` | 新建（同上） |
| `src/test/java/com/lifepilot/document/testsupport/DocxFixtureGenerator.java` | 新建：一次性生成 fixture，@Test 仅手动触发 |
| `src/test/java/com/lifepilot/document/patch/docx/TextAnchorLocator_定位测试.java` | 新建 |
| `src/test/java/com/lifepilot/document/patch/docx/DocxPatchEngine_四种操作测试.java` | 新建 |
| `src/test/java/com/lifepilot/document/patch/docx/DocxDiffBuilder_构造测试.java` | 新建 |
| `src/test/java/com/lifepilot/document/repository/DocumentVersionRepository_持久化测试.java` | 新建 |
| `src/test/java/com/lifepilot/document/repository/SessionDocumentRepository_扩展字段测试.java` | 新建（追加 P3 字段相关测试） |
| `src/test/java/com/lifepilot/document/version/DocumentVersionService_生命周期测试.java` | 新建 |
| `src/test/java/com/lifepilot/document/tool/DocumentEditActionDispatchExecutor_路由测试.java` | 新建 |
| `src/test/java/com/lifepilot/interaction/web/controller/DocumentController_P3端点测试.java` | 新建 |
| `src/test/java/com/lifepilot/document/config/DocumentAutoConfiguration_装配测试.java` | 追加（已存在则扩展）：校验 P3 新 Bean 都装配 |

### 不动清单

- `knowledge/parser/` 任意文件
- `MarkdownToDocxGenerator / StructuredDataToXlsxGenerator / OutlineToPptxGenerator`（Phase 2 既有生成器）
- `AbstractDocumentCreateToolExecutor / DocumentCreate*ToolExecutor / DocumentCreateActionDispatchExecutor`（Phase 2 既有 create 链路）
- `DocumentToolProvider`（Phase 2B 的 create provider 保持不变；P3 新建独立 `DocumentEditToolProvider`）

---

## Task 1：Flyway V13 迁移 + SessionDocumentRecord / Repository 扩展

**目的**：扩展 `session_documents` 表承载 P3 工作副本元数据（`source_path` + `latest_version`），新增 origin 常量，扩充 Repository API。

**Files:**
- Create: `src/main/resources/db/migration/V13__document_patch_and_versions.sql`
- Modify: `src/main/java/com/lifepilot/document/model/SessionDocumentRecord.java`
- Modify: `src/main/java/com/lifepilot/document/repository/SessionDocumentRepository.java`
- Test: `src/test/java/com/lifepilot/document/repository/SessionDocumentRepository_扩展字段测试.java`

**前置阅读：**
- `src/main/resources/db/migration/V12__add_session_documents_table.sql`（既有表定义）
- `src/main/java/com/lifepilot/document/repository/SessionDocumentRepository.java`（Phase 2A Repository 模式）
- `.claude/rules/database-rules.md`（SQLite 方言 + Flyway 命名规则）

- [ ] **Step 1：写 Flyway V13 SQL**

新建 `src/main/resources/db/migration/V13__document_patch_and_versions.sql`：

```sql
-- Phase 3A: 文档编辑与版本管理
-- 扩展 session_documents 以承载工作副本状态，新建 document_versions 存版本链。

ALTER TABLE session_documents ADD COLUMN source_path TEXT;
ALTER TABLE session_documents ADD COLUMN latest_version INTEGER NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_session_documents_source_path ON session_documents(source_path);

CREATE TABLE IF NOT EXISTS document_versions (
    id TEXT PRIMARY KEY,
    document_id TEXT NOT NULL,
    version_no INTEGER NOT NULL,
    file_path TEXT NOT NULL,
    source TEXT NOT NULL,
    patch_summary TEXT,
    diff_json TEXT,
    created_at TEXT NOT NULL,
    FOREIGN KEY (document_id) REFERENCES session_documents(id) ON DELETE CASCADE,
    UNIQUE (document_id, version_no)
);

CREATE INDEX IF NOT EXISTS idx_document_versions_document_id ON document_versions(document_id);
CREATE INDEX IF NOT EXISTS idx_document_versions_source ON document_versions(source);
```

- [ ] **Step 2：运行 mvn compile 确认 Flyway 校验通过**

Run: `mvn compile -q -DskipTests`
Expected: `BUILD SUCCESS`，无 Flyway checksum 错误。

- [ ] **Step 3：扩展 SessionDocumentRecord**

替换 `src/main/java/com/lifepilot/document/model/SessionDocumentRecord.java` 的 record 声明为：

```java
public record SessionDocumentRecord(
        String id,
        String sessionId,
        @Nullable String entryId,
        String fileName,
        String filePath,
        long fileSize,
        String mimeType,
        String origin,
        @Nullable String sourcePath,
        int latestVersion,
        Instant createdAt
) {

    public static final String ORIGIN_AGENT_GENERATED = "agent_generated";
    public static final String ORIGIN_USER_UPLOAD = "user_upload";
    public static final String ORIGIN_TEMPLATE_RENDERED = "template_rendered";
    public static final String ORIGIN_USER_LOCAL_FILE = "user_local_file";
    public static final String ORIGIN_USER_ATTACHMENT_EDITED = "user_attachment_edited";
}
```

并在 Javadoc 新增两行 `@param`：

```
 * @param sourcePath     原始本机路径（path 源时填；附件/AI 产物为 null）
 * @param latestVersion  工作副本最新版本号；0 = 未被 patch 过
```

- [ ] **Step 4：修改 SessionDocumentRepository**

替换 `src/main/java/com/lifepilot/document/repository/SessionDocumentRepository.java` 为：

```java
package com.lifepilot.document.repository;

import com.lifepilot.document.model.SessionDocumentRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 会话级文档产物元数据访问层。
 *
 * <p>P3 扩展：SELECT 增补 {@code source_path} / {@code latest_version}；
 * 增加 {@link #updateLatestVersion} / {@link #updateFilePath} /
 * {@link #findBySessionAndSourcePath} / {@link #deleteById} 支持工作副本生命周期。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
@Repository
public class SessionDocumentRepository {

    private static final Logger log = LoggerFactory.getLogger(SessionDocumentRepository.class);

    private static final String SELECT_COLUMNS =
            "id, session_id, entry_id, file_name, file_path, file_size, mime_type, origin, " +
                    "source_path, latest_version, created_at";

    private static final RowMapper<SessionDocumentRecord> ROW_MAPPER = (rs, rowNum) -> new SessionDocumentRecord(
            rs.getString("id"),
            rs.getString("session_id"),
            rs.getString("entry_id"),
            rs.getString("file_name"),
            rs.getString("file_path"),
            rs.getLong("file_size"),
            rs.getString("mime_type"),
            rs.getString("origin"),
            rs.getString("source_path"),
            rs.getInt("latest_version"),
            Instant.parse(rs.getString("created_at"))
    );

    private final JdbcTemplate jdbcTemplate;

    public SessionDocumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String save(SessionDocumentRecord record) {
        String id = record.id() != null ? record.id() : UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO session_documents (id, session_id, entry_id, file_name, file_path, " +
                        "file_size, mime_type, origin, source_path, latest_version, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, record.sessionId(), record.entryId(),
                record.fileName(), record.filePath(), record.fileSize(),
                record.mimeType(), record.origin(),
                record.sourcePath(), record.latestVersion(),
                record.createdAt().toString());
        log.debug("保存文档：id={}, fileName={}, origin={}, sourcePath={}",
                id, record.fileName(), record.origin(), record.sourcePath());
        return id;
    }

    @Nullable
    public SessionDocumentRecord findById(String id) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT " + SELECT_COLUMNS + " FROM session_documents WHERE id = ?",
                    ROW_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            log.warn("未找到文档：id={}", id);
            return null;
        }
    }

    public List<SessionDocumentRecord> findBySessionId(String sessionId) {
        return jdbcTemplate.query(
                "SELECT " + SELECT_COLUMNS + " FROM session_documents WHERE session_id = ? " +
                        "ORDER BY created_at DESC",
                ROW_MAPPER, sessionId);
    }

    @Nullable
    public SessionDocumentRecord findBySessionAndSourcePath(String sessionId, String sourcePath) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT " + SELECT_COLUMNS + " FROM session_documents " +
                            "WHERE session_id = ? AND source_path = ?",
                    ROW_MAPPER, sessionId, sourcePath);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public int updateLatestVersion(String id, int newVersion) {
        return jdbcTemplate.update(
                "UPDATE session_documents SET latest_version = ? WHERE id = ?",
                newVersion, id);
    }

    public int updateFilePath(String id, String newFilePath, long newFileSize) {
        return jdbcTemplate.update(
                "UPDATE session_documents SET file_path = ?, file_size = ? WHERE id = ?",
                newFilePath, newFileSize, id);
    }

    public int deleteById(String id) {
        return jdbcTemplate.update("DELETE FROM session_documents WHERE id = ?", id);
    }

    public int deleteBySessionId(String sessionId) {
        return jdbcTemplate.update("DELETE FROM session_documents WHERE session_id = ?", sessionId);
    }
}
```

- [ ] **Step 5：写扩展字段测试**

新建 `src/test/java/com/lifepilot/document/repository/SessionDocumentRepository_扩展字段测试.java`：

```java
package com.lifepilot.document.repository;

import com.lifepilot.document.model.SessionDocumentRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SessionDocumentRepository P3 扩展字段测试 —— 覆盖 sourcePath / latestVersion
 * 读写 + updateLatestVersion / updateFilePath / findBySessionAndSourcePath。
 *
 * @author zsg
 * @since 2026-04-21
 */
@JdbcTest
@Import(SessionDocumentRepository.class)
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration")
class SessionDocumentRepository_扩展字段测试 {

    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Autowired
    private SessionDocumentRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 准备会话() {
        jdbcTemplate.update(
                "INSERT INTO session_store (session_id, created_at, updated_at) VALUES (?, ?, ?)",
                "sess-p3", Instant.now().toString(), Instant.now().toString());
    }

    @Test
    @DisplayName("保存并按 sessionId+sourcePath 读回")
    void 保存并按sessionId与sourcePath读回() {
        var record = new SessionDocumentRecord(
                "doc-1", "sess-p3", null, "甲方合同.docx",
                "/tmp/doc.docx", 123L, DOCX_MIME,
                SessionDocumentRecord.ORIGIN_USER_LOCAL_FILE,
                "D:/合同/甲方.docx", 0, Instant.now());
        repository.save(record);

        var found = repository.findBySessionAndSourcePath("sess-p3", "D:/合同/甲方.docx");

        assertThat(found).isNotNull();
        assertThat(found.id()).isEqualTo("doc-1");
        assertThat(found.latestVersion()).isZero();
        assertThat(found.sourcePath()).isEqualTo("D:/合同/甲方.docx");
    }

    @Test
    @DisplayName("updateLatestVersion 递增版本号")
    void updateLatestVersion递增版本号() {
        var record = new SessionDocumentRecord(
                "doc-2", "sess-p3", null, "x.docx", "/tmp/x.docx", 1L, DOCX_MIME,
                SessionDocumentRecord.ORIGIN_USER_LOCAL_FILE, "D:/x.docx", 0, Instant.now());
        repository.save(record);

        int affected = repository.updateLatestVersion("doc-2", 3);

        assertThat(affected).isEqualTo(1);
        assertThat(repository.findById("doc-2").latestVersion()).isEqualTo(3);
    }

    @Test
    @DisplayName("updateFilePath 同步更新 filePath 与 fileSize")
    void updateFilePath同步更新路径与大小() {
        var record = new SessionDocumentRecord(
                "doc-3", "sess-p3", null, "y.docx", "/old/path.docx", 100L, DOCX_MIME,
                SessionDocumentRecord.ORIGIN_AGENT_GENERATED, null, 0, Instant.now());
        repository.save(record);

        repository.updateFilePath("doc-3", "/new/path/v1.docx", 250L);

        var found = repository.findById("doc-3");
        assertThat(found.filePath()).isEqualTo("/new/path/v1.docx");
        assertThat(found.fileSize()).isEqualTo(250L);
    }

    @Test
    @DisplayName("找不到 sourcePath 返回 null")
    void 找不到sourcePath返回null() {
        assertThat(repository.findBySessionAndSourcePath("sess-p3", "不存在.docx")).isNull();
    }

    @Test
    @DisplayName("deleteById 删除并留其他记录")
    void deleteById删除指定记录() {
        var a = new SessionDocumentRecord("doc-a", "sess-p3", null, "a.docx", "/p/a", 1L, DOCX_MIME,
                SessionDocumentRecord.ORIGIN_AGENT_GENERATED, null, 0, Instant.now());
        var b = new SessionDocumentRecord("doc-b", "sess-p3", null, "b.docx", "/p/b", 1L, DOCX_MIME,
                SessionDocumentRecord.ORIGIN_AGENT_GENERATED, null, 0, Instant.now());
        repository.save(a);
        repository.save(b);

        int affected = repository.deleteById("doc-a");

        assertThat(affected).isEqualTo(1);
        assertThat(repository.findById("doc-a")).isNull();
        assertThat(repository.findById("doc-b")).isNotNull();
    }
}
```

- [ ] **Step 6：运行测试 + Phase 2A 已有持久化测试回归**

Run: `mvn test -q -Dtest='SessionDocumentRepository*'`
Expected: 所有 SessionDocumentRepository 相关测试通过（含 Phase 2A 原有的 + P3 新增的）。

- [ ] **Step 7：commit**

```bash
git add src/main/resources/db/migration/V13__document_patch_and_versions.sql \
        src/main/java/com/lifepilot/document/model/SessionDocumentRecord.java \
        src/main/java/com/lifepilot/document/repository/SessionDocumentRepository.java \
        src/test/java/com/lifepilot/document/repository/SessionDocumentRepository_扩展字段测试.java
git commit -m "feat(document): Phase 3A Task 1 — V13 迁移 + session_documents 扩展 sourcePath/latestVersion"
```

---

## Task 2：DocumentVersionRecord + DocumentVersionRepository

**目的**：为 `document_versions` 表建 record + JdbcTemplate Repository，提供 save / findByDocumentId / findByDocumentIdAndVersion / deleteByDocumentId。

**Files:**
- Create: `src/main/java/com/lifepilot/document/model/DocumentVersionRecord.java`
- Create: `src/main/java/com/lifepilot/document/repository/DocumentVersionRepository.java`
- Test: `src/test/java/com/lifepilot/document/repository/DocumentVersionRepository_持久化测试.java`

**前置阅读：**
- `src/main/java/com/lifepilot/document/repository/SessionDocumentRepository.java`（同款 JdbcTemplate 模式，照抄风格）

- [ ] **Step 1：创建 DocumentVersionRecord**

新建 `src/main/java/com/lifepilot/document/model/DocumentVersionRecord.java`：

```java
package com.lifepilot.document.model;

import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * 文档版本记录 —— document_versions 表一行。
 *
 * <p>每次 patch / rollback 产生一条，初始 checkout 时写 source=initial 的 v0。
 * {@code diffJson} 缓存前端渲染用的 diff 结构；{@code patchSummary} 是一句话摘要。</p>
 *
 * @param id             版本记录 UUID
 * @param documentId     所属 session_documents.id
 * @param versionNo      版本号（0 = initial checkout；递增 1/2/3...）
 * @param filePath       该版本物理文件绝对路径（{@code working/{documentId}/v{n}.docx}）
 * @param source         initial / patch / rollback
 * @param patchSummary   可空；patch 时给用户的摘要，如 "共 3 处修改：replace_text x2, insert_paragraph_after x1"
 * @param diffJson       可空；patch 时缓存的 diff JSON 字符串
 * @param createdAt      ISO 8601
 * @author zsg
 * @since 2026-04-21
 */
public record DocumentVersionRecord(
        String id,
        String documentId,
        int versionNo,
        String filePath,
        String source,
        @Nullable String patchSummary,
        @Nullable String diffJson,
        Instant createdAt
) {

    public static final String SOURCE_INITIAL = "initial";
    public static final String SOURCE_PATCH = "patch";
    public static final String SOURCE_ROLLBACK = "rollback";
}
```

- [ ] **Step 2：创建 DocumentVersionRepository**

新建 `src/main/java/com/lifepilot/document/repository/DocumentVersionRepository.java`：

```java
package com.lifepilot.document.repository;

import com.lifepilot.document.model.DocumentVersionRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 文档版本访问层 —— 对齐 SessionDocumentRepository 风格。
 *
 * @author zsg
 * @since 2026-04-21
 */
@Repository
public class DocumentVersionRepository {

    private static final Logger log = LoggerFactory.getLogger(DocumentVersionRepository.class);

    private static final String SELECT_COLUMNS =
            "id, document_id, version_no, file_path, source, patch_summary, diff_json, created_at";

    private static final RowMapper<DocumentVersionRecord> ROW_MAPPER = (rs, rowNum) -> new DocumentVersionRecord(
            rs.getString("id"),
            rs.getString("document_id"),
            rs.getInt("version_no"),
            rs.getString("file_path"),
            rs.getString("source"),
            rs.getString("patch_summary"),
            rs.getString("diff_json"),
            Instant.parse(rs.getString("created_at"))
    );

    private final JdbcTemplate jdbcTemplate;

    public DocumentVersionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String save(DocumentVersionRecord record) {
        String id = record.id() != null ? record.id() : UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO document_versions (id, document_id, version_no, file_path, source, " +
                        "patch_summary, diff_json, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id, record.documentId(), record.versionNo(), record.filePath(),
                record.source(), record.patchSummary(), record.diffJson(),
                record.createdAt().toString());
        log.debug("保存文档版本：documentId={}, versionNo={}, source={}",
                record.documentId(), record.versionNo(), record.source());
        return id;
    }

    public List<DocumentVersionRecord> findByDocumentId(String documentId) {
        return jdbcTemplate.query(
                "SELECT " + SELECT_COLUMNS + " FROM document_versions " +
                        "WHERE document_id = ? ORDER BY version_no ASC",
                ROW_MAPPER, documentId);
    }

    @Nullable
    public DocumentVersionRecord findByDocumentIdAndVersion(String documentId, int versionNo) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT " + SELECT_COLUMNS + " FROM document_versions " +
                            "WHERE document_id = ? AND version_no = ?",
                    ROW_MAPPER, documentId, versionNo);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public int deleteByDocumentId(String documentId) {
        return jdbcTemplate.update(
                "DELETE FROM document_versions WHERE document_id = ?", documentId);
    }
}
```

- [ ] **Step 3：写持久化测试**

新建 `src/test/java/com/lifepilot/document/repository/DocumentVersionRepository_持久化测试.java`：

```java
package com.lifepilot.document.repository;

import com.lifepilot.document.model.DocumentVersionRecord;
import com.lifepilot.document.model.SessionDocumentRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DocumentVersionRepository 持久化测试 —— save / findByDocumentId / findByDocumentIdAndVersion / delete。
 *
 * @author zsg
 * @since 2026-04-21
 */
@JdbcTest
@Import({SessionDocumentRepository.class, DocumentVersionRepository.class})
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration")
class DocumentVersionRepository_持久化测试 {

    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Autowired
    private DocumentVersionRepository versionRepository;
    @Autowired
    private SessionDocumentRepository documentRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 准备会话与文档() {
        jdbcTemplate.update(
                "INSERT INTO session_store (session_id, created_at, updated_at) VALUES (?, ?, ?)",
                "sess-ver", Instant.now().toString(), Instant.now().toString());
        documentRepository.save(new SessionDocumentRecord(
                "doc-ver", "sess-ver", null, "x.docx", "/p/x", 1L, DOCX_MIME,
                SessionDocumentRecord.ORIGIN_USER_LOCAL_FILE, "D:/x.docx", 0, Instant.now()));
    }

    @Test
    @DisplayName("保存 initial 版本并按 documentId 读回")
    void 保存initial版本并按documentId读回() {
        versionRepository.save(new DocumentVersionRecord(
                "v-1", "doc-ver", 0, "/w/v0.docx", DocumentVersionRecord.SOURCE_INITIAL,
                null, null, Instant.now()));

        var list = versionRepository.findByDocumentId("doc-ver");

        assertThat(list).hasSize(1);
        assertThat(list.get(0).versionNo()).isZero();
        assertThat(list.get(0).source()).isEqualTo(DocumentVersionRecord.SOURCE_INITIAL);
    }

    @Test
    @DisplayName("多版本按 versionNo 升序返回")
    void 多版本按versionNo升序返回() {
        versionRepository.save(new DocumentVersionRecord(
                "v-a", "doc-ver", 2, "/w/v2.docx", DocumentVersionRecord.SOURCE_PATCH,
                "共 2 处", "{}", Instant.now()));
        versionRepository.save(new DocumentVersionRecord(
                "v-b", "doc-ver", 0, "/w/v0.docx", DocumentVersionRecord.SOURCE_INITIAL,
                null, null, Instant.now()));
        versionRepository.save(new DocumentVersionRecord(
                "v-c", "doc-ver", 1, "/w/v1.docx", DocumentVersionRecord.SOURCE_PATCH,
                "共 1 处", "{}", Instant.now()));

        var list = versionRepository.findByDocumentId("doc-ver");

        assertThat(list).extracting(DocumentVersionRecord::versionNo).containsExactly(0, 1, 2);
    }

    @Test
    @DisplayName("findByDocumentIdAndVersion 命中")
    void 按版本号精确查找() {
        versionRepository.save(new DocumentVersionRecord(
                "v-x", "doc-ver", 5, "/w/v5.docx", DocumentVersionRecord.SOURCE_ROLLBACK,
                "回滚到 v2", null, Instant.now()));

        var found = versionRepository.findByDocumentIdAndVersion("doc-ver", 5);

        assertThat(found).isNotNull();
        assertThat(found.source()).isEqualTo(DocumentVersionRecord.SOURCE_ROLLBACK);
    }

    @Test
    @DisplayName("deleteByDocumentId 级联删除所有版本")
    void deleteByDocumentId级联删除() {
        versionRepository.save(new DocumentVersionRecord(
                "v-d1", "doc-ver", 0, "/w/v0.docx", DocumentVersionRecord.SOURCE_INITIAL,
                null, null, Instant.now()));
        versionRepository.save(new DocumentVersionRecord(
                "v-d2", "doc-ver", 1, "/w/v1.docx", DocumentVersionRecord.SOURCE_PATCH,
                "s", null, Instant.now()));

        int affected = versionRepository.deleteByDocumentId("doc-ver");

        assertThat(affected).isEqualTo(2);
        assertThat(versionRepository.findByDocumentId("doc-ver")).isEmpty();
    }

    @Test
    @DisplayName("UNIQUE(document_id, version_no) 约束生效")
    void 重复版本号触发唯一约束() {
        versionRepository.save(new DocumentVersionRecord(
                "v-u1", "doc-ver", 1, "/w/v1.docx", DocumentVersionRecord.SOURCE_PATCH,
                null, null, Instant.now()));

        var duplicate = new DocumentVersionRecord(
                "v-u2", "doc-ver", 1, "/w/v1b.docx", DocumentVersionRecord.SOURCE_PATCH,
                null, null, Instant.now());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> versionRepository.save(duplicate))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }
}
```

- [ ] **Step 4：运行测试**

Run: `mvn test -q -Dtest='DocumentVersionRepository*'`
Expected: 5 个用例全部通过。

- [ ] **Step 5：commit**

```bash
git add src/main/java/com/lifepilot/document/model/DocumentVersionRecord.java \
        src/main/java/com/lifepilot/document/repository/DocumentVersionRepository.java \
        src/test/java/com/lifepilot/document/repository/DocumentVersionRepository_持久化测试.java
git commit -m "feat(document): Phase 3A Task 2 — DocumentVersionRecord + Repository"
```

---

## Task 3：协议层 — DocumentPatchOperation sealed + 4 个 op record + 辅助 record

**目的**：定义面向 LLM 的 patch 协议数据模型；patch 执行器、工具层、diff builder 都依赖这些 record。

**Files:**
- Create: `src/main/java/com/lifepilot/document/patch/DocumentPatchOperation.java`
- Create: `src/main/java/com/lifepilot/document/patch/ReplaceTextOp.java`
- Create: `src/main/java/com/lifepilot/document/patch/InsertParagraphAfterOp.java`
- Create: `src/main/java/com/lifepilot/document/patch/DeleteParagraphOp.java`
- Create: `src/main/java/com/lifepilot/document/patch/AddTableRowOp.java`
- Create: `src/main/java/com/lifepilot/document/patch/NewParagraph.java`
- Create: `src/main/java/com/lifepilot/document/patch/DocumentPatchResult.java`
- Create: `src/main/java/com/lifepilot/document/patch/FailedOp.java`
- Create: `src/main/java/com/lifepilot/document/version/SourceRef.java`

**前置阅读：**
- `src/main/java/com/lifepilot/document/generator/SheetData.java`（既有 record 风格）
- `.claude/rules/java-conventions.md`（sealed interface + record 偏好）

- [ ] **Step 1：sealed interface DocumentPatchOperation**

新建 `src/main/java/com/lifepilot/document/patch/DocumentPatchOperation.java`：

```java
package com.lifepilot.document.patch;

/**
 * 文档 patch 操作 —— 面向 LLM 的 sealed 协议。
 *
 * <p>4 种 op 共享一个父接口供 Engine 按 pattern matching 分派；每个 op 自带定位器字段与新内容。
 * 协议形状见 {@code docs/superpowers/specs/2026-04-21-document-workspace-phase3-design.md} §2.3。</p>
 *
 * @author zsg
 * @since 2026-04-21
 */
public sealed interface DocumentPatchOperation
        permits ReplaceTextOp, InsertParagraphAfterOp, DeleteParagraphOp, AddTableRowOp {

    /** LLM 可选的解释，会透传到 diff JSON 给用户看。 */
    String reason();
}
```

- [ ] **Step 2：ReplaceTextOp**

新建 `src/main/java/com/lifepilot/document/patch/ReplaceTextOp.java`：

```java
package com.lifepilot.document.patch;

import org.springframework.lang.Nullable;

/**
 * 文本锚点替换 —— P3A 主力 op。
 *
 * <p>服务端在工作副本全文中查找 {@code beforeContext + target + afterContext} 拼接串，
 * 必须恰好出现 1 次，否则定位失败。找到后只替换 {@code target} 对应的文字，
 * 保留横跨 run 的样式（首 run 样式继承到替换文本）。</p>
 *
 * @param beforeContext 前置锚点（可为空串），建议 ≥ 10 字提高精度
 * @param target        要替换的文本（非空）
 * @param afterContext  后置锚点（可为空串）
 * @param newText       新文本（可为空串，空串 = 删除 target）
 * @param reason        可选，LLM 给用户看的解释
 * @author zsg
 * @since 2026-04-21
 */
public record ReplaceTextOp(
        String beforeContext,
        String target,
        String afterContext,
        String newText,
        @Nullable String reason
) implements DocumentPatchOperation {

    public ReplaceTextOp {
        if (target == null || target.isEmpty()) {
            throw new IllegalArgumentException("ReplaceTextOp.target 不能为空");
        }
        if (beforeContext == null) beforeContext = "";
        if (afterContext == null) afterContext = "";
        if (newText == null) newText = "";
    }
}
```

- [ ] **Step 3：InsertParagraphAfterOp + NewParagraph**

新建 `src/main/java/com/lifepilot/document/patch/NewParagraph.java`：

```java
package com.lifepilot.document.patch;

/**
 * 新增段落描述 —— InsertParagraphAfterOp 的子元素。
 *
 * @param text  段落文本
 * @param style 段落样式枚举：Normal / Heading1 / Heading2 / Heading3 / ListBullet；缺失默认 Normal
 * @author zsg
 * @since 2026-04-21
 */
public record NewParagraph(String text, String style) {

    public static final String STYLE_NORMAL = "Normal";
    public static final String STYLE_HEADING_1 = "Heading1";
    public static final String STYLE_HEADING_2 = "Heading2";
    public static final String STYLE_HEADING_3 = "Heading3";
    public static final String STYLE_LIST_BULLET = "ListBullet";

    public NewParagraph {
        if (text == null) text = "";
        if (style == null || style.isBlank()) style = STYLE_NORMAL;
    }
}
```

新建 `src/main/java/com/lifepilot/document/patch/InsertParagraphAfterOp.java`：

```java
package com.lifepilot.document.patch;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 在锚点段落之后插入新段 —— 锚点段落文本必须在文档中唯一匹配。
 *
 * @param anchorParagraphText 锚点段落全文（精确匹配）
 * @param newParagraphs       要插入的段落列表（非空）
 * @param reason              可选
 * @author zsg
 * @since 2026-04-21
 */
public record InsertParagraphAfterOp(
        String anchorParagraphText,
        List<NewParagraph> newParagraphs,
        @Nullable String reason
) implements DocumentPatchOperation {

    public InsertParagraphAfterOp {
        if (anchorParagraphText == null || anchorParagraphText.isBlank()) {
            throw new IllegalArgumentException("anchorParagraphText 不能为空");
        }
        if (newParagraphs == null || newParagraphs.isEmpty()) {
            throw new IllegalArgumentException("newParagraphs 至少一项");
        }
        newParagraphs = List.copyOf(newParagraphs);
    }
}
```

- [ ] **Step 4：DeleteParagraphOp + AddTableRowOp**

新建 `src/main/java/com/lifepilot/document/patch/DeleteParagraphOp.java`：

```java
package com.lifepilot.document.patch;

import org.springframework.lang.Nullable;

/**
 * 删除整段 —— paragraphText 必须在文档中唯一匹配一个段落。
 *
 * @author zsg
 * @since 2026-04-21
 */
public record DeleteParagraphOp(
        String paragraphText,
        @Nullable String reason
) implements DocumentPatchOperation {

    public DeleteParagraphOp {
        if (paragraphText == null || paragraphText.isBlank()) {
            throw new IllegalArgumentException("paragraphText 不能为空");
        }
    }
}
```

新建 `src/main/java/com/lifepilot/document/patch/AddTableRowOp.java`：

```java
package com.lifepilot.document.patch;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 表格追加行 —— 按 tableAnchorText 定位表格（表格内任一单元格唯一命中），
 * position=start 插到表格头部，end 追加到尾部。cells 长度必须等于表格列数。
 *
 * @author zsg
 * @since 2026-04-21
 */
public record AddTableRowOp(
        String tableAnchorText,
        String position,
        List<String> cells,
        @Nullable String reason
) implements DocumentPatchOperation {

    public static final String POSITION_START = "start";
    public static final String POSITION_END = "end";

    public AddTableRowOp {
        if (tableAnchorText == null || tableAnchorText.isBlank()) {
            throw new IllegalArgumentException("tableAnchorText 不能为空");
        }
        if (position == null || (!position.equals(POSITION_START) && !position.equals(POSITION_END))) {
            throw new IllegalArgumentException("position 必须是 start 或 end");
        }
        if (cells == null || cells.isEmpty()) {
            throw new IllegalArgumentException("cells 不能为空");
        }
        cells = List.copyOf(cells);
    }
}
```

- [ ] **Step 5：DocumentPatchResult + FailedOp**

新建 `src/main/java/com/lifepilot/document/patch/FailedOp.java`：

```java
package com.lifepilot.document.patch;

/**
 * patch 失败的 op 描述 —— 透传给 LLM 以便重试。
 *
 * @param opIndex    op 在请求数组中的下标
 * @param opType     op 类型（replace_text / insert_paragraph_after / ...）
 * @param reason     失败原因标识（locator_not_found / locator_not_unique / cells_mismatch / ...）
 * @param matchCount 命中次数（定位失败时填 0 或 >1；其他失败填 -1）
 * @param hint       人类可读提示
 * @author zsg
 * @since 2026-04-21
 */
public record FailedOp(int opIndex, String opType, String reason, int matchCount, String hint) {}
```

新建 `src/main/java/com/lifepilot/document/patch/DocumentPatchResult.java`：

```java
package com.lifepilot.document.patch;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * patch 执行结果 —— 成功时附新版本号 + diffJson；失败时附 failedOps。
 *
 * @author zsg
 * @since 2026-04-21
 */
public record DocumentPatchResult(
        boolean success,
        int newVersion,
        @Nullable String diffJson,
        @Nullable String patchSummary,
        List<FailedOp> failedOps
) {

    public static DocumentPatchResult success(int newVersion, String diffJson, String summary) {
        return new DocumentPatchResult(true, newVersion, diffJson, summary, List.of());
    }

    public static DocumentPatchResult failure(List<FailedOp> failedOps) {
        return new DocumentPatchResult(false, -1, null, null, List.copyOf(failedOps));
    }
}
```

- [ ] **Step 6：SourceRef sealed**

新建 `src/main/java/com/lifepilot/document/version/SourceRef.java`：

```java
package com.lifepilot.document.version;

/**
 * 文档来源引用 —— sealed interface，三种具体来源。
 *
 * <p>LLM 在 {@code document.edit} 工具里以 {@code source.type + value/id} 传入；
 * ActionDispatcher 反序列化为对应子类后传给 {@link DocumentVersionService#checkout}。</p>
 *
 * @author zsg
 * @since 2026-04-21
 */
public sealed interface SourceRef permits SourceRef.PathSource, SourceRef.AttachmentSource, SourceRef.DocumentSource {

    /** 本机路径源：LLM 给 D:/... 这类绝对路径。 */
    record PathSource(String path) implements SourceRef {
        public PathSource {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("PathSource.path 不能为空");
            }
        }
    }

    /** 附件源：前端拖拽上传产生的 attachmentId。 */
    record AttachmentSource(String attachmentId) implements SourceRef {
        public AttachmentSource {
            if (attachmentId == null || attachmentId.isBlank()) {
                throw new IllegalArgumentException("AttachmentSource.attachmentId 不能为空");
            }
        }
    }

    /** 已在 session_documents 表的文档 ID（包括 Phase 2 AI 产物，或 P3 已 checkout 过的）。 */
    record DocumentSource(String documentId) implements SourceRef {
        public DocumentSource {
            if (documentId == null || documentId.isBlank()) {
                throw new IllegalArgumentException("DocumentSource.documentId 不能为空");
            }
        }
    }
}
```

- [ ] **Step 7：编译确认**

Run: `mvn compile -q -DskipTests`
Expected: BUILD SUCCESS。

- [ ] **Step 8：commit**

```bash
git add src/main/java/com/lifepilot/document/patch/ \
        src/main/java/com/lifepilot/document/version/SourceRef.java
git commit -m "feat(document): Phase 3A Task 3 — DocumentPatchOperation 协议 + SourceRef"
```

---

## Task 4：测试 fixture 生成器 + 生成两个 docx fixture

**目的**：一次性生成两份可控内容的 docx 样本文件提交进 resources/fixtures，供 Task 5-10 patch / diff 测试消费（保证跨机器可复现）。

**Files:**
- Create: `src/test/java/com/lifepilot/document/testsupport/DocxFixtureGenerator.java`
- Create: `src/test/resources/fixtures/document/sample-contract.docx`（运行 Generator 产出）
- Create: `src/test/resources/fixtures/document/sample-with-styles.docx`（同上）

**前置阅读：**
- `src/main/java/com/lifepilot/document/generator/MarkdownToDocxGenerator.java`（Phase 2A 的 POI XWPF 调用范例）

- [ ] **Step 1：写 DocxFixtureGenerator**

新建 `src/test/java/com/lifepilot/document/testsupport/DocxFixtureGenerator.java`：

```java
package com.lifepilot.document.testsupport;

import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Docx 测试 fixture 一次性生成器 —— 手动触发，产出文件提交进 resources/fixtures。
 *
 * <p>两个产出：</p>
 * <ul>
 *   <li>sample-contract.docx：小型合同样本（标题 + 段落 + 1 个简单表格），供 4 种 op 基础测试</li>
 *   <li>sample-with-styles.docx：含加粗 / 斜体 / 字号混合的段落，专测 replace_text 跨 run 样式保留</li>
 * </ul>
 *
 * <p>@Disabled 默认跳过，需要刷新 fixture 时人工解开并跑单个用例。</p>
 *
 * @author zsg
 * @since 2026-04-21
 */
@Disabled("手动触发生成 fixture — 产出文件会写到 src/test/resources/fixtures/document/")
class DocxFixtureGenerator {

    private static final Path FIXTURE_DIR = Paths.get("src/test/resources/fixtures/document");

    @Test
    void 生成sample_contract() throws Exception {
        Files.createDirectories(FIXTURE_DIR);
        try (XWPFDocument doc = new XWPFDocument();
             FileOutputStream out = new FileOutputStream(
                     FIXTURE_DIR.resolve("sample-contract.docx").toFile())) {

            addHeading(doc, "技术服务合同", 1);
            addParagraph(doc, "甲方：示例科技有限公司");
            addParagraph(doc, "乙方：个人开发者张三");
            addHeading(doc, "第一章 服务范围", 2);
            addParagraph(doc, "乙方负责完成甲方委托的软件开发任务，交付物包括源代码与文档。");
            addHeading(doc, "第二章 付款条款", 2);
            addParagraph(doc, "风险如下：付款期限 30 天，若超期按月息 1% 计违约金。");
            addHeading(doc, "第三章 违约责任", 2);
            addParagraph(doc, "任一方违约需承担实际损失的赔偿责任。");

            XWPFTable table = doc.createTable(3, 3);
            XWPFTableRow header = table.getRow(0);
            header.getCell(0).setText("产品名称");
            header.getCell(1).setText("交付日期");
            header.getCell(2).setText("金额");
            XWPFTableRow r1 = table.getRow(1);
            r1.getCell(0).setText("后端模块");
            r1.getCell(1).setText("2026-05-01");
            r1.getCell(2).setText("50000");
            XWPFTableRow r2 = table.getRow(2);
            r2.getCell(0).setText("前端模块");
            r2.getCell(1).setText("2026-06-01");
            r2.getCell(2).setText("30000");

            doc.write(out);
        }
    }

    @Test
    void 生成sample_with_styles() throws Exception {
        Files.createDirectories(FIXTURE_DIR);
        try (XWPFDocument doc = new XWPFDocument();
             FileOutputStream out = new FileOutputStream(
                     FIXTURE_DIR.resolve("sample-with-styles.docx").toFile())) {

            // 段落：混合 3 个 run —— 普通 / 加粗 / 斜体 红色
            XWPFParagraph para = doc.createParagraph();
            para.setAlignment(ParagraphAlignment.LEFT);

            XWPFRun run1 = para.createRun();
            run1.setText("开头段 ");
            run1.setFontFamily("Calibri");

            XWPFRun run2 = para.createRun();
            run2.setText("重要内容");
            run2.setBold(true);
            run2.setFontFamily("Calibri");
            run2.setFontSize(14);

            XWPFRun run3 = para.createRun();
            run3.setText(" 结尾段");
            run3.setItalic(true);
            run3.setColor("C00000");
            run3.setFontFamily("Calibri");

            // 第二段：纯文本（供 replace_text 基础案例）
            XWPFParagraph p2 = doc.createParagraph();
            XWPFRun r = p2.createRun();
            r.setText("第二段纯文本内容，其中有付款期限 30 天这个短语。");
            r.setFontFamily("Calibri");

            doc.write(out);
        }
    }

    private static void addHeading(XWPFDocument doc, String text, int level) {
        XWPFParagraph p = doc.createParagraph();
        p.setStyle("Heading" + level);
        XWPFRun r = p.createRun();
        r.setText(text);
        r.setBold(true);
        r.setFontSize(level == 1 ? 18 : 14);
    }

    private static void addParagraph(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        XWPFRun r = p.createRun();
        r.setText(text);
    }
}
```

- [ ] **Step 2：手动触发生成 fixture**

Run: `mvn test -q -Dtest='DocxFixtureGenerator' -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false -P !default -pl . -Dfile.encoding=UTF-8 -DJunit.Platform.configuration=junit-platform.properties`

若 `@Disabled` 阻止运行，临时去掉 `@Disabled` 跑一次：

```bash
mvn test -q -Dtest='DocxFixtureGenerator#生成sample_contract+生成sample_with_styles' -DfailIfNoTests=false
```

Expected：`src/test/resources/fixtures/document/sample-contract.docx` 与 `sample-with-styles.docx` 两个文件生成。

**注意**：跑完后把 `@Disabled` 加回去，避免后续 CI 覆写 fixture。

- [ ] **Step 3：手工用 Word / LibreOffice 打开两个 fixture 验证可读**

目视确认：
- `sample-contract.docx` 含 3 章标题、付款条款段、3x3 表格
- `sample-with-styles.docx` 第一段含 3 个不同样式的 run

- [ ] **Step 4：commit**

```bash
git add src/test/java/com/lifepilot/document/testsupport/DocxFixtureGenerator.java \
        src/test/resources/fixtures/document/sample-contract.docx \
        src/test/resources/fixtures/document/sample-with-styles.docx
git commit -m "test(document): Phase 3A Task 4 — docx fixture 生成器 + 两个 fixture 文件"
```

---

## Task 5：TextAnchorLocator — 文本锚点定位辅助

**目的**：提供通用的"在 XWPFDocument 全文中定位 target 文本并返回段落索引 + run 偏移范围"能力，被 4 种 op 共享。

**Files:**
- Create: `src/main/java/com/lifepilot/document/patch/docx/ParagraphRunRange.java`
- Create: `src/main/java/com/lifepilot/document/patch/docx/TextAnchorLocator.java`
- Test: `src/test/java/com/lifepilot/document/patch/docx/TextAnchorLocator_定位测试.java`

**前置阅读：**
- `src/main/java/com/lifepilot/knowledge/parser/WordParser.java`（既有 POI run 拼接逻辑，反向参照）

- [ ] **Step 1：ParagraphRunRange record**

新建 `src/main/java/com/lifepilot/document/patch/docx/ParagraphRunRange.java`：

```java
package com.lifepilot.document.patch.docx;

/**
 * 文本锚点在段落中的范围定位 —— 段落索引 + 起止 run 下标 + run 内字符偏移。
 *
 * <p>例：一段有 3 个 run ["付款期限 ", "30", " 天"]，target="期限 30"，
 * 返回 {@code paragraphIndex=?, startRunIndex=0, startCharOffset=2, endRunIndex=1, endCharOffset=2}。
 * 其中 endCharOffset 是"结束位置的下一个索引"（开区间），便于后续 substring。</p>
 *
 * @author zsg
 * @since 2026-04-21
 */
public record ParagraphRunRange(
        int paragraphIndex,
        int startRunIndex,
        int startCharOffset,
        int endRunIndex,
        int endCharOffset
) {}
```

- [ ] **Step 2：TextAnchorLocator 主逻辑**

新建 `src/main/java/com/lifepilot/document/patch/docx/TextAnchorLocator.java`：

```java
package com.lifepilot.document.patch.docx;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 文本锚点定位器 —— 段内查找 {@code beforeContext + target + afterContext}
 * 拼接串的唯一出现位置，返回 target 部分的 run 范围。
 *
 * <p>设计权衡：只在**同一段落**内定位（POI 的 run 天然段内组织，跨段落定位逻辑复杂且实际
 * 很少见于 LLM 规划）。若 LLM 传来的 before/after context 跨段了，定位会失败，应返回
 * locator_not_found，让 LLM 重试。</p>
 *
 * @author zsg
 * @since 2026-04-21
 */
public class TextAnchorLocator {

    /**
     * 定位 target 在文档中的唯一出现。
     *
     * @return Optional.empty 表示 0 次或 >1 次命中；命中时返回唯一 ParagraphRunRange
     */
    public Optional<ParagraphRunRange> locate(XWPFDocument document,
                                              String beforeContext,
                                              String target,
                                              String afterContext) {
        String full = beforeContext + target + afterContext;
        List<ParagraphRunRange> hits = new ArrayList<>();

        List<XWPFParagraph> paragraphs = document.getParagraphs();
        for (int pIndex = 0; pIndex < paragraphs.size(); pIndex++) {
            XWPFParagraph para = paragraphs.get(pIndex);
            String paraText = para.getText();
            int fromIndex = 0;
            while (true) {
                int hit = paraText.indexOf(full, fromIndex);
                if (hit < 0) break;
                int targetStart = hit + beforeContext.length();
                int targetEnd = targetStart + target.length();
                ParagraphRunRange range = mapCharOffsetToRunRange(para, pIndex, targetStart, targetEnd);
                if (range != null) {
                    hits.add(range);
                    if (hits.size() > 1) return Optional.empty();
                }
                fromIndex = hit + 1;
            }
        }
        return hits.size() == 1 ? Optional.of(hits.get(0)) : Optional.empty();
    }

    /**
     * 直接按段落完整文本查找（InsertParagraphAfterOp / DeleteParagraphOp 使用）。
     *
     * @return 命中唯一段落的下标；0 或 >1 返回 -1
     */
    public int locateParagraphIndexByFullText(XWPFDocument document, String paragraphFullText) {
        List<XWPFParagraph> paragraphs = document.getParagraphs();
        int hitIndex = -1;
        for (int i = 0; i < paragraphs.size(); i++) {
            if (paragraphs.get(i).getText().equals(paragraphFullText)) {
                if (hitIndex != -1) return -1;  // 多命中
                hitIndex = i;
            }
        }
        return hitIndex;
    }

    /** 把段内字符绝对偏移 [start, end) 映射到 run 范围。 */
    private ParagraphRunRange mapCharOffsetToRunRange(XWPFParagraph para, int paragraphIndex,
                                                       int charStart, int charEnd) {
        List<XWPFRun> runs = para.getRuns();
        int cursor = 0;
        int startRunIdx = -1, startOffset = 0;
        int endRunIdx = -1, endOffset = 0;
        for (int i = 0; i < runs.size(); i++) {
            String runText = runs.get(i).text();
            if (runText == null) runText = "";
            int runLen = runText.length();
            int runStart = cursor;
            int runEnd = cursor + runLen;

            if (startRunIdx == -1 && charStart >= runStart && charStart < runEnd) {
                startRunIdx = i;
                startOffset = charStart - runStart;
            }
            if (charEnd > runStart && charEnd <= runEnd) {
                endRunIdx = i;
                endOffset = charEnd - runStart;
            }
            cursor = runEnd;
        }
        if (startRunIdx < 0 || endRunIdx < 0) return null;
        return new ParagraphRunRange(paragraphIndex, startRunIdx, startOffset, endRunIdx, endOffset);
    }
}
```

- [ ] **Step 3：写定位测试**

新建 `src/test/java/com/lifepilot/document/patch/docx/TextAnchorLocator_定位测试.java`：

```java
package com.lifepilot.document.patch.docx;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TextAnchorLocator 定位测试 —— 覆盖唯一命中 / 0 命中 / 多命中 / 跨 run 场景。
 *
 * @author zsg
 * @since 2026-04-21
 */
class TextAnchorLocator_定位测试 {

    private static final Path CONTRACT = Path.of("src/test/resources/fixtures/document/sample-contract.docx");
    private static final Path STYLES = Path.of("src/test/resources/fixtures/document/sample-with-styles.docx");

    private final TextAnchorLocator locator = new TextAnchorLocator();

    @Test
    @DisplayName("唯一命中返回 ParagraphRunRange")
    void 唯一命中返回范围() throws Exception {
        try (InputStream in = Files.newInputStream(CONTRACT);
             XWPFDocument doc = new XWPFDocument(in)) {
            Optional<ParagraphRunRange> r = locator.locate(doc, "风险如下：", "付款期限 30 天", "，若超期");
            assertThat(r).isPresent();
            assertThat(r.get().paragraphIndex()).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    @DisplayName("0 命中返回 empty")
    void 零命中返回empty() throws Exception {
        try (InputStream in = Files.newInputStream(CONTRACT);
             XWPFDocument doc = new XWPFDocument(in)) {
            Optional<ParagraphRunRange> r = locator.locate(doc, "", "不存在的文字片段 XYZ", "");
            assertThat(r).isEmpty();
        }
    }

    @Test
    @DisplayName("多命中返回 empty 防误改")
    void 多命中返回empty() throws Exception {
        try (InputStream in = Files.newInputStream(CONTRACT);
             XWPFDocument doc = new XWPFDocument(in)) {
            // 不加任何 context，"第" 在多个标题里出现 → 多命中
            Optional<ParagraphRunRange> r = locator.locate(doc, "", "第", "");
            assertThat(r).isEmpty();
        }
    }

    @Test
    @DisplayName("跨 run 的 target 能正确定位")
    void 跨run的target能定位() throws Exception {
        try (InputStream in = Files.newInputStream(STYLES);
             XWPFDocument doc = new XWPFDocument(in)) {
            // sample-with-styles 第一段三 run：["开头段 ", "重要内容", " 结尾段"]
            // target "段 重要" 跨 run 1-2
            Optional<ParagraphRunRange> r = locator.locate(doc, "开头", "段 重要", "内容");
            assertThat(r).isPresent();
            var range = r.get();
            assertThat(range.startRunIndex()).isZero();
            assertThat(range.endRunIndex()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("locateParagraphIndexByFullText 精确匹配")
    void 按段落全文精确匹配() throws Exception {
        try (InputStream in = Files.newInputStream(CONTRACT);
             XWPFDocument doc = new XWPFDocument(in)) {
            int idx = locator.locateParagraphIndexByFullText(doc, "第三章 违约责任");
            assertThat(idx).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    @DisplayName("按段落全文找不到返回 -1")
    void 段落全文找不到返回负一() throws Exception {
        try (InputStream in = Files.newInputStream(CONTRACT);
             XWPFDocument doc = new XWPFDocument(in)) {
            int idx = locator.locateParagraphIndexByFullText(doc, "不存在的段落");
            assertThat(idx).isEqualTo(-1);
        }
    }
}
```

- [ ] **Step 4：跑测试**

Run: `mvn test -q -Dtest='TextAnchorLocator*'`
Expected: 6 个用例通过。

- [ ] **Step 5：commit**

```bash
git add src/main/java/com/lifepilot/document/patch/docx/ParagraphRunRange.java \
        src/main/java/com/lifepilot/document/patch/docx/TextAnchorLocator.java \
        src/test/java/com/lifepilot/document/patch/docx/TextAnchorLocator_定位测试.java
git commit -m "feat(document): Phase 3A Task 5 — TextAnchorLocator 文本锚点定位"
```

---

## Task 6：DocxPatchEngine 骨架 + replace_text 实现

**目的**：建 POI 内存 patch 引擎骨架（dispatch 4 种 op 的 switch / 内存回滚语义），实现第一个 op `replace_text`（保样式跨 run 替换）。

**Files:**
- Create: `src/main/java/com/lifepilot/document/patch/docx/DocxPatchEngine.java`
- Create: `src/main/java/com/lifepilot/document/patch/docx/AppliedOp.java`
- Test: `src/test/java/com/lifepilot/document/patch/docx/DocxPatchEngine_四种操作测试.java`（先只覆盖 replace_text）

**前置阅读：**
- `src/main/java/com/lifepilot/document/patch/docx/TextAnchorLocator.java`（Task 5 已建）
- 本 Task 的 POI run 拆分处理参考 POI XWPFRun API（`setText(newText, position=0)`、`getText(position=0)`）

- [ ] **Step 1：AppliedOp record**

新建 `src/main/java/com/lifepilot/document/patch/docx/AppliedOp.java`：

```java
package com.lifepilot.document.patch.docx;

import com.lifepilot.document.patch.DocumentPatchOperation;
import org.springframework.lang.Nullable;

/**
 * 成功应用的 op + 定位结果 —— 供 DocxDiffBuilder 构造 diff 用。
 *
 * @param op    原 op 对象
 * @param range 定位范围（replace_text 必填；其他 op 段落级时填 paragraph-only range，cell 场景填 null）
 * @author zsg
 * @since 2026-04-21
 */
public record AppliedOp(DocumentPatchOperation op, @Nullable ParagraphRunRange range) {}
```

- [ ] **Step 2：DocxPatchEngine 骨架**

新建 `src/main/java/com/lifepilot/document/patch/docx/DocxPatchEngine.java`：

```java
package com.lifepilot.document.patch.docx;

import com.lifepilot.document.patch.AddTableRowOp;
import com.lifepilot.document.patch.DeleteParagraphOp;
import com.lifepilot.document.patch.DocumentPatchOperation;
import com.lifepilot.document.patch.FailedOp;
import com.lifepilot.document.patch.InsertParagraphAfterOp;
import com.lifepilot.document.patch.ReplaceTextOp;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * docx patch 引擎 —— 纯内存操作 XWPFDocument。
 *
 * <p>职责：对给定 XWPFDocument 按顺序应用 ops。任一 op 定位失败立即中止，
 * 已改动的 XWPFDocument 由调用方丢弃（不写盘即天然回滚）。成功时返回已应用 op 列表供
 * DiffBuilder 使用。</p>
 *
 * <p>本 Task 只实现 replace_text；其他 3 个 op 在 Task 7 补齐。</p>
 *
 * @author zsg
 * @since 2026-04-21
 */
public class DocxPatchEngine {

    private static final Logger log = LoggerFactory.getLogger(DocxPatchEngine.class);

    private final TextAnchorLocator locator;

    public DocxPatchEngine(TextAnchorLocator locator) {
        this.locator = locator;
    }

    /** patch 执行结果：成功时 appliedOps 非空；失败时 failedOps 非空。 */
    public record EngineResult(boolean success, List<AppliedOp> appliedOps, List<FailedOp> failedOps) {}

    public EngineResult apply(XWPFDocument document, List<DocumentPatchOperation> ops) {
        List<AppliedOp> applied = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            DocumentPatchOperation op = ops.get(i);
            try {
                AppliedOp result = switch (op) {
                    case ReplaceTextOp r -> applyReplaceText(document, r);
                    case InsertParagraphAfterOp r -> applyInsertAfter(document, r);
                    case DeleteParagraphOp r -> applyDeleteParagraph(document, r);
                    case AddTableRowOp r -> applyAddTableRow(document, r);
                };
                applied.add(result);
            } catch (PatchLocatorException e) {
                return new EngineResult(false, List.of(),
                        List.of(new FailedOp(i, opType(op), e.reason, e.matchCount, e.hint)));
            } catch (RuntimeException e) {
                log.warn("patch op #{} 执行异常：op={}, err={}", i, op.getClass().getSimpleName(), e.getMessage());
                return new EngineResult(false, List.of(),
                        List.of(new FailedOp(i, opType(op), "execution_error", -1, e.getMessage())));
            }
        }
        return new EngineResult(true, applied, List.of());
    }

    // ===== replace_text =====

    private AppliedOp applyReplaceText(XWPFDocument doc, ReplaceTextOp op) {
        Optional<ParagraphRunRange> rangeOpt = locator.locate(
                doc, op.beforeContext(), op.target(), op.afterContext());
        if (rangeOpt.isEmpty()) {
            throw PatchLocatorException.notUniqueOrMissing(
                    "locator_not_unique_or_missing",
                    "before_context+target+after_context 在文档中未唯一出现，建议加长锚点");
        }
        ParagraphRunRange range = rangeOpt.get();
        XWPFParagraph para = doc.getParagraphs().get(range.paragraphIndex());
        replaceTextInRange(para, range, op.newText());
        return new AppliedOp(op, range);
    }

    /**
     * 在 [range.startRun, range.endRun] 范围内替换文本：
     * - 首 run：保留其前缀（到 startCharOffset），拼接 newText
     * - 中间 run：清空文本
     * - 尾 run：保留其后缀（从 endCharOffset 开始）；若 startRun == endRun，尾缀拼在首 run 末尾
     *
     * 样式保留策略：首 run 的 font/bold/color/size 自动传承（因为首 run 的文本未完全清除，只是替换中段）。
     */
    private void replaceTextInRange(XWPFParagraph para, ParagraphRunRange range, String newText) {
        List<XWPFRun> runs = para.getRuns();
        XWPFRun startRun = runs.get(range.startRunIndex());
        String startOriginal = getRunText(startRun);

        if (range.startRunIndex() == range.endRunIndex()) {
            String replaced = startOriginal.substring(0, range.startCharOffset())
                    + newText
                    + startOriginal.substring(range.endCharOffset());
            startRun.setText(replaced, 0);
            return;
        }

        XWPFRun endRun = runs.get(range.endRunIndex());
        String endOriginal = getRunText(endRun);

        // 首 run：前缀 + 新文本
        startRun.setText(startOriginal.substring(0, range.startCharOffset()) + newText, 0);
        // 中间 run：清空
        for (int i = range.startRunIndex() + 1; i < range.endRunIndex(); i++) {
            runs.get(i).setText("", 0);
        }
        // 尾 run：保留后缀
        endRun.setText(endOriginal.substring(range.endCharOffset()), 0);
    }

    private String getRunText(XWPFRun run) {
        String t = run.getText(0);
        return t == null ? "" : t;
    }

    // ===== 以下 3 个 op 在 Task 7 实现，当前先抛 UnsupportedOperationException =====

    private AppliedOp applyInsertAfter(XWPFDocument doc, InsertParagraphAfterOp op) {
        throw new UnsupportedOperationException("insert_paragraph_after 将在 Task 7 实现");
    }

    private AppliedOp applyDeleteParagraph(XWPFDocument doc, DeleteParagraphOp op) {
        throw new UnsupportedOperationException("delete_paragraph 将在 Task 7 实现");
    }

    private AppliedOp applyAddTableRow(XWPFDocument doc, AddTableRowOp op) {
        throw new UnsupportedOperationException("add_table_row 将在 Task 7 实现");
    }

    // ===== 辅助 =====

    static String opType(DocumentPatchOperation op) {
        return switch (op) {
            case ReplaceTextOp r -> "replace_text";
            case InsertParagraphAfterOp r -> "insert_paragraph_after";
            case DeleteParagraphOp r -> "delete_paragraph";
            case AddTableRowOp r -> "add_table_row";
        };
    }

    /** patch 定位失败的受检异常（仅 engine 内部使用，apply 转成 FailedOp）。 */
    static final class PatchLocatorException extends RuntimeException {
        final String reason;
        final int matchCount;
        final String hint;

        private PatchLocatorException(String reason, int matchCount, String hint) {
            super(reason + ": " + hint);
            this.reason = reason;
            this.matchCount = matchCount;
            this.hint = hint;
        }

        static PatchLocatorException notUniqueOrMissing(String reason, String hint) {
            return new PatchLocatorException(reason, 0, hint);
        }

        static PatchLocatorException cellsMismatch(int expected, int actual) {
            return new PatchLocatorException(
                    "cells_mismatch", -1,
                    "cells 长度 " + actual + " 与表格列数 " + expected + " 不符");
        }
    }
}
```

- [ ] **Step 3：写 replace_text 测试**

新建 `src/test/java/com/lifepilot/document/patch/docx/DocxPatchEngine_四种操作测试.java`：

```java
package com.lifepilot.document.patch.docx;

import com.lifepilot.document.patch.DocumentPatchOperation;
import com.lifepilot.document.patch.ReplaceTextOp;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DocxPatchEngine 四种 op 行为测试 —— 本 Task 先覆盖 replace_text，Task 7 补齐其余。
 *
 * @author zsg
 * @since 2026-04-21
 */
class DocxPatchEngine_四种操作测试 {

    private static final Path CONTRACT = Path.of("src/test/resources/fixtures/document/sample-contract.docx");
    private static final Path STYLES = Path.of("src/test/resources/fixtures/document/sample-with-styles.docx");

    private final DocxPatchEngine engine = new DocxPatchEngine(new TextAnchorLocator());

    @Test
    @DisplayName("replace_text 单 run 内替换成功")
    void replace_text单run内替换() throws Exception {
        try (InputStream in = Files.newInputStream(CONTRACT);
             XWPFDocument doc = new XWPFDocument(in)) {
            var op = new ReplaceTextOp("风险如下：", "付款期限 30 天", "，若超期", "付款期限 15 天", "缩短期限");

            var result = engine.apply(doc, List.<DocumentPatchOperation>of(op));

            assertThat(result.success()).isTrue();
            assertThat(result.appliedOps()).hasSize(1);
            // 验证文本被改
            String fulltext = extractAllText(doc);
            assertThat(fulltext).contains("付款期限 15 天").doesNotContain("付款期限 30 天");
        }
    }

    @Test
    @DisplayName("replace_text 跨 run 保样式")
    void replace_text跨run保样式() throws Exception {
        try (InputStream in = Files.newInputStream(STYLES);
             XWPFDocument doc = new XWPFDocument(in)) {
            // 跨 run 1-2：run 1 "重要内容" 加粗, run 2 " 结尾段" 斜体红色。
            // target "内容 结尾" 跨这两个 run
            var op = new ReplaceTextOp("重要", "内容 结尾", "段", "文字 尾部", null);

            var result = engine.apply(doc, List.<DocumentPatchOperation>of(op));

            assertThat(result.success()).isTrue();
            XWPFParagraph para = doc.getParagraphs().get(0);
            // run 1 应保留加粗
            XWPFRun run1 = para.getRuns().get(1);
            assertThat(run1.isBold()).isTrue();
            // run 2 应保留斜体 + 红色
            XWPFRun run2 = para.getRuns().get(2);
            assertThat(run2.isItalic()).isTrue();
            assertThat(run2.getColor()).isEqualTo("C00000");
            // 拼接文本应是：开头段 重要文字 尾部段
            assertThat(para.getText()).isEqualTo("开头段 重要文字 尾部段");
        }
    }

    @Test
    @DisplayName("replace_text locator 多命中整批失败")
    void replace_text多命中失败() throws Exception {
        try (InputStream in = Files.newInputStream(CONTRACT);
             XWPFDocument doc = new XWPFDocument(in)) {
            var op = new ReplaceTextOp("", "第", "", "X", null);

            var result = engine.apply(doc, List.<DocumentPatchOperation>of(op));

            assertThat(result.success()).isFalse();
            assertThat(result.failedOps()).hasSize(1);
            assertThat(result.failedOps().get(0).opType()).isEqualTo("replace_text");
        }
    }

    @Test
    @DisplayName("replace_text 事务性 — 第二 op 失败整批回滚")
    void replace_text事务性() throws Exception {
        try (InputStream in = Files.newInputStream(CONTRACT);
             XWPFDocument doc = new XWPFDocument(in)) {
            var beforeText = extractAllText(doc);
            var good = new ReplaceTextOp("风险如下：", "付款期限 30 天", "，若超期", "付款期限 15 天", null);
            var bad = new ReplaceTextOp("", "绝不存在的文字", "", "Y", null);

            var result = engine.apply(doc, List.<DocumentPatchOperation>of(good, bad));

            assertThat(result.success()).isFalse();
            // 引擎返回 failure；文档虽已被内存改动，但调用方会丢弃，不写盘。
            // 这里验证返回的 failedOps.opIndex=1（bad 是第二个）。
            assertThat(result.failedOps().get(0).opIndex()).isEqualTo(1);
        }
    }

    private static String extractAllText(XWPFDocument doc) {
        StringBuilder sb = new StringBuilder();
        for (XWPFParagraph p : doc.getParagraphs()) {
            sb.append(p.getText()).append("\n");
        }
        return sb.toString();
    }
}
```

- [ ] **Step 4：跑测试**

Run: `mvn test -q -Dtest='DocxPatchEngine*'`
Expected: 4 个用例通过。

- [ ] **Step 5：commit**

```bash
git add src/main/java/com/lifepilot/document/patch/docx/DocxPatchEngine.java \
        src/main/java/com/lifepilot/document/patch/docx/AppliedOp.java \
        src/test/java/com/lifepilot/document/patch/docx/DocxPatchEngine_四种操作测试.java
git commit -m "feat(document): Phase 3A Task 6 — DocxPatchEngine 骨架 + replace_text 实现"
```

---

## Task 7：DocxPatchEngine — 剩余 3 个 op（insert_paragraph_after / delete_paragraph / add_table_row）

**目的**：补齐 Task 6 预留的三个 op 实现，扩充测试覆盖。

**Files:**
- Modify: `src/main/java/com/lifepilot/document/patch/docx/DocxPatchEngine.java`
- Test: `src/test/java/com/lifepilot/document/patch/docx/DocxPatchEngine_四种操作测试.java`（补测）

**前置阅读：**
- POI XWPFDocument API：`createParagraph() / getDocument() / insertXmlObject` 等
- POI XWPFTable API：`createRow() / getRow() / getTables()`

- [ ] **Step 1：实现 applyInsertAfter**

替换 `DocxPatchEngine.applyInsertAfter` 占位实现为：

```java
private AppliedOp applyInsertAfter(XWPFDocument doc, InsertParagraphAfterOp op) {
    int anchorIdx = locator.locateParagraphIndexByFullText(doc, op.anchorParagraphText());
    if (anchorIdx < 0) {
        throw PatchLocatorException.notUniqueOrMissing(
                "anchor_paragraph_not_unique_or_missing",
                "anchor_paragraph_text 在文档中未唯一匹配一个段落");
    }
    XWPFParagraph anchor = doc.getParagraphs().get(anchorIdx);
    // POI XWPF 没有直接"在指定位置插入段落"的 high-level API，
    // 但可以通过 org.apache.xmlbeans CTP 的 XmlCursor 在 anchor 的 XML 后插入新段落。
    for (int i = op.newParagraphs().size() - 1; i >= 0; i--) {
        NewParagraph np = op.newParagraphs().get(i);
        org.apache.xmlbeans.XmlCursor cursor = anchor.getCTP().newCursor();
        cursor.toEndToken();
        cursor.toNextToken();
        XWPFParagraph created = doc.insertNewParagraph(cursor);
        applyStyle(created, np.style());
        XWPFRun run = created.createRun();
        run.setText(np.text());
        cursor.dispose();
    }
    return new AppliedOp(op, new ParagraphRunRange(anchorIdx, -1, -1, -1, -1));
}

/** 给段落套 styleId。缺失或系统不认识时退回不设（段落保持默认样式）。 */
private void applyStyle(XWPFParagraph para, String style) {
    if (style == null || style.isBlank() || NewParagraph.STYLE_NORMAL.equals(style)) return;
    try {
        para.setStyle(style);
    } catch (RuntimeException e) {
        // 样式不存在等异常 — 安全退回
    }
}
```

并在 `DocxPatchEngine.java` 顶部 imports 追加：

```java
import com.lifepilot.document.patch.NewParagraph;
```

- [ ] **Step 2：实现 applyDeleteParagraph**

替换占位：

```java
private AppliedOp applyDeleteParagraph(XWPFDocument doc, DeleteParagraphOp op) {
    int idx = locator.locateParagraphIndexByFullText(doc, op.paragraphText());
    if (idx < 0) {
        throw PatchLocatorException.notUniqueOrMissing(
                "paragraph_text_not_unique_or_missing",
                "paragraph_text 在文档中未唯一匹配一个段落");
    }
    // XWPFDocument.removeBodyElement(pos) 按 body element 下标删除；段落/表格混合时需要用整体下标
    int bodyPos = doc.getPosOfParagraph(doc.getParagraphs().get(idx));
    doc.removeBodyElement(bodyPos);
    return new AppliedOp(op, new ParagraphRunRange(idx, -1, -1, -1, -1));
}
```

- [ ] **Step 3：实现 applyAddTableRow**

替换占位：

```java
private AppliedOp applyAddTableRow(XWPFDocument doc, AddTableRowOp op) {
    org.apache.poi.xwpf.usermodel.XWPFTable targetTable = findTableByAnchor(doc, op.tableAnchorText());
    if (targetTable == null) {
        throw PatchLocatorException.notUniqueOrMissing(
                "table_anchor_not_unique_or_missing",
                "table_anchor_text 在任何表格单元格中未唯一匹配");
    }
    int cols = targetTable.getRow(0).getTableCells().size();
    if (op.cells().size() != cols) {
        throw PatchLocatorException.cellsMismatch(cols, op.cells().size());
    }
    org.apache.poi.xwpf.usermodel.XWPFTableRow newRow;
    if (AddTableRowOp.POSITION_START.equals(op.position())) {
        // 在第 0 行前插入 — 需用 insertNewTableRow(0)
        org.apache.xmlbeans.XmlCursor cursor = targetTable.getRow(0).getCtRow().newCursor();
        newRow = targetTable.insertNewTableRow(0);
        cursor.dispose();
        fillRowCells(newRow, op.cells(), cols);
    } else {
        newRow = targetTable.createRow();
        fillRowCells(newRow, op.cells(), cols);
    }
    return new AppliedOp(op, null);
}

/** 按 anchorText 在文档所有表格单元格里唯一查找表格。*/
private org.apache.poi.xwpf.usermodel.XWPFTable findTableByAnchor(XWPFDocument doc, String anchorText) {
    org.apache.poi.xwpf.usermodel.XWPFTable hit = null;
    for (org.apache.poi.xwpf.usermodel.XWPFTable table : doc.getTables()) {
        for (var row : table.getRows()) {
            for (var cell : row.getTableCells()) {
                if (cell.getText().contains(anchorText)) {
                    if (hit != null && hit != table) return null;  // 多表命中
                    hit = table;
                }
            }
        }
    }
    return hit;
}

/** createRow() 默认会给新行每 cell 创建 1 个空 cell（按首行列数对齐），此处只需 setText。
 *  insertNewTableRow(0) 不会自动填充 cell —— 需要我们自己补齐到 {@code cols} 个。 */
private void fillRowCells(org.apache.poi.xwpf.usermodel.XWPFTableRow row, List<String> cells, int cols) {
    while (row.getTableCells().size() < cols) {
        row.createCell();
    }
    for (int i = 0; i < cols; i++) {
        row.getCell(i).removeParagraph(0);
        org.apache.poi.xwpf.usermodel.XWPFParagraph p = row.getCell(i).addParagraph();
        XWPFRun r = p.createRun();
        r.setText(cells.get(i));
    }
}
```

- [ ] **Step 4：扩展测试覆盖剩余 3 op**

在 `DocxPatchEngine_四种操作测试.java` 追加：

```java
    @Test
    @DisplayName("insert_paragraph_after 在锚点段后插入新段")
    void insert_paragraph_after在锚点后插入() throws Exception {
        try (InputStream in = Files.newInputStream(CONTRACT);
             XWPFDocument doc = new XWPFDocument(in)) {
            var op = new com.lifepilot.document.patch.InsertParagraphAfterOp(
                    "第三章 违约责任",
                    List.of(new com.lifepilot.document.patch.NewParagraph(
                            "补充：违约金上限为合同总额的 20%。", "Normal")),
                    "补充违约条款");

            var result = engine.apply(doc, List.<DocumentPatchOperation>of(op));

            assertThat(result.success()).isTrue();
            String full = extractAllText(doc);
            assertThat(full).contains("补充：违约金上限为合同总额的 20%。");
        }
    }

    @Test
    @DisplayName("delete_paragraph 唯一命中后段落被移除")
    void delete_paragraph移除段落() throws Exception {
        try (InputStream in = Files.newInputStream(CONTRACT);
             XWPFDocument doc = new XWPFDocument(in)) {
            var op = new com.lifepilot.document.patch.DeleteParagraphOp(
                    "任一方违约需承担实际损失的赔偿责任。", "冗余");

            var result = engine.apply(doc, List.<DocumentPatchOperation>of(op));

            assertThat(result.success()).isTrue();
            String full = extractAllText(doc);
            assertThat(full).doesNotContain("任一方违约需承担实际损失的赔偿责任。");
        }
    }

    @Test
    @DisplayName("add_table_row position=end 表格追加新行")
    void add_table_row_end追加行() throws Exception {
        try (InputStream in = Files.newInputStream(CONTRACT);
             XWPFDocument doc = new XWPFDocument(in)) {
            var op = new com.lifepilot.document.patch.AddTableRowOp(
                    "产品名称", "end", List.of("测试模块", "2026-07-01", "10000"), null);

            int beforeRows = doc.getTables().get(0).getNumberOfRows();
            var result = engine.apply(doc, List.<DocumentPatchOperation>of(op));

            assertThat(result.success()).isTrue();
            var table = doc.getTables().get(0);
            assertThat(table.getNumberOfRows()).isEqualTo(beforeRows + 1);
            var lastRow = table.getRow(table.getNumberOfRows() - 1);
            assertThat(lastRow.getCell(0).getText()).isEqualTo("测试模块");
            assertThat(lastRow.getCell(1).getText()).isEqualTo("2026-07-01");
            assertThat(lastRow.getCell(2).getText()).isEqualTo("10000");
        }
    }

    @Test
    @DisplayName("add_table_row cells 长度与列数不符失败")
    void add_table_row长度不符失败() throws Exception {
        try (InputStream in = Files.newInputStream(CONTRACT);
             XWPFDocument doc = new XWPFDocument(in)) {
            var op = new com.lifepilot.document.patch.AddTableRowOp(
                    "产品名称", "end", List.of("不够", "列"), null);

            var result = engine.apply(doc, List.<DocumentPatchOperation>of(op));

            assertThat(result.success()).isFalse();
            assertThat(result.failedOps().get(0).reason()).isEqualTo("cells_mismatch");
        }
    }
```

- [ ] **Step 5：跑全部 engine 测试**

Run: `mvn test -q -Dtest='DocxPatchEngine*'`
Expected: 8 个用例全通。

- [ ] **Step 6：commit**

```bash
git add src/main/java/com/lifepilot/document/patch/docx/DocxPatchEngine.java \
        src/test/java/com/lifepilot/document/patch/docx/DocxPatchEngine_四种操作测试.java
git commit -m "feat(document): Phase 3A Task 7 — DocxPatchEngine 补齐 insert/delete/add_table_row"
```

---

## Task 8：DocxDiffBuilder — 生成 diff JSON 供前端渲染

**目的**：按"已应用 op + 定位结果"反推 diff segments，不跑完整文本 diff 算法。输出 spec §2.5 定义的 JSON 结构。

**Files:**
- Create: `src/main/java/com/lifepilot/document/patch/docx/DocxDiffBuilder.java`
- Test: `src/test/java/com/lifepilot/document/patch/docx/DocxDiffBuilder_构造测试.java`

**前置阅读：**
- spec §2.5（diff JSON 结构）
- `pom.xml` 中 Jackson 依赖（com.fasterxml.jackson.databind.ObjectMapper，项目已有）

- [ ] **Step 1：写 DocxDiffBuilder**

新建 `src/main/java/com/lifepilot/document/patch/docx/DocxDiffBuilder.java`：

```java
package com.lifepilot.document.patch.docx;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.document.patch.AddTableRowOp;
import com.lifepilot.document.patch.DeleteParagraphOp;
import com.lifepilot.document.patch.DocumentPatchOperation;
import com.lifepilot.document.patch.InsertParagraphAfterOp;
import com.lifepilot.document.patch.NewParagraph;
import com.lifepilot.document.patch.ReplaceTextOp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 按已应用的 op + 定位结果构造 diff JSON。
 *
 * <p>输出结构见 spec §2.5：</p>
 * <pre>{
 *   documentId, fromVersion, toVersion, summary,
 *   changes: [{patch_id, op, paragraph_index, paragraph_preview, segments, reason}]
 * }</pre>
 *
 * @author zsg
 * @since 2026-04-21
 */
public class DocxDiffBuilder {

    private final ObjectMapper mapper = new ObjectMapper();

    public String build(String documentId, int fromVersion, int toVersion,
                        List<AppliedOp> applied) throws JsonProcessingException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("documentId", documentId);
        root.put("fromVersion", fromVersion);
        root.put("toVersion", toVersion);
        root.put("summary", summarize(applied));

        List<Map<String, Object>> changes = new ArrayList<>();
        for (AppliedOp a : applied) {
            changes.add(changeEntry(a));
        }
        root.put("changes", changes);

        return mapper.writeValueAsString(root);
    }

    /** 一句话摘要，供 session_documents / version 记录里留档。 */
    public String summarize(List<AppliedOp> applied) {
        Map<String, Integer> counter = new LinkedHashMap<>();
        for (AppliedOp a : applied) {
            String k = DocxPatchEngine.opType(a.op());
            counter.merge(k, 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder("共 ").append(applied.size()).append(" 处修改");
        if (!counter.isEmpty()) {
            sb.append("（");
            boolean first = true;
            for (var e : counter.entrySet()) {
                if (!first) sb.append(", ");
                sb.append(e.getKey()).append(" x").append(e.getValue());
                first = false;
            }
            sb.append("）");
        }
        return sb.toString();
    }

    private Map<String, Object> changeEntry(AppliedOp a) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("patch_id", UUID.randomUUID().toString());
        entry.put("op", DocxPatchEngine.opType(a.op()));
        int pIndex = a.range() == null ? -1 : a.range().paragraphIndex();
        entry.put("paragraph_index", pIndex);

        switch (a.op()) {
            case ReplaceTextOp r -> {
                entry.put("paragraph_preview", r.beforeContext() + "[" + r.target() + "]" + r.afterContext());
                entry.put("segments", List.of(
                        segment("keep", r.beforeContext()),
                        segment("delete", r.target()),
                        segment("insert", r.newText()),
                        segment("keep", r.afterContext())
                ));
                entry.put("reason", r.reason() == null ? "" : r.reason());
            }
            case InsertParagraphAfterOp ins -> {
                StringBuilder inserted = new StringBuilder();
                for (NewParagraph np : ins.newParagraphs()) {
                    if (inserted.length() > 0) inserted.append("\n");
                    inserted.append(np.text());
                }
                entry.put("paragraph_preview", "（在“" + ins.anchorParagraphText() + "”后插入）");
                entry.put("segments", List.of(segment("insert", inserted.toString())));
                entry.put("reason", ins.reason() == null ? "" : ins.reason());
            }
            case DeleteParagraphOp del -> {
                entry.put("paragraph_preview", "（删除段落）");
                entry.put("segments", List.of(segment("delete", del.paragraphText())));
                entry.put("reason", del.reason() == null ? "" : del.reason());
            }
            case AddTableRowOp row -> {
                entry.put("paragraph_preview", "（表格新增一行，锚点：" + row.tableAnchorText() + "）");
                entry.put("segments", List.of(segment("insert", String.join(" | ", row.cells()))));
                entry.put("reason", row.reason() == null ? "" : row.reason());
            }
        }
        return entry;
    }

    private static Map<String, Object> segment(String type, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("text", text == null ? "" : text);
        return m;
    }
}
```

- [ ] **Step 2：写构造测试**

新建 `src/test/java/com/lifepilot/document/patch/docx/DocxDiffBuilder_构造测试.java`：

```java
package com.lifepilot.document.patch.docx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.document.patch.AddTableRowOp;
import com.lifepilot.document.patch.DeleteParagraphOp;
import com.lifepilot.document.patch.InsertParagraphAfterOp;
import com.lifepilot.document.patch.NewParagraph;
import com.lifepilot.document.patch.ReplaceTextOp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DocxDiffBuilder 构造测试 —— 覆盖 4 种 op 的 segments 生成 + summary 摘要。
 *
 * @author zsg
 * @since 2026-04-21
 */
class DocxDiffBuilder_构造测试 {

    private final DocxDiffBuilder builder = new DocxDiffBuilder();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("replace_text 生成 4 段 segments")
    void replace_text生成四段segments() throws Exception {
        var op = new ReplaceTextOp("前缀", "旧文", "后缀", "新文", "测试");
        var applied = new AppliedOp(op, new ParagraphRunRange(3, 0, 2, 0, 4));

        String json = builder.build("doc-1", 0, 1, List.of(applied));

        JsonNode root = mapper.readTree(json);
        assertThat(root.get("documentId").asText()).isEqualTo("doc-1");
        assertThat(root.get("toVersion").asInt()).isEqualTo(1);
        JsonNode change = root.get("changes").get(0);
        assertThat(change.get("op").asText()).isEqualTo("replace_text");
        assertThat(change.get("paragraph_index").asInt()).isEqualTo(3);
        JsonNode segs = change.get("segments");
        assertThat(segs).hasSize(4);
        assertThat(segs.get(0).get("type").asText()).isEqualTo("keep");
        assertThat(segs.get(1).get("type").asText()).isEqualTo("delete");
        assertThat(segs.get(1).get("text").asText()).isEqualTo("旧文");
        assertThat(segs.get(2).get("type").asText()).isEqualTo("insert");
        assertThat(segs.get(2).get("text").asText()).isEqualTo("新文");
    }

    @Test
    @DisplayName("insert_paragraph_after 生成 insert segment")
    void insert_paragraph_after生成insert() throws Exception {
        var op = new InsertParagraphAfterOp("锚点段",
                List.of(new NewParagraph("新段 A", "Normal"), new NewParagraph("新段 B", "Normal")),
                "补充");
        var applied = new AppliedOp(op, new ParagraphRunRange(2, -1, -1, -1, -1));

        String json = builder.build("doc-2", 0, 1, List.of(applied));

        JsonNode change = mapper.readTree(json).get("changes").get(0);
        assertThat(change.get("op").asText()).isEqualTo("insert_paragraph_after");
        JsonNode segs = change.get("segments");
        assertThat(segs).hasSize(1);
        assertThat(segs.get(0).get("type").asText()).isEqualTo("insert");
        assertThat(segs.get(0).get("text").asText()).contains("新段 A").contains("新段 B");
    }

    @Test
    @DisplayName("delete_paragraph 生成 delete segment")
    void delete_paragraph生成delete() throws Exception {
        var op = new DeleteParagraphOp("要删的段", "冗余");
        var applied = new AppliedOp(op, new ParagraphRunRange(5, -1, -1, -1, -1));

        String json = builder.build("doc-3", 1, 2, List.of(applied));

        JsonNode change = mapper.readTree(json).get("changes").get(0);
        assertThat(change.get("op").asText()).isEqualTo("delete_paragraph");
        assertThat(change.get("segments").get(0).get("type").asText()).isEqualTo("delete");
        assertThat(change.get("segments").get(0).get("text").asText()).isEqualTo("要删的段");
    }

    @Test
    @DisplayName("add_table_row 生成拼接的 insert segment")
    void add_table_row生成insert() throws Exception {
        var op = new AddTableRowOp("产品名称", "end", List.of("a", "b", "c"), null);
        var applied = new AppliedOp(op, null);

        String json = builder.build("doc-4", 0, 1, List.of(applied));

        JsonNode change = mapper.readTree(json).get("changes").get(0);
        assertThat(change.get("op").asText()).isEqualTo("add_table_row");
        assertThat(change.get("segments").get(0).get("text").asText()).isEqualTo("a | b | c");
    }

    @Test
    @DisplayName("summary 汇总数量与类型")
    void summary汇总数量与类型() throws Exception {
        var a = new AppliedOp(new ReplaceTextOp("", "t1", "", "n1", null), new ParagraphRunRange(0, 0, 0, 0, 2));
        var b = new AppliedOp(new ReplaceTextOp("", "t2", "", "n2", null), new ParagraphRunRange(0, 0, 0, 0, 2));
        var c = new AppliedOp(new DeleteParagraphOp("x", null), new ParagraphRunRange(1, -1, -1, -1, -1));

        String summary = builder.summarize(List.of(a, b, c));

        assertThat(summary).contains("共 3 处修改").contains("replace_text x2").contains("delete_paragraph x1");
    }
}
```

- [ ] **Step 3：跑测试**

Run: `mvn test -q -Dtest='DocxDiffBuilder*'`
Expected: 5 个用例通过。

- [ ] **Step 4：commit**

```bash
git add src/main/java/com/lifepilot/document/patch/docx/DocxDiffBuilder.java \
        src/test/java/com/lifepilot/document/patch/docx/DocxDiffBuilder_构造测试.java
git commit -m "feat(document): Phase 3A Task 8 — DocxDiffBuilder diff JSON 构造器"
```

---

## Task 9：DocumentVersionService — 工作副本生命周期

**目的**：串起 checkout / applyPatch / commit / rollback / discard / listVersions 全流程；是 Tool 层与 Controller 端点共同调用的单一入口。

**Files:**
- Create: `src/main/java/com/lifepilot/document/version/DocumentVersionService.java`
- Test: `src/test/java/com/lifepilot/document/version/DocumentVersionService_生命周期测试.java`

**前置阅读：**
- Task 1、Task 2（Repository 接口）
- Task 3（SourceRef sealed）
- Task 6-8（Engine + DiffBuilder）

- [ ] **Step 1：写 DocumentVersionService**

新建 `src/main/java/com/lifepilot/document/version/DocumentVersionService.java`：

```java
package com.lifepilot.document.version;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.lifepilot.document.model.DocumentVersionRecord;
import com.lifepilot.document.model.SessionDocumentRecord;
import com.lifepilot.document.patch.DocumentPatchOperation;
import com.lifepilot.document.patch.DocumentPatchResult;
import com.lifepilot.document.patch.docx.DocxDiffBuilder;
import com.lifepilot.document.patch.docx.DocxPatchEngine;
import com.lifepilot.document.repository.DocumentVersionRepository;
import com.lifepilot.document.repository.SessionDocumentRepository;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * 文档版本服务 —— P3 核心编排。
 *
 * <p>完整生命周期：checkout（首次引用源）→ applyPatch（多次）→
 * commit（覆盖原路径 / 另存） / rollback / discard。</p>
 *
 * <p>与 Phase 2 的关系：</p>
 * <ul>
 *   <li>checkout 会为 {@link com.lifepilot.document.version.SourceRef.PathSource} 和
 *       {@link com.lifepilot.document.version.SourceRef.AttachmentSource} 首次引用
 *       在 {@code session_documents} 插入新行；</li>
 *   <li>{@link com.lifepilot.document.version.SourceRef.DocumentSource} 若对应记录
 *       {@code latest_version == 0}，会将其原始 file_path 复制为 working/v0，
 *       之后 file_path 切换到 working 路径。</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-21
 */
@Service
public class DocumentVersionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentVersionService.class);

    private static final DateTimeFormatter BACKUP_TS =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.systemDefault());

    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final SessionDocumentRepository documentRepository;
    private final DocumentVersionRepository versionRepository;
    private final AttachmentRepository attachmentRepository;
    private final DocxPatchEngine engine;
    private final DocxDiffBuilder diffBuilder;
    private final String storageDir;

    public DocumentVersionService(SessionDocumentRepository documentRepository,
                                  DocumentVersionRepository versionRepository,
                                  AttachmentRepository attachmentRepository,
                                  DocxPatchEngine engine,
                                  DocxDiffBuilder diffBuilder,
                                  String storageDir) {
        this.documentRepository = documentRepository;
        this.versionRepository = versionRepository;
        this.attachmentRepository = attachmentRepository;
        this.engine = engine;
        this.diffBuilder = diffBuilder;
        this.storageDir = storageDir;
    }

    // ===== checkout =====

    /**
     * 确保源文件有对应工作副本与 session_documents 行；返回 documentId。
     */
    public String checkout(String sessionId, SourceRef source) throws IOException {
        return switch (source) {
            case SourceRef.PathSource p -> checkoutFromPath(sessionId, p.path());
            case SourceRef.AttachmentSource a -> checkoutFromAttachment(sessionId, a.attachmentId());
            case SourceRef.DocumentSource d -> checkoutFromDocument(d.documentId());
        };
    }

    private String checkoutFromPath(String sessionId, String sourcePath) throws IOException {
        var existing = documentRepository.findBySessionAndSourcePath(sessionId, sourcePath);
        if (existing != null) {
            return existing.id();
        }
        String documentId = UUID.randomUUID().toString();
        Path source = Paths.get(sourcePath);
        Path workingV0 = workingPath(sessionId, documentId, 0);
        Files.createDirectories(workingV0.getParent());
        Files.copy(source, workingV0, StandardCopyOption.REPLACE_EXISTING);
        long size = Files.size(workingV0);
        String fileName = source.getFileName().toString();

        documentRepository.save(new SessionDocumentRecord(
                documentId, sessionId, null, fileName, workingV0.toString(), size,
                DOCX_MIME, SessionDocumentRecord.ORIGIN_USER_LOCAL_FILE,
                sourcePath, 0, Instant.now()));
        versionRepository.save(new DocumentVersionRecord(
                UUID.randomUUID().toString(), documentId, 0, workingV0.toString(),
                DocumentVersionRecord.SOURCE_INITIAL, null, null, Instant.now()));
        log.info("checkout 本机路径：documentId={}, sourcePath={}", documentId, sourcePath);
        return documentId;
    }

    private String checkoutFromAttachment(String sessionId, String attachmentId) throws IOException {
        var att = attachmentRepository.findById(attachmentId);
        if (att == null) {
            throw new IllegalArgumentException("attachment 不存在：" + attachmentId);
        }
        // 附件本地路径即源；沿用 PathSource checkout 但 origin 区分
        String documentId = UUID.randomUUID().toString();
        Path sourceFile = Paths.get(att.filePath());
        Path workingV0 = workingPath(sessionId, documentId, 0);
        Files.createDirectories(workingV0.getParent());
        Files.copy(sourceFile, workingV0, StandardCopyOption.REPLACE_EXISTING);
        long size = Files.size(workingV0);

        documentRepository.save(new SessionDocumentRecord(
                documentId, sessionId, null, att.fileName(), workingV0.toString(), size,
                att.mimeType(), SessionDocumentRecord.ORIGIN_USER_ATTACHMENT_EDITED,
                null, 0, Instant.now()));
        versionRepository.save(new DocumentVersionRecord(
                UUID.randomUUID().toString(), documentId, 0, workingV0.toString(),
                DocumentVersionRecord.SOURCE_INITIAL, null, null, Instant.now()));
        log.info("checkout 附件：documentId={}, attachmentId={}", documentId, attachmentId);
        return documentId;
    }

    private String checkoutFromDocument(String documentId) throws IOException {
        var existing = documentRepository.findById(documentId);
        if (existing == null) {
            throw new IllegalArgumentException("document 不存在：" + documentId);
        }
        if (existing.latestVersion() > 0) {
            return documentId;
        }
        // 首次从 Phase 2 AI 产物 checkout：复制到 working/v0
        Path oldFile = Paths.get(existing.filePath());
        Path workingV0 = workingPath(existing.sessionId(), documentId, 0);
        Files.createDirectories(workingV0.getParent());
        Files.copy(oldFile, workingV0, StandardCopyOption.REPLACE_EXISTING);
        long size = Files.size(workingV0);

        documentRepository.updateFilePath(documentId, workingV0.toString(), size);
        versionRepository.save(new DocumentVersionRecord(
                UUID.randomUUID().toString(), documentId, 0, workingV0.toString(),
                DocumentVersionRecord.SOURCE_INITIAL, null, null, Instant.now()));
        log.info("checkout Phase2 产物：documentId={}", documentId);
        return documentId;
    }

    // ===== applyPatch =====

    public DocumentPatchResult applyPatch(String documentId, List<DocumentPatchOperation> ops)
            throws IOException, JsonProcessingException {
        var record = requireDocument(documentId);
        int nextVersion = record.latestVersion() + 1;
        Path currentFile = Paths.get(record.filePath());
        Path nextFile = workingPath(record.sessionId(), documentId, nextVersion);
        Files.createDirectories(nextFile.getParent());

        try (InputStream in = Files.newInputStream(currentFile);
             XWPFDocument doc = new XWPFDocument(in)) {
            var engineResult = engine.apply(doc, ops);
            if (!engineResult.success()) {
                return DocumentPatchResult.failure(engineResult.failedOps());
            }
            byte[] bytes = serializeDocx(doc);
            Files.write(nextFile, bytes);

            String diffJson = diffBuilder.build(documentId, record.latestVersion(), nextVersion,
                    engineResult.appliedOps());
            String summary = diffBuilder.summarize(engineResult.appliedOps());

            versionRepository.save(new DocumentVersionRecord(
                    UUID.randomUUID().toString(), documentId, nextVersion, nextFile.toString(),
                    DocumentVersionRecord.SOURCE_PATCH, summary, diffJson, Instant.now()));
            documentRepository.updateLatestVersion(documentId, nextVersion);
            documentRepository.updateFilePath(documentId, nextFile.toString(), bytes.length);
            attachmentRepository.updateSizeByFilePath(nextFile.toString(), (long) bytes.length);

            log.info("patch 成功：documentId={}, version={}→{}, {}",
                    documentId, record.latestVersion(), nextVersion, summary);
            return DocumentPatchResult.success(nextVersion, diffJson, summary);
        }
    }

    // ===== commit =====

    public record CommitResult(String committedPath, String backupPath) {}

    /** overwrite：覆盖 sourcePath；另存：copy 到 saveAsPath。均返回实际落盘目标路径与 .bak（overwrite 时）。 */
    public CommitResult commitOverwrite(String documentId) throws IOException {
        var record = requireDocument(documentId);
        if (record.sourcePath() == null || record.sourcePath().isBlank()) {
            throw new IllegalStateException("此文档没有 sourcePath（非本机路径源），不能 overwrite");
        }
        Path target = Paths.get(record.sourcePath());
        Path backup = Paths.get(record.sourcePath() + "." + BACKUP_TS.format(Instant.now()) + ".bak");
        if (Files.exists(target)) {
            Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.copy(Paths.get(record.filePath()), target, StandardCopyOption.REPLACE_EXISTING);
        log.info("commit overwrite：documentId={}, target={}, backup={}",
                documentId, target, backup);
        return new CommitResult(target.toString(), backup.toString());
    }

    public CommitResult commitSaveAs(String documentId, String saveAsPath) throws IOException {
        var record = requireDocument(documentId);
        Path target = Paths.get(saveAsPath);
        Files.createDirectories(target.getParent() == null ? Paths.get(".") : target.getParent());
        Files.copy(Paths.get(record.filePath()), target, StandardCopyOption.REPLACE_EXISTING);
        log.info("commit saveAs：documentId={}, target={}", documentId, target);
        return new CommitResult(target.toString(), null);
    }

    // ===== rollback =====

    public DocumentPatchResult rollback(String documentId, int targetVersion) throws IOException {
        var record = requireDocument(documentId);
        var targetVer = versionRepository.findByDocumentIdAndVersion(documentId, targetVersion);
        if (targetVer == null) {
            throw new IllegalArgumentException(
                    "目标版本不存在：documentId=" + documentId + ", version=" + targetVersion);
        }
        int nextVersion = record.latestVersion() + 1;
        Path nextFile = workingPath(record.sessionId(), documentId, nextVersion);
        Files.createDirectories(nextFile.getParent());
        Files.copy(Paths.get(targetVer.filePath()), nextFile, StandardCopyOption.REPLACE_EXISTING);
        long size = Files.size(nextFile);

        String summary = "回滚到版本 " + targetVersion;
        versionRepository.save(new DocumentVersionRecord(
                UUID.randomUUID().toString(), documentId, nextVersion, nextFile.toString(),
                DocumentVersionRecord.SOURCE_ROLLBACK, summary, null, Instant.now()));
        documentRepository.updateLatestVersion(documentId, nextVersion);
        documentRepository.updateFilePath(documentId, nextFile.toString(), size);
        attachmentRepository.updateSizeByFilePath(nextFile.toString(), size);

        log.info("rollback：documentId={}, to={}", documentId, targetVersion);
        return DocumentPatchResult.success(nextVersion, null, summary);
    }

    // ===== discard / list =====

    public void discard(String documentId) throws IOException {
        var record = requireDocument(documentId);
        if (record.latestVersion() == 0) {
            throw new IllegalStateException("文档无工作副本（latestVersion=0），不可丢弃");
        }
        Path workingDir = Paths.get(storageDir, record.sessionId(), "working", documentId);
        if (Files.exists(workingDir)) {
            Files.walk(workingDir)
                    .sorted((a, b) -> b.toString().length() - a.toString().length())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            log.warn("删除工作副本文件失败：{}", p, e);
                        }
                    });
        }
        versionRepository.deleteByDocumentId(documentId);
        documentRepository.deleteById(documentId);
        log.info("discard：documentId={}", documentId);
    }

    public List<DocumentVersionRecord> listVersions(String documentId) {
        requireDocument(documentId);
        return versionRepository.findByDocumentId(documentId);
    }

    // ===== 辅助 =====

    private SessionDocumentRecord requireDocument(String documentId) {
        var record = documentRepository.findById(documentId);
        if (record == null) {
            throw new IllegalArgumentException("document 不存在：" + documentId);
        }
        return record;
    }

    private Path workingPath(String sessionId, String documentId, int version) {
        return Paths.get(storageDir, sessionId, "working", documentId, "v" + version + ".docx");
    }

    private byte[] serializeDocx(XWPFDocument doc) throws IOException {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            doc.write(bos);
            return bos.toByteArray();
        }
    }
}
```

- [ ] **Step 2：写生命周期集成测试**

新建 `src/test/java/com/lifepilot/document/version/DocumentVersionService_生命周期测试.java`：

```java
package com.lifepilot.document.version;

import com.lifepilot.document.model.DocumentVersionRecord;
import com.lifepilot.document.patch.DocumentPatchOperation;
import com.lifepilot.document.patch.ReplaceTextOp;
import com.lifepilot.document.patch.docx.DocxDiffBuilder;
import com.lifepilot.document.patch.docx.DocxPatchEngine;
import com.lifepilot.document.patch.docx.TextAnchorLocator;
import com.lifepilot.document.repository.DocumentVersionRepository;
import com.lifepilot.document.repository.SessionDocumentRepository;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DocumentVersionService 生命周期集成测试 —— 真实 JdbcTemplate + fixture docx。
 *
 * @author zsg
 * @since 2026-04-21
 */
@JdbcTest
@Import({SessionDocumentRepository.class, DocumentVersionRepository.class,
        AttachmentRepository.class})
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration")
class DocumentVersionService_生命周期测试 {

    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/document/sample-contract.docx");

    @Autowired SessionDocumentRepository documentRepository;
    @Autowired DocumentVersionRepository versionRepository;
    @Autowired AttachmentRepository attachmentRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @TempDir Path tempDir;

    private DocumentVersionService service;
    private Path sourceCopy;

    @BeforeEach
    void 准备() throws Exception {
        jdbcTemplate.update(
                "INSERT INTO session_store (session_id, created_at, updated_at) VALUES (?, ?, ?)",
                "sess-life", Instant.now().toString(), Instant.now().toString());

        // 把 fixture 复制到 tempDir 模拟"用户本机路径"
        sourceCopy = tempDir.resolve("contract.docx");
        Files.copy(FIXTURE, sourceCopy, StandardCopyOption.REPLACE_EXISTING);

        service = new DocumentVersionService(
                documentRepository, versionRepository, attachmentRepository,
                new DocxPatchEngine(new TextAnchorLocator()),
                new DocxDiffBuilder(),
                tempDir.resolve("storage").toString());
    }

    @Test
    @DisplayName("PathSource checkout 建立 session_documents + v0 版本")
    void pathSource_checkout建立v0() throws Exception {
        String docId = service.checkout("sess-life", new SourceRef.PathSource(sourceCopy.toString()));

        var rec = documentRepository.findById(docId);
        assertThat(rec).isNotNull();
        assertThat(rec.sourcePath()).isEqualTo(sourceCopy.toString());
        assertThat(rec.latestVersion()).isZero();
        assertThat(versionRepository.findByDocumentId(docId))
                .extracting(DocumentVersionRecord::versionNo).containsExactly(0);
    }

    @Test
    @DisplayName("applyPatch 成功后 latestVersion++ 且生成 v1 文件")
    void applyPatch成功后版本递增() throws Exception {
        String docId = service.checkout("sess-life", new SourceRef.PathSource(sourceCopy.toString()));
        var op = new ReplaceTextOp("风险如下：", "付款期限 30 天", "，若超期", "付款期限 15 天", null);

        var result = service.applyPatch(docId, List.<DocumentPatchOperation>of(op));

        assertThat(result.success()).isTrue();
        assertThat(result.newVersion()).isEqualTo(1);
        assertThat(result.diffJson()).isNotBlank();
        var rec = documentRepository.findById(docId);
        assertThat(rec.latestVersion()).isEqualTo(1);
        assertThat(Path.of(rec.filePath())).exists();
    }

    @Test
    @DisplayName("applyPatch 失败版本不推进 文件不生成")
    void applyPatch失败不推进版本() throws Exception {
        String docId = service.checkout("sess-life", new SourceRef.PathSource(sourceCopy.toString()));
        var badOp = new ReplaceTextOp("", "绝不存在的文字", "", "X", null);

        var result = service.applyPatch(docId, List.<DocumentPatchOperation>of(badOp));

        assertThat(result.success()).isFalse();
        assertThat(documentRepository.findById(docId).latestVersion()).isZero();
    }

    @Test
    @DisplayName("commitOverwrite 覆盖源文件并生成 .bak")
    void commitOverwrite覆盖源文件() throws Exception {
        String docId = service.checkout("sess-life", new SourceRef.PathSource(sourceCopy.toString()));
        service.applyPatch(docId, List.<DocumentPatchOperation>of(
                new ReplaceTextOp("风险如下：", "付款期限 30 天", "，若超期",
                        "付款期限 15 天", null)));

        var result = service.commitOverwrite(docId);

        assertThat(result.committedPath()).isEqualTo(sourceCopy.toString());
        assertThat(Path.of(result.backupPath())).exists();
        // 源文件内容被替换
        try (InputStream in = Files.newInputStream(sourceCopy);
             var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument(in)) {
            StringBuilder sb = new StringBuilder();
            doc.getParagraphs().forEach(p -> sb.append(p.getText()).append("\n"));
            assertThat(sb.toString()).contains("付款期限 15 天");
        }
    }

    @Test
    @DisplayName("rollback 产生新版本 内容回到旧版")
    void rollback到旧版本() throws Exception {
        String docId = service.checkout("sess-life", new SourceRef.PathSource(sourceCopy.toString()));
        service.applyPatch(docId, List.<DocumentPatchOperation>of(
                new ReplaceTextOp("风险如下：", "付款期限 30 天", "，若超期",
                        "付款期限 15 天", null)));  // v1

        var result = service.rollback(docId, 0);

        assertThat(result.newVersion()).isEqualTo(2);
        var rec = documentRepository.findById(docId);
        try (InputStream in = Files.newInputStream(Path.of(rec.filePath()));
             var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument(in)) {
            StringBuilder sb = new StringBuilder();
            doc.getParagraphs().forEach(p -> sb.append(p.getText()).append("\n"));
            assertThat(sb.toString()).contains("付款期限 30 天");
        }
    }

    @Test
    @DisplayName("discard 清理工作副本 + 表行")
    void discard清理副本与表行() throws Exception {
        String docId = service.checkout("sess-life", new SourceRef.PathSource(sourceCopy.toString()));
        service.applyPatch(docId, List.<DocumentPatchOperation>of(
                new ReplaceTextOp("风险如下：", "付款期限 30 天", "，若超期",
                        "付款期限 15 天", null)));

        service.discard(docId);

        assertThat(documentRepository.findById(docId)).isNull();
        assertThat(versionRepository.findByDocumentId(docId)).isEmpty();
    }
}
```

- [ ] **Step 3：AttachmentRepository.updateSizeByFilePath 先建占位（Task 10 正式实现）**

为让当前 Task 9 测试能跑，先在 `AttachmentRepository` 加空实现：

```java
    public int updateSizeByFilePath(String filePath, long newSize) {
        return jdbcTemplate.update(
                "UPDATE message_attachments SET file_size = ? WHERE file_path = ?",
                newSize, filePath);
    }
```

（Task 10 会补测试用例；此处放行仅为编译通过 + Service 逻辑跑得通）

- [ ] **Step 4：跑测试**

Run: `mvn test -q -Dtest='DocumentVersionService*'`
Expected: 6 个用例通过。

- [ ] **Step 5：commit**

```bash
git add src/main/java/com/lifepilot/document/version/DocumentVersionService.java \
        src/main/java/com/lifepilot/interaction/web/repository/AttachmentRepository.java \
        src/test/java/com/lifepilot/document/version/DocumentVersionService_生命周期测试.java
git commit -m "feat(document): Phase 3A Task 9 — DocumentVersionService 全生命周期"
```

---

## Task 10：AttachmentRepository.updateSizeByFilePath 测试补齐

**目的**：Task 9 Step 3 已加实现；本 Task 补测试保证回归。

**Files:**
- Modify: `src/test/java/com/lifepilot/interaction/web/repository/AttachmentRepository_*.java`（定位既有测试类追加；若无既有则新建）

- [ ] **Step 1：查找既有测试类**

Run: `find src/test -name "AttachmentRepository*Test*.java" -o -name "AttachmentRepository_*.java"`

若有既有类就追加；若没有则新建 `src/test/java/com/lifepilot/interaction/web/repository/AttachmentRepository_文件尺寸更新测试.java`：

```java
package com.lifepilot.interaction.web.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AttachmentRepository P3 扩展方法 updateSizeByFilePath 测试。
 *
 * @author zsg
 * @since 2026-04-21
 */
@JdbcTest
@Import(AttachmentRepository.class)
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration")
class AttachmentRepository_文件尺寸更新测试 {

    @Autowired AttachmentRepository repository;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 准备会话() {
        jdbcTemplate.update(
                "INSERT INTO session_store (session_id, created_at, updated_at) VALUES (?, ?, ?)",
                "sess-att", Instant.now().toString(), Instant.now().toString());
    }

    @Test
    @DisplayName("按 filePath 更新 fileSize")
    void 按文件路径更新尺寸() {
        repository.saveForEntry(null, "sess-att", "a.docx", "/p/working/a.docx",
                100L, "application/docx", "/api/documents/x/download");

        int affected = repository.updateSizeByFilePath("/p/working/a.docx", 250L);

        assertThat(affected).isEqualTo(1);
        Long newSize = jdbcTemplate.queryForObject(
                "SELECT file_size FROM message_attachments WHERE file_path = ?",
                Long.class, "/p/working/a.docx");
        assertThat(newSize).isEqualTo(250L);
    }

    @Test
    @DisplayName("路径不存在返回 0")
    void 路径不存在返回零() {
        int affected = repository.updateSizeByFilePath("/p/不存在.docx", 100L);
        assertThat(affected).isZero();
    }
}
```

- [ ] **Step 2：跑测试**

Run: `mvn test -q -Dtest='AttachmentRepository*'`
Expected: 含既有 + 2 个新增用例全通。

- [ ] **Step 3：commit**

```bash
git add src/test/java/com/lifepilot/interaction/web/repository/AttachmentRepository_文件尺寸更新测试.java
git commit -m "test(document): Phase 3A Task 10 — AttachmentRepository.updateSizeByFilePath 测试"
```

---

## Task 11：DocumentEditActionDispatchExecutor — 5 个 action 路由

**目的**：对齐 Phase 2B `DocumentCreateActionDispatchExecutor`，把 `document.edit` 工具的 5 个 action 分发到 `DocumentVersionService` 对应方法。

**Files:**
- Create: `src/main/java/com/lifepilot/document/tool/DocumentEditActionDispatchExecutor.java`
- Test: `src/test/java/com/lifepilot/document/tool/DocumentEditActionDispatchExecutor_路由测试.java`

**前置阅读：**
- `src/main/java/com/lifepilot/document/tool/DocumentCreateActionDispatchExecutor.java`（Phase 2B 范例）
- `src/main/java/com/lifepilot/tool/dispatch/ActionDispatchExecutor.java`（父类 register API）

- [ ] **Step 1：DocumentEditActionDispatchExecutor**

新建 `src/main/java/com/lifepilot/document/tool/DocumentEditActionDispatchExecutor.java`：

```java
package com.lifepilot.document.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.document.model.DocumentVersionRecord;
import com.lifepilot.document.patch.AddTableRowOp;
import com.lifepilot.document.patch.DeleteParagraphOp;
import com.lifepilot.document.patch.DocumentPatchOperation;
import com.lifepilot.document.patch.DocumentPatchResult;
import com.lifepilot.document.patch.InsertParagraphAfterOp;
import com.lifepilot.document.patch.NewParagraph;
import com.lifepilot.document.patch.ReplaceTextOp;
import com.lifepilot.document.version.DocumentVersionService;
import com.lifepilot.document.version.SourceRef;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.dispatch.ActionDispatchExecutor;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * document.edit action 路由 —— 5 个 action 分发到 {@link DocumentVersionService}。
 *
 * <p>action 语义见 spec §2.1。所有 action 返回结构化 Map，前端或 LLM 按需取字段。</p>
 *
 * @author zsg
 * @since 2026-04-21
 */
public class DocumentEditActionDispatchExecutor extends ActionDispatchExecutor {

    private static final Logger log = LoggerFactory.getLogger(DocumentEditActionDispatchExecutor.class);

    private final DocumentVersionService versionService;
    private final ObjectMapper mapper = new ObjectMapper();

    public DocumentEditActionDispatchExecutor(DocumentVersionService versionService) {
        this.versionService = versionService;

        register("patch", RiskLevel.LOW, mediumWrite(), this::patch);
        register("diff", RiskLevel.LOW, readOnly(), this::diff);
        register("commit", RiskLevel.MEDIUM, mediumWrite(), this::commit);
        register("rollback", RiskLevel.LOW, mediumWrite(), this::rollback);
        register("list_versions", RiskLevel.LOW, readOnly(), this::listVersions);
    }

    private ToolExecutionSemantics mediumWrite() {
        return ToolExecutionSemantics.of(
                PermissionActionType.WRITE_FILE,
                ToolSchedulingMode.SEQUENTIAL,
                ToolScopeResolvers.pathTrees());
    }

    private ToolExecutionSemantics readOnly() {
        return ToolExecutionSemantics.of(
                PermissionActionType.READ_FILE,
                ToolSchedulingMode.SEQUENTIAL,
                ToolScopeResolvers.pathTrees());
    }

    // ===== action handlers =====

    ToolResult patch(ToolInput input) {
        try {
            String sessionId = requireSession(input);
            SourceRef source = parseSource(input);
            String documentId = versionService.checkout(sessionId, source);
            List<DocumentPatchOperation> ops = parseOperations(input);

            DocumentPatchResult result = versionService.applyPatch(documentId, ops);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("success", result.success());
            data.put("documentId", documentId);
            if (result.success()) {
                data.put("newVersion", result.newVersion());
                data.put("summary", result.patchSummary());
                data.put("diffJson", result.diffJson());
                data.put("downloadUrl", "/api/documents/" + documentId + "/download");
            } else {
                data.put("failedOps", result.failedOps());
            }
            return ToolResult.success(data);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("参数错误：" + e.getMessage());
        } catch (Exception e) {
            log.warn("document.edit patch 失败", e);
            return ToolResult.error("执行失败：" + e.getMessage());
        }
    }

    ToolResult diff(ToolInput input) {
        try {
            String documentId = input.getParam("documentId", String.class);
            int from = input.getParam("from", Integer.class);
            int to = input.getParam("to", Integer.class);
            // 简化实现：只支持取 "to" 那次 patch 的缓存 diff
            var versions = versionService.listVersions(documentId);
            var toVer = versions.stream()
                    .filter(v -> v.versionNo() == to)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("版本不存在：" + to));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("documentId", documentId);
            data.put("from", from);
            data.put("to", to);
            data.put("diffJson", toVer.diffJson() == null ? "" : toVer.diffJson());
            return ToolResult.success(data);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("参数错误：" + e.getMessage());
        }
    }

    ToolResult commit(ToolInput input) {
        try {
            String documentId = input.getParam("documentId", String.class);
            String target = input.getParam("commitTarget", String.class);
            DocumentVersionService.CommitResult result;
            if ("overwrite".equals(target)) {
                result = versionService.commitOverwrite(documentId);
            } else if ("saveAs".equals(target)) {
                String saveAsPath = input.getParam("saveAsPath", String.class);
                result = versionService.commitSaveAs(documentId, saveAsPath);
            } else {
                return ToolResult.error("commitTarget 必须是 overwrite 或 saveAs");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("committedPath", result.committedPath());
            if (result.backupPath() != null) data.put("backupPath", result.backupPath());
            return ToolResult.success(data);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("参数错误：" + e.getMessage());
        } catch (Exception e) {
            log.warn("document.edit commit 失败", e);
            return ToolResult.error("执行失败：" + e.getMessage());
        }
    }

    ToolResult rollback(ToolInput input) {
        try {
            String documentId = input.getParam("documentId", String.class);
            int version = input.getParam("version", Integer.class);
            var result = versionService.rollback(documentId, version);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("success", result.success());
            data.put("newVersion", result.newVersion());
            data.put("summary", result.patchSummary());
            return ToolResult.success(data);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("参数错误：" + e.getMessage());
        } catch (Exception e) {
            log.warn("document.edit rollback 失败", e);
            return ToolResult.error("执行失败：" + e.getMessage());
        }
    }

    ToolResult listVersions(ToolInput input) {
        try {
            String documentId = input.getParam("documentId", String.class);
            List<DocumentVersionRecord> versions = versionService.listVersions(documentId);
            List<Map<String, Object>> list = new ArrayList<>();
            for (var v : versions) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("versionNo", v.versionNo());
                m.put("source", v.source());
                m.put("summary", v.patchSummary() == null ? "" : v.patchSummary());
                m.put("createdAt", v.createdAt().toString());
                list.add(m);
            }
            return ToolResult.success(Map.of("versions", list));
        } catch (IllegalArgumentException e) {
            return ToolResult.error("参数错误：" + e.getMessage());
        }
    }

    // ===== 参数解析 =====

    private String requireSession(ToolInput input) {
        return input.getContextValue("sessionId", String.class)
                .orElseThrow(() -> new IllegalArgumentException("缺少执行上下文 sessionId"));
    }

    private SourceRef parseSource(ToolInput input) {
        @SuppressWarnings("unchecked")
        Map<String, Object> src = input.getParam("source", Map.class);
        if (src == null) throw new IllegalArgumentException("source 不能为空");
        String type = String.valueOf(src.get("type"));
        return switch (type) {
            case "path" -> new SourceRef.PathSource(String.valueOf(src.get("value")));
            case "attachment" -> new SourceRef.AttachmentSource(String.valueOf(src.get("id")));
            case "document" -> new SourceRef.DocumentSource(String.valueOf(src.get("id")));
            default -> throw new IllegalArgumentException("source.type 必须是 path/attachment/document");
        };
    }

    @SuppressWarnings("unchecked")
    private List<DocumentPatchOperation> parseOperations(ToolInput input) {
        List<Map<String, Object>> raw = input.getParam("operations", List.class);
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("operations 不能为空");
        }
        List<DocumentPatchOperation> ops = new ArrayList<>();
        for (Map<String, Object> m : raw) {
            ops.add(parseOneOp(m));
        }
        return ops;
    }

    @SuppressWarnings("unchecked")
    private DocumentPatchOperation parseOneOp(Map<String, Object> m) {
        String op = String.valueOf(m.get("op"));
        String reason = m.get("reason") == null ? null : String.valueOf(m.get("reason"));
        return switch (op) {
            case "replace_text" -> new ReplaceTextOp(
                    String.valueOf(m.getOrDefault("before_context", "")),
                    String.valueOf(m.get("target")),
                    String.valueOf(m.getOrDefault("after_context", "")),
                    String.valueOf(m.getOrDefault("new_text", "")),
                    reason);
            case "insert_paragraph_after" -> {
                List<Map<String, Object>> newParas = (List<Map<String, Object>>) m.get("new_paragraphs");
                List<NewParagraph> ps = new ArrayList<>();
                for (Map<String, Object> np : newParas) {
                    ps.add(new NewParagraph(
                            String.valueOf(np.getOrDefault("text", "")),
                            np.get("style") == null ? null : String.valueOf(np.get("style"))));
                }
                yield new InsertParagraphAfterOp(String.valueOf(m.get("anchor_paragraph_text")), ps, reason);
            }
            case "delete_paragraph" -> new DeleteParagraphOp(
                    String.valueOf(m.get("paragraph_text")), reason);
            case "add_table_row" -> {
                List<String> cells = new ArrayList<>();
                for (Object c : (List<Object>) m.get("cells")) {
                    cells.add(c == null ? "" : String.valueOf(c));
                }
                yield new AddTableRowOp(
                        String.valueOf(m.get("table_anchor_text")),
                        String.valueOf(m.get("position")),
                        cells, reason);
            }
            default -> throw new IllegalArgumentException("未知 op：" + op);
        };
    }
}
```

- [ ] **Step 2：写路由测试（Mock DocumentVersionService）**

新建 `src/test/java/com/lifepilot/document/tool/DocumentEditActionDispatchExecutor_路由测试.java`：

```java
package com.lifepilot.document.tool;

import com.lifepilot.document.model.DocumentVersionRecord;
import com.lifepilot.document.patch.DocumentPatchResult;
import com.lifepilot.document.patch.ReplaceTextOp;
import com.lifepilot.document.version.DocumentVersionService;
import com.lifepilot.document.version.SourceRef;
import com.lifepilot.tool.model.ToolInput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * DocumentEditActionDispatchExecutor 路由测试。
 *
 * @author zsg
 * @since 2026-04-21
 */
@ExtendWith(MockitoExtension.class)
class DocumentEditActionDispatchExecutor_路由测试 {

    @Mock DocumentVersionService versionService;

    @InjectMocks DocumentEditActionDispatchExecutor dispatcher;

    @Test
    @DisplayName("patch action — 路径源 + replace_text 操作")
    void patch_action路由正确() throws Exception {
        when(versionService.checkout(eq("s1"), any(SourceRef.PathSource.class))).thenReturn("d1");
        when(versionService.applyPatch(eq("d1"), any())).thenReturn(
                DocumentPatchResult.success(1, "{\"summary\":\"ok\"}", "共 1 处"));

        var input = ToolInput.builder()
                .contextValue("sessionId", "s1")
                .param("action", "patch")
                .param("source", Map.of("type", "path", "value", "D:/x.docx"))
                .param("operations", List.of(Map.of(
                        "op", "replace_text", "target", "old", "new_text", "new")))
                .build();

        var result = dispatcher.execute(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).containsEntry("documentId", "d1");
        assertThat(result.getData()).containsEntry("newVersion", 1);
    }

    @Test
    @DisplayName("commit overwrite")
    void commit_overwrite() throws Exception {
        when(versionService.commitOverwrite("d2"))
                .thenReturn(new DocumentVersionService.CommitResult("D:/x.docx", "D:/x.docx.xx.bak"));

        var input = ToolInput.builder()
                .contextValue("sessionId", "s1")
                .param("action", "commit")
                .param("documentId", "d2")
                .param("commitTarget", "overwrite")
                .build();

        var result = dispatcher.execute(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).containsEntry("committedPath", "D:/x.docx");
        assertThat(result.getData()).containsEntry("backupPath", "D:/x.docx.xx.bak");
    }

    @Test
    @DisplayName("list_versions 返回版本列表")
    void list_versions返回列表() {
        when(versionService.listVersions("d3")).thenReturn(List.of(
                new DocumentVersionRecord("v0", "d3", 0, "/p/v0", "initial", null, null, Instant.now()),
                new DocumentVersionRecord("v1", "d3", 1, "/p/v1", "patch", "共 1 处", "{}", Instant.now())
        ));

        var input = ToolInput.builder()
                .contextValue("sessionId", "s1")
                .param("action", "list_versions")
                .param("documentId", "d3")
                .build();

        var result = dispatcher.execute(input);

        assertThat(result.isSuccess()).isTrue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> versions = (List<Map<String, Object>>) result.getData().get("versions");
        assertThat(versions).hasSize(2);
        assertThat(versions.get(1)).containsEntry("versionNo", 1).containsEntry("source", "patch");
    }

    @Test
    @DisplayName("未知 action 返回错误")
    void 未知action返回错误() {
        var input = ToolInput.builder()
                .contextValue("sessionId", "s1")
                .param("action", "unknown_action")
                .build();

        var result = dispatcher.execute(input);

        assertThat(result.isSuccess()).isFalse();
    }
}
```

（注：`ToolInput.builder()` / `ToolResult.isSuccess()` / `getData()` 的 API 具体名称以项目实际为准；若与项目不一致按实际调整。）

- [ ] **Step 3：跑测试**

Run: `mvn test -q -Dtest='DocumentEditActionDispatchExecutor*'`
Expected: 4 个用例通过。

- [ ] **Step 4：commit**

```bash
git add src/main/java/com/lifepilot/document/tool/DocumentEditActionDispatchExecutor.java \
        src/test/java/com/lifepilot/document/tool/DocumentEditActionDispatchExecutor_路由测试.java
git commit -m "feat(document): Phase 3A Task 11 — DocumentEditActionDispatchExecutor 路由"
```

---

## Task 12：DocumentEditToolProvider + 装配 + application.yml

**目的**：把 `document.edit` 工具注册为 BuiltinTool，完成 Spring 装配链，让 LLM 能真正调用。

**Files:**
- Create: `src/main/java/com/lifepilot/document/tool/DocumentEditToolProvider.java`
- Modify: `src/main/java/com/lifepilot/document/config/DocumentAutoConfiguration.java`
- Modify: `src/main/resources/application.yml`

**前置阅读：**
- `src/main/java/com/lifepilot/document/tool/DocumentToolProvider.java`（Phase 2B create provider 范例）
- `src/main/java/com/lifepilot/document/config/DocumentAutoConfiguration.java`（当前装配链）

- [ ] **Step 1：DocumentEditToolProvider**

新建 `src/main/java/com/lifepilot/document/tool/DocumentEditToolProvider.java`：

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
 * document.edit 工具提供者 —— 单工具 + 5 种 action。
 *
 * <p>对齐 Phase 2B {@link DocumentToolProvider}。schema 扁平化，action 枚举 patch/diff/commit/rollback/list_versions。</p>
 *
 * @author zsg
 * @since 2026-04-21
 */
public class DocumentEditToolProvider {

    private static final List<String> TAGS = List.of("infrastructure", "document", "edit");

    private final DocumentEditActionDispatchExecutor dispatcher;

    public DocumentEditToolProvider(DocumentEditActionDispatchExecutor dispatcher) {
        this.dispatcher = dispatcher;
    }

    public BuiltinTool buildEditTool() {
        return BuiltinTool.builder()
                .id("document.edit")
                .category(ToolCategory.ACTION)
                .name("编辑文档工作副本")
                .description(buildDescription())
                .inputSchema(JsonSchema.of(buildSchema()))
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.WRITE_FILE,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.pathTrees()))
                .tags(TAGS)
                .actionMetadataFrom(dispatcher)
                .executor(dispatcher)
                .build();
    }

    private String buildDescription() {
        return "对 docx 文档施加文本锚点编辑并管理版本。" +
                "action=patch 提交一批 operations（文本锚点事务性替换/插入/删除/表格行），" +
                "locator 需在文档中唯一匹配，事务性整批成败，保留原 run 样式；" +
                "action=diff 拉取某次 patch 的 diff JSON；" +
                "action=commit 把工作副本覆盖回 sourcePath 或另存到 saveAsPath（.bak 自动备份）；" +
                "action=rollback 回滚到指定版本（生成新版本而非物理撤销）；" +
                "action=list_versions 列出版本历史。" +
                "LLM 用法要点：(1) 先用 file.read 读到文档内容再规划 locator；" +
                "(2) before_context/after_context 建议各带 10-30 字提高唯一性；" +
                "(3) commit 是用户动作，LLM 不应自动 commit。";
    }

    private Map<String, Object> buildSchema() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("action", Map.of(
                "type", "string",
                "enum", List.of("patch", "diff", "commit", "rollback", "list_versions"),
                "description", "操作类型"
        ));
        properties.put("source", Map.of(
                "type", "object",
                "description", "patch action 必填；type ∈ {path, attachment, document}；" +
                        "path 用 value=绝对路径，attachment/document 用 id=资源 ID",
                "properties", Map.of(
                        "type", Map.of("type", "string", "enum", List.of("path", "attachment", "document")),
                        "value", Map.of("type", "string"),
                        "id", Map.of("type", "string")
                )
        ));
        properties.put("operations", Map.of(
                "type", "array",
                "description", "patch action 必填；op ∈ {replace_text, insert_paragraph_after, delete_paragraph, add_table_row}；" +
                        "字段组合见 spec §2.3",
                "items", Map.of("type", "object")
        ));
        properties.put("documentId", Map.of(
                "type", "string",
                "description", "diff/commit/rollback/list_versions action 必填"
        ));
        properties.put("from", Map.of("type", "integer", "description", "diff action 的起始版本号"));
        properties.put("to", Map.of("type", "integer", "description", "diff action 的目标版本号"));
        properties.put("commitTarget", Map.of(
                "type", "string",
                "enum", List.of("overwrite", "saveAs"),
                "description", "commit action：overwrite=覆盖原路径；saveAs=另存"
        ));
        properties.put("saveAsPath", Map.of(
                "type", "string",
                "description", "commit+saveAs 时必填的目标路径（绝对路径）"
        ));
        properties.put("version", Map.of(
                "type", "integer",
                "description", "rollback 目标版本号"
        ));

        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("required", List.of("action"));
        schema.put("properties", properties);
        return schema;
    }
}
```

- [ ] **Step 2：扩展 DocumentAutoConfiguration**

在 `src/main/java/com/lifepilot/document/config/DocumentAutoConfiguration.java` 文件末尾（最后一个 Bean 定义之后、类大括号之前）追加：

```java
    // ===== P3 装配 =====

    @Bean
    com.lifepilot.document.patch.docx.TextAnchorLocator textAnchorLocator() {
        return new com.lifepilot.document.patch.docx.TextAnchorLocator();
    }

    @Bean
    com.lifepilot.document.patch.docx.DocxPatchEngine docxPatchEngine(
            com.lifepilot.document.patch.docx.TextAnchorLocator locator) {
        return new com.lifepilot.document.patch.docx.DocxPatchEngine(locator);
    }

    @Bean
    com.lifepilot.document.patch.docx.DocxDiffBuilder docxDiffBuilder() {
        return new com.lifepilot.document.patch.docx.DocxDiffBuilder();
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean({
            com.lifepilot.document.repository.SessionDocumentRepository.class,
            com.lifepilot.document.repository.DocumentVersionRepository.class,
            com.lifepilot.interaction.web.repository.AttachmentRepository.class
    })
    com.lifepilot.document.version.DocumentVersionService documentVersionService(
            com.lifepilot.document.repository.SessionDocumentRepository docRepo,
            com.lifepilot.document.repository.DocumentVersionRepository verRepo,
            com.lifepilot.interaction.web.repository.AttachmentRepository attRepo,
            com.lifepilot.document.patch.docx.DocxPatchEngine engine,
            com.lifepilot.document.patch.docx.DocxDiffBuilder diffBuilder,
            DocumentProperties properties) {
        return new com.lifepilot.document.version.DocumentVersionService(
                docRepo, verRepo, attRepo, engine, diffBuilder, properties.getStorageDir());
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(
            com.lifepilot.document.version.DocumentVersionService.class)
    DocumentEditActionDispatchExecutor documentEditActionDispatchExecutor(
            com.lifepilot.document.version.DocumentVersionService service) {
        return new DocumentEditActionDispatchExecutor(service);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(DocumentEditActionDispatchExecutor.class)
    DocumentEditToolProvider documentEditToolProvider(DocumentEditActionDispatchExecutor dispatcher) {
        return new DocumentEditToolProvider(dispatcher);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(DocumentEditToolProvider.class)
    BuiltinTool documentEditTool(DocumentEditToolProvider provider) {
        var tool = provider.buildEditTool();
        log.info("已装配 document.edit 工具：id={}", tool.id());
        return tool;
    }
```

- [ ] **Step 3：application.yml 加 document.edit 到 core-tool-ids**

修改 `src/main/resources/application.yml`，在 `core-tool-ids` 列表末尾追加（参考 L76）：

```yaml
    core-tool-ids:
      - shell.exec
      - web.search
      - shell.process
      - web.fetch
      - file.read
      - file.write
      - file.list
      - memory
      - knowledge.search
      - notify
      - document.create
      - document.edit         # Phase 3A：docx 编辑（patch/diff/commit/rollback/list_versions）
```

- [ ] **Step 4：编译 + 启动 smoke**

Run: `mvn compile -q -DskipTests`
Expected: BUILD SUCCESS。

Run: `mvn spring-boot:run` 启动（或集成测试中用 ApplicationContextRunner）
Expected: 日志中出现 `已装配 document.edit 工具：id=document.edit`。

- [ ] **Step 5：commit**

```bash
git add src/main/java/com/lifepilot/document/tool/DocumentEditToolProvider.java \
        src/main/java/com/lifepilot/document/config/DocumentAutoConfiguration.java \
        src/main/resources/application.yml
git commit -m "feat(document): Phase 3A Task 12 — document.edit 工具装配 + core-tool-ids 注册"
```

---

## Task 13：DocumentController 扩展 5 个端点 + download 支持 version

**目的**：REST 层承接前端按钮（commit / rollback / discard）和元数据读取。

**Files:**
- Modify: `src/main/java/com/lifepilot/interaction/web/controller/DocumentController.java`
- Test: `src/test/java/com/lifepilot/interaction/web/controller/DocumentController_P3端点测试.java`

**前置阅读：**
- `src/main/java/com/lifepilot/interaction/web/controller/DocumentController.java`（Phase 2A download 实现）

- [ ] **Step 1：扩展 DocumentController**

替换 `DocumentController.java` 为：

```java
package com.lifepilot.interaction.web.controller;

import com.lifepilot.document.model.DocumentVersionRecord;
import com.lifepilot.document.model.SessionDocumentRecord;
import com.lifepilot.document.repository.DocumentVersionRepository;
import com.lifepilot.document.repository.SessionDocumentRepository;
import com.lifepilot.document.version.DocumentVersionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档产物访问控制器 —— Phase 3A 扩展：元数据 / 版本列表 / diff / commit / rollback / 丢弃工作副本。
 *
 * @author zsg
 * @since 2026-02-28
 */
@RestController
@RequestMapping("/api/documents")
@ConditionalOnProperty(name = "lifepilot.gateway.channels.web.enabled", havingValue = "true")
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private final SessionDocumentRepository documentRepository;
    private final DocumentVersionRepository versionRepository;
    private final DocumentVersionService versionService;

    public DocumentController(SessionDocumentRepository documentRepository,
                              DocumentVersionRepository versionRepository,
                              DocumentVersionService versionService) {
        this.documentRepository = documentRepository;
        this.versionRepository = versionRepository;
        this.versionService = versionService;
    }

    // ===== Phase 2A 下载（扩展 version 支持）=====

    @GetMapping("/{id}/download")
    public ResponseEntity<ByteArrayResource> download(@PathVariable String id,
                                                      @RequestParam(required = false) Integer version) {
        SessionDocumentRecord record = documentRepository.findById(id);
        if (record == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Path path;
        if (version != null) {
            var ver = versionRepository.findByDocumentIdAndVersion(id, version);
            if (ver == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            path = Paths.get(ver.filePath());
        } else {
            path = Paths.get(record.filePath());
        }
        if (!Files.exists(path)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        try {
            byte[] data = Files.readAllBytes(path);
            String encoded = URLEncoder.encode(record.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
            MediaType mediaType;
            try {
                mediaType = MediaType.parseMediaType(record.mimeType());
            } catch (IllegalArgumentException e) {
                mediaType = MediaType.APPLICATION_OCTET_STREAM;
            }
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                    .contentLength(data.length)
                    .body(new ByteArrayResource(data));
        } catch (IOException e) {
            log.error("读取文档字节失败：id={}, path={}", id, path, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ===== P3 新增 =====

    @GetMapping("/{id}")
    public Map<String, Object> getMetadata(@PathVariable String id) {
        var record = documentRepository.findById(id);
        if (record == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var map = new LinkedHashMap<String, Object>();
        map.put("id", record.id());
        map.put("fileName", record.fileName());
        map.put("mimeType", record.mimeType());
        map.put("fileSize", record.fileSize());
        map.put("origin", record.origin());
        map.put("sourcePath", record.sourcePath());
        map.put("latestVersion", record.latestVersion());
        map.put("createdAt", record.createdAt().toString());
        return map;
    }

    @GetMapping("/{id}/versions")
    public List<Map<String, Object>> listVersions(@PathVariable String id) {
        if (documentRepository.findById(id) == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var versions = versionRepository.findByDocumentId(id);
        var list = new java.util.ArrayList<Map<String, Object>>();
        for (DocumentVersionRecord v : versions) {
            var m = new LinkedHashMap<String, Object>();
            m.put("versionNo", v.versionNo());
            m.put("source", v.source());
            m.put("patchSummary", v.patchSummary());
            m.put("createdAt", v.createdAt().toString());
            list.add(m);
        }
        return list;
    }

    @GetMapping("/{id}/diff")
    public Map<String, Object> diff(@PathVariable String id,
                                    @RequestParam int from,
                                    @RequestParam int to) {
        if (documentRepository.findById(id) == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var ver = versionRepository.findByDocumentIdAndVersion(id, to);
        if (ver == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var m = new LinkedHashMap<String, Object>();
        m.put("documentId", id);
        m.put("from", from);
        m.put("to", to);
        m.put("diffJson", ver.diffJson() == null ? "" : ver.diffJson());
        return m;
    }

    public record CommitRequest(String target, String saveAsPath) {}

    @PostMapping("/{id}/commit")
    public Map<String, Object> commit(@PathVariable String id, @RequestBody CommitRequest body) {
        try {
            DocumentVersionService.CommitResult result;
            if ("overwrite".equals(body.target())) {
                result = versionService.commitOverwrite(id);
            } else if ("saveAs".equals(body.target())) {
                if (body.saveAsPath() == null || body.saveAsPath().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "saveAsPath 不能为空");
                }
                result = versionService.commitSaveAs(id, body.saveAsPath());
            } else {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "target 必须是 overwrite/saveAs");
            }
            var m = new LinkedHashMap<String, Object>();
            m.put("committedPath", result.committedPath());
            if (result.backupPath() != null) m.put("backupPath", result.backupPath());
            return m;
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IOException e) {
            log.error("commit 失败：id={}", id, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    public record RollbackRequest(int version) {}

    @PostMapping("/{id}/rollback")
    public Map<String, Object> rollback(@PathVariable String id, @RequestBody RollbackRequest body) {
        try {
            var result = versionService.rollback(id, body.version());
            var m = new LinkedHashMap<String, Object>();
            m.put("newVersion", result.newVersion());
            m.put("summary", result.patchSummary());
            return m;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @DeleteMapping("/{id}/working-copy")
    public ResponseEntity<Void> discardWorkingCopy(@PathVariable String id) {
        try {
            versionService.discard(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }
}
```

- [ ] **Step 2：写 MockMvc 端点测试**

新建 `src/test/java/com/lifepilot/interaction/web/controller/DocumentController_P3端点测试.java`：

```java
package com.lifepilot.interaction.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.document.model.DocumentVersionRecord;
import com.lifepilot.document.model.SessionDocumentRecord;
import com.lifepilot.document.repository.DocumentVersionRepository;
import com.lifepilot.document.repository.SessionDocumentRepository;
import com.lifepilot.document.version.DocumentVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * DocumentController P3 端点测试 —— Mockito + MockMvc。
 *
 * @author zsg
 * @since 2026-04-21
 */
@ExtendWith(MockitoExtension.class)
class DocumentController_P3端点测试 {

    @Mock SessionDocumentRepository documentRepository;
    @Mock DocumentVersionRepository versionRepository;
    @Mock DocumentVersionService versionService;

    @InjectMocks DocumentController controller;

    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("GET /api/documents/{id} 返回元数据")
    void getMetadata_成功() throws Exception {
        when(documentRepository.findById("d1")).thenReturn(new SessionDocumentRecord(
                "d1", "s1", null, "x.docx", "/p/x", 100L,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "user_local_file", "D:/x.docx", 2, Instant.now()));

        mockMvc.perform(get("/api/documents/d1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("d1"))
                .andExpect(jsonPath("$.latestVersion").value(2))
                .andExpect(jsonPath("$.sourcePath").value("D:/x.docx"));
    }

    @Test
    @DisplayName("GET /api/documents/{id} 不存在返回 404")
    void getMetadata_不存在() throws Exception {
        when(documentRepository.findById("miss")).thenReturn(null);
        mockMvc.perform(get("/api/documents/miss")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /versions 返回按版本升序")
    void 列版本列表() throws Exception {
        when(documentRepository.findById("d1")).thenReturn(new SessionDocumentRecord(
                "d1", "s1", null, "x.docx", "/p/x", 1L, "a", "agent_generated", null, 1, Instant.now()));
        when(versionRepository.findByDocumentId("d1")).thenReturn(List.of(
                new DocumentVersionRecord("v1", "d1", 0, "/p/v0", "initial", null, null, Instant.now()),
                new DocumentVersionRecord("v2", "d1", 1, "/p/v1", "patch", "共 1 处", "{}", Instant.now())
        ));

        mockMvc.perform(get("/api/documents/d1/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].versionNo").value(0))
                .andExpect(jsonPath("$[1].versionNo").value(1));
    }

    @Test
    @DisplayName("POST /commit overwrite 调用 service.commitOverwrite")
    void commit_overwrite() throws Exception {
        when(versionService.commitOverwrite("d1")).thenReturn(
                new DocumentVersionService.CommitResult("D:/x.docx", "D:/x.docx.20260421.bak"));

        mockMvc.perform(post("/api/documents/d1/commit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("target", "overwrite"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.committedPath").value("D:/x.docx"))
                .andExpect(jsonPath("$.backupPath").exists());
    }

    @Test
    @DisplayName("POST /rollback 调用 service.rollback")
    void rollback成功() throws Exception {
        when(versionService.rollback(eq("d1"), eq(0))).thenReturn(
                com.lifepilot.document.patch.DocumentPatchResult.success(2, null, "回滚到版本 0"));

        mockMvc.perform(post("/api/documents/d1/rollback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("version", 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newVersion").value(2));
    }

    @Test
    @DisplayName("DELETE /working-copy 调用 service.discard")
    void discard成功() throws Exception {
        mockMvc.perform(delete("/api/documents/d1/working-copy"))
                .andExpect(status().isNoContent());
    }
}
```

- [ ] **Step 3：跑测试**

Run: `mvn test -q -Dtest='DocumentController_P3端点测试'`
Expected: 6 个用例通过。

- [ ] **Step 4：commit**

```bash
git add src/main/java/com/lifepilot/interaction/web/controller/DocumentController.java \
        src/test/java/com/lifepilot/interaction/web/controller/DocumentController_P3端点测试.java
git commit -m "feat(document): Phase 3A Task 13 — DocumentController 5 个 P3 端点"
```

---

## Task 14：前端 API 封装 + DocumentDiffCard.vue

**目的**：用 Reka UI 2.x + Tailwind 命名尺度实现对话气泡内的 diff 卡片（折叠 / 展开 / 三按钮）。

**Files:**
- Create: `zhiwei-web/src/api/documents.ts`
- Create: `zhiwei-web/src/components/chat/DocumentDiffCard.vue`

**前置阅读：**
- `zhiwei-web/src/api/client.ts`（既有 API 客户端模式）
- `zhiwei-web/src/components/chat/ToolCallCard.vue`（折叠卡片风格参照）
- `.claude/rules/frontend-conventions.md`（Reka UI + Tailwind 命名尺度约束）

- [ ] **Step 1：api/documents.ts**

新建 `zhiwei-web/src/api/documents.ts`：

```ts
/**
 * 文档产物 REST 封装 —— Phase 3A：元数据 / 版本 / diff / commit / rollback / 丢弃。
 *
 * @author zsg
 * @since 2026-04-21
 */
import { apiClient } from './client'

export interface DocumentMetadata {
  id: string
  fileName: string
  mimeType: string
  fileSize: number
  origin: string
  sourcePath: string | null
  latestVersion: number
  createdAt: string
}

export interface DocumentVersionInfo {
  versionNo: number
  source: 'initial' | 'patch' | 'rollback'
  patchSummary: string | null
  createdAt: string
}

export interface DiffSegment {
  type: 'keep' | 'delete' | 'insert'
  text: string
}

export interface DiffChange {
  patch_id: string
  op: string
  paragraph_index: number
  paragraph_preview: string
  segments: DiffSegment[]
  reason: string
}

export interface DiffPayload {
  documentId: string
  fromVersion: number
  toVersion: number
  summary: string
  changes: DiffChange[]
}

export interface CommitResult {
  committedPath: string
  backupPath?: string
}

export async function getDocument(id: string): Promise<DocumentMetadata> {
  return (await apiClient.get<DocumentMetadata>(`/api/documents/${id}`)).data
}

export async function listVersions(id: string): Promise<DocumentVersionInfo[]> {
  return (await apiClient.get<DocumentVersionInfo[]>(`/api/documents/${id}/versions`)).data
}

export async function getDiff(id: string, from: number, to: number): Promise<{ diffJson: string }> {
  return (await apiClient.get(`/api/documents/${id}/diff`, { params: { from, to } })).data
}

export async function commit(id: string, target: 'overwrite' | 'saveAs', saveAsPath?: string): Promise<CommitResult> {
  return (await apiClient.post<CommitResult>(`/api/documents/${id}/commit`, { target, saveAsPath })).data
}

export async function rollback(id: string, version: number) {
  return (await apiClient.post(`/api/documents/${id}/rollback`, { version })).data
}

export async function discardWorkingCopy(id: string) {
  await apiClient.delete(`/api/documents/${id}/working-copy`)
}

export function parseDiffJson(json: string): DiffPayload | null {
  if (!json || !json.trim()) return null
  try {
    return JSON.parse(json) as DiffPayload
  } catch {
    return null
  }
}
```

- [ ] **Step 2：DocumentDiffCard.vue**

新建 `zhiwei-web/src/components/chat/DocumentDiffCard.vue`：

```vue
<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import { ChevronDown, ChevronUp, FileText, Upload, Save, Trash2 } from 'lucide-vue-next'
import {
  getDocument,
  getDiff,
  commit,
  discardWorkingCopy,
  parseDiffJson,
  type DocumentMetadata,
  type DiffPayload,
} from '@/api/documents'

interface Props {
  documentId: string
}

const props = defineProps<Props>()
const emit = defineEmits<{
  (e: 'committed', documentId: string, backupPath: string | undefined): void
  (e: 'discarded', documentId: string): void
}>()

const expanded = ref(false)
const loading = ref(false)
const metadata = ref<DocumentMetadata | null>(null)
const diff = ref<DiffPayload | null>(null)

const canOverwrite = computed(() => !!metadata.value?.sourcePath)
const hasChanges = computed(() => metadata.value && metadata.value.latestVersion > 0)

async function loadData() {
  loading.value = true
  try {
    metadata.value = await getDocument(props.documentId)
    if (metadata.value.latestVersion > 0) {
      const from = metadata.value.latestVersion - 1
      const to = metadata.value.latestVersion
      const payload = await getDiff(props.documentId, from, to)
      diff.value = parseDiffJson(payload.diffJson)
    }
  } catch (e) {
    console.warn('加载文档 diff 失败', e)
  } finally {
    loading.value = false
  }
}

async function onOverwrite() {
  if (!canOverwrite.value) return
  if (!confirm('确认覆盖原文件 ' + metadata.value?.sourcePath + ' 吗？系统会自动生成 .bak 备份。')) return
  const result = await commit(props.documentId, 'overwrite')
  emit('committed', props.documentId, result.backupPath)
  alert('已覆盖；备份：' + (result.backupPath || '无'))
}

async function onSaveAs() {
  const path = prompt('另存为绝对路径：')
  if (!path || !path.trim()) return
  const result = await commit(props.documentId, 'saveAs', path.trim())
  emit('committed', props.documentId, undefined)
  alert('已另存到：' + result.committedPath)
}

async function onDiscard() {
  if (!confirm('丢弃工作副本将不可恢复，继续？')) return
  await discardWorkingCopy(props.documentId)
  emit('discarded', props.documentId)
}

onMounted(loadData)
</script>

<template>
  <div class="rounded-md border border-border bg-card p-md text-sm">
    <button
      type="button"
      class="flex w-full items-center justify-between gap-sm"
      @click="expanded = !expanded"
    >
      <span class="flex items-center gap-xs">
        <FileText class="size-md text-muted-foreground" />
        <span class="font-medium">{{ metadata?.fileName || '加载中…' }}</span>
        <span v-if="diff" class="text-muted-foreground">
          · {{ diff.summary }} · v{{ diff.fromVersion }} → v{{ diff.toVersion }}
        </span>
      </span>
      <component :is="expanded ? ChevronUp : ChevronDown" class="size-md" />
    </button>

    <div v-if="expanded" class="mt-md">
      <div v-if="loading" class="text-muted-foreground">加载中…</div>

      <div v-else-if="diff" class="space-y-md">
        <div
          v-for="(c, idx) in diff.changes"
          :key="c.patch_id"
          class="rounded-md border border-border p-md"
        >
          <div class="text-muted-foreground text-xs mb-xs">修改 {{ idx + 1 }}：{{ c.op }}</div>
          <div class="text-muted-foreground text-xs mb-xs">{{ c.paragraph_preview }}</div>
          <p class="leading-relaxed">
            <template v-for="(seg, i) in c.segments" :key="i">
              <span v-if="seg.type === 'keep'">{{ seg.text }}</span>
              <span v-else-if="seg.type === 'delete'" class="bg-red-100 line-through px-xs rounded">
                {{ seg.text }}
              </span>
              <span v-else class="bg-green-100 px-xs rounded">{{ seg.text }}</span>
            </template>
          </p>
          <div v-if="c.reason" class="text-muted-foreground text-xs mt-xs">原因：{{ c.reason }}</div>
        </div>
      </div>

      <div v-else-if="hasChanges" class="text-muted-foreground">diff 数据暂不可用</div>
      <div v-else class="text-muted-foreground">尚无改动</div>

      <div v-if="hasChanges" class="mt-md flex items-center gap-sm">
        <button
          v-if="canOverwrite"
          type="button"
          class="inline-flex items-center gap-xs rounded-md bg-primary text-primary-foreground px-md py-xs"
          @click="onOverwrite"
        >
          <Upload class="size-md" /> 应用到原路径
        </button>
        <button
          type="button"
          class="inline-flex items-center gap-xs rounded-md border border-border px-md py-xs"
          @click="onSaveAs"
        >
          <Save class="size-md" /> 另存为…
        </button>
        <button
          type="button"
          class="inline-flex items-center gap-xs rounded-md border border-border px-md py-xs text-destructive"
          @click="onDiscard"
        >
          <Trash2 class="size-md" /> 丢弃
        </button>
      </div>
    </div>
  </div>
</template>
```

**Tailwind 尺度检查**：只用 `xs/sm/md/lg/xl/2xl`（对齐 `.claude/rules/frontend-conventions.md`），未出现 `p-3 / px-5 / mt-7` 这类任意值。

- [ ] **Step 3：TypeScript 编译检查**

Run: `cd zhiwei-web && npm run build`
Expected: 构建成功，无类型错误。

- [ ] **Step 4：commit**

```bash
git add zhiwei-web/src/api/documents.ts \
        zhiwei-web/src/components/chat/DocumentDiffCard.vue
git commit -m "feat(document): Phase 3A Task 14 — 前端 documents API + DocumentDiffCard"
```

---

## Task 15：MessageBubble.vue 集成 DocumentDiffCard

**目的**：让消息气泡在附件的 MIME=docx 且对应 document.latestVersion>0 时渲染 DocumentDiffCard；其他附件保持原有卡片。

**Files:**
- Modify: `zhiwei-web/src/components/chat/MessageBubble.vue`

**前置阅读：**
- `zhiwei-web/src/components/chat/MessageBubble.vue`（现有附件渲染逻辑）

- [ ] **Step 1：读取附件挂载的 documentId**

在 `MessageBubble.vue` 的 `<script setup lang="ts">` 里追加 imports 与状态：

```ts
import DocumentDiffCard from './DocumentDiffCard.vue'
import { getDocument, type DocumentMetadata } from '@/api/documents'
import { ref, onMounted } from 'vue'

/** 按附件 file_path 解析 documentId —— downloadUrl 形如 `/api/documents/{id}/download`。 */
function extractDocumentId(url: string | undefined): string | null {
  if (!url) return null
  const m = url.match(/\/api\/documents\/([^/]+)\/download/)
  return m ? m[1] : null
}

const docxMetaCache = ref<Record<string, DocumentMetadata | null>>({})

async function resolveDocMeta(docId: string) {
  if (docxMetaCache.value[docId] !== undefined) return
  try {
    docxMetaCache.value[docId] = await getDocument(docId)
  } catch {
    docxMetaCache.value[docId] = null
  }
}
```

- [ ] **Step 2：修改附件渲染模板**

在 `MessageBubble.vue` 的模板里找到渲染 docx 附件的位置（通常是一个 `v-for` 遍历 `message.attachments`），将 docx 类型附件用**三元**分发：

```vue
<template v-for="att in dockxAttachments" :key="att.id">
  <!-- 若该附件对应 document.latestVersion > 0 → DiffCard，否则沿用原附件卡片 -->
  <template v-if="isEditedDocx(att)">
    <DocumentDiffCard
      :document-id="extractDocumentId(att.url)!"
      @committed="onCommitted"
      @discarded="onDiscarded"
    />
  </template>
  <template v-else>
    <!-- 原附件卡片（保持 Phase 2A 已有实现） -->
    <AttachmentCardOriginal :attachment="att" />
  </template>
</template>
```

辅助函数 `isEditedDocx(att)`：

```ts
function isEditedDocx(att: ChatAttachment): boolean {
  if (!att.mimeType?.includes('wordprocessingml.document')) return false
  const docId = extractDocumentId(att.url)
  if (!docId) return false
  void resolveDocMeta(docId)
  const meta = docxMetaCache.value[docId]
  return !!meta && meta.latestVersion > 0
}

function onCommitted(documentId: string, backupPath: string | undefined) {
  console.info('已 commit 文档', documentId, backupPath)
  delete docxMetaCache.value[documentId]
}

function onDiscarded(documentId: string) {
  delete docxMetaCache.value[documentId]
}
```

**说明**：若 `MessageBubble.vue` 现有结构不便插入，可以在现有附件卡片组件（若存在独立如 `AttachmentCard.vue`）外层包装；关键是按 `mimeType + latestVersion>0` 的条件切换。若项目实际结构和这里不同，按实际调整并保持原附件卡片对非编辑 docx 的兼容。

- [ ] **Step 3：手动回归 smoke**

Run: `cd zhiwei-web && npm run dev`
Run 后端：`mvn spring-boot:run`

手工测试：
1. 在对话里告诉 LLM 改一份本机 docx（需要 LLM 先 file.read 再 document.edit patch）
2. 消息气泡里应出现 DocumentDiffCard（折叠态）
3. 点开看 diff，点"另存为..."输入临时路径，验证文件产出

- [ ] **Step 4：运行既有前端测试回归**

Run: `cd zhiwei-web && npm run test:run`
Expected: 既有 `MessageBubble.spec.ts` 通过（Phase 3A 对非 docx / 未编辑 docx 附件渲染不变）。

- [ ] **Step 5：commit**

```bash
git add zhiwei-web/src/components/chat/MessageBubble.vue
git commit -m "feat(document): Phase 3A Task 15 — MessageBubble 识别已编辑 docx 挂 DiffCard"
```

---

## Task 16：ApplicationContextRunner 装配契约测试

**目的**：校验 P3 所有新 Bean 都装配上去、`document.edit` 作为 BuiltinTool 正确注册；Phase 2B 的 `document.create` 不受影响。

**Files:**
- Create or Modify: `src/test/java/com/lifepilot/document/config/DocumentAutoConfiguration_装配测试.java`
- Test run: `mvn test` 全量最后回归

**前置阅读：**
- Phase 2A/2B 同类装配测试（若存在）
- `.claude/rules/java-conventions.md`（ApplicationContextRunner 用法）

- [ ] **Step 1：写装配测试**

新建（或扩展已存在的）`src/test/java/com/lifepilot/document/config/DocumentAutoConfiguration_装配测试.java`：

```java
package com.lifepilot.document.config;

import com.lifepilot.document.repository.DocumentVersionRepository;
import com.lifepilot.document.repository.SessionDocumentRepository;
import com.lifepilot.document.tool.DocumentEditActionDispatchExecutor;
import com.lifepilot.document.tool.DocumentEditToolProvider;
import com.lifepilot.document.tool.DocumentToolProvider;
import com.lifepilot.document.version.DocumentVersionService;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.tool.BuiltinTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DocumentAutoConfiguration 装配契约测试 —— Phase 3A 扩展版。
 *
 * <p>断言：P3 Bean（DocumentVersionService / DocxPatchEngine / DocumentEditToolProvider 等）
 * 都能装配，且 {@code document.edit} 作为独立 BuiltinTool 出现在上下文中，
 * 同时 Phase 2B {@code document.create} 不受影响。</p>
 *
 * @author zsg
 * @since 2026-04-21
 */
class DocumentAutoConfiguration_装配测试 {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    JdbcTemplateAutoConfiguration.class,
                    FlywayAutoConfiguration.class,
                    DocumentAutoConfiguration.class))
            .withUserConfiguration(TestAttachmentRepoConfig.class)
            .withPropertyValues(
                    "spring.datasource.url=jdbc:sqlite::memory:",
                    "spring.datasource.driver-class-name=org.sqlite.JDBC",
                    "spring.flyway.locations=classpath:db/migration",
                    "lifepilot.document.enabled=true",
                    "lifepilot.document.storage-dir=${java.io.tmpdir}/zhiwei-test-doc"
            );

    @Test
    @DisplayName("P3 Bean 全装配 + document.edit 工具注册")
    void 全装配P3链() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(DocumentVersionService.class);
            assertThat(ctx).hasSingleBean(DocumentEditActionDispatchExecutor.class);
            assertThat(ctx).hasSingleBean(DocumentEditToolProvider.class);
            // Phase 2B create 仍在
            assertThat(ctx).hasSingleBean(DocumentToolProvider.class);

            var tools = ctx.getBeansOfType(BuiltinTool.class);
            assertThat(tools.values())
                    .extracting(BuiltinTool::id)
                    .contains("document.create", "document.edit");
        });
    }

    @Test
    @DisplayName("禁用开关 lifepilot.document.enabled=false 整模块不装配")
    void 禁用开关不装配P3链() {
        runner.withPropertyValues("lifepilot.document.enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(DocumentVersionService.class);
            assertThat(ctx).doesNotHaveBean(DocumentEditToolProvider.class);
        });
    }

    /** 补 AttachmentRepository Bean，DocumentVersionService 依赖它。 */
    @org.springframework.context.annotation.Configuration
    static class TestAttachmentRepoConfig {
        @org.springframework.context.annotation.Bean
        AttachmentRepository attachmentRepository(org.springframework.jdbc.core.JdbcTemplate jdbc) {
            return new AttachmentRepository(jdbc);
        }
    }
}
```

- [ ] **Step 2：跑装配测试**

Run: `mvn test -q -Dtest='DocumentAutoConfiguration_装配测试'`
Expected: 2 个用例通过。

- [ ] **Step 3：全量测试回归**

Run: `mvn test -q`
Expected: 所有 Phase 3A 新测试 + 既有 Phase 0/1B/2A/2B 测试全部通过。

- [ ] **Step 4：启动 smoke 验证 LLM 可见 `document.edit`**

Run: `mvn spring-boot:run`
Expected: 日志含 `工具注册统计: JAVA_NATIVE=23`（从 22 升 1，因为加了 document.edit；若与实际数字不符，按实际核对新增 1）。

或检查 `GET /api/tools` 响应中出现 `{"id":"document.edit", ...}`。

- [ ] **Step 5：commit**

```bash
git add src/test/java/com/lifepilot/document/config/DocumentAutoConfiguration_装配测试.java
git commit -m "test(document): Phase 3A Task 16 — AutoConfig 装配契约 + 全量回归"
```

---

## 完成收尾

**最终验证清单：**

- [ ] `mvn test -q` 全绿
- [ ] `cd zhiwei-web && npm run test:run` 全绿
- [ ] `mvn spring-boot:run` 启动无报错；`document.edit` 出现在工具列表
- [ ] 手工 smoke：对话 "改 D:/合同/甲方.docx 付款期限改成 15 天" → 气泡出现 DiffCard → 点"应用到原路径"→ 验证原文件被改 + `.bak` 生成
- [ ] 所有 16 个 task 的 commit 形成清晰 Phase 3A 提交链

**P3A 完成后需要**：
1. 跑 `/ship check`（项目既有 slash command）做端到端测试 + 文档同步 + code review
2. 合并前提 PR，PR 描述包含：验证步骤 + V13 迁移说明 + `document.edit` 工具的 schema

**P3B 启动条件**：P3A 合并后，基于本 plan 的协议 / 工程模式横向复制到 xlsx（update_cell / insert_row / delete_row / set_range 等 op + cell 级 diff 适配）。单独 spec + plan。
