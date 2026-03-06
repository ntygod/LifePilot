package com.lifepilot.interaction.web.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Web Controller Bug 条件探索测试。
 *
 * <p>验证两个后端 API 缺失 bug 的根因：
 * <ul>
 *   <li>Bug 7: TraceController 上的 {@code @ConditionalOnBean(TraceQuery.class)} 注解
 *       导致组件扫描时因 Bean 注册顺序问题而跳过注册，{@code GET /api/traces} 返回 404</li>
 *   <li>Bug 8: MarketplaceController 上的 {@code @ConditionalOnBean(MarketplaceService.class)} 注解
 *       导致组件扫描时因 Bean 注册顺序问题而跳过注册，{@code GET /api/marketplace/skills} 返回 404</li>
 * </ul>
 *
 * <p>{@code @ConditionalOnBean} 设计用于 {@code @Configuration} 类中的 {@code @Bean} 方法，
 * 而非组件扫描的 {@code @RestController}。当用在组件扫描类上时，条件评估发生在
 * AutoConfiguration Bean 注册之前，导致条件始终为 false，控制器不被注册。</p>
 *
 * <p>此测试编码期望行为（控制器不应有 {@code @ConditionalOnBean}），在未修复代码上将 FAIL，
 * 证明 bug 存在。修复后（移除注解）测试将 PASS。</p>
 *
 * <p><b>Validates: Requirements 1.7, 1.8</b>
 *
 * @author zsg
 * @since 2026-03-07
 */
class WebController_BugCondition_探索测试 {

    /**
     * Bug 7: 验证 TraceController 不应有 @ConditionalOnBean 注解。
     *
     * <p>期望行为：TraceController 作为 {@code @RestController} 应通过组件扫描无条件注册。
     * {@code @ConditionalOnBean(TraceQuery.class)} 在组件扫描阶段评估时，
     * TraceQuery Bean 尚未被 ObservabilityAutoConfiguration 注册，导致条件为 false，
     * 控制器被跳过，所有 {@code /api/traces} 端点返回 404。</p>
     *
     * <p>在未修复代码上，TraceController 类上存在 {@code @ConditionalOnBean} 注解，
     * 此断言将 FAIL，证明 bug 存在。</p>
     *
     * <p><b>Validates: Requirements 1.7</b>
     */
    @Test
    @DisplayName("Bug7_TraceController不应有ConditionalOnBean注解")
    void bug7_TraceController不应有ConditionalOnBean注解() {
        // 确认 TraceController 是 @RestController（应通过组件扫描注册）
        assertTrue(TraceController.class.isAnnotationPresent(RestController.class),
                "TraceController 应标注 @RestController");

        // 期望行为：TraceController 不应有 @ConditionalOnBean 注解
        // 在未修复代码上，此断言将 FAIL（注解存在，引用 TraceQuery.class）
        ConditionalOnBean conditionalAnnotation =
                TraceController.class.getAnnotation(ConditionalOnBean.class);

        assertNull(conditionalAnnotation,
                "TraceController 不应有 @ConditionalOnBean 注解 — "
                        + "@ConditionalOnBean 在组件扫描的 @RestController 上无法正确工作，"
                        + "因为 AutoConfiguration Bean（TraceQuery）在组件扫描之后才注册，"
                        + "导致条件始终为 false，控制器不被注册，/api/traces 返回 404。"
                        + "实际发现: @ConditionalOnBean("
                        + (conditionalAnnotation != null
                        ? java.util.Arrays.toString(conditionalAnnotation.value())
                        : "")
                        + ")");
    }

    /**
     * Bug 8: 验证 MarketplaceController 不应有 @ConditionalOnBean 注解。
     *
     * <p>期望行为：MarketplaceController 作为 {@code @RestController} 应通过组件扫描无条件注册。
     * {@code @ConditionalOnBean(MarketplaceService.class)} 在组件扫描阶段评估时，
     * MarketplaceService Bean 尚未被 MarketplaceAutoConfiguration 注册，导致条件为 false，
     * 控制器被跳过，所有 {@code /api/marketplace} 端点返回 404。</p>
     *
     * <p>在未修复代码上，MarketplaceController 类上存在 {@code @ConditionalOnBean} 注解，
     * 此断言将 FAIL，证明 bug 存在。</p>
     *
     * <p><b>Validates: Requirements 1.8</b>
     */
    @Test
    @DisplayName("Bug8_MarketplaceController不应有ConditionalOnBean注解")
    void bug8_MarketplaceController不应有ConditionalOnBean注解() {
        // 确认 MarketplaceController 是 @RestController（应通过组件扫描注册）
        assertTrue(MarketplaceController.class.isAnnotationPresent(RestController.class),
                "MarketplaceController 应标注 @RestController");

        // 期望行为：MarketplaceController 不应有 @ConditionalOnBean 注解
        // 在未修复代码上，此断言将 FAIL（注解存在，引用 MarketplaceService.class）
        ConditionalOnBean conditionalAnnotation =
                MarketplaceController.class.getAnnotation(ConditionalOnBean.class);

        assertNull(conditionalAnnotation,
                "MarketplaceController 不应有 @ConditionalOnBean 注解 — "
                        + "@ConditionalOnBean 在组件扫描的 @RestController 上无法正确工作，"
                        + "因为 AutoConfiguration Bean（MarketplaceService）在组件扫描之后才注册，"
                        + "导致条件始终为 false，控制器不被注册，/api/marketplace 返回 404。"
                        + "实际发现: @ConditionalOnBean("
                        + (conditionalAnnotation != null
                        ? java.util.Arrays.toString(conditionalAnnotation.value())
                        : "")
                        + ")");
    }
}
