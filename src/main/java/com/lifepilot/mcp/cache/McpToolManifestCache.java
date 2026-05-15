package com.lifepilot.mcp.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.lifepilot.mcp.model.McpToolSchema;
import com.lifepilot.mcp.protocol.McpJsonSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * MCP 工具清单缓存 — 持久化 Server 的工具列表到本地文件。
 *
 * <p>缓存路径：{@code ~/.zhiwei/mcp/tool-cache/{serverName}.json}</p>
 *
 * <p>用途：启动时从缓存加载工具 Schema 并注册为工具桩，使 LLM 在 Server 未连接时
 * 也能感知可用工具。首次工具调用时触发懒连接，连接成功后自动刷新缓存。</p>
 *
 * @author zsg
 * @since 2026-04-09
 */
public class McpToolManifestCache {

    private static final Logger log = LoggerFactory.getLogger(McpToolManifestCache.class);

    private static final TypeReference<List<McpToolSchema>> SCHEMA_LIST_TYPE = new TypeReference<>() {};

    private final Path cacheDir;

    public McpToolManifestCache() {
        this(Path.of(System.getProperty("user.home"), "zhiwei", "mcp", "tool-cache"));
    }

    /** 测试用构造函数，支持自定义缓存目录。 */
    McpToolManifestCache(Path cacheDir) {
        this.cacheDir = cacheDir.toAbsolutePath().normalize();
    }

    /** 安全解析缓存文件路径，防止路径穿越。 */
    private Path safeCachePath(String serverName) {
        Path file = cacheDir.resolve(serverName + ".json").normalize();
        if (!file.startsWith(cacheDir)) {
            throw new IllegalArgumentException("非法 serverName: " + serverName);
        }
        return file;
    }

    /**
     * 读取指定 Server 的缓存工具清单。
     *
     * @param serverName 服务器名称
     * @return 缓存的工具 Schema 列表，无缓存或读取失败时返回空列表
     */
    public List<McpToolSchema> load(String serverName) {
        Path file = safeCachePath(serverName);
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            List<McpToolSchema> schemas = McpJsonSupport.MAPPER.readValue(bytes, SCHEMA_LIST_TYPE);
            log.debug("工具缓存加载成功: server={}, tools={}", serverName, schemas.size());
            return schemas;
        } catch (IOException e) {
            log.warn("工具缓存读取失败: server={}, error={}", serverName, e.getMessage());
            return List.of();
        }
    }

    /**
     * 写入指定 Server 的工具清单到缓存。
     *
     * @param serverName 服务器名称
     * @param schemas 工具 Schema 列表
     */
    public void save(String serverName, List<McpToolSchema> schemas) {
        try {
            Files.createDirectories(cacheDir);
            Path file = safeCachePath(serverName);
            McpJsonSupport.MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValue(file.toFile(), schemas);
            log.debug("工具缓存写入成功: server={}, tools={}", serverName, schemas.size());
        } catch (IOException e) {
            log.warn("工具缓存写入失败: server={}, error={}", serverName, e.getMessage());
        }
    }

    /**
     * 删除指定 Server 的缓存。
     *
     * @param serverName 服务器名称
     */
    public void evict(String serverName) {
        try {
            Path file = safeCachePath(serverName);
            Files.deleteIfExists(file);
            log.debug("工具缓存已删除: server={}", serverName);
        } catch (IOException e) {
            log.warn("工具缓存删除失败: server={}, error={}", serverName, e.getMessage());
        }
    }
}
