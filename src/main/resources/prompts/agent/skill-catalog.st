<skill_catalog>
{skillEntries}
</skill_catalog>

## 使用规则
- 匹配到合适 Skill → 调用 skill.load(names=["skill-name"]) 加载完整指南
- 多个 Skill 协同 → skill.load(names=["a","b"])，一次最多 3 个
- Skill 指南里引用 {skill_dir}/references/xxx.md 的详细文档 → 用 file.read(path="...") 按需加载
- 无匹配 Skill → 继续用 Tier 1 工具或 tools.search 发现工具
