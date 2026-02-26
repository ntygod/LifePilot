package com.lifepilot.sandbox.model;

import java.time.Instant;

import org.springframework.lang.Nullable;

/**
 * 执行审计记录。
 *
 * <p>每次代码执行（无论成功、超时、失败或被拒绝）都会生成一条审计记录，
 * 代码内容仅存 SHA-256 哈希，不存储原始代码。</p>
 *
 * @param id               记录 ID（UUID）
 * @param sessionId        会话 ID
 * @param language         编程语言
 * @param codeHash         代码 SHA-256 哈希
 * @param codeLength       代码长度（字符数）
 * @param booterType       沙箱类型（process / docker）
 * @param validationPassed 预检是否通过
 * @param violationCount   违规项数量
 * @param exitCode         退出码（预检失败时为 null）
 * @param stdoutLength     标准输出字节长度（预检失败时为 null）
 * @param stderrLength     标准错误字节长度（预检失败时为 null）
 * @param durationMs       执行耗时毫秒（预检失败时为 null）
 * @param state            执行状态（COMPLETED / TIMEOUT / FAILED / REJECTED）
 * @param errorMessage     错误消息（正常完成时为 null）
 * @param createdAt        创建时间
 * @param updatedAt        更新时间
 * @author zsg
 * @since 2026-03-01
 */
public record ExecutionRecord(
        String id,
        String sessionId,
        Language language,
        String codeHash,
        int codeLength,
        String booterType,
        boolean validationPassed,
        int violationCount,
        @Nullable Integer exitCode,
        @Nullable Integer stdoutLength,
        @Nullable Integer stderrLength,
        @Nullable Long durationMs,
        String state,
        @Nullable String errorMessage,
        Instant createdAt,
        Instant updatedAt
) {}
