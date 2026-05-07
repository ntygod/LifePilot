package com.lifepilot.agent.callback;

/**
 * LLM 调用目的。
 *
 * <p>流式层只根据调用目的决定是否写入用户可见正文通道，
 * 避免把“是否带工具”误用成“是否最终答案”的判断依据。</p>
 *
 * @author zsg
 * @since 2026-05-07
 */
public enum LlmCallPurpose {

    /** 常规 ReAct 轮：模型可以直接回答，也可以选择工具调用。 */
    AGENT_STEP(true, true),

    /** 规划、工具选择或执行续跑阶段；模型正文只能作为内部候选内容收集。 */
    PLANNING(false, false),

    /** 已进入最终答案阶段；模型正文可以即时流式推送到用户可见答案区。 */
    FINAL_ANSWER(true, false);

    private final boolean visibleAnswerStream;
    private final boolean toolAwareCandidateStream;

    LlmCallPurpose(boolean visibleAnswerStream, boolean toolAwareCandidateStream) {
        this.visibleAnswerStream = visibleAnswerStream;
        this.toolAwareCandidateStream = toolAwareCandidateStream;
    }

    public boolean streamsVisibleAnswer() {
        return visibleAnswerStream;
    }

    public boolean usesToolAwareCandidateStream() {
        return toolAwareCandidateStream;
    }
}
