package com.lifepilot.agent.task.proactive;

import java.util.List;

/**
 * 主动行为插件接口。
 *
 * <p>每种主动行为（提醒、信息补充、洞察推送等）实现此接口，
 * 由 {@link ProactiveEngine} 在心跳中调度。</p>
 *
 * <p>生命周期：detect（快速检测候选）→ reason（精细推理生成内容）→
 * execute（自主度 C 时代行）→ onDelivered（投递后回调）。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
public interface ProactiveBehavior {

    /** 插件名称，用于日志和跨插件去重。 */
    String name();

    /**
     * 快速检测：是否有候选？返回带粗略分数的候选列表。
     *
     * <p>在 Gate 2 调用，不应调用 LLM。应尽量轻量。
     * 插件负责自身的主题级过滤（静音、冷却），框架负责全局过滤。</p>
     *
     * @param ctx 心跳上下文
     * @return 候选列表（可为空）
     */
    List<ProactiveCandidate> detect(ContextPacket ctx);

    /**
     * 精细推理：为入选候选生成具体内容。
     *
     * <p>仅在 Gate 3（FULL）时调用，允许调用 LLM。
     * 接收该插件的所有入选候选，可做跨候选合成。</p>
     *
     * @param candidates 入选的候选列表
     * @param ctx        心跳上下文
     * @return 生成的主动行为动作列表
     */
    List<ProactiveAction> reason(List<ProactiveCandidate> candidates, ContextPacket ctx);

    /**
     * 执行动作 — 仅在自主度为 C（代行）时调用。
     *
     * <p>Phase 1 不使用，预留接口。</p>
     */
    default void execute(ProactiveAction action) {}

    /**
     * 投递后回调 — 插件可在此做持久化、训练样本记录等。
     */
    default void onDelivered(ProactiveAction action, DeliveryResult result) {}
}
