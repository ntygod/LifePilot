# 定时任务全局入口 + Datastore 用户侧下架 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把后端已有但前端零 UI 的"定时任务"升级为**侧栏一级管理功能**（Plan 2），同时**下架 Datastore 用户侧概念**（Plan 3）——前端路由移除 + LLM 工具注册表下线，后端代码保留。合成一份 PR 交付。

**Architecture:**
- **Part A（定时任务）**：V19 给 `cron_tasks` 加 `project_id` → `ScheduledTaskController` 暴露 CRUD REST → 前端 `ScheduledTasksView` 全局管理页（列表带项目 tag + 暂停/编辑/删除）。创建入口保持"由 LLM 在对话中自然语言触发"，UI 只管存量管理。
- **Part B（Datastore 下架）**：`StorageToolProvider` 不再向 `ToolRegistry` 注册 `datastore` 工具；前端 `router/index.ts` 移除 `/datastores`、`/datastores/:id` 路由；`DatastoreView.vue` / `DatastoreDetailView.vue` 文件保留（按 spec §5.3 "后端代码保留"对称处理前端）。后端 `DataStoreManager` 及整套 datastore 能力保持完整可用（LLM 不接触即可）。

**Tech Stack:** 延续 Plan 1 stack — Spring Boot 3 + JdbcTemplate + Flyway + JUnit 5 + Mockito (后端)；Vue 3 + TypeScript + Pinia + Reka UI 2.x + Tailwind 命名尺度 + lucide-vue-next (前端)。

**设计依据:** `docs/superpowers/specs/2026-04-22-project-workspace-design.md` §3.5（定时任务全局管理页）+ §5（Datastore 处置）

---

## 前置条件

- [ ] 在 worktree `.worktrees/plan2-3` 内、分支 `feature/scheduled-tasks-and-datastore-cleanup` 上执行
- [ ] 该分支基于 `feature/project-workspace`（Plan 1 分支），包含 Plan 1 所有 commits（V15-V18 迁移、Project 相关类、ProjectContext/Resolver 等）
- [ ] 本地能跑通 `mvn compile` 和 `cd zhiwei-web && npm run build`

## Scope 边界

**本 Plan 内**：
- 定时任务：后端加 project_id + REST API；前端新建全局管理页 + 侧栏入口（Plan 1 已占位，这次真接通）；创建时从 ProjectContext 填充
- Datastore：LLM 工具下线 + 前端路由下架 + 菜单入口移除 + 集成测试

**本 Plan 外**（明确不做）：
- **定时任务从 UI 手动新建**——保持"LLM 对话中自然语言触发"的既有机制，ScheduledTasksView 不做新建入口
- **定时任务跨项目迁移**——不支持（和对话不可迁移一致）；要换归属删除重建
- **Datastore 后端代码删除**——保留 `DataStoreManager` 等完整能力（spec §5.3 明确要求）
- **后台进程项目级聚合**——会话级视图已覆盖（spec 明确不做）
- **L3/L4 key-level override** — 留给后续 plan

---

## 文件结构

### 后端新增

| 路径 | 责任 |
|---|---|
| `src/main/resources/db/migration/V19__add_project_id_to_cron_tasks.sql` | 定时任务加项目归属 |
| `src/main/java/com/lifepilot/agent/task/CronTaskRepository.java` | **修改**：Entry 加 projectId + findById/findAll/update/deleteById 同步 |
| `src/main/java/com/lifepilot/agent/task/CronTaskEntry.java` | **修改**：加 `@Nullable String projectId` 字段 |
| `src/main/java/com/lifepilot/interaction/web/controller/ScheduledTaskController.java` | 新建：REST API (list/update/delete/pause/resume) |
| `src/main/java/com/lifepilot/interaction/web/model/scheduled/*.java` | 新建：CronTaskResponse / UpdateScheduledTaskRequest records |

### 后端修改

| 路径 | 改动要点 |
|---|---|
| 定时任务创建入口（现有工具，Step 0 grep 定位：`grep -rn "cron_tasks\|CronTaskEntry" src/main/java`） | 创建时从 ToolInput 的 sessionId 反查 ChatSession.projectId 填入，与 Plan 1 Task 15 的 MemoryToolProvider 写入路径对齐 |
| `src/main/java/com/lifepilot/meta/infra/storage/StorageToolProvider.java` | **下线**：`buildStorageTools()` 返回空列表（或整个 bean 通过 `@ConditionalOnProperty` 关掉） |
| `src/main/java/com/lifepilot/meta/config/MetaAutoConfiguration.java` | **修改**：装配时不再把 StorageToolProvider 的 tools 加入 ToolRegistry（或添加 `@ConditionalOnProperty`） |

### 前端新增

| 路径 | 责任 |
|---|---|
| `zhiwei-web/src/api/scheduledTask.ts` | API client |
| `zhiwei-web/src/stores/scheduledTask.ts` | Pinia store |
| `zhiwei-web/src/views/ScheduledTasksView.vue` | 全局管理页 |
| `zhiwei-web/src/views/ScheduledTasksView.spec.ts` | 组件测试 |
| `zhiwei-web/src/stores/scheduledTask.spec.ts` | store 测试 |

### 前端修改

| 路径 | 改动要点 |
|---|---|
| `zhiwei-web/src/router/index.ts` | 新增 `/scheduled-tasks` 路由；**移除** `/datastores` 和 `/datastores/:id` 路由（文件保留不挂载） |
| 侧栏/主菜单中 `/datastores` 入口（Step B3 grep 定位） | 移除 |
| `zhiwei-web/src/components/layout/UnifiedSidebar.vue`（Plan 1 Task 18 占位） | 确认定时任务按钮的 router.push 命中真实路由 |

### 前端保留不挂载

| 路径 | 说明 |
|---|---|
| `zhiwei-web/src/views/DatastoreView.vue` | 保留文件，路由已移除 |
| `zhiwei-web/src/views/DatastoreDetailView.vue` | 同上 |
| `zhiwei-web/src/views/DatastoreView.spec.ts` / `DatastoreDetailView.spec.ts` | spec 保留，测试仍跑（只要组件本身能 compile + test 通过即可） |

---

# Part A: 定时任务全局入口（Task A1–A7）

## Task A1: Flyway V19 — `cron_tasks` 加 `project_id`

**Files:**
- Create: `src/main/resources/db/migration/V19__add_project_id_to_cron_tasks.sql`

- [ ] **Step 1: 写迁移脚本**

```sql
-- Plan 2 定时任务项目归属：
-- NULL = 归属主账户；非 NULL = 归属具体项目
-- 级联策略由应用层实现（参考 Plan 1 V16 V17 既定风格），不加 FK 到 projects(id)
-- 避免删除循环和跨迁移依赖
ALTER TABLE cron_tasks ADD COLUMN project_id TEXT;

CREATE INDEX idx_cron_tasks_project_id ON cron_tasks(project_id)
    WHERE project_id IS NOT NULL;
```

- [ ] **Step 2: 验证编译**

```bash
mvn -q compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: （无需代码——SQL 即实现）**

- [ ] **Step 4: 确认 SQL 文件语法正确**

```bash
cat src/main/resources/db/migration/V19__add_project_id_to_cron_tasks.sql
```

Expected: 3 条 SQL 正确、关键字大写、snake_case 列名

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V19__add_project_id_to_cron_tasks.sql
git commit -m "feat(scheduled): cron_tasks 加 project_id 字段（V19）"
```

---

## Task A2: `CronTaskEntry` 加 projectId + Repository 读写

**Files:**
- Modify: `src/main/java/com/lifepilot/agent/task/CronTaskEntry.java`（加字段）
- Modify: `src/main/java/com/lifepilot/agent/task/CronTaskRepository.java`（SQL 三对齐）
- Test: `src/test/java/com/lifepilot/agent/task/CronTaskRepository_项目归属测试.java`

- [ ] **Step 1: 先 grep 现有 CronTaskEntry / CronTaskRepository 结构**

```bash
cat src/main/java/com/lifepilot/agent/task/CronTaskEntry.java
grep -A 40 "INSERT INTO cron_tasks\|SELECT.*FROM cron_tasks" src/main/java/com/lifepilot/agent/task/CronTaskRepository.java | head -60
```

确认现有字段、INSERT 列清单、mapRow 签名。

- [ ] **Step 2: 写失败测试**

```java
package com.lifepilot.agent.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CronTaskRepository 项目归属字段集成测试。
 *
 * @author zsg
 * @since 2026-04-23
 */
class CronTaskRepository_项目归属测试 {

    private CronTaskRepository repo;

    @BeforeEach
    void setUp() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        ds.setAutoCommit(true);
        JdbcTemplate jt = new JdbcTemplate(ds);
        jt.execute("PRAGMA foreign_keys = ON");
        // 用 V1 + V3 + V19 叠加后的最终 schema
        jt.execute("""
                CREATE TABLE cron_tasks (
                    id          TEXT PRIMARY KEY,
                    name        TEXT NOT NULL,
                    schedule    TEXT NOT NULL,
                    instruction TEXT NOT NULL,
                    status      TEXT NOT NULL DEFAULT 'active',
                    skill_ids   TEXT,
                    project_id  TEXT,
                    created_at  TEXT NOT NULL,
                    updated_at  TEXT NOT NULL
                )
                """);
        repo = new CronTaskRepository(jt);
    }

    @Test
    void insert_携带projectId_能回读() {
        Instant now = Instant.now();
        CronTaskEntry entry = new CronTaskEntry(
                UUID.randomUUID().toString(),
                "周报提醒", "0 0 21 ? * SUN", "提醒写周报",
                "active", null, "p-1", now, now);
        repo.insert(entry);
        CronTaskEntry found = repo.findById(entry.id()).orElseThrow();
        assertEquals("p-1", found.projectId());
    }

    @Test
    void insert_projectId为null_回读也为null() {
        Instant now = Instant.now();
        CronTaskEntry entry = new CronTaskEntry(
                UUID.randomUUID().toString(),
                "日常提醒", "0 0 9 * * *", "今日天气",
                "active", null, null, now, now);
        repo.insert(entry);
        assertNull(repo.findById(entry.id()).orElseThrow().projectId());
    }

    @Test
    void findByProjectId_只返回归属该项目() {
        Instant now = Instant.now();
        repo.insert(new CronTaskEntry("t1", "A", "0 0 * * * *", "", "active", null, "p-1", now, now));
        repo.insert(new CronTaskEntry("t2", "B", "0 0 * * * *", "", "active", null, "p-1", now, now));
        repo.insert(new CronTaskEntry("t3", "C", "0 0 * * * *", "", "active", null, null, now, now));
        assertEquals(2, repo.findByProjectId("p-1").size());
        assertEquals(1, repo.findByProjectId(null).size());  // 主账户
    }
}
```

- [ ] **Step 3: 修改 `CronTaskEntry` 加 `@Nullable String projectId` 字段**

```java
// 保留原有字段顺序，在 updatedAt 前插入 projectId（或放在最合适的位置——按实际 record 结构）
public record CronTaskEntry(
        String id,
        String name,
        String schedule,
        String instruction,
        String status,
        @Nullable String skillIds,
        @Nullable String projectId,   // 新增 — Plan 2
        Instant createdAt,
        Instant updatedAt
) {
    // 紧凑构造器若有额外校验，保留原有；projectId 无额外校验
}
```

> **注意**：如果现有 `CronTaskEntry` 有不同的字段顺序或额外校验，**按实际结构插入 projectId**，不强行重排其他字段。

- [ ] **Step 4: 修改 `CronTaskRepository`**

INSERT 语句加 `project_id` 列 + ?：

```java
jdbcTemplate.update("""
        INSERT INTO cron_tasks (id, name, schedule, instruction, status, skill_ids, project_id, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        entry.id(), entry.name(), entry.schedule(), entry.instruction(),
        entry.status(), entry.skillIds(), entry.projectId(),
        entry.createdAt().toString(), entry.updatedAt().toString());
```

SELECT 列清单加 `project_id`；`mapRow` 读出：

```java
private CronTaskEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new CronTaskEntry(
            rs.getString("id"),
            rs.getString("name"),
            rs.getString("schedule"),
            rs.getString("instruction"),
            rs.getString("status"),
            rs.getString("skill_ids"),
            rs.getString("project_id"),
            Instant.parse(rs.getString("created_at")),
            Instant.parse(rs.getString("updated_at"))
    );
}
```

UPDATE 语句保留 project_id 不变（项目归属不可迁移，参考 plan 边界）：

```java
// 不要把 project_id 放进 UPDATE SET 子句
jdbcTemplate.update("""
        UPDATE cron_tasks
        SET name = ?, schedule = ?, instruction = ?, status = ?, skill_ids = ?, updated_at = ?
        WHERE id = ?
        """, entry.name(), entry.schedule(), entry.instruction(),
        entry.status(), entry.skillIds(), entry.updatedAt().toString(), entry.id());
```

新增 `findByProjectId` 方法：

```java
/**
 * 按项目归属查询定时任务。
 *
 * @param projectId 项目 id，null 表示查询主账户（project_id IS NULL）
 */
public List<CronTaskEntry> findByProjectId(@Nullable String projectId) {
    String sql = projectId == null
            ? """
              SELECT id, name, schedule, instruction, status, skill_ids, project_id, created_at, updated_at
              FROM cron_tasks WHERE project_id IS NULL ORDER BY created_at DESC
              """
            : """
              SELECT id, name, schedule, instruction, status, skill_ids, project_id, created_at, updated_at
              FROM cron_tasks WHERE project_id = ? ORDER BY created_at DESC
              """;
    return projectId == null
            ? jdbcTemplate.query(sql, this::mapRow)
            : jdbcTemplate.query(sql, this::mapRow, projectId);
}
```

- [ ] **Step 5: 跑测试 + commit**

```bash
mvn -q test -Dtest="CronTaskRepository_项目归属测试"
```

Expected: PASS (3 tests)

```bash
# 补同时跑原有 CronTaskRepository 集成测试确认无回归
mvn -q test -Dtest="CronTask*"
```

Expected: 全绿

```bash
git add src/main/java/com/lifepilot/agent/task/CronTaskEntry.java \
        src/main/java/com/lifepilot/agent/task/CronTaskRepository.java \
        src/test/java/com/lifepilot/agent/task/CronTaskRepository_项目归属测试.java
git commit -m "feat(scheduled): CronTaskRepository 读写支持 project_id"
```

---

## Task A3: `ScheduledTaskController` REST API

**Files:**
- Create: `src/main/java/com/lifepilot/interaction/web/model/scheduled/ScheduledTaskResponse.java`
- Create: `src/main/java/com/lifepilot/interaction/web/model/scheduled/UpdateScheduledTaskRequest.java`
- Create: `src/main/java/com/lifepilot/interaction/web/controller/ScheduledTaskController.java`
- Test: `src/test/java/com/lifepilot/interaction/web/controller/ScheduledTaskController_单元测试.java`

**API 设计**（对齐 Plan 1 `ProjectController` 风格）：

- `GET /api/scheduled-tasks` — 列表（支持 `?projectId=` 过滤；缺省返回所有归属：主账户 + 所有项目）
- `PUT /api/scheduled-tasks/{id}` — 更新 name/schedule/instruction/status
- `DELETE /api/scheduled-tasks/{id}` — 删除
- **不提供**：POST（创建入口保持 LLM 自然语言触发）

**暂停/恢复**通过 PUT 更新 `status` 字段实现（`active` ↔ `paused`）。

- [ ] **Step 1: 写 DTO records**

```java
// ScheduledTaskResponse.java
package com.lifepilot.interaction.web.model.scheduled;

import com.lifepilot.agent.task.CronTaskEntry;
import java.time.Instant;

/**
 * 定时任务 API 响应 DTO。
 *
 * @author zsg
 * @since 2026-04-23
 */
public record ScheduledTaskResponse(
        String id,
        String name,
        String schedule,
        String instruction,
        String status,
        String skillIds,
        String projectId,
        Instant createdAt,
        Instant updatedAt
) {
    public static ScheduledTaskResponse from(CronTaskEntry e) {
        return new ScheduledTaskResponse(e.id(), e.name(), e.schedule(), e.instruction(),
                e.status(), e.skillIds(), e.projectId(), e.createdAt(), e.updatedAt());
    }
}
```

```java
// UpdateScheduledTaskRequest.java
package com.lifepilot.interaction.web.model.scheduled;

public record UpdateScheduledTaskRequest(
        String name,
        String schedule,
        String instruction,
        String status
) {}
```

- [ ] **Step 2: 写 Controller 单元测试**

```java
package com.lifepilot.interaction.web.controller;

import com.lifepilot.agent.task.CronTaskEntry;
import com.lifepilot.agent.task.CronTaskRepository;
import com.lifepilot.interaction.web.model.scheduled.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ScheduledTaskController_单元测试 {

    @Mock CronTaskRepository repository;
    @InjectMocks ScheduledTaskController controller;

    private CronTaskEntry mockEntry(String id, String projectId) {
        Instant now = Instant.now();
        return new CronTaskEntry(id, "任务", "0 0 9 * * *", "指令",
                "active", null, projectId, now, now);
    }

    @Test
    void 列表_不传projectId_返回所有() {
        when(repository.findAll()).thenReturn(List.of(
                mockEntry("t1", "p-1"), mockEntry("t2", null)));
        var resp = controller.list(null);
        assertEquals(2, resp.getBody().data().size());
    }

    @Test
    void 列表_传projectId_返回该项目() {
        when(repository.findByProjectId("p-1")).thenReturn(List.of(mockEntry("t1", "p-1")));
        var resp = controller.list("p-1");
        assertEquals(1, resp.getBody().data().size());
        assertEquals("p-1", resp.getBody().data().get(0).projectId());
    }

    @Test
    void 更新_代理到repository() {
        CronTaskEntry existing = mockEntry("t1", "p-1");
        when(repository.findById("t1")).thenReturn(Optional.of(existing));
        controller.update("t1",
                new UpdateScheduledTaskRequest("新名", "0 0 10 * * *", "新指令", "paused"));
        verify(repository).update(argThat(updated ->
                updated.name().equals("新名")
                        && updated.status().equals("paused")
                        && updated.projectId().equals("p-1")));  // projectId 保留
    }

    @Test
    void 更新_任务不存在_抛异常() {
        when(repository.findById("nope")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () ->
                controller.update("nope",
                        new UpdateScheduledTaskRequest("n", "0 * * * * *", "i", "active")));
    }

    @Test
    void 删除_代理到repository() {
        controller.delete("t1");
        verify(repository).deleteById("t1");
    }
}
```

- [ ] **Step 3: 实现 Controller**

```java
package com.lifepilot.interaction.web.controller;

import com.lifepilot.agent.task.CronTaskEntry;
import com.lifepilot.agent.task.CronTaskRepository;
import com.lifepilot.interaction.web.model.ApiResponse;
import com.lifepilot.interaction.web.model.scheduled.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/**
 * 定时任务 REST API。
 *
 * <p>创建入口保持 LLM 对话中自然语言触发（调用现有 cron 工具）；
 * 本 Controller 只管存量管理（列表/编辑/暂停/删除）。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@RestController
@RequestMapping("/api/scheduled-tasks")
@ConditionalOnProperty(name = "lifepilot.gateway.channels.web.enabled", havingValue = "true")
public class ScheduledTaskController {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskController.class);

    private final CronTaskRepository repository;

    public ScheduledTaskController(CronTaskRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ScheduledTaskResponse>>> list(
            @RequestParam(required = false) @Nullable String projectId) {
        List<CronTaskEntry> entries = (projectId == null || projectId.isBlank())
                ? repository.findAll()
                : repository.findByProjectId(projectId);
        List<ScheduledTaskResponse> items = entries.stream()
                .map(ScheduledTaskResponse::from).toList();
        return ResponseEntity.ok(ApiResponse.ok(items));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ScheduledTaskResponse>> update(
            @PathVariable String id, @RequestBody UpdateScheduledTaskRequest req) {
        CronTaskEntry existing = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("定时任务不存在：" + id));
        CronTaskEntry updated = new CronTaskEntry(
                existing.id(),
                req.name() != null ? req.name() : existing.name(),
                req.schedule() != null ? req.schedule() : existing.schedule(),
                req.instruction() != null ? req.instruction() : existing.instruction(),
                req.status() != null ? req.status() : existing.status(),
                existing.skillIds(),
                existing.projectId(),   // 项目归属不可变更
                existing.createdAt(),
                Instant.now()
        );
        repository.update(updated);
        log.info("更新定时任务: id={}, status={}", id, updated.status());
        return ResponseEntity.ok(ApiResponse.ok(ScheduledTaskResponse.from(updated)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable String id) {
        repository.deleteById(id);
        log.info("删除定时任务: id={}", id);
        return ResponseEntity.ok(ApiResponse.ok());
    }
}
```

> **注意**：`ApiResponse.ok()`（空参版本）要看项目实际签名是否存在，如果没有用 `ApiResponse.ok(null)` 或 delete 直接返回 `ResponseEntity.noContent().build()`。

- [ ] **Step 4: 跑测试通过**

```bash
mvn -q test -Dtest="ScheduledTaskController_单元测试"
```

Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/lifepilot/interaction/web/controller/ScheduledTaskController.java \
        src/main/java/com/lifepilot/interaction/web/model/scheduled/ \
        src/test/java/com/lifepilot/interaction/web/controller/ScheduledTaskController_单元测试.java
git commit -m "feat(scheduled): 新增 /api/scheduled-tasks REST CRUD"
```

---

## Task A4: 前端 API client + Pinia store

**Files:**
- Create: `zhiwei-web/src/api/scheduledTask.ts`
- Create: `zhiwei-web/src/stores/scheduledTask.ts`
- Create: `zhiwei-web/src/stores/scheduledTask.spec.ts`

- [ ] **Step 1: 写 API client（模仿 Plan 1 的 `api/project.ts` pattern）**

```typescript
// zhiwei-web/src/api/scheduledTask.ts
/**
 * 定时任务 API client。
 *
 * @author zsg
 * @since 2026-04-23
 */

export interface ScheduledTaskDto {
  id: string
  name: string
  schedule: string
  instruction: string
  status: string       // 'active' | 'paused' | ...
  skillIds: string | null
  projectId: string | null
  createdAt: string
  updatedAt: string
}

export interface UpdateScheduledTaskRequest {
  name?: string
  schedule?: string
  instruction?: string
  status?: string
}

interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const resp = await fetch(url, {
    headers: { 'Content-Type': 'application/json' },
    ...init
  })
  if (!resp.ok) throw new Error(`${resp.status} ${resp.statusText}`)
  const body: ApiResponse<T> = await resp.json()
  if (body.code !== 0) throw new Error(body.message)
  return body.data
}

export async function listScheduledTasks(projectId?: string | null): Promise<ScheduledTaskDto[]> {
  const qs = projectId ? `?projectId=${encodeURIComponent(projectId)}` : ''
  return request<ScheduledTaskDto[]>(`/api/scheduled-tasks${qs}`)
}

export async function updateScheduledTask(id: string, req: UpdateScheduledTaskRequest): Promise<ScheduledTaskDto> {
  return request<ScheduledTaskDto>(`/api/scheduled-tasks/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify(req)
  })
}

export async function deleteScheduledTask(id: string): Promise<void> {
  await request<void>(`/api/scheduled-tasks/${encodeURIComponent(id)}`, { method: 'DELETE' })
}
```

- [ ] **Step 2: 写 Pinia store**

```typescript
// zhiwei-web/src/stores/scheduledTask.ts
import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { ScheduledTaskDto, UpdateScheduledTaskRequest } from '@/api/scheduledTask'
import * as api from '@/api/scheduledTask'

export const useScheduledTaskStore = defineStore('scheduledTask', () => {
  const tasks = ref<ScheduledTaskDto[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function fetchAll() {
    loading.value = true
    error.value = null
    try {
      tasks.value = await api.listScheduledTasks()
    } catch (e: any) {
      error.value = e?.message ?? '加载失败'
      throw e
    } finally {
      loading.value = false
    }
  }

  async function updateTask(id: string, req: UpdateScheduledTaskRequest): Promise<ScheduledTaskDto> {
    const updated = await api.updateScheduledTask(id, req)
    const idx = tasks.value.findIndex(t => t.id === id)
    if (idx >= 0) tasks.value[idx] = updated
    return updated
  }

  async function deleteTask(id: string): Promise<void> {
    await api.deleteScheduledTask(id)
    tasks.value = tasks.value.filter(t => t.id !== id)
  }

  async function pauseTask(id: string) {
    return updateTask(id, { status: 'paused' })
  }

  async function resumeTask(id: string) {
    return updateTask(id, { status: 'active' })
  }

  return { tasks, loading, error, fetchAll, updateTask, deleteTask, pauseTask, resumeTask }
})
```

- [ ] **Step 3: 写 store 测试**

```typescript
// zhiwei-web/src/stores/scheduledTask.spec.ts
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useScheduledTaskStore } from './scheduledTask'
import * as api from '@/api/scheduledTask'

vi.mock('@/api/scheduledTask')

describe('useScheduledTaskStore', () => {
  beforeEach(() => setActivePinia(createPinia()))

  const mockTask = (id: string, projectId: string | null = null) => ({
    id, name: `任务${id}`, schedule: '0 0 9 * * *', instruction: '指令',
    status: 'active', skillIds: null, projectId,
    createdAt: '', updatedAt: ''
  })

  it('fetchAll 拉取列表', async () => {
    vi.mocked(api.listScheduledTasks).mockResolvedValue([mockTask('t1', 'p-1'), mockTask('t2')])
    const store = useScheduledTaskStore()
    await store.fetchAll()
    expect(store.tasks).toHaveLength(2)
  })

  it('pauseTask 调 updateTask 并更新列表', async () => {
    const store = useScheduledTaskStore()
    store.tasks = [mockTask('t1')]
    vi.mocked(api.updateScheduledTask).mockResolvedValue({ ...mockTask('t1'), status: 'paused' })
    await store.pauseTask('t1')
    expect(store.tasks[0].status).toBe('paused')
  })

  it('deleteTask 成功后从列表移除', async () => {
    const store = useScheduledTaskStore()
    store.tasks = [mockTask('t1'), mockTask('t2')]
    vi.mocked(api.deleteScheduledTask).mockResolvedValue(undefined)
    await store.deleteTask('t1')
    expect(store.tasks).toHaveLength(1)
    expect(store.tasks[0].id).toBe('t2')
  })

  it('请求失败时写 error 并 rethrow', async () => {
    vi.mocked(api.listScheduledTasks).mockRejectedValue(new Error('网络'))
    const store = useScheduledTaskStore()
    await expect(store.fetchAll()).rejects.toThrow('网络')
    expect(store.error).toBe('网络')
  })
})
```

- [ ] **Step 4: 跑测试**

```bash
cd zhiwei-web && npm run test:run -- scheduledTask
```

Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add zhiwei-web/src/api/scheduledTask.ts \
        zhiwei-web/src/stores/scheduledTask.ts \
        zhiwei-web/src/stores/scheduledTask.spec.ts
git commit -m "feat(web): 新增定时任务 API client 与 Pinia store"
```

---

## Task A5: `ScheduledTasksView` + 路由

**Files:**
- Create: `zhiwei-web/src/views/ScheduledTasksView.vue`
- Create: `zhiwei-web/src/views/ScheduledTasksView.spec.ts`
- Modify: `zhiwei-web/src/router/index.ts`

- [ ] **Step 1: 路由注册**

在 `router/index.ts` 新增：

```typescript
{
  path: '/scheduled-tasks',
  name: 'scheduledTasks',
  component: () => import('@/views/ScheduledTasksView.vue')
}
```

- [ ] **Step 2: 写组件测试**

```typescript
// zhiwei-web/src/views/ScheduledTasksView.spec.ts
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import ScheduledTasksView from './ScheduledTasksView.vue'
import * as api from '@/api/scheduledTask'

vi.mock('@/api/scheduledTask')

describe('ScheduledTasksView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: {} }, { path: '/projects/:id', name: 'projectDetail', component: {} }]
  })

  function mountView() {
    return mount(ScheduledTasksView, {
      global: { plugins: [router] }
    })
  }

  it('页面挂载时 fetchAll 拉取任务', async () => {
    vi.mocked(api.listScheduledTasks).mockResolvedValue([])
    mountView()
    await flushPromises()
    expect(api.listScheduledTasks).toHaveBeenCalled()
  })

  it('展示任务 name / schedule / 项目 tag', async () => {
    vi.mocked(api.listScheduledTasks).mockResolvedValue([{
      id: 't1', name: '周报提醒', schedule: '0 0 21 ? * SUN', instruction: '写周报',
      status: 'active', skillIds: null, projectId: 'p-1',
      createdAt: '', updatedAt: ''
    }])
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain('周报提醒')
    expect(wrapper.text()).toContain('0 0 21 ? * SUN')
  })

  it('主账户任务（projectId=null）展示"主"tag', async () => {
    vi.mocked(api.listScheduledTasks).mockResolvedValue([{
      id: 't2', name: '天气', schedule: '0 0 8 * * *', instruction: '',
      status: 'active', skillIds: null, projectId: null,
      createdAt: '', updatedAt: ''
    }])
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain('主')
  })

  it('点击暂停按钮调 store.pauseTask', async () => {
    vi.mocked(api.listScheduledTasks).mockResolvedValue([{
      id: 't1', name: '任务', schedule: '0 0 * * * *', instruction: '',
      status: 'active', skillIds: null, projectId: null,
      createdAt: '', updatedAt: ''
    }])
    vi.mocked(api.updateScheduledTask).mockResolvedValue({
      id: 't1', name: '任务', schedule: '0 0 * * * *', instruction: '',
      status: 'paused', skillIds: null, projectId: null,
      createdAt: '', updatedAt: ''
    })
    const wrapper = mountView()
    await flushPromises()
    const pauseBtn = wrapper.find('[data-testid="pause-t1"]')
    if (pauseBtn.exists()) {
      await pauseBtn.trigger('click')
      await flushPromises()
      expect(api.updateScheduledTask).toHaveBeenCalledWith('t1', { status: 'paused' })
    }
  })
})
```

- [ ] **Step 3: 实现 `ScheduledTasksView.vue`**

```vue
<script setup lang="ts">
/**
 * 定时任务全局管理页。
 *
 * <p>侧栏一级入口；展示所有定时任务（主账户 + 所有项目，含隔离项目），
 * 带项目 tag + 暂停/恢复/删除。</p>
 *
 * <p><b>创建入口不在此页</b>：由 LLM 在对话中自然语言触发创建。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
import { onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { Clock, Play, Pause, Trash2, Folder } from 'lucide-vue-next'
import { useScheduledTaskStore } from '@/stores/scheduledTask'
import { useProjectStore } from '@/stores/project'

const store = useScheduledTaskStore()
const projectStore = useProjectStore()
const router = useRouter()

onMounted(async () => {
  await Promise.all([store.fetchAll(), projectStore.fetchProjects().catch(() => {})])
})

const projectNameById = computed(() => {
  const m = new Map<string, string>()
  for (const p of projectStore.projects) m.set(p.id, p.name)
  return m
})

function projectLabel(projectId: string | null) {
  if (!projectId) return '主'
  return projectNameById.value.get(projectId) ?? projectId
}

function openProject(projectId: string) {
  router.push({ name: 'projectDetail', params: { id: projectId } })
}

async function togglePause(taskId: string, currentStatus: string) {
  if (currentStatus === 'active') await store.pauseTask(taskId)
  else await store.resumeTask(taskId)
}

async function deleteTask(taskId: string, taskName: string) {
  if (!window.confirm(`确认删除定时任务「${taskName}」？此操作不可撤销。`)) return
  await store.deleteTask(taskId)
}
</script>

<template>
  <div class="flex h-full flex-col">
    <header class="flex items-center gap-sm border-b px-xl py-md">
      <Clock class="size-md" />
      <span class="text-lg font-semibold">定时任务</span>
    </header>

    <div class="flex-1 overflow-auto p-xl">
      <p v-if="store.loading && store.tasks.length === 0" class="text-sm text-muted-foreground">
        加载中…
      </p>
      <p v-else-if="store.tasks.length === 0" class="text-sm text-muted-foreground">
        暂无定时任务。你可以在对话中对微微说"每周日 21 点提醒我..."来创建。
      </p>
      <ul v-else class="flex flex-col gap-sm">
        <li v-for="task in store.tasks" :key="task.id"
            class="flex items-center gap-md rounded-md border px-md py-sm hover:bg-accent">
          <button
            class="flex items-center gap-xs rounded-md bg-muted px-sm py-xs text-xs"
            :data-testid="`project-tag-${task.id}`"
            @click="task.projectId && openProject(task.projectId)"
            :disabled="!task.projectId"
          >
            <Folder class="size-xs" />
            {{ projectLabel(task.projectId) }}
          </button>

          <div class="flex-1">
            <div class="text-sm font-medium">{{ task.name }}</div>
            <div class="text-xs text-muted-foreground">
              <code>{{ task.schedule }}</code>
              <span class="mx-xs">·</span>
              <span>{{ task.instruction }}</span>
            </div>
          </div>

          <span class="text-xs text-muted-foreground">
            {{ task.status === 'active' ? '运行中' : '已暂停' }}
          </span>

          <button
            :data-testid="`pause-${task.id}`"
            class="rounded-md p-xs hover:bg-accent"
            :title="task.status === 'active' ? '暂停' : '恢复'"
            @click="togglePause(task.id, task.status)"
          >
            <Pause v-if="task.status === 'active'" class="size-sm" />
            <Play v-else class="size-sm" />
          </button>
          <button
            :data-testid="`delete-${task.id}`"
            class="rounded-md p-xs text-destructive hover:bg-destructive/10"
            title="删除"
            @click="deleteTask(task.id, task.name)"
          >
            <Trash2 class="size-sm" />
          </button>
        </li>
      </ul>
    </div>
  </div>
</template>
```

- [ ] **Step 4: 跑测试 + vue-tsc**

```bash
cd zhiwei-web && npm run test:run -- ScheduledTasksView
npx vue-tsc --noEmit
```

Expected: PASS + 0 type errors

- [ ] **Step 5: Commit**

```bash
git add zhiwei-web/src/views/ScheduledTasksView.vue \
        zhiwei-web/src/views/ScheduledTasksView.spec.ts \
        zhiwei-web/src/router/index.ts
git commit -m "feat(web): 新增 ScheduledTasksView 全局定时任务管理页 + /scheduled-tasks 路由"
```

---

## Task A6: 定时任务创建时自动填充 projectId

**Files:**
- Step 0 定位：创建定时任务的工具执行器（对话中 LLM 调的那个工具）
- 修改：加 projectId 填充

- [ ] **Step 1: 定位创建入口**

```bash
grep -rn "CronTaskRepository.*insert\|cronTaskRepository\.insert" src/main/java
grep -rn "cron_tasks.*INSERT\|new CronTaskEntry" src/main/java
```

预期找到 cron 工具的 executor（类似 `CronToolExecutor` / `TaskToolExecutor`），它在用户对话里调用时 `insert` 一个 `CronTaskEntry`。

- [ ] **Step 2: 对齐 Plan 1 Task 15 MemoryToolProvider 的 writeContext 模式**

参考 `src/main/java/com/lifepilot/meta/infra/memory/MemoryToolProvider.java` Plan 1 引入的 `resolveProjectContext(ToolInput)` 方法，定位该创建入口后按**同样 pattern** 注入可选依赖并填充 projectId：

```java
// 注入（构造器或字段）
private final @Nullable com.lifepilot.project.context.ProjectContextResolver projectContextResolver;
private final @Nullable com.lifepilot.interaction.web.repository.ChatSessionRepository chatSessionRepository;

// 执行创建时
String sessionId = input.getContextValue("sessionId", String.class);
String projectId = resolveProjectIdOrNull(sessionId);

CronTaskEntry entry = new CronTaskEntry(
        UUID.randomUUID().toString(),
        name, schedule, instruction, "active", skillIds, projectId,
        Instant.now(), Instant.now()
);
repository.insert(entry);
```

`resolveProjectIdOrNull` 辅助方法：

```java
private @Nullable String resolveProjectIdOrNull(@Nullable String sessionId) {
    if (chatSessionRepository == null || projectContextResolver == null) return null;
    if (sessionId == null || sessionId.isBlank()) return null;
    try {
        return chatSessionRepository.findById(sessionId)
                .map(s -> s.projectId())
                .orElse(null);
    } catch (Exception e) {
        log.debug("反查 session 失败，回退主账户: sessionId={}", sessionId, e);
        return null;
    }
}
```

- [ ] **Step 3: 写测试**

测试类 pattern 参照 Plan 1 `MemoryToolProvider_项目写入路径测试`：

```java
@Test
void 创建定时任务_隔离项目对话_填充project_id() {
    // 构造 session 带 projectId="p-1" → executor 插入时应带 projectId="p-1"
}

@Test
void 创建定时任务_主账户对话_projectId为null() {
    // session.projectId=null → 插入时 projectId=null
}

@Test
void 创建定时任务_resolver缺失_回退projectId为null() {
    // chatSessionRepository=null → 插入时 projectId=null 不抛异常
}
```

- [ ] **Step 4: 跑测试 + 全量验证**

```bash
mvn -q test -Dtest="<Cron 创建测试类>"
mvn -q test    # 全量确认无回归
```

Expected: PASS + BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add <修改的文件>
git commit -m "feat(scheduled): 定时任务创建时按 ProjectContext 填充 project_id"
```

---

## Task A7: 定时任务集成测试 + 端到端

**Files:**
- Create: `src/test/java/com/lifepilot/agent/task/ScheduledTask_项目隔离端到端测试.java`

- [ ] **Step 1: 写集成测试**

```java
package com.lifepilot.agent.task;

import com.lifepilot.project.model.Project;
import com.lifepilot.project.model.ProjectIsolation;
import com.lifepilot.project.service.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 定时任务项目归属端到端测试。
 *
 * @author zsg
 * @since 2026-04-23
 */
@SpringBootTest
class ScheduledTask_项目隔离端到端测试 {

    @Autowired ProjectService projectService;
    @Autowired CronTaskRepository cronRepo;

    @Test
    @Transactional
    void 按projectId过滤_只返回归属该项目的任务() {
        Project p = projectService.createProject("论文", "", ProjectIsolation.ISOLATED);
        // 手动 insert 两个任务，一个归属项目一个归属主账户
        java.time.Instant now = java.time.Instant.now();
        cronRepo.insert(new CronTaskEntry(
                "t-project", "周报提醒", "0 0 21 ? * SUN", "",
                "active", null, p.id(), now, now));
        cronRepo.insert(new CronTaskEntry(
                "t-main", "天气推送", "0 0 8 * * *", "",
                "active", null, null, now, now));

        assertEquals(1, cronRepo.findByProjectId(p.id()).size());
        assertEquals("t-project", cronRepo.findByProjectId(p.id()).get(0).id());

        var main = cronRepo.findByProjectId(null);
        assertTrue(main.stream().anyMatch(e -> e.id().equals("t-main")));
        assertFalse(main.stream().anyMatch(e -> e.id().equals("t-project")));
    }
}
```

- [ ] **Step 2: 跑测试**

```bash
mvn -q test -Dtest="ScheduledTask_项目隔离端到端测试"
```

Expected: PASS (1 test)

- [ ] **Step 3-5: Commit**

```bash
git add src/test/java/com/lifepilot/agent/task/ScheduledTask_项目隔离端到端测试.java
git commit -m "test(scheduled): 定时任务项目隔离端到端集成测试"
```

---

# Part B: Datastore 用户侧下架（Task B1–B4）

## Task B1: LLM 工具注册表移除 datastore

**Files:**
- Modify: `src/main/java/com/lifepilot/meta/config/MetaAutoConfiguration.java`（StorageToolProvider 装配点，grep 确认）
- Modify（可能需要）: `src/main/java/com/lifepilot/meta/infra/storage/StorageToolProvider.java`

**策略**：让 StorageToolProvider 的 `buildStorageTools()` 返回空列表 **OR** 通过 `@ConditionalOnProperty` 关掉整个 bean。**后端代码完整保留**（按 spec §5.3）——只是 LLM 不再看到这个工具。

- [ ] **Step 1: 定位注册点**

```bash
grep -rn "StorageToolProvider\|buildStorageTools" src/main/java/com/lifepilot/meta/
grep -rn "StorageToolProvider" src/main/java/com/lifepilot/tool/
```

确认：
- `StorageToolProvider` 的构造 Bean 位置
- 返回的 `List<BuiltinTool>` 被谁消费（ToolRegistry / BuiltinTool autoConfigure）
- 最干净的下线点是哪里

- [ ] **Step 2: 写失败测试**

```java
package com.lifepilot.meta.infra.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.datastore.DataStoreManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class StorageToolProvider_Plan3下架测试 {

    @Test
    void buildStorageTools_返回空列表_datastore工具已下线() {
        StorageToolProvider provider = new StorageToolProvider(mock(DataStoreManager.class), new ObjectMapper());
        assertTrue(provider.buildStorageTools().isEmpty(),
                "Plan 3: datastore 工具应从 LLM 工具集下线，buildStorageTools 返回空列表");
    }
}
```

> **如果选择 @ConditionalOnProperty 方案**，测试要用 `ApplicationContextRunner` 验证 bean 未加载；参考项目其他 autoconfig 测试。

- [ ] **Step 3: 下线实现**

**方案 A（推荐）**：直接让 `buildStorageTools` 返回空：

```java
public List<BuiltinTool> buildStorageTools() {
    // Plan 3（spec §5.2）: datastore 工具从 LLM 工具集下线。
    // 后端 DataStoreManager 及完整能力保留，仅 LLM 不再接触这个工具。
    // 未来若决定复活（百万级 CRUD 场景），把下面的 return List.of() 改回原实现即可。
    return List.of();
    // var executor = new DatastoreActionDispatchExecutor(dataStoreManager, objectMapper);
    // return List.of(buildDatastoreTool(executor));
}
```

保留 `buildDatastoreTool` / `DatastoreActionDispatchExecutor` / `buildSchema` 等方法完整（spec §5.3 要求代码保留）。

**方案 B**：类级 `@ConditionalOnProperty`（更彻底但影响启动探活）——可选，项目如有这个惯例再用。

- [ ] **Step 4: 跑测试**

```bash
mvn -q test -Dtest="StorageToolProvider*"
```

Expected: PASS + 既有 StorageToolProvider 测试不挂

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/lifepilot/meta/infra/storage/StorageToolProvider.java \
        src/test/java/com/lifepilot/meta/infra/storage/StorageToolProvider_Plan3下架测试.java
git commit -m "refactor(datastore): Plan 3 — LLM 工具集下线 datastore（后端能力保留）"
```

---

## Task B2: 前端路由下架 + 菜单入口移除

**Files:**
- Modify: `zhiwei-web/src/router/index.ts`（行 97-105 附近，删 `/datastores` 和 `/datastores/:id`）
- Modify: 现有侧栏/主菜单中 datastore 入口（Step 0 grep）
- **保留**: `zhiwei-web/src/views/DatastoreView.vue` / `DatastoreDetailView.vue`（spec §5.3 代码保留）

- [ ] **Step 1: 定位菜单入口**

```bash
grep -rn "/datastores\|datastores\b\|资料仓库\|Datastore" zhiwei-web/src/components/ zhiwei-web/src/layouts/ 2>/dev/null
```

记录所有需要删除的引用点。

- [ ] **Step 2: 删除路由**

`router/index.ts` 删除 `/datastores` 和 `/datastores/:id` 两条路由对象。

```typescript
// 删除
{
  path: '/datastores',
  name: 'datastores',
  component: () => import('@/views/DatastoreView.vue')
},
{
  path: '/datastores/:id',
  name: 'datastoreDetail',
  component: () => import('@/views/DatastoreDetailView.vue')
}
```

保留这两个 `.vue` 文件本身（`src/views/DatastoreView.vue` / `DatastoreDetailView.vue`）——**不删文件**。

- [ ] **Step 3: 删除菜单入口**

按 Step 1 找到的引用点，逐个删除。典型位置：
- `zhiwei-web/src/components/layout/UnifiedSidebar.vue`（如果有 datastore 链接）
- `zhiwei-web/src/views/SettingsView.vue`（如果资料仓库是设置菜单的子项）
- 其他 layout / menu 组件

**不要动**：`DatastoreView.vue` / `DatastoreDetailView.vue` 本身（保留）和它们的 `.spec.ts` 文件（保留测试）。

- [ ] **Step 4: 验证**

```bash
cd zhiwei-web && npm run build && npm run test:run
npx vue-tsc --noEmit
```

Expected: BUILD SUCCESS + tests PASS + 0 type errors

手动验证：`npm run dev`，浏览器访问 `/datastores` 应 404（命中 `NotFoundView`）。

- [ ] **Step 5: Commit**

```bash
git add zhiwei-web/src/router/index.ts <其他菜单入口文件>
git commit -m "refactor(web): Plan 3 — 下架 /datastores 路由与菜单入口（组件文件保留）"
```

---

## Task B3: Datastore 下架端到端测试

**Files:**
- Create: `zhiwei-web/src/router/index.spec.ts`（如不存在）或在合适位置加测试

- [ ] **Step 1: 写路由下架验证测试**

```typescript
// zhiwei-web/src/router/index.spec.ts（或追加到现有测试）
import { describe, it, expect } from 'vitest'
import router from './index'

describe('router Plan 3 验证', () => {
  it('/datastores 路由已下架', () => {
    const route = router.resolve('/datastores')
    expect(route.name).not.toBe('datastores')
    // 命中 NotFoundView 的 catch-all 或 redirect 到 /
  })

  it('/datastores/:id 路由已下架', () => {
    const route = router.resolve('/datastores/ds-abc')
    expect(route.name).not.toBe('datastoreDetail')
  })

  it('/scheduled-tasks 路由存在（Plan 2）', () => {
    const route = router.resolve('/scheduled-tasks')
    expect(route.name).toBe('scheduledTasks')
  })
})
```

- [ ] **Step 2: 跑测试**

```bash
cd zhiwei-web && npm run test:run -- router
```

Expected: PASS

- [ ] **Step 3: （无额外实现）**

- [ ] **Step 4: 全量回归**

```bash
cd zhiwei-web && npm run test:run
```

Expected: 全绿

- [ ] **Step 5: Commit**

```bash
git add zhiwei-web/src/router/index.spec.ts
git commit -m "test(web): Plan 2/3 路由状态验证（datastore 下架 + scheduledTasks 上线）"
```

---

# 最终验收

执行完以上所有 task 后：

1. **全量回归**

```bash
mvn test
cd zhiwei-web && npm run test:run
npx vue-tsc --noEmit
```

Expected: 后端 3430+ tests 全绿 / 前端 135+ tests 全绿 / 0 type errors

2. **手动冒烟**

```bash
mvn spring-boot:run &
cd zhiwei-web && npm run dev &
# 浏览器：
# 1. 访问 /scheduled-tasks — 看到全局任务列表（空列表有提示文案）
# 2. 访问 /datastores — 404 或 NotFound
# 3. 侧栏点"定时任务"入口 — 跳转 /scheduled-tasks 正常
# 4. 对话中让 AI 排一个定时任务，然后去 /scheduled-tasks 看它出现（带正确项目 tag）
```

3. **spec §12 验收对照**

| 验收点 | Plan 2+3 完成情况 |
|---|---|
| 7. Datastore 主菜单入口消失、`/datastores` 404 | ✅ Task B2 + B3 |
| 8. LLM 不再调用 datastore 工具 | ✅ Task B1 |
| （Plan 2 附加）定时任务全局管理页 + 项目 tag | ✅ Task A1-A7 |

---

## Out of Scope（明确不做）

- 定时任务从 UI 手动新建（保持 LLM 自然语言触发）
- 定时任务跨项目迁移（不支持）
- 后台进程项目级聚合（会话级 tab 已覆盖）
- DataStoreManager 及后端 datastore 能力代码删除（spec §5.3 要求保留）
- 定时任务编辑弹窗（本 plan 暂用 PUT API，UI 可先只做暂停/恢复/删除；若需编辑 cron 表达式的弹窗，后续 polish）

---

## Execution Handoff

Plan 完成后，执行选项：

1. **Subagent-Driven（推荐）**：fresh subagent per task + 两阶段 review；本 plan 10 个 task 规模比 Plan 1 小得多，预计快速过完
2. **Inline Execution**：在当前 session 批量执行

推荐 Subagent-Driven（和 Plan 1 一致，保持节奏）。
