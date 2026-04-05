package com.lifepilot.sandbox.model;

import java.util.Map;
import java.util.Optional;

/**
 * 沙箱支持的编程语言。
 *
 * @author zsg
 * @since 2026-03-01
 */
public enum Language {

    PYTHON("python", ".py"),
    JAVASCRIPT("node", ".js"),
    SHELL("bash", ".sh");

    private final String runtimeCommand;
    private final String fileExtension;

    /** 别名 → 标准枚举值的映射表（小写）。 */
    private static final Map<String, Language> ALIASES = Map.of(
            "python", PYTHON,
            "py", PYTHON,
            "python3", PYTHON,
            "javascript", JAVASCRIPT,
            "js", JAVASCRIPT,
            "node", JAVASCRIPT,
            "shell", SHELL,
            "bash", SHELL,
            "sh", SHELL
    );

    Language(String runtimeCommand, String fileExtension) {
        this.runtimeCommand = runtimeCommand;
        this.fileExtension = fileExtension;
    }

    /**
     * 获取语言的默认运行时命令。
     *
     * @return 运行时命令（如 python3、node、bash）
     */
    public String runtimeCommand() {
        return runtimeCommand;
    }

    /**
     * 获取语言的文件扩展名。
     *
     * @return 文件扩展名（如 .py、.js、.sh）
     */
    public String fileExtension() {
        return fileExtension;
    }

    /**
     * 从字符串解析语言枚举，支持别名（py/js/bash/sh/node/python3 等）。
     *
     * @param name 语言名称（不区分大小写）
     * @return 对应的 Language 枚举值
     */
    public static Optional<Language> fromString(String name) {
        if (name == null) {
            return Optional.empty();
        }
        // 先查别名表，再回退到枚举名匹配
        Language aliased = ALIASES.get(name.toLowerCase());
        if (aliased != null) {
            return Optional.of(aliased);
        }
        try {
            return Optional.of(valueOf(name.toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
