package com.lifepilot.sandbox.model;

import java.nio.file.Path;

/**
 * 代码执行请求。
 *
 * @param language         编程语言
 * @param code             待执行的代码
 * @param timeoutSeconds   超时时间（秒）
 * @param workingDirectory 工作目录
 * @author zsg
 * @since 2026-03-01
 */
public record ExecutionRequest(
        Language language,
        String code,
        int timeoutSeconds,
        Path workingDirectory
) {}
