# Skill v3 规范模板

## 目录结构

```text
skills/<name>/
├── SKILL.md
├── references/
│   └── *.md
├── scripts/
│   └── *.{sh,py,js}
└── assets/
    └── *
```

`<name>` 必须等于 frontmatter 的 `name` 字段。只创建真正会被用到的子目录。

## frontmatter 模板

```yaml
---
name: my-skill
description: 当用户要<任务/场景/高频说法>时使用，尤其适合<强触发面>；不适合<边界>。
version: 1.0.0
metadata:
  zhiwei:
    tags:
      - topic
    suggested_tools:
      - file.read
    outputs:
      - text
      - file
    requires:
      bins: [git]
      env: [API_TOKEN]
      os: [windows]
      tools: [web.search]
---
```

字段不存在 `id`、`category`、`priority`。`suggested_tools` 不自动注入工具，只提供路由和 UI 提示。

## description

必须满足：

1. ≤1024 字符
2. 以 `当` / `用于` / `Use when` / `Use this when` 开头
3. 不含工作流词：`步骤 N` / `首先` / `然后` / `接下来` / `Step N` / `First` / `Then`

推荐结构：

```text
当用户要<任务>、<高频说法>或<上下文>时使用，尤其适合<强场景>；不适合<反例>。
```

## body 四必需小节

body ≤5000 字符；标题必须完全一致。

```markdown
# <中文指南标题>

## 触发判断

- <正例：用户表达或上下文>
- <正例：输入/产物/渠道>
- 不要触发：<反例或交给其他 Skill/Tool 的边界>

## 决策路径

1. <先判断目标、输入、权限、风险>
2. <按场景分流到策略、工具或 references>
3. <执行前需要确认的条件>

## 输出标准

- <输出形态：text / file / a2ui / memory / notification / task>
- <必须包含的路径、字段、证据、数值、状态或交互组件>

## 失败策略

- <依赖缺失、权限不足、输入不够、工具失败、风险过高时如何降级或追问>

## 详细参考

- <长命令 / 参数表 / 模板 / 错误码>：`{skill_dir}/references/<topic>.md`
```

## outputs

允许值：

| 值 | 含义 |
|---|---|
| `text` | 直接文本、表格、摘要、方案 |
| `file` | 生成或修改文件 |
| `a2ui` | 可交互组件 |
| `memory` | 记忆候选、偏好、长期事实 |
| `notification` | 通知、飞书、桌面推送 |
| `task` | 定时任务、跟进任务、计划项 |

每个内置 Skill 至少声明一个 outputs，多个可并列。

## 占位符

- `{skill_dir}` → Skill 根目录绝对路径
- `{skill_references_dir}` → references 目录绝对路径
- `{skill_scripts_dir}` → scripts 目录绝对路径

## 常见校验失败

| 报错 | 处理 |
|---|---|
| `description 必须以...开头` | 改成 `当...` / `用于...` 开头 |
| `description 不得含工作流词` | 把步骤词移到 body 的 `决策路径` |
| `body 缺少必需小节` | 补齐 v3 四段标题 |
| `version 必须是语义化版本` | 改成 `1.0.0` 形式 |
| `outputs 包含未知输出形态` | 只使用允许值 |
| `字段 'id' 已废弃` | 删除 `id`，使用 `name` |

## v1/v2 迁移

1. 删除 `id`、`category`、`priority`。
2. description 改成高精度触发句，移除步骤词。
3. 把 `适用场景` + `不适用场景` 合并为 `触发判断`。
4. 把 `工作流` 改写为 `决策路径`，强调判断顺序而非命令堆叠。
5. 新增 `输出标准` 和 `失败策略`。
6. 为 `metadata.zhiwei` 补 `outputs`，按需补 `tags` / `requires`。
7. 长命令、模板、错误表迁入 references。
