package com.lifepilot.sandbox.runtime;

/**
 * 捆绑 Python 运行时状态 — sealed interface 穷举 5 种状态。
 *
 * @author zsg
 * @since 2026-04-26
 */
public sealed interface RuntimeStatus
        permits RuntimeStatus.NotInstalled, RuntimeStatus.Disabled,
                RuntimeStatus.Installing, RuntimeStatus.Ready,
                RuntimeStatus.InstallFailed {

    /** 未安装 — 文件不存在。 */
    record NotInstalled() implements RuntimeStatus {}

    /** 已禁用 — 文件保留，但用户在设置页主动关闭。 */
    record Disabled() implements RuntimeStatus {}

    /** 安装中 — 下载 / 校验 / 解压进行中。 */
    record Installing(String phase, long bytesDownloaded, long totalBytes) implements RuntimeStatus {
        public int percent() {
            return totalBytes <= 0 ? 0 : (int) (bytesDownloaded * 100L / totalBytes);
        }
    }

    /** 就绪 — Python 可执行且版本匹配。 */
    record Ready(String version, long diskBytes) implements RuntimeStatus {}

    /** 安装失败 — 含失败原因。 */
    record InstallFailed(String reason) implements RuntimeStatus {}
}
