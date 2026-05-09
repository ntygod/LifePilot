package com.lifepilot.memory.eval.runner;

import com.lifepilot.memory.eval.loader.BenchmarkQuestion;
import com.lifepilot.memory.eval.loader.BenchmarkSession;

import java.util.List;

/**
 * 向隔离记忆环境提问的适配器。
 *
 * <p>本接口存在的目的是把"如何召回答案"的细节与 {@link BenchmarkRunner} 解耦：</p>
 * <ul>
 *     <li>真实实现调用 {@code memory.search} / {@code memory.recall}，拼 context 后由 LLM 回答</li>
 *     <li>测试实现 mock 返回固定预测，便于 Runner 主流程单测</li>
 * </ul>
 *
 * <p>首版 spec 只定义接口；真实实现放到后续集成任务。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public interface MemoryQueryAdapter {

    /**
     * 针对单题产出 Agent 回答。
     *
     * @param sessions        对话历史（已被 Replayer 写入 L0）
     * @param question        评估题
     * @return 一次召回 + 回答的结果，含预测文本、召回实体 ID、注入文本（用于 token 估算）
     */
    QueryResult answer(List<BenchmarkSession> sessions, BenchmarkQuestion question);

    /**
     * 单次查询结果。
     *
     * @param prediction           Agent 回答文本
     * @param retrievedEntityIds   本次召回实体 ID（供 RetrievalProbe 命中率统计）
     * @param injectedContextText  实际拼入 Prompt 的上下文文本（供 TokenProbe 估算）
     */
    record QueryResult(
            String prediction,
            List<String> retrievedEntityIds,
            String injectedContextText
    ) {
        public QueryResult {
            if (prediction == null) prediction = "";
            retrievedEntityIds = retrievedEntityIds == null ? List.of() : List.copyOf(retrievedEntityIds);
            if (injectedContextText == null) injectedContextText = "";
        }
    }
}
