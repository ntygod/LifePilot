<skill_catalog>
{skillEntries}
</skill_catalog>

## 使用规则
- 收到用户消息后，**先在 skill_catalog 里评估是否有匹配 skill** —— description 描述了 skill 的适用场景
- 命中一个 → `skill.load(names=["skill-name"])` 加载完整指南
- 命中多个 → `skill.load(names=["a","b"])`，一次最多 3 个

## references 加载规则
skill 指南里 `{skill_dir}/references/xxx.md` 是 skill 的"执行手册"——存放具体的命令模板 / 参数表 / 格式骨架 / 速查清单（与 SKILL.md 的"路径决策"分工）。

- 要执行具体动作（写命令、生成产物、套用格式、查陌生参数） → `file.read(path="...")` 加载
- 仅做路径判断 / 单步通用操作 → 跳过
