package com.lifepilot.meta.infra.interaction;

/**
 * CLI 交互处理器接口 — 抽象 CLI 通道的交互推送。
 *
 * <p>由 CLI 模块实现，InteractionBridge 通过 {@code @Nullable} 注入。
 * CLI 模块未加载时为 null，InteractionBridge 将仅使用 Web Channel。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public interface CliInteractionHandler {

    /**
     * 推送交互请求到 CLI 终端。
     *
     * @param request 交互请求
     */
    void pushInteraction(InteractionRequest request);
}
