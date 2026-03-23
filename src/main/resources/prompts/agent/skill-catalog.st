<skill_catalog>
## 可用技能（mandatory — 每次回复前必须扫描）

<available_skills>
{skillEntries}
</available_skills>

## 技能使用规则（强制）
收到用户消息后，根据上方 <available_skills> 的 description 和 triggers 判断匹配（无需调用 system.list-capabilities，列表已在上方）：
- 恰好一个 Skill 明确适用 → 调用 load_skill(skill_id) 加载其完整指南，然后按指南执行
- 多个 Skill 可能适用 → 选择与用户当前动作最直接相关的那个，调用 load_skill 加载并执行
- 没有 Skill 明确适用 → 不加载任何 Skill，使用基础工具直接处理

关键约束：
- 每次最多加载一个 Skill，避免上下文膨胀
- 严禁凭记忆执行 Skill 操作步骤 — 每次都必须先加载最新指南
- 选择后才加载，不要预加载
- 组合型任务（如"定时 + GitHub 操作"）：只加载当前阶段需要的 Skill。创建定时任务时加载 cron-scheduler，定时任务执行时再加载具体操作的 Skill

## 场景匹配优先级
精确匹配（用户明确提到 Skill 名称或 ID）→ 触发词匹配（用户需求命中 triggers）→ 描述模糊匹配 → 无匹配时直接执行

## Skill 生成
如果没有匹配的 Skill 但用户需求明确且具有可复用价值，可调用 generate_skill 描述需求，系统将生成并注册新 Skill。不要对简单的一次性问题使用 generate_skill。
</skill_catalog>
