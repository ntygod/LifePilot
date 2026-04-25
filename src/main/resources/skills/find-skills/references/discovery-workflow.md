# 能力发现与自扩展详解

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

## 判断是否真的需要新 Skill

先检查：

- 现有内置 Skill 是否已经覆盖？
- 能否通过组合现有 Skill 解决？（如 daily-manager 协调多个 Skill）
- 是否是一次性任务？（一次性任务直接用工具完成，不创建 Skill）

只有当需求具有**可复用性**且现有 Skill 不覆盖时，才进入下一步。

## 外部搜索顺序

### SkillHub CLI（优先）

```bash
shell.exec(command="skillhub search <关键词>")
```

未安装时安装：

```bash
shell.exec(command="curl -fsSL https://skillhub-1388575217.cos.ap-guangzhou.myqcloud.com/install/install.sh | bash -s -- --cli-only")
```

### npx skills（回退）

```bash
shell.exec(command="npx -y skills find <关键词>")
```

### 在线搜索（最终回退）

```
web.search(query="zhiwei skill <关键词>")
```

搜索到合适的 Skill 后，向用户确认并安装到 `~/.zhiwei/skills/`。

## 手动创作兜底

搜索无果时引导用户用 skill-creator 自己写一份 SKILL.md（v2 规范），保存到 `~/.zhiwei/skills/<name>/SKILL.md`，
SkillFileWatcher 会自动加载。自动生成器（基于 LLM）尚未上线，本流程暂不涉及。

## 告知用户结果

安装成功后，告知用户：

- Skill 名称和能力描述
- 如何触发（关键词或场景）
- 知微已自动加载，无需重启

## 常见错误处理

- **CLI 未安装** → 给出安装命令
- **网络问题** → 换用其他搜索源
- **手动创作的 SKILL.md 校验失败** → 让 skill-creator 复核 description / body 字符上限
