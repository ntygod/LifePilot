package com.lifepilot.sandbox.guard;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * 命令字符串归一化 — 防止 ANSI 转义/全角字符/null 字节绕过 CommandGuard。
 *
 * @author zsg
 * @since 2026-04-26
 */
public final class CommandNormalizer {

    /** ANSI CSI / OSC / SGR 转义序列。 */
    private static final Pattern ANSI = Pattern.compile("\\u001B\\[[0-?]*[ -/]*[@-~]");

    private CommandNormalizer() {}

    /**
     * 归一化命令字符串：strip ANSI → NFKC → strip null。
     */
    public static String normalize(String input) {
        if (input == null) return "";
        String stripped = ANSI.matcher(input).replaceAll("");
        String nfkc = Normalizer.normalize(stripped, Normalizer.Form.NFKC);
        return nfkc.replace("\u0000", "");
    }
}
