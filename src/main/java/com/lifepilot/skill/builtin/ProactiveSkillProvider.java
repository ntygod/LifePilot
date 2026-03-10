package com.lifepilot.skill.builtin;

import com.lifepilot.agent.proactive.candidate.CandidateProvider;
import com.lifepilot.agent.proactive.signal.SignalSource;

import java.util.List;

/**
 * 支持主动推理的内置 Skill 提供者接口。
 *
 * <p>扩展 {@link BuiltinSkillProvider}，允许 Skill 声明自己的信号源和候选提供者。
 * 默认方法返回空列表，Skill 可按需覆盖。</p>
 *
 * @author zsg
 * @since 2026-03-10
 */
public interface ProactiveSkillProvider extends BuiltinSkillProvider {

    /**
     * 提供该 Skill 的信号源列表。默认返回空列表。
     *
     * @return 信号源列表，不为 null
     */
    default List<SignalSource> signalSources() {
        return List.of();
    }

    /**
     * 提供该 Skill 的候选提供者列表。默认返回空列表。
     *
     * @return 候选提供者列表，不为 null
     */
    default List<CandidateProvider> candidateProviders() {
        return List.of();
    }
}
