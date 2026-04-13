# Proactive Engine Phase 1: Framework Refactoring — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor the proactive reminder engine from a monolithic reminder-only service into a pluggable proactive intelligence framework, with three-level demand detection, unified decision gate, four-level delivery, and a new "queue" delivery channel.

**Architecture:** Adapter pattern — create a new `proactive` framework package alongside the existing `reminder` package. `ReminderBehavior` adapts existing components (signal collector, candidate detector, scoring model, message generator) to the new `ProactiveBehavior` interface. `ProactiveEngine` orchestrates plugins through a three-level detection pipeline (SILENT/FAST/FULL), passes approved actions through `DecisionGate` (global hard/soft constraints), and delivers via `DeliveryEngine` (four-level: Silent/Queue/Notify/Interrupt). Existing reminder code is NOT modified — only wrapped.

**Tech Stack:** Java 22, Spring Boot, SQLite (Flyway), JUnit 5, Mockito

**Design Spec:** `docs/superpowers/specs/2026-04-13-proactive-engine-evolution-design.md`

---

## File Structure

### New files — framework (`src/main/java/com/lifepilot/agent/task/proactive/`)

| File | Responsibility |
|------|---------------|
| `ProactiveBehavior.java` | Plugin interface: `detect()` / `reason()` / `execute()` / `onDelivered()` |
| `ProactiveCandidate.java` | Candidate record from `detect()` — score, topic, plugin-opaque detail |
| `ProactiveAction.java` | Action record from `reason()` — content, suggested delivery level |
| `ContextPacket.java` | Framework-level heartbeat context with change flags for Gate 1 |
| `DetectionLevel.java` | Enum: `SILENT`, `FAST`, `FULL` |
| `DeliveryLevel.java` | Enum: `SILENT`, `QUEUE`, `NOTIFY`, `INTERRUPT` |
| `DeliveryResult.java` | Delivery outcome record (notificationId, level, timestamp) |
| `DecisionGate.java` | Global hard/soft constraint checks + delivery level downgrade |
| `DeliveryEngine.java` | Four-level delivery router (log / queue / notification / interrupt) |
| `QueuedActionRecord.java` | Persistence record for QUEUE delivery level |
| `QueuedActionRepository.java` | SQLite persistence for queued actions |
| `ProactiveEngine.java` | Three-level detection orchestrator — the main heartbeat entry point |
| `ProactiveAutoConfiguration.java` | Spring auto-configuration for framework beans |

### New files — reminder adapter (`src/main/java/com/lifepilot/agent/task/reminder/`)

| File | Responsibility |
|------|---------------|
| `ReminderBehavior.java` | Adapts existing reminder components to `ProactiveBehavior` interface |
| `ReminderCandidateDetail.java` | Plugin-specific detail attached to `ProactiveCandidate` |

### New files — migration

| File | Responsibility |
|------|---------------|
| `src/main/resources/db/migration/V3__proactive_queued_actions.sql` | Queue table for QUEUE delivery level |

### Modified files

| File | Change |
|------|--------|
| `HeartbeatRunner.java` | Add `ProactiveEngine` dependency, call `engine.heartbeat()` |
| `ReminderAutoConfiguration.java` | Register `ReminderBehavior` bean |

### New test files

| File | Tests |
|------|-------|
| `proactive/DecisionGate_单元测试.java` | Hard/soft constraints, delivery level assignment |
| `proactive/DeliveryEngine_单元测试.java` | Four-level routing |
| `proactive/ProactiveEngine_单元测试.java` | Three-level detection pipeline |
| `proactive/QueuedActionRepository_集成测试.java` | Queue CRUD |
| `reminder/ReminderBehavior_单元测试.java` | detect/reason adapter flow |

---

## Task 1: Framework Types and ProactiveBehavior Interface

**Files:**
- Create: `src/main/java/com/lifepilot/agent/task/proactive/DetectionLevel.java`
- Create: `src/main/java/com/lifepilot/agent/task/proactive/DeliveryLevel.java`
- Create: `src/main/java/com/lifepilot/agent/task/proactive/DeliveryResult.java`
- Create: `src/main/java/com/lifepilot/agent/task/proactive/ProactiveCandidate.java`
- Create: `src/main/java/com/lifepilot/agent/task/proactive/ProactiveAction.java`
- Create: `src/main/java/com/lifepilot/agent/task/proactive/ContextPacket.java`
- Create: `src/main/java/com/lifepilot/agent/task/proactive/ProactiveBehavior.java`

- [ ] **Step 1: Create enums — DetectionLevel and DeliveryLevel**

```java
// DetectionLevel.java
package com.lifepilot.agent.task.proactive;

/**
 * 三级需求检测级别。
 *
 * <p>SILENT: 无变化，跳过（~80%）。
 * FAST: 有变化但无高分候选，轻量响应（~15%）。
 * FULL: 高分候选进入完整推理（~5%）。</p>
 */
public enum DetectionLevel {
    SILENT,
    FAST,
    FULL
}
```

```java
// DeliveryLevel.java
package com.lifepilot.agent.task.proactive;

/**
 * 四级投递阶梯。
 *
 * <p>SILENT: 仅记录到决策日志。
 * QUEUE: 存储，下次用户主动对话时展示。
 * NOTIFY: 浮窗气泡/桌面通知。
 * INTERRUPT: 主动消息/直接发到渠道。</p>
 */
public enum DeliveryLevel {
    SILENT,
    QUEUE,
    NOTIFY,
    INTERRUPT
}
```

- [ ] **Step 2: Create DeliveryResult record**

```java
// DeliveryResult.java
package com.lifepilot.agent.task.proactive;

import org.springframework.lang.Nullable;
import java.time.Instant;
import java.util.Objects;

/**
 * 投递结果。
 *
 * @param notificationId 通知 ID（NOTIFY/INTERRUPT 时非空）
 * @param level          实际投递级别
 * @param deliveredAt    投递时间
 */
public record DeliveryResult(
        @Nullable String notificationId,
        DeliveryLevel level,
        Instant deliveredAt
) {
    public DeliveryResult {
        Objects.requireNonNull(level, "level 不能为空");
        deliveredAt = Objects.requireNonNullElse(deliveredAt, Instant.now());
    }
}
```

- [ ] **Step 3: Create ProactiveCandidate record**

```java
// ProactiveCandidate.java
package com.lifepilot.agent.task.proactive;

import java.util.Objects;

/**
 * 主动候选 — 由行为插件的 detect() 产出。
 *
 * @param id           唯一标识
 * @param behaviorName 产出此候选的行为插件名称
 * @param topicKey     主题键
 * @param title        主题标题
 * @param score        综合评分 [0, 1]
 * @param rationale    候选理由（面向日志/调试）
 * @param detail       插件专属数据（框架不解读，插件在 reason 阶段取回使用）
 */
public record ProactiveCandidate(
        String id,
        String behaviorName,
        String topicKey,
        String title,
        float score,
        String rationale,
        Object detail
) {
    public ProactiveCandidate {
        Objects.requireNonNull(id, "id 不能为空");
        Objects.requireNonNull(behaviorName, "behaviorName 不能为空");
        Objects.requireNonNull(topicKey, "topicKey 不能为空");
        Objects.requireNonNull(title, "title 不能为空");
        rationale = Objects.requireNonNullElse(rationale, "");
        score = Math.max(0f, Math.min(1f, score));
    }
}
```

- [ ] **Step 4: Create ProactiveAction record**

```java
// ProactiveAction.java
package com.lifepilot.agent.task.proactive;

import java.util.Objects;

/**
 * 主动行为动作 — 由行为插件的 reason() 产出。
 *
 * @param candidate      源候选
 * @param content        生成的通知/消息内容
 * @param suggestedLevel 插件建议的投递级别（DecisionGate 可降级但不升级）
 * @param detail         插件专属投递数据（元信息、策略追踪等）
 */
public record ProactiveAction(
        ProactiveCandidate candidate,
        String content,
        DeliveryLevel suggestedLevel,
        Object detail
) {
    public ProactiveAction {
        Objects.requireNonNull(candidate, "candidate 不能为空");
        Objects.requireNonNull(content, "content 不能为空");
        Objects.requireNonNull(suggestedLevel, "suggestedLevel 不能为空");
    }
}
```

- [ ] **Step 5: Create ContextPacket record**

```java
// ContextPacket.java
package com.lifepilot.agent.task.proactive;

import com.lifepilot.agent.task.reminder.ReminderFocusState;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 心跳上下文包 — 框架级上下文，供三级检测和行为插件使用。
 *
 * @param userId               目标用户
 * @param now                  当前时间
 * @param zoneId               时区
 * @param quietHoursStart      安静时段开始（可为 null）
 * @param quietHoursEnd        安静时段结束（可为 null）
 * @param actionsSentToday     今日已发送的主动行为数
 * @param dailyMaxActions      每日最大主动行为数
 * @param focusState           桌面焦点状态（可为 null）
 * @param lastHeartbeatAt      上次心跳时间（首次运行为 null）
 * @param heartbeatIntervalMin 心跳间隔（分钟）
 */
public record ContextPacket(
        String userId,
        Instant now,
        ZoneId zoneId,
        @Nullable LocalTime quietHoursStart,
        @Nullable LocalTime quietHoursEnd,
        int actionsSentToday,
        int dailyMaxActions,
        @Nullable ReminderFocusState focusState,
        @Nullable Instant lastHeartbeatAt,
        int heartbeatIntervalMin
) {

    private static final Pattern IDE_TITLE_PATTERN = Pattern.compile(
            "VS Code|Visual Studio Code|IntelliJ|WebStorm|PyCharm|CLion|GoLand|Rider|RustRover|Cursor|Zed|Neovim",
            Pattern.CASE_INSENSITIVE
    );

    public ContextPacket {
        Objects.requireNonNull(userId, "userId 不能为空");
        Objects.requireNonNull(now, "now 不能为空");
        Objects.requireNonNull(zoneId, "zoneId 不能为空");
        actionsSentToday = Math.max(0, actionsSentToday);
        dailyMaxActions = Math.max(1, dailyMaxActions);
        heartbeatIntervalMin = Math.max(1, heartbeatIntervalMin);
    }

    /**
     * Gate 1 判断：自上次心跳以来是否有变化。
     *
     * <p>Phase 1 策略（保守）：如果用户空闲超过心跳间隔，判定无变化。
     * 无焦点状态信息时默认有变化（无法判断用户是否在线）。</p>
     */
    public boolean hasChangeSinceLastHeartbeat() {
        if (lastHeartbeatAt == null) {
            return true;
        }
        if (focusState != null && focusState.idleMinutes() >= heartbeatIntervalMin) {
            return false;
        }
        return true;
    }

    /** 是否在安静时段内。 */
    public boolean isWithinQuietHours() {
        if (quietHoursStart == null || quietHoursEnd == null) {
            return false;
        }
        LocalTime localNow = LocalTime.ofInstant(now, zoneId);
        if (quietHoursStart.isBefore(quietHoursEnd)) {
            return !localNow.isBefore(quietHoursStart) && localNow.isBefore(quietHoursEnd);
        }
        return !localNow.isBefore(quietHoursStart) || localNow.isBefore(quietHoursEnd);
    }

    /** 焦点应用是否全屏。 */
    public boolean isFullscreen() {
        return focusState != null && focusState.fullscreen();
    }

    /** 用户是否正在 IDE 编码。 */
    public boolean isFocusedCoding() {
        return focusState != null && IDE_TITLE_PATTERN.matcher(focusState.focusTitle()).find();
    }

    /** 今日剩余投递额度。 */
    public int remainingSlots() {
        return Math.max(0, dailyMaxActions - actionsSentToday);
    }
}
```

- [ ] **Step 6: Create ProactiveBehavior interface**

```java
// ProactiveBehavior.java
package com.lifepilot.agent.task.proactive;

import java.util.List;

/**
 * 主动行为插件接口。
 *
 * <p>每种主动行为（提醒、信息补充、洞察推送等）实现此接口，
 * 由 {@link ProactiveEngine} 在心跳中调度。</p>
 *
 * <p>生命周期：detect（快速检测候选）→ reason（精细推理生成内容）→
 * execute（自主度 C 时代行）→ onDelivered（投递后回调）。</p>
 */
public interface ProactiveBehavior {

    /** 插件名称，用于日志和跨插件去重。 */
    String name();

    /**
     * 快速检测：是否有候选？返回带粗略分数的候选列表。
     *
     * <p>在 Gate 2 调用，不应调用 LLM。应尽量轻量。
     * 插件负责自身的主题级过滤（静音、冷却），框架负责全局过滤。</p>
     *
     * @param ctx 心跳上下文
     * @return 候选列表（可为空）
     */
    List<ProactiveCandidate> detect(ContextPacket ctx);

    /**
     * 精细推理：为入选候选生成具体内容。
     *
     * <p>仅在 Gate 3（FULL）时调用，允许调用 LLM。
     * 接收该插件的所有入选候选，可做跨候选合成。</p>
     *
     * @param candidates 入选的候选列表
     * @param ctx        心跳上下文
     * @return 生成的主动行为动作列表
     */
    List<ProactiveAction> reason(List<ProactiveCandidate> candidates, ContextPacket ctx);

    /**
     * 执行动作 — 仅在自主度为 C（代行）时调用。
     *
     * <p>Phase 1 不使用，预留接口。</p>
     */
    default void execute(ProactiveAction action) {}

    /**
     * 投递后回调 — 插件可在此做持久化、训练样本记录等。
     */
    default void onDelivered(ProactiveAction action, DeliveryResult result) {}
}
```

- [ ] **Step 7: Verify compilation**

Run: `mvn compile -pl . -q`

Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/lifepilot/agent/task/proactive/
git commit -m "feat(proactive): 主动引擎框架类型定义 — ProactiveBehavior 接口 + 数据模型

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: DecisionGate

**Files:**
- Create: `src/main/java/com/lifepilot/agent/task/proactive/DecisionGate.java`
- Test: `src/test/java/com/lifepilot/agent/task/proactive/DecisionGate_单元测试.java`

- [ ] **Step 1: Write the failing test**

```java
package com.lifepilot.agent.task.proactive;

import com.lifepilot.agent.task.reminder.ReminderFocusState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionGate_单元测试 {

    private static final String USER = "u1";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    // ── 硬边界 ──

    @Test
    void 安静时段内全部过滤() {
        // 23:30 在安静时段 23:00-08:00 内
        var ctx = ctx(Instant.parse("2026-04-14T15:30:00Z"), // UTC 15:30 = CST 23:30
                LocalTime.of(23, 0), LocalTime.of(8, 0), 0, 5, null);
        var actions = List.of(action(0.8f));

        var result = new DecisionGate().evaluate(actions, ctx);

        assertThat(result).isEmpty();
    }

    @Test
    void 全屏模式全部过滤() {
        var focus = new ReminderFocusState("game.exe", "Game", true, 0, Instant.now());
        var ctx = ctx(Instant.parse("2026-04-14T06:00:00Z"), // CST 14:00
                null, null, 0, 5, focus);
        var actions = List.of(action(0.8f));

        var result = new DecisionGate().evaluate(actions, ctx);

        assertThat(result).isEmpty();
    }

    @Test
    void 每日上限已满时过滤() {
        var ctx = ctx(Instant.parse("2026-04-14T06:00:00Z"),
                null, null, 5, 5, null);  // sent=5, max=5
        var actions = List.of(action(0.8f));

        var result = new DecisionGate().evaluate(actions, ctx);

        assertThat(result).isEmpty();
    }

    // ── 软约束 ──

    @Test
    void 编码中INTERRUPT降级为NOTIFY() {
        var focus = new ReminderFocusState("code.exe", "VS Code - project", false, 0, Instant.now());
        var ctx = ctx(Instant.parse("2026-04-14T06:00:00Z"),
                null, null, 0, 5, focus);
        var actions = List.of(action(0.8f, DeliveryLevel.INTERRUPT));

        var result = new DecisionGate().evaluate(actions, ctx);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().level()).isEqualTo(DeliveryLevel.NOTIFY);
    }

    @Test
    void 多个候选只保留最高分() {
        var ctx = ctx(Instant.parse("2026-04-14T06:00:00Z"),
                null, null, 0, 5, null);
        var actions = List.of(
                action(0.5f, DeliveryLevel.NOTIFY),
                action(0.8f, DeliveryLevel.INTERRUPT),
                action(0.6f, DeliveryLevel.NOTIFY)
        );

        var result = new DecisionGate().evaluate(actions, ctx);

        // 额度 5，全部通过但按分数排序
        assertThat(result.getFirst().action().candidate().score()).isEqualTo(0.8f);
    }

    @Test
    void 超出每日额度截断低分() {
        var ctx = ctx(Instant.parse("2026-04-14T06:00:00Z"),
                null, null, 4, 5, null);  // 剩余 1 个额度
        var actions = List.of(
                action(0.5f, DeliveryLevel.NOTIFY),
                action(0.8f, DeliveryLevel.INTERRUPT)
        );

        var result = new DecisionGate().evaluate(actions, ctx);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().action().candidate().score()).isEqualTo(0.8f);
    }

    // ── 投递级别分配 ──

    @Test
    void 分数到投递级别映射() {
        assertThat(DecisionGate.scoreToLevel(0.2f)).isEqualTo(DeliveryLevel.SILENT);
        assertThat(DecisionGate.scoreToLevel(0.4f)).isEqualTo(DeliveryLevel.QUEUE);
        assertThat(DecisionGate.scoreToLevel(0.6f)).isEqualTo(DeliveryLevel.NOTIFY);
        assertThat(DecisionGate.scoreToLevel(0.8f)).isEqualTo(DeliveryLevel.INTERRUPT);
    }

    // ── helpers ──

    private ContextPacket ctx(Instant now, LocalTime qStart, LocalTime qEnd,
                              int sent, int max, ReminderFocusState focus) {
        return new ContextPacket(USER, now, ZONE, qStart, qEnd, sent, max, focus, null, 30);
    }

    private ProactiveAction action(float score) {
        return action(score, DecisionGate.scoreToLevel(score));
    }

    private ProactiveAction action(float score, DeliveryLevel level) {
        var candidate = new ProactiveCandidate(
                "c-" + score, "reminder", "topic-" + score,
                "Title", score, "test", null);
        return new ProactiveAction(candidate, "内容", level, null);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest="DecisionGate_单元测试" -pl . -q`

Expected: FAIL — `DecisionGate` class not found

- [ ] **Step 3: Implement DecisionGate**

```java
package com.lifepilot.agent.task.proactive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 统一决策门控 — 防过度打扰的最后防线。
 *
 * <p>负责全局维度的约束检查和投递级别调整，
 * 插件级约束（话题静音、冷却等）由插件自行在 detect 阶段过滤。</p>
 */
public class DecisionGate {

    private static final Logger log = LoggerFactory.getLogger(DecisionGate.class);

    /** 分数 → 投递级别映射。 */
    public static DeliveryLevel scoreToLevel(float score) {
        if (score >= 0.7f) return DeliveryLevel.INTERRUPT;
        if (score >= 0.5f) return DeliveryLevel.NOTIFY;
        if (score >= 0.3f) return DeliveryLevel.QUEUE;
        return DeliveryLevel.SILENT;
    }

    /**
     * 评估候选动作列表，返回通过门控的 (动作, 投递级别) 对。
     *
     * @param actions 所有插件 reason() 产出的动作
     * @param ctx     心跳上下文
     * @return 通过门控的评估结果列表，按分数降序排列
     */
    public List<GatedAction> evaluate(List<ProactiveAction> actions, ContextPacket ctx) {
        if (actions == null || actions.isEmpty()) {
            return List.of();
        }

        // ── 硬边界（一票否决） ──
        if (ctx.isWithinQuietHours()) {
            log.debug("决策门控: 安静时段，全部跳过");
            return List.of();
        }
        if (ctx.isFullscreen()) {
            log.debug("决策门控: 全屏模式，全部跳过");
            return List.of();
        }
        int remaining = ctx.remainingSlots();
        if (remaining <= 0) {
            log.debug("决策门控: 每日上限已满，全部跳过");
            return List.of();
        }

        // ── 按分数降序排列 ──
        var sorted = actions.stream()
                .sorted(Comparator.comparingDouble((ProactiveAction a) -> a.candidate().score()).reversed())
                .toList();

        // ── 软约束 + 投递级别分配 ──
        var result = new ArrayList<GatedAction>();
        for (var action : sorted) {
            if (result.size() >= remaining) {
                log.debug("决策门控: 额度用尽，跳过 topic={}", action.candidate().topicKey());
                break;
            }
            DeliveryLevel level = action.suggestedLevel();

            // 编码中降级: INTERRUPT → NOTIFY
            if (ctx.isFocusedCoding() && level == DeliveryLevel.INTERRUPT) {
                level = DeliveryLevel.NOTIFY;
                log.debug("决策门控: 编码中，INTERRUPT→NOTIFY topic={}", action.candidate().topicKey());
            }

            // SILENT 级别不占额度，仅记录
            if (level == DeliveryLevel.SILENT) {
                log.debug("决策门控: SILENT topic={}", action.candidate().topicKey());
                continue;
            }

            result.add(new GatedAction(action, level));
        }
        return List.copyOf(result);
    }

    /**
     * 门控通过的动作 + 最终投递级别。
     */
    public record GatedAction(ProactiveAction action, DeliveryLevel level) {}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest="DecisionGate_单元测试" -pl . -q`

Expected: PASS (all 7 tests green)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/lifepilot/agent/task/proactive/DecisionGate.java \
        src/test/java/com/lifepilot/agent/task/proactive/DecisionGate_单元测试.java
git commit -m "feat(proactive): DecisionGate 统一决策门控 — 硬边界 + 软约束 + 四级投递

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Queued Action Persistence

**Files:**
- Create: `src/main/resources/db/migration/V3__proactive_queued_actions.sql`
- Create: `src/main/java/com/lifepilot/agent/task/proactive/QueuedActionRecord.java`
- Create: `src/main/java/com/lifepilot/agent/task/proactive/QueuedActionRepository.java`
- Test: `src/test/java/com/lifepilot/agent/task/proactive/QueuedActionRepository_集成测试.java`

- [ ] **Step 1: Write the Flyway migration**

```sql
-- V3__proactive_queued_actions.sql
-- 主动引擎排队动作表 — 存储 QUEUE 级别的动作，下次用户对话时展示

CREATE TABLE IF NOT EXISTS proactive_queued_actions (
    id          TEXT    NOT NULL PRIMARY KEY,
    user_id     TEXT    NOT NULL,
    behavior    TEXT    NOT NULL,
    topic_key   TEXT    NOT NULL,
    title       TEXT    NOT NULL,
    content     TEXT    NOT NULL,
    score       REAL    NOT NULL,
    metadata    TEXT,
    shown       INTEGER NOT NULL DEFAULT 0,
    created_at  TEXT    NOT NULL,
    shown_at    TEXT
);

CREATE INDEX IF NOT EXISTS idx_proactive_queued_actions_user_shown
    ON proactive_queued_actions (user_id, shown, created_at DESC);
```

- [ ] **Step 2: Create QueuedActionRecord**

```java
package com.lifepilot.agent.task.proactive;

import org.springframework.lang.Nullable;
import java.time.Instant;
import java.util.Objects;

/**
 * 排队动作记录。
 *
 * @param id        唯一标识
 * @param userId    用户 ID
 * @param behavior  产出此动作的行为插件名称
 * @param topicKey  主题键
 * @param title     主题标题
 * @param content   消息内容
 * @param score     候选分数
 * @param metadata  扩展元数据 JSON
 * @param shown     是否已展示
 * @param createdAt 创建时间
 * @param shownAt   展示时间
 */
public record QueuedActionRecord(
        String id,
        String userId,
        String behavior,
        String topicKey,
        String title,
        String content,
        float score,
        @Nullable String metadata,
        boolean shown,
        Instant createdAt,
        @Nullable Instant shownAt
) {
    public QueuedActionRecord {
        Objects.requireNonNull(id, "id 不能为空");
        Objects.requireNonNull(userId, "userId 不能为空");
        Objects.requireNonNull(behavior, "behavior 不能为空");
        Objects.requireNonNull(topicKey, "topicKey 不能为空");
        Objects.requireNonNull(title, "title 不能为空");
        Objects.requireNonNull(content, "content 不能为空");
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
    }
}
```

- [ ] **Step 3: Write the failing repository test**

```java
package com.lifepilot.agent.task.proactive;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class QueuedActionRepository_集成测试 {

    @Autowired
    JdbcTemplate jdbc;

    QueuedActionRepository repo;

    @BeforeEach
    void setUp() {
        repo = new QueuedActionRepository(jdbc);
    }

    @Test
    void 保存并查询未展示的排队动作() {
        var record = new QueuedActionRecord(
                UUID.randomUUID().toString(), "u1", "reminder",
                "topic-1", "标题", "内容", 0.45f, null,
                false, Instant.now(), null);

        repo.save(record);

        var pending = repo.findPendingByUserId("u1", 10);
        assertThat(pending).hasSize(1);
        assertThat(pending.getFirst().topicKey()).isEqualTo("topic-1");
    }

    @Test
    void 标记为已展示后不再返回() {
        var id = UUID.randomUUID().toString();
        var record = new QueuedActionRecord(
                id, "u1", "reminder", "topic-2", "标题", "内容",
                0.4f, null, false, Instant.now(), null);

        repo.save(record);
        repo.markShown(id);

        var pending = repo.findPendingByUserId("u1", 10);
        assertThat(pending).isEmpty();
    }

    @Test
    void 清理过期排队动作() {
        var old = new QueuedActionRecord(
                UUID.randomUUID().toString(), "u1", "reminder",
                "topic-old", "旧标题", "旧内容", 0.35f, null,
                false, Instant.now().minusSeconds(8 * 86400), null);  // 8天前
        repo.save(old);

        int deleted = repo.deleteOlderThan(Instant.now().minusSeconds(7 * 86400));

        assertThat(deleted).isEqualTo(1);
        assertThat(repo.findPendingByUserId("u1", 10)).isEmpty();
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `mvn test -Dtest="QueuedActionRepository_集成测试" -pl . -q`

Expected: FAIL — `QueuedActionRepository` class not found

- [ ] **Step 5: Implement QueuedActionRepository**

```java
package com.lifepilot.agent.task.proactive;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;

/**
 * 排队动作仓储 — 存取 QUEUE 级别的主动行为动作。
 */
public class QueuedActionRepository {

    private final JdbcTemplate jdbc;

    public QueuedActionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<QueuedActionRecord> ROW_MAPPER = (rs, _) ->
            new QueuedActionRecord(
                    rs.getString("id"),
                    rs.getString("user_id"),
                    rs.getString("behavior"),
                    rs.getString("topic_key"),
                    rs.getString("title"),
                    rs.getString("content"),
                    rs.getFloat("score"),
                    rs.getString("metadata"),
                    rs.getInt("shown") == 1,
                    Instant.parse(rs.getString("created_at")),
                    rs.getString("shown_at") != null ? Instant.parse(rs.getString("shown_at")) : null
            );

    /** 保存排队动作。 */
    public void save(QueuedActionRecord record) {
        jdbc.update("""
                INSERT INTO proactive_queued_actions
                    (id, user_id, behavior, topic_key, title, content, score, metadata, shown, created_at, shown_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                record.id(), record.userId(), record.behavior(),
                record.topicKey(), record.title(), record.content(),
                record.score(), record.metadata(),
                record.shown() ? 1 : 0,
                record.createdAt().toString(),
                record.shownAt() != null ? record.shownAt().toString() : null);
    }

    /** 查询用户未展示的排队动作（按分数降序）。 */
    public List<QueuedActionRecord> findPendingByUserId(String userId, int limit) {
        return jdbc.query("""
                SELECT * FROM proactive_queued_actions
                WHERE user_id = ? AND shown = 0
                ORDER BY score DESC, created_at DESC
                LIMIT ?
                """, ROW_MAPPER, userId, limit);
    }

    /** 标记为已展示。 */
    public void markShown(String id) {
        jdbc.update("""
                UPDATE proactive_queued_actions
                SET shown = 1, shown_at = ?
                WHERE id = ?
                """, Instant.now().toString(), id);
    }

    /** 清理过期排队动作（超过指定时间的记录）。 */
    public int deleteOlderThan(Instant before) {
        return jdbc.update("""
                DELETE FROM proactive_queued_actions
                WHERE created_at < ?
                """, before.toString());
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn test -Dtest="QueuedActionRepository_集成测试" -pl . -q`

Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V3__proactive_queued_actions.sql \
        src/main/java/com/lifepilot/agent/task/proactive/QueuedActionRecord.java \
        src/main/java/com/lifepilot/agent/task/proactive/QueuedActionRepository.java \
        src/test/java/com/lifepilot/agent/task/proactive/QueuedActionRepository_集成测试.java
git commit -m "feat(proactive): 排队动作持久化 — Flyway V3 + QueuedActionRepository

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: DeliveryEngine

**Files:**
- Create: `src/main/java/com/lifepilot/agent/task/proactive/DeliveryEngine.java`
- Test: `src/test/java/com/lifepilot/agent/task/proactive/DeliveryEngine_单元测试.java`

- [ ] **Step 1: Write the failing test**

```java
package com.lifepilot.agent.task.proactive;

import com.lifepilot.interaction.model.ResponseContent;
import com.lifepilot.notification.NotificationRequest;
import com.lifepilot.notification.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DeliveryEngine_单元测试 {

    NotificationService notificationService;
    QueuedActionRepository queuedActionRepository;
    DeliveryEngine engine;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        queuedActionRepository = mock(QueuedActionRepository.class);
        engine = new DeliveryEngine(notificationService, queuedActionRepository);
    }

    @Test
    void SILENT级别仅记录不投递() {
        var action = testAction(0.2f);

        var result = engine.deliver(action, DeliveryLevel.SILENT, "u1");

        assertThat(result.level()).isEqualTo(DeliveryLevel.SILENT);
        assertThat(result.notificationId()).isNull();
        verifyNoInteractions(notificationService);
        verifyNoInteractions(queuedActionRepository);
    }

    @Test
    void QUEUE级别存入排队表() {
        var action = testAction(0.4f);

        var result = engine.deliver(action, DeliveryLevel.QUEUE, "u1");

        assertThat(result.level()).isEqualTo(DeliveryLevel.QUEUE);
        assertThat(result.notificationId()).isNull();
        verify(queuedActionRepository).save(any(QueuedActionRecord.class));
        verifyNoInteractions(notificationService);
    }

    @Test
    void NOTIFY级别发送通知() {
        when(notificationService.send(any())).thenReturn(List.of("nid-1"));
        var action = testAction(0.6f);

        var result = engine.deliver(action, DeliveryLevel.NOTIFY, "u1");

        assertThat(result.level()).isEqualTo(DeliveryLevel.NOTIFY);
        assertThat(result.notificationId()).isEqualTo("nid-1");
        verify(notificationService).send(any(NotificationRequest.class));
    }

    @Test
    void INTERRUPT级别发送通知带打断标记() {
        when(notificationService.send(any())).thenReturn(List.of("nid-2"));
        var action = testAction(0.8f);

        var result = engine.deliver(action, DeliveryLevel.INTERRUPT, "u1");

        assertThat(result.level()).isEqualTo(DeliveryLevel.INTERRUPT);
        assertThat(result.notificationId()).isEqualTo("nid-2");
        var captor = org.mockito.ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationService).send(captor.capture());
        assertThat(captor.getValue().metadata()).containsEntry("deliveryLevel", "INTERRUPT");
    }

    private ProactiveAction testAction(float score) {
        var candidate = new ProactiveCandidate(
                "c1", "reminder", "topic-1", "标题", score, "理由", null);
        return new ProactiveAction(candidate, "测试内容", DecisionGate.scoreToLevel(score), null);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest="DeliveryEngine_单元测试" -pl . -q`

Expected: FAIL — `DeliveryEngine` class not found

- [ ] **Step 3: Implement DeliveryEngine**

```java
package com.lifepilot.agent.task.proactive;

import com.lifepilot.interaction.model.ResponseContent;
import com.lifepilot.notification.NotificationRequest;
import com.lifepilot.notification.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * 四级投递引擎。
 *
 * <p>根据 {@link DeliveryLevel} 将主动行为动作路由到对应投递通道：
 * SILENT（仅记录）→ QUEUE（排队）→ NOTIFY（通知）→ INTERRUPT（打断）。</p>
 */
public class DeliveryEngine {

    private static final Logger log = LoggerFactory.getLogger(DeliveryEngine.class);
    private static final String PROACTIVE_TYPE = "proactive_action";

    private final NotificationService notificationService;
    private final QueuedActionRepository queuedActionRepository;

    public DeliveryEngine(NotificationService notificationService,
                          QueuedActionRepository queuedActionRepository) {
        this.notificationService = notificationService;
        this.queuedActionRepository = queuedActionRepository;
    }

    /**
     * 按指定级别投递主动行为动作。
     */
    public DeliveryResult deliver(ProactiveAction action, DeliveryLevel level, String userId) {
        return switch (level) {
            case SILENT -> deliverSilent(action);
            case QUEUE -> deliverQueue(action, userId);
            case NOTIFY -> deliverNotification(action, userId, false);
            case INTERRUPT -> deliverNotification(action, userId, true);
        };
    }

    private DeliveryResult deliverSilent(ProactiveAction action) {
        log.debug("投递引擎: SILENT behavior={} topic={}",
                action.candidate().behaviorName(), action.candidate().topicKey());
        return new DeliveryResult(null, DeliveryLevel.SILENT, Instant.now());
    }

    private DeliveryResult deliverQueue(ProactiveAction action, String userId) {
        var record = new QueuedActionRecord(
                UUID.randomUUID().toString(),
                userId,
                action.candidate().behaviorName(),
                action.candidate().topicKey(),
                action.candidate().title(),
                action.content(),
                action.candidate().score(),
                null,
                false,
                Instant.now(),
                null
        );
        queuedActionRepository.save(record);
        log.debug("投递引擎: QUEUE behavior={} topic={}",
                action.candidate().behaviorName(), action.candidate().topicKey());
        return new DeliveryResult(null, DeliveryLevel.QUEUE, Instant.now());
    }

    private DeliveryResult deliverNotification(ProactiveAction action, String userId, boolean interrupt) {
        var candidate = action.candidate();
        var metadata = new LinkedHashMap<String, String>();
        metadata.put("behaviorName", candidate.behaviorName());
        metadata.put("topicKey", candidate.topicKey());
        metadata.put("score", String.format("%.3f", candidate.score()));
        metadata.put("deliveryLevel", interrupt ? "INTERRUPT" : "NOTIFY");

        String title = interrupt ? "【主动提醒】" : "【轻提醒】";
        String text = title + "\n" + action.content();

        List<String> ids = notificationService.send(new NotificationRequest(
                userId,
                new ResponseContent.TextContent(text),
                "WEB",
                PROACTIVE_TYPE,
                metadata
        ));

        String notificationId = ids.isEmpty() ? null : ids.getFirst();
        log.debug("投递引擎: {} behavior={} topic={} notificationId={}",
                interrupt ? "INTERRUPT" : "NOTIFY",
                candidate.behaviorName(), candidate.topicKey(), notificationId);
        return new DeliveryResult(notificationId, interrupt ? DeliveryLevel.INTERRUPT : DeliveryLevel.NOTIFY,
                Instant.now());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest="DeliveryEngine_单元测试" -pl . -q`

Expected: PASS (all 4 tests green)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/lifepilot/agent/task/proactive/DeliveryEngine.java \
        src/test/java/com/lifepilot/agent/task/proactive/DeliveryEngine_单元测试.java
git commit -m "feat(proactive): DeliveryEngine 四级投递引擎 — Silent/Queue/Notify/Interrupt

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: ReminderBehavior Adapter

**Files:**
- Create: `src/main/java/com/lifepilot/agent/task/reminder/ReminderCandidateDetail.java`
- Create: `src/main/java/com/lifepilot/agent/task/reminder/ReminderBehavior.java`
- Test: `src/test/java/com/lifepilot/agent/task/reminder/ReminderBehavior_单元测试.java`

- [ ] **Step 1: Create ReminderCandidateDetail record**

```java
package com.lifepilot.agent.task.reminder;

/**
 * ReminderBehavior 专属候选详情 — 存储在 ProactiveCandidate.detail 中，
 * 由 detect() 阶段产出，reason() 阶段取回使用。
 *
 * @param snapshot    主题快照（reason 阶段需要用于消息生成）
 * @param candidate   原始提醒候选（保留完整评分细节）
 * @param policyConfig 策略配置（reason 阶段需要用于学习策略）
 */
public record ReminderCandidateDetail(
        ReminderTopicSnapshot snapshot,
        ReminderCandidate candidate,
        ReminderPolicyConfig policyConfig
) {}
```

- [ ] **Step 2: Write the failing test**

```java
package com.lifepilot.agent.task.reminder;

import com.lifepilot.agent.task.proactive.ContextPacket;
import com.lifepilot.agent.task.proactive.DeliveryLevel;
import com.lifepilot.agent.task.proactive.ProactiveCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReminderBehavior_单元测试 {

    ReminderSignalCollector signalCollector;
    ReminderCandidateDetector candidateDetector;
    ReminderMessageGenerator messageGenerator;
    ReminderBehavior behavior;

    @BeforeEach
    void setUp() {
        signalCollector = mock(ReminderSignalCollector.class);
        candidateDetector = mock(ReminderCandidateDetector.class);
        messageGenerator = mock(ReminderMessageGenerator.class);
        behavior = new ReminderBehavior(signalCollector, candidateDetector, messageGenerator);
    }

    @Test
    void 插件名称为reminder() {
        assertThat(behavior.name()).isEqualTo("reminder");
    }

    @Test
    void detect返回信号采集和候选检测的结果() {
        var signal = new ReminderSignal("s1", ReminderSignalKind.DEADLINE,
                0.8f, 0.7f, 2, Instant.now(), Instant.now().plusSeconds(3600),
                null, null, null, 0f, true, false, "deadline");
        var snapshot = new ReminderTopicSnapshot("topic-1", "任务截止",
                List.of(signal), ReminderTopicState.empty());
        when(signalCollector.collect(eq("u1"), any())).thenReturn(List.of(snapshot));

        var candidate = new ReminderCandidate("topic-1", "任务截止",
                ReminderCandidateType.DUE_SOON, "s1",
                0.7f, 0.8f, 0.9f, 0.5f, 1.0f, 0f, 0f, 0.75f,
                null, "截止临近");
        when(candidateDetector.detect(eq(snapshot), any(), any())).thenReturn(List.of(candidate));

        var ctx = testCtx();
        var result = behavior.detect(ctx);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().behaviorName()).isEqualTo("reminder");
        assertThat(result.getFirst().score()).isEqualTo(0.75f);
        assertThat(result.getFirst().detail()).isInstanceOf(ReminderCandidateDetail.class);
    }

    @Test
    void detect过滤已静音主题() {
        var state = new ReminderTopicState(null, 0, 0, 0, 0, 0, 0, 0f, true);
        var snapshot = new ReminderTopicSnapshot("muted-topic", "已静音", List.of(), state);
        when(signalCollector.collect(eq("u1"), any())).thenReturn(List.of(snapshot));

        var result = behavior.detect(testCtx());

        assertThat(result).isEmpty();
        verifyNoInteractions(candidateDetector);
    }

    @Test
    void reason生成消息并返回ProactiveAction() {
        var signal = new ReminderSignal("s1", ReminderSignalKind.DEADLINE,
                0.8f, 0.7f, 2, Instant.now(), Instant.now().plusSeconds(3600),
                null, null, null, 0f, true, false, "deadline");
        var snapshot = new ReminderTopicSnapshot("topic-1", "任务截止",
                List.of(signal), ReminderTopicState.empty());
        var reminderCandidate = new ReminderCandidate("topic-1", "任务截止",
                ReminderCandidateType.DUE_SOON, "s1",
                0.7f, 0.8f, 0.9f, 0.5f, 1.0f, 0f, 0f, 0.75f,
                null, "截止临近");
        var detail = new ReminderCandidateDetail(snapshot, reminderCandidate, new ReminderPolicyConfig());
        var proactiveCandidate = new ProactiveCandidate(
                "c1", "reminder", "topic-1", "任务截止", 0.75f, "截止临近", detail);

        when(messageGenerator.generate(eq("u1"), any(), eq(snapshot), any()))
                .thenReturn(new ReminderMessage("关于「任务截止」，现在处理会更从容。", "fallback", null, null));

        var ctx = testCtx();
        var actions = behavior.reason(List.of(proactiveCandidate), ctx);

        assertThat(actions).hasSize(1);
        assertThat(actions.getFirst().content()).contains("任务截止");
        assertThat(actions.getFirst().suggestedLevel()).isEqualTo(DeliveryLevel.INTERRUPT);
    }

    private ContextPacket testCtx() {
        return new ContextPacket("u1", Instant.now(), ZoneId.of("Asia/Shanghai"),
                null, null, 0, 5, null, null, 30);
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn test -Dtest="ReminderBehavior_单元测试" -pl . -q`

Expected: FAIL — `ReminderBehavior` class not found

- [ ] **Step 4: Implement ReminderBehavior**

```java
package com.lifepilot.agent.task.reminder;

import com.lifepilot.agent.task.proactive.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 提醒行为插件 — 将现有提醒管线适配为 {@link ProactiveBehavior} 接口。
 *
 * <p>detect() 使用现有信号采集器和候选检测器。
 * reason() 使用现有消息生成器（可能调 LLM）。
 * 现有学习管线（Bandit、策略调优、回放）保持独立运行不变。</p>
 */
public class ReminderBehavior implements ProactiveBehavior {

    private static final Logger log = LoggerFactory.getLogger(ReminderBehavior.class);

    private final ReminderSignalCollector signalCollector;
    private final ReminderCandidateDetector candidateDetector;
    private final ReminderMessageGenerator messageGenerator;
    @Nullable
    private final ReminderOutcomeInferenceService outcomeInferenceService;

    /** 简化构造器（测试用）。 */
    public ReminderBehavior(ReminderSignalCollector signalCollector,
                            ReminderCandidateDetector candidateDetector,
                            ReminderMessageGenerator messageGenerator) {
        this(signalCollector, candidateDetector, messageGenerator, null);
    }

    public ReminderBehavior(ReminderSignalCollector signalCollector,
                            ReminderCandidateDetector candidateDetector,
                            ReminderMessageGenerator messageGenerator,
                            @Nullable ReminderOutcomeInferenceService outcomeInferenceService) {
        this.signalCollector = signalCollector;
        this.candidateDetector = candidateDetector;
        this.messageGenerator = messageGenerator;
        this.outcomeInferenceService = outcomeInferenceService;
    }

    @Override
    public String name() {
        return "reminder";
    }

    @Override
    public List<ProactiveCandidate> detect(ContextPacket ctx) {
        // 维护：隐式结果推断
        inferOutcomesQuietly(ctx);

        // 信号采集
        var runtimeCtx = toReminderContext(ctx);
        var policyConfig = new ReminderPolicyConfig();
        var topics = signalCollector.collect(ctx.userId(), runtimeCtx);

        // 候选检测 + 评分
        var result = new ArrayList<ProactiveCandidate>();
        for (var snapshot : topics) {
            // 插件级过滤：已静音
            if (snapshot.state().muted()) {
                continue;
            }
            var candidates = candidateDetector.detect(snapshot, runtimeCtx, policyConfig);
            for (var candidate : candidates) {
                result.add(toProactiveCandidate(candidate, snapshot, policyConfig));
            }
        }
        log.debug("ReminderBehavior.detect: topics={}, candidates={}", topics.size(), result.size());
        return result;
    }

    @Override
    public List<ProactiveAction> reason(List<ProactiveCandidate> candidates, ContextPacket ctx) {
        var runtimeCtx = toReminderContext(ctx);
        var actions = new ArrayList<ProactiveAction>();

        for (var candidate : candidates) {
            if (!(candidate.detail() instanceof ReminderCandidateDetail detail)) {
                log.warn("ReminderBehavior.reason: 无法识别的 detail 类型, id={}", candidate.id());
                continue;
            }
            // 构造 ReminderDecision（reason 阶段需要 decision 来生成消息）
            var decision = new ReminderDecision(
                    detail.candidate(),
                    candidate.score() >= 0.7f ? ReminderAction.NORMAL_PUSH : ReminderAction.SOFT_PUSH,
                    null,
                    detail.candidate().rationale()
            );
            var message = messageGenerator.generate(ctx.userId(), decision, detail.snapshot(), runtimeCtx);
            if (message.body().isBlank()) {
                log.debug("ReminderBehavior.reason: 消息为空, topic={}", candidate.topicKey());
                continue;
            }
            actions.add(new ProactiveAction(
                    candidate,
                    message.body(),
                    scoreToDeliveryLevel(candidate.score()),
                    detail
            ));
        }
        log.debug("ReminderBehavior.reason: candidates={}, actions={}", candidates.size(), actions.size());
        return actions;
    }

    /** 将 ContextPacket 转为现有 ReminderRuntimeContext。 */
    private ReminderRuntimeContext toReminderContext(ContextPacket ctx) {
        return new ReminderRuntimeContext(
                ctx.now(),
                ctx.zoneId(),
                ctx.quietHoursStart(),
                ctx.quietHoursEnd(),
                ctx.actionsSentToday(),
                ctx.focusState()
        );
    }

    /** 将现有 ReminderCandidate 转为框架 ProactiveCandidate。 */
    private ProactiveCandidate toProactiveCandidate(ReminderCandidate candidate,
                                                     ReminderTopicSnapshot snapshot,
                                                     ReminderPolicyConfig policyConfig) {
        return new ProactiveCandidate(
                UUID.randomUUID().toString(),
                name(),
                candidate.topicKey(),
                candidate.title(),
                candidate.finalScore(),
                candidate.rationale(),
                new ReminderCandidateDetail(snapshot, candidate, policyConfig)
        );
    }

    /** 分数 → 建议投递级别。 */
    private static DeliveryLevel scoreToDeliveryLevel(float score) {
        if (score >= 0.7f) return DeliveryLevel.INTERRUPT;
        if (score >= 0.5f) return DeliveryLevel.NOTIFY;
        if (score >= 0.3f) return DeliveryLevel.QUEUE;
        return DeliveryLevel.SILENT;
    }

    private void inferOutcomesQuietly(ContextPacket ctx) {
        if (outcomeInferenceService == null) return;
        try {
            outcomeInferenceService.inferRecentOutcomes(ctx.userId(), ctx.now());
        } catch (Exception e) {
            log.debug("ReminderBehavior: 隐式结果推断跳过: {}", e.getMessage());
        }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn test -Dtest="ReminderBehavior_单元测试" -pl . -q`

Expected: PASS (all 4 tests green)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/lifepilot/agent/task/reminder/ReminderCandidateDetail.java \
        src/main/java/com/lifepilot/agent/task/reminder/ReminderBehavior.java \
        src/test/java/com/lifepilot/agent/task/reminder/ReminderBehavior_单元测试.java
git commit -m "feat(proactive): ReminderBehavior 适配器 — 现有提醒管线接入 ProactiveBehavior

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: ProactiveEngine Orchestrator

**Files:**
- Create: `src/main/java/com/lifepilot/agent/task/proactive/ProactiveEngine.java`
- Test: `src/test/java/com/lifepilot/agent/task/proactive/ProactiveEngine_单元测试.java`

- [ ] **Step 1: Write the failing test**

```java
package com.lifepilot.agent.task.proactive;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProactiveEngine_单元测试 {

    ProactiveBehavior behavior;
    DecisionGate gate;
    DeliveryEngine delivery;
    ProactiveEngine engine;

    @BeforeEach
    void setUp() {
        behavior = mock(ProactiveBehavior.class);
        when(behavior.name()).thenReturn("test-behavior");
        gate = mock(DecisionGate.class);
        delivery = mock(DeliveryEngine.class);
        engine = new ProactiveEngine(List.of(behavior), gate, delivery);
    }

    @Test
    void Gate1_无变化时SILENT不调detect() {
        // focusState idle 超过心跳间隔 → SILENT
        var ctx = ctxWithIdle(60);
        ctx = new ContextPacket(ctx.userId(), ctx.now(), ctx.zoneId(),
                ctx.quietHoursStart(), ctx.quietHoursEnd(), ctx.actionsSentToday(),
                ctx.dailyMaxActions(), ctx.focusState(),
                Instant.now().minusSeconds(1800), ctx.heartbeatIntervalMin());

        var result = engine.heartbeat(ctx);

        assertThat(result).isEqualTo(DetectionLevel.SILENT);
        verifyNoInteractions(behavior);
        verifyNoInteractions(gate);
        verifyNoInteractions(delivery);
    }

    @Test
    void Gate2_无候选时FAST不调reason() {
        var ctx = activeCtx();
        when(behavior.detect(any())).thenReturn(List.of());

        var result = engine.heartbeat(ctx);

        assertThat(result).isEqualTo(DetectionLevel.FAST);
        verify(behavior).detect(any());
        verify(behavior, never()).reason(anyList(), any());
    }

    @Test
    void Gate2_所有候选低分时FAST() {
        var ctx = activeCtx();
        var lowCandidate = new ProactiveCandidate(
                "c1", "test-behavior", "topic", "Title", 0.3f, "", null);
        when(behavior.detect(any())).thenReturn(List.of(lowCandidate));

        var result = engine.heartbeat(ctx);

        assertThat(result).isEqualTo(DetectionLevel.FAST);
        verify(behavior, never()).reason(anyList(), any());
    }

    @Test
    void Gate3_高分候选走完整流程FULL() {
        var ctx = activeCtx();
        var candidate = new ProactiveCandidate(
                "c1", "test-behavior", "topic", "Title", 0.75f, "", null);
        when(behavior.detect(any())).thenReturn(List.of(candidate));

        var action = new ProactiveAction(candidate, "内容", DeliveryLevel.INTERRUPT, null);
        when(behavior.reason(anyList(), any())).thenReturn(List.of(action));

        var gatedAction = new DecisionGate.GatedAction(action, DeliveryLevel.INTERRUPT);
        when(gate.evaluate(anyList(), any())).thenReturn(List.of(gatedAction));

        var deliveryResult = new DeliveryResult("nid-1", DeliveryLevel.INTERRUPT, Instant.now());
        when(delivery.deliver(any(), any(), anyString())).thenReturn(deliveryResult);

        var result = engine.heartbeat(ctx);

        assertThat(result).isEqualTo(DetectionLevel.FULL);
        verify(behavior).detect(any());
        verify(behavior).reason(anyList(), any());
        verify(gate).evaluate(anyList(), any());
        verify(delivery).deliver(eq(action), eq(DeliveryLevel.INTERRUPT), eq("u1"));
        verify(behavior).onDelivered(eq(action), eq(deliveryResult));
    }

    @Test
    void 多个候选仅高分进入reason() {
        var ctx = activeCtx();
        var low = new ProactiveCandidate("c1", "test-behavior", "low", "Low", 0.2f, "", null);
        var high = new ProactiveCandidate("c2", "test-behavior", "high", "High", 0.8f, "", null);
        when(behavior.detect(any())).thenReturn(List.of(low, high));

        var action = new ProactiveAction(high, "内容", DeliveryLevel.INTERRUPT, null);
        when(behavior.reason(anyList(), any())).thenReturn(List.of(action));
        when(gate.evaluate(anyList(), any())).thenReturn(List.of());

        engine.heartbeat(ctx);

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(behavior).reason(captor.capture(), any());
        @SuppressWarnings("unchecked")
        List<ProactiveCandidate> reasoned = captor.getValue();
        assertThat(reasoned).hasSize(1);
        assertThat(reasoned.getFirst().topicKey()).isEqualTo("high");
    }

    // ── helpers ──

    private ContextPacket activeCtx() {
        return new ContextPacket("u1", Instant.now(), ZoneId.of("Asia/Shanghai"),
                null, null, 0, 5, null, null, 30);
    }

    private ContextPacket ctxWithIdle(int idleMinutes) {
        var focus = new com.lifepilot.agent.task.reminder.ReminderFocusState(
                "explorer.exe", "Desktop", false, idleMinutes, Instant.now());
        return new ContextPacket("u1", Instant.now(), ZoneId.of("Asia/Shanghai"),
                null, null, 0, 5, focus, null, 30);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest="ProactiveEngine_单元测试" -pl . -q`

Expected: FAIL — `ProactiveEngine` class not found

- [ ] **Step 3: Implement ProactiveEngine**

```java
package com.lifepilot.agent.task.proactive;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.task.reminder.ReminderFocusState;
import com.lifepilot.agent.task.reminder.ReminderFocusStateHolder;
import com.lifepilot.notification.NotificationRepository;
import com.lifepilot.notification.config.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 主动智能引擎 — 三级需求检测 + 行为插件编排。
 *
 * <p>心跳流程：
 * <ol>
 *   <li>Gate 1（SILENT）：变化量检查 — 无变化直接休眠</li>
 *   <li>Gate 2（FAST）：每个插件快速检测候选 — 无高分候选则跳过</li>
 *   <li>Gate 3（FULL）：高分候选进入精细推理 → 决策门控 → 投递</li>
 * </ol>
 * </p>
 */
public class ProactiveEngine {

    private static final Logger log = LoggerFactory.getLogger(ProactiveEngine.class);
    private static final String PROACTIVE_TYPE = "proactive_action";

    /** Gate 2 阈值 — 所有候选最高分低于此值时不进入 FULL。 */
    private static final float GATE2_THRESHOLD = 0.4f;

    private final List<ProactiveBehavior> behaviors;
    private final DecisionGate decisionGate;
    private final DeliveryEngine deliveryEngine;
    private final NotificationProperties notificationProperties;
    private final NotificationRepository notificationRepository;
    private final AgentConfigProperties config;
    @Nullable
    private final ReminderFocusStateHolder focusStateHolder;

    /** 上次心跳时间，用于 Gate 1 变化量检查。 */
    @Nullable
    private volatile Instant lastHeartbeatAt;

    /** 测试用简化构造器。 */
    public ProactiveEngine(List<ProactiveBehavior> behaviors,
                           DecisionGate decisionGate,
                           DeliveryEngine deliveryEngine) {
        this(behaviors, decisionGate, deliveryEngine, null, null, null, null);
    }

    public ProactiveEngine(List<ProactiveBehavior> behaviors,
                           DecisionGate decisionGate,
                           DeliveryEngine deliveryEngine,
                           @Nullable NotificationProperties notificationProperties,
                           @Nullable NotificationRepository notificationRepository,
                           @Nullable AgentConfigProperties config,
                           @Nullable ReminderFocusStateHolder focusStateHolder) {
        this.behaviors = List.copyOf(behaviors);
        this.decisionGate = decisionGate;
        this.deliveryEngine = deliveryEngine;
        this.notificationProperties = notificationProperties;
        this.notificationRepository = notificationRepository;
        this.config = config;
        this.focusStateHolder = focusStateHolder;
    }

    /**
     * 生产入口：自行构建 ContextPacket 并执行心跳。
     */
    public DetectionLevel heartbeat() {
        var ctx = buildContextPacket();
        var level = heartbeat(ctx);
        lastHeartbeatAt = ctx.now();
        return level;
    }

    /**
     * 执行一次心跳（可注入上下文，主要供测试使用）。
     *
     * @param ctx 心跳上下文
     * @return 本次心跳的检测级别
     */
    public DetectionLevel heartbeat(ContextPacket ctx) {
        // ── Gate 1: 变化量检查 ──
        if (!ctx.hasChangeSinceLastHeartbeat()) {
            log.debug("主动引擎: SILENT — 自上次心跳无变化");
            return DetectionLevel.SILENT;
        }

        // ── Gate 2: 各插件快速检测候选 ──
        var allCandidates = new ArrayList<ProactiveCandidate>();
        for (var behavior : behaviors) {
            try {
                var candidates = behavior.detect(ctx);
                allCandidates.addAll(candidates);
            } catch (Exception e) {
                log.warn("主动引擎: 插件 detect 异常, behavior={}, error={}",
                        behavior.name(), e.getMessage());
            }
        }

        float maxScore = allCandidates.stream()
                .map(ProactiveCandidate::score)
                .max(Float::compare)
                .orElse(0f);

        if (allCandidates.isEmpty() || maxScore < GATE2_THRESHOLD) {
            log.debug("主动引擎: FAST — candidates={}, maxScore={}",
                    allCandidates.size(), maxScore);
            return DetectionLevel.FAST;
        }

        // ── Gate 3: 高分候选进入精细推理 ──
        var topCandidates = allCandidates.stream()
                .filter(c -> c.score() >= GATE2_THRESHOLD)
                .sorted(Comparator.comparingDouble(ProactiveCandidate::score).reversed())
                .toList();

        // 按插件分组，调用 reason()
        var byBehavior = topCandidates.stream()
                .collect(Collectors.groupingBy(ProactiveCandidate::behaviorName, LinkedHashMap::new, Collectors.toList()));

        var allActions = new ArrayList<ProactiveAction>();
        for (var entry : byBehavior.entrySet()) {
            var behavior = findBehavior(entry.getKey());
            if (behavior == null) continue;
            try {
                var actions = behavior.reason(entry.getValue(), ctx);
                allActions.addAll(actions);
            } catch (Exception e) {
                log.warn("主动引擎: 插件 reason 异常, behavior={}, error={}",
                        entry.getKey(), e.getMessage());
            }
        }

        if (allActions.isEmpty()) {
            log.debug("主动引擎: FULL — 推理后无有效动作");
            return DetectionLevel.FULL;
        }

        // ── 决策门控 ──
        var gated = decisionGate.evaluate(allActions, ctx);

        // ── 投递 ──
        for (var ga : gated) {
            try {
                var result = deliveryEngine.deliver(ga.action(), ga.level(), ctx.userId());
                var behavior = findBehavior(ga.action().candidate().behaviorName());
                if (behavior != null) {
                    behavior.onDelivered(ga.action(), result);
                }
            } catch (Exception e) {
                log.warn("主动引擎: 投递异常, topic={}, error={}",
                        ga.action().candidate().topicKey(), e.getMessage());
            }
        }

        log.info("主动引擎: FULL — candidates={}, actions={}, delivered={}",
                topCandidates.size(), allActions.size(), gated.size());
        return DetectionLevel.FULL;
    }

    private ProactiveBehavior findBehavior(String name) {
        return behaviors.stream()
                .filter(b -> b.name().equals(name))
                .findFirst()
                .orElse(null);
    }

    private ContextPacket buildContextPacket() {
        String userId = notificationProperties != null
                ? notificationProperties.getDefaultUserId() : "default";
        Instant now = Instant.now();
        ZoneId zoneId = ZoneId.systemDefault();
        Instant startOfDay = LocalDate.now(zoneId).atStartOfDay(zoneId).toInstant();
        int sentToday = notificationRepository != null
                ? (int) notificationRepository.countSentByUserIdAndTypeSince(userId, PROACTIVE_TYPE, startOfDay)
                : 0;
        int dailyMax = config != null ? config.getTask().getProactiveReminderDailyMaxReminders() : 3;
        int heartbeatMin = config != null ? config.getTask().getHeartbeatIntervalSeconds() / 60 : 30;
        ReminderFocusState focusState = focusStateHolder != null ? focusStateHolder.get() : null;
        LocalTime qStart = parseTime(config != null
                ? config.getTask().getProactiveReminderQuietHoursStart() : null);
        LocalTime qEnd = parseTime(config != null
                ? config.getTask().getProactiveReminderQuietHoursEnd() : null);

        return new ContextPacket(userId, now, zoneId, qStart, qEnd,
                sentToday, dailyMax, focusState, lastHeartbeatAt, heartbeatMin);
    }

    @Nullable
    private static LocalTime parseTime(@Nullable String timeStr) {
        if (timeStr == null || timeStr.isBlank()) return null;
        try { return LocalTime.parse(timeStr); }
        catch (Exception e) { return null; }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest="ProactiveEngine_单元测试" -pl . -q`

Expected: PASS (all 5 tests green)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/lifepilot/agent/task/proactive/ProactiveEngine.java \
        src/test/java/com/lifepilot/agent/task/proactive/ProactiveEngine_单元测试.java
git commit -m "feat(proactive): ProactiveEngine 三级检测编排 — SILENT/FAST/FULL 管线

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: Auto-Configuration + HeartbeatRunner Rewiring

**Files:**
- Create: `src/main/java/com/lifepilot/agent/task/proactive/ProactiveAutoConfiguration.java`
- Modify: `src/main/java/com/lifepilot/agent/task/HeartbeatRunner.java`
- Modify: `src/main/java/com/lifepilot/agent/task/config/ReminderAutoConfiguration.java`

- [ ] **Step 1: Create ProactiveAutoConfiguration**

```java
package com.lifepilot.agent.task.proactive;

import com.lifepilot.notification.NotificationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * 主动引擎框架自动配置。
 *
 * <p>注册 DecisionGate、DeliveryEngine、QueuedActionRepository、ProactiveEngine。
 * 行为插件（如 ReminderBehavior）由各自的自动配置类注册。</p>
 */
@Configuration
public class ProactiveAutoConfiguration {

    @Bean
    QueuedActionRepository queuedActionRepository(JdbcTemplate jdbcTemplate) {
        return new QueuedActionRepository(jdbcTemplate);
    }

    @Bean
    DecisionGate proactiveDecisionGate() {
        return new DecisionGate();
    }

    @Bean
    DeliveryEngine proactiveDeliveryEngine(NotificationService notificationService,
                                           QueuedActionRepository queuedActionRepository) {
        return new DeliveryEngine(notificationService, queuedActionRepository);
    }

    @Bean
    @ConditionalOnBean(ProactiveBehavior.class)
    ProactiveEngine proactiveEngine(List<ProactiveBehavior> behaviors,
                                    DecisionGate decisionGate,
                                    DeliveryEngine deliveryEngine,
                                    NotificationProperties notificationProperties,
                                    NotificationRepository notificationRepository,
                                    AgentConfigProperties config,
                                    @Nullable ReminderFocusStateHolder focusStateHolder) {
        return new ProactiveEngine(behaviors, decisionGate, deliveryEngine,
                notificationProperties, notificationRepository, config, focusStateHolder);
    }
}
```

- [ ] **Step 2: Add ReminderBehavior bean to ReminderAutoConfiguration**

In `src/main/java/com/lifepilot/agent/task/config/ReminderAutoConfiguration.java`, add a new bean method after the existing `ReminderDecisionEngine` bean registration. Find the class and add:

```java
@Bean
ReminderBehavior reminderBehavior(ReminderSignalCollector signalCollector,
                                  ReminderCandidateDetector candidateDetector,
                                  ReminderMessageGenerator messageGenerator,
                                  @Nullable ReminderOutcomeInferenceService outcomeInferenceService) {
    return new ReminderBehavior(signalCollector, candidateDetector, messageGenerator, outcomeInferenceService);
}
```

Add the import: `import com.lifepilot.agent.task.reminder.ReminderBehavior;`

- [ ] **Step 3: Modify HeartbeatRunner to use ProactiveEngine**

Replace the `ProactiveReminderService` dependency with `ProactiveEngine`. The `ProactiveReminderService` is kept as a fallback for backward compatibility (deferred wakeups, warmup).

Changes to `src/main/java/com/lifepilot/agent/task/HeartbeatRunner.java`:

1. Add field: `@Nullable private final ProactiveEngine proactiveEngine;`
2. Add constructor parameter: `@Nullable ProactiveEngine proactiveEngine`
3. In `beat()`: call `runProactiveEngineIfEnabled()` first, fall back to `runProactiveReminderIfEnabled()` if engine unavailable

```java
// 新增字段
@Nullable
private final ProactiveEngine proactiveEngine;

// 新增构造器（保留旧构造器向后兼容）
public HeartbeatRunner(ScheduledExecutorService scheduler,
                       AgentConfigProperties config,
                       @Nullable ProactiveReminderService proactiveReminderService,
                       @Nullable ProactiveEngine proactiveEngine) {
    this.scheduler = scheduler;
    this.config = config;
    this.proactiveReminderService = proactiveReminderService;
    this.proactiveEngine = proactiveEngine;
}

// 新增方法
private void runProactiveEngineIfEnabled() {
    if (proactiveEngine == null) {
        return;
    }
    try {
        var level = proactiveEngine.heartbeat();  // 引擎自行构建上下文
        log.debug("主动引擎心跳完成: level={}", level);
    } catch (Exception e) {
        log.warn("主动引擎心跳异常: {}", e.getMessage());
    }
}
```

The full modified `beat()` method:
```java
void beat() {
    if (!isWithinActiveHours()) {
        log.debug("心跳跳过: 当前不在活跃时段");
        return;
    }
    // 优先使用新引擎，无引擎时回退到旧服务
    if (proactiveEngine != null) {
        runProactiveEngineIfEnabled();
    } else {
        runProactiveReminderIfEnabled();
    }
}
```

- [ ] **Step 4: Update HeartbeatRunner bean registration in ReminderAutoConfiguration**

Find the existing `HeartbeatRunner` bean registration and add the `ProactiveEngine` parameter:

```java
@Bean
HeartbeatRunner heartbeatRunner(ScheduledExecutorService scheduler,
                                AgentConfigProperties config,
                                @Nullable ProactiveReminderService proactiveReminderService,
                                @Nullable ProactiveEngine proactiveEngine) {
    return new HeartbeatRunner(scheduler, config, proactiveReminderService, proactiveEngine);
}
```

- [ ] **Step 5: Verify compilation**

Run: `mvn compile -pl . -q`

Expected: BUILD SUCCESS

- [ ] **Step 6: Run all existing tests to verify no regression**

Run: `mvn test -pl . -q`

Expected: All existing tests pass. If any fail, fix the wiring issues before proceeding.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/lifepilot/agent/task/proactive/ProactiveAutoConfiguration.java \
        src/main/java/com/lifepilot/agent/task/HeartbeatRunner.java \
        src/main/java/com/lifepilot/agent/task/config/ReminderAutoConfiguration.java
git commit -m "feat(proactive): 自动配置 + HeartbeatRunner 接入 ProactiveEngine

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: Regression Verification and Cleanup

**Files:** None new — verification only

- [ ] **Step 1: Run full test suite**

Run: `mvn test -pl . -q`

Expected: ALL tests pass (existing + new). Pay special attention to:
- `HeartbeatRunner_单元测试` — constructor signature changed
- `TaskAutoConfiguration_集成测试` — new beans may affect wiring
- All `Reminder*_单元测试` — existing behavior must not degrade

- [ ] **Step 2: Fix any HeartbeatRunner test failures**

The `HeartbeatRunner_单元测试` likely needs updating because the constructor now accepts `ProactiveEngine`. Update the test to pass `null` for the new parameter:

```java
// 在测试中更新构造调用
var runner = new HeartbeatRunner(scheduler, config, reminderService, null);
```

- [ ] **Step 3: Verify compilation clean**

Run: `mvn compile -pl . -q`

Expected: BUILD SUCCESS with zero warnings related to new code

- [ ] **Step 4: Commit any test fixes**

```bash
git add -u
git commit -m "fix(proactive): 修复 HeartbeatRunner 测试适配新构造器

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 5: Final verification — all tests green**

Run: `mvn test -pl . -q`

Expected: BUILD SUCCESS — all tests pass

---

## Verification Checklist

After all tasks are complete, verify:

- [ ] **现有提醒功能不退化**: 所有 `Reminder*_单元测试` 和 `ProactiveReminderService_单元测试` 全部通过
- [ ] **新框架编译通过**: `mvn compile` 零错误
- [ ] **三级检测生效**: `ProactiveEngine_单元测试` 验证 SILENT/FAST/FULL 三条路径
- [ ] **四级投递生效**: `DeliveryEngine_单元测试` 验证 Silent/Queue/Notify/Interrupt
- [ ] **决策门控生效**: `DecisionGate_单元测试` 验证硬边界 + 软约束
- [ ] **排队持久化**: `QueuedActionRepository_集成测试` 验证 CRUD
- [ ] **适配器连接**: `ReminderBehavior_单元测试` 验证 detect/reason 流程
- [ ] **Flyway 迁移**: V3 迁移在启动时自动执行
- [ ] **HeartbeatRunner**: 优先使用 ProactiveEngine，无引擎时回退到 ProactiveReminderService
