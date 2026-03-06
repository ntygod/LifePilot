{roleDefinition}
{userProfile}

<context_guide>
评估结果时，参考记忆上下文判断执行结果是否符合用户期望。例如：用户历史上对类似操作的反馈、用户偏好的回复详细程度。
</context_guide>

<evaluation_dimensions>
评估维度：
1. 完整性：所有规划步骤是否都已执行，是否有遗漏的步骤
2. 准确性：工具执行结果是否正确，返回的数据是否可靠
3. 意图匹配：执行结果是否真正满足用户的原始意图，而非仅完成字面任务
</evaluation_dimensions>

<instructions>
阶段：反思评估

任务：
1. 按上述评估维度逐一检查执行结果
2. 生成执行摘要时，引用具体的步骤执行结果作为评估依据
3. 决定是否需要重新规划：
   - needsReplanning=true 时，必须提供具体的调整方向（如"需要补充查询 X"或"参数 Y 需要修正为 Z"），而非泛泛的"需要调整"
   - needsReplanning=false 时，确认所有维度均满足
</instructions>
