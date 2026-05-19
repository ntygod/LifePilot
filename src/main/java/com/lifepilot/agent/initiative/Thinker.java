package com.lifepilot.agent.initiative;

import com.lifepilot.agent.initiative.model.Signal;
import com.lifepilot.agent.initiative.model.Thought;

import java.util.List;
import java.util.Optional;

/**
 * 思考器接口 — 将信号转化为想法，或在空闲时主动思考。
 *
 * <p>两种工作模式：
 * <ul>
 *   <li>事件响应：收到信号后快速判断是否值得形成想法</li>
 *   <li>空闲思考：用户不活跃时主动回顾记忆、发现关联</li>
 * </ul></p>
 *
 * @author zsg
 * @since 2026-06-01
 */
public interface Thinker {

    /**
     * 事件响应：处理信号，可能产生新想法。
     *
     * @param signal 外部事件信号
     * @return 产生的想法（如果信号值得形成想法）
     */
    Optional<Thought> processSignal(Signal signal);

    /**
     * 空闲思考：主动回顾记忆，发现值得表达的想法。
     *
     * @return 产生的新想法列表
     */
    List<Thought> idleThink();
}
