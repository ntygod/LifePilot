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

当用户需求超出现有 Skill 覆盖范围时，通过外部搜索或自动生成补足能力缺口。

知微的核心设计理念：**不可能穷举所有使用场景，但可以利用已有的工具原语组合出新的 Skill。**

## 适用场景

- 用户请求的任务没有现有 Skill 能覆盖
- Agent 在执行中意识到缺少特定领域的指导
- 用户主动要求搜索或安装新技能
- 用户描述了一个可复用的工作流，值得固化为 Skill

## 不适用场景

- 已知 Skill 的使用 → 直接加载对应 Skill
- 系统内置工具查询 → 用 introspection
- 一次性简单任务 → 直接用工具完成，不需要创建 Skill

## 核心原则

知微拥有一组核心工具原语，任何新 Skill 都是这些原语的组合编排：

| 类别 | 工具 | 能力 |
|------|------|------|
| 信息获取 | web.search, web.fetch, knowledge.search | 搜索、抓取、知识检索 |
| 文件操作 | file.read, file.write, file.list, file.edit, file.manage | 完整文件生命周期 |
| 命令执行 | shell.exec, shell.process | 任意命令行 + 后台进程 |
| 代码执行 | code.execute | Python 持久内核 |
| 浏览器 | browser | JS 渲染页面交互 |
| 记忆 | memory | 跨会话持久化 |
| Git | git.query, git.mutate | 版本控制 |
| 调度 | cron | 定时触发 |
| 通知 | notify | 消息推送 |
| 渠道 | channel.feishu | 飞书集成 |

任何用户需求，如果能分解为上述工具的组合，就可以生成一个 Skill。

## 工作流

### 判断是否真的需要新 Skill

先检查：
- 现有内置 Skill 是否已经覆盖？
- 能否通过组合现有 Skill 解决？（如 daily-manager 协调多个 Skill）
- 是否是一次性任务？（一次性任务直接用工具完成，不创建 Skill）

只有当需求具有**可复用性**且现有 Skill 不覆盖时，才进入下一步。

### 外部搜索

**SkillHub CLI（优先）：**
```bash
shell.exec(command="skillhub search <关键词>")
```

未安装时安装：
```bash
shell.exec(command="curl -fsSL https://skillhub-1388575217.cos.ap-guangzhou.myqcloud.com/install/install.sh | bash -s -- --cli-only")
```

**npx skills（回退）：**
```bash
shell.exec(command="npx -y skills find <关键词>")
```

**在线搜索（最终回退）：**
```
web.search(query="zhiwei skill <关键词>")
```

搜索到合适的 Skill 后，向用户确认并安装到 `~/.zhiwei/skills/`。

### 自动生成 Skill

搜索无果时，用 `generate_skill` 从核心工具原语组合出新 Skill：

```
generate_skill(description="用户需求描述", suggested_name="skill-id", suggested_tools=["tool1", "tool2"])
```

生成器会获取所有已注册工具的能力清单，选择最匹配的模板，用 LLM 生成 SKILL.md 并三重验证迭代修正，最终保存到 `~/.zhiwei/skills/auto/`。

### 告知用户结果

安装或生成成功后，告知用户：
- Skill 名称和能力描述
- 如何触发（关键词或场景）
- 知微已自动加载，无需重启

## 规则

- 搜索顺序：SkillHub CLI → npx skills → web.search → generate_skill
- 安装外部 Skill 前必须向用户确认
- 自动生成的 Skill 需要用户确认后才激活
- 一次性任务不创建 Skill，直接用工具完成
- `generate_skill` 只能组合已注册的工具，不能引用不存在的工具

## 常见错误处理

- **CLI 未安装** → 给出安装命令
- **网络问题** → 换用其他搜索源
- **generate_skill 验证失败** → 系统会自动迭代修正，多次失败后告知用户原因
- **生成的 Skill 质量不佳** → 建议用户手动编辑 `~/.zhiwei/skills/auto/{id}/SKILL.md` 微调
