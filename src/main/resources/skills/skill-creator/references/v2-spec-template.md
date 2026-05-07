# Skill v2 规范模板

## 目录结构

```
skills/<name>/
├── SKILL.md              # 必需，frontmatter + body
├── references/           # 可选，详细参考（LLM 用 file.read 按需加载）
│   └── *.md
├── scripts/              # 可选，可执行脚本
└── assets/               # 可选，模板/schema/静态资源
```

`<name>` 必须等于 frontmatter 的 `name` 字段。

## frontmatter 模板

```yaml
---
name: my-skill                       # 必需，正则 ^[a-z0-9][a-z0-9-]{0,62}$
description: 当...时使用。关键词：a、b、c。反例 → <其他 Skill>  # 必需，≤1024
version: 1.0.0                       # 必需，semver
metadata:
  zhiwei:                            # 可选块；不写则用默认值
    tags:                            # 辅助 BM25 召回
      - a
      - b
    suggested_tools:                 # UI / 检索参考，不会自动注入 Agent
      - file.read
      - shell.exec
    requires:                        # 可选硬过滤；不满足则 SkillRequirementGate 剔除
      bins: [git]
      env: [API_KEY]
      os: [linux]
      tools: [gh.pr.create]
---
```

字段不存在 `category`、`id`（v1 已废弃）。

## description 硬约束（最关键）

代码会拒，必须满足：

1. ≤1024 字符
2. 必须以 `当` / `用于` / `Use when` / `Use this when` 开头（大小写不敏感）
3. **不得含工作流词**：`步骤 N` / `首先` / `然后` / `接下来` / `Step N` / `First` / `Then`

推荐结构：

```
当 <场景> 时使用。关键词：<高频说法 1>、<高频说法 2>...。<反例场景> → <其他 Skill 或工具>。
```

工作流应写在 body 的"## 工作流"章节，不是 description。

## body 三必需小节（硬约束）

body ≤5000 字符；缺任一标题或标题不一致都会被拒。

```markdown
# <中文标题>

按"用户表达 → 路径"组织内容。

## 适用场景
- 2-5 条典型场景，反映用户高频说法

## 不适用场景
- 反例 → 引导到其他 Skill / 工具

## 工作流
- 路径分流表 + 各路径要点
- 详细命令 / 错误表 / 模板下沉到 references/

## 详细参考（可选）
- 参见 {skill_dir}/references/<file>.md
```

body 超 5000 → 拆 references；不要裁信息。

## 占位符（运行期由 SkillActivator 替换）

- `{skill_dir}` → Skill 根目录绝对路径
- `{skill_references_dir}` → `references/` 子目录绝对路径
- `{skill_scripts_dir}` → `scripts/` 子目录绝对路径

## suggested_tools

- 仅作为 UI 展示、检索和人工参考元数据，不会在 `skill.load` 后自动注入 Agent
- 建议引用已注册 canonical 工具 ID；用 `status` 或 `tool.search` 拿当前工具清单核对
- 多 action 工具（`memory` / `file.read` / `shell.process` 等）按工具 ID 写一条即可，action 不拆

## 落盘与验证

```
file_write(path="src/main/resources/skills/<name>/SKILL.md", content="...")
file_write(path="src/main/resources/skills/<name>/references/<topic>.md", content="...")
```

启动时观察日志 `BUILTIN Skill 安装完成: installed=N, skipped=0`。`skipped > 0` 一般是 description 或 body 校验失败。

## 常见校验失败原因速查

| 报错 | 原因 |
|------|------|
| `description 必须以 '当...' / '用于...' / 'Use when...' 开头` | 开头不在白名单触发词 |
| `description 不得含工作流词` | 含 `步骤/首先/然后/Step N/First/Then`，搬到 body |
| `description 长度超过 ≤1024 字符限制` | description 太长，关键词列表精简或拆 |
| `body 缺少必需小节: ## XXX` | 三个标题必须**完全一致**包括前导 `## ` 和中文 |
| `body 长度超过 ≤5000 字符限制` | 拆最长子章节到 `references/<topic>.md` |
| `name 必须匹配正则 ^[a-z0-9][a-z0-9-]{0,62}$` | name 只能小写字母数字+中划线，长度 1-63 |
| `字段 'id' 已废弃` | v1 的 `id:` 改成 `name:` |
| `缺少必需字段: name/description/version` | frontmatter 三必需字段缺失 |

## 老 Skill 迁移步骤

1. `file_read` 读老 SKILL.md
2. 把老 `id:` 改成 `name:`，必须 kebab-case
3. description 改写：去掉工作流词、补开头触发词、加关键词清单和反例
4. body 重组三小节（适用 / 不适用 / 工作流），用"用户表达 → 路径"表格
5. 详细命令、长样例下沉到 `references/<topic>.md`
6. metadata 删 `category`，按需补 `tags` / `suggested_tools`
7. `file_write` 落盘后重启服务验证日志

## 全局规则别重复

不要在 SKILL.md / references 里重述"如何选工具""如何调多个工具""如何回应用户"——这些 react-system 已经管。Skill 只说自己**特有**的：用户表达分流、关键决策点、领域命令清单。
