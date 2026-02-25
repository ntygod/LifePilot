package com.lifepilot.interaction.middleware.auth;

import lombok.Builder;
import org.springframework.lang.Nullable;

/**
 * 认证结果不可变记录，封装认证策略的执行结果。
 *
 * <p>通过静态工厂方法 {@link #success(String, TrustLevel)} 和 {@link #failure(String)} 创建实例，
 * 避免直接构造时遗漏字段。
 *
 * @param authenticated  是否认证通过
 * @param userId         用户标识，认证失败时为空字符串
 * @param trustLevel     信任等级，认证失败时为 {@link TrustLevel#ANONYMOUS}
 * @param failureReason  认证失败原因，认证成功时为 null
 * @author zsg
 * @since 2026-02-25
 */
@Builder(toBuilder = true)
public record AuthResult(
        boolean authenticated,
        String userId,
        TrustLevel trustLevel,
        @Nullable String failureReason
) {

    /**
     * 创建认证成功结果。
     *
     * @param userId     用户标识
     * @param trustLevel 信任等级
     * @return 认证成功的 AuthResult
     */
    public static AuthResult success(String userId, TrustLevel trustLevel) {
        return new AuthResult(true, userId, trustLevel, null);
    }

    /**
     * 创建认证失败结果。
     *
     * @param reason 失败原因
     * @return 认证失败的 AuthResult
     */
    public static AuthResult failure(String reason) {
        return new AuthResult(false, "", TrustLevel.ANONYMOUS, reason);
    }
}
