# 记忆提取质量评估数据集 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构造 30 个边界测试场景的 YAML 对话剧本，覆盖实体提取、经验提取、巩固管线的判断边界，用于通过真实 API 对话验证提取质量。

**Architecture:** 纯数据文件（YAML），放在 `eval-datasets/` 目录。每个 YAML 包含用户消息序列 + 期望提取结果 + 不应提取的内容 + 审查要点。通过 Web UI 手动对话，人工审查提取结果。

**Tech Stack:** YAML 数据文件，无代码依赖

**相关 API 端点：**
- 对话：Web UI 或 `POST /api/chat/messages`
- 创建会话：`POST /api/chat/sessions`
- 查实体：`GET /api/memories/entities?type=PREFERENCE` 等
- 查经验：`GET /api/memories/entities?type=EXPERIENCE`
- 查模板：`GET /api/memories/templates`
- 查偏好规则：`GET /api/memories/preferences`
- 触发巩固：`POST /api/memories/consolidate`
- 触发去重：`POST /api/memories/deduplicate`（Task 1 新增）

---

### Task 0: 添加去重手动触发端点

`EntityDeduplicator` 目前只有 cron 调度，无法手动触发。巩固管线也不包含去重。需要在 `MemoryController` 加一个端点。

**Files:**
- Modify: `src/main/java/com/lifepilot/interaction/web/controller/MemoryController.java:738` 附近

- [ ] **Step 1: 在 MemoryController 中注入 EntityDeduplicator**

在构造函数参数中添加 `@Nullable EntityDeduplicator entityDeduplicator`，保存为字段。

- [ ] **Step 2: 添加去重触发端点**

在 `triggerConsolidation()` 方法后面添加：

```java
// ========== Req 9: 手动触发去重 ==========

/**
 * 手动触发实体去重。
 */
@PostMapping("/deduplicate")
public ApiResponse<Map<String, String>> triggerDeduplication() {
    requireMemoryEnabled();
    if (entityDeduplicator == null) {
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "去重服务未启用");
    }
    Thread.startVirtualThread(entityDeduplicator::runDedup);
    return ApiResponse.ok(Map.of("status", "accepted", "message", "去重任务已提交"));
}
```

注意：需要确认 `EntityDeduplicator` 的公开方法名。搜索 `@Scheduled` 标注的方法，确认入口方法名（可能是 `scheduledDedup()` 或 `runDedup()`），调用该方法。

- [ ] **Step 3: 编译验证**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/lifepilot/interaction/web/controller/MemoryController.java
git commit -m "feat(memory): 添加去重手动触发端点"
```

---

### Task 1: 创建目录结构和 README

**Files:**
- Create: `eval-datasets/README.md`
- Create: 目录结构

- [ ] **Step 1: 创建目录**

```bash
mkdir -p eval-datasets/entity-extraction/boundary-1-implicit-vs-explicit
mkdir -p eval-datasets/entity-extraction/boundary-2-temporary-vs-persistent
mkdir -p eval-datasets/entity-extraction/boundary-3-quoted-vs-self
mkdir -p eval-datasets/entity-extraction/boundary-4-contradiction-update
mkdir -p eval-datasets/entity-extraction/boundary-5-multi-entity
mkdir -p eval-datasets/entity-extraction/boundary-6-domain-boundary
mkdir -p eval-datasets/experience-extraction
mkdir -p eval-datasets/consolidation
```

- [ ] **Step 2: 创建 README.md**

```markdown
# 记忆提取质量评估数据集

通过边界对话场景验证实体提取、经验提取、巩固管线的判断能力。

## 使用方法

### 实体提取 / 经验提取测试

1. 启动系统：`mvn spring-boot:run`
2. 打开 Web UI（http://localhost:5173）
3. 按场景文件创建新会话
4. 依次发送 `conversation` 中的用户消息
5. 等待 5-10 秒（异步提取完成）
6. 检查提取结果：
   - 实体：`GET /api/memories/entities?type={TYPE}`
   - 经验：`GET /api/memories/entities?type=EXPERIENCE`
7. 对照 `expected` / `should_not_extract` 在文件末尾填写 `actual_result`

### 巩固管线测试

1. 先跑完 `prerequisite_sessions` 列出的前置场景
2. 触发巩固：`POST /api/memories/consolidate`
3. 触发去重：`POST /api/memories/deduplicate`
4. 检查结果变化（importance 变化、实体合并、偏好同步等）
5. 对照 `expected_after_trigger` 填写结果

### 结果标注格式

在场景文件末尾追加：

```yaml
actual_result:
  date: 2026-04-XX
  extracted:
    - type: PREFERENCE
      name: "实际提取的名称"
      confidence: 0.85
      verdict: CORRECT       # CORRECT | WRONG_TYPE | MISSING | UNEXPECTED
  missed: []
  unexpected: []
  notes: "补充说明"
```

## 场景目录

| 类别 | 场景数 | 测试焦点 |
|------|--------|---------|
| entity-extraction/boundary-1 | 3 | 隐含 vs 显式表达 |
| entity-extraction/boundary-2 | 3 | 临时状态 vs 持久事实 |
| entity-extraction/boundary-3 | 3 | 转述他人 vs 自我陈述 |
| entity-extraction/boundary-4 | 3 | 矛盾更新与假设性表述 |
| entity-extraction/boundary-5 | 2 | 多实体纠缠与角色区分 |
| entity-extraction/boundary-6 | 3 | 领域知识 vs 个人记忆边界 |
| experience-extraction | 6 | 经验归因、去重、门控 |
| consolidation | 6 | 合并、晋升、衰减、空间隔离 |
```

- [ ] **Step 3: 提交**

```bash
git add eval-datasets/
git commit -m "chore: 创建记忆提取评估数据集目录结构"
```

---

### Task 2: 实体提取 boundary-1 — 隐含 vs 显式

**Files:**
- Create: `eval-datasets/entity-extraction/boundary-1-implicit-vs-explicit/entity-implicit-01.yml`
- Create: `eval-datasets/entity-extraction/boundary-1-implicit-vs-explicit/entity-implicit-02.yml`
- Create: `eval-datasets/entity-extraction/boundary-1-implicit-vs-explicit/entity-implicit-03.yml`

- [ ] **Step 1: 创建 entity-implicit-01.yml — 隐含偏好-从不满推断**

```yaml
id: entity-implicit-01
name: 隐含偏好-从不满推断
category: entity-extraction/boundary-1-implicit-vs-explicit
difficulty: hard
tags: [preference, implicit, inference]

conversation:
  - "帮我推荐几家吃饭的地方"
  - "你推荐的这些都太远了，走过去要二十多分钟，有没有近一点的？我中午休息时间就一个小时"

expected:
  - operation: ADD
    type: PREFERENCE
    name_hint: "餐厅距离/就餐偏好"
    description_should_contain:
      - "近"
      - "步行距离"
    min_confidence: 0.6
    rationale: "用户反复强调距离问题并给出具体原因（午休时间短），这是稳定偏好而非临时要求"

should_not_extract:
  - type: PLACE
    name_hint: "公司附近/餐厅"
    reason: "没有提到具体地点名称，'附近'是相对概念"
  - type: HABIT
    name_hint: "午餐习惯"
    reason: "仅知道午休一小时，无法推断就餐习惯模式"
  - type: GOAL
    name_hint: "找餐厅"
    reason: "这是当前任务请求，不是长期目标"

review_notes: |
  核心边界：不满情绪中的隐含偏好能否被正确提取？
  1. 偏好的推断是否合理——"距离近"确实是从上下文推出的持久偏好
  2. 置信度是否适当降低——隐含推断应低于显式声明（如"我喜欢近的餐厅"）
  3. 不应过度推断——不能从"中午休息一小时"推出"工作很忙"等其他偏好
```

- [ ] **Step 2: 创建 entity-implicit-02.yml — 双重否定理解**

```yaml
id: entity-implicit-02
name: 双重否定理解
category: entity-extraction/boundary-1-implicit-vs-explicit
difficulty: hard
tags: [preference, negation, semantic]

conversation:
  - "最近同事们经常约我去打羽毛球"
  - "我不是不喜欢运动，就是最近项目太赶了，每天加班到九十点，实在没精力。等忙完这阵子肯定去"

expected:
  - operation: ADD
    type: PREFERENCE
    name_hint: "运动态度"
    description_should_contain:
      - "喜欢运动"
    min_confidence: 0.7
    rationale: "'不是不喜欢'= 喜欢，加上'肯定去'的意愿表达，是明确的正面态度"

should_not_extract:
  - type: HABIT
    name_hint: "运动/羽毛球"
    reason: "用户当前并不运动，不能提取为习惯"
  - type: PREFERENCE
    name_hint: "不喜欢运动"
    description_contains: ["不喜欢"]
    reason: "双重否定被错误解析为否定——这是最常见的提取错误"
  - type: HABIT
    name_hint: "加班"
    reason: "'最近'限定词说明这是临时状态，不是持久习惯"

review_notes: |
  核心边界：双重否定的语义解析。
  1. "不是不喜欢" → 喜欢。如果提取出"不喜欢运动"，说明 prompt 的否定理解有严重缺陷
  2. "最近项目太赶"中的"最近"是关键时间限定词，不应提取为持久状态
  3. "每天加班到九十点"虽然具体，但有"最近"限定，不应提取为 HABIT
```

- [ ] **Step 3: 创建 entity-implicit-03.yml — 行为暗示习惯**

```yaml
id: entity-implicit-03
name: 行为暗示-信息不完整的习惯
category: entity-extraction/boundary-1-implicit-vs-explicit
difficulty: hard
tags: [habit, implicit, incomplete]

conversation:
  - "又到周五了，帮我订一下老地方的位子"
  - "就那个靠窗的桌子，两个人，晚上七点"

expected:
  - operation: ADD
    type: HABIT
    name_hint: "周五聚餐"
    description_should_contain:
      - "周五"
    min_confidence: 0.5
    rationale: "'又到周五'+'老地方'+'老规矩'暗示固定模式，但信息有限，置信度应较低"

should_not_extract:
  - type: PLACE
    name_hint: "老地方/餐厅"
    reason: "没有说出具体餐厅名，'老地方'是指代不是实体名"
  - type: PERSON
    name_hint: "同伴"
    reason: "'两个人'没有透露对方身份"

review_notes: |
  核心边界：信息不完整时是否应该提取？
  1. 合理行为：提取低置信度的 HABIT（因为"又"暗示重复模式）
  2. 但不应填充未知信息——不知道餐厅名就不应虚构
  3. 如果完全不提取也可接受——关键是不要提取错误信息
  4. 这个场景测试的是"宁可不提取也不要提取错"的判断
```

- [ ] **Step 4: 提交**

```bash
git add eval-datasets/entity-extraction/boundary-1-implicit-vs-explicit/
git commit -m "chore: 添加实体提取边界测试 — 隐含 vs 显式"
```

---

### Task 3: 实体提取 boundary-2 — 临时状态 vs 持久事实

**Files:**
- Create: `eval-datasets/entity-extraction/boundary-2-temporary-vs-persistent/entity-temp-01.yml`
- Create: `eval-datasets/entity-extraction/boundary-2-temporary-vs-persistent/entity-temp-02.yml`
- Create: `eval-datasets/entity-extraction/boundary-2-temporary-vs-persistent/entity-temp-03.yml`

- [ ] **Step 1: 创建 entity-temp-01.yml — 学习中 vs 已有技能**

```yaml
id: entity-temp-01
name: 学习中 vs 已有技能
category: entity-extraction/boundary-2-temporary-vs-persistent
difficulty: hard
tags: [skill, temporal, learning]

conversation:
  - "我最近在学 Python，之前一直写 Java 的"
  - "有没有适合 Java 开发者转 Python 的教程推荐"

expected:
  - operation: ADD
    type: SKILL
    name_hint: "Java"
    description_should_contain:
      - "Java"
    min_confidence: 0.8
    rationale: "'一直写 Java'表明成熟技能，高置信度"
  - operation: ADD
    type: GOAL
    name_hint: "学习 Python"
    description_should_contain:
      - "学习"
      - "Python"
    min_confidence: 0.7
    rationale: "'在学'是进行中的目标，不是已掌握的技能"

should_not_extract:
  - type: SKILL
    name_hint: "Python"
    reason: "'在学'≠ 已掌握。提取为 SKILL 是经典错误——混淆学习意图和现有能力"
  - type: PREFERENCE
    name_hint: "Python/Java 偏好"
    reason: "用户没有表达偏好，学新语言不代表不喜欢旧语言"

review_notes: |
  核心边界：时态敏感的技能识别。
  1. "一直写 Java" → SKILL(Java)，高置信度
  2. "在学 Python" → GOAL(学 Python)，不是 SKILL(Python)
  3. 如果两者都被提取为 SKILL，说明 prompt 缺乏时态意识
  4. 额外检查：是否误生成 Java vs Python 的偏好比较
```

- [ ] **Step 2: 创建 entity-temp-02.yml — 情绪宣泄 vs 持久偏好**

```yaml
id: entity-temp-02
name: 情绪宣泄 vs 持久偏好
category: entity-extraction/boundary-2-temporary-vs-persistent
difficulty: medium
tags: [preference, emotion, transient]

conversation:
  - "帮我调一下这段代码的格式"
  - "这个格式化工具怎么又把我的缩进搞乱了，烦死了，每次都要手动调回来"

expected: []

should_not_extract:
  - type: PREFERENCE
    name_hint: "代码格式化/工具偏好"
    reason: "这是对当前工具的不满情绪，不是持久偏好。用户没说'我喜欢X格式'或'我不要Y工具'"
  - type: HABIT
    name_hint: "手动调整缩进"
    reason: "'每次'虽然暗示重复，但这是被迫行为不是主动习惯"
  - type: SKILL
    name_hint: "代码格式化"
    reason: "用户在求助，不是展示能力"

review_notes: |
  核心边界：情绪表达不应被记录为持久态度。
  1. 正确行为：完全不提取任何实体
  2. "烦死了"是情绪宣泄，不是"我不喜欢X"的偏好表达
  3. 如果提取出 PREFERENCE，说明 prompt 把临时情绪等同于稳定偏好
  4. 这个场景应该产出零提取——测试 prompt 的"不提取"能力
```

- [ ] **Step 3: 创建 entity-temp-03.yml — 出差 vs 常驻**

```yaml
id: entity-temp-03
name: 出差 vs 常住地
category: entity-extraction/boundary-2-temporary-vs-persistent
difficulty: medium
tags: [place, temporal, context]

conversation:
  - "我这周在上海出差，帮我推荐附近有什么好的咖啡馆"
  - "平时在北京工作，不太熟悉这边"

expected:
  - operation: ADD
    type: PLACE
    name_hint: "北京/工作地"
    description_should_contain:
      - "北京"
      - "工作"
    min_confidence: 0.8
    rationale: "'平时在北京工作'是稳定的地理事实"

should_not_extract:
  - type: PLACE
    name_hint: "上海"
    reason: "'这周在上海出差'有明确时间限定，是临时状态"
  - type: HABIT
    name_hint: "喝咖啡"
    reason: "找咖啡馆可能是临时需求，不能推断为习惯"
  - type: PREFERENCE
    name_hint: "咖啡偏好"
    reason: "没有表达对咖啡的偏好态度"

review_notes: |
  核心边界：同一对话中的时间限定词区分。
  1. "这周" → 临时，不应提取上海为常驻地
  2. "平时" → 持久，应提取北京为工作地
  3. 关键能力：同一对话中正确区分两个地点的时间属性
  4. 如果上海和北京都被提取为 PLACE，说明忽略了时间限定词
```

- [ ] **Step 4: 提交**

```bash
git add eval-datasets/entity-extraction/boundary-2-temporary-vs-persistent/
git commit -m "chore: 添加实体提取边界测试 — 临时 vs 持久"
```

---

### Task 4: 实体提取 boundary-3 — 转述 vs 自述

**Files:**
- Create: `eval-datasets/entity-extraction/boundary-3-quoted-vs-self/entity-quote-01.yml`
- Create: `eval-datasets/entity-extraction/boundary-3-quoted-vs-self/entity-quote-02.yml`
- Create: `eval-datasets/entity-extraction/boundary-3-quoted-vs-self/entity-quote-03.yml`

- [ ] **Step 1: 创建 entity-quote-01.yml — 引用同事观点**

```yaml
id: entity-quote-01
name: 引用同事观点-技术偏好归属
category: entity-extraction/boundary-3-quoted-vs-self
difficulty: hard
tags: [preference, attribution, person]

conversation:
  - "我同事小李觉得 TypeScript 比 JavaScript 好用多了，他说类型检查能减少很多 bug"
  - "你觉得这个说法有道理吗？我在考虑要不要也试试"

expected:
  - operation: ADD
    type: PERSON
    name_hint: "小李"
    description_should_contain:
      - "同事"
    min_confidence: 0.7
    rationale: "小李是用户的同事，这是用户社交关系中的确定信息"

should_not_extract:
  - type: PREFERENCE
    name_hint: "TypeScript/JavaScript 偏好"
    reason: "'小李觉得'明确标记了观点归属——这是小李的偏好，不是用户的"
  - type: SKILL
    name_hint: "TypeScript/JavaScript"
    reason: "用户'在考虑要不要试试'，说明尚未使用 TypeScript"
  - type: GOAL
    name_hint: "学 TypeScript"
    reason: "'在考虑'比'我要学'弱得多，还在犹豫阶段，不应提取为目标"

review_notes: |
  核心边界：观点归属的正确判断。
  1. "我同事小李觉得" → 归属小李，不归属用户
  2. PERSON(小李) 的提取是合理的——社交关系是用户事实
  3. 如果提取出用户的 PREFERENCE(TypeScript)，说明 prompt 忽略了归属标记
  4. "在考虑"是弱意图，不应升级为 GOAL
  5. 对比测试：如果用户说"我觉得 TypeScript 更好"，那就应该提取
```

- [ ] **Step 2: 创建 entity-quote-02.yml — 组织决策 vs 个人偏好**

```yaml
id: entity-quote-02
name: 组织决策 vs 个人偏好
category: entity-extraction/boundary-3-quoted-vs-self
difficulty: hard
tags: [organization, preference, mandate]

conversation:
  - "老板决定下个季度我们全部迁移到 Kubernetes，帮我整理一下 K8s 的学习路线"
  - "说实话我觉得现在的 Docker Compose 部署挺好的，但没办法，公司决定了"

expected:
  - operation: ADD
    type: PREFERENCE
    name_hint: "部署方式偏好"
    description_should_contain:
      - "Docker Compose"
      - "偏好"
    min_confidence: 0.7
    rationale: "'我觉得挺好的'是用户个人对 Docker Compose 的正面态度"
  - operation: ADD
    type: GOAL
    name_hint: "学习 Kubernetes"
    description_should_contain:
      - "K8s"
      - "学习"
    min_confidence: 0.7
    rationale: "虽然被动驱动，但'整理学习路线'是真实行动目标"

should_not_extract:
  - type: PREFERENCE
    name_hint: "Kubernetes 偏好"
    reason: "用户明确说'没办法'——迁移 K8s 是被迫的，不是偏好"
  - type: SKILL
    name_hint: "Kubernetes"
    reason: "用户需要学习路线，说明尚不掌握"

review_notes: |
  核心边界：组织指令 vs 个人态度的分离。
  1. "老板决定" → 组织决策，K8s 不是用户偏好
  2. "我觉得 Docker Compose 挺好的" → 用户真实偏好
  3. "没办法" → 关键信号词，标记了非自愿
  4. 正确提取应该是：PREFERENCE(Docker Compose, 正面) + GOAL(学 K8s)
  5. 错误提取：PREFERENCE(K8s, 正面) — 混淆了被迫执行和主动偏好
```

- [ ] **Step 3: 创建 entity-quote-03.yml — 复述文章内容**

```yaml
id: entity-quote-03
name: 复述文章-转述 vs 自身行为
category: entity-extraction/boundary-3-quoted-vs-self
difficulty: medium
tags: [habit, attribution, article]

conversation:
  - "我今天看到一篇文章说每天冥想十分钟能提高专注力"
  - "你觉得这个说法有科学依据吗"

expected: []

should_not_extract:
  - type: HABIT
    name_hint: "冥想"
    reason: "用户在引用文章观点，没有说自己做冥想"
  - type: PREFERENCE
    name_hint: "冥想/专注力"
    reason: "用户在求证信息真伪，不是表达偏好"
  - type: GOAL
    name_hint: "冥想/提高专注力"
    reason: "没有任何意愿表达，纯粹在讨论一个话题"

review_notes: |
  核心边界：信息讨论 vs 个人行为/意图。
  1. 正确行为：零提取——用户在讨论一篇文章，没有透露任何个人信息
  2. "我看到一篇文章说" → 转述来源，不是自述
  3. "你觉得有科学依据吗" → 求证态度，不是接受态度
  4. 如果提取出 HABIT(冥想) 或 GOAL(提高专注力)，说明 prompt 无法区分"讨论话题"和"表达意图"
  5. 对比测试：如果用户说"我最近开始每天冥想十分钟"，那就应该提取
```

- [ ] **Step 4: 提交**

```bash
git add eval-datasets/entity-extraction/boundary-3-quoted-vs-self/
git commit -m "chore: 添加实体提取边界测试 — 转述 vs 自述"
```

---

### Task 5: 实体提取 boundary-4 — 矛盾更新与假设性表述

**Files:**
- Create: `eval-datasets/entity-extraction/boundary-4-contradiction-update/entity-contra-01.yml`
- Create: `eval-datasets/entity-extraction/boundary-4-contradiction-update/entity-contra-02.yml`
- Create: `eval-datasets/entity-extraction/boundary-4-contradiction-update/entity-contra-03.yml`

- [ ] **Step 1: 创建 entity-contra-01.yml — 显式否定旧偏好**

```yaml
id: entity-contra-01
name: 显式否定旧偏好-颜色变更
category: entity-extraction/boundary-4-contradiction-update
difficulty: hard
tags: [preference, update, contradiction]

# 需要两个会话，先跑 session_1 建立基线
session_1:
  description: "建立蓝色偏好基线"
  conversation:
    - "帮我选个应用主题颜色，我喜欢蓝色系的，清爽一点"

# 间隔一段时间后跑 session_2
session_2:
  description: "否定旧偏好"
  conversation:
    - "之前选了蓝色主题，但用了一段时间觉得太冷了"
    - "还是换成暖色调吧，我现在更喜欢绿色系"

expected:
  - operation: UPDATE
    type: PREFERENCE
    name_hint: "主题颜色偏好"
    description_should_contain:
      - "绿色"
    min_confidence: 0.8
    rationale: "'现在更喜欢'是明确的偏好变更信号，应该 UPDATE 而非 ADD"

should_not_extract:
  - operation: ADD
    type: PREFERENCE
    name_hint: "绿色偏好"
    reason: "应该是 UPDATE 操作更新已有蓝色偏好，不是新增一条绿色偏好"
  - operation: NOOP
    type: PREFERENCE
    name_hint: "蓝色偏好"
    reason: "旧偏好不应该保持不变——'觉得太冷了'是明确否定"

review_notes: |
  核心边界：偏好变更时的操作类型判断。
  1. session_1 后应有 PREFERENCE(蓝色)
  2. session_2 后应变为 PREFERENCE(绿色)，蓝色版本被标记为历史
  3. 如果 ADD 了新的绿色偏好而蓝色仍然 is_current=true → AUDN 判断失败
  4. 如果整体变成 NOOP → 提取器没有捕获到变更信号
  5. 检查：entity 的 version 是否递增，旧版本 validTo 是否被设置
```

- [ ] **Step 2: 创建 entity-contra-02.yml — 工作身份变化**

```yaml
id: entity-contra-02
name: 工作身份变化-跳槽
category: entity-extraction/boundary-4-contradiction-update
difficulty: hard
tags: [organization, update, lifecycle]

session_1:
  description: "建立工作身份基线"
  conversation:
    - "我在阿里做后端开发，帮我看一下这段 Java 代码"

session_2:
  description: "身份变更"
  conversation:
    - "跟你说个好消息，我上周跳槽了"
    - "现在在字节跳动做架构师，团队还在组建中"

expected:
  - operation: ADD
    type: ORGANIZATION
    name_hint: "字节跳动"
    description_should_contain:
      - "当前工作"
      - "架构师"
    min_confidence: 0.9
  - operation: UPDATE
    type: ORGANIZATION
    name_hint: "阿里"
    description_should_contain:
      - "之前"
    rationale: "旧工作单位应标记为历史，validTo 应被设置"
  - operation: ADD
    type: SKILL
    name_hint: "架构设计"
    min_confidence: 0.6
    rationale: "'做架构师'暗示具备架构能力，但新角色可能还在适应"

should_not_extract:
  - type: ORGANIZATION
    name_hint: "阿里"
    with_is_current: true
    reason: "已跳槽，阿里不应保持 is_current 状态"

review_notes: |
  核心边界：身份生命周期管理。
  1. 核心检查：阿里的 validTo 是否被设置，is_current 是否变为 false
  2. 字节跳动应该是新 ADD，关联"架构师"角色
  3. "团队还在组建中" → 额外上下文，可存为 properties 但不应生成新实体
  4. session_1 中的 SKILL(Java/后端开发) 是否在 session_2 后仍有效——应该是的
  5. 如果阿里仍 is_current=true，说明矛盾检测或 UPDATE 逻辑有缺陷
```

- [ ] **Step 3: 创建 entity-contra-03.yml — 假设性目标**

```yaml
id: entity-contra-03
name: 假设性目标-条件意愿
category: entity-extraction/boundary-4-contradiction-update
difficulty: hard
tags: [goal, conditional, uncertainty]

conversation:
  - "如果明年能攒够钱的话，我特别想去日本旅游"
  - "不过还不确定，先看看到时候的经济情况再说吧"

expected: []

should_not_extract:
  - type: GOAL
    name_hint: "去日本旅游"
    reason: "'如果'+'不确定'+'先看看再说'三重弱化——条件未满足、自我否定、推迟决策"
  - type: PREFERENCE
    name_hint: "日本/旅游"
    reason: "'特别想去'表达了兴趣但不是持久偏好，且被后续不确定性覆盖"

review_notes: |
  核心边界：条件性+不确定性的叠加判断。
  1. "如果...的话" → 条件前提未满足
  2. "不确定" → 明确的不确定性表达
  3. "先看看再说" → 推迟决策
  4. 三个信号叠加，合理行为是不提取
  5. 但如果只有"如果明年能攒够钱我想去日本"没有后面的否定——那可以低置信度提取 GOAL
  6. 这个场景测试的是：prompt 是否能综合多个弱化信号做出"不提取"的判断
  7. 可接受的替代结果：极低置信度（<0.4）的 GOAL，但不推荐
```

- [ ] **Step 4: 提交**

```bash
git add eval-datasets/entity-extraction/boundary-4-contradiction-update/
git commit -m "chore: 添加实体提取边界测试 — 矛盾更新与假设性"
```

---

### Task 6: 实体提取 boundary-5 — 多实体纠缠

**Files:**
- Create: `eval-datasets/entity-extraction/boundary-5-multi-entity/entity-multi-01.yml`
- Create: `eval-datasets/entity-extraction/boundary-5-multi-entity/entity-multi-02.yml`

- [ ] **Step 1: 创建 entity-multi-01.yml — 一句话多实体+关系+时间**

```yaml
id: entity-multi-01
name: 关系链提取-一句话四实体
category: entity-extraction/boundary-5-multi-entity
difficulty: hard
tags: [person, organization, project, relation, temporal]

conversation:
  - "我和老同学王磊去年在腾讯一起做了个推荐系统的项目，上个月刚结项"
  - "现在他去了百度做搜索，我们还经常聊技术"

expected:
  - operation: ADD
    type: PERSON
    name_hint: "王磊"
    description_should_contain:
      - "老同学"
    min_confidence: 0.8
  - operation: ADD
    type: ORGANIZATION
    name_hint: "腾讯"
    min_confidence: 0.7
    rationale: "用户曾在腾讯工作（或合作），但'去年'表明不一定是当前状态"
  - operation: ADD
    type: ORGANIZATION
    name_hint: "百度"
    min_confidence: 0.7
    rationale: "王磊当前所在，不是用户的"
  - operation: ADD
    type: PROJECT
    name_hint: "推荐系统"
    description_should_contain:
      - "已结项"
    min_confidence: 0.7
    rationale: "'上个月刚结项'表明项目已结束，validTo 应设置"

should_not_extract:
  - type: ORGANIZATION
    name_hint: "百度"
    description_contains: ["用户在百度"]
    reason: "百度是王磊的当前单位，不是用户的。归属必须正确"
  - type: SKILL
    name_hint: "推荐系统"
    reason: "做过推荐系统项目不等于精通推荐系统——用户没有对技能做陈述"

review_notes: |
  核心边界：单句多实体的拆分准确性和关系归属。
  1. 四个实体：PERSON(王磊) + ORG(腾讯) + ORG(百度) + PROJECT(推荐系统)
  2. 关键关系：王磊-oldClassmate-用户、王磊-worksAt-百度、项目-at-腾讯
  3. 归属陷阱：百度是王磊的，不是用户的
  4. 时间陷阱：项目已结项(validTo)，腾讯可能是历史(去年)
  5. 如果提取出 SKILL(推荐系统) → 过度推断
  6. 如果缺少某个实体 → 多实体拆分能力不足
  7. 关系是否被正确创建——这可能是最难验证的部分
```

- [ ] **Step 2: 创建 entity-multi-02.yml — 角色 vs 实体混淆**

```yaml
id: entity-multi-02
name: 角色描述 vs 独立实体
category: entity-extraction/boundary-5-multi-entity
difficulty: medium
tags: [person, role, confusion]

conversation:
  - "帮我安排和我们产品经理李总明天下午三点的会议"
  - "主要讨论一下下个版本的需求优先级排序"

expected:
  - operation: ADD
    type: PERSON
    name_hint: "李总"
    description_should_contain:
      - "产品经理"
    min_confidence: 0.7
    rationale: "李总是具体的人，'产品经理'是描述角色的属性"

should_not_extract:
  - type: PERSON
    name_hint: "产品经理"
    reason: "'产品经理'是角色描述不是人名，不应作为独立实体"
  - type: PROJECT
    name_hint: "下个版本"
    reason: "'下个版本'太模糊，没有项目名称，不应提取"
  - type: EVENT
    name_hint: "会议"
    reason: "单次会议安排是临时任务，不是需要持久化的事件"
  - type: HABIT
    name_hint: "开会"
    reason: "单次会议请求不能推断为习惯"

review_notes: |
  核心边界：角色是属性不是实体。
  1. "产品经理李总" → PERSON(李总, role=产品经理)，不是两个实体
  2. 如果生成了 PERSON(产品经理) + PERSON(李总) → 角色/人名拆分错误
  3. "明天下午三点的会议" → 临时事件，不应持久化
  4. "下个版本" → 太泛，缺乏具体项目上下文
```

- [ ] **Step 3: 提交**

```bash
git add eval-datasets/entity-extraction/boundary-5-multi-entity/
git commit -m "chore: 添加实体提取边界测试 — 多实体纠缠"
```

---

### Task 7: 实体提取 boundary-6 — 领域知识边界

**Files:**
- Create: `eval-datasets/entity-extraction/boundary-6-domain-boundary/entity-domain-01.yml`
- Create: `eval-datasets/entity-extraction/boundary-6-domain-boundary/entity-domain-02.yml`
- Create: `eval-datasets/entity-extraction/boundary-6-domain-boundary/entity-domain-03.yml`

- [ ] **Step 1: 创建 entity-domain-01.yml — 用户设备 vs 技术知识**

```yaml
id: entity-domain-01
name: 用户设备 vs 领域知识
category: entity-extraction/boundary-6-domain-boundary
difficulty: hard
tags: [user-fact, domain, device]

conversation:
  - "我现在用的 MacBook Pro M3 芯片的，编译大项目特别慢，经常要等十几分钟"
  - "有什么办法能优化编译速度吗"

expected:
  - operation: ADD
    type: CUSTOM
    name_hint: "使用设备/MacBook"
    description_should_contain:
      - "MacBook Pro"
      - "M3"
    min_confidence: 0.7
    rationale: "用户的个人设备是 USER_FACT，影响未来工具推荐和方案选择"

should_not_extract:
  - type: TOPIC
    name_hint: "M3 芯片性能"
    reason: "芯片性能参数是领域知识，属于 datastore 范畴"
  - type: SKILL
    name_hint: "编译优化"
    reason: "用户在求助，不是展示能力"
  - type: PREFERENCE
    name_hint: "MacBook/Mac 偏好"
    reason: "'我用的是'描述现状，不是'我喜欢'表达偏好"

review_notes: |
  核心边界：用户事实 vs 领域知识的分离。
  1. "我用的 MacBook Pro M3" → USER_FACT(设备信息)——这对未来推荐有价值
  2. M3 芯片的性能特征 → 领域知识，不应进入个人记忆
  3. "编译慢" → 当前问题上下文，不是持久事实
  4. 如果提取了 TOPIC(M3/编译优化) → 领域知识泄漏到个人记忆
  5. 分离关键：同一句话中"我用的"是个人信息，"M3 芯片"是领域概念
```

- [ ] **Step 2: 创建 entity-domain-02.yml — 经验发现 vs 公共知识**

```yaml
id: entity-domain-02
name: 经验发现 vs 已知事实
category: entity-extraction/boundary-6-domain-boundary
difficulty: hard
tags: [experience, domain, discovery]

conversation:
  - "我昨天排查了半天 bug，最后发现是 SQLite 在 WAL 模式下长事务会阻塞其他写入"
  - "以后写代码得注意事务要尽量短，这个教训挺深的"

expected:
  - operation: ADD
    type: EXPERIENCE
    name_hint: "SQLite 事务排查"
    description_should_contain:
      - "WAL"
      - "长事务"
      - "阻塞"
    min_confidence: 0.7
    rationale: "虽然事实本身是公知，但'排查了半天'+'教训挺深'表明这是用户的个人经验教训"

should_not_extract:
  - type: TOPIC
    name_hint: "SQLite WAL 模式"
    reason: "WAL 模式的技术细节是领域知识"
  - type: SKILL
    name_hint: "SQLite/数据库"
    reason: "排查了一次 bug 不等于精通数据库"

review_notes: |
  核心边界：这个场景最具争议性。
  1. 事实层面："SQLite WAL 模式下长事务阻塞写入"是公开文档中的已知事实
  2. 经验层面："排查了半天"+"教训挺深"让这个事实对用户有特殊意义
  3. 合理判断：提取为 EXPERIENCE 是正确的——不是记录 SQLite 知识，而是记录"用户踩过这个坑"
  4. EXPERIENCE 的 lessons 应该包含"保持事务尽量短"而非 SQLite WAL 的技术细节
  5. 如果提取为 TOPIC → 混淆了"用户经历"和"领域知识"
```

- [ ] **Step 3: 创建 entity-domain-03.yml — 团队事实 + 个人偏好混合句**

```yaml
id: entity-domain-03
name: 混合句分离-团队技术 + 个人偏好
category: entity-extraction/boundary-6-domain-boundary
difficulty: hard
tags: [preference, project, separation]

conversation:
  - "我们团队的项目用的 React 框架"
  - "说实话我个人更喜欢 Vue，API 设计更直觉一些，但团队已经定了就不折腾了"

expected:
  - operation: ADD
    type: PREFERENCE
    name_hint: "前端框架偏好"
    description_should_contain:
      - "Vue"
      - "喜欢"
    min_confidence: 0.8
    rationale: "'我个人更喜欢'是明确的第一人称偏好表达"

should_not_extract:
  - type: PREFERENCE
    name_hint: "React"
    reason: "React 是团队决策不是个人偏好。'团队定了就不折腾'恰好说明用户不主动选择 React"
  - type: SKILL
    name_hint: "React/Vue"
    reason: "使用某框架不等于精通，且没有技能声明"
  - type: PROJECT
    name_hint: "团队项目"
    reason: "没有给出项目名称，太泛无法作为 PROJECT 实体"

review_notes: |
  核心边界：同一话题中区分团队决策和个人态度。
  1. "我们团队用的 React" → 团队/组织层面的事实（可能是 ORG 决策）
  2. "我个人更喜欢 Vue" → 用户个人 PREFERENCE
  3. "团队定了就不折腾" → 强化了"React 不是个人选择"的信号
  4. 正确结果：只提取 PREFERENCE(Vue)，不提取 PREFERENCE(React)
  5. 如果两个框架都被提取为偏好 → prompt 没有区分"我们用"和"我喜欢"
  6. 可选：提取 ORG/PROJECT 决策(React)，但不应标记为 PREFERENCE
```

- [ ] **Step 4: 提交**

```bash
git add eval-datasets/entity-extraction/boundary-6-domain-boundary/
git commit -m "chore: 添加实体提取边界测试 — 领域知识边界"
```

---

### Task 8: 经验提取场景

**Files:**
- Create: `eval-datasets/experience-extraction/exp-attr-01.yml`
- Create: `eval-datasets/experience-extraction/exp-attr-02.yml`
- Create: `eval-datasets/experience-extraction/exp-attr-03.yml`
- Create: `eval-datasets/experience-extraction/exp-attr-04.yml`
- Create: `eval-datasets/experience-extraction/exp-attr-05.yml`
- Create: `eval-datasets/experience-extraction/exp-attr-06.yml`

注意：经验提取依赖 Agent 实际执行工具，用户消息只能 **引导** Agent 行为，不能精确控制。
每个场景的 review_notes 重点描述"观察什么"而非"精确期望什么"。

- [ ] **Step 1: 创建 exp-attr-01.yml — 多工具协作成功**

```yaml
id: exp-attr-01
name: 多工具协作-应提取有效经验
category: experience-extraction
difficulty: medium
tags: [multi-tool, success, baseline]

conversation:
  - "帮我查一下北京明天的天气情况，然后根据天气帮我推荐适合的户外活动"

trigger_expectation: |
  Agent 应该先调用天气查询工具，再基于结果推理活动推荐。
  如果系统有天气工具 → 至少 2 次工具调用 → 触发经验提取门槛

expected:
  - type: EXPERIENCE
    scenario_should_contain: ["天气", "推荐"]
    strategy_should_contain: ["查询", "推荐"]
    success: true
    min_lessons: 1
    rationale: "多工具组合完成复合任务，值得提取为经验"

should_not_extract:
  - type: EXPERIENCE
    scenario: "查询天气"
    reason: "如果拆成两条独立经验（查天气 + 推荐活动），说明经验粒度过细"

review_notes: |
  经验提取的基线测试：
  1. 是否触发了经验提取？（检查 EXPERIENCE 类型实体）
  2. scenario 是否描述的是"复合任务"而非单步操作？
  3. lessons 是否包含具体发现（如"先查天气再推荐"的策略）而非泛泛之谈？
  4. 如果没有触发提取 → 检查是否满足触发条件（≥2 工具调用）
  5. 如果系统没有天气工具，Agent 可能只用了搜索 → 仍然可能触发
```

- [ ] **Step 2: 创建 exp-attr-02.yml — 用户中途纠正**

```yaml
id: exp-attr-02
name: 用户中途纠正方向
category: experience-extraction
difficulty: hard
tags: [correction, attribution, lesson]

conversation:
  - "帮我写一篇关于人工智能在医疗领域应用的文章"
  - "不对不对，我不是要科普文章，我需要的是一篇面向技术团队的架构选型分析报告，重点比较几种方案的优缺点"
  - "嗯对，这个方向对了，就按这个思路继续"

trigger_expectation: |
  Agent 第一次可能生成科普风格内容 → 用户纠正 → Agent 调整方向。
  这个过程应该体现在经验中：初始理解偏差 + 用户反馈 + 方向调整

expected:
  - type: EXPERIENCE
    success: true
    lessons_should_contain: ["需求理解", "用户反馈"]
    rationale: "虽然最终成功，但经历了方向纠正，这个纠正过程本身就是有价值的经验"

should_not_extract:
  - type: EXPERIENCE
    failureAttribution: "strategy"
    reason: "最终成功了，不应标记为策略失败。但 lessons 应记录纠偏过程"

review_notes: |
  核心测试：用户纠正行为是否被捕获为经验的一部分。
  1. 经验的 success 应为 true（最终完成了任务）
  2. 但 lessons 中应该包含类似"需注意区分科普 vs 技术分析的需求"
  3. 如果 lessons 完全没提到方向调整 → 经验提取 prompt 忽略了对话动态
  4. 如果标记为失败 → 过度惩罚了一次自然的需求澄清过程
  5. 这个场景测试"纠正 ≠ 失败"的判断
```

- [ ] **Step 3: 创建 exp-attr-03.yml — 多任务部分成功**

```yaml
id: exp-attr-03
name: 多任务部分成功-混合结果
category: experience-extraction
difficulty: hard
tags: [partial-success, multi-task, attribution]

conversation:
  - "帮我做三件事：第一，查一下北京今天的天气；第二，帮我在日历上创建一个明天上午十点的团队会议；第三，搜索一下最近有什么值得关注的 AI 新闻"

trigger_expectation: |
  任务 1（查天气）和任务 3（搜新闻）可能成功（如果有对应工具）。
  任务 2（创建日历事件）大概率失败（可能没有日历工具）。
  形成部分成功的局面。

expected:
  - type: EXPERIENCE
    success: false
    lessons_should_reflect: "部分任务成功的经验仍有价值"
    rationale: "即使整体标记为失败，成功部分的策略应该被记录"

review_notes: |
  核心测试：部分成功场景的处理。
  1. 整体 success 可能是 false（不是所有任务都完成）
  2. 但 lessons 应该分别记录成功和失败的部分
  3. failureAttribution 应该区分：日历任务失败是"system"（缺少工具）还是"strategy"
  4. 如果全部标记为 strategy 失败 → 归因过于粗糙
  5. 如果成功任务的经验也被丢弃 → 经验提取的粒度太粗
  6. 理想结果：提取出经验，lessons 包含"天气查询成功""日历功能不可用"等区分性信息
```

- [ ] **Step 4: 创建 exp-attr-04.yml — 单步简单查询不应提取**

```yaml
id: exp-attr-04
name: 单步简单查询-不应提取
category: experience-extraction
difficulty: easy
tags: [simple, gate, negative]

conversation:
  - "今天星期几"

trigger_expectation: |
  Agent 直接回答，可能不调用任何工具。
  即使调用了工具，也是单步操作，不应触发经验提取。

expected: []

should_not_extract:
  - type: EXPERIENCE
    reason: "单步操作没有策略价值。经验提取门槛要求 ≥2 工具调用或 ≥2 不同工具。"

review_notes: |
  门控测试：验证经验提取的触发条件是否生效。
  1. 正确行为：完全不触发经验提取
  2. 如果生成了 EXPERIENCE → 触发门槛设置过低或被绕过
  3. 补充检查：查看日志中是否有"经验提取: 跳过"相关输出
```

- [ ] **Step 5: 创建 exp-attr-05.yml — 重复模式去重**

```yaml
id: exp-attr-05
name: 重复模式-第三次应去重
category: experience-extraction
difficulty: hard
tags: [dedup, repeated, boost]

# 需要跑 3 次，每次在新会话中
session_1:
  conversation:
    - "帮我查一下上海今天的天气，然后推荐适合今天穿的衣服"

session_2:
  conversation:
    - "查一下广州的天气，推荐一下穿什么合适"

session_3:
  conversation:
    - "深圳今天天气怎么样，穿什么衣服出门好"

trigger_expectation: |
  三次任务模式高度相似：查天气 → 推荐穿搭。
  session_1 应创建新 EXPERIENCE。
  session_2/3 应匹配已有经验，boost importance 而非新建。

expected:
  - check: dedup_effective
    description: "最终应只有 1 条（或最多 2 条）天气+穿搭相关经验"
  - check: importance_boosted
    description: "该经验的 importanceScore 应高于初始值 0.6"

should_not_extract:
  - check: three_separate_experiences
    reason: "如果产生了 3 条几乎相同的经验 → 去重失效"

review_notes: |
  去重能力测试：
  1. 跑完三个 session 后，查询 type=EXPERIENCE 的实体
  2. 搜索含"天气"+"穿"关键词的经验
  3. 如果只有 1 条且 importance > 0.6 → 去重正常
  4. 如果有 2 条但相似 → 去重阈值可能需要调低
  5. 如果有 3 条 → 向量相似度计算或阈值有问题
  6. 检查 importanceScore 是否随重复而递增
```

- [ ] **Step 6: 创建 exp-attr-06.yml — 系统故障归因**

```yaml
id: exp-attr-06
name: 系统故障-不应污染经验库
category: experience-extraction
difficulty: medium
tags: [system-failure, attribution, filter]

conversation:
  - "帮我搜索一下最近一周关于大语言模型的学术论文，整理出前五篇最有影响力的"

trigger_expectation: |
  如果搜索工具出现超时或错误（网络问题、API 限流等），
  Agent 可能重试多次后仍然失败或降级处理。
  这类系统层面的失败不应记录为策略经验。

expected:
  - if_system_error:
      type: EXPERIENCE
      failureAttribution: "system"
      should_be_filtered: true
      rationale: "系统故障归因的经验应被 ExperienceSummarizer 过滤，不写入记忆"
  - if_success:
      type: EXPERIENCE
      success: true
      rationale: "如果正常完成，可以正常提取经验"

review_notes: |
  归因过滤测试：
  1. 如果工具执行中有明显系统错误（超时、500 等）→ failureAttribution 应为 "system"
  2. system 归因的经验应被过滤（日志中应有"跳过系统归因经验"）
  3. 如果工具错误被归因为 "strategy" → 归因判断有误
  4. 注意：这个场景依赖实际的工具执行情况，如果工具正常运行则转为正例测试
  5. 可以通过临时降低网络超时时间来制造系统错误条件
```

- [ ] **Step 7: 提交**

```bash
git add eval-datasets/experience-extraction/
git commit -m "chore: 添加经验提取边界测试场景"
```

---

### Task 9: 巩固管线场景

**Files:**
- Create: `eval-datasets/consolidation/consol-merge-01.yml`
- Create: `eval-datasets/consolidation/consol-merge-02.yml`
- Create: `eval-datasets/consolidation/consol-pref-01.yml`
- Create: `eval-datasets/consolidation/consol-decay-01.yml`
- Create: `eval-datasets/consolidation/consol-promote-01.yml`
- Create: `eval-datasets/consolidation/consol-space-01.yml`

- [ ] **Step 1: 创建 consol-merge-01.yml — 同义人物实体合并**

```yaml
id: consol-merge-01
name: 同义人物实体合并
category: consolidation/entity-merge
difficulty: hard
tags: [dedup, person, alias]

prerequisite_sessions:
  - session_a:
      conversation:
        - "我同事小王帮我 review 了代码，提了几个很好的建议"
  - session_b:
      conversation:
        - "王磊今天给我分享了一个很有用的 Git 技巧"
  - session_c:
      conversation:
        - "老王说他最近在研究 Rust，推荐我也看看"

setup_notes: |
  三个会话分别用了"小王"/"王磊"/"老王"指代同一个人。
  但系统不知道它们是同一人——需要去重来发现。
  注意：如果对话中没有明确关联三个名字，去重只能依赖向量相似度。
  实际上这三个名字的向量可能不够相似，因为：
  - "小王" vs "王磊" 语义差异大
  - 只有"王磊" vs "老王"可能被检测为相似
  这个场景测试的是去重的极限能力。

trigger: dedup

expected_after_trigger:
  - check: similarity_detection
    description: "至少'王磊'和'老王'应被识别为候选重复对"
  - check: merge_quality
    description: "合并后主实体描述应综合多个来源（同事、review代码、Git技巧、研究Rust）"

should_not_happen:
  - check: over_merge
    description: "不应把无关的 PERSON 实体误合并"

review_notes: |
  去重极限测试：
  1. 先检查前置对话是否成功创建了 3 个 PERSON 实体
  2. 触发去重后，检查 entity_merge_log 表
  3. "小王"和"王磊"语义差异大，很可能不会被检测为重复 → 这是预期的
  4. "王磊"和"老王"有共同的"王"字，可能被检测 → 取决于向量模型
  5. 如果三个都没合并 → 说明纯向量去重对中文别名效果有限
  6. 如果误合并了其他人 → 去重阈值太低
  7. 这个场景可能暴露"需要名称归一化"的改进方向
```

- [ ] **Step 2: 创建 consol-merge-02.yml — 经验语义合并**

```yaml
id: consol-merge-02
name: 相似经验语义合并
category: consolidation/experience-merge
difficulty: hard
tags: [experience, merge, generalization]

prerequisite_sessions:
  - ref: exp-attr-05
    note: "跑完 exp-attr-05 的三个 session 后，应有若干天气+穿搭经验"

trigger: consolidation

expected_after_trigger:
  - check: experience_merged
    description: "如果存在多条相似天气经验，巩固后应被合并为 meta-experience"
  - check: lessons_generalized
    description: "合并后的 lessons 应是泛化描述（如'先查天气再推荐'）而非某次具体城市"
  - check: source_archived
    description: "被合并的原始经验应被归档（is_current=false）"

should_not_happen:
  - check: lessons_concatenated
    description: "合并不是简单拼接——不应出现'上海天气;广州天气;深圳天气'的拼接式 lessons"

review_notes: |
  经验合并质量测试：
  1. 依赖 exp-attr-05 的前置数据
  2. 触发巩固后，检查 EXPERIENCE 实体的变化
  3. 合并后的 meta-experience 的 lessons 应该是抽象化的
  4. 好的合并："查询目标城市天气 → 根据气温和天气状况推荐穿搭"
  5. 差的合并："上海查天气推荐穿搭 + 广州查天气推荐穿搭"
  6. 原始经验是否被正确归档（is_current=false, 有 parent_entity_id 指向 meta）
```

- [ ] **Step 3: 创建 consol-pref-01.yml — 矛盾偏好巩固同步**

```yaml
id: consol-pref-01
name: 矛盾偏好巩固到 L4
category: consolidation/preference-sync
difficulty: medium
tags: [preference, sync, l4]

prerequisite_sessions:
  - ref: entity-contra-01
    note: "跑完两个 session 后，应有绿色偏好覆盖蓝色偏好"

trigger: consolidation

expected_after_trigger:
  - check: preference_rule_synced
    api: "GET /api/memories/preferences"
    description: "L4 的 PreferenceRule 中应存在颜色偏好，且值为绿色相关"
  - check: old_preference_not_synced
    description: "蓝色偏好不应出现在 L4 PreferenceRule 中（已被覆盖）"

review_notes: |
  偏好同步测试：
  1. 依赖 entity-contra-01 的前置数据
  2. 巩固后检查 GET /api/memories/preferences
  3. 只有 is_current=true 的 PREFERENCE 实体应同步到 L4
  4. 如果蓝色和绿色都出现在 preferences 中 → 巩固时没检查 is_current
  5. 如果完全没有颜色偏好 → 巩固同步逻辑可能有 bug
```

- [ ] **Step 4: 创建 consol-decay-01.yml — 低频实体不应涨分**

```yaml
id: consol-decay-01
name: 低频实体巩固后不涨分
category: consolidation/importance-stability
difficulty: medium
tags: [importance, frequency, stability]

prerequisite_sessions:
  - ref: entity-temp-01
    note: "跑完后应有 SKILL(Java) 实体"
  - additional:
      description: "再跑 3-5 个不提及 Java 的普通对话，制造'低频'条件"
      conversations:
        - ["帮我查一下明天北京天气"]
        - ["推荐几本好看的科幻小说"]
        - ["帮我翻译一段英文"]

trigger: consolidation

expected_after_trigger:
  - check: importance_stable
    entity_hint: "Java"
    description: "Java 技能的 importanceScore 不应在巩固后上升（因为后续对话未提及）"

should_not_happen:
  - check: importance_boosted
    entity_hint: "Java"
    description: "如果没被提及但 importance 仍上升 → 巩固的 mention counting 有误"

review_notes: |
  重要性稳定性测试：
  1. 记录巩固前 Java 实体的 importanceScore
  2. 触发巩固
  3. 再次查看 importanceScore
  4. 应该不变（后续对话未提及 Java）
  5. 如果上升 → 提及计数可能有误匹配（如某个对话中偶然出现 Java 相关词）
  6. 这个测试验证巩固的"不该动的别动"原则
```

- [ ] **Step 5: 创建 consol-promote-01.yml — 高频经验晋升 L4**

```yaml
id: consol-promote-01
name: 高频经验晋升为程序模板
category: consolidation/promotion
difficulty: hard
tags: [experience, promotion, l4, template]

prerequisite_sessions:
  - ref: exp-attr-05
    note: "需要天气+穿搭经验存在，且 importanceScore >= 0.8, accessCount >= 3"
  - manual_boost:
      description: |
        如果自然场景无法满足晋升条件，可通过 API 手动调整：
        PUT /api/memories/entities/{id}，修改 importanceScore 和 accessCount

trigger: consolidation

expected_after_trigger:
  - check: template_created
    api: "GET /api/memories/templates"
    description: "应出现一个与天气穿搭相关的 ProcedureTemplate"
  - check: template_has_steps
    description: "模板应包含步骤（如 step1:查天气, step2:推荐穿搭）"
  - check: source_archived
    description: "被晋升的 EXPERIENCE 应被归档"

review_notes: |
  晋升机制测试：
  1. 确认前置条件：经验的 importanceScore >= 0.8 且 accessCount >= 3
  2. 如果条件不满足，通过 API 手动调整后再触发巩固
  3. 巩固后检查 GET /api/memories/templates
  4. 新模板的 triggerIntent 应该是类似"查天气推荐穿搭"
  5. 模板的 steps 应该有合理的工具调用序列
  6. 原始 EXPERIENCE 的 is_current 应变为 false
  7. 如果没有创建模板 → 晋升条件判断或模板创建逻辑有问题
```

- [ ] **Step 6: 创建 consol-space-01.yml — 跨空间不应合并**

```yaml
id: consol-space-01
name: 跨空间隔离-去重不跨界
category: consolidation/space-isolation
difficulty: hard
tags: [space, isolation, dedup, boundary]

prerequisite_sessions:
  - session_personal:
      description: "在个人空间创建一个 Python 相关实体"
      conversation:
        - "我个人比较喜欢用 Python 写脚本，简洁高效"
  - session_domain:
      description: "在领域空间（需要开启领域学习的会话）创建类似实体"
      setup: "需要在一个关联了 datastore 且开启了 domainLearning 的会话中操作"
      conversation:
        - "Python 是一种高级编程语言，广泛用于数据科学和机器学习领域"

setup_notes: |
  这个场景需要两个不同空间（personal 和 domain）中存在类似主题的实体。
  domain 空间的实体需要通过开启了 domainLearning 的会话来创建。
  如果无法轻松创建 domain 空间实体，可通过 API 直接创建用于测试。

trigger: dedup

expected_after_trigger:
  - check: no_cross_space_merge
    description: "personal 空间的 Python 偏好和 domain 空间的 Python 知识不应被合并"
  - check: both_entities_exist
    description: "两个空间各自的实体应独立保留"

should_not_happen:
  - check: cross_space_merge
    description: "不同 memory_space 的实体被合并 → 空间隔离边界被突破"

review_notes: |
  空间隔离测试：
  1. 这是最重要的安全边界测试之一
  2. personal 的"我喜欢 Python"和 domain 的"Python 是一种语言"语义相似但用途不同
  3. 去重时 EntityDeduplicator 是否在查询时带上 space 过滤条件？
  4. 如果发生跨空间合并 → 严重 bug，领域知识和个人记忆的边界被破坏
  5. 检查 EntityDeduplicator 代码：findAllCurrent() 是否按 space 分组
```

- [ ] **Step 7: 提交**

```bash
git add eval-datasets/consolidation/
git commit -m "chore: 添加巩固管线边界测试场景"
```

---

### Task 10: 最终检查与提交

- [ ] **Step 1: 检查文件完整性**

```bash
find eval-datasets -name "*.yml" | wc -l
# Expected: 27 个 YAML 文件
find eval-datasets -name "*.md" | wc -l
# Expected: 1 个 README
```

- [ ] **Step 2: YAML 格式验证**

抽查几个文件确认 YAML 语法正确（可用任意 YAML linter）：
```bash
python -c "import yaml; yaml.safe_load(open('eval-datasets/entity-extraction/boundary-1-implicit-vs-explicit/entity-implicit-01.yml'))"
```

- [ ] **Step 3: 更新 spec 文档的场景数量**

确认 `docs/superpowers/specs/2026-04-14-memory-extraction-eval-design.md` 中的场景数量与实际文件一致。

- [ ] **Step 4: 最终提交**

```bash
git add eval-datasets/ docs/
git commit -m "feat: 完成记忆提取质量评估数据集（30 个边界场景）"
```
