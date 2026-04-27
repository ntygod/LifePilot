package com.lifepilot.sandbox.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 sealed {@link RuntimeStatus} 扁平化为前端契约的 JSON 表示。
 *
 * <p><b>为何不让 Jackson 直接序列化 sealed record</b>：默认 Jackson
 * 不会为 record 加入判别字段（前端契约要 {@code status: 'INSTALLING'}），
 * 也不会序列化 {@link RuntimeStatus.Installing#percent()} 这种非 component 方法。
 * REST 路径（{@code RuntimeController#status}）和 SSE 路径
 * （{@link RuntimeInstallProgressEmitter#emit}）必须共用同一份扁平化逻辑，
 * 否则前端 {@code status?.status === 'INSTALLING'} 判定永远 false，
 * 进度条不渲染。</p>
 *
 * @author zsg
 * @since 2026-04-26
 */
public final class RuntimeStatusJson {

    private RuntimeStatusJson() {
    }

    /**
     * 把 {@link RuntimeStatus} 转成扁平 Map，字段集合：
     * <ul>
     *   <li>NotInstalled / Disabled → {@code status} 一个字段</li>
     *   <li>Installing → status, phase, bytesDownloaded, totalBytes, percent</li>
     *   <li>Ready → status, version, diskBytes</li>
     *   <li>InstallFailed → status, reason</li>
     * </ul>
     */
    public static Map<String, Object> toMap(RuntimeStatus status) {
        var map = new LinkedHashMap<String, Object>();
        switch (status) {
            case RuntimeStatus.NotInstalled n -> map.put("status", "NOT_INSTALLED");
            case RuntimeStatus.Disabled d -> map.put("status", "DISABLED");
            case RuntimeStatus.Installing i -> {
                map.put("status", "INSTALLING");
                map.put("phase", i.phase());
                map.put("bytesDownloaded", i.bytesDownloaded());
                map.put("totalBytes", i.totalBytes());
                map.put("percent", i.percent());
            }
            case RuntimeStatus.Ready r -> {
                map.put("status", "READY");
                map.put("version", r.version());
                map.put("diskBytes", r.diskBytes());
            }
            case RuntimeStatus.InstallFailed f -> {
                map.put("status", "INSTALL_FAILED");
                map.put("reason", f.reason());
            }
        }
        return map;
    }
}
