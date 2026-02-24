package com.lifepilot.llm;

import com.lifepilot.llm.circuit.CircuitBreakerManager;
import com.lifepilot.llm.registry.ProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LLM 路由策略引擎（骨架，Task 12 完善实现）。
 *
 * @author zsg
 * @since 2026-02-24
 */
public class LlmRouter {

    private static final Logger log = LoggerFactory.getLogger(LlmRouter.class);

    private final ProviderRegistry providerRegistry;
    private final CircuitBreakerManager circuitBreakerManager;

    public LlmRouter(ProviderRegistry providerRegistry,
                     CircuitBreakerManager circuitBreakerManager) {
        this.providerRegistry = providerRegistry;
        this.circuitBreakerManager = circuitBreakerManager;
        log.info("LlmRouter 初始化完成");
    }
}
