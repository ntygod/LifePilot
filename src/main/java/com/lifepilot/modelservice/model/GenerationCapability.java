package com.lifepilot.modelservice.model;

/**
 * 生成服务子能力枚举。
 *
 * @author zsg
 * @since 2026-03-24
 */
public enum GenerationCapability {
    /** 对话生成。 */
    CHAT,
    /** 结构化输出。 */
    STRUCTURED_OUTPUT,
    /** 函数调用。 */
    FUNCTION_CALLING,
    /** 流式输出。 */
    STREAMING,
    /** 视觉理解。 */
    VISION,
    /** 原生音频理解。 */
    NATIVE_AUDIO,
    /** 原生视频理解。 */
    NATIVE_VIDEO
}
