# 知微（ZhiWei）— 从"个人生活助手"到"个人助手"演进计划

> 创建时间：2026-03-08
> 更新时间：2026-03-09
> 状态：已确认，准备实施
> AI 名称：知微（ZhiWei），意为"见微知著"
> 目标版本：0.2.0（公开内测版）

---

## 1. 已确认决策

| 决策项 | 结论 |
|--------|------|
| 包名 `com.lifepilot` | 保留不变（改动风险大，收益低） |
| 数据目录 `~/.zhiwei/` | 重命名为 `~/.zhiwei/` |
| 前端项目 `lifepilot-web` | 重命名为 `zhiwei-web` |
| Slogan | "见微知著，你的 AI 伙伴" |
| 版本号 | 0.1.0 → 0.2.0 |
| 发布范围 | 公开内测 |

---

## 2. 内置能力评估结论

### 2.1 内置 Skill（4 个 BuiltinSkillProvider）

| Skill | 决策 | 理由 |
|-------|------|------|
| TodoSkillProvider | ✅ 保留 | 待办管理是通用能力 |
| ScheduleSkillProvider | ✅ 保留 | 日程管理是通用能力 |
| HabitSkillProvider | ✅ 保留，Prompt 层弱化 | 偏生活管理，但保留合理；不再作为核心能力首屏展示 |
| MemorySkillProvider | ✅ 保留 | 底层基础设施，完全通用 |

### 2.2 预设 Agent（4 个 preset-agents）

| Agent | 决策 | 理由 |
|-------|------|------|
| onboarding-guide | ✅ 保留并更新内容 | 必需，更新为"个人助手"定位 |
| life-coach | 🔄 重塑为 advisor（顾问） | "生活教练"太窄，扩展到工作决策、学习规划等 |
| planner | ✅ 保留并微调措辞 | 规划能力完全通用 |
| writer | ✅ 保留 | 写作专家本身通用 |

新增预设 Agent：

| Agent | 定位 |
|-------|------|
| researcher | 调研专家：利用 WebSearch/WebFetch/KnowledgeBase 做信息收集、对比分析、摘要提炼 |
| analyst | 数据分析师：利用 CodeExecute/Calculate/File 做数据处理、图表生成、趋势分析 |

### 2.3 主动推理引擎

- 6 种 NotificationType 全部保留（DEADLINE_REMINDER / SCHEDULE_REMINDER / HABIT_REMINDER / STREAK_AT_RISK / DAILY_SUMMARY / WEEKLY_REVIEW）
- HABIT_REMINDER / STREAK_AT_RISK 降低默认优先级
- SignalCollector / RuleEngine 架构 0.2 不大改，仅更新 Prompt 层描述
- 规则引擎可配置化归入后续版本

### 2.4 内置工作流模板（新增）

| 工作流 | 触发方式 | 功能 |
|--------|---------|------|
| content-review | 事件触发 / 手动 | LLM 风险分析 + 条件分支 + 人工审批，覆盖 ConditionStep / ApprovalStep |
| data-aggregation | Cron（每周一早 9 点） / 手动 | 并行多源采集 + LLM 综合分析，覆盖 ParallelStep / DAG dependsOn |
| batch-processing | Cron（每天凌晨 2 点） / 手动 | 循环逐条处理 + 条件路由，覆盖 LoopStep / ErrorStrategy |
| scheduled-inspection | Cron（每 30 分钟） | 定时巡检 + 分级告警 + 自动修复，覆盖 WaitStep / SubWorkflowStep |
| research-approval | 手动触发 | 多源调研 + 审批 + 补偿回滚，覆盖 ApprovalStep / compensate ErrorStrategy |

---

## 3. Spec 实施计划

执行顺序：1 → 2 → 3 → 4（有依赖关系）

### Spec 1: `brand-upgrade`（品牌升级）— 1-2 天

- 版本号 0.1 → 0.2（pom.xml + package.json）
- 数据目录 `~/.lifepilot/` → `~/.zhiwei/`
- 前端项目 `lifepilot-web` → `zhiwei-web`
- Slogan 更新："见微知著，你的 AI 伙伴"
- 文档措辞统一（ARCHITECTURE.md / FEATURES.md / ROADMAP.md）

### Spec 2: `agent-persona-upgrade`（Agent 人设升级）— 2-3 天

- role-definition.st 重写：从"智能生活助手"到"个人 AI 助手"
- 预设 Agent 调整：life-coach → advisor，新增 researcher、analyst
- onboarding-guide 内容更新
- 能力声明扩展（覆盖工作、学习、信息处理等领域）

### Spec 3: `builtin-workflows`（内置工作流模板）— 3-5 天

- 实现 5 个预装工作流 YAML
- 启动时自动加载机制（类似 preset-agents 的加载方式）
- 工作流模板的文档说明

### Spec 4: `beta-release-readiness`（内测发布准备）— 2-3 天

- README 重写（面向公开用户）
- Docker 配置更新（镜像名、环境变量）
- 默认配置优化（开箱即用体验）
- 启动脚本更新
- 已知限制文档

---

## 4. 核心结论

架构层面不需要推倒重来。当前的核心组件（StateReducer / 四层记忆 / 混合检索 / Skill 系统 / MCP / Meta 基础设施）都是领域无关的。从"生活助手"到"个人助手"的升级主要是：

1. 内容层面的扩展（新 Prompt、新预设 Agent、新工作流模板）
2. 品牌层面的调整（名称、文档、slogan、对外描述）
3. 内测发布准备（README、Docker、默认配置）
