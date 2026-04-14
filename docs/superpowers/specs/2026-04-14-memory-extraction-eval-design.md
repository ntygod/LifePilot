# 记忆提取质量评估数据集设计

## 背景

知微的记忆模块（4 层架构 + 巩固管线 + MaRS 遗忘引擎）在架构上已对齐业界前沿，但缺乏真实数据验证提取质量。本设计构建一套 **边界测试数据集**，通过真实 API 对话验证实体提取、经验提取、巩固管线三个环节的判断能力。

## 目标

- 构造覆盖关键判断边界的对话剧本，喂入真实系统
- 人工审查提取结果，定位 prompt 和代码的薄弱环节
- 为后续 prompt 优化提供可重复的基准对照

## 非目标

- 不构建自动化评分框架（LLM 输出的语义等价性无法精确匹配判定）
- 不集成到 CI/CD（数据少时人工审查比自动化更有效）
- 不测试 Agent 执行能力（现有 eval 模块已覆盖）

---

## 数据集格式

### 实体提取 / 经验提取场景

```yaml
# --- 元信息 ---
id: entity-implicit-01
name: 隐含偏好-从不满推断
category: entity-extraction/boundary-1-implicit-vs-explicit
difficulty: hard
tags: [preference, implicit, inference]

# --- 对话剧本 ---
# 只写用户消息，AI 回复由系统自然生成
conversation:
  - "帮我找几家附近的餐厅"
  - "每次你推荐的餐厅都太远了，能不能找近一点的"

# --- 期望提取 ---
expected:
  - operation: ADD
    type: PREFERENCE
    name_hint: "餐厅距离偏好"
    description_should_contain:
      - "近"
    min_confidence: 0.6

# --- 不应提取 ---
should_not_extract:
  - type: PLACE
    name_hint: "附近餐厅"
    reason: "这是查询上下文，不是用户相关地点"

# --- 审查要点 ---
review_notes: |
  核心判断：能否从用户的不满情绪中推断出持久偏好？
  合理的提取：PREFERENCE "偏好近距离餐厅"
  不合理的提取：HABIT "经常去餐厅"（无依据）

# --- 实际结果（测试后填写） ---
# actual_result:
#   date: 2026-04-XX
#   extracted: []
#   missed: []
#   unexpected: []
#   notes: ""
```

### 巩固场景（扩展字段）

```yaml
id: consolidation-merge-01
name: 同义人物实体合并
category: consolidation/boundary-1-entity-merge
difficulty: hard
tags: [dedup, person, alias]

# 依赖哪些前置对话场景（需先跑完）
prerequisite_sessions:
  - entity-multi-01      # 提到"小王"
  - entity-multi-02      # 提到"王磊"
  - entity-multi-03      # 提到"我同事老王"

# 触发哪个接口
trigger: dedup            # consolidation | dedup | both

# 触发后检查
expected_after_trigger:
  - check: entity_merged
    from: ["小王", "王磊", "老王"]
    to_single: true
    expected_type: PERSON
  - check: relations_migrated
    description: "所有指向被合并实体的关系应迁移到主实体"

should_not_happen:
  - check: cross_space_merge
    description: "不同 memory_space 的同名实体不应被合并"

review_notes: |
  核心判断：名称差异大但指同一人时，去重是否生效？
  关注：合并后的描述是否综合了多个来源的信息

# actual_result:
#   date: 2026-04-XX
#   merged: false
#   notes: ""
```

---

## 场景目录

### 一、实体提取（18 个场景）

#### boundary-1-implicit-vs-explicit（隐含 vs 显式）

| ID | 名称 | 对话要点 | 核心边界 |
|----|------|---------|---------|
| entity-implicit-01 | 隐含偏好-从不满推断 | "每次推荐的餐厅都太远了" | 不满 → 偏好推断的合理边界 |
| entity-implicit-02 | 双重否定 | "我不是不喜欢运动，只是最近太忙" | 否定解析正确性 |
| entity-implicit-03 | 行为暗示习惯 | "又到周五了，帮我订老地方的位子" | 信息不完整时是否该提取 |

#### boundary-2-temporary-vs-persistent（临时 vs 持久）

| ID | 名称 | 对话要点 | 核心边界 |
|----|------|---------|---------|
| entity-temp-01 | 学习中 vs 技能 | "我最近在学 Python" | SKILL vs 临时活动的区分 |
| entity-temp-02 | 情绪 vs 偏好 | "这破功能烦死了" | 一时情绪不应成为持久 PREFERENCE |
| entity-temp-03 | 出差 vs 居住 | "我这周在上海出差" | 不应提取为 PLACE 居住地 |

#### boundary-3-quoted-vs-self（转述 vs 自述）

| ID | 名称 | 对话要点 | 核心边界 |
|----|------|---------|---------|
| entity-quote-01 | 引用他人观点 | "我同事说 Python 比 Java 好" | 归属判断：同事的偏好 ≠ 用户的偏好 |
| entity-quote-02 | 组织决策 vs 个人偏好 | "老板要求我们用微服务架构" | 应提 ORG 决策 or 用户 PREFERENCE？ |
| entity-quote-03 | 复述文章 | "我看到篇文章说每天冥想有好处" | 分享信息 ≠ 用户行为 |

#### boundary-4-contradiction-update（矛盾与更新）

| ID | 名称 | 对话要点 | 核心边界 |
|----|------|---------|---------|
| entity-contra-01 | 显式否定旧偏好 | "我之前说喜欢蓝色，现在觉得绿色更好" | 应 UPDATE 非 ADD，旧版本失效 |
| entity-contra-02 | 身份变化 | "我换工作了，现在在字节跳动" | 旧 ORG 失效 + 新 ORG ADD |
| entity-contra-03 | 假设性目标 | "如果有钱的话想去日本" | 条件性表述是否该提取为 GOAL |

#### boundary-5-multi-entity（多实体纠缠）

| ID | 名称 | 对话要点 | 核心边界 |
|----|------|---------|---------|
| entity-multi-01 | 关系链提取 | "我和小王在腾讯做的那个 AI 项目上季度结束了" | 一句话含 PERSON+ORG+PROJECT+时间状态 |
| entity-multi-02 | 角色 vs 实体 | "帮我约产品经理李总开会讨论需求" | "产品经理"是角色不是独立实体 |

#### boundary-6-domain-boundary（领域知识边界）

| ID | 名称 | 对话要点 | 核心边界 |
|----|------|---------|---------|
| entity-domain-01 | 用户设备 vs 领域知识 | "我用的 MacBook Pro M3，编译很慢" | "用 MacBook"是 USER_FACT，M3 性能是领域知识 |
| entity-domain-02 | 经验 vs 公知 | "我发现 SQLite WAL 模式下长事务会阻塞" | 用户发现 vs 公开文档记载的事实 |
| entity-domain-03 | 混合句中分离 | "我们项目用 React，不过我个人更喜欢 Vue" | 项目事实 + 个人偏好需拆分提取 |

---

### 二、经验提取（6 个场景）

| ID | 名称 | 触发方式 | 核心边界 |
|----|------|---------|---------|
| exp-attr-01 | 歪打正着 | 让 Agent 用错误工具但碰巧得到结果 | 错误策略不应记为有效经验 |
| exp-attr-02 | 用户纠偏后成功 | Agent 方向错 → 用户纠正 → 完成 | 成功归因于用户干预，lessons 如何表述 |
| exp-attr-03 | 部分成功 | 3 个子任务完成 2 个 | success=false 但部分经验有价值 |
| exp-attr-04 | 冗余步骤过滤 | 触发 10 步操作但仅 3 步有效 | 能否提取关键路径而非全部步骤 |
| exp-attr-05 | 重复模式去重 | 第三次做类似"查数据+生成报告" | 应 boost 已有经验而非新建 |
| exp-attr-06 | 纯系统故障 | API 超时三次后成功 | attribution=system，不应写入经验库 |

---

### 三、巩固管线（6 个场景）

| ID | 名称 | 前置依赖 | 触发方式 | 核心边界 |
|----|------|---------|---------|---------|
| consol-merge-01 | 同义人物合并 | entity-multi-01/02/03 | dedup | 名称差异大但指同一人 |
| consol-merge-02 | 经验语义合并 | exp-attr-05（跑 3 次变体） | consolidation | 合并后 lessons 泛化而非拼接 |
| consol-pref-01 | 矛盾偏好巩固 | entity-contra-01 | consolidation | L4 PreferenceRule 应反映最新值 |
| consol-decay-01 | 低频实体不涨分 | entity-temp-01（仅提及 1 次） | consolidation | 巩固不应 boost 无后续提及的实体 |
| consol-promote-01 | 高频经验晋升 L4 | exp-attr-05（多次访问） | consolidation | 应创建 ProcedureTemplate，原经验归档 |
| consol-space-01 | 跨空间隔离 | 分别在 personal 和 domain 创建同名实体 | dedup | 去重不应跨 memory_space 合并 |

---

## 执行流程

### 实体提取 / 经验提取

```
1. mvn spring-boot:run 启动系统
2. 打开 Web UI 或使用 API
3. 按场景文件创建新会话
4. 依次发送 conversation 中的用户消息
5. 等待 Agent 回复 + 异步提取完成（约 5-10 秒）
6. 查看提取结果：
   - 实体：GET /api/memory/entities?scope=USER_PROFILE
   - 经验：GET /api/memory/entities?type=EXPERIENCE
7. 对照 expected / should_not_extract 标注 actual_result
```

### 巩固管线

```
1. 先按 prerequisite_sessions 列表跑完所有前置对话
2. 手动触发巩固/去重接口：
   - 巩固：POST /api/memory/consolidation/trigger
   - 去重：POST /api/memory/dedup/trigger
3. 检查巩固结果：
   - 实体 importance 变化
   - 经验合并情况
   - L4 PreferenceRule / ProcedureTemplate 变化
4. 对照 expected_after_trigger 标注结果
```

### 结果标注

在每个场景文件末尾追加 `actual_result` 块：

```yaml
actual_result:
  date: 2026-04-14
  extracted:
    - type: PREFERENCE
      name: "餐厅距离偏好"
      confidence: 0.85
      verdict: CORRECT           # CORRECT | WRONG_TYPE | MISSING | UNEXPECTED
  missed:                        # expected 中未被提取的
    - expected_type: PREFERENCE
      name_hint: "餐厅距离偏好"
  unexpected:                    # should_not_extract 中被错误提取的
    - type: SKILL
      name: "餐厅推荐"
      verdict: FALSE_POSITIVE
  notes: "prompt 把查询上下文误判为用户技能"
```

---

## 文件组织

```
eval-datasets/
  entity-extraction/
    boundary-1-implicit-vs-explicit/
      entity-implicit-01.yml
      entity-implicit-02.yml
      entity-implicit-03.yml
    boundary-2-temporary-vs-persistent/
      entity-temp-01.yml
      entity-temp-02.yml
      entity-temp-03.yml
    boundary-3-quoted-vs-self/
      entity-quote-01.yml
      entity-quote-02.yml
      entity-quote-03.yml
    boundary-4-contradiction-update/
      entity-contra-01.yml
      entity-contra-02.yml
      entity-contra-03.yml
    boundary-5-multi-entity/
      entity-multi-01.yml
      entity-multi-02.yml
    boundary-6-domain-boundary/
      entity-domain-01.yml
      entity-domain-02.yml
      entity-domain-03.yml
  experience-extraction/
    exp-attr-01.yml
    exp-attr-02.yml
    exp-attr-03.yml
    exp-attr-04.yml
    exp-attr-05.yml
    exp-attr-06.yml
  consolidation/
    consol-merge-01.yml
    consol-merge-02.yml
    consol-pref-01.yml
    consol-decay-01.yml
    consol-promote-01.yml
    consol-space-01.yml
  README.md
```

位置：项目根目录 `eval-datasets/`，不进 `src/`（人工测试资料，非代码）。

---

## 成功标准

- 实体提取：18 个场景中 ≥ 14 个判断正确（CORRECT + 无 FALSE_POSITIVE）
- 经验提取：6 个场景中 ≥ 4 个 attribution 和 lessons 判断正确
- 巩固管线：6 个场景中 ≥ 5 个操作符合预期
- 如未达标，记录失败模式 → 指导 prompt 和代码的定向优化

## 后续演进

1. 每次优化 prompt 后重跑数据集，对比 actual_result 变化
2. 积累真实用户场景后补充数据集
3. 数据量足够时升级为自动化评分（引入 LlmJudge 做语义对比）
