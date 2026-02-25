package com.lifepilot.interaction.middleware.auth;

/**
 * 信任等级枚举，表示消息来源的信任程度。
 *
 * <p>不同通道类型对应不同的信任等级：
 * <ul>
 *   <li>{@link #TRUSTED} — CLI 本地用户，完全信任</li>
 *   <li>{@link #VERIFIED} — 已验证身份（Web JWT / 企业通道签名）</li>
 *   <li>{@link #ANONYMOUS} — 未验证</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-02-25
 */
public enum TrustLevel {

    /** CLI 本地用户，完全信任 */
    TRUSTED,

    /** 已验证身份（Web JWT / 企业通道签名） */
    VERIFIED,

    /** 未验证 */
    ANONYMOUS
}
