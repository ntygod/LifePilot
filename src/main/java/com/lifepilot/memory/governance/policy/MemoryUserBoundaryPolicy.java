package com.lifepilot.memory.governance.policy;

import org.springframework.lang.Nullable;

import java.util.Locale;

/**
 * 用户对本轮记忆使用边界的轻量识别策略。
 *
 * @author zsg
 * @since 2026-07-06
 */
public final class MemoryUserBoundaryPolicy {

    private static final String[] DEFAULT_MEMORY_READ_BLOCKERS = {
            "不要参考记忆",
            "不参考记忆",
            "别参考记忆",
            "无需参考记忆",
            "不要使用记忆",
            "不要用记忆",
            "不用记忆",
            "不使用记忆",
            "不要读取记忆",
            "忽略记忆",
            "不要参考历史",
            "不参考历史",
            "别参考历史",
            "无需参考历史",
            "不要使用历史",
            "不要用历史",
            "不用历史",
            "忽略历史",
            "dontusememory",
            "don'tusememory",
            "donotusememory",
            "nomemory",
            "withoutmemory"
    };

    private static final String[] AUTO_LEARNING_BLOCKERS = {
            "不要记住",
            "别记住",
            "不要学习这次",
            "别学习这次",
            "不要学习",
            "别学习",
            "不要写入长期记忆",
            "不要写长期记忆",
            "不写入长期记忆",
            "不要沉淀记忆",
            "不要保存记忆",
            "不要保存这次",
            "不要把这次记下来",
            "dontremember",
            "don'tremember",
            "donotremember",
            "dontsavememory",
            "don'tsavememory",
            "donotsavememory",
            "dontwritememory",
            "don'twritememory",
            "donotwritememory",
            "nomemory"
    };

    private static final String[] CONVERSATION_SMOKE_TEST_MARKERS = {
            "这是知微主对话闭环冒烟测试",
            "主对话闭环冒烟测试",
            "冒烟测试消息",
            "smoketestmessage",
            "conversationloopsmoketest"
    };

    private MemoryUserBoundaryPolicy() {
    }

    public static Decision defaultMemoryReadBoundary(@Nullable String userText) {
        String normalized = normalize(userText);
        if (normalized.isBlank()) {
            return Decision.allow();
        }
        if (containsAny(normalized, DEFAULT_MEMORY_READ_BLOCKERS)) {
            return Decision.skip("user_memory_read_denied");
        }
        if (containsAny(normalized, CONVERSATION_SMOKE_TEST_MARKERS)) {
            return Decision.skip("conversation_smoke_test");
        }
        return Decision.allow();
    }

    public static Decision autoLearningBoundary(@Nullable String userText) {
        String normalized = normalize(userText);
        if (normalized.isBlank()) {
            return Decision.allow();
        }
        if (containsAny(normalized, AUTO_LEARNING_BLOCKERS)) {
            return Decision.skip("user_memory_write_denied");
        }
        if (containsAny(normalized, CONVERSATION_SMOKE_TEST_MARKERS)) {
            return Decision.skip("conversation_smoke_test");
        }
        return Decision.allow();
    }

    private static String normalize(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "")
                .replace("，", ",")
                .replace("。", ".")
                .replace("’", "'");
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    public record Decision(boolean skip, @Nullable String reason) {
        private static Decision allow() {
            return new Decision(false, null);
        }

        private static Decision skip(String reason) {
            return new Decision(true, reason);
        }
    }
}
