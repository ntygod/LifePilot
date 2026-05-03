<skill_catalog>
{skillEntries}
</skill_catalog>

## references 加载规则
skill 指南里 `{skill_dir}/references/xxx.md` 是 skill 的"执行手册"——存放具体的命令模板 / 参数表 / 格式骨架 / 速查清单（与 SKILL.md 的"路径决策"分工）。

- 要执行具体动作（写命令、生成产物、套用格式、查陌生参数） → `file_read(path="...")` 加载
- 仅做路径判断 / 单步通用操作 → 跳过
