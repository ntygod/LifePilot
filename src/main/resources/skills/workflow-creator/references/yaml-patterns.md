# 工作流 YAML 模式详细参考

## condition 分支

```yaml
- id: check-risk
  type: condition
  condition: "${steps.risk-analysis.output.result.riskLevel} == 'high'"
  dependsOn: [risk-analysis]
  then:
    - id: need-approval
      type: approval
      message: "高风险内容需要审批"
      approvers: [admin]
      timeoutSeconds: 86400
  else:
    - id: auto-pass
      type: noop
```

## loop 循环

```yaml
- id: process-items
  type: loop
  items: "${steps.fetch-data.output.result}"
  loopVar: item
  body:
    - id: handle-item
      type: llm
      scene: agent_reasoning
      prompt: "处理：${item}"
```

## parallel 并行

```yaml
- id: parallel-analysis
  type: parallel
  branches:
    - - id: branch-a
        type: llm
        prompt: "情感分析：${inputs.content}"
    - - id: branch-b
        type: llm
        prompt: "关键词提取：${inputs.content}"
```

## 其他参考

详细的步骤语义、错误处理、子工作流参数传递参见 `docs/guides/workflow-guide.md`。
