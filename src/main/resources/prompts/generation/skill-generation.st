请根据以下需求生成一个 YAML 格式的 Skill 定义。

<constraints>
## 安全约束（必须遵守）
- 只能引用已注册的非 HIGH/CRITICAL 风险工具
- maxSteps 不超过 {maxSteps}
- timeoutSeconds 不超过 {maxTimeout}
- maxTokens 不超过 {maxTokens}
- maxCostCents 不超过 {maxCostCents}
- 记忆写权限必须设置 require-approval 为 true
</constraints>

<requirements>
## 需求描述
{gapDescription}
</requirements>

<examples>
## 已有 Skill 参考（格式示例）
{existingSkills}
</examples>

<output>
## 输出要求
请直接输出 YAML 内容，根节点为 skill，包含 id、name、description、version、system-prompt、allowed-tools 等字段。
</output>
