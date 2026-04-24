# ZhiWei Skill 规范 v2

## 1. Skill 目录结构（三级物理分层）

每个 skill 是 `<skills 根目录>/<skill-name>/` 下的一个文件夹：

    skills/<name>/
    ├── SKILL.md              # 必需。L1 frontmatter + L2 body（前 3 小节必需）
    ├── references/           # 可选。L3 按需加载的详细参考
    │   └── *.md              #   LLM 用 file.read(path=...) 主动加载
    ├── scripts/              # 可选。可执行脚本（不入 context）
    │   └── *.{sh,py,js}
    └── assets/               # 可选。模板/schema/静态资源（不入 context）
        └── *

## 2. SKILL.md 结构

### 2.1 Frontmatter（YAML，必需）

| 字段 | 必需 | 类型 | 约束 |
|---|---|---|---|
| `name` | 是 | string | 正则 `^[a-z0-9][a-z0-9-]{0,62}$`，等于目录名 |
| `description` | 是 | string | ≤1024 字符，"当…时使用" 或 "Use when…" 开头，不得含工作流词（步骤 N / 首先 / 然后 / 接下来 / Step N / First / Then） |
| `version` | 是 | string | 语义化版本 semver，如 `1.0.0` |
| `metadata.zhiwei` | 否 | object | 下表字段 |

### 2.2 `metadata.zhiwei` 子字段

| 字段 | 类型 | 用途 |
|---|---|---|
| `suggested_tools` | `List<string>` | 激活后合并进 activatedToolIds（软引导） |
| `tags` | `List<string>` | 辅助检索 |
| `category` | string | external-integration / content-creation / automation / infrastructure / utility |
| `priority` | enum | `high` / `normal` / `low`，影响 catalog 排序 |
| `requires.bins` | `List<string>` | 运行依赖的二进制（如 `git`, `gh`, `sqlite3`） |
| `requires.env` | `List<string>` | 必需环境变量（名称，不含值） |
| `requires.os` | `List<string>` | OS 白名单 `windows` / `darwin` / `linux` |
| `requires.tools` | `List<string>` | 必需的已注册工具 id |

### 2.3 Body（Markdown）

推荐 4 段式，`SkillBodyValidator` 强制前 3 段：

    ## 适用场景（必需）
    - 2-5 条，每条描述一个典型场景

    ## 不适用场景（必需）
    - 反例，压抑误触发

    ## 工作流（必需）
    - 高层步骤骨架，不写细节命令
    - 需要详细参数/示例/错误处理时引用 references/

    ## 详细参考（可选）
    - 工具参数：参见 {skill_dir}/references/api-details.md
    - 常见错误：参见 {skill_dir}/references/error-handbook.md

- Body 硬限 ≤ 5000 字符。超长强制拆 references。
- 可用占位符（由 SkillActivator 替换为绝对路径）：
  - `{skill_dir}` → skill 安装目录
  - `{skill_references_dir}` → `{skill_dir}/references`
  - `{skill_scripts_dir}` → `{skill_dir}/scripts`

## 3. References 文件

- 只是普通 Markdown
- 文件名 snake-case 或 kebab-case，描述性
- SKILL.md body 里用自然语言引用（`参见 {skill_dir}/references/api.md`）
- LLM 用 `file.read(path="...")` 加载

## 4. 安装来源

- `BUILTIN`：ZhiWei 内置，随 classpath 分发
- `USER_IMPORTED`：用户上传 .skill 包或 Git URL 导入
- `MARKETPLACE`：从 ZhiWei 市场下载
- `AUTO_GENERATED`：SkillSynthesizer 自动产出，默认 `enabled=1`，前端标识为"自动生成"以示区分

## 5. 校验规则

| 校验器 | 规则 |
|---|---|
| 解析期校验（`MarkdownSkillParser`） | 必需字段存在 + name 正则 + version semver + 拒绝老 `id` 字段 |
| `SkillDescriptionValidator` | 描述 ≤1024 + 开头触发词 + 禁工作流词 |
| `SkillBodyValidator` | body ≤5000 + 必需 3 小节 |
| `SkillValidator`（合一） | 调用 description/body validator + secret 模式检测 + 工具引用校验（generated 路径额外禁 HIGH/CRITICAL） |
| `SkillRequirementGate` | 加载期检查 requires.bins/env/os/tools，未满足不进 catalog |
