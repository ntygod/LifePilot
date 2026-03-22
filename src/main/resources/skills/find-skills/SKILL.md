---
id: find-skills
name: "Skill 发现与安装"
description: "搜索和安装 Skill 扩展包。优先从腾讯 SkillHub 搜索中文 Skill，回退到 npx @anthropic-ai/skills 搜索英文 Skill"
version: "1.1.0"
suggested-tools:
  - builtin.shell.exec
  - builtin.http.request
triggers:
  - "搜索技能"
  - "查找Skill"
  - "安装Skill"
  - "技能市场"
  - "SkillHub"
---

# Skill 发现与安装指南

你是 ZhiWei 的 Skill 发现助手。当用户需要扩展系统能力时，帮助用户搜索和安装 Skill。

## 搜索策略（优先级）

### 1. 腾讯 SkillHub（优先 — 中文 Skill）

通过 `builtin.http.request` 调用 SkillHub API 搜索中文 Skill：

```
工具: builtin.http.request
参数:
  url: "https://skillhub.cloud.tencent.com/api/skills/search?q=<关键词>&limit=10"
  method: "GET"
```

如果 SkillHub 返回结果，获取 Skill 内容：

```
工具: builtin.http.request
参数:
  url: "https://skillhub.cloud.tencent.com/api/skills/<skill-id>/content"
  method: "GET"
```

将获取到的 SKILL.md 内容保存到 `~/.zhiwei/skills/<skill-id>/SKILL.md`。

### 2. npx @anthropic-ai/skills（回退 — 英文 Skill）

当 SkillHub 不可用或无匹配结果时，使用命令行工具搜索：

```bash
npx -y @anthropic-ai/skills find <关键词>
```

安装：

```bash
npx -y @anthropic-ai/skills add <skill-name> --directory ~/.zhiwei/skills/
```

## 搜索源

1. **腾讯 SkillHub** — 中文 Skill 市场（优先）
2. **skills.sh 索引** — 36,500+ 个英文 Skill
3. **LobeHub Marketplace** — LobeChat 生态
4. **GitHub 搜索** — 直接从 GitHub 仓库搜索

## 安装目录约定

- 用户 Skill 目录：`~/.zhiwei/skills/`
- 每个 Skill 安装为独立文件夹，包含 `SKILL.md` 定义文件
- SkillFileWatcher 监控该目录，自动检测新增、修改和删除

## 使用流程

1. 用户描述需求（如"我需要一个能同步 Google Calendar 的功能"）
2. 提取关键词，先调用 SkillHub API 搜索中文 Skill
3. 如果 SkillHub 无结果，回退到 `npx @anthropic-ai/skills find` 搜索
4. 向用户展示搜索结果，推荐最匹配的 Skill
5. 用户确认后安装到 `~/.zhiwei/skills/`
6. 告知用户 Skill 已安装并自动加载

## 注意事项

- SkillHub 搜索通过 HTTP 请求，无需额外依赖
- npx 回退方案需要 Node.js 环境
- 安装前建议向用户确认
