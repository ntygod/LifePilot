package com.lifepilot.agent.proactive.signal;

import com.lifepilot.agent.proactive.model.Signal;

import java.util.List;

/**
 * 信号源接口 — 任何能产生主动推理信号的模块均可实现。
 *
 * <p>不限于 Skill，Workflow、Memory、Sync 等任何模块均可注册为信号源。
 * 实现类注册为 Spring Bean 后，{@link com.lifepilot.agent.proactive.SignalCollector}
 * 会自动发现并在每次信号收集时调用 {@link #collect()} 方法。</p>
 *
 * @author zsg
 * @since 2026-03-10
 */
public interface SignalSource {

    /**
     * 信号源唯一标识。
     *
     * @return 标识字符串
     */
    String id();

    /**
     * 收集信号，返回泛化信号列表。
     *
     * @return 信号列表，不为 null
     */
    List<Signal> collect();
}
