---
name: skill-creator
description: 当用户要手动创作或改写一个新的知微 Skill、需要 v2 规范示例模板、排查 description/body 校验失败原因或打磨 metadata.zhiwei 元数据时使用。关键词：创建 skill、写 SKILL.md、skill 规范、v2 模板、description 校验、body 拆分 references、metadata.zhiwei、suggested_tools。从开源社区找现成 Skill 走前端"扩展市场"页面，仅查看已有 Skill 列表直接调 system.status 工具。
version: 1.0.0
metadata:
  zhiwei:
    priority: high
    tags:
      - skill-spec
      - meta
      - v2
      - template
      - author
      - validation
    suggested_tools:
      - file.read
      - file.write
      - file.edit
      - file.list
---

# Skill 创作指南

根据 v2 规范（docs/skill-spec.md）手写或改写 SKILL.md 文件。本 Skill 自身就是 v2 规范的参考样例。

## 适用场景

- 从零撰写一个新的 BUILTIN / USER_IMPORTED Skill
- 修复因 description / body 校验失败而被跳过的 Skill
- 在现有 Skill 基础上改写，使其符合 v2 规范
- 分析一个已有 Skill 的 metadata 元数据是否合理（category / priority / requires）

## 不适用场景

- 从开源社区找现成 Skill → 走前端"扩展市场"页面
- 只是查看已有 Skill 列表或能力 → 直接调 `system.status` 工具
- 调用已有 Skill 执行业务 → 直接加载对应 Skill

## 工作流

1. **目录结构**：`skills/<name>/` 下 `SKILL.md` + 可选 `references/` / `scripts/` / `assets/`
2. **写 frontmatter**：name（kebab-case）+ description（≤1024）+ version（semver）+ 可选 metadata.zhiwei
3. **description 硬约束**：必须以"当/用于/Use when"开头，**禁工作流词**（步骤 N / 首先 / 然后 / Step N / First / Then）
4. **写 body 三必需小节**：`## 适用场景` / `## 不适用场景` / `## 工作流`，标题必须完全一致；body ≤5000 字符
5. **超长拆 references/**：API 表、完整错误手册、长示例搬到子文件，用 `{skill_dir}/references/xxx.md` 引用
6. **落盘验证**：重启应用看日志 `installed=N, skipped=0`；skipped>0 查日志报错原因
7. **name 等于目录名**：都用 kebab-case

## 详细参考

- v2 规范完整模板 + 硬约束清单 + 占位符说明 + 错误处理：`{skill_dir}/references/v2-spec-template.md`
</content>
</invoke>