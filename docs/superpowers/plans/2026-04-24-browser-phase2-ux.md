# 浏览器能力补全 Phase 2 — 可观察性 + 人机交接 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **上游 roadmap**：`docs/superpowers/plans/2026-04-24-browser-capability-roadmap.md`
> **前置依赖**：Phase 1 已合入 develop —— `browser.snapshot` / `IndexedElement` / `InteractiveElementIndexer` / `click(index)` / `input(index)` / `hover(index)`

**Goal：** 让用户能实时看到 Agent 浏览过程（BrowserToolCallCard 特化），验证码 / 登录墙场景 Agent 能主动暂停、用户在浏览器里接管完成后 Agent 继续（`browser.requestHumanTakeover`）。

**Architecture：**
1. **复用现有挂起-恢复**：新增 `SuspendReason.BrowserTakeover` permit，Agent 引擎自动识别挂起/恢复，不重造机制。参见 `docs/architecture/agent-suspend-resume.md`。
2. **Tool 层**：`BrowserHumanTakeoverExecutor` 调 `AgentOrchestrator.suspend(BrowserTakeover(...))` 即挂起当前回合；前端收到 `AGENT_SUSPENDED` 事件渲染浏览器接管弹窗；用户完成点"继续"走现有 resume 端点；Agent 从下一步继续。
3. **UX 层**：`BrowserToolCallCard.vue` 特化 browser.* 的对话卡展示（URL、截图缩略图、action 徽章）；`HumanTakeoverModal.vue` 暂停弹窗；headless 默认 `false` 让用户看得见。

**Tech Stack：** Java 22（sealed interface 加 permit）、Spring Boot 3.x、Vue 3 + Reka UI 2.x + Tailwind 命名尺度、Pinia / 现有 composables、JUnit 5 + Vitest

---

## File Structure

### 后端新建

| 路径 | 责任 |
|---|---|
| `src/main/java/com/lifepilot/meta/infra/browser/BrowserHumanTakeoverExecutor.java` | `browser.requestHumanTakeover` action，调用 AgentOrchestrator.suspend |

### 后端修改

| 路径 | 改动 |
|---|---|
| `src/main/java/com/lifepilot/agent/suspend/model/SuspendReason.java` | sealed interface 的 `permits` 追加 `BrowserTakeover`；新增 record `BrowserTakeover(String sessionId, String reason, Instant requestedAt)` |
| `src/main/java/com/lifepilot/agent/suspend/AgentResumeListener.java`（如有 switch SuspendReason） | 若有穷举 switch 需补 BrowserTakeover 分支 |
| `src/main/java/com/lifepilot/agent/orchestration/AgentOrchestrator.java` | 确认 `suspend(SuspendReason, ...)` 公开 API 可被 tool executor 调用；若无则新增或引入 `AgentSuspendPort` 接口 |
| `src/main/java/com/lifepilot/agent/suspend/store/SqliteSuspendStore.java` | 检查 SuspendReason 序列化是否需要适配（若 JSON 按 type 字段分派，加 `BrowserTakeover` 即可） |
| `src/main/java/com/lifepilot/meta/infra/browser/BrowserActionDispatchExecutor.java` | 构造器注册 `requestHumanTakeover` action，注入 AgentOrchestrator |
| `src/main/java/com/lifepilot/meta/infra/BrowserToolProvider.java` | schema 新增 `requestHumanTakeover` action（参数 `reason: string`，可选 `sessionId`） |
| `src/main/resources/application.yml` | 新增 `browser.takeover.timeout-seconds: 300`、`browser.headless: false` 默认值 |
| `src/main/java/com/lifepilot/meta/config/MetaProperties.java` | `Browser.Takeover` 配置类；`headless` 默认值改 false |
| `.env.example` | 追加 `BROWSER_HEADLESS=true`（容器部署覆写） |
| `docker-compose.yml` | env 继承 `BROWSER_HEADLESS` |
| `src/main/resources/skills/browser-automation/SKILL.md` | 补"登录墙 → requestHumanTakeover"的具体触发条件（不写话术模板） |

### 前端新建

| 路径 | 操作 | 职责 |
|---|---|---|
| `zhiwei-web/src/components/chat/BrowserToolCallCard.vue` | 新建 | 浏览器工具专用卡片：URL + 截图缩略图 + action 徽章 + elements 折叠列表 |
| `zhiwei-web/src/components/chat/HumanTakeoverModal.vue` | 新建 | 接管弹窗：原因 + 倒计时 + 继续/取消 |

### 前端修改

| 路径 | 改动 |
|---|---|
| `zhiwei-web/src/components/chat/ToolCallCard.vue` | `tool.toolId.startsWith('browser.')` 时委托 BrowserToolCallCard |
| `zhiwei-web/src/composables/useChat.ts` | `AGENT_SUSPENDED` case 识别 reason 类型为 BrowserTakeover，触发弹窗显示 |
| `zhiwei-web/src/types/sse.ts` 或等价类型定义 | `SseAgentSuspendedEvent.reasonType` 加 `browser_takeover` 枚举 |
| `zhiwei-web/src/api/agent.ts`（若已有 resume 接口包）或新建 | 前端 resume API 调用封装，包含 `browser_takeover` case |

### 测试

| 路径 | 用途 |
|---|---|
| `src/test/java/com/lifepilot/meta/infra/browser/BrowserHumanTakeoverExecutor_挂起测试.java` | 调 orchestrator.suspend 参数正确、挂起结果返回给 LLM |
| `src/test/java/com/lifepilot/agent/suspend/model/SuspendReason_BrowserTakeover序列化测试.java` | JSON round-trip，permit 穷举通过 |
| `zhiwei-web/src/components/chat/BrowserToolCallCard.spec.ts` | Vitest：snapshot action 渲染 elements 列表、其他 action 只显示 URL+截图 |
| `zhiwei-web/src/components/chat/HumanTakeoverModal.spec.ts` | Vitest：倒计时、继续按钮触发 resume、取消触发 cancel |

---

## Task 1: SuspendReason 新增 BrowserTakeover permit

**Files:**
- Modify: `src/main/java/com/lifepilot/agent/suspend/model/SuspendReason.java`
- Create: `src/test/java/com/lifepilot/agent/suspend/model/SuspendReason_BrowserTakeover序列化测试.java`

- [ ] **Step 1: 先看现有 SuspendReason 完整定义**

```bash
grep -n "permits\|record.*implements SuspendReason" src/main/java/com/lifepilot/agent/suspend/model/SuspendReason.java
```

确认当前 permits 列表（文档 agent-suspend-resume.md 列出 5 种：WorkflowWait / UserConfirmation / RemoteDelegation / ScheduledWakeup / ExternalDataWait）。

- [ ] **Step 2: 写失败的 permit 穷举测试**

```java
package com.lifepilot.agent.suspend.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SuspendReason_BrowserTakeover序列化测试 {

    @Test
    void BrowserTakeover_JSON_round_trip() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var reason = new SuspendReason.BrowserTakeover(
                "task-login",
                "需要短信验证码",
                Instant.parse("2026-04-24T10:00:00Z")
        );
        String json = mapper.writeValueAsString(reason);
        SuspendReason parsed = mapper.readValue(json, SuspendReason.class);
        assertThat(parsed).isInstanceOf(SuspendReason.BrowserTakeover.class);
        var br = (SuspendReason.BrowserTakeover) parsed;
        assertThat(br.sessionId()).isEqualTo("task-login");
        assertThat(br.reason()).isEqualTo("需要短信验证码");
    }

    @Test
    void 模式匹配_穷举_新增的_BrowserTakeover() {
        SuspendReason reason = new SuspendReason.BrowserTakeover("s", "r", Instant.now());
        String label = switch (reason) {
            case SuspendReason.WorkflowWait w -> "workflow";
            case SuspendReason.UserConfirmation u -> "confirm";
            case SuspendReason.RemoteDelegation rd -> "remote";
            case SuspendReason.ScheduledWakeup sw -> "wakeup";
            case SuspendReason.ExternalDataWait ed -> "external";
            case SuspendReason.BrowserTakeover bt -> "browser_takeover";
        };
        assertThat(label).isEqualTo("browser_takeover");
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

```bash
mvn test -Dtest=SuspendReason_BrowserTakeover序列化测试
```
Expected: FAIL（`BrowserTakeover` permit 不存在）

- [ ] **Step 4: 新增 permit**

在 `SuspendReason.java` 的 `permits` 列表追加 `BrowserTakeover`：
```java
public sealed interface SuspendReason permits
        SuspendReason.WorkflowWait,
        SuspendReason.UserConfirmation,
        SuspendReason.RemoteDelegation,
        SuspendReason.ScheduledWakeup,
        SuspendReason.ExternalDataWait,
        SuspendReason.BrowserTakeover {
    // ...现有 record

    /** 等待用户在浏览器中完成人工接管（验证码、登录、扫码等）。 */
    record BrowserTakeover(
            String sessionId,
            String reason,
            java.time.Instant requestedAt
    ) implements SuspendReason {}
}
```

如 SuspendReason 用 Jackson `@JsonSubTypes` 显式标注，还要新增：
```java
@JsonSubTypes.Type(value = SuspendReason.BrowserTakeover.class, name = "browser_takeover")
```

- [ ] **Step 5: 补所有 switch 穷举分支**

运行编译看哪些 switch 报错：
```bash
mvn compile -q 2>&1 | grep "switch\|pattern"
```

为每个报错的 switch 加 `case BrowserTakeover bt -> ...`（通常是 AgentResumeListener、持久化映射、SSE 事件构造等 3-5 处）。具体每处行为：
- `AgentResumeListener`：识别 resume 信号后让 Agent 继续，BrowserTakeover 不需要特殊数据，走通用路径
- SSE 构造：`reasonDetail` 填 `reason` 字段；`reasonType` 填 `"browser_takeover"`
- 持久化映射：JSON 序列化即可

- [ ] **Step 6: 运行测试通过 + 全量编译**

```bash
mvn test -Dtest=SuspendReason_BrowserTakeover序列化测试
mvn compile -q
```
Expected: PASS + BUILD SUCCESS

- [ ] **Step 7: commit**

```bash
git add src/main/java/com/lifepilot/agent/suspend/model/SuspendReason.java src/test/java/com/lifepilot/agent/suspend/model/SuspendReason_BrowserTakeover序列化测试.java $(对应补充的 switch 文件)
git commit -m "feat(agent): SuspendReason 新增 BrowserTakeover permit"
```

---

## Task 2: BrowserHumanTakeoverExecutor

**Files:**
- Create: `src/main/java/com/lifepilot/meta/infra/browser/BrowserHumanTakeoverExecutor.java`
- Create: `src/test/java/com/lifepilot/meta/infra/browser/BrowserHumanTakeoverExecutor_挂起测试.java`
- Modify: `src/main/java/com/lifepilot/meta/config/MetaProperties.java`（新增 `Browser.Takeover`）
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: 新增 Takeover 配置**

`MetaProperties.Browser` 内部：
```java
@NestedConfigurationProperty
private Takeover takeover = new Takeover();
public Takeover getTakeover() { return takeover; }
public void setTakeover(Takeover t) { this.takeover = t; }

public static class Takeover {
    /** 等待用户接管完成的超时秒数。 */
    private int timeoutSeconds = 300;
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int s) { this.timeoutSeconds = s; }
}
```

`application.yml` 在 `zhiwei.meta.infra.browser` 节点追加：
```yaml
takeover:
  timeout-seconds: 300
```

- [ ] **Step 2: 先确认 AgentOrchestrator 的 suspend 入口**

```bash
grep -rn "suspend\(.*SuspendReason" src/main/java/com/lifepilot/agent/orchestration/
grep -rn "AgentSuspendPort\|AgentSuspendService" src/main/java/com/lifepilot/agent/
```

预期找到类似 `AgentOrchestrator.suspendCurrent(SuspendReason)` 或 `AgentSuspendPort.requestSuspend(...)` 的公开 API。若工具执行上下文中能拿到 orchestrator 引用（常见方式：`ToolInput` 带 `agentContext` 或有 `AgentContextHolder` 线程上下文），直接注入。

若找不到公开 suspend 入口，说明之前的 suspend 都由 orchestrator 内部触发（比如工具返回特殊 ToolResult），需要查 `ToolResult` 是否支持 `suspended(SuspendReason)` 语义。**这一步必须先读代码确认，再写实现**。

- [ ] **Step 3: 写失败测试（按 Step 2 确认的 API 形状）**

假设发现 `ToolResult.suspend(SuspendReason)` 已有约定语义（orchestrator 看到 ToolResult 类型为 SUSPEND 会执行挂起），则：

```java
package com.lifepilot.meta.infra.browser;

import com.lifepilot.agent.suspend.model.SuspendReason;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BrowserHumanTakeoverExecutor_挂起测试 {

    @Test
    void 返回_suspend_结果_携带_BrowserTakeover_原因() {
        var exec = new BrowserHumanTakeoverExecutor();
        ToolResult result = exec.execute(ToolInput.of(Map.of(
                "sessionId", "login-task",
                "reason", "需要短信验证码"
        )));

        assertThat(result.isSuspend()).isTrue();
        var reason = (SuspendReason.BrowserTakeover) result.getSuspendReason();
        assertThat(reason.sessionId()).isEqualTo("login-task");
        assertThat(reason.reason()).isEqualTo("需要短信验证码");
    }

    @Test
    void 缺少_reason_参数_返回错误() {
        var exec = new BrowserHumanTakeoverExecutor();
        ToolResult r = exec.execute(ToolInput.of(Map.of("sessionId", "x")));
        assertThat(r.isSuccess()).isFalse();
    }
}
```

若 Step 2 发现 API 形状不是 ToolResult.suspend 而是注入 `AgentSuspendPort`，则测试改为 Mockito 验证 port 调用。

- [ ] **Step 4: 实现 executor**

骨架（按 Step 2 实际 API 调整）：

```java
package com.lifepilot.meta.infra.browser;

import com.lifepilot.agent.suspend.model.SuspendReason;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * 浏览器人机接管请求 — Agent 主动挂起回合，等待用户在浏览器里完成验证码 / 登录 / 扫码等操作。
 *
 * <p>用户完成后走现有 resume 端点恢复；Agent 从下一步继续。典型触发条件：
 * navigate 后页面是登录墙 / 检测到验证码 / 连续 snapshot 结果未变化。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
public class BrowserHumanTakeoverExecutor {

    private static final Logger log = LoggerFactory.getLogger(BrowserHumanTakeoverExecutor.class);

    public ToolResult execute(ToolInput input) {
        String reason;
        try {
            reason = input.getParam("reason", String.class);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("缺少必需参数: reason");
        }
        String sessionId = input.getOptionalParam("sessionId", String.class).orElse("default");

        var suspendReason = new SuspendReason.BrowserTakeover(sessionId, reason, Instant.now());
        log.info("浏览器请求人机接管: sessionId={}, reason={}", sessionId, reason);
        return ToolResult.suspend(suspendReason);
    }
}
```

若 `ToolResult.suspend` 不存在，方案 B：注入 `AgentSuspendPort`：
```java
public BrowserHumanTakeoverExecutor(AgentSuspendPort suspendPort) { this.suspendPort = suspendPort; }
// execute 里：suspendPort.requestSuspend(agentContext, suspendReason);
```

选择执行路径由 Step 2 的代码调研决定。

- [ ] **Step 5: 运行测试通过**

```bash
mvn test -Dtest=BrowserHumanTakeoverExecutor_挂起测试
```
Expected: PASS

- [ ] **Step 6: commit**

```bash
git add src/main/java/com/lifepilot/meta/infra/browser/BrowserHumanTakeoverExecutor.java src/test/java/com/lifepilot/meta/infra/browser/BrowserHumanTakeoverExecutor_挂起测试.java src/main/java/com/lifepilot/meta/config/MetaProperties.java src/main/resources/application.yml
git commit -m "feat(browser): requestHumanTakeover executor"
```

---

## Task 3: 注册 requestHumanTakeover action

**Files:**
- Modify: `BrowserActionDispatchExecutor.java`
- Modify: `BrowserToolProvider.java`（schema）
- Modify: `BrowserAutoConfiguration.java` 或等价装配

- [ ] **Step 1: Dispatcher 构造器注册**

```java
var takeoverExecutor = new BrowserHumanTakeoverExecutor(/* 按 Task 2 依赖注入 */);
register("requestHumanTakeover", RiskLevel.LOW, browserSessionSemantics, takeoverExecutor::execute);
```

- [ ] **Step 2: Provider schema 新增 action**

参数：
- `reason: string`（必需，展示给用户的原因）
- `sessionId: string?`（默认 default）

description 内嵌完整触发指引（按记忆「Schema 与 Skill 分工」：工具通用规则写 schema）：
> "Agent 遇到需要人工介入的场景（验证码、登录墙、扫码、人机验证、账号保护）时调用。Agent 当前回合被挂起，前端弹出窗口让用户完成浏览器内操作；用户点击继续后 Agent 从下一步自动恢复。reason 字段会展示给用户，说明为什么需要接管。"

- [ ] **Step 3: mvn compile 通过**

```bash
mvn compile -q
```
Expected: BUILD SUCCESS

- [ ] **Step 4: commit**

```bash
git add src/main/java/com/lifepilot/meta/infra/browser/BrowserActionDispatchExecutor.java src/main/java/com/lifepilot/meta/infra/BrowserToolProvider.java src/main/java/com/lifepilot/meta/infra/browser/BrowserAutoConfiguration.java
git commit -m "feat(browser): 注册 requestHumanTakeover action"
```

---

## Task 4: headless 默认切 false

**Files:**
- Modify: `application.yml`
- Modify: `MetaProperties.java`（Browser.headless 默认值）
- Modify: `.env.example`
- Modify: `docker-compose.yml`

- [ ] **Step 1: 切 yml 和默认值**

`application.yml`:
```yaml
browser:
  headless: false   # 本地开发可见；容器部署通过环境变量 BROWSER_HEADLESS=true 覆写
```

`MetaProperties.Browser`:
```java
private boolean headless = false;  // was true
```

- [ ] **Step 2: env 和 docker-compose**

`.env.example` 新增：
```bash
# 容器部署设为 true；桌面/本地开发设为 false 可看到浏览器
BROWSER_HEADLESS=true
```

`docker-compose.yml` 的 backend service 的 environment 段落追加：
```yaml
- ZHIWEI_META_INFRA_BROWSER_HEADLESS=${BROWSER_HEADLESS:-true}
```

（Spring Boot 环境变量映射约定：`a.b.c-d` → `A_B_CD` 或 `A_B_C_D`，以现有 `application.yml` 其他 env 覆写为准）

- [ ] **Step 3: 手测本地 + Docker 两个场景**

本地：
```bash
mvn spring-boot:run
```
前端发"打开 github.com"，应看到 Chromium 窗口弹出。

Docker：
```bash
BROWSER_HEADLESS=true docker compose up -d backend
docker compose logs backend | grep -i headless
```
日志应显示 `headless=true`。

- [ ] **Step 4: commit**

```bash
git add src/main/resources/application.yml src/main/java/com/lifepilot/meta/config/MetaProperties.java .env.example docker-compose.yml
git commit -m "chore(browser): 本地默认 headless=false，容器用环境变量覆写"
```

---

## Task 5: BrowserToolCallCard.vue 特化

**Files:**
- Create: `zhiwei-web/src/components/chat/BrowserToolCallCard.vue`
- Modify: `zhiwei-web/src/components/chat/ToolCallCard.vue`
- Create: `zhiwei-web/src/components/chat/BrowserToolCallCard.spec.ts`

- [ ] **Step 1: 写 BrowserToolCallCard.vue**

```vue
<script setup lang="ts">
import { computed } from 'vue'
import type { ToolCallSummary } from '@/types'
import { Card } from '@/components/ui/card'
import {
  Dialog, DialogTrigger, DialogContent, DialogTitle,
} from '@/components/ui/dialog'
import { Globe, Camera, MousePointer2, Type, ScanLine } from 'lucide-vue-next'

const props = defineProps<{ tool: ToolCallSummary }>()

/** 从 tool output JSON 中提取 URL / title / screenshot / elements。 */
const parsed = computed(() => {
  try {
    return typeof props.tool.output === 'string'
      ? JSON.parse(props.tool.output)
      : (props.tool.output ?? {})
  } catch {
    return {}
  }
})

const actionIcon = computed(() => {
  switch (props.tool.action) {
    case 'navigate': return Globe
    case 'screenshot': return Camera
    case 'snapshot': return ScanLine
    case 'click': case 'hover': return MousePointer2
    case 'input': return Type
    default: return Globe
  }
})

const url = computed(() => parsed.value.url ?? '')
const title = computed(() => parsed.value.title ?? '')
const screenshotDataUri = computed(() => parsed.value.screenshotDataUri ?? '')
const elements = computed(() => parsed.value.elements ?? [])
</script>

<template>
  <Card class="browser-tool-card gap-sm p-sm text-xs">
    <!-- 顶部 URL + action 徽章 -->
    <div class="flex items-center gap-sm">
      <component :is="actionIcon" :size="14" class="shrink-0 text-muted-foreground" />
      <span class="truncate font-medium">{{ tool.action }}</span>
      <span v-if="url" class="truncate text-muted-foreground">{{ url }}</span>
    </div>

    <!-- 截图缩略图 -->
    <Dialog v-if="screenshotDataUri">
      <DialogTrigger as-child>
        <img
          :src="screenshotDataUri"
          :alt="title"
          class="w-full cursor-zoom-in rounded-md border border-border/50"
          loading="lazy"
        />
      </DialogTrigger>
      <DialogContent class="max-w-2xl">
        <DialogTitle>{{ title || url }}</DialogTitle>
        <img :src="screenshotDataUri" :alt="title" class="w-full" />
      </DialogContent>
    </Dialog>

    <!-- snapshot 特有：elements 折叠列表 -->
    <details v-if="elements.length" class="text-xs">
      <summary class="cursor-pointer text-muted-foreground">
        已识别 {{ elements.length }} 个可交互元素
      </summary>
      <ul class="mt-sm space-y-xs">
        <li v-for="el in elements.slice(0, 20)" :key="el.index" class="flex gap-sm">
          <span class="shrink-0 rounded bg-muted px-xs font-mono">#{{ el.index }}</span>
          <span class="text-muted-foreground">{{ el.tag }}</span>
          <span class="truncate">{{ el.text || el.ariaLabel || el.name }}</span>
        </li>
      </ul>
    </details>
  </Card>
</template>
```

- [ ] **Step 2: ToolCallCard.vue 委托**

在现有 ToolCallCard.vue 的 template 开头加分支（保留原 generic 实现作 fallback）：
```vue
<script setup lang="ts">
// ... 现有 import
import BrowserToolCallCard from './BrowserToolCallCard.vue'

const isBrowserTool = computed(() => props.tool.toolId.startsWith('browser.') || props.tool.toolId === 'browser')
</script>

<template>
  <BrowserToolCallCard v-if="isBrowserTool" :tool="tool" />
  <Card v-else>
    <!-- 原 generic 模板 -->
  </Card>
</template>
```

- [ ] **Step 3: 写 Vitest**

```typescript
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import BrowserToolCallCard from '@/components/chat/BrowserToolCallCard.vue'

describe('BrowserToolCallCard', () => {
  it('snapshot action 渲染 elements 列表', () => {
    const wrapper = mount(BrowserToolCallCard, {
      props: {
        tool: {
          toolId: 'browser',
          action: 'snapshot',
          success: true,
          latencyMs: 200,
          output: JSON.stringify({
            url: 'https://example.com',
            title: '示例',
            screenshotDataUri: 'data:image/png;base64,AAA',
            elements: [
              { index: 0, tag: 'a', text: '首页', bbox: [0,0,50,20], role: '', name: '', id: 'home', ariaLabel: '' },
              { index: 1, tag: 'button', text: '搜索', bbox: [60,0,40,20], role: '', name: '', id: 'btn', ariaLabel: '' },
            ],
          }),
          inputSummary: '', outputSummary: '',
        } as never,
      },
    })
    expect(wrapper.text()).toContain('已识别 2 个可交互元素')
    expect(wrapper.text()).toContain('#0')
    expect(wrapper.find('img').attributes('src')).toContain('data:image/png;base64')
  })

  it('navigate action 只显示 URL 和截图，不显示 elements', () => {
    const wrapper = mount(BrowserToolCallCard, {
      props: {
        tool: {
          toolId: 'browser', action: 'navigate', success: true, latencyMs: 100,
          output: JSON.stringify({ url: 'https://example.com', title: '示例' }),
          inputSummary: '', outputSummary: '',
        } as never,
      },
    })
    expect(wrapper.text()).toContain('https://example.com')
    expect(wrapper.text()).not.toContain('已识别')
  })
})
```

- [ ] **Step 4: 运行测试通过**

```bash
cd zhiwei-web && npm run test:run -- BrowserToolCallCard
```
Expected: PASS

- [ ] **Step 5: commit**

```bash
git add zhiwei-web/src/components/chat/BrowserToolCallCard.vue zhiwei-web/src/components/chat/ToolCallCard.vue zhiwei-web/src/components/chat/BrowserToolCallCard.spec.ts
git commit -m "feat(ui): 浏览器工具专用对话卡片"
```

---

## Task 6: HumanTakeoverModal.vue + useChat 订阅

**Files:**
- Create: `zhiwei-web/src/components/chat/HumanTakeoverModal.vue`
- Create: `zhiwei-web/src/components/chat/HumanTakeoverModal.spec.ts`
- Modify: `zhiwei-web/src/composables/useChat.ts`（AGENT_SUSPENDED case 处理）
- Modify: `zhiwei-web/src/types/sse.ts`（若有 `SseAgentSuspendedEvent.reasonType` 补 `browser_takeover`）

- [ ] **Step 1: 写 HumanTakeoverModal.vue**

```vue
<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { Dialog, DialogContent, DialogTitle, DialogDescription } from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { AlertCircle } from 'lucide-vue-next'

const props = defineProps<{
  open: boolean
  reason: string
  timeoutSeconds: number
}>()

const emit = defineEmits<{
  (e: 'continue'): void
  (e: 'cancel'): void
}>()

const remaining = ref(props.timeoutSeconds)
let timer: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  timer = setInterval(() => {
    remaining.value = Math.max(0, remaining.value - 1)
    if (remaining.value === 0) emit('cancel')
  }, 1000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})

const remainingLabel = computed(() => {
  const m = Math.floor(remaining.value / 60)
  const s = remaining.value % 60
  return `${m}:${String(s).padStart(2, '0')}`
})
</script>

<template>
  <Dialog :open="open" @update:open="(v) => !v && emit('cancel')">
    <DialogContent class="max-w-md p-lg">
      <div class="flex items-center gap-sm">
        <AlertCircle :size="20" class="text-amber-500" />
        <DialogTitle>需要你接管</DialogTitle>
      </div>
      <DialogDescription class="mt-md text-sm">
        {{ reason }}
      </DialogDescription>
      <p class="mt-md text-xs text-muted-foreground">
        请在浏览器窗口完成操作，完成后点"继续"让 Agent 恢复任务。
      </p>
      <div class="mt-md flex items-center justify-between">
        <span class="font-mono text-xs text-muted-foreground">剩余 {{ remainingLabel }}</span>
        <div class="flex gap-sm">
          <Button variant="outline" @click="emit('cancel')">取消任务</Button>
          <Button @click="emit('continue')">已完成，继续</Button>
        </div>
      </div>
    </DialogContent>
  </Dialog>
</template>
```

- [ ] **Step 2: useChat.ts 的 AGENT_SUSPENDED case 扩展**

在现有 `case SSE_EVENT_TYPES.AGENT_SUSPENDED` 块内（useChat.ts:547 附近）新增分支：

```typescript
case SSE_EVENT_TYPES.AGENT_SUSPENDED: {
  const event: SseAgentSuspendedEvent = JSON.parse(data)
  currentTurnId = event.turnId ?? currentTurnId
  const suspendReasonDetail = event.reasonDetail?.trim() || event.terminationReason?.trim()
  flushStreamingText()

  // 新增：浏览器接管场景触发弹窗
  if (event.reasonType === 'browser_takeover') {
    activeBrowserTakeover.value = {
      turnId: event.turnId ?? '',
      reason: suspendReasonDetail || '需要你在浏览器中完成操作',
      timeoutSeconds: event.timeoutSeconds ?? 300,
    }
    break
  }

  // 其他 suspend 场景走原有逻辑
  // ... 保留现有 suspendedContent 处理
}
```

顶部 state 区域新增：
```typescript
const activeBrowserTakeover = ref<{
  turnId: string
  reason: string
  timeoutSeconds: number
} | null>(null)
```

导出给页面组件使用：
```typescript
return { /* ... 原有 */, activeBrowserTakeover, confirmBrowserTakeover, cancelBrowserTakeover }
```

继续/取消方法：
```typescript
async function confirmBrowserTakeover() {
  const takeover = activeBrowserTakeover.value
  if (!takeover) return
  await resumeAgent(takeover.turnId, { resumeType: 'browser_takeover', confirmed: true })
  activeBrowserTakeover.value = null
}

async function cancelBrowserTakeover() {
  const takeover = activeBrowserTakeover.value
  if (!takeover) return
  await cancelAgent(takeover.turnId)
  activeBrowserTakeover.value = null
}
```

`resumeAgent` / `cancelAgent` 调已有的 resume / cancel 端点（需看 `src/api/` 下是否已封装，没有的话补一层）。

- [ ] **Step 3: 在对话页面挂载 Modal**

`zhiwei-web/src/views/Chat.vue`（或等价聊天主页面）模板中：

```vue
<HumanTakeoverModal
  v-if="activeBrowserTakeover"
  :open="true"
  :reason="activeBrowserTakeover.reason"
  :timeout-seconds="activeBrowserTakeover.timeoutSeconds"
  @continue="confirmBrowserTakeover"
  @cancel="cancelBrowserTakeover"
/>
```

- [ ] **Step 4: SSE 类型补字段**

`zhiwei-web/src/types/sse.ts`（或等价）：
```typescript
export type SuspendReasonType =
  | 'workflow_wait' | 'user_confirmation' | 'remote_delegation'
  | 'scheduled_wakeup' | 'external_data_wait' | 'browser_takeover'

export interface SseAgentSuspendedEvent {
  turnId?: string
  reasonType: SuspendReasonType
  reasonDetail?: string
  terminationReason?: string
  content?: string
  timeoutSeconds?: number
}
```

- [ ] **Step 5: 写 Modal 单测**

```typescript
import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import HumanTakeoverModal from '@/components/chat/HumanTakeoverModal.vue'

describe('HumanTakeoverModal', () => {
  it('点击继续触发 continue 事件', async () => {
    const wrapper = mount(HumanTakeoverModal, {
      props: { open: true, reason: '需要验证码', timeoutSeconds: 300 },
    })
    await wrapper.find('button:last-of-type').trigger('click')
    expect(wrapper.emitted('continue')).toHaveLength(1)
  })

  it('超时自动 cancel', async () => {
    vi.useFakeTimers()
    const wrapper = mount(HumanTakeoverModal, {
      props: { open: true, reason: 'x', timeoutSeconds: 1 },
    })
    vi.advanceTimersByTime(1100)
    expect(wrapper.emitted('cancel')).toBeTruthy()
    vi.useRealTimers()
  })
})
```

- [ ] **Step 6: 运行前端测试通过**

```bash
cd zhiwei-web && npm run test:run -- HumanTakeoverModal
```
Expected: PASS

- [ ] **Step 7: commit**

```bash
git add zhiwei-web/src/components/chat/HumanTakeoverModal.vue zhiwei-web/src/components/chat/HumanTakeoverModal.spec.ts zhiwei-web/src/composables/useChat.ts zhiwei-web/src/types/sse.ts zhiwei-web/src/views/Chat.vue
git commit -m "feat(ui): 人机接管弹窗 + useChat 订阅"
```

---

## Task 7: SKILL.md 补登录墙 → takeover 引导

**Files:**
- Modify: `src/main/resources/skills/browser-automation/SKILL.md`

- [ ] **Step 1: 在"登录墙识别"章节（Phase 1 已加）下补触发条件**

```markdown
### 登录墙识别与接管

调用 `browser.requestHumanTakeover` 的**客观触发条件**（任一满足）：
- navigate 后页面 URL 含 login / signin / auth 关键词
- snapshot 返回的 elements 中有 type=password 输入框
- 截图中出现验证码图片（visual inspection）
- 同一 snapshot 连续 2 次 elements 列表完全相同且 Agent 无法推进（说明操作没生效）

**不要**用于：页面加载慢、元素暂时未出现 — 这些用 wait。

reason 字段要简短、用户语言（示例：「需要扫码登录」「请输入短信验证码」「人机验证」），具体措辞 Agent 根据观察组织。
```

- [ ] **Step 2: 更新 action 表格补 requestHumanTakeover**

```markdown
| `requestHumanTakeover` | 暂停让用户接管 | `reason` |
```

- [ ] **Step 3: frontmatter version 升 3.1.0**

- [ ] **Step 4: commit**

```bash
git add src/main/resources/skills/browser-automation/SKILL.md
git commit -m "docs(skill): 补登录墙识别和人机接管触发条件"
```

---

## Task 8: Phase 2 合并验收

- [ ] **Step 1: 全量编译 + 后端测试**

```bash
mvn clean compile
mvn test -q
```
Expected: BUILD SUCCESS + 全绿

- [ ] **Step 2: 前端测试**

```bash
cd zhiwei-web && npm run test:run
```
Expected: 全绿

- [ ] **Step 3: 端到端手测**

启动后端和前端：
```bash
mvn spring-boot:run &
cd zhiwei-web && npm run dev
```

自然对话（不指定工具名和 action）：

1. "打开 https://github.com 看看" — 验证：
   - 非 headless 能看到 Chromium
   - 对话卡是 BrowserToolCallCard（有截图缩略图点击可放大）

2. "帮我在这个页面搜索 playwright-mcp" — 验证：
   - snapshot 对话卡显示 elements 列表
   - click(index) 定位正确

3. "帮我登录招行网银（或任选需要登录的站点）" — 验证：
   - Agent 主动调 requestHumanTakeover
   - 前端弹出 HumanTakeoverModal，显示原因
   - 用户手动登录后点继续
   - Agent 恢复执行后续步骤

4. 取消路径：点 HumanTakeoverModal 的"取消任务" — 验证：
   - 后端收到 cancel，Agent 停止
   - 对话列表 turn 标记为已取消

- [ ] **Step 4: Docker 容器化验证（可选）**

```bash
BROWSER_HEADLESS=true docker compose up -d
# 触发浏览器任务，验证能在 headless 下正常跑
```

- [ ] **Step 5: 推分支 + 开 PR**

```bash
git push -u origin feature/browser-phase2-ux
gh pr create --base develop --title "feat(browser): Phase 2 可观察性 + 人机交接" --body "$(cat <<'EOF'
## 摘要

按 roadmap（docs/superpowers/plans/2026-04-24-browser-capability-roadmap.md）Phase 2 实施：
- 新增 `SuspendReason.BrowserTakeover` permit，复用现有 suspend-resume 机制
- 新增 `browser.requestHumanTakeover` action 供 Agent 触发人机接管
- 本地默认 `headless=false`，容器部署通过 `BROWSER_HEADLESS=true` 覆写
- 新建 `BrowserToolCallCard.vue` 浏览器工具专用对话卡，展示 URL / 截图 / elements 列表
- 新建 `HumanTakeoverModal.vue` 接管弹窗 + 倒计时
- `useChat.ts` 的 AGENT_SUSPENDED 处理 browser_takeover 类型

## 验证

- [x] `mvn test` / `npm run test:run` 全绿
- [x] 自然对话 E2E：snapshot → click(index) → 遇登录墙触发 takeover → 用户完成 → Agent 继续
- [x] 取消路径：用户点取消，Agent 停止
- [x] 容器部署 headless override 生效

## 前置

- Phase 1 `feature/browser-phase1-dom-indexing` 已合入 develop

## 后续

- Phase 3 Playwright CLI A/B：生产跑 2 周后按 roadmap 触发条件评估

EOF
)"
```

- [ ] **Step 6: PR 合入 develop**

---

## 风险与应对

| 风险 | 应对 |
|---|---|
| AgentOrchestrator 没有公开 suspend API | Task 2 Step 2 已列调研步骤；若确认没有则方案 B 走 `ToolResult.suspend(reason)` 或引入 `AgentSuspendPort` 接口 |
| sealed interface 新增 permit 触发多处 switch 报错 | Task 1 Step 5 已列应对方法；编译错误会显式指出所有未覆盖 case |
| headless=false 在 CI 环境失败 | CI 用 `BROWSER_HEADLESS=true` 覆写（GitHub Actions / 构建脚本补 env） |
| 用户在接管后关浏览器窗口 | takeover 超时后 Agent 收到 cancel 信号自动结束，回合标记失败 |
| Modal 倒计时与后端 timeout 不同步 | 后端 suspend 存的 timeoutSeconds 就是前端 Modal 倒计时起点，一致性由同一个 config 字段保证 |
| SSE 类型文件不存在 | Task 6 Step 4 是有则补，没有则新建 `types/sse.ts` |

---

## 执行顺序

Task 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8。

Task 4（headless）与 Task 5（ToolCallCard）理论上可并行，但串行更稳。Task 6 依赖 Task 1（reasonType 枚举），不能提前。

---

## 与 Phase 1 的关系

Phase 1 产出的 `browser.snapshot` 在本 Phase 中是**核心展示对象**：
- BrowserToolCallCard 对 snapshot action 特化渲染 elements 列表
- 登录墙识别依赖 snapshot 返回的 elements 是否含 password input

Phase 2 不改 Phase 1 的契约，只在其上增强 UX。
