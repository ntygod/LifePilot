package com.lifepilot.meta.infra.env;

import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统信息工具执行器 — 获取 OS、JVM、内存和磁盘信息。
 *
 * <p>通过 {@code System.getProperty()} 和 {@code Runtime.getRuntime()} 聚合系统信息。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class SystemInfoToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(SystemInfoToolExecutor.class);

    /**
     * 执行系统信息查询。
     *
     * @param input 工具输入（无必需参数）
     * @return 包含系统信息的结构化结果
     */
    public ToolResult execute(ToolInput input) {
        try {
            Runtime runtime = Runtime.getRuntime();

            var data = new LinkedHashMap<String, Object>();

            // OS 信息
            data.put("osName", System.getProperty("os.name"));
            data.put("osVersion", System.getProperty("os.version"));
            data.put("osArch", System.getProperty("os.arch"));

            // JVM 信息
            data.put("javaVersion", System.getProperty("java.version"));
            data.put("javaVendor", System.getProperty("java.vendor"));
            data.put("jvmName", System.getProperty("java.vm.name"));

            // 内存信息（MB）
            long totalMemory = runtime.totalMemory() / (1024 * 1024);
            long freeMemory = runtime.freeMemory() / (1024 * 1024);
            long maxMemory = runtime.maxMemory() / (1024 * 1024);
            data.put("totalMemoryMB", totalMemory);
            data.put("freeMemoryMB", freeMemory);
            data.put("maxMemoryMB", maxMemory);
            data.put("usedMemoryMB", totalMemory - freeMemory);

            // CPU 信息
            data.put("availableProcessors", runtime.availableProcessors());

            // 磁盘信息（GB）
            File[] roots = File.listRoots();
            if (roots.length > 0) {
                File root = roots[0];
                data.put("diskTotalGB", root.getTotalSpace() / (1024 * 1024 * 1024));
                data.put("diskFreeGB", root.getFreeSpace() / (1024 * 1024 * 1024));
                data.put("diskUsableGB", root.getUsableSpace() / (1024 * 1024 * 1024));
            }

            // 用户信息
            data.put("userName", System.getProperty("user.name"));
            data.put("userHome", System.getProperty("user.home"));

            return ToolResult.success(Map.copyOf(data));
        } catch (Exception e) {
            log.error("获取系统信息失败: {}", e.getMessage(), e);
            return ToolResult.error("获取系统信息失败: " + e.getMessage());
        }
    }
}
