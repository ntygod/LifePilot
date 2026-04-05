package com.lifepilot.meta.infra.git;

/**
 * Git 命令执行结果。
 *
 * @param exitCode 进程退出码
 * @param stdout   标准输出
 * @param stderr   标准错误输出
 * @author zsg
 * @since 2026-03-31
 */
public record GitCommandResult(int exitCode, String stdout, String stderr) {

    /** 命令是否执行成功（退出码为 0）。 */
    public boolean ok() {
        return exitCode == 0;
    }
}
