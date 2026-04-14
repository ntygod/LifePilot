# Proactive Engine Phase 2: Deep Perception — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add intent memory (persistent user goal tracking), two new behavior plugins (proactive follow-up and insight push), and migrate clipboard intent detection into the engine framework.

**Architecture:** Intent memory is a cross-cutting service: `IntentRepository` persists goals, `IntentExtractor` mines conversations for intents using regex patterns, `IntentMemoryService` manages lifecycle (extract → store → check → trigger → expire). `FollowUpBehavior` and `InsightBehavior` implement `ProactiveBehavior` — detect via memory queries, reason via LLM with StringTemplate prompts. `ClipboardBehavior` receives clipboard events via an in-memory buffer and generates contextual suggestions. All new plugins auto-discovered by `ProactiveEngine` through Spring configuration.

**Tech Stack:** Java 22, Spring Boot, SQLite (Flyway), JUnit 5, Mockito, StringTemplate prompts

**Design Spec:** `docs/superpowers/specs/2026-04-13-proactive-engine-evolution-design.md` §4–§5.2

---

## File Structure

### New files — intent memory (`src/main/java/com/lifepilot/agent/task/proactive/intent/`)

| File | Responsibility |
|------|---------------|
| `IntentRecord.java` | Intent data model — goal, triggerCondition, status, expiry |
| `IntentStatus.java` | Enum: ACTIVE / TRIGGERED / FULFILLED / EXPIRED |
| `IntentType.java` | Enum: GOAL / MONITORING / CONDITIONAL / RECURRING |
| `IntentRepository.java` | SQLite persistence — CRUD + query by status/user |
| `IntentExtractor.java` | Pattern-based extraction from conversation transcripts |
| `IntentMemoryService.java` | Lifecycle: extract, heartbeat check, trigger, expire |

### New files — behavior plugins (`src/main/java/com/lifepilot/agent/task/proactive/behavior/`)

| File | Responsibility |
|------|---------------|
| `FollowUpBehavior.java` | Proactive follow-up: detect unfinished topics + active intents, reason via LLM |
| `InsightBehavior.java` | Insight push: detect pattern changes in semantic memory, reason via LLM |
| `ClipboardBehavior.java` | Clipboard intent: detect from buffer, generate contextual suggestions |
| `ClipboardIntentBuffer.java` | Thread-safe ring buffer for recent clipboard intents |

### New files — prompts

| File | Responsibility |
|------|---------------|
| `src/main/resources/prompts/generation/proactive-follow-up.st` | Follow-up question prompt |
| `src/main/resources/prompts/generation/proactive-insight.st` | Insight generation prompt |

### New files — migration

| File | Responsibility |
|------|---------------|
| `src/main/resources/db/migration/V4__intent_memory.sql` | Intent memory table |

### Modified files

| File | Change |
|------|--------|
| `proactive/ProactiveAutoConfiguration.java` | Register new behavior beans + IntentMemoryService |
| `interaction/web/controller/ContextController.java` | Feed clipboard intents to ClipboardIntentBuffer (instead of direct notification) |

### New test files

| File | Tests |
|------|-------|
| `proactive/intent/IntentRepository_集成测试.java` | Intent CRUD + lifecycle queries |
| `proactive/intent/IntentExtractor_单元测试.java` | Pattern extraction from conversation text |
| `proactive/intent/IntentMemoryService_单元测试.java` | Lifecycle operations |
| `proactive/behavior/FollowUpBehavior_单元测试.java` | Detect + reason flow |
| `proactive/behavior/InsightBehavior_单元测试.java` | Detect + reason flow |
| `proactive/behavior/ClipboardBehavior_单元测试.java` | Detect + reason flow |

---

## Task 1: Intent Data Model and Persistence

**Files:**
- Create: `src/main/resources/db/migration/V4__intent_memory.sql`
- Create: `src/main/java/com/lifepilot/agent/task/proactive/intent/IntentStatus.java`
- Create: `src/main/java/com/lifepilot/agent/task/proactive/intent/IntentType.java`
- Create: `src/main/java/com/lifepilot/agent/task/proactive/intent/IntentRecord.java`
- Create: `src/main/java/com/lifepilot/agent/task/proactive/intent/IntentRepository.java`
- Test: `src/test/java/com/lifepilot/agent/task/proactive/intent/IntentRepository_集成测试.java`

- [ ] **Step 1: Create V4 migration**

```sql
-- V4__intent_memory.sql
-- 意图记忆表 — 持久化用户目标、关注点和触发条件

CREATE TABLE IF NOT EXISTS proactive_intent_memory (
    id                   TEXT    NOT NULL PRIMARY KEY,
    user_id              TEXT    NOT NULL,
    intent_type          TEXT    NOT NULL,
    goal                 TEXT    NOT NULL,
    trigger_condition    TEXT,
    source_session_id    TEXT,
    status               TEXT    NOT NULL DEFAULT 'ACTIVE',
    check_count          INTEGER NOT NULL DEFAULT 0,
    created_at           TEXT    NOT NULL,
    expires_at           TEXT,
    triggered_at         TEXT,
    fulfilled_at         TEXT,
    updated_at           TEXT    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_proactive_intent_memory_user_status
    ON proactive_intent_memory (user_id, status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_proactive_intent_memory_expires
    ON proactive_intent_memory (status, expires_at);
```

- [ ] **Step 2: Create enums and record**

```java
// IntentStatus.java
package com.lifepilot.agent.task.proactive.intent;

public enum IntentStatus {
    ACTIVE,
    TRIGGERED,
    FULFILLED,
    EXPIRED
}
```

```java
// IntentType.java
package com.lifepilot.agent.task.proactive.intent;

/** 意图类型 — "我想买耳机"是 GOAL，"帮我盯着降价"是 MONITORING，"等降到300告诉我"是 CONDITIONAL。 */
public enum IntentType {
    GOAL,
    MONITORING,
    CONDITIONAL,
    RECURRING
}
```

```java
// IntentRecord.java
package com.lifepilot.agent.task.proactive.intent;

import org.springframework.lang.Nullable;
import java.time.Instant;
import java.util.Objects;

public record IntentRecord(
        String id,
        String userId,
        IntentType intentType,
        String goal,
        @Nullable String triggerCondition,
        @Nullable String sourceSessionId,
        IntentStatus status,
        int checkCount,
        Instant createdAt,
        @Nullable Instant expiresAt,
        @Nullable Instant triggeredAt,
        @Nullable Instant fulfilledAt,
        Instant updatedAt
) {
    public IntentRecord {
        Objects.requireNonNull(id, "id 不能为空");
        Objects.requireNonNull(userId, "userId 不能为空");
        Objects.requireNonNull(intentType, "intentType 不能为空");
        Objects.requireNonNull(goal, "goal 不能为空");
        Objects.requireNonNull(status, "status 不能为空");
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
        Objects.requireNonNull(updatedAt, "updatedAt 不能为空");
        checkCount = Math.max(0, checkCount);
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }
}
```

- [ ] **Step 3: Write failing repository test**

```java
// IntentRepository_集成测试.java
package com.lifepilot.agent.task.proactive.intent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class IntentRepository_集成测试 {
    private IntentRepository repo;
    private SingleConnectionDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE proactive_intent_memory (
                    id TEXT NOT NULL PRIMARY KEY, user_id TEXT NOT NULL,
                    intent_type TEXT NOT NULL, goal TEXT NOT NULL,
                    trigger_condition TEXT, source_session_id TEXT,
                    status TEXT NOT NULL DEFAULT 'ACTIVE', check_count INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL, expires_at TEXT, triggered_at TEXT,
                    fulfilled_at TEXT, updated_at TEXT NOT NULL
                )""");
        jdbc.execute("CREATE INDEX idx_intent_user_status ON proactive_intent_memory (user_id, status, created_at DESC)");
        repo = new IntentRepository(jdbc);
    }

    @AfterEach
    void tearDown() { if (dataSource != null) dataSource.destroy(); }

    @Test
    void 保存并按状态查询() {
        var now = Instant.now();
        var record = new IntentRecord(UUID.randomUUID().toString(), "u1", IntentType.GOAL,
                "买耳机", "价格低于300", "sess-1", IntentStatus.ACTIVE, 0,
                now, now.plusSeconds(90 * 86400), null, null, now);
        repo.save(record);

        var active = repo.findActiveByUserId("u1");
        assertThat(active).hasSize(1);
        assertThat(active.getFirst().goal()).isEqualTo("买耳机");
    }

    @Test
    void 更新状态为TRIGGERED() {
        var id = UUID.randomUUID().toString();
        var now = Instant.now();
        repo.save(new IntentRecord(id, "u1", IntentType.CONDITIONAL, "等降价",
                "价格<300", null, IntentStatus.ACTIVE, 0, now, null, null, null, now));

        repo.updateStatus(id, IntentStatus.TRIGGERED, now);

        var found = repo.findById(id);
        assertThat(found).isNotNull();
        assertThat(found.status()).isEqualTo(IntentStatus.TRIGGERED);
    }

    @Test
    void 递增检查计数() {
        var id = UUID.randomUUID().toString();
        var now = Instant.now();
        repo.save(new IntentRecord(id, "u1", IntentType.GOAL, "目标",
                null, null, IntentStatus.ACTIVE, 0, now, null, null, null, now));

        repo.incrementCheckCount(id);
        repo.incrementCheckCount(id);

        var found = repo.findById(id);
        assertThat(found.checkCount()).isEqualTo(2);
    }

    @Test
    void 过期意图查询() {
        var now = Instant.now();
        repo.save(new IntentRecord(UUID.randomUUID().toString(), "u1", IntentType.GOAL, "已过期",
                null, null, IntentStatus.ACTIVE, 5, now.minusSeconds(100 * 86400),
                now.minusSeconds(86400), null, null, now.minusSeconds(100 * 86400)));

        var expired = repo.findExpired(now);
        assertThat(expired).hasSize(1);
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `mvn test -Dtest="IntentRepository_集成测试" -pl . -q`
Expected: FAIL — class not found

- [ ] **Step 5: Implement IntentRepository**

```java
package com.lifepilot.agent.task.proactive.intent;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.Nullable;
import java.time.Instant;
import java.util.List;

public class IntentRepository {
    private final JdbcTemplate jdbc;

    public IntentRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<IntentRecord> ROW_MAPPER = (rs, _) -> new IntentRecord(
            rs.getString("id"), rs.getString("user_id"),
            IntentType.valueOf(rs.getString("intent_type")),
            rs.getString("goal"), rs.getString("trigger_condition"),
            rs.getString("source_session_id"),
            IntentStatus.valueOf(rs.getString("status")),
            rs.getInt("check_count"), Instant.parse(rs.getString("created_at")),
            rs.getString("expires_at") != null ? Instant.parse(rs.getString("expires_at")) : null,
            rs.getString("triggered_at") != null ? Instant.parse(rs.getString("triggered_at")) : null,
            rs.getString("fulfilled_at") != null ? Instant.parse(rs.getString("fulfilled_at")) : null,
            Instant.parse(rs.getString("updated_at")));

    public void save(IntentRecord r) {
        jdbc.update("""
                INSERT INTO proactive_intent_memory
                (id,user_id,intent_type,goal,trigger_condition,source_session_id,status,check_count,
                 created_at,expires_at,triggered_at,fulfilled_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                r.id(), r.userId(), r.intentType().name(), r.goal(), r.triggerCondition(),
                r.sourceSessionId(), r.status().name(), r.checkCount(),
                r.createdAt().toString(), r.expiresAt() != null ? r.expiresAt().toString() : null,
                r.triggeredAt() != null ? r.triggeredAt().toString() : null,
                r.fulfilledAt() != null ? r.fulfilledAt().toString() : null, r.updatedAt().toString());
    }

    @Nullable
    public IntentRecord findById(String id) {
        var list = jdbc.query("SELECT * FROM proactive_intent_memory WHERE id = ?", ROW_MAPPER, id);
        return list.isEmpty() ? null : list.getFirst();
    }

    public List<IntentRecord> findActiveByUserId(String userId) {
        return jdbc.query("""
                SELECT * FROM proactive_intent_memory
                WHERE user_id = ? AND status = 'ACTIVE' ORDER BY created_at DESC""", ROW_MAPPER, userId);
    }

    public List<IntentRecord> findExpired(Instant now) {
        return jdbc.query("""
                SELECT * FROM proactive_intent_memory
                WHERE status = 'ACTIVE' AND expires_at IS NOT NULL AND expires_at < ?""",
                ROW_MAPPER, now.toString());
    }

    public void updateStatus(String id, IntentStatus status, Instant now) {
        String triggeredCol = status == IntentStatus.TRIGGERED ? now.toString() : null;
        String fulfilledCol = status == IntentStatus.FULFILLED ? now.toString() : null;
        jdbc.update("""
                UPDATE proactive_intent_memory
                SET status = ?, triggered_at = COALESCE(?, triggered_at),
                    fulfilled_at = COALESCE(?, fulfilled_at), updated_at = ?
                WHERE id = ?""", status.name(), triggeredCol, fulfilledCol, now.toString(), id);
    }

    public void incrementCheckCount(String id) {
        jdbc.update("""
                UPDATE proactive_intent_memory
                SET check_count = check_count + 1, updated_at = ?
                WHERE id = ?""", Instant.now().toString(), id);
    }

    public int expireOlderThan(Instant before) {
        return jdbc.update("""
                UPDATE proactive_intent_memory SET status = 'EXPIRED', updated_at = ?
                WHERE status = 'ACTIVE' AND expires_at IS NOT NULL AND expires_at < ?""",
                Instant.now().toString(), before.toString());
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn test -Dtest="IntentRepository_集成测试" -pl . -q`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V4__intent_memory.sql \
        src/main/java/com/lifepilot/agent/task/proactive/intent/ \
        src/test/java/com/lifepilot/agent/task/proactive/intent/
git commit -m "feat(proactive): 意图记忆持久化 — V4 迁移 + IntentRepository"
```

---

## Task 2: IntentExtractor — Pattern-Based Conversation Mining

**Files:**
- Create: `src/main/java/com/lifepilot/agent/task/proactive/intent/IntentExtractor.java`
- Test: `src/test/java/com/lifepilot/agent/task/proactive/intent/IntentExtractor_单元测试.java`

- [ ] **Step 1: Write the failing test**

```java
package com.lifepilot.agent.task.proactive.intent;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class IntentExtractor_单元测试 {

    private final IntentExtractor extractor = new IntentExtractor();

    @Test
    void 提取目标意图_我想() {
        var intents = extractor.extract("u1", "sess-1",
                List.of("我想买一个降噪耳机", "好的，帮你留意。"));
        assertThat(intents).anyMatch(i -> i.intentType() == IntentType.GOAL && i.goal().contains("耳机"));
    }

    @Test
    void 提取监控意图_帮我盯着() {
        var intents = extractor.extract("u1", "sess-2",
                List.of("帮我盯着京东上AirPods的价格"));
        assertThat(intents).anyMatch(i -> i.intentType() == IntentType.MONITORING);
    }

    @Test
    void 提取条件触发_等什么时候告诉我() {
        var intents = extractor.extract("u1", "sess-3",
                List.of("等那个降到300以下的时候告诉我"));
        assertThat(intents).anyMatch(i -> i.intentType() == IntentType.CONDITIONAL
                && i.triggerCondition() != null);
    }

    @Test
    void 提取习惯意图_每天() {
        var intents = extractor.extract("u1", "sess-4",
                List.of("我想养成每天早起跑步的习惯"));
        assertThat(intents).anyMatch(i -> i.intentType() == IntentType.RECURRING);
    }

    @Test
    void 无意图文本返回空() {
        var intents = extractor.extract("u1", "sess-5",
                List.of("今天天气不错", "是啊"));
        assertThat(intents).isEmpty();
    }

    @Test
    void 重复意图去重() {
        var intents = extractor.extract("u1", "sess-6",
                List.of("我想买耳机", "我想买个好耳机"));
        // 相似目标应合并或只返回一个
        assertThat(intents.stream().filter(i -> i.goal().contains("耳机")).count()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest="IntentExtractor_单元测试" -pl . -q`
Expected: FAIL — class not found

- [ ] **Step 3: Implement IntentExtractor**

```java
package com.lifepilot.agent.task.proactive.intent;

import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 意图提取器 — 从对话文本中识别用户目标、关注点和触发条件。
 *
 * <p>使用规则化模式匹配，不调用 LLM，确保在 Gate 2 阶段可安全调用。</p>
 */
public class IntentExtractor {

    /** "我想/我要/打算/计划" + 后续内容 → GOAL */
    private static final Pattern GOAL_PATTERN = Pattern.compile(
            "(?:我想|我要|打算|计划|准备|希望|想要)(.{2,40}?)(?:[，。！？,\\.!?]|$)");

    /** "帮我盯着/关注/留意" → MONITORING */
    private static final Pattern MONITORING_PATTERN = Pattern.compile(
            "(?:帮我|请|麻烦)(?:盯着|关注|留意|跟踪|追踪)(.{2,40}?)(?:[，。！？,\\.!?]|$)");

    /** "等...告诉我/提醒我" 或 "...的时候通知我" → CONDITIONAL */
    private static final Pattern CONDITIONAL_PATTERN = Pattern.compile(
            "(?:等|当|如果)(.{2,50}?)(?:告诉我|提醒我|通知我|叫我)");

    /** "每天/每周/每月" + 动作 → RECURRING */
    private static final Pattern RECURRING_PATTERN = Pattern.compile(
            "(?:每天|每周|每月|每个?早上|每个?晚上)(.{2,30}?)(?:[，。！？,\\.!?]|$)");

    /**
     * 从对话消息列表中提取意图。
     *
     * @param userId    用户 ID
     * @param sessionId 对话 ID
     * @param messages  用户消息文本列表（仅用户侧）
     * @return 提取到的意图列表
     */
    public List<IntentRecord> extract(String userId, String sessionId, List<String> messages) {
        var results = new LinkedHashMap<String, IntentRecord>(); // goal 去重
        Instant now = Instant.now();
        Instant defaultExpiry = now.plusSeconds(90L * 86400); // 90 天

        for (String msg : messages) {
            extractByPattern(msg, CONDITIONAL_PATTERN, IntentType.CONDITIONAL, userId, sessionId, now, defaultExpiry, results);
            extractByPattern(msg, MONITORING_PATTERN, IntentType.MONITORING, userId, sessionId, now, defaultExpiry, results);
            extractByPattern(msg, RECURRING_PATTERN, IntentType.RECURRING, userId, sessionId, now, defaultExpiry, results);
            extractByPattern(msg, GOAL_PATTERN, IntentType.GOAL, userId, sessionId, now, defaultExpiry, results);
        }
        return List.copyOf(results.values());
    }

    private void extractByPattern(String text, Pattern pattern, IntentType type,
                                   String userId, String sessionId, Instant now, Instant expiry,
                                   Map<String, IntentRecord> results) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String goal = matcher.group(1).strip();
            if (goal.length() < 2) continue;

            // 简单去重：同一目标关键词已存在则跳过
            String dedupeKey = normalizeForDedupe(goal);
            if (results.containsKey(dedupeKey)) continue;

            String triggerCondition = type == IntentType.CONDITIONAL ? goal : null;
            String goalText = type == IntentType.CONDITIONAL
                    ? text.strip()  // 条件型：整句作为目标
                    : goal;

            results.put(dedupeKey, new IntentRecord(
                    UUID.randomUUID().toString(), userId, type, goalText,
                    triggerCondition, sessionId, IntentStatus.ACTIVE, 0,
                    now, expiry, null, null, now));
        }
    }

    /** 取前 6 个字符作为去重键（同一对话内）。 */
    private static String normalizeForDedupe(String goal) {
        String cleaned = goal.replaceAll("[\\s，。！？,\\.!?]", "");
        return cleaned.length() > 6 ? cleaned.substring(0, 6) : cleaned;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest="IntentExtractor_单元测试" -pl . -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/lifepilot/agent/task/proactive/intent/IntentExtractor.java \
        src/test/java/com/lifepilot/agent/task/proactive/intent/IntentExtractor_单元测试.java
git commit -m "feat(proactive): IntentExtractor 意图提取器 — 规则化对话挖掘"
```

---

## Task 3: IntentMemoryService — Lifecycle Management

**Files:**
- Create: `src/main/java/com/lifepilot/agent/task/proactive/intent/IntentMemoryService.java`
- Test: `src/test/java/com/lifepilot/agent/task/proactive/intent/IntentMemoryService_单元测试.java`

- [ ] **Step 1: Write the failing test**

```java
package com.lifepilot.agent.task.proactive.intent;

import com.lifepilot.memory.episodic.EpisodicMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IntentMemoryService_单元测试 {

    IntentRepository intentRepository;
    IntentExtractor intentExtractor;
    EpisodicMemory episodicMemory;
    IntentMemoryService service;

    @BeforeEach
    void setUp() {
        intentRepository = mock(IntentRepository.class);
        intentExtractor = new IntentExtractor();
        episodicMemory = mock(EpisodicMemory.class);
        service = new IntentMemoryService(intentRepository, intentExtractor, episodicMemory);
    }

    @Test
    void 心跳过期清理() {
        var expired = new IntentRecord("i1", "u1", IntentType.GOAL, "已过期目标",
                null, null, IntentStatus.ACTIVE, 10,
                Instant.now().minusSeconds(100 * 86400), Instant.now().minusSeconds(86400),
                null, null, Instant.now().minusSeconds(100 * 86400));
        when(intentRepository.findExpired(any())).thenReturn(List.of(expired));

        service.expireStaleIntents();

        verify(intentRepository).updateStatus(eq("i1"), eq(IntentStatus.EXPIRED), any());
    }

    @Test
    void 查询活跃意图() {
        var intent = new IntentRecord("i1", "u1", IntentType.GOAL, "买耳机",
                null, "sess-1", IntentStatus.ACTIVE, 0,
                Instant.now(), Instant.now().plusSeconds(90 * 86400), null, null, Instant.now());
        when(intentRepository.findActiveByUserId("u1")).thenReturn(List.of(intent));

        var result = service.getActiveIntents("u1");

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().goal()).isEqualTo("买耳机");
    }

    @Test
    void 标记意图完成() {
        service.fulfillIntent("i1");
        verify(intentRepository).updateStatus(eq("i1"), eq(IntentStatus.FULFILLED), any());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest="IntentMemoryService_单元测试" -pl . -q`
Expected: FAIL

- [ ] **Step 3: Implement IntentMemoryService**

```java
package com.lifepilot.agent.task.proactive.intent;

import com.lifepilot.memory.episodic.EpisodicMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 意图记忆服务 — 管理意图的提取、存储、检查和过期。
 */
public class IntentMemoryService {

    private static final Logger log = LoggerFactory.getLogger(IntentMemoryService.class);
    private static final Duration EXTRACTION_LOOKBACK = Duration.ofDays(7);

    private final IntentRepository intentRepository;
    private final IntentExtractor intentExtractor;
    @Nullable
    private final EpisodicMemory episodicMemory;

    public IntentMemoryService(IntentRepository intentRepository,
                               IntentExtractor intentExtractor,
                               @Nullable EpisodicMemory episodicMemory) {
        this.intentRepository = intentRepository;
        this.intentExtractor = intentExtractor;
        this.episodicMemory = episodicMemory;
    }

    /** 获取用户活跃意图。 */
    public List<IntentRecord> getActiveIntents(String userId) {
        return intentRepository.findActiveByUserId(userId);
    }

    /** 保存新意图。 */
    public void saveIntent(IntentRecord intent) {
        intentRepository.save(intent);
        log.debug("意图已保存: type={}, goal={}", intent.intentType(), intent.goal());
    }

    /** 标记意图完成。 */
    public void fulfillIntent(String intentId) {
        intentRepository.updateStatus(intentId, IntentStatus.FULFILLED, Instant.now());
    }

    /** 过期清理 — 在心跳中调用。 */
    public void expireStaleIntents() {
        var expired = intentRepository.findExpired(Instant.now());
        for (var intent : expired) {
            intentRepository.updateStatus(intent.id(), IntentStatus.EXPIRED, Instant.now());
            log.debug("意图已过期: id={}, goal={}", intent.id(), intent.goal());
        }
    }

    /**
     * 从近期对话中提取新意图 — 在心跳中调用。
     *
     * @return 新提取的意图数量
     */
    public int extractFromRecentConversations(String userId) {
        if (episodicMemory == null) return 0;

        try {
            var recent = episodicMemory.getRecent(EXTRACTION_LOOKBACK);
            var existingGoals = intentRepository.findActiveByUserId(userId).stream()
                    .map(IntentRecord::goal)
                    .toList();

            int count = 0;
            for (var conversation : recent) {
                var userMessages = conversation.messages().stream()
                        .filter(m -> "user".equals(m.role()))
                        .map(m -> m.content())
                        .toList();
                if (userMessages.isEmpty()) continue;

                var extracted = intentExtractor.extract(userId, conversation.sessionId(), userMessages);
                for (var intent : extracted) {
                    // 简单去重：目标文本前缀匹配
                    boolean duplicate = existingGoals.stream()
                            .anyMatch(g -> g.contains(intent.goal().substring(0, Math.min(4, intent.goal().length())))
                                    || intent.goal().contains(g.substring(0, Math.min(4, g.length()))));
                    if (!duplicate) {
                        intentRepository.save(intent);
                        count++;
                    }
                }
            }
            if (count > 0) {
                log.info("意图提取完成: userId={}, newIntents={}", userId, count);
            }
            return count;
        } catch (Exception e) {
            log.debug("意图提取跳过: {}", e.getMessage());
            return 0;
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest="IntentMemoryService_单元测试" -pl . -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/lifepilot/agent/task/proactive/intent/IntentMemoryService.java \
        src/test/java/com/lifepilot/agent/task/proactive/intent/IntentMemoryService_单元测试.java
git commit -m "feat(proactive): IntentMemoryService 意图记忆服务 — 生命周期管理"
```

---

## Task 4: FollowUpBehavior — Proactive Follow-Up Plugin

**Files:**
- Create: `src/main/resources/prompts/generation/proactive-follow-up.st`
- Create: `src/main/java/com/lifepilot/agent/task/proactive/behavior/FollowUpBehavior.java`
- Test: `src/test/java/com/lifepilot/agent/task/proactive/behavior/FollowUpBehavior_单元测试.java`

- [ ] **Step 1: Create prompt template**

```
你是一个个人助手的追问生成器。请基于给定对话上下文生成一条中文追问。

要求：
1. 语气自然，像朋友间的关心，不要机械或说教。
2. 追问应该和用户之前提到的话题自然衔接。
3. 一句话即可，不超过 40 个汉字。
4. 不要出现技术术语，不要使用 Markdown。
5. 只输出追问内容，不要解释。

当前时间：{currentTime}
用户目标：{intentGoal}
对话摘要：{conversationSummary}
距上次对话：{daysSinceLastChat}天
```

- [ ] **Step 2: Write the failing test**

```java
package com.lifepilot.agent.task.proactive.behavior;

import com.lifepilot.agent.task.proactive.*;
import com.lifepilot.agent.task.proactive.intent.*;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FollowUpBehavior_单元测试 {

    IntentMemoryService intentMemoryService;
    GenerationRouter generationRouter;
    PromptRegistry promptRegistry;
    FollowUpBehavior behavior;

    @BeforeEach
    void setUp() {
        intentMemoryService = mock(IntentMemoryService.class);
        generationRouter = mock(GenerationRouter.class);
        promptRegistry = mock(PromptRegistry.class);
        behavior = new FollowUpBehavior(intentMemoryService, generationRouter, promptRegistry);
    }

    @Test
    void 插件名称() {
        assertThat(behavior.name()).isEqualTo("follow-up");
    }

    @Test
    void detect_有活跃意图时返回候选() {
        var intent = new IntentRecord("i1", "u1", IntentType.GOAL, "买耳机",
                null, "sess-1", IntentStatus.ACTIVE, 2,
                Instant.now().minusSeconds(3 * 86400), Instant.now().plusSeconds(87 * 86400),
                null, null, Instant.now().minusSeconds(86400));
        when(intentMemoryService.getActiveIntents("u1")).thenReturn(List.of(intent));

        var ctx = testCtx();
        var candidates = behavior.detect(ctx);

        assertThat(candidates).isNotEmpty();
        assertThat(candidates.getFirst().behaviorName()).isEqualTo("follow-up");
        assertThat(candidates.getFirst().topicKey()).contains("i1");
    }

    @Test
    void detect_无意图时返回空() {
        when(intentMemoryService.getActiveIntents("u1")).thenReturn(List.of());

        var candidates = behavior.detect(testCtx());

        assertThat(candidates).isEmpty();
    }

    @Test
    void detect_刚创建的意图不追问() {
        // 创建不到 24 小时的意图不应追问
        var intent = new IntentRecord("i1", "u1", IntentType.GOAL, "买耳机",
                null, "sess-1", IntentStatus.ACTIVE, 0,
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(90 * 86400),
                null, null, Instant.now().minusSeconds(3600));
        when(intentMemoryService.getActiveIntents("u1")).thenReturn(List.of(intent));

        var candidates = behavior.detect(testCtx());

        assertThat(candidates).isEmpty();
    }

    @Test
    void reason_生成追问内容() {
        when(promptRegistry.render(eq("generation/proactive-follow-up"), any()))
                .thenReturn("那个耳机你看好哪款了？");
        when(generationRouter.generateSync(anyString(), any()))
                .thenReturn("那个降噪耳机你考虑好了吗？");

        var candidate = new ProactiveCandidate("c1", "follow-up", "intent-i1",
                "买耳机", 0.5f, "活跃目标", null);
        var actions = behavior.reason(List.of(candidate), testCtx());

        assertThat(actions).hasSize(1);
        assertThat(actions.getFirst().suggestedLevel()).isEqualTo(DeliveryLevel.NOTIFY);
    }

    private ContextPacket testCtx() {
        return new ContextPacket("u1", Instant.now(), ZoneId.of("Asia/Shanghai"),
                null, null, 0, 5, null, null, 30);
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn test -Dtest="FollowUpBehavior_单元测试" -pl . -q`
Expected: FAIL

- [ ] **Step 4: Implement FollowUpBehavior**

```java
package com.lifepilot.agent.task.proactive.behavior;

import com.lifepilot.agent.task.proactive.*;
import com.lifepilot.agent.task.proactive.intent.*;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 主动追问行为插件 — 基于意图记忆追踪用户未完成的目标和话题。
 *
 * <p>detect: 查询活跃意图，过滤创建超过 24h 且检查次数合理的。
 * reason: 使用 LLM 生成自然追问（带回退模板）。</p>
 */
public class FollowUpBehavior implements ProactiveBehavior {

    private static final Logger log = LoggerFactory.getLogger(FollowUpBehavior.class);
    private static final Duration MIN_AGE = Duration.ofHours(24);
    private static final int MAX_CHECK_COUNT = 5;
    private static final String PROMPT_KEY = "generation/proactive-follow-up";

    private final IntentMemoryService intentMemoryService;
    @Nullable private final GenerationRouter generationRouter;
    @Nullable private final PromptRegistry promptRegistry;

    public FollowUpBehavior(IntentMemoryService intentMemoryService,
                            @Nullable GenerationRouter generationRouter,
                            @Nullable PromptRegistry promptRegistry) {
        this.intentMemoryService = intentMemoryService;
        this.generationRouter = generationRouter;
        this.promptRegistry = promptRegistry;
    }

    @Override
    public String name() { return "follow-up"; }

    @Override
    public List<ProactiveCandidate> detect(ContextPacket ctx) {
        // 维护：过期清理 + 提取新意图
        intentMemoryService.expireStaleIntents();
        intentMemoryService.extractFromRecentConversations(ctx.userId());

        var intents = intentMemoryService.getActiveIntents(ctx.userId());
        var candidates = new ArrayList<ProactiveCandidate>();

        for (var intent : intents) {
            // 太新的意图不追问
            if (Duration.between(intent.createdAt(), ctx.now()).compareTo(MIN_AGE) < 0) continue;
            // 检查次数过多的衰减
            if (intent.checkCount() >= MAX_CHECK_COUNT) continue;

            float score = computeScore(intent, ctx.now());
            if (score < 0.3f) continue;

            candidates.add(new ProactiveCandidate(
                    UUID.randomUUID().toString(), name(),
                    "intent-" + intent.id(), intent.goal(),
                    score, "活跃意图: " + intent.intentType(), intent));
        }
        log.debug("FollowUpBehavior.detect: intents={}, candidates={}", intents.size(), candidates.size());
        return candidates;
    }

    @Override
    public List<ProactiveAction> reason(List<ProactiveCandidate> candidates, ContextPacket ctx) {
        var actions = new ArrayList<ProactiveAction>();
        for (var candidate : candidates) {
            String content = generateFollowUp(candidate, ctx);
            if (content == null || content.isBlank()) continue;

            actions.add(new ProactiveAction(candidate, content,
                    DeliveryLevel.NOTIFY, candidate.detail()));

            // 递增检查计数
            if (candidate.detail() instanceof IntentRecord intent) {
                intentMemoryService.incrementCheckCount(intent.id());
            }
        }
        return actions;
    }

    private float computeScore(IntentRecord intent, Instant now) {
        long ageDays = Duration.between(intent.createdAt(), now).toDays();
        // 基础分：0.5
        float score = 0.5f;
        // 时间衰减：超过 7 天降分
        if (ageDays > 7) score -= (ageDays - 7) * 0.02f;
        // 检查次数衰减
        score -= intent.checkCount() * 0.05f;
        // CONDITIONAL 类型加分（有触发条件更值得追踪）
        if (intent.intentType() == IntentType.CONDITIONAL) score += 0.1f;
        return Math.max(0f, Math.min(1f, score));
    }

    private String generateFollowUp(ProactiveCandidate candidate, ContextPacket ctx) {
        if (generationRouter != null && promptRegistry != null) {
            try {
                String prompt = promptRegistry.render(PROMPT_KEY, Map.of(
                        "currentTime", DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(
                                ctx.now().atZone(ctx.zoneId())),
                        "intentGoal", candidate.title(),
                        "conversationSummary", candidate.rationale(),
                        "daysSinceLastChat", String.valueOf(computeDaysSince(candidate))
                ));
                String result = generationRouter.generateSync(prompt, null);
                if (result != null && !result.isBlank()) return result.strip();
            } catch (Exception e) {
                log.debug("FollowUpBehavior: LLM 生成失败，使用回退模板: {}", e.getMessage());
            }
        }
        // 回退模板
        return "你之前提到过「" + candidate.title() + "」，进展怎么样了？";
    }

    private long computeDaysSince(ProactiveCandidate candidate) {
        if (candidate.detail() instanceof IntentRecord intent) {
            return Duration.between(intent.createdAt(), Instant.now()).toDays();
        }
        return 1;
    }
}
```

Note: `intentMemoryService.incrementCheckCount()` needs to be added — a simple delegate to `intentRepository.incrementCheckCount()`. Add this method to `IntentMemoryService`:

```java
public void incrementCheckCount(String intentId) {
    intentRepository.incrementCheckCount(intentId);
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn test -Dtest="FollowUpBehavior_单元测试" -pl . -q`
Expected: PASS (adjust mocking as needed for GenerationRouter.generateSync signature)

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/prompts/generation/proactive-follow-up.st \
        src/main/java/com/lifepilot/agent/task/proactive/behavior/FollowUpBehavior.java \
        src/main/java/com/lifepilot/agent/task/proactive/intent/IntentMemoryService.java \
        src/test/java/com/lifepilot/agent/task/proactive/behavior/FollowUpBehavior_单元测试.java
git commit -m "feat(proactive): FollowUpBehavior 主动追问插件 — 基于意图记忆"
```

---

## Task 5: InsightBehavior — Insight Push Plugin

**Files:**
- Create: `src/main/resources/prompts/generation/proactive-insight.st`
- Create: `src/main/java/com/lifepilot/agent/task/proactive/behavior/InsightBehavior.java`
- Test: `src/test/java/com/lifepilot/agent/task/proactive/behavior/InsightBehavior_单元测试.java`

- [ ] **Step 1: Create prompt template**

```
你是一个个人助手的洞察生成器。请基于给定的用户行为变化生成一条有价值的中文洞察。

要求：
1. 洞察应该揭示用户可能没注意到的趋势或变化。
2. 语气温和，像朋友的提醒，不要数据化或机械。
3. 2-3 句话，不超过 60 个汉字。
4. 不要出现技术术语，不要使用 Markdown。
5. 只输出洞察内容，不要解释。

当前时间：{currentTime}
变化类型：{changeType}
变化详情：{changeDetail}
相关话题：{relatedTopics}
```

- [ ] **Step 2: Write the failing test**

```java
package com.lifepilot.agent.task.proactive.behavior;

import com.lifepilot.agent.task.proactive.*;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InsightBehavior_单元测试 {

    SemanticMemory semanticMemory;
    GenerationRouter generationRouter;
    PromptRegistry promptRegistry;
    InsightBehavior behavior;

    @BeforeEach
    void setUp() {
        semanticMemory = mock(SemanticMemory.class);
        generationRouter = mock(GenerationRouter.class);
        promptRegistry = mock(PromptRegistry.class);
        behavior = new InsightBehavior(semanticMemory, generationRouter, promptRegistry);
    }

    @Test
    void 插件名称() {
        assertThat(behavior.name()).isEqualTo("insight");
    }

    @Test
    void detect_无语义记忆时返回空() {
        behavior = new InsightBehavior(null, generationRouter, promptRegistry);
        var candidates = behavior.detect(testCtx());
        assertThat(candidates).isEmpty();
    }

    @Test
    void reason_生成洞察内容_使用回退模板() {
        var candidate = new ProactiveCandidate("c1", "insight", "goal-trend",
                "最近频繁关注健身", 0.5f, "新增目标: 健身", null);

        var actions = behavior.reason(List.of(candidate), testCtx());

        assertThat(actions).hasSize(1);
        assertThat(actions.getFirst().content()).isNotBlank();
        assertThat(actions.getFirst().suggestedLevel()).isEqualTo(DeliveryLevel.NOTIFY);
    }

    private ContextPacket testCtx() {
        return new ContextPacket("u1", Instant.now(), ZoneId.of("Asia/Shanghai"),
                null, null, 0, 5, null, null, 30);
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn test -Dtest="InsightBehavior_单元测试" -pl . -q`
Expected: FAIL

- [ ] **Step 4: Implement InsightBehavior**

```java
package com.lifepilot.agent.task.proactive.behavior;

import com.lifepilot.agent.task.proactive.*;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.model.EntityType;
import com.lifepilot.memory.semantic.model.TemporalEntity;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 洞察推送行为插件 — 基于语义记忆中的实体变化检测模式趋势。
 *
 * <p>detect: 查询最近新增/更新的实体（目标、习惯、话题），识别变化趋势。
 * reason: 使用 LLM 生成可解释的洞察（带回退模板）。</p>
 */
public class InsightBehavior implements ProactiveBehavior {

    private static final Logger log = LoggerFactory.getLogger(InsightBehavior.class);
    private static final Duration RECENT_WINDOW = Duration.ofDays(7);
    private static final String PROMPT_KEY = "generation/proactive-insight";
    private static final Set<EntityType> WATCHED_TYPES = Set.of(
            EntityType.GOAL, EntityType.HABIT, EntityType.TOPIC, EntityType.PROJECT);

    @Nullable private final SemanticMemory semanticMemory;
    @Nullable private final GenerationRouter generationRouter;
    @Nullable private final PromptRegistry promptRegistry;

    public InsightBehavior(@Nullable SemanticMemory semanticMemory,
                           @Nullable GenerationRouter generationRouter,
                           @Nullable PromptRegistry promptRegistry) {
        this.semanticMemory = semanticMemory;
        this.generationRouter = generationRouter;
        this.promptRegistry = promptRegistry;
    }

    @Override
    public String name() { return "insight"; }

    @Override
    public List<ProactiveCandidate> detect(ContextPacket ctx) {
        if (semanticMemory == null) return List.of();

        var candidates = new ArrayList<ProactiveCandidate>();
        Instant recentSince = ctx.now().minus(RECENT_WINDOW);

        for (var type : WATCHED_TYPES) {
            try {
                var entities = semanticMemory.findCurrentByType(type);
                for (var entity : entities) {
                    if (entity.createdAt().isAfter(recentSince)
                            || entity.updatedAt().isAfter(recentSince)) {
                        float score = computeScore(entity, ctx.now());
                        if (score >= 0.3f) {
                            String changeType = entity.createdAt().isAfter(recentSince)
                                    ? "新增" + type.name().toLowerCase()
                                    : "更新" + type.name().toLowerCase();
                            candidates.add(new ProactiveCandidate(
                                    UUID.randomUUID().toString(), name(),
                                    type.name().toLowerCase() + "-" + entity.name(),
                                    changeType + ": " + entity.name(),
                                    score, changeType, entity));
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("InsightBehavior: 查询 {} 失败: {}", type, e.getMessage());
            }
        }
        log.debug("InsightBehavior.detect: candidates={}", candidates.size());
        return candidates;
    }

    @Override
    public List<ProactiveAction> reason(List<ProactiveCandidate> candidates, ContextPacket ctx) {
        var actions = new ArrayList<ProactiveAction>();
        for (var candidate : candidates) {
            String content = generateInsight(candidate, ctx);
            if (content == null || content.isBlank()) continue;

            actions.add(new ProactiveAction(candidate, content,
                    DeliveryLevel.NOTIFY, candidate.detail()));
        }
        return actions;
    }

    private float computeScore(TemporalEntity entity, Instant now) {
        float score = 0.4f;
        // 重要性加分
        score += entity.importanceScore() * 0.2f;
        // 新实体加分
        if (Duration.between(entity.createdAt(), now).toDays() <= 3) score += 0.15f;
        // 访问频率加分
        if (entity.accessCount() > 3) score += 0.1f;
        return Math.max(0f, Math.min(1f, score));
    }

    private String generateInsight(ProactiveCandidate candidate, ContextPacket ctx) {
        if (generationRouter != null && promptRegistry != null) {
            try {
                String prompt = promptRegistry.render(PROMPT_KEY, Map.of(
                        "currentTime", DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(
                                ctx.now().atZone(ctx.zoneId())),
                        "changeType", candidate.rationale(),
                        "changeDetail", candidate.title(),
                        "relatedTopics", ""
                ));
                String result = generationRouter.generateSync(prompt, null);
                if (result != null && !result.isBlank()) return result.strip();
            } catch (Exception e) {
                log.debug("InsightBehavior: LLM 生成失败: {}", e.getMessage());
            }
        }
        return "最近你在关注「" + candidate.title().replaceAll("^[^:]+: ", "") + "」方面有新的变化，想聊聊吗？";
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn test -Dtest="InsightBehavior_单元测试" -pl . -q`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/prompts/generation/proactive-insight.st \
        src/main/java/com/lifepilot/agent/task/proactive/behavior/InsightBehavior.java \
        src/test/java/com/lifepilot/agent/task/proactive/behavior/InsightBehavior_单元测试.java
git commit -m "feat(proactive): InsightBehavior 洞察推送插件 — 基于语义记忆变化"
```

---

## Task 6: ClipboardBehavior — Clipboard Intent as Plugin

**Files:**
- Create: `src/main/java/com/lifepilot/agent/task/proactive/behavior/ClipboardIntentBuffer.java`
- Create: `src/main/java/com/lifepilot/agent/task/proactive/behavior/ClipboardBehavior.java`
- Modify: `src/main/java/com/lifepilot/interaction/web/controller/ContextController.java`
- Test: `src/test/java/com/lifepilot/agent/task/proactive/behavior/ClipboardBehavior_单元测试.java`

- [ ] **Step 1: Write the failing test**

```java
package com.lifepilot.agent.task.proactive.behavior;

import com.lifepilot.agent.task.proactive.*;
import com.lifepilot.agent.task.reminder.ReminderClipboardIntent;
import com.lifepilot.agent.task.reminder.ReminderClipboardIntentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ClipboardBehavior_单元测试 {

    ClipboardIntentBuffer buffer;
    ClipboardBehavior behavior;

    @BeforeEach
    void setUp() {
        buffer = new ClipboardIntentBuffer();
        behavior = new ClipboardBehavior(buffer);
    }

    @Test
    void 插件名称() {
        assertThat(behavior.name()).isEqualTo("clipboard");
    }

    @Test
    void detect_缓冲区为空时返回空() {
        var candidates = behavior.detect(testCtx());
        assertThat(candidates).isEmpty();
    }

    @Test
    void detect_有快递单号意图时返回候选() {
        buffer.offer(new ReminderClipboardIntent(
                ReminderClipboardIntentType.TRACKING_NUMBER, "SF1234567890123", Instant.now()));

        var candidates = behavior.detect(testCtx());

        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().title()).contains("快递");
    }

    @Test
    void detect_消费后缓冲区清空() {
        buffer.offer(new ReminderClipboardIntent(
                ReminderClipboardIntentType.FLIGHT_NUMBER, "CA1234", Instant.now()));

        behavior.detect(testCtx());  // 第一次消费
        var secondDetect = behavior.detect(testCtx());

        assertThat(secondDetect).isEmpty();
    }

    @Test
    void reason_生成建议文案() {
        var candidate = new ProactiveCandidate("c1", "clipboard", "tracking-SF123",
                "快递单号: SF1234567890123", 0.6f, "TRACKING_NUMBER",
                new ReminderClipboardIntent(ReminderClipboardIntentType.TRACKING_NUMBER,
                        "SF1234567890123", Instant.now()));

        var actions = behavior.reason(List.of(candidate), testCtx());

        assertThat(actions).hasSize(1);
        assertThat(actions.getFirst().content()).contains("SF1234567890123");
        assertThat(actions.getFirst().suggestedLevel()).isEqualTo(DeliveryLevel.NOTIFY);
    }

    private ContextPacket testCtx() {
        return new ContextPacket("u1", Instant.now(), ZoneId.of("Asia/Shanghai"),
                null, null, 0, 5, null, null, 30);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest="ClipboardBehavior_单元测试" -pl . -q`
Expected: FAIL

- [ ] **Step 3: Implement ClipboardIntentBuffer and ClipboardBehavior**

```java
// ClipboardIntentBuffer.java
package com.lifepilot.agent.task.proactive.behavior;

import com.lifepilot.agent.task.reminder.ReminderClipboardIntent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 剪贴板意图缓冲区 — 线程安全的环形缓冲，供 ClipboardBehavior 消费。
 */
public class ClipboardIntentBuffer {

    private final ConcurrentLinkedQueue<ReminderClipboardIntent> queue = new ConcurrentLinkedQueue<>();
    private static final int MAX_SIZE = 20;

    /** 写入一条意图（由 ContextController 调用）。 */
    public void offer(ReminderClipboardIntent intent) {
        queue.offer(intent);
        while (queue.size() > MAX_SIZE) queue.poll();
    }

    /** 消费并清空缓冲区（由 ClipboardBehavior.detect 调用）。 */
    public List<ReminderClipboardIntent> drainAll() {
        var result = new ArrayList<ReminderClipboardIntent>();
        ReminderClipboardIntent item;
        while ((item = queue.poll()) != null) result.add(item);
        return result;
    }

    public boolean isEmpty() { return queue.isEmpty(); }
}
```

```java
// ClipboardBehavior.java
package com.lifepilot.agent.task.proactive.behavior;

import com.lifepilot.agent.task.proactive.*;
import com.lifepilot.agent.task.reminder.ReminderClipboardIntent;
import com.lifepilot.agent.task.reminder.ReminderClipboardIntentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 剪贴板意图行为插件 — 从剪贴板缓冲区检测意图并生成上下文建议。
 */
public class ClipboardBehavior implements ProactiveBehavior {

    private static final Logger log = LoggerFactory.getLogger(ClipboardBehavior.class);
    private final ClipboardIntentBuffer buffer;

    public ClipboardBehavior(ClipboardIntentBuffer buffer) {
        this.buffer = buffer;
    }

    @Override
    public String name() { return "clipboard"; }

    @Override
    public List<ProactiveCandidate> detect(ContextPacket ctx) {
        var intents = buffer.drainAll();
        if (intents.isEmpty()) return List.of();

        var candidates = new ArrayList<ProactiveCandidate>();
        for (var intent : intents) {
            String title = describIntent(intent);
            candidates.add(new ProactiveCandidate(
                    UUID.randomUUID().toString(), name(),
                    intent.intentType().name().toLowerCase() + "-" + intent.value(),
                    title, 0.6f,
                    intent.intentType().name(), intent));
        }
        log.debug("ClipboardBehavior.detect: intents={}", candidates.size());
        return candidates;
    }

    @Override
    public List<ProactiveAction> reason(List<ProactiveCandidate> candidates, ContextPacket ctx) {
        var actions = new ArrayList<ProactiveAction>();
        for (var candidate : candidates) {
            if (!(candidate.detail() instanceof ReminderClipboardIntent intent)) continue;
            String suggestion = generateSuggestion(intent);
            actions.add(new ProactiveAction(candidate, suggestion,
                    DeliveryLevel.NOTIFY, intent));
        }
        return actions;
    }

    private String describIntent(ReminderClipboardIntent intent) {
        return switch (intent.intentType()) {
            case TRACKING_NUMBER -> "快递单号: " + intent.value();
            case FLIGHT_NUMBER -> "航班号: " + intent.value();
            case TRAIN_NUMBER -> "火车车次: " + intent.value();
            case URL -> "链接: " + truncate(intent.value(), 40);
            case PHONE -> "电话号码: " + intent.value();
        };
    }

    private String generateSuggestion(ReminderClipboardIntent intent) {
        return switch (intent.intentType()) {
            case TRACKING_NUMBER -> "检测到快递单号 " + intent.value() + "，要帮你查一下物流状态吗？";
            case FLIGHT_NUMBER -> "检测到航班号 " + intent.value() + "，要帮你查一下航班动态吗？";
            case TRAIN_NUMBER -> "检测到火车车次 " + intent.value() + "，要帮你查一下列车信息吗？";
            case URL -> "检测到一个链接，要帮你看看内容吗？";
            case PHONE -> "检测到电话号码 " + intent.value() + "，要帮你查一下归属地吗？";
        };
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest="ClipboardBehavior_单元测试" -pl . -q`
Expected: PASS

- [ ] **Step 5: Modify ContextController to feed buffer**

In `ContextController.java`, inject `ClipboardIntentBuffer` and call `buffer.offer(intent)` instead of directly sending notifications. The original notification path is kept as fallback when the engine is not available.

Find the `reportClipboardIntent` method and add:
```java
// 新增字段
@Nullable private final ClipboardIntentBuffer clipboardIntentBuffer;

// 在 reportClipboardIntent 方法开头添加：
if (clipboardIntentBuffer != null) {
    clipboardIntentBuffer.offer(intent);
    log.debug("剪贴板意图已缓冲: type={}", intent.intentType());
    return ResponseEntity.noContent().build();
}
// 原有直接通知逻辑作为回退...
```

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/lifepilot/agent/task/proactive/behavior/ \
        src/main/java/com/lifepilot/interaction/web/controller/ContextController.java \
        src/test/java/com/lifepilot/agent/task/proactive/behavior/ClipboardBehavior_单元测试.java
git commit -m "feat(proactive): ClipboardBehavior 剪贴板意图插件 — 迁入引擎框架"
```

---

## Task 7: Auto-Configuration and Wiring

**Files:**
- Modify: `src/main/java/com/lifepilot/agent/task/proactive/ProactiveAutoConfiguration.java`

- [ ] **Step 1: Add all new beans to ProactiveAutoConfiguration**

```java
// 新增 Bean 注册

// ── 意图记忆 ──
@Bean @ConditionalOnMissingBean
IntentRepository intentRepository(JdbcTemplate jdbc) { return new IntentRepository(jdbc); }

@Bean @ConditionalOnMissingBean
IntentExtractor intentExtractor() { return new IntentExtractor(); }

@Bean @ConditionalOnMissingBean
IntentMemoryService intentMemoryService(IntentRepository intentRepository,
                                         IntentExtractor intentExtractor,
                                         @Autowired(required = false) EpisodicMemory episodicMemory) {
    return new IntentMemoryService(intentRepository, intentExtractor, episodicMemory);
}

// ── 行为插件 ──
@Bean @ConditionalOnMissingBean
ClipboardIntentBuffer clipboardIntentBuffer() { return new ClipboardIntentBuffer(); }

@Bean @ConditionalOnMissingBean
FollowUpBehavior followUpBehavior(IntentMemoryService intentMemoryService,
                                   @Autowired(required = false) GenerationRouter generationRouter,
                                   @Autowired(required = false) PromptRegistry promptRegistry) {
    return new FollowUpBehavior(intentMemoryService, generationRouter, promptRegistry);
}

@Bean @ConditionalOnMissingBean
InsightBehavior insightBehavior(@Autowired(required = false) SemanticMemory semanticMemory,
                                @Autowired(required = false) GenerationRouter generationRouter,
                                @Autowired(required = false) PromptRegistry promptRegistry) {
    return new InsightBehavior(semanticMemory, generationRouter, promptRegistry);
}

@Bean @ConditionalOnMissingBean
ClipboardBehavior clipboardBehavior(ClipboardIntentBuffer clipboardIntentBuffer) {
    return new ClipboardBehavior(clipboardIntentBuffer);
}
```

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -pl . -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/lifepilot/agent/task/proactive/ProactiveAutoConfiguration.java
git commit -m "feat(proactive): Phase 2 自动配置 — 意图记忆 + 三个行为插件注册"
```

---

## Task 8: Regression Verification

- [ ] **Step 1: Run all task-related tests**

Run: `mvn test -Dtest="com.lifepilot.agent.task.**" -pl . -q`
Expected: All pass (minus pre-existing DefaultReminderMessageGenerator failure)

- [ ] **Step 2: Fix any compilation or test issues**

- [ ] **Step 3: Commit any fixes**

---

## Verification Checklist

- [ ] **意图记忆生效**: `IntentRepository_集成测试` + `IntentExtractor_单元测试` + `IntentMemoryService_单元测试` 通过
- [ ] **主动追问生效**: `FollowUpBehavior_单元测试` 验证 detect/reason 流程
- [ ] **洞察推送生效**: `InsightBehavior_单元测试` 验证 detect/reason 流程
- [ ] **剪贴板迁入**: `ClipboardBehavior_单元测试` 验证 detect/reason 流程 + buffer drain
- [ ] **V4 迁移**: Flyway 启动时自动执行
- [ ] **新插件被引擎发现**: ProactiveAutoConfiguration 注册 3 个 ProactiveBehavior bean
- [ ] **现有提醒不退化**: 所有 `Reminder*` 测试通过
