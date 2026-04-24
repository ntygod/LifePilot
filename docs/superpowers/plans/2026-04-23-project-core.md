# 项目核心（Project Core）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 引入"项目（Project）"作为用户显式创建的领域级容器，承载领域任务的对话 + 知识库 + 文档 + 记忆；实现基础的 scope 隔离语义（单向隔离，隔离项目能读主账户但产出不写回）。

**Architecture:** 项目实体 + `conversations.project_id` 关联 + 每个项目对应一个 `MemorySpace`（`space_type=PROJECT`, `owner_type=PROJECT`）。隔离语义通过 `MemoryReadFilter.buildForProject(...)` 构造 "project space + personal space + experience space" 的并集读取过滤器实现；写入时按 `isolation` 开关路由到对应 space。前端侧栏按 Qwen 范式重排（项目分组 + 所有对话分组 + 定时任务占位入口）。

**Tech Stack:** Spring Boot 3 + JdbcTemplate + Flyway + JUnit 5/jqwik + Mockito (后端)；Vue 3 + TypeScript + Pinia + Reka UI 2.x + Tailwind 命名尺度 + lucide-vue-next (前端)。

**设计依据:** `docs/superpowers/specs/2026-04-22-project-workspace-design.md`

---

## 前置条件

- [ ] 在 worktree `.worktrees/project-workspace` 内、分支 `feature/project-workspace` 上执行
- [ ] 当前 HEAD 包含最新设计文档（`docs/superpowers/specs/2026-04-22-project-workspace-design.md`）
- [ ] 本地能跑通 `mvn compile` 和 `cd zhiwei-web && npm install && npm run build`

## Scope 边界

**本 Plan 内**：projects 表 + project CRUD API + MemorySpace 联动 + 基础 scope 隔离读写 + 前端侧栏/创建对话框/项目详情页。

**本 Plan 外**（后续 plan）：
- 定时任务全局入口 UI（Plan 2）
- Datastore 用户侧下架（Plan 3）
- L3 用户偏好 / L4 程序记忆的"按唯一键覆盖主账户"精细冲突解决（在 Plan 1 里简化为"项目 space + 主账户 space 并集读取"，不做 key-level override —— 后续 plan 优化）
- 项目场景模板、微微主动提议、归档、跨项目搜索等（见 spec 9.2）

---

## 文件结构

### 后端新增

| 路径 | 责任 |
|---|---|
| `src/main/resources/db/migration/V15__create_projects_table.sql` | 建表 |
| `src/main/resources/db/migration/V16__add_project_id_to_conversations.sql` | conversations 加 project_id |
| `src/main/java/com/lifepilot/project/model/Project.java` | Project record |
| `src/main/java/com/lifepilot/project/model/ProjectIsolation.java` | 隔离模式枚举 |
| `src/main/java/com/lifepilot/project/repository/ProjectRepository.java` | JdbcTemplate 仓储 |
| `src/main/java/com/lifepilot/project/service/ProjectService.java` | CRUD + MemorySpace 联动 |
| `src/main/java/com/lifepilot/project/context/ProjectContext.java` | 对话/请求的 project 上下文载体 |
| `src/main/java/com/lifepilot/project/context/ProjectContextResolver.java` | 从 conversationId 解析 ProjectContext |
| `src/main/java/com/lifepilot/project/config/ProjectAutoConfiguration.java` | 装配 |
| `src/main/java/com/lifepilot/interaction/web/controller/ProjectController.java` | REST API |
| `src/main/java/com/lifepilot/interaction/web/model/project/*` | API DTO records |

### 后端修改

| 路径 | 改动要点 |
|---|---|
| `src/main/java/com/lifepilot/memory/scope/MemorySpaceType.java` | 新增 `PROJECT` 枚举值 |
| `src/main/java/com/lifepilot/memory/scope/MemorySpaceKeys.java` | 新增 `project(String projectId)` 方法 |
| `src/main/java/com/lifepilot/memory/scope/MemorySpaceRepository.java` | 新增 `ensureProjectSpace(String projectId)` + `deleteById(String spaceId)` |
| `src/main/java/com/lifepilot/memory/scope/MemoryReadFilter.java` | 新增 `buildForProject(...)` 静态工厂方法 |
| `src/main/java/com/lifepilot/conversation/transcript/JdbcTranscriptStore.java` 及相关 | 对话创建/读取支持 `project_id`（具体路径在 Task 13 确认） |

### 前端新增

| 路径 | 责任 |
|---|---|
| `zhiwei-web/src/stores/project.ts` | Pinia store |
| `zhiwei-web/src/api/project.ts` | API client |
| `zhiwei-web/src/views/ProjectDetailView.vue` | 项目详情页 |
| `zhiwei-web/src/components/project/CreateProjectDialog.vue` | 新建项目对话框 |
| `zhiwei-web/src/components/project/ProjectResourcePanel.vue` | 项目资料抽屉（知识库 / 文档 tab） |
| `zhiwei-web/src/components/project/ProjectSettingsPanel.vue` | 项目设置抽屉 |
| `zhiwei-web/src/components/sidebar/ProjectSection.vue` | 侧栏"项目"分组 |

### 前端修改

| 路径 | 改动要点 |
|---|---|
| `zhiwei-web/src/router/index.ts` | 新增 `/projects/:id` 路由 |
| 现有侧栏组件（Task 18 先 Grep 确定） | 加入 `ProjectSection` + 定时任务入口占位 |
| `zhiwei-web/src/views/ChatView.vue` | 新建对话时携带 `projectId`（可空） |

---

## Task 1: 建 `projects` 表（Flyway V15）

**Files:**
- Create: `src/main/resources/db/migration/V15__create_projects_table.sql`

- [ ] **Step 1: 写迁移脚本**

```sql
-- 项目（Project）— 用户显式创建的领域级任务容器。
-- project_id IS NULL 在关联表里代表"归属主账户"。
CREATE TABLE projects (
    id            TEXT PRIMARY KEY,
    name          TEXT NOT NULL,
    instructions  TEXT NOT NULL DEFAULT '',
    isolation     TEXT NOT NULL DEFAULT 'ISOLATED',  -- 'ISOLATED' | 'SHARED'
    memory_space_id TEXT NOT NULL,                   -- 关联 memory_spaces.id
    created_at    TEXT NOT NULL,
    updated_at    TEXT NOT NULL,
    UNIQUE(name),
    FOREIGN KEY (memory_space_id) REFERENCES memory_spaces(id)
);

CREATE INDEX idx_projects_created_at ON projects(created_at DESC);
CREATE INDEX idx_projects_memory_space ON projects(memory_space_id);
```

- [ ] **Step 2: 本地验证迁移能跑**

```bash
mvn -q flyway:migrate -Dflyway.url="jdbc:sqlite:$HOME/.zhiwei/zhiwei.db" 2>/dev/null || \
  (rm -f $HOME/.zhiwei/zhiwei.db && mvn -q spring-boot:run > /tmp/migrate.log 2>&1 &)
```

Expected: Flyway schema_history 出现 `V15__create_projects_table`

- [ ] **Step 3: （无需实现——SQL 即实现）**

- [ ] **Step 4: 确认表结构**

```bash
sqlite3 $HOME/.zhiwei/zhiwei.db ".schema projects"
```

Expected: 看到 CREATE TABLE projects 语句，字段齐全

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V15__create_projects_table.sql
git commit -m "feat(project): 新增 projects 表（V15）"
```

---

## Task 2: `conversations` 加 `project_id`（Flyway V16）

**Files:**
- Create: `src/main/resources/db/migration/V16__add_project_id_to_conversations.sql`

- [ ] **Step 1: 写迁移脚本**

```sql
-- 对话归属项目：NULL = 归属主账户；非 NULL = 归属具体项目
ALTER TABLE conversations ADD COLUMN project_id TEXT;

CREATE INDEX idx_conversations_project_id ON conversations(project_id)
    WHERE project_id IS NOT NULL;
```

- [ ] **Step 2: 本地跑迁移**

重启应用或清库重跑。

- [ ] **Step 3: （无需代码）**

- [ ] **Step 4: 验证字段**

```bash
sqlite3 $HOME/.zhiwei/zhiwei.db ".schema conversations" | grep project_id
```

Expected: 看到 `project_id TEXT`

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V16__add_project_id_to_conversations.sql
git commit -m "feat(project): conversations 加 project_id 字段（V16）"
```

---

## Task 3: `ProjectIsolation` 枚举

**Files:**
- Create: `src/main/java/com/lifepilot/project/model/ProjectIsolation.java`
- Test: `src/test/java/com/lifepilot/project/model/ProjectIsolation_枚举测试.java`

- [ ] **Step 1: 写失败测试**

```java
package com.lifepilot.project.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProjectIsolation_枚举测试 {

    @Test
    void 默认值为_ISOLATED() {
        assertEquals(ProjectIsolation.ISOLATED, ProjectIsolation.defaultValue());
    }

    @Test
    void fromString_支持大小写兼容() {
        assertEquals(ProjectIsolation.ISOLATED, ProjectIsolation.fromString("isolated"));
        assertEquals(ProjectIsolation.SHARED, ProjectIsolation.fromString("SHARED"));
    }

    @Test
    void fromString_非法值抛异常() {
        assertThrows(IllegalArgumentException.class, () -> ProjectIsolation.fromString("BOGUS"));
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

```bash
mvn -q test -Dtest="ProjectIsolation_枚举测试"
```

Expected: 编译失败（`ProjectIsolation` 不存在）

- [ ] **Step 3: 实现枚举**

```java
package com.lifepilot.project.model;

import java.util.Locale;

/**
 * 项目记忆隔离模式。
 *
 * @author zsg
 * @since 2026-04-23
 */
public enum ProjectIsolation {
    /** 隔离：写入只落项目 space；读取合并主账户 space。 */
    ISOLATED,
    /** 不隔离：写入路由到主账户 space（合流语义）。 */
    SHARED;

    public static ProjectIsolation defaultValue() {
        return ISOLATED;
    }

    public static ProjectIsolation fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("isolation 不能为 null");
        }
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
```

- [ ] **Step 4: 跑测试通过**

```bash
mvn -q test -Dtest="ProjectIsolation_枚举测试"
```

Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/lifepilot/project/model/ProjectIsolation.java \
        src/test/java/com/lifepilot/project/model/ProjectIsolation_枚举测试.java
git commit -m "feat(project): 新增 ProjectIsolation 枚举"
```

---

## Task 4: `Project` record

**Files:**
- Create: `src/main/java/com/lifepilot/project/model/Project.java`

- [ ] **Step 1: 写失败测试（构造器约束）**

Test: `src/test/java/com/lifepilot/project/model/Project_构造约束测试.java`

```java
package com.lifepilot.project.model;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class Project_构造约束测试 {

    @Test
    void name_非空校验() {
        Instant now = Instant.now();
        assertThrows(IllegalArgumentException.class, () ->
            new Project("id", "", "指示词", ProjectIsolation.ISOLATED, "space-1", now, now));
        assertThrows(IllegalArgumentException.class, () ->
            new Project("id", "  ", "指示词", ProjectIsolation.ISOLATED, "space-1", now, now));
    }

    @Test
    void name_超长校验() {
        String tooLong = "x".repeat(65);
        Instant now = Instant.now();
        assertThrows(IllegalArgumentException.class, () ->
            new Project("id", tooLong, "", ProjectIsolation.ISOLATED, "space-1", now, now));
    }

    @Test
    void instructions_超长校验() {
        String tooLong = "x".repeat(1001);
        Instant now = Instant.now();
        assertThrows(IllegalArgumentException.class, () ->
            new Project("id", "项目 A", tooLong, ProjectIsolation.ISOLATED, "space-1", now, now));
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

```bash
mvn -q test -Dtest="Project_构造约束测试"
```

Expected: 编译失败（`Project` 不存在）

- [ ] **Step 3: 实现 record**

```java
package com.lifepilot.project.model;

import java.time.Instant;

/**
 * 项目（领域级任务容器）。
 *
 * @param id               UUID
 * @param name             项目名（1..64 字符，同账户唯一）
 * @param instructions     指示词（0..1000 字符，注入对话 system prompt）
 * @param isolation        记忆隔离模式
 * @param memorySpaceId    关联的 MemorySpace id（每个项目对应一个 PROJECT 类型 space）
 * @param createdAt
 * @param updatedAt
 *
 * @author zsg
 * @since 2026-04-23
 */
public record Project(
        String id,
        String name,
        String instructions,
        ProjectIsolation isolation,
        String memorySpaceId,
        Instant createdAt,
        Instant updatedAt
) {
    public static final int MAX_NAME_LENGTH = 64;
    public static final int MAX_INSTRUCTIONS_LENGTH = 1000;

    public Project {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("项目名不能为空");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("项目名超过 " + MAX_NAME_LENGTH + " 字符");
        }
        if (instructions == null) {
            instructions = "";
        }
        if (instructions.length() > MAX_INSTRUCTIONS_LENGTH) {
            throw new IllegalArgumentException("指示词超过 " + MAX_INSTRUCTIONS_LENGTH + " 字符");
        }
        if (isolation == null) {
            throw new IllegalArgumentException("isolation 不能为空");
        }
        if (memorySpaceId == null || memorySpaceId.isBlank()) {
            throw new IllegalArgumentException("memorySpaceId 不能为空");
        }
    }
}
```

- [ ] **Step 4: 跑测试通过**

```bash
mvn -q test -Dtest="Project_构造约束测试"
```

Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/lifepilot/project/model/Project.java \
        src/test/java/com/lifepilot/project/model/Project_构造约束测试.java
git commit -m "feat(project): 新增 Project record 与字段约束"
```

---

## Task 5: `MemorySpaceType.PROJECT` + `MemorySpaceKeys.project()` + `MemorySpaceRepository.ensureProjectSpace()`

**Files:**
- Modify: `src/main/java/com/lifepilot/memory/scope/MemorySpaceType.java`
- Modify: `src/main/java/com/lifepilot/memory/scope/MemorySpaceKeys.java`
- Modify: `src/main/java/com/lifepilot/memory/scope/MemorySpaceRepository.java`
- Test: `src/test/java/com/lifepilot/memory/scope/MemorySpaceRepository_项目空间集成测试.java`

- [ ] **Step 1: 写失败的集成测试**

```java
package com.lifepilot.memory.scope;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.core.io.ClassPathResource;

import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;

class MemorySpaceRepository_项目空间集成测试 {

    MemorySpaceRepository repo;

    @BeforeEach
    void setUp() {
        DataSource ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)  // 或复用项目 SQLite TestConfig
                .addScript("classpath:db/migration/V1__init_schema.sql")
                // ...加载到 V16
                .build();
        repo = new MemorySpaceRepository(new JdbcTemplate(ds), new ObjectMapper());
    }

    @Test
    void ensureProjectSpace_首次创建_type为PROJECT() {
        MemorySpace space = repo.ensureProjectSpace("project-123");
        assertEquals(MemorySpaceType.PROJECT, space.spaceType());
        assertEquals("project:project-123", space.spaceKey());
        assertEquals("PROJECT", space.ownerType());
        assertEquals("project-123", space.ownerId());
    }

    @Test
    void ensureProjectSpace_重复调用_返回同一个空间() {
        MemorySpace a = repo.ensureProjectSpace("project-123");
        MemorySpace b = repo.ensureProjectSpace("project-123");
        assertEquals(a.id(), b.id());
    }
}
```

> **注**：项目里已有测试用的 datasource builder（Grep `EmbeddedDatabaseBuilder\|SqliteTest` 找现成的 pattern，照搬）。

- [ ] **Step 2: 跑测试验证失败**

```bash
mvn -q test -Dtest="MemorySpaceRepository_项目空间集成测试"
```

Expected: 编译失败（`MemorySpaceType.PROJECT` 不存在）

- [ ] **Step 3: 实现**

`MemorySpaceType.java`：

```java
public enum MemorySpaceType {
    PERSONAL,
    DOMAIN,
    EXPERIENCE,
    /** 项目级记忆空间（Plan 1 引入）。 */
    PROJECT
}
```

`MemorySpaceKeys.java` 新增方法：

```java
public static String project(String projectId) {
    return "project:" + projectId;
}
```

`MemorySpaceRepository.java` 新增方法：

```java
public MemorySpace ensureProjectSpace(String projectId) {
    return ensureSpace(
            MemorySpaceKeys.project(projectId),
            MemorySpaceType.PROJECT,
            "项目记忆",
            "PROJECT",
            projectId,
            Map.of("projectId", projectId)
    );
}

public void deleteById(String spaceId) {
    jdbcTemplate.update("DELETE FROM memory_spaces WHERE id = ?", spaceId);
}
```

- [ ] **Step 4: 跑测试通过**

```bash
mvn -q test -Dtest="MemorySpaceRepository_项目空间集成测试"
```

Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/lifepilot/memory/scope/MemorySpaceType.java \
        src/main/java/com/lifepilot/memory/scope/MemorySpaceKeys.java \
        src/main/java/com/lifepilot/memory/scope/MemorySpaceRepository.java \
        src/test/java/com/lifepilot/memory/scope/MemorySpaceRepository_项目空间集成测试.java
git commit -m "feat(memory): MemorySpace 支持 PROJECT 类型"
```

---

## Task 6: `ProjectRepository` CRUD

**Files:**
- Create: `src/main/java/com/lifepilot/project/repository/ProjectRepository.java`
- Test: `src/test/java/com/lifepilot/project/repository/ProjectRepository_集成测试.java`

- [ ] **Step 1: 写失败测试**

```java
package com.lifepilot.project.repository;

import com.lifepilot.project.model.Project;
import com.lifepilot.project.model.ProjectIsolation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
// (省略 H2/SQLite test ds 装配，参照项目现有 pattern)

import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ProjectRepository_集成测试 {

    ProjectRepository repo;
    // setUp: 初始化 H2/SQLite 并跑 Flyway 到 V16

    @Test
    void insert_后_findById_能找到() {
        Instant now = Instant.now();
        Project p = new Project(UUID.randomUUID().toString(), "论文",
                "严谨学术风", ProjectIsolation.ISOLATED, "space-1", now, now);
        repo.insert(p);
        assertEquals(p.name(), repo.findById(p.id()).orElseThrow().name());
    }

    @Test
    void findAll_按创建时间降序() {
        // insert 两条，验证顺序
    }

    @Test
    void update_可改名_可改指示词_可改isolation() {
        // insert 后调 update，verify
    }

    @Test
    void existsByName_判断重名() {
        Instant now = Instant.now();
        Project p = new Project(UUID.randomUUID().toString(), "论文",
                "", ProjectIsolation.ISOLATED, "space-1", now, now);
        repo.insert(p);
        assertTrue(repo.existsByName("论文"));
        assertFalse(repo.existsByName("小说"));
    }

    @Test
    void deleteById_后_找不到() {
        // insert 后 delete，verify findById 返回 empty
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

Expected: 编译失败

- [ ] **Step 3: 实现 repository**

```java
package com.lifepilot.project.repository;

import com.lifepilot.project.model.Project;
import com.lifepilot.project.model.ProjectIsolation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 项目仓储。
 *
 * @author zsg
 * @since 2026-04-23
 */
@Repository
public class ProjectRepository {

    private final JdbcTemplate jdbcTemplate;

    public ProjectRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(Project p) {
        jdbcTemplate.update("""
                INSERT INTO projects (id, name, instructions, isolation, memory_space_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                p.id(), p.name(), p.instructions(), p.isolation().name(),
                p.memorySpaceId(), p.createdAt().toString(), p.updatedAt().toString());
    }

    public Optional<Project> findById(String id) {
        return jdbcTemplate.query("""
                SELECT id, name, instructions, isolation, memory_space_id, created_at, updated_at
                FROM projects WHERE id = ?
                """, this::mapRow, id).stream().findFirst();
    }

    public List<Project> findAll() {
        return jdbcTemplate.query("""
                SELECT id, name, instructions, isolation, memory_space_id, created_at, updated_at
                FROM projects ORDER BY created_at DESC
                """, this::mapRow);
    }

    public boolean existsByName(String name) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM projects WHERE name = ?", Integer.class, name);
        return count != null && count > 0;
    }

    public void update(Project p) {
        jdbcTemplate.update("""
                UPDATE projects SET name = ?, instructions = ?, isolation = ?, updated_at = ?
                WHERE id = ?
                """, p.name(), p.instructions(), p.isolation().name(),
                p.updatedAt().toString(), p.id());
    }

    public void deleteById(String id) {
        jdbcTemplate.update("DELETE FROM projects WHERE id = ?", id);
    }

    private Project mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Project(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("instructions"),
                ProjectIsolation.valueOf(rs.getString("isolation")),
                rs.getString("memory_space_id"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at"))
        );
    }
}
```

- [ ] **Step 4: 跑测试通过**

Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/lifepilot/project/repository/ProjectRepository.java \
        src/test/java/com/lifepilot/project/repository/ProjectRepository_集成测试.java
git commit -m "feat(project): 新增 ProjectRepository CRUD"
```

---

## Task 7: `ProjectService` — CRUD + MemorySpace 联动

**Files:**
- Create: `src/main/java/com/lifepilot/project/service/ProjectService.java`
- Test: `src/test/java/com/lifepilot/project/service/ProjectService_单元测试.java`

- [ ] **Step 1: 写失败测试**

```java
package com.lifepilot.project.service;

import com.lifepilot.memory.scope.*;
import com.lifepilot.project.model.*;
import com.lifepilot.project.repository.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ProjectService_单元测试 {

    @Mock ProjectRepository projectRepository;
    @Mock MemorySpaceRepository memorySpaceRepository;
    @InjectMocks ProjectService service;

    @Test
    void createProject_会自动建关联MemorySpace() {
        when(memorySpaceRepository.ensureProjectSpace(anyString())).thenReturn(
                new MemorySpace("ms-1", "project:p-1", MemorySpaceType.PROJECT, "项目记忆",
                        "PROJECT", "p-1", java.util.Map.of(), Instant.now(), Instant.now()));
        Project created = service.createProject("论文", "严谨", ProjectIsolation.ISOLATED);
        assertEquals("ms-1", created.memorySpaceId());
        verify(memorySpaceRepository).ensureProjectSpace(created.id());
        verify(projectRepository).insert(created);
    }

    @Test
    void createProject_重名抛异常() {
        when(projectRepository.existsByName("论文")).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () ->
                service.createProject("论文", "", ProjectIsolation.ISOLATED));
        verifyNoInteractions(memorySpaceRepository);
    }

    @Test
    void deleteProject_会级联删除MemorySpace() {
        Project p = new Project("p-1", "论文", "", ProjectIsolation.ISOLATED, "ms-1",
                Instant.now(), Instant.now());
        when(projectRepository.findById("p-1")).thenReturn(java.util.Optional.of(p));
        service.deleteProject("p-1");
        verify(projectRepository).deleteById("p-1");
        verify(memorySpaceRepository).deleteById("ms-1");
    }

    @Test
    void updateProject_可改name_和instructions_和isolation() {
        Project p = new Project("p-1", "论文", "", ProjectIsolation.ISOLATED, "ms-1",
                Instant.now(), Instant.now());
        when(projectRepository.findById("p-1")).thenReturn(java.util.Optional.of(p));
        service.updateProject("p-1", "论文 v2", "更严谨", ProjectIsolation.SHARED);
        verify(projectRepository).update(argThat(updated ->
                updated.name().equals("论文 v2")
                        && updated.instructions().equals("更严谨")
                        && updated.isolation() == ProjectIsolation.SHARED));
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

```bash
mvn -q test -Dtest="ProjectService_单元测试"
```

Expected: 编译失败

- [ ] **Step 3: 实现 service**

```java
package com.lifepilot.project.service;

import com.lifepilot.memory.scope.MemorySpace;
import com.lifepilot.memory.scope.MemorySpaceRepository;
import com.lifepilot.project.model.Project;
import com.lifepilot.project.model.ProjectIsolation;
import com.lifepilot.project.repository.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 项目应用服务。
 *
 * @author zsg
 * @since 2026-04-23
 */
@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final MemorySpaceRepository memorySpaceRepository;

    public ProjectService(ProjectRepository projectRepository,
                          MemorySpaceRepository memorySpaceRepository) {
        this.projectRepository = projectRepository;
        this.memorySpaceRepository = memorySpaceRepository;
    }

    @Transactional
    public Project createProject(String name, String instructions, ProjectIsolation isolation) {
        if (projectRepository.existsByName(name)) {
            throw new IllegalArgumentException("项目名已存在：" + name);
        }
        String id = UUID.randomUUID().toString();
        MemorySpace space = memorySpaceRepository.ensureProjectSpace(id);
        Instant now = Instant.now();
        Project project = new Project(id, name, instructions != null ? instructions : "",
                isolation != null ? isolation : ProjectIsolation.defaultValue(),
                space.id(), now, now);
        projectRepository.insert(project);
        return project;
    }

    public List<Project> listProjects() {
        return projectRepository.findAll();
    }

    public Optional<Project> getProject(String id) {
        return projectRepository.findById(id);
    }

    @Transactional
    public Project updateProject(String id, String name, String instructions,
                                 ProjectIsolation isolation) {
        Project existing = projectRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在：" + id));
        Project updated = new Project(existing.id(), name, instructions,
                isolation != null ? isolation : existing.isolation(),
                existing.memorySpaceId(), existing.createdAt(), Instant.now());
        projectRepository.update(updated);
        return updated;
    }

    @Transactional
    public void deleteProject(String id) {
        Project existing = projectRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在：" + id));
        projectRepository.deleteById(id);
        memorySpaceRepository.deleteById(existing.memorySpaceId());
    }
}
```

- [ ] **Step 4: 跑测试通过**

Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/lifepilot/project/service/ProjectService.java \
        src/test/java/com/lifepilot/project/service/ProjectService_单元测试.java
git commit -m "feat(project): 新增 ProjectService 与 MemorySpace 自动联动"
```

---

## Task 8: `ProjectController` — REST CRUD API

**Files:**
- Create: `src/main/java/com/lifepilot/interaction/web/model/project/CreateProjectRequest.java`
- Create: `src/main/java/com/lifepilot/interaction/web/model/project/UpdateProjectRequest.java`
- Create: `src/main/java/com/lifepilot/interaction/web/model/project/ProjectResponse.java`
- Create: `src/main/java/com/lifepilot/interaction/web/controller/ProjectController.java`
- Test: `src/test/java/com/lifepilot/interaction/web/controller/ProjectController_单元测试.java`

- [ ] **Step 1: 写失败测试**

```java
package com.lifepilot.interaction.web.controller;

import com.lifepilot.project.model.Project;
import com.lifepilot.project.model.ProjectIsolation;
import com.lifepilot.project.service.ProjectService;
import com.lifepilot.interaction.web.model.project.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ProjectController_单元测试 {

    @Mock ProjectService service;
    @InjectMocks ProjectController controller;

    @Test
    void 创建项目_返回_201_含完整信息() {
        Project created = new Project("p-1", "论文", "严谨", ProjectIsolation.ISOLATED,
                "ms-1", Instant.now(), Instant.now());
        when(service.createProject(eq("论文"), eq("严谨"), eq(ProjectIsolation.ISOLATED)))
                .thenReturn(created);
        var body = controller.create(new CreateProjectRequest("论文", "严谨", "ISOLATED"));
        assertEquals("p-1", body.getBody().data().id());
    }

    @Test
    void 列表接口_返回所有项目() {
        when(service.listProjects()).thenReturn(List.of(
                new Project("p-1", "论文", "", ProjectIsolation.ISOLATED, "ms-1",
                        Instant.now(), Instant.now())));
        var body = controller.list();
        assertEquals(1, body.getBody().data().size());
    }

    @Test
    void 删除项目_返回_204() {
        var resp = controller.delete("p-1");
        verify(service).deleteProject("p-1");
        assertEquals(204, resp.getStatusCodeValue());
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

Expected: 编译失败

- [ ] **Step 3: 实现 Controller + DTOs**

```java
// CreateProjectRequest.java
package com.lifepilot.interaction.web.model.project;

public record CreateProjectRequest(String name, String instructions, String isolation) {}

// UpdateProjectRequest.java
public record UpdateProjectRequest(String name, String instructions, String isolation) {}

// ProjectResponse.java
package com.lifepilot.interaction.web.model.project;
import com.lifepilot.project.model.Project;
import java.time.Instant;

public record ProjectResponse(
        String id, String name, String instructions, String isolation,
        String memorySpaceId, Instant createdAt, Instant updatedAt
) {
    public static ProjectResponse from(Project p) {
        return new ProjectResponse(p.id(), p.name(), p.instructions(),
                p.isolation().name(), p.memorySpaceId(), p.createdAt(), p.updatedAt());
    }
}
```

```java
package com.lifepilot.interaction.web.controller;

import com.lifepilot.interaction.web.model.ApiResponse;
import com.lifepilot.interaction.web.model.project.*;
import com.lifepilot.project.model.Project;
import com.lifepilot.project.model.ProjectIsolation;
import com.lifepilot.project.service.ProjectService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 项目 REST API。
 *
 * @author zsg
 * @since 2026-04-23
 */
@RestController
@RequestMapping("/api/projects")
@ConditionalOnProperty(name = "lifepilot.gateway.channels.web.enabled", havingValue = "true")
public class ProjectController {

    private final ProjectService service;

    public ProjectController(ProjectService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ProjectResponse>> create(@RequestBody CreateProjectRequest req) {
        Project p = service.createProject(
                req.name(),
                req.instructions() != null ? req.instructions() : "",
                req.isolation() != null ? ProjectIsolation.fromString(req.isolation())
                                        : ProjectIsolation.defaultValue());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(ProjectResponse.from(p)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ProjectResponse>>> list() {
        List<ProjectResponse> items = service.listProjects().stream()
                .map(ProjectResponse::from).toList();
        return ResponseEntity.ok(ApiResponse.ok(items));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ProjectResponse>> get(@PathVariable String id) {
        Project p = service.getProject(id)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在：" + id));
        return ResponseEntity.ok(ApiResponse.ok(ProjectResponse.from(p)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ProjectResponse>> update(
            @PathVariable String id, @RequestBody UpdateProjectRequest req) {
        Project p = service.updateProject(id, req.name(), req.instructions(),
                req.isolation() != null ? ProjectIsolation.fromString(req.isolation()) : null);
        return ResponseEntity.ok(ApiResponse.ok(ProjectResponse.from(p)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.deleteProject(id);
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 4: 跑测试通过**

Expected: PASS (3 tests)

- [ ] **Step 5: 手动冒烟测试 API**

```bash
mvn -q spring-boot:run &  # 后台启动
sleep 20
curl -s -X POST http://localhost:8080/api/projects \
  -H "Content-Type: application/json" \
  -d '{"name":"测试项目","instructions":"","isolation":"ISOLATED"}' | jq
curl -s http://localhost:8080/api/projects | jq
```

Expected: 201 + 返回完整 project JSON；list 里有此项目

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/lifepilot/interaction/web/controller/ProjectController.java \
        src/main/java/com/lifepilot/interaction/web/model/project/*.java \
        src/test/java/com/lifepilot/interaction/web/controller/ProjectController_单元测试.java
git commit -m "feat(project): 新增 /api/projects CRUD"
```

---

## Task 9: `MemoryReadFilter.buildForProject(...)`

**Files:**
- Modify: `src/main/java/com/lifepilot/memory/scope/MemoryReadFilter.java`
- Test: `src/test/java/com/lifepilot/memory/scope/MemoryReadFilter_项目过滤器测试.java`

- [ ] **Step 1: 写失败测试**

```java
package com.lifepilot.memory.scope;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MemoryReadFilter_项目过滤器测试 {

    // 场景：主账户（ms-personal），隔离项目 P（ms-project）
    // 隔离 → filter 应允许 read [ms-project, ms-personal]
    @Test
    void 隔离项目_合并主账户和项目空间() {
        MemoryReadFilter f = MemoryReadFilter.buildForProject(
                "ms-project",              // 项目 space
                "ms-personal",             // 主账户 personal space
                "ms-experience",           // 主账户 experience space
                true                       // isolation = true
        );
        assertTrue(f.spaceIds().contains("ms-project"));
        assertTrue(f.spaceIds().contains("ms-personal"));
        assertTrue(f.spaceIds().contains("ms-experience"));
        assertEquals(3, f.spaceIds().size());
    }

    // 不隔离项目：等同主账户（只看 personal + experience）
    @Test
    void 不隔离项目_只看主账户() {
        MemoryReadFilter f = MemoryReadFilter.buildForProject(
                "ms-project", "ms-personal", "ms-experience", false);
        assertFalse(f.spaceIds().contains("ms-project"));
        assertTrue(f.spaceIds().contains("ms-personal"));
        assertTrue(f.spaceIds().contains("ms-experience"));
    }

    // 主账户对话（projectSpaceId 为 null）
    @Test
    void 主账户对话_projectSpaceId为null时_忽略项目space() {
        MemoryReadFilter f = MemoryReadFilter.buildForProject(
                null, "ms-personal", "ms-experience", false);
        assertEquals(2, f.spaceIds().size());
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

Expected: 编译失败

- [ ] **Step 3: 实现方法**

在 `MemoryReadFilter.java` 加入：

```java
/**
 * 构造项目上下文的读取过滤器。
 *
 * <p>语义：
 * <ul>
 *   <li>隔离项目：允许读取 [项目 space + 主账户 personal + 主账户 experience]</li>
 *   <li>不隔离项目：等同主账户读取（项目 space 不加入）</li>
 *   <li>主账户对话（projectSpaceId = null）：只读主账户 space</li>
 * </ul>
 *
 * @param projectSpaceId       项目 MemorySpace id（主账户对话时为 null）
 * @param personalSpaceId      主账户 personal MemorySpace id
 * @param experienceSpaceId    主账户 experience MemorySpace id
 * @param isolated             当前项目是否 ISOLATED
 */
public static MemoryReadFilter buildForProject(
        @Nullable String projectSpaceId,
        String personalSpaceId,
        String experienceSpaceId,
        boolean isolated) {
    Set<String> spaces = new LinkedHashSet<>();
    if (projectSpaceId != null && isolated) {
        spaces.add(projectSpaceId);
    }
    spaces.add(personalSpaceId);
    spaces.add(experienceSpaceId);
    return new MemoryReadFilter(spaces, Set.of());
}
```

> **注**：Plan 1 先做 space-level 合并，不做 key-level override；L3 用户偏好 / L4 程序记忆的"项目级覆盖主账户同键"留给后续 plan。

- [ ] **Step 4: 跑测试通过**

Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/lifepilot/memory/scope/MemoryReadFilter.java \
        src/test/java/com/lifepilot/memory/scope/MemoryReadFilter_项目过滤器测试.java
git commit -m "feat(memory): MemoryReadFilter 新增 buildForProject 工厂方法"
```

---

## Task 10: `ProjectContext` 与 `ProjectContextResolver`

**Files:**
- Create: `src/main/java/com/lifepilot/project/context/ProjectContext.java`
- Create: `src/main/java/com/lifepilot/project/context/ProjectContextResolver.java`
- Test: `src/test/java/com/lifepilot/project/context/ProjectContextResolver_单元测试.java`

- [ ] **Step 1: 写失败测试**

```java
package com.lifepilot.project.context;

import com.lifepilot.memory.scope.*;
import com.lifepilot.project.model.*;
import com.lifepilot.project.repository.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ProjectContextResolver_单元测试 {

    @Mock ProjectRepository projectRepository;
    @Mock MemorySpaceRepository memorySpaceRepository;
    @InjectMocks ProjectContextResolver resolver;

    @Test
    void projectId为null_返回主账户上下文() {
        when(memorySpaceRepository.ensureDefaultPersonalSpace()).thenReturn(
                space("ms-personal", MemorySpaceType.PERSONAL));
        when(memorySpaceRepository.ensureDefaultExperienceSpace()).thenReturn(
                space("ms-exp", MemorySpaceType.EXPERIENCE));
        ProjectContext ctx = resolver.resolve(null);
        assertNull(ctx.projectId());
        assertNull(ctx.projectSpaceId());
        assertEquals("ms-personal", ctx.personalSpaceId());
        assertFalse(ctx.isolated());
    }

    @Test
    void projectId非null_返回项目上下文_含隔离标志() {
        Project p = new Project("p-1", "论文", "", ProjectIsolation.ISOLATED,
                "ms-project", Instant.now(), Instant.now());
        when(projectRepository.findById("p-1")).thenReturn(Optional.of(p));
        when(memorySpaceRepository.ensureDefaultPersonalSpace())
                .thenReturn(space("ms-personal", MemorySpaceType.PERSONAL));
        when(memorySpaceRepository.ensureDefaultExperienceSpace())
                .thenReturn(space("ms-exp", MemorySpaceType.EXPERIENCE));
        ProjectContext ctx = resolver.resolve("p-1");
        assertEquals("p-1", ctx.projectId());
        assertEquals("ms-project", ctx.projectSpaceId());
        assertTrue(ctx.isolated());
    }

    private MemorySpace space(String id, MemorySpaceType type) {
        return new MemorySpace(id, "k", type, "n", "o", "oid",
                java.util.Map.of(), Instant.now(), Instant.now());
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

Expected: 编译失败

- [ ] **Step 3: 实现**

```java
// ProjectContext.java
package com.lifepilot.project.context;

import org.springframework.lang.Nullable;

/**
 * 请求/对话的项目上下文。
 *
 * <p>projectId 为 null 表示主账户对话；非 null 且 isolated=true 表示隔离项目对话。</p>
 */
public record ProjectContext(
        @Nullable String projectId,
        @Nullable String projectSpaceId,
        String personalSpaceId,
        String experienceSpaceId,
        boolean isolated
) {

    public static ProjectContext personal(String personalSpaceId, String experienceSpaceId) {
        return new ProjectContext(null, null, personalSpaceId, experienceSpaceId, false);
    }
}
```

```java
// ProjectContextResolver.java
package com.lifepilot.project.context;

import com.lifepilot.memory.scope.MemorySpace;
import com.lifepilot.memory.scope.MemorySpaceRepository;
import com.lifepilot.project.model.Project;
import com.lifepilot.project.model.ProjectIsolation;
import com.lifepilot.project.repository.ProjectRepository;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * 从 projectId 解析 ProjectContext。
 *
 * @author zsg
 * @since 2026-04-23
 */
@Component
public class ProjectContextResolver {

    private final ProjectRepository projectRepository;
    private final MemorySpaceRepository memorySpaceRepository;

    public ProjectContextResolver(ProjectRepository projectRepository,
                                  MemorySpaceRepository memorySpaceRepository) {
        this.projectRepository = projectRepository;
        this.memorySpaceRepository = memorySpaceRepository;
    }

    public ProjectContext resolve(@Nullable String projectId) {
        MemorySpace personal = memorySpaceRepository.ensureDefaultPersonalSpace();
        MemorySpace experience = memorySpaceRepository.ensureDefaultExperienceSpace();
        if (projectId == null) {
            return ProjectContext.personal(personal.id(), experience.id());
        }
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在：" + projectId));
        return new ProjectContext(
                p.id(),
                p.memorySpaceId(),
                personal.id(),
                experience.id(),
                p.isolation() == ProjectIsolation.ISOLATED
        );
    }
}
```

- [ ] **Step 4: 跑测试通过**

Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/lifepilot/project/context/*.java \
        src/test/java/com/lifepilot/project/context/*.java
git commit -m "feat(project): 新增 ProjectContext 与解析器"
```

---

## Task 11: `conversations` CRUD 加 `projectId` 字段

**Files:**
- Modify: 对话仓储（先 Grep 找：`grep -rn "INSERT INTO conversations" src/main/java`，定位 JdbcTemplate 对应代码）
- Test: 在定位到的 repository 测试里追加

- [ ] **Step 1: 定位对话 INSERT 代码**

```bash
grep -rn "INSERT INTO conversations" src/main/java
```

记录文件路径（预期类似 `conversation/transcript/JdbcTranscriptStore.java` 或 `conversation/repository/ConversationRepository.java`）。

- [ ] **Step 2: 写失败测试**

在该文件对应的集成测试里加：

```java
@Test
void 创建对话_可携带projectId_能回读() {
    String id = store.createConversation(/* ...原有参数, */ "p-1");
    var conv = store.findById(id).orElseThrow();
    assertEquals("p-1", conv.projectId());
}

@Test
void 创建对话_projectId为null_回读也为null() {
    String id = store.createConversation(/* ...原有参数, */ null);
    assertNull(store.findById(id).orElseThrow().projectId());
}
```

跑测试：Expected 编译失败（createConversation 签名未变）

- [ ] **Step 3: 修改 repository / record**

在对话 record 里加 `@Nullable String projectId` 字段；在 `INSERT INTO conversations` 和 `SELECT` 列表里加 `project_id`；createConversation/insert 签名加一个 `@Nullable String projectId` 参数。

关键 SQL：

```java
jdbcTemplate.update("""
        INSERT INTO conversations (id, session_id, goal, summary, project_id, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """, id, sessionId, goal, summary, projectId, now, now);
```

并在读取方法中 select `project_id` 并填入 record。

- [ ] **Step 4: 跑测试通过**

```bash
mvn -q test -Dtest="<对话仓储测试类名>"
```

Expected: PASS (新增 2 tests)

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(conversation): 对话持久化支持 project_id"
```

---

## Task 12: 对话创建入口注入 `projectId`

**Files:**
- Modify: 对话创建的 Controller / Service（先 Grep：`grep -rn "createConversation\|conversations\"" src/main/java/com/lifepilot/interaction`）

- [ ] **Step 1: 定位对话创建的 Controller 入口**

```bash
grep -rn "@PostMapping" src/main/java/com/lifepilot/interaction/web/controller/ | grep -i conversation
```

- [ ] **Step 2: 写失败测试（Controller 层）**

```java
@Test
void 创建对话_请求体带projectId_会透传到service() {
    var req = new CreateConversationRequest(/*existing fields,*/ "p-1");
    controller.create(req);
    verify(service).createConversation(/* existing args, */ eq("p-1"));
}
```

- [ ] **Step 3: 修改请求 DTO 和 Service 签名**

给 `CreateConversationRequest` record 加 `@Nullable String projectId`；给 Service / Store 的 createConversation 签名加 projectId 并透传。

- [ ] **Step 4: 跑测试通过**

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(conversation): 对话创建 API 接受可选 projectId"
```

---

## Task 13: 对话列表 API 支持按 `projectId` 过滤

**Files:**
- Modify: 对话列表 Controller / Service / Repository

- [ ] **Step 1: 写失败测试**

在对话列表仓储测试追加：

```java
@Test
void 按projectId过滤_只返回归属该项目的对话() {
    // insert 3 条：2 条 projectId=p-1，1 条 projectId=null
    var list = store.findByProjectId("p-1");
    assertEquals(2, list.size());
}

@Test
void 按projectId过滤_传null_返回主账户对话() {
    // insert 3 条：1 条 projectId=p-1，2 条 projectId=null
    var list = store.findByProjectId(null);
    assertEquals(2, list.size());
    list.forEach(c -> assertNull(c.projectId()));
}
```

- [ ] **Step 2: 跑测试验证失败**

Expected: 编译失败

- [ ] **Step 3: 实现过滤**

Repository 加：

```java
public List<Conversation> findByProjectId(@Nullable String projectId) {
    String sql = projectId == null
        ? "SELECT ... FROM conversations WHERE project_id IS NULL ORDER BY created_at DESC"
        : "SELECT ... FROM conversations WHERE project_id = ? ORDER BY created_at DESC";
    return projectId == null
        ? jdbcTemplate.query(sql, this::mapRow)
        : jdbcTemplate.query(sql, this::mapRow, projectId);
}
```

Controller 暴露 `GET /api/conversations?projectId=xxx`。

- [ ] **Step 4: 跑测试通过 + API 冒烟**

```bash
mvn -q test -Dtest="<列表测试类>"
curl "http://localhost:8080/api/conversations?projectId=p-1" | jq
```

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(conversation): 对话列表 API 支持按 projectId 过滤"
```

---

## Task 14: 记忆读取路径接入 ProjectContext

**Files:**
- Modify: 记忆读取主要入口（先 Grep：`grep -rn "MemoryReadFilter\." src/main/java | grep -v "\.java:" | head`）

- [ ] **Step 1: 定位关键调用点**

```bash
grep -rn "MemoryReadFilter\.\(all\|userMemory\|userProfile\|agentExperience\)" src/main/java
```

预期找到：Semantic/Procedural/Episodic retrieval 的入口 Service/Class。

- [ ] **Step 2: 写失败测试**

选一个代表性 retrieval service（如 `MemoryRetriever` 或类似），写测试：

```java
@Test
void 隔离项目对话_读取filter包含项目space和主账户space() {
    ProjectContext ctx = new ProjectContext("p-1", "ms-project", "ms-personal",
            "ms-exp", true);
    MemoryReadFilter filter = retriever.buildReadFilter(ctx);
    assertTrue(filter.spaceIds().contains("ms-project"));
    assertTrue(filter.spaceIds().contains("ms-personal"));
    assertTrue(filter.spaceIds().contains("ms-exp"));
}

@Test
void 主账户对话_读取filter不含项目space() {
    ProjectContext ctx = ProjectContext.personal("ms-personal", "ms-exp");
    MemoryReadFilter filter = retriever.buildReadFilter(ctx);
    assertFalse(filter.spaceIds().stream().anyMatch(id -> id.startsWith("ms-project")));
}
```

- [ ] **Step 3: 改造 retriever 入口**

在 retriever / retrieval service 加入方法：

```java
public MemoryReadFilter buildReadFilter(ProjectContext ctx) {
    return MemoryReadFilter.buildForProject(
            ctx.projectSpaceId(), ctx.personalSpaceId(),
            ctx.experienceSpaceId(), ctx.isolated());
}
```

并把**所有**原先用 `MemoryReadFilter.all()` / `.userMemory()` 的调用点，改成从 ProjectContext 构造。调用方需要拿到当前对话的 ProjectContext（通过 ProjectContextResolver 从对话 projectId 解析）。

**实施策略**：
1. 先在 retriever 入口接受 `ProjectContext` 参数
2. 把所有调用方级联改造：对话级链路从 projectId → ProjectContextResolver.resolve(projectId) 获得 ctx → 传给 retriever

这一步影响面较大，分几个 sub-commit：
- sub-a: `MemoryRetriever`/`SemanticRetriever` 等类加 ProjectContext 参数（保留旧 overload，内部调用 `ProjectContext.personal(...)` 兜底）
- sub-b: Agent 执行主链（ReactAgentLoop / ContextAssembler）从对话的 projectId 拿 ProjectContext 并传入 retriever

- [ ] **Step 4: 跑测试通过**

```bash
mvn -q test -Dtest="*MemoryRetriever*"
```

- [ ] **Step 5: Commit (分 sub-commit)**

```bash
git add -A
git commit -m "feat(memory): 记忆读取接入 ProjectContext（隔离项目继承主账户）"
```

---

## Task 15: 记忆写入路径接入 ProjectContext

**Files:**
- Modify: 记忆写入入口（Grep：`grep -rn "ensureDefaultPersonalSpace\|ensureDomainSpace\|MemoryWriteContext" src/main/java`）

- [ ] **Step 1: 定位写入点**

Semantic / Episodic / Procedural 的写入：查找 `INSERT INTO semantic_entity\|episodic\|procedural` 或 `MemoryWriteContext`（如果已有）。

- [ ] **Step 2: 写失败测试**

```java
@Test
void 隔离项目对话_写入记忆_落到项目space() {
    ProjectContext ctx = new ProjectContext("p-1", "ms-project", "ms-personal",
            "ms-exp", true);
    writer.writeSemantic(ctx, "事实内容");
    verify(jdbcTemplate).update(contains("INSERT"),
            argThat(args -> java.util.Arrays.asList(args).contains("ms-project")));
}

@Test
void 不隔离项目对话_写入记忆_落到主账户space() {
    ProjectContext ctx = new ProjectContext("p-1", "ms-project", "ms-personal",
            "ms-exp", false);
    writer.writeSemantic(ctx, "事实内容");
    verify(jdbcTemplate).update(contains("INSERT"),
            argThat(args -> java.util.Arrays.asList(args).contains("ms-personal")));
}
```

- [ ] **Step 3: 改造写入路由**

在写入方法里按 `ctx.isolated()` 选择 space：

```java
String targetSpaceId = ctx.isolated() && ctx.projectSpaceId() != null
        ? ctx.projectSpaceId()
        : ctx.personalSpaceId();  // 不隔离时合流到主账户
// 同理 experience 记忆用 experienceSpaceId 相关规则
```

- [ ] **Step 4: 跑测试通过**

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(memory): 记忆写入按项目隔离模式路由到对应 space"
```

---

## Task 16: 项目删除级联清理

**Files:**
- Modify: `ProjectService.deleteProject`
- Test: 在 `ProjectService_单元测试` 扩展

- [ ] **Step 1: 写失败测试**

```java
@Test
void deleteProject_级联清理对话() {
    Project p = /* ... */;
    when(projectRepository.findById("p-1")).thenReturn(Optional.of(p));
    service.deleteProject("p-1");
    verify(conversationRepository).deleteByProjectId("p-1");
}

@Test
void deleteProject_级联清理项目级memory_space_knowledge_bases关联() {
    // 验证 memory_space_knowledge_bases 表中 space_id=ms-project 的行被删
}
```

- [ ] **Step 2: 跑测试验证失败**

- [ ] **Step 3: 扩展 ProjectService.deleteProject**

```java
@Transactional
public void deleteProject(String id) {
    Project existing = projectRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("项目不存在：" + id));
    // 级联：对话 → messages (FK)，memory_space_knowledge_bases，memory_space
    conversationRepository.deleteByProjectId(id);
    memorySpaceKbLinkRepository.deleteBySpaceId(existing.memorySpaceId());
    projectRepository.deleteById(id);
    memorySpaceRepository.deleteById(existing.memorySpaceId());
}
```

> **注**：具体级联 repository 和方法名在 Grep 现有代码后确定（`grep -rn "memory_space_knowledge_bases" src/main/java`）。

- [ ] **Step 4: 跑测试通过 + 集成验证**

```bash
# 端到端：建一个项目→开对话→删项目→库里不应剩记录
```

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(project): 删除项目级联清理关联对话和 memory_space"
```

---

## Task 17: 前端 API client 与 Pinia store

**Files:**
- Create: `zhiwei-web/src/api/project.ts`
- Create: `zhiwei-web/src/stores/project.ts`
- Test: `zhiwei-web/src/stores/__tests__/project.spec.ts`

- [ ] **Step 1: 写失败测试**

```typescript
// project.spec.ts
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useProjectStore } from '../project'
import * as projectApi from '@/api/project'

vi.mock('@/api/project')

describe('useProjectStore', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('fetchProjects 拉取并保存列表', async () => {
    vi.mocked(projectApi.listProjects).mockResolvedValue([
      { id: 'p-1', name: '论文', instructions: '', isolation: 'ISOLATED',
        memorySpaceId: 'ms-1', createdAt: '', updatedAt: '' }
    ])
    const store = useProjectStore()
    await store.fetchProjects()
    expect(store.projects).toHaveLength(1)
    expect(store.projects[0].name).toBe('论文')
  })

  it('createProject 成功后追加到列表', async () => {
    vi.mocked(projectApi.createProject).mockResolvedValue({
      id: 'p-2', name: '小说', instructions: '', isolation: 'ISOLATED',
      memorySpaceId: 'ms-2', createdAt: '', updatedAt: ''
    })
    const store = useProjectStore()
    await store.createProject({ name: '小说', instructions: '', isolation: 'ISOLATED' })
    expect(store.projects.some(p => p.name === '小说')).toBe(true)
  })
})
```

- [ ] **Step 2: 跑测试验证失败**

```bash
cd zhiwei-web && npm run test:run -- project.spec
```

Expected: 编译/模块解析失败

- [ ] **Step 3: 实现**

```typescript
// api/project.ts
import { http } from './http'  // 复用项目现有 axios 封装（先 Grep 找）

export interface ProjectDto {
  id: string
  name: string
  instructions: string
  isolation: 'ISOLATED' | 'SHARED'
  memorySpaceId: string
  createdAt: string
  updatedAt: string
}

export interface CreateProjectRequest {
  name: string
  instructions: string
  isolation: 'ISOLATED' | 'SHARED'
}

export async function listProjects(): Promise<ProjectDto[]> {
  const { data } = await http.get('/api/projects')
  return data.data
}

export async function createProject(req: CreateProjectRequest): Promise<ProjectDto> {
  const { data } = await http.post('/api/projects', req)
  return data.data
}

export async function updateProject(id: string, req: CreateProjectRequest): Promise<ProjectDto> {
  const { data } = await http.put(`/api/projects/${id}`, req)
  return data.data
}

export async function deleteProject(id: string): Promise<void> {
  await http.delete(`/api/projects/${id}`)
}

export async function getProject(id: string): Promise<ProjectDto> {
  const { data } = await http.get(`/api/projects/${id}`)
  return data.data
}
```

```typescript
// stores/project.ts
import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { ProjectDto, CreateProjectRequest } from '@/api/project'
import * as api from '@/api/project'

export const useProjectStore = defineStore('project', () => {
  const projects = ref<ProjectDto[]>([])
  const loading = ref(false)

  async function fetchProjects() {
    loading.value = true
    try {
      projects.value = await api.listProjects()
    } finally {
      loading.value = false
    }
  }

  async function createProject(req: CreateProjectRequest): Promise<ProjectDto> {
    const p = await api.createProject(req)
    projects.value.unshift(p)
    return p
  }

  async function deleteProject(id: string) {
    await api.deleteProject(id)
    projects.value = projects.value.filter(p => p.id !== id)
  }

  async function updateProject(id: string, req: CreateProjectRequest): Promise<ProjectDto> {
    const p = await api.updateProject(id, req)
    const idx = projects.value.findIndex(x => x.id === id)
    if (idx >= 0) projects.value[idx] = p
    return p
  }

  return { projects, loading, fetchProjects, createProject, deleteProject, updateProject }
})
```

- [ ] **Step 4: 跑测试通过**

```bash
cd zhiwei-web && npm run test:run -- project.spec
```

Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add zhiwei-web/src/api/project.ts \
        zhiwei-web/src/stores/project.ts \
        zhiwei-web/src/stores/__tests__/project.spec.ts
git commit -m "feat(web): 新增 project store 与 API client"
```

---

## Task 18: 侧栏重构 —— `ProjectSection` 组件 + 定时任务入口占位

**Files:**
- Create: `zhiwei-web/src/components/sidebar/ProjectSection.vue`
- Modify: 侧栏主组件（先 Grep：`grep -rn "所有对话\|新建对话" zhiwei-web/src/components/`）

- [ ] **Step 1: 定位现有侧栏主组件**

```bash
grep -rn "所有对话\|新建对话\|搜索对话" zhiwei-web/src/components/
grep -rn "sidebar" zhiwei-web/src/components/ | head -20
```

记录主侧栏组件路径（可能是 `AppSidebar.vue` 或 `AppLayout.vue` 内的侧栏区域）。

- [ ] **Step 2: 写 `ProjectSection` 测试**

```typescript
// zhiwei-web/src/components/sidebar/__tests__/ProjectSection.spec.ts
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import ProjectSection from '../ProjectSection.vue'

describe('ProjectSection', () => {
  it('展示项目列表', () => {
    const wrapper = mount(ProjectSection, {
      global: {
        plugins: [createTestingPinia({
          initialState: {
            project: {
              projects: [
                { id: 'p-1', name: '毕业论文', instructions: '', isolation: 'ISOLATED',
                  memorySpaceId: 'ms-1', createdAt: '', updatedAt: '' }
              ]
            }
          }
        })]
      }
    })
    expect(wrapper.text()).toContain('毕业论文')
  })

  it('"新建项目"按钮触发 emit', async () => {
    const wrapper = mount(ProjectSection, {
      global: { plugins: [createTestingPinia()] }
    })
    await wrapper.find('[data-testid="create-project-btn"]').trigger('click')
    expect(wrapper.emitted('create')).toBeTruthy()
  })
})
```

- [ ] **Step 3: 实现 `ProjectSection.vue`**

```vue
<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { FolderIcon, FolderPlusIcon } from 'lucide-vue-next'
import { useProjectStore } from '@/stores/project'

defineEmits<{ create: [] }>()

const store = useProjectStore()
const router = useRouter()

onMounted(() => store.fetchProjects())

function openProject(id: string) {
  router.push(`/projects/${id}`)
}
</script>

<template>
  <div class="flex flex-col gap-xs py-sm">
    <div class="px-md text-xs uppercase tracking-wider text-muted-foreground">项目</div>

    <button
      data-testid="create-project-btn"
      class="flex items-center gap-sm px-md py-xs rounded-md hover:bg-accent"
      @click="$emit('create')"
    >
      <FolderPlusIcon class="size-md" />
      <span class="text-md">新建项目</span>
    </button>

    <button
      v-for="p in store.projects" :key="p.id"
      class="flex items-center gap-sm px-md py-xs rounded-md hover:bg-accent text-left"
      @click="openProject(p.id)"
    >
      <FolderIcon class="size-md" />
      <span class="text-md truncate">{{ p.name }}</span>
    </button>
  </div>
</template>
```

- [ ] **Step 4: 在主侧栏插入 `ProjectSection`**

修改 Step 1 定位的主侧栏组件（例如 `AppSidebar.vue`），在"新建对话"/"搜索对话"之后、"所有对话"之前插入：

```vue
<!-- ... 新建对话 / 搜索对话 ... -->

<button
  class="flex items-center gap-sm px-md py-xs rounded-md hover:bg-accent"
  @click="router.push('/scheduled-tasks')"
  data-testid="scheduled-tasks-entry"
>
  <ClockIcon class="size-md" />
  <span class="text-md">定时任务</span>
</button>

<ProjectSection @create="showCreateProjectDialog = true" />

<!-- ... 所有对话 ... -->

<CreateProjectDialog v-model:open="showCreateProjectDialog" />
```

> **注**：`/scheduled-tasks` 路由暂不实现，留给 Plan 2。这里只占位，点击后 404 即可。

- [ ] **Step 5: 跑测试 + 手动验证 + Commit**

```bash
cd zhiwei-web && npm run test:run -- ProjectSection
npm run dev
# 手动验证：侧栏出现"项目"分组 + "定时任务"入口
```

```bash
git add -A
git commit -m "feat(web): 侧栏加入项目分组与定时任务入口占位"
```

---

## Task 19: `CreateProjectDialog` 组件

**Files:**
- Create: `zhiwei-web/src/components/project/CreateProjectDialog.vue`
- Test: `zhiwei-web/src/components/project/__tests__/CreateProjectDialog.spec.ts`

- [ ] **Step 1: 写失败测试**

```typescript
import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import CreateProjectDialog from '../CreateProjectDialog.vue'

describe('CreateProjectDialog', () => {
  it('默认选中"隔离"', () => {
    const wrapper = mount(CreateProjectDialog, {
      props: { open: true },
      global: { plugins: [createTestingPinia()] }
    })
    const isolated = wrapper.find('[data-testid="isolation-ISOLATED"]') as any
    expect(isolated.element.checked).toBe(true)
  })

  it('提交时调 store.createProject 并关闭', async () => {
    const wrapper = mount(CreateProjectDialog, {
      props: { open: true },
      global: { plugins: [createTestingPinia({ createSpy: vi.fn })] }
    })
    await wrapper.find('[data-testid="name-input"]').setValue('论文')
    await wrapper.find('[data-testid="submit-btn"]').trigger('click')
    // expect store.createProject called with { name: '论文', isolation: 'ISOLATED', ... }
  })

  it('名字为空时提交按钮禁用', async () => {
    const wrapper = mount(CreateProjectDialog, {
      props: { open: true },
      global: { plugins: [createTestingPinia()] }
    })
    const submit = wrapper.find('[data-testid="submit-btn"]')
    expect(submit.attributes('disabled')).toBeDefined()
  })
})
```

- [ ] **Step 2: 跑测试验证失败**

- [ ] **Step 3: 实现组件**

```vue
<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { DialogRoot, DialogOverlay, DialogContent, DialogTitle, DialogClose } from 'reka-ui'
import { XIcon } from 'lucide-vue-next'
import { useProjectStore } from '@/stores/project'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ 'update:open': [value: boolean] }>()

const store = useProjectStore()

const name = ref('')
const instructions = ref('')
const isolation = ref<'ISOLATED' | 'SHARED'>('ISOLATED')
const showAdvanced = ref(false)
const submitting = ref(false)

const canSubmit = computed(() => name.value.trim().length > 0 && !submitting.value)

watch(() => props.open, (v) => {
  if (v) {
    name.value = ''
    instructions.value = ''
    isolation.value = 'ISOLATED'
    showAdvanced.value = false
  }
})

async function submit() {
  if (!canSubmit.value) return
  submitting.value = true
  try {
    await store.createProject({
      name: name.value.trim(),
      instructions: instructions.value,
      isolation: isolation.value
    })
    emit('update:open', false)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <DialogRoot :open="open" @update:open="emit('update:open', $event)">
    <DialogOverlay class="fixed inset-0 bg-black/40" />
    <DialogContent class="fixed left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2
                          w-[520px] max-w-[90vw] bg-card rounded-xl p-xl shadow-xl">
      <div class="flex items-center justify-between mb-md">
        <DialogTitle class="text-lg font-semibold">新建项目</DialogTitle>
        <DialogClose class="p-xs rounded hover:bg-accent"><XIcon class="size-md" /></DialogClose>
      </div>

      <div class="flex flex-col gap-md">
        <div>
          <label class="text-md">项目名称</label>
          <input
            data-testid="name-input"
            v-model="name"
            maxlength="64"
            class="w-full mt-xs px-md py-xs border rounded-md"
            placeholder="例如：毕业论文-MT 评估"
          />
        </div>

        <details :open="showAdvanced" @toggle="(e: any) => showAdvanced = e.target.open">
          <summary class="cursor-pointer text-md text-muted-foreground">高级设置</summary>
          <div class="flex flex-col gap-md mt-md pl-md">
            <div>
              <div class="text-md mb-xs">记忆</div>
              <label class="flex items-start gap-sm mb-xs cursor-pointer">
                <input
                  data-testid="isolation-SHARED"
                  type="radio" name="isolation" value="SHARED" v-model="isolation"
                  class="mt-xs"
                />
                <div>
                  <div class="text-md">不隔离</div>
                  <div class="text-xs text-muted-foreground">
                    项目聊天与主账户记忆双向读写
                  </div>
                </div>
              </label>
              <label class="flex items-start gap-sm cursor-pointer">
                <input
                  data-testid="isolation-ISOLATED"
                  type="radio" name="isolation" value="ISOLATED" v-model="isolation"
                  class="mt-xs"
                />
                <div>
                  <div class="text-md">隔离（默认）</div>
                  <div class="text-xs text-muted-foreground">
                    项目聊天可读主账户记忆作为背景，但产出不写回主账户
                  </div>
                </div>
              </label>
            </div>

            <div>
              <label class="text-md">指示词</label>
              <textarea
                v-model="instructions"
                maxlength="1000"
                class="w-full mt-xs px-md py-xs border rounded-md resize-none"
                rows="4"
                placeholder="AI 应该了解这个项目的哪些信息？"
              />
              <div class="text-xs text-muted-foreground text-right">
                {{ instructions.length }} / 1000
              </div>
            </div>
          </div>
        </details>
      </div>

      <div class="flex justify-end gap-sm mt-xl">
        <button
          class="px-md py-xs rounded-md hover:bg-accent"
          @click="emit('update:open', false)"
        >取消</button>
        <button
          data-testid="submit-btn"
          :disabled="!canSubmit"
          class="px-md py-xs rounded-md bg-primary text-primary-foreground
                 disabled:opacity-50 disabled:cursor-not-allowed"
          @click="submit"
        >创建项目</button>
      </div>
    </DialogContent>
  </DialogRoot>
</template>
```

> **注**：初始文件上传字段延后到前端联调阶段再加（Plan 1 范围内只做文本字段 + isolation；文件上传可在侧 Task 或 Plan 1 尾部补充）。

- [ ] **Step 4: 跑测试通过 + 手动验证**

```bash
cd zhiwei-web && npm run test:run -- CreateProjectDialog
npm run dev
# 手动验证：点击"新建项目" → 对话框打开 → 填写名字 → 高级设置折叠 → 创建
```

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(web): 新建项目对话框（名字/记忆/指示词）"
```

---

## Task 20: `ProjectDetailView` 项目详情页

**Files:**
- Create: `zhiwei-web/src/views/ProjectDetailView.vue`
- Modify: `zhiwei-web/src/router/index.ts`
- Test: `zhiwei-web/src/views/__tests__/ProjectDetailView.spec.ts`

- [ ] **Step 1: 写失败测试**

```typescript
import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import ProjectDetailView from '../ProjectDetailView.vue'
import { createRouter, createMemoryHistory } from 'vue-router'

describe('ProjectDetailView', () => {
  it('顶栏展示项目名', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/projects/:id', name: 'projectDetail', component: ProjectDetailView }]
    })
    await router.push('/projects/p-1')
    const wrapper = mount(ProjectDetailView, {
      global: {
        plugins: [router, createTestingPinia({
          initialState: {
            project: {
              projects: [{ id: 'p-1', name: '毕业论文', instructions: '', isolation: 'ISOLATED',
                memorySpaceId: 'ms-1', createdAt: '', updatedAt: '' }]
            }
          }
        })]
      }
    })
    expect(wrapper.text()).toContain('毕业论文')
  })
})
```

- [ ] **Step 2: 跑测试验证失败**

- [ ] **Step 3: 实现**

```vue
<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { FolderIcon, PaperclipIcon, SettingsIcon } from 'lucide-vue-next'
import { useProjectStore } from '@/stores/project'
import ProjectResourcePanel from '@/components/project/ProjectResourcePanel.vue'
import ProjectSettingsPanel from '@/components/project/ProjectSettingsPanel.vue'
// import 现有 ChatInput 和对话列表组件（先 Grep 定位）

const route = useRoute()
const router = useRouter()
const store = useProjectStore()

const projectId = computed(() => route.params.id as string)
const project = computed(() => store.projects.find(p => p.id === projectId.value))

const showResource = ref(false)
const showSettings = ref(false)

onMounted(async () => {
  if (store.projects.length === 0) await store.fetchProjects()
})

async function createNewConversation() {
  // 调用现有的新建对话 API，带 projectId，跳转
  router.push({ name: 'newConversation', query: { projectId: projectId.value } })
}
</script>

<template>
  <div v-if="project" class="flex flex-col h-full">
    <header class="flex items-center justify-between px-xl py-md border-b">
      <div class="flex items-center gap-sm">
        <FolderIcon class="size-md" />
        <span class="text-lg font-semibold">{{ project.name }}</span>
      </div>
      <div class="flex items-center gap-sm">
        <button
          class="p-xs rounded hover:bg-accent"
          title="项目资料" @click="showResource = true"
        >
          <PaperclipIcon class="size-md" />
        </button>
        <button
          class="p-xs rounded hover:bg-accent"
          title="项目设置" @click="showSettings = true"
        >
          <SettingsIcon class="size-md" />
        </button>
      </div>
    </header>

    <div class="flex-1 overflow-auto p-xl flex flex-col items-center">
      <button
        class="mb-xl px-xl py-md rounded-xl bg-primary text-primary-foreground"
        @click="createNewConversation"
      >开始新对话</button>
      <!-- TODO: 加上该项目的对话列表组件（复用现有 ConversationList + projectId 参数） -->
    </div>

    <ProjectResourcePanel v-model:open="showResource" :project-id="projectId" />
    <ProjectSettingsPanel v-model:open="showSettings" :project="project" />
  </div>
  <div v-else class="p-xl text-muted-foreground">加载中…</div>
</template>
```

路由加：

```typescript
// router/index.ts 在 routes 数组里加
{
  path: '/projects/:id',
  name: 'projectDetail',
  component: () => import('@/views/ProjectDetailView.vue')
}
```

- [ ] **Step 4: 跑测试通过 + 手动冒烟**

```bash
cd zhiwei-web && npm run test:run -- ProjectDetailView
npm run dev
# 手动：侧栏点击项目 → 跳到项目详情页 → 顶栏显示项目名
```

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(web): 新增项目详情页（清爽款 + 资料/设置抽屉占位）"
```

---

## Task 21: `ProjectResourcePanel` 项目资料抽屉

**Files:**
- Create: `zhiwei-web/src/components/project/ProjectResourcePanel.vue`
- Test: `zhiwei-web/src/components/project/__tests__/ProjectResourcePanel.spec.ts`

- [ ] **Step 1: 写失败测试**

```typescript
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import ProjectResourcePanel from '../ProjectResourcePanel.vue'

describe('ProjectResourcePanel', () => {
  it('默认显示知识库 tab', () => {
    const wrapper = mount(ProjectResourcePanel, {
      props: { open: true, projectId: 'p-1' },
      global: { plugins: [createTestingPinia()] }
    })
    expect(wrapper.text()).toContain('知识库')
    expect(wrapper.find('[data-testid="tab-knowledge-base"]').classes())
      .toContain('bg-accent')  // 当前选中样式
  })

  it('切换到文档 tab', async () => {
    const wrapper = mount(ProjectResourcePanel, {
      props: { open: true, projectId: 'p-1' },
      global: { plugins: [createTestingPinia()] }
    })
    await wrapper.find('[data-testid="tab-documents"]').trigger('click')
    expect(wrapper.find('[data-testid="tab-documents"]').classes())
      .toContain('bg-accent')
  })
})
```

- [ ] **Step 2: 跑测试验证失败**

- [ ] **Step 3: 实现**

```vue
<script setup lang="ts">
import { ref } from 'vue'
import { DialogRoot, DialogOverlay, DialogContent, DialogTitle, DialogClose } from 'reka-ui'
import { XIcon } from 'lucide-vue-next'

const props = defineProps<{ open: boolean; projectId: string }>()
const emit = defineEmits<{ 'update:open': [value: boolean] }>()

const tab = ref<'knowledgeBase' | 'documents'>('knowledgeBase')
</script>

<template>
  <DialogRoot :open="open" @update:open="emit('update:open', $event)">
    <DialogOverlay class="fixed inset-0 bg-black/40" />
    <DialogContent class="fixed right-0 top-0 bottom-0 w-[480px] bg-card shadow-xl
                          flex flex-col">
      <header class="flex items-center justify-between px-xl py-md border-b">
        <DialogTitle class="text-lg font-semibold">项目资料</DialogTitle>
        <DialogClose class="p-xs rounded hover:bg-accent"><XIcon class="size-md" /></DialogClose>
      </header>

      <nav class="flex border-b">
        <button
          data-testid="tab-knowledge-base"
          :class="['flex-1 py-md text-md', tab === 'knowledgeBase' ? 'bg-accent' : '']"
          @click="tab = 'knowledgeBase'"
        >知识库</button>
        <button
          data-testid="tab-documents"
          :class="['flex-1 py-md text-md', tab === 'documents' ? 'bg-accent' : '']"
          @click="tab = 'documents'"
        >文档</button>
      </nav>

      <div class="flex-1 overflow-auto p-xl">
        <div v-if="tab === 'knowledgeBase'">
          <!-- TODO: 复用现有 KnowledgeBase 列表组件，按 projectId 过滤；
               当前 KB 没有 project_id 字段，需要通过 memory_space_knowledge_bases 关联查询。
               Plan 1 内若代价过高可先显示占位："此项目暂未绑定知识库" -->
          <p class="text-muted-foreground">知识库绑定视图（占位）</p>
        </div>
        <div v-else>
          <!-- TODO: 复用现有文档列表组件 -->
          <p class="text-muted-foreground">文档列表（占位）</p>
        </div>
      </div>
    </DialogContent>
  </DialogRoot>
</template>
```

> **Plan 1 范围内知识库/文档视图可以先用占位**，在 Plan 1 尾部或单独的 polish task 里接入真实数据。核心交互（抽屉打开/tab 切换）先到位。

- [ ] **Step 4: 跑测试通过**

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(web): 新增项目资料抽屉（知识库/文档 tab 骨架）"
```

---

## Task 22: `ProjectSettingsPanel` 项目设置抽屉

**Files:**
- Create: `zhiwei-web/src/components/project/ProjectSettingsPanel.vue`
- Test: `zhiwei-web/src/components/project/__tests__/ProjectSettingsPanel.spec.ts`

- [ ] **Step 1: 写失败测试**

```typescript
import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import ProjectSettingsPanel from '../ProjectSettingsPanel.vue'

describe('ProjectSettingsPanel', () => {
  const project = {
    id: 'p-1', name: '论文', instructions: '严谨', isolation: 'ISOLATED' as const,
    memorySpaceId: 'ms-1', createdAt: '', updatedAt: ''
  }

  it('展示当前项目信息', () => {
    const wrapper = mount(ProjectSettingsPanel, {
      props: { open: true, project },
      global: { plugins: [createTestingPinia()] }
    })
    expect((wrapper.find('[data-testid="edit-name"]').element as HTMLInputElement).value).toBe('论文')
  })

  it('点击删除触发确认再调 store.deleteProject', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const wrapper = mount(ProjectSettingsPanel, {
      props: { open: true, project },
      global: { plugins: [createTestingPinia({ createSpy: vi.fn })] }
    })
    await wrapper.find('[data-testid="delete-btn"]').trigger('click')
    expect(confirm).toHaveBeenCalled()
    // verify store.deleteProject called with 'p-1'
  })
})
```

- [ ] **Step 2: 跑测试验证失败**

- [ ] **Step 3: 实现**

```vue
<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { DialogRoot, DialogOverlay, DialogContent, DialogTitle, DialogClose } from 'reka-ui'
import { XIcon } from 'lucide-vue-next'
import type { ProjectDto } from '@/api/project'
import { useProjectStore } from '@/stores/project'

const props = defineProps<{ open: boolean; project: ProjectDto }>()
const emit = defineEmits<{ 'update:open': [value: boolean] }>()

const store = useProjectStore()
const router = useRouter()

const name = ref(props.project.name)
const instructions = ref(props.project.instructions)
const isolation = ref(props.project.isolation)

watch(() => props.project, (p) => {
  name.value = p.name
  instructions.value = p.instructions
  isolation.value = p.isolation
})

async function save() {
  await store.updateProject(props.project.id, {
    name: name.value.trim(),
    instructions: instructions.value,
    isolation: isolation.value
  })
  emit('update:open', false)
}

async function remove() {
  if (!window.confirm(`确认删除项目「${props.project.name}」及其所有对话？此操作不可撤销。`)) return
  await store.deleteProject(props.project.id)
  emit('update:open', false)
  router.push('/home')
}
</script>

<template>
  <DialogRoot :open="open" @update:open="emit('update:open', $event)">
    <DialogOverlay class="fixed inset-0 bg-black/40" />
    <DialogContent class="fixed right-0 top-0 bottom-0 w-[480px] bg-card shadow-xl
                          flex flex-col">
      <header class="flex items-center justify-between px-xl py-md border-b">
        <DialogTitle class="text-lg font-semibold">项目设置</DialogTitle>
        <DialogClose class="p-xs rounded hover:bg-accent"><XIcon class="size-md" /></DialogClose>
      </header>

      <div class="flex-1 overflow-auto p-xl flex flex-col gap-md">
        <div>
          <label class="text-md">项目名称</label>
          <input
            data-testid="edit-name"
            v-model="name" maxlength="64"
            class="w-full mt-xs px-md py-xs border rounded-md"
          />
        </div>

        <div>
          <label class="text-md">指示词</label>
          <textarea
            v-model="instructions" maxlength="1000" rows="4"
            class="w-full mt-xs px-md py-xs border rounded-md resize-none"
          />
        </div>

        <div>
          <div class="text-md mb-xs">记忆</div>
          <label class="flex items-start gap-sm mb-xs cursor-pointer">
            <input type="radio" value="SHARED" v-model="isolation" class="mt-xs" />
            <div>
              <div class="text-md">不隔离</div>
              <div class="text-xs text-muted-foreground">双向读写</div>
            </div>
          </label>
          <label class="flex items-start gap-sm cursor-pointer">
            <input type="radio" value="ISOLATED" v-model="isolation" class="mt-xs" />
            <div>
              <div class="text-md">隔离</div>
              <div class="text-xs text-muted-foreground">只读主账户，产出不外泄</div>
            </div>
          </label>
        </div>
      </div>

      <footer class="flex justify-between px-xl py-md border-t">
        <button
          data-testid="delete-btn"
          class="px-md py-xs rounded-md text-destructive hover:bg-destructive/10"
          @click="remove"
        >删除项目</button>
        <button
          class="px-md py-xs rounded-md bg-primary text-primary-foreground"
          @click="save"
        >保存</button>
      </footer>
    </DialogContent>
  </DialogRoot>
</template>
```

- [ ] **Step 4: 跑测试通过**

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(web): 新增项目设置抽屉（改名/指示词/隔离模式/删除）"
```

---

## Task 23: 新建对话支持 `projectId` 参数

**Files:**
- Modify: 前端新建对话入口（ChatView 或 home redirect）

- [ ] **Step 1: 定位**

```bash
grep -rn "newConversation" zhiwei-web/src/
```

找到 `home` redirect 到 `newConversation` 的逻辑，以及 newConversation 如何 POST 后端建对话。

- [ ] **Step 2: 写失败测试 / 直接 patch**

本 task 简化为：直接让新建对话的请求携带 `route.query.projectId`。

- [ ] **Step 3: 修改**

在新建对话的调用点：

```typescript
const projectId = route.query.projectId as string | undefined
const conv = await conversationApi.create({ /* existing fields, */ projectId })
```

后端 Controller / request DTO 已在 Task 12 支持。

- [ ] **Step 4: 手动冒烟**

1. 侧栏新建项目"测试项目"
2. 进入项目详情页，点"开始新对话"
3. 聊几句
4. 回主界面"所有对话"分组，看不到刚才那条对话（应只在项目下可见）
5. 侧栏点击"测试项目"，看到该对话

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(web): 新建对话继承当前项目上下文"
```

---

## Task 24: 端到端集成测试 —— 项目生命周期

**Files:**
- Create: `src/test/java/com/lifepilot/project/ProjectLifecycle_集成测试.java`

- [ ] **Step 1: 写测试**

```java
package com.lifepilot.project;

import com.lifepilot.project.model.ProjectIsolation;
import com.lifepilot.project.service.ProjectService;
import com.lifepilot.memory.scope.MemorySpaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ProjectLifecycle_集成测试 {

    @Autowired ProjectService projectService;
    @Autowired MemorySpaceRepository memorySpaceRepository;

    @Test
    @Transactional
    void 建项目_查项目_删项目_MemorySpace级联清理() {
        var p = projectService.createProject("集成测试项目", "", ProjectIsolation.ISOLATED);
        assertNotNull(memorySpaceRepository.findById(p.memorySpaceId()).orElse(null));

        projectService.deleteProject(p.id());

        assertFalse(projectService.getProject(p.id()).isPresent());
        assertFalse(memorySpaceRepository.findById(p.memorySpaceId()).isPresent());
    }

    @Test
    @Transactional
    void 重名项目创建失败() {
        projectService.createProject("重名测试", "", ProjectIsolation.ISOLATED);
        assertThrows(IllegalArgumentException.class, () ->
                projectService.createProject("重名测试", "", ProjectIsolation.ISOLATED));
    }
}
```

- [ ] **Step 2: 跑测试验证**

```bash
mvn -q test -Dtest="ProjectLifecycle_集成测试"
```

Expected: PASS (2 tests)

- [ ] **Step 3: （无需新实现）**

- [ ] **Step 4: 手动端到端冒烟**

```bash
mvn -q spring-boot:run &
cd zhiwei-web && npm run dev &
sleep 30
# 浏览器打开 http://localhost:5173
# 1. 侧栏点"新建项目"，建"冒烟项目"
# 2. 进入项目详情页，"开始新对话"
# 3. 聊一句"你好"
# 4. 打开 ProjectSettingsPanel，点删除
# 5. 确认对话消失、侧栏项目消失
```

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/lifepilot/project/ProjectLifecycle_集成测试.java
git commit -m "test(project): 新增项目生命周期端到端集成测试"
```

---

## Task 25: 隔离语义端到端集成测试

**Files:**
- Create: `src/test/java/com/lifepilot/project/ProjectIsolation_端到端测试.java`

- [ ] **Step 1: 写测试**

```java
package com.lifepilot.project;

import com.lifepilot.memory.scope.*;
import com.lifepilot.project.context.*;
import com.lifepilot.project.model.ProjectIsolation;
import com.lifepilot.project.service.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ProjectIsolation_端到端测试 {

    @Autowired ProjectService projectService;
    @Autowired ProjectContextResolver resolver;
    @Autowired MemorySpaceRepository spaceRepo;

    @Test
    @Transactional
    void 隔离项目的_ProjectContext_isolated为true_filter包含项目space和主账户space() {
        var p = projectService.createProject("隔离测试", "", ProjectIsolation.ISOLATED);
        var ctx = resolver.resolve(p.id());
        assertTrue(ctx.isolated());

        MemoryReadFilter filter = MemoryReadFilter.buildForProject(
                ctx.projectSpaceId(), ctx.personalSpaceId(),
                ctx.experienceSpaceId(), ctx.isolated());
        assertEquals(3, filter.spaceIds().size());
        assertTrue(filter.spaceIds().contains(ctx.projectSpaceId()));
        assertTrue(filter.spaceIds().contains(ctx.personalSpaceId()));
    }

    @Test
    @Transactional
    void 不隔离项目_filter不包含项目space() {
        var p = projectService.createProject("共享测试", "", ProjectIsolation.SHARED);
        var ctx = resolver.resolve(p.id());
        assertFalse(ctx.isolated());

        MemoryReadFilter filter = MemoryReadFilter.buildForProject(
                ctx.projectSpaceId(), ctx.personalSpaceId(),
                ctx.experienceSpaceId(), ctx.isolated());
        Set<String> ids = filter.spaceIds();
        assertFalse(ids.contains(ctx.projectSpaceId()));
        assertTrue(ids.contains(ctx.personalSpaceId()));
    }

    @Test
    @Transactional
    void 主账户对话_ProjectContext_projectId为null_filter只含主账户space() {
        var ctx = resolver.resolve(null);
        assertNull(ctx.projectId());
        assertNull(ctx.projectSpaceId());

        MemoryReadFilter filter = MemoryReadFilter.buildForProject(
                null, ctx.personalSpaceId(), ctx.experienceSpaceId(), false);
        assertEquals(2, filter.spaceIds().size());
    }
}
```

- [ ] **Step 2: 跑测试**

```bash
mvn -q test -Dtest="ProjectIsolation_端到端测试"
```

Expected: PASS (3 tests)

- [ ] **Step 3: （无需新实现）**

- [ ] **Step 4: 最终全量测试**

```bash
mvn -q test
cd zhiwei-web && npm run test:run
```

Expected: 全绿

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/lifepilot/project/ProjectIsolation_端到端测试.java
git commit -m "test(project): 新增隔离语义端到端测试"
```

---

## 验收（最终核对）

Plan 1 完成后应满足设计文档（`docs/superpowers/specs/2026-04-22-project-workspace-design.md`）第 12 节验收标准的以下子集：

- [x] 1. 用户可在侧栏建项目、重命名、删除 ✓（Task 18/19/22）
- [x] 2. 项目内发起的对话打上 `project_id` ✓（Task 11/12/23）
- [ ] 3-4. 记忆读写按 scope 路由（**Plan 1 完成 filter 骨架和写入路由，具体 retriever/writer 的接入覆盖见 Task 14/15；完整收敛放 Plan 1.5 / 后续 plan**）
- [ ] 5-6. L3/L4 在主账户/项目的精细同键覆盖（**留给后续 plan**）
- [x] 11. 删除项目级联清理（Task 16/24） ✓

**Plan 1 输出**：一个可用的"项目组织维度 + 基础 scope 隔离"闭环，用户能建项目、对话归属正确、MemorySpace 自动联动、删除级联清理。

**Plan 1 已知 gap**（留给下个 plan 处理）：
1. L3 用户偏好 / L4 程序记忆的 key-level override（当前方案 B 已写入设计但 Plan 1 未实现）
2. 知识库通过 `memory_space_knowledge_bases` 的 project scope 查询完整接入 RAG（Plan 1 占位，Plan 2 完整实现）
3. 项目资料抽屉里的真实知识库/文档列表数据（Plan 1 占位 UI）

---

## Out of Scope（明确不做）

- 定时任务全局入口 UI（→ Plan 2）
- 后台进程项目级聚合（不做，维持会话级）
- Datastore 用户侧下架（→ Plan 3）
- 场景模板 / 微微主动提议 / 归档 / 跨项目搜索
- 对话跨项目迁移

---

## Execution Handoff

Plan 1 写完。两种执行方式：

1. **Subagent-Driven（推荐）**：每个 task 派一个 fresh subagent（opus 4.7），做完 code review 再前进。适合 Plan 1 这种涉及多模块的大计划。
2. **Inline Execution**：在当前 session 按 `executing-plans` skill 批量执行，checkpoint review。

选哪种继续？
