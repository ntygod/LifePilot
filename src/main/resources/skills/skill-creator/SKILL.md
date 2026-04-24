---
name: skill-creator
description: 当用户要手动创作或改写一个新的知微 Skill、需要 v2 规范示例模板、排查 description/body 校验失败原因或打磨 metadata.zhiwei 元数据时使用。关键词：创建 skill、写 SKILL.md、skill 规范、v2 模板、description 校验、body 拆分 references、metadata.zhiwei、suggested_tools。自动生成新 Skill 用 find-skills 的 generate_skill，仅查看已有 Skill 能力用 introspection。
version: 1.0.0
metadata:
  zhiwei:
    category: infrastructure
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

- 让系统自动生成 Skill → 用 find-skills 调 `generate_skill`
- 只是查看已有 Skill 列表或能力 → 用 introspection
- 调用已有 Skill 执行业务 → 直接加载对应 Skill

## 工作流

### 1. 确定目录结构

每个 Skill 是一个目录，路径为 `skills/<name>/`：

```
skills/<name>/
├── SKILL.md              # 必需。L1 frontmatter + L2 body
├── references/           # 可选。L3 详细参考（LLM 用 file.read 按需加载）
│   └── *.md
├── scripts/              # 可选。可执行脚本
└── assets/               # 可选。模板 / schema / 静态资源
```

### 2. 写 frontmatter

三个必需字段 + 可选 metadata.zhiwei 块：

```yaml
---
name: my-skill                   # 必需，kebab-case，正则 ^[a-z0-9][a-z0-9-]{0,62}$
description: 当...时使用。关键词 a, b, c。反例场景 → 用其他 Skill  # 必需，≤1024
version: 1.0.0                   # 必需，semver
metadata:
  zhiwei:
    category: infrastructure     # 五选一
    priority: normal             # high / normal / low
    tags: [a, b, c]              # 辅助检索
    suggested_tools: [tool.id]   # 激活后合并进 activatedToolIds
    requires:                    # 可选，加载期硬过滤
      bins: [git]
      env: [API_KEY]
      os: [linux]
      tools: [gh.pr.create]
---
```

category 仅五个合法值：`external-integration` / `content-creation` / `automation` / `infrastructure` / `utility`。

### 3. 写 description（最难，最关键）

硬约束：
- ≤1024 字符
- 必须以 "当..." / "用于..." / "Use when..." / "Use this when..." 开头
- **不得含工作流词**：步骤 N / 首先 / 然后 / 接下来 / Step N / First / Then

推荐结构：`当[场景]时使用。关键词：a、b、c。[反例] → 用 [其他 Skill]。`

### 4. 写 body（三必需小节）

body ≤5000 字符，超长拆到 references/。

```markdown
# <中文标题>

## 适用场景
- 2-5 条，每条描述一个典型场景

## 不适用场景
- 反例，压抑误触发

## 工作流
- 高层步骤骨架，不写细节命令
- 需要详细参数/示例/错误处理时引用 {skill_dir}/references/xxx.md

## 详细参考（可选）
- 参见 {skill_dir}/references/xxx.md
```

### 5. 落盘与验证

```
file.write(path="src/main/resources/skills/<name>/SKILL.md", content="...")
file.write(path="src/main/resources/skills/<name>/references/details.md", content="...")
```

验证路径：重启应用，看日志 `BUILTIN Skill 安装完成: installed=N, skipped=0`。

## 规则

- name 必须等于目录名，都用 kebab-case
- description 开头触发词和禁工作流词是**硬约束**，代码会拒绝
- 三必需 body 小节标题必须**完全一致**：`## 适用场景` / `## 不适用场景` / `## 工作流`
- metadata.zhiwei.category 使用五个合法值之一，其他值虽然不会拒绝但会被渲染成 category 名字直接上屏
- suggested_tools 只能引用已注册的工具 id，不存在的工具会在激活时静默丢弃
- 占位符 `{skill_dir}` / `{skill_references_dir}` / `{skill_scripts_dir}` 会由 SkillActivator 替换为绝对路径
- 超长 body 拆 references/ 而不是裁剪关键信息

## 常见错误处理

- **description 校验失败** → 检查开头是否"当/用于/Use when"，是否含"步骤/首先/然后/Step N/First/Then"
- **name 正则不匹配** → 必须小写字母或数字开头，只能包含小写字母、数字、中划线，长度 1-63
- **body 缺小节** → 三个必需标题必须**完全一致**包括前导 `## `
- **body 超 5000 字符** → 把最长的子章节（如完整 API 列表、详细错误表）拆到 references/xxx.md
- **字段 id 拒绝** → v1 规范的 `id:` 已废弃，改为 `name:`
- **requires 不满足** → SkillRequirementGate 会从 catalog 输出剔除该 Skill，检查 bins/env/os/tools 是否真的可用
