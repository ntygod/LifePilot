package com.lifepilot.meta.infra.code.kernel;

import java.util.Map;

/**
 * 持久内核接口 — 支持跨调用保持状态的代码执行内核。
 *
 * <p>每个内核维护独立的运行时上下文（变量、导入等），
 * 支持 Python、JavaScript 和 Shell 三种语言。</p>
 *
 * @author zsg
 * @since 2026-03-31
 */
public sealed interface PersistentKernel permits ProcessKernelBase, ShellKernel {

    /**
     * 获取内核唯一标识。
     *
     * @return 内核 ID
     */
    String kernelId();

    /**
     * 获取内核当前状态。
     *
     * @return 当前状态
     */
    KernelState state();

    /**
     * 在内核中执行代码。
     *
     * @param code           要执行的代码
     * @param timeoutSeconds 超时时间（秒）
     * @return 执行结果
     */
    KernelExecutionResult execute(String code, int timeoutSeconds);

    /**
     * 检查内核内部状态（如已定义的变量）。
     *
     * @return 状态信息 Map
     */
    Map<String, String> inspect();

    /**
     * 重置内核状态（清空变量等），不关闭进程。
     */
    void reset();

    /**
     * 关闭内核，释放所有资源。
     */
    void close();
}
