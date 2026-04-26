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

    /** ANSI CSI 转义序列（含 SGR 子集，含 0x20-0x2F 中间字节）。
     *  仅覆盖 7-bit ESC [ 形式；不覆盖 OSC/DCS/SS3 及 C1 单字节 0x9B 形式。 */
    private static final Pattern ANSI = Pattern.compile("\\u001B\\[[0-?]*[ -/]*[@-~]");

    private CommandNormalizer() {}

    /**
     * 归一化命令字符串：NFKC → strip ANSI CSI → strip null。
     *
     * <p>顺序设计：先 NFKC（折叠全角字符为 ASCII，避免全角 ESC [ 形式绕过），
     * 再 strip ANSI（清除真实 CSI 序列，含 SGR），最后 strip null（清除注入的 null 字节）。
     * NFKC 本身不会引入 ESC（0x1B）或 null（0x00），所以反向也不会引入新攻击面。</p>
     *
     * @param input 待归一化字符串，允许 null
     * @return 归一化后的字符串；输入为 null 时返回空串（fail-closed）
     */
    public static String normalize(String input) {
        if (input == null) return "";
        String nfkc = Normalizer.normalize(input, Normalizer.Form.NFKC);
        String stripped = ANSI.matcher(nfkc).replaceAll("");
        return stripped.replace("\u0000", "");
    }
}
