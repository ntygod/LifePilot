---
name: find-skills
description: 当用户需求超出现有 Skill 覆盖范围、需要外部搜索 SkillHub 或让知微自动生成新 Skill 来补足能力缺口时使用。关键词：有没有能…的功能、帮我找个技能、你能不能、SkillHub、安装技能、生成技能、能力扩展。已知 Skill 的使用直接加载对应 Skill，系统内置工具查询用 introspection，一次性简单任务直接用工具完成。
version: 2.0.0
metadata:
  zhiwei:
    category: infrastructure
    priority: high
    tags:
      - meta
      - skill-discovery
      - skill-generation
      - capability
      - extension
    suggested_tools:
      - shell.exec
      - web.search
      - generate_skill
---

# 能力发现与自扩展指南

当用户需求超出现有 Skill 覆盖范围时，通过外部搜索或自动生成补足能力缺口。知微的核心设计理念：**不可能穷举所有使用场景，但可以利用已有的工具原语组合出新的 Skill。**

## 适用场景

- 用户请求的任务没有现有 Skill 能覆盖
- Agent 在执行中意识到缺少特定领域的指导
- 用户主动要求搜索或安装新技能
- 用户描述了一个可复用的工作流，值得固化为 Skill

## 不适用场景

- 已知 Skill 的使用 → 直接加载对应 Skill
- 系统内置工具查询 → 用 introspection
- 一次性简单任务 → 直接用工具完成，不需要创建 Skill

## 工作流

1. **判断是否真的需要新 Skill**：现有 Skill 是否覆盖？能否组合解决？是一次性吗？
2. **外部搜索顺序**：SkillHub CLI → npx skills → web.search → generate_skill
3. **安装前用户确认**：外部 Skill 装到 `~/.zhiwei/skills/` 前向用户确认
4. **自动生成**：搜索无果时 `generate_skill` 从核心工具原语组合，保存到 `~/.zhiwei/skills/auto/`
5. **生成的 Skill 需用户确认后才激活**
6. **告知结果**：名称 / 能力 / 触发关键词，无需重启

## 详细参考

- 核心工具原语清单 + 搜索与生成详细命令 + 错误处理：`{skill_dir}/references/discovery-workflow.md`
</content>
</invoke>