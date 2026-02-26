package com.lifepilot.workflow.model;

/**
 * 步骤错误处理策略 sealed interface。
 *
 * <p>定义步骤执行失败时的四种处理策略：
 * <ul>
 *   <li>{@link Retry} — 指数退避重试，耗尽后回退到 Fail</li>
 *   <li>{@link Skip} — 跳过当前步骤，记录原因，继续执行</li>
 *   <li>{@link Fail} — 终止工作流，标记为 FAILED</li>
 *   <li>{@link Compensate} — 执行补偿步骤后标记失败（借鉴 Saga Pattern）</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-02-26
 */
public sealed interface ErrorStrategy permits
        ErrorStrategy.Retry,
        ErrorStrategy.Skip,
        ErrorStrategy.Fail,
        ErrorStrategy.Compensate {

    /**
     * 指数退避重试策略。
     *
     * @param maxAttempts   最大重试次数
     * @param initialDelayMs 初始延迟（毫秒）
     * @param maxDelayMs    最大延迟（毫秒）
     */
    record Retry(int maxAttempts, long initialDelayMs, long maxDelayMs) implements ErrorStrategy {}

    /**
     * 跳过策略，记录跳过原因后继续执行后续步骤。
     *
     * @param reason 跳过原因
     */
    record Skip(String reason) implements ErrorStrategy {}

    /**
     * 失败策略，终止工作流并标记为 FAILED。
     */
    record Fail() implements ErrorStrategy {}

    /**
     * 补偿策略，执行补偿步骤后标记原步骤为失败。
     *
     * <p>借鉴 Saga Pattern，允许用户为关键步骤定义回滚操作。
     *
     * @param compensationStep 补偿步骤（前向引用 {@link WorkflowStep}）
     */
    record Compensate(WorkflowStep compensationStep) implements ErrorStrategy {}
}
