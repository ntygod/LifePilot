---
name: skill-creator
description: 当用户要手动创作或改写一个新的知微 Skill、需要 v2 规范示例模板、排查 description/body 校验失败原因或打磨 metadata.zhiwei 元数据时使用。
version: 1.1.0
metadata:
  zhiwei:
    tags:
      - skill-spec
      - meta
      - v2
      - template
      - author
      - validation
    suggested_tools:
      - file_read
      - file_write
---

# Skill 创作指南

帮用户手写 / 改写一份符合 v2 规范的 SKILL.md。**核心约束：description 必须以"当 / 用于 / Use when"开头，不含工作流词；body 必须有"适用场景 / 不适用场景 / 工作流"三小节。**

## 适用场景

- 用户从零写一个新的 Skill（描述 / 工作流 / references 全套）
- 改写老格式 Skill 让它符合 v2 规范
- 修复因 description / body 校验失败被跳过的 Skill
- 打磨 metadata.zhiwei（tags / suggested_tools / requires）

## 不适用场景

- 从开源市场找现成 Skill → 走前端"扩展市场"页面
- 只是查看已有 Skill 列表 / 能力 → 直接调 `status` 工具
- 调用已有 Skill 执行业务 → 直接 `skill_load`

## 工作流

1. **目录结构**：`skills/<name>/` 下 `SKILL.md` + 可选 `references/` / `scripts/` / `assets/`
2. **写 frontmatter**：name（kebab-case）+ description（≤1024）+ version（semver）+ metadata.zhiwei（可选）
3. **description 硬约束**：
   - 必须以"当 / 用于 / Use when"开头
   - **禁工作流词**：步骤 N / 首先 / 然后 / Step N / First / Then 不能出现
   - 列关键词（用户高频说法）+ 边界（不适用场景的反向引导）
4. **body 三必需小节**：标题完全一致（`## 适用场景` / `## 不适用场景` / `## 工作流`）；body ≤5000 字符
5. **复杂内容下沉 references**：长命令 / 详细模板 / 大量样例放 `references/<name>.md`，body 只引用
6. **suggested_tools 必须真实存在**：调 `status` 拿当前注册工具清单核对，不能写已下架 / 不存在的工具 ID
7. **3 指标自检**：用词精简？职责分工（不重复 react-system / context-guide 已说的全局规则）？场景全面（按用户表达列分流路径）？

## 详细参考

- v2 frontmatter 完整字段、body 模板、常见校验错误、迁移老 Skill 步骤：`{skill_dir}/references/v2-spec-template.md`
