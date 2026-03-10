package com.lifepilot.agent.proactive.candidate;

import com.lifepilot.agent.proactive.model.ProactiveCandidate;
import com.lifepilot.agent.proactive.model.SignalBundle;

import java.util.List;

/**
 * 候选提供者接口 — 将信号解释为"值得主动介入的候选机会"。
 *
 * <p>任何模块（Skill、Workflow、Memory、Sync 等）均可实现此接口并注册为 Spring Bean，
 * {@code PolicyEngine} 会自动发现并在评估阶段遍历所有候选提供者。</p>
 *
 * @author zsg
 * @since 2026-03-10
 */
public interface CandidateProvider {

    /** 候选提供者唯一标识。 */
    String id();

    /** 评估信号包，返回候选列表。 */
    List<ProactiveCandidate> evaluate(SignalBundle signals);
}
