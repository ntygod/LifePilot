---
name: skill-creator
description: 当用户要手动创作、升级或排查一个知微 Skill，或需要打磨 Skill v3 的 description、body、metadata.zhiwei、references 和校验错误时使用。
version: 1.2.0
metadata:
  zhiwei:
    tags:
      - skill-spec
      - meta
      - v3
      - template
      - author
      - validation
    suggested_tools:
      - file.read
      - file.write
    outputs:
      - text
      - file
---

# Skill 创作指南

帮用户手写、升级或修复一份符合 v3 规范的 SKILL.md。核心约束：description 负责触发面；body 负责任务策略；references 负责长细节；metadata.zhiwei.outputs 负责声明结果形态。

## 触发判断

- 从零写一个新的 Skill（frontmatter / 策略正文 / references / scripts）
- 把老 v1 / v2 Skill 升级为 v3 策略包
- 排查 description、body、version、metadata.zhiwei 或工具引用校验失败
- 打磨 tags、suggested_tools、outputs、requires，使 Skill 更容易被正确触发
- 不要触发：只是调用已有 Skill 执行业务时，直接用 `skill.load`；只是查看列表时，走能力/状态查询

## 决策路径

1. 先确认任务边界：这个 Skill 要解决哪类用户表达，哪些场景必须交给其他 Skill 或 Tool。
2. 写 frontmatter：`name` 用 kebab-case；`description` 用一句高精度触发描述；`version` 用 semver；`metadata.zhiwei` 至少补 tags 和 outputs。
3. 写 body 四段：`触发判断`、`决策路径`、`输出标准`、`失败策略`。不要把命令清单堆在 body。
4. 把长命令、模板、API 参数、错误处理表、示例迁到 `references/`，并在 body 的 `详细参考` 中引用。
5. `suggested_tools` 只写 canonical Tool ID；不确定时先用 `status` 或 `tool.search` 核对。
6. 自检：触发描述是否过宽？是否有失败策略？outputs 是否能指导 UI/文件/通知/记忆/任务结果？references 是否按需可读？

## 输出标准

- 输出新 Skill 时给出完整 SKILL.md，必要时同时给 references 文件内容或落盘路径。
- 修复旧 Skill 时说明触发面、决策路径、输出标准、失败策略分别改了什么。
- 生成文件后保持目录结构为 `skills/<name>/SKILL.md` + 可选 `references/` / `scripts/` / `assets/`。

## 失败策略

- 用户没有给具体使用场景时，先基于目标生成保守触发面，并标出需要用户确认的开放问题。
- 校验失败时优先改最小字段：description 开头、工作流词、缺失小节、version semver、outputs 枚举、旧 `id` 字段。
- 工具不存在时不要编造 Tool ID；改成不声明，或提示用户先接入对应 Tool/MCP。
- Skill 内容超过 5000 字符时拆 references，不要删关键策略。

## 详细参考

- v3 frontmatter 字段、body 模板、常见校验错误、老 Skill 迁移步骤：`{skill_dir}/references/v3-spec-template.md`
