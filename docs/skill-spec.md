# ZhiWei Skill 规范 v3

## 1. 定位

Skill 是可移植的任务策略包：负责告诉 Agent 何时触发、如何决策、产出什么、失败时如何收束。Tool 是可执行能力，MCP / Channel 是能力接入，A2UI 是可交互输出。Skill 不应退化成工具命令清单，也不应复制全局 Agent 规则。

## 2. 目录结构

每个 Skill 位于 `<skills 根目录>/<skill-name>/`：

    skills/<name>/
    ├── SKILL.md
    ├── references/
    │   └── *.md
    ├── scripts/
    │   └── *.{sh,py,js}
    └── assets/
        └── *

`SKILL.md` 必需；其他目录按需创建。详细命令、长模板、参数表和错误手册放 `references/`，可重复执行且容易写错的逻辑放 `scripts/`。

## 3. Frontmatter

| 字段 | 必需 | 类型 | 约束 |
|---|---|---|---|
| `name` | 是 | string | `^[a-z0-9][a-z0-9-]{0,62}$`，等于目录名 |
| `description` | 是 | string | ≤1024 字符，以 `当` / `用于` / `Use when` / `Use this when` 开头；只写触发面和边界，不写步骤 |
| `version` | 是 | string | semver，例如 `1.0.0` |
| `metadata.zhiwei` | 否 | object | 知微运行期元数据 |

### metadata.zhiwei

| 字段 | 类型 | 用途 |
|---|---|---|
| `tags` | `List<string>` | 检索、排序和 UI 标签 |
| `suggested_tools` | `List<string>` | 建议工具 ID；仅作提示，不自动注入 |
| `outputs` | `List<string>` | 预期输出形态：`text` / `file` / `a2ui` / `memory` / `notification` / `task` |
| `requires.bins` | `List<string>` | 必需二进制 |
| `requires.env` | `List<string>` | 必需环境变量名 |
| `requires.os` | `List<string>` | `windows` / `darwin` / `linux` |
| `requires.tools` | `List<string>` | 必需已注册工具 ID |

`suggested_tools` 和 `outputs` 是路由与展示信号，不代表授权执行。真正的工具可用性仍由工具注册表、权限和用户确认决定。

## 4. Body

Body ≤5000 字符，必须包含四个二级标题：

    ## 触发判断
    - 写 2-5 条正例，并写清不要触发的边界。

    ## 决策路径
    - 按用户意图分流，写判断顺序和升级条件。
    - 不堆具体命令；复杂细节引用 references/。

    ## 输出标准
    - 说明结果应以文本、文件、A2UI、记忆、通知或任务中的哪几类交付。
    - 写清必须验证的证据、字段、路径、数值或交互状态。

    ## 失败策略
    - 写依赖缺失、权限不足、信息不够、工具失败、风险过高时如何降级或追问。

可选：

    ## 详细参考
    - 引用 `{skill_dir}/references/<file>.md`

## 5. 编写原则

- 把“何时用”写进 description，因为 body 只有触发后才会加载。
- 把“怎么判断”写进 body，优先策略、边界、质量标准和失败路径。
- 把长命令、模板、API 参数、错误码和示例移入 references。
- 不在 Skill 中重复全局礼貌、安全、工具协议或系统提示词已有规则。
- 每个 Skill 都要声明 outputs，方便主对话轻量展示和结果渲染。

## 6. 校验规则

| 校验器 | 规则 |
|---|---|
| `MarkdownSkillParser` | frontmatter 必需字段、name 正则、version semver、拒绝废弃 `id` 字段、解析 `metadata.zhiwei` |
| `SkillDescriptionValidator` | description 非空、≤1024、触发词开头、禁止工作流词 |
| `SkillBodyValidator` | body 非空、≤5000、必须含 v3 四段 |
| `SkillValidator` | description/body 校验、secret 扫描、outputs 枚举校验；自生成路径额外校验工具存在且非 HIGH/CRITICAL |
| `SkillRequirementGate` | 加载期检查 bins/env/os/tools，不满足则不进入 catalog |

## 7. 执行链路

1. `SkillInstaller` 解析并校验 SKILL.md，写入 `<skills>/<name>/SKILL.md` 和 `skills` 表。
2. `MarkdownSkillLoader` 扫描安装目录，构造 `SkillDefinition` 并注册到 `SkillRegistry`。
3. `ContextAssembler` 只把已启用且依赖满足的 Skill catalog 注入系统提示词。
4. Agent 需要完整策略时调用 `skill.load`，`SkillActivator` 替换 `{skill_dir}` / `{skill_references_dir}` / `{skill_scripts_dir}`。
5. `SkillLoadToolExecutor` 返回 body，并在发现 references 路径时追加 `file.read(...)` 强引导。
