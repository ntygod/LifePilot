package com.lifepilot.mcp.discovery;

import com.lifepilot.mcp.config.McpConfigProperties;
import com.lifepilot.mcp.config.McpServerConfig;
import com.lifepilot.mcp.transport.TransportType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * McpServerDiscovery 单元测试。
 *
 * <p>通过 {@code @TempDir} 创建临时目录模拟文件系统，
 * 通过子类覆写 {@code getUserMcpDir()} 控制发现路径。</p>
 *
 * @author zsg
 * @since 2026-04-03
 */
class McpServerDiscovery_单元测试 {

    @TempDir
    Path tempDir;

    /** 模拟 ~/.zhiwei/mcp/ 目录。 */
    private Path zhiweiMcpDir;

    /** 模拟 ~/.mcp/ 目录。 */
    private Path userMcpDir;

    /** 模拟项目根目录。 */
    private Path projectRoot;

    /** 自定义路径目录。 */
    private Path customDir;

    private McpConfigProperties properties;

    @BeforeEach
    void 初始化() throws IOException {
        zhiweiMcpDir = tempDir.resolve(".zhiwei").resolve("mcp");
        userMcpDir = tempDir.resolve(".mcp");
        projectRoot = tempDir.resolve("project");
        customDir = tempDir.resolve("custom");

        Files.createDirectories(zhiweiMcpDir);
        Files.createDirectories(userMcpDir);
        Files.createDirectories(projectRoot);
        Files.createDirectories(customDir);

        properties = new McpConfigProperties();
        // 禁用 seedBuiltinServers 避免测试 discover() 时触发 classpath 读取
        properties.getDiscovery().setSeedBuiltinServers(false);
    }

    /**
     * 构建被测对象 — 覆写 getUserMcpDir() 返回临时目录，
     * 并劫持 buildDiscoveryPaths 使用的 system properties。
     */
    private McpServerDiscovery 创建被测对象() {
        return new McpServerDiscovery(properties) {
            @Override
            Path getUserMcpDir() {
                return zhiweiMcpDir;
            }
        };
    }

    /**
     * 在 discover() 期间临时设置 user.home 和 user.dir，用于控制 buildDiscoveryPaths。
     */
    private List<McpServerConfig> 以临时系统属性执行发现(McpServerDiscovery discovery) {
        var originalHome = System.getProperty("user.home");
        var originalDir = System.getProperty("user.dir");
        try {
            System.setProperty("user.home", tempDir.toString());
            System.setProperty("user.dir", projectRoot.toString());
            return discovery.discover();
        } finally {
            System.setProperty("user.home", originalHome);
            System.setProperty("user.dir", originalDir);
        }
    }

    // ─────────────────────────────────────────────
    //  发现开关
    // ─────────────────────────────────────────────

    @Nested
    class 发现开关 {

        @Test
        void 发现禁用时_仅返回显式配置() {
            properties.getDiscovery().setEnabled(false);

            var entry = new McpConfigProperties.ServerEntry();
            entry.setName("explicit-server");
            entry.setTransport(TransportType.STDIO);
            entry.setCommand("npx");
            properties.setServers(List.of(entry));

            var discovery = 创建被测对象();
            var result = discovery.discover();

            assertEquals(1, result.size());
            assertEquals("explicit-server", result.getFirst().name());
        }

        @Test
        void 发现禁用且无显式配置时_返回空列表() {
            properties.getDiscovery().setEnabled(false);

            var discovery = 创建被测对象();
            var result = discovery.discover();

            assertTrue(result.isEmpty());
        }
    }

    // ─────────────────────────────────────────────
    //  配置文件解析
    // ─────────────────────────────────────────────

    @Nested
    class 配置文件解析 {

        @Test
        void 解析stdio类型服务器配置() throws IOException {
            var json = """
                    {
                      "mcpServers": {
                        "filesystem": {
                          "command": "npx",
                          "args": ["-y", "@modelcontextprotocol/server-filesystem"],
                          "env": {"HOME": "/tmp"},
                          "autoConnect": false
                        }
                      }
                    }
                    """;
            Files.writeString(zhiweiMcpDir.resolve("servers.json"), json);

            var discovery = 创建被测对象();
            var result = 以临时系统属性执行发现(discovery);

            assertEquals(1, result.size());
            var config = result.getFirst();
            assertEquals("filesystem", config.name());
            assertEquals(TransportType.STDIO, config.transport());
            assertEquals("npx", config.command());
            assertEquals(List.of("-y", "@modelcontextprotocol/server-filesystem"), config.args());
            assertEquals("/tmp", config.env().get("HOME"));
            assertFalse(config.autoConnect());
        }

        @Test
        void 解析远程传输服务器_url指定时推断为STREAMABLE_HTTP() throws IOException {
            var json = """
                    {
                      "mcpServers": {
                        "remote-api": {
                          "url": "http://localhost:3001/mcp"
                        }
                      }
                    }
                    """;
            Files.writeString(zhiweiMcpDir.resolve("servers.json"), json);

            var discovery = 创建被测对象();
            var result = 以临时系统属性执行发现(discovery);

            assertEquals(1, result.size());
            var config = result.getFirst();
            assertEquals("remote-api", config.name());
            assertEquals(TransportType.STREAMABLE_HTTP, config.transport());
            assertEquals("http://localhost:3001/mcp", config.url());
        }

        @Test
        void 显式指定transport字段() throws IOException {
            var json = """
                    {
                      "mcpServers": {
                        "legacy-sse": {
                          "transport": "sse-legacy",
                          "url": "http://localhost:8080/sse"
                        }
                      }
                    }
                    """;
            Files.writeString(zhiweiMcpDir.resolve("servers.json"), json);

            var discovery = 创建被测对象();
            var result = 以临时系统属性执行发现(discovery);

            assertEquals(1, result.size());
            assertEquals(TransportType.SSE_LEGACY, result.getFirst().transport());
        }

        @Test
        void 无command时默认推断为STREAMABLE_HTTP() throws IOException {
            var json = """
                    {
                      "mcpServers": {
                        "inferred-http": {
                          "url": "https://example.com/mcp"
                        }
                      }
                    }
                    """;
            Files.writeString(zhiweiMcpDir.resolve("servers.json"), json);

            var discovery = 创建被测对象();
            var result = 以临时系统属性执行发现(discovery);

            assertEquals(1, result.size());
            assertEquals(TransportType.STREAMABLE_HTTP, result.getFirst().transport());
        }

        @Test
        void 有command时默认推断为STDIO() throws IOException {
            var json = """
                    {
                      "mcpServers": {
                        "inferred-stdio": {
                          "command": "node",
                          "args": ["server.js"]
                        }
                      }
                    }
                    """;
            Files.writeString(zhiweiMcpDir.resolve("servers.json"), json);

            var discovery = 创建被测对象();
            var result = 以临时系统属性执行发现(discovery);

            assertEquals(1, result.size());
            assertEquals(TransportType.STDIO, result.getFirst().transport());
            assertEquals("node", result.getFirst().command());
        }

        @Test
        void autoConnect默认为true() throws IOException {
            var json = """
                    {
                      "mcpServers": {
                        "default-auto": {
                          "command": "npx",
                          "args": ["-y", "some-server"]
                        }
                      }
                    }
                    """;
            Files.writeString(zhiweiMcpDir.resolve("servers.json"), json);

            var discovery = 创建被测对象();
            var result = 以临时系统属性执行发现(discovery);

            assertTrue(result.getFirst().autoConnect());
        }

        @Test
        void 解析多个服务器() throws IOException {
            var json = """
                    {
                      "mcpServers": {
                        "server-a": {
                          "command": "npx",
                          "args": ["-y", "server-a"]
                        },
                        "server-b": {
                          "url": "http://localhost:3002/mcp"
                        }
                      }
                    }
                    """;
            Files.writeString(zhiweiMcpDir.resolve("servers.json"), json);

            var discovery = 创建被测对象();
            var result = 以临时系统属性执行发现(discovery);

            assertEquals(2, result.size());
            var names = result.stream().map(McpServerConfig::name).toList();
            assertTrue(names.contains("server-a"));
            assertTrue(names.contains("server-b"));
        }
    }

    // ─────────────────────────────────────────────
    //  错误容忍
    // ─────────────────────────────────────────────

    @Nested
    class 错误容忍 {

        @Test
        void 配置文件不存在时_返回空列表() {
            // zhiweiMcpDir/servers.json 不存在
            var discovery = 创建被测对象();
            var result = 以临时系统属性执行发现(discovery);

            assertTrue(result.isEmpty());
        }

        @Test
        void JSON格式错误时_跳过该文件不抛异常() throws IOException {
            Files.writeString(zhiweiMcpDir.resolve("servers.json"), "{ invalid json !!!");

            var discovery = 创建被测对象();
            var result = 以临时系统属性执行发现(discovery);

            assertTrue(result.isEmpty());
        }

        @Test
        void 无mcpServers节点时_返回空列表() throws IOException {
            Files.writeString(zhiweiMcpDir.resolve("servers.json"), """
                    { "otherKey": "value" }
                    """);

            var discovery = 创建被测对象();
            var result = 以临时系统属性执行发现(discovery);

            assertTrue(result.isEmpty());
        }

        @Test
        void mcpServers为空对象时_返回空列表() throws IOException {
            Files.writeString(zhiweiMcpDir.resolve("servers.json"), """
                    { "mcpServers": {} }
                    """);

            var discovery = 创建被测对象();
            var result = 以临时系统属性执行发现(discovery);

            assertTrue(result.isEmpty());
        }

        @Test
        void 单个server条目解析失败时_跳过该条目继续解析() throws IOException {
            // stdio 传输但没有 command 会触发 McpServerConfig 构造异常
            var json = """
                    {
                      "mcpServers": {
                        "bad-server": {
                          "transport": "stdio"
                        },
                        "good-server": {
                          "command": "npx",
                          "args": ["-y", "good-pkg"]
                        }
                      }
                    }
                    """;
            Files.writeString(zhiweiMcpDir.resolve("servers.json"), json);

            var discovery = 创建被测对象();
            var result = 以临时系统属性执行发现(discovery);

            assertEquals(1, result.size());
            assertEquals("good-server", result.getFirst().name());
        }

        @Test
        void 配置路径指向目录而非文件时_跳过() throws IOException {
            // zhiweiMcpDir/servers.json 是目录而非文件
            Files.createDirectories(zhiweiMcpDir.resolve("servers.json"));

            var discovery = 创建被测对象();
            var result = 以临时系统属性执行发现(discovery);

            assertTrue(result.isEmpty());
        }
    }

    // ─────────────────────────────────────────────
    //  多源发现与优先级
    // ─────────────────────────────────────────────

    @Nested
    class 多源发现与优先级 {

        @Test
        void 知微目录优先于用户全局mcp配置() throws IOException {
            // ~/.zhiwei/mcp/servers.json 中有 my-server (command=npx)
            Files.writeString(zhiweiMcpDir.resolve("servers.json"), """
                    {
                      "mcpServers": {
                        "my-server": {
                          "command": "npx",
                          "args": ["first-version"]
                        }
                      }
                    }
                    """);

            // ~/.mcp/servers.json 中有同名 my-server (command=node)
            Files.writeString(userMcpDir.resolve("servers.json"), """
                    {
                      "mcpServers": {
                        "my-server": {
                          "command": "node",
                          "args": ["second-version"]
                        }
                      }
                    }
                    """);

            var discovery = 创建被测对象();
            var result = 以临时系统属性执行发现(discovery);

            // 应使用知微目录的配置
            assertEquals(1, result.size());
            assertEquals("npx", result.getFirst().command());
            assertEquals(List.of("first-version"), result.getFirst().args());
        }

        @Test
        void 显式配置优先于所有发现源() throws IOException {
            // 显式配置
            var entry = new McpConfigProperties.ServerEntry();
            entry.setName("explicit-srv");
            entry.setTransport(TransportType.STDIO);
            entry.setCommand("explicit-cmd");
            properties.setServers(List.of(entry));

            // 发现源中有同名
            Files.writeString(zhiweiMcpDir.resolve("servers.json"), """
                    {
                      "mcpServers": {
                        "explicit-srv": {
                          "command": "discovered-cmd"
                        }
                      }
                    }
                    """);

            var discovery = 创建被测对象();
            var result = 以临时系统属性执行发现(discovery);

            // 只保留显式配置的版本
            assertEquals(1, result.size());
            assertEquals("explicit-cmd", result.getFirst().command());
        }

        @Test
        void 显式配置与发现配置合并_不同名称均保留() throws IOException {
            // 显式配置
            var entry = new McpConfigProperties.ServerEntry();
            entry.setName("explicit-only");
            entry.setTransport(TransportType.STDIO);
            entry.setCommand("cmd1");
            properties.setServers(List.of(entry));

            // 发现源有不同名称的服务器
            Files.writeString(zhiweiMcpDir.resolve("servers.json"), """
                    {
                      "mcpServers": {
                        "discovered-only": {
                          "command": "cmd2"
                        }
                      }
                    }
                    """);

            var discovery = 创建被测对象();
            var result = 以临时系统属性执行发现(discovery);

            assertEquals(2, result.size());
            var names = result.stream().map(McpServerConfig::name).toList();
            assertTrue(names.contains("explicit-only"));
            assertTrue(names.contains("discovered-only"));
        }

        @Test
        void 合并结果中显式配置排在前面() throws IOException {
            var entry = new McpConfigProperties.ServerEntry();
            entry.setName("aaa-explicit");
            entry.setTransport(TransportType.STDIO);
            entry.setCommand("cmd");
            properties.setServers(List.of(entry));

            Files.writeString(zhiweiMcpDir.resolve("servers.json"), """
                    {
                      "mcpServers": {
                        "zzz-discovered": {
                          "command": "cmd2"
                        }
                      }
                    }
                    """);

            var discovery = 创建被测对象();
            var result = 以临时系统属性执行发现(discovery);

            assertEquals(2, result.size());
            assertEquals("aaa-explicit", result.get(0).name());
            assertEquals("zzz-discovered", result.get(1).name());
        }

        @Test
        void 从多个不同源发现不同服务器_全部收集() throws IOException {
            Files.writeString(zhiweiMcpDir.resolve("servers.json"), """
                    {
                      "mcpServers": {
                        "zhiwei-srv": { "command": "npx", "args": ["a"] }
                      }
                    }
                    """);

            Files.writeString(userMcpDir.resolve("servers.json"), """
                    {
                      "mcpServers": {
                        "user-srv": { "command": "node", "args": ["b"] }
                      }
                    }
                    """);

            Files.writeString(projectRoot.resolve(".mcp.json"), """
                    {
                      "mcpServers": {
                        "project-srv": { "command": "python", "args": ["c.py"] }
                      }
                    }
                    """);

            var discovery = 创建被测对象();
            var result = 以临时系统属性执行发现(discovery);

            assertEquals(3, result.size());
            var names = result.stream().map(McpServerConfig::name).toList();
            assertTrue(names.contains("zhiwei-srv"));
            assertTrue(names.contains("user-srv"));
            assertTrue(names.contains("project-srv"));
        }

        @Test
        void 自定义路径也被扫描() throws IOException {
            var customFile = customDir.resolve("extra-servers.json");
            Files.writeString(customFile, """
                    {
                      "mcpServers": {
                        "custom-srv": { "command": "custom-cmd" }
                      }
                    }
                    """);
            properties.getDiscovery().setPaths(List.of(customFile.toString()));

            var discovery = 创建被测对象();
            var result = 以临时系统属性执行发现(discovery);

            var names = result.stream().map(McpServerConfig::name).toList();
            assertTrue(names.contains("custom-srv"));
        }
    }

    // ─────────────────────────────────────────────
    //  项目本地配置 (.mcp.json)
    // ─────────────────────────────────────────────

    @Nested
    class 项目本地配置 {

        @Test
        void 从项目根目录发现mcp_json() throws IOException {
            Files.writeString(projectRoot.resolve(".mcp.json"), """
                    {
                      "mcpServers": {
                        "project-tool": {
                          "command": "node",
                          "args": ["tool.js"],
                          "env": { "DEBUG": "true" }
                        }
                      }
                    }
                    """);

            var discovery = 创建被测对象();
            var result = 以临时系统属性执行发现(discovery);

            assertEquals(1, result.size());
            var config = result.getFirst();
            assertEquals("project-tool", config.name());
            assertEquals("node", config.command());
            assertEquals("true", config.env().get("DEBUG"));
        }
    }

    // ─────────────────────────────────────────────
    //  内置服务器种子
    // ─────────────────────────────────────────────

    @Nested
    class 内置服务器种子 {

        @Test
        void 释放内置配置到用户目录() {
            var discovery = 创建被测对象();
            discovery.seedBuiltinServers();

            var serversFile = zhiweiMcpDir.resolve("servers.json");
            assertTrue(Files.exists(serversFile), "内置配置应被释放到用户目录");
        }

        @Test
        void 释放内置配置包含预期服务器() throws IOException {
            var discovery = 创建被测对象();
            discovery.seedBuiltinServers();

            var content = Files.readString(zhiweiMcpDir.resolve("servers.json"));
            // classpath 内置配置包含 mcp-installer 和 desktop-control
            assertTrue(content.contains("mcp-installer"), "应包含 mcp-installer");
            assertTrue(content.contains("desktop-control"), "应包含 desktop-control");
        }

        @Test
        void 用户已有配置时_仅补充新增服务器不覆盖() throws IOException {
            // 用户已有配置，mcp-installer 已被自定义
            Files.writeString(zhiweiMcpDir.resolve("servers.json"), """
                    {
                      "mcpServers": {
                        "mcp-installer": {
                          "command": "custom-npx",
                          "args": ["my-custom-installer"]
                        }
                      }
                    }
                    """);

            var discovery = 创建被测对象();
            discovery.seedBuiltinServers();

            var content = Files.readString(zhiweiMcpDir.resolve("servers.json"));
            // mcp-installer 保持用户自定义值
            assertTrue(content.contains("custom-npx"), "用户自定义的 mcp-installer 不应被覆盖");
            assertTrue(content.contains("my-custom-installer"), "用户自定义的 args 不应被覆盖");
            // desktop-control 应被补充添加
            assertTrue(content.contains("desktop-control"), "新增的 desktop-control 应被补充");
        }

        @Test
        void 用户已有全部内置服务器时_不写入文件() throws IOException {
            // 预先写入所有内置服务器
            Files.writeString(zhiweiMcpDir.resolve("servers.json"), """
                    {
                      "mcpServers": {
                        "mcp-installer": { "command": "npx", "args": ["-y", "custom"] },
                        "desktop-control": { "command": "npx", "args": ["-y", "custom2"] }
                      }
                    }
                    """);
            var modifiedBefore = Files.getLastModifiedTime(zhiweiMcpDir.resolve("servers.json"));

            var discovery = 创建被测对象();
            discovery.seedBuiltinServers();

            var modifiedAfter = Files.getLastModifiedTime(zhiweiMcpDir.resolve("servers.json"));
            // added == 0，不应写入文件，修改时间不变
            assertEquals(modifiedBefore, modifiedAfter, "已全部包含时不应写入文件");
        }

        @Test
        void 用户目录不存在时_自动创建() throws IOException {
            // 删除 zhiweiMcpDir 使其不存在
            Files.delete(zhiweiMcpDir);

            var freshMcpDir = tempDir.resolve("fresh-zhiwei-mcp");
            var discovery = new McpServerDiscovery(properties) {
                @Override
                Path getUserMcpDir() {
                    return freshMcpDir;
                }
            };
            discovery.seedBuiltinServers();

            assertTrue(Files.exists(freshMcpDir.resolve("servers.json")),
                    "应自动创建目录并释放配置文件");
        }

        @Test
        void 发现时如果seedBuiltinServers为true则先释放再扫描() throws IOException {
            properties.getDiscovery().setSeedBuiltinServers(true);

            var discovery = 创建被测对象();
            var result = 以临时系统属性执行发现(discovery);

            // 释放后应能发现内置服务器
            assertTrue(Files.exists(zhiweiMcpDir.resolve("servers.json")),
                    "内置配置应被释放");
            var names = result.stream().map(McpServerConfig::name).toList();
            assertTrue(names.contains("mcp-installer"), "应发现内置的 mcp-installer");
        }
    }

    // ─────────────────────────────────────────────
    //  返回值不可变性
    // ─────────────────────────────────────────────

    @Nested
    class 返回值不可变性 {

        @Test
        void discover返回不可变列表() throws IOException {
            Files.writeString(zhiweiMcpDir.resolve("servers.json"), """
                    {
                      "mcpServers": {
                        "test-srv": { "command": "npx", "args": ["test"] }
                      }
                    }
                    """);

            var discovery = 创建被测对象();
            var result = 以临时系统属性执行发现(discovery);

            assertThrows(UnsupportedOperationException.class, () ->
                    result.add(McpServerConfig.builder()
                            .name("hack")
                            .transport(TransportType.STDIO)
                            .command("hack")
                            .build()));
        }
    }

    // ─────────────────────────────────────────────
    //  env 与 args 边界
    // ─────────────────────────────────────────────

    @Nested
    class 字段边界 {

        @Test
        void 未指定args时默认为空列表() throws IOException {
            Files.writeString(zhiweiMcpDir.resolve("servers.json"), """
                    {
                      "mcpServers": {
                        "no-args": { "command": "simple-cmd" }
                      }
                    }
                    """);

            var discovery = 创建被测对象();
            var result = 以临时系统属性执行发现(discovery);

            assertEquals(1, result.size());
            assertNotNull(result.getFirst().args());
            assertTrue(result.getFirst().args().isEmpty());
        }

        @Test
        void 未指定env时默认为空Map() throws IOException {
            Files.writeString(zhiweiMcpDir.resolve("servers.json"), """
                    {
                      "mcpServers": {
                        "no-env": { "command": "simple-cmd" }
                      }
                    }
                    """);

            var discovery = 创建被测对象();
            var result = 以临时系统属性执行发现(discovery);

            assertEquals(1, result.size());
            assertNotNull(result.getFirst().env());
            assertTrue(result.getFirst().env().isEmpty());
        }

        @Test
        void env值为数字类型时转换为字符串() throws IOException {
            Files.writeString(zhiweiMcpDir.resolve("servers.json"), """
                    {
                      "mcpServers": {
                        "numeric-env": {
                          "command": "cmd",
                          "env": { "PORT": 3000, "VERBOSE": true }
                        }
                      }
                    }
                    """);

            var discovery = 创建被测对象();
            var result = 以临时系统属性执行发现(discovery);

            assertEquals(1, result.size());
            assertEquals("3000", result.getFirst().env().get("PORT"));
            assertEquals("true", result.getFirst().env().get("VERBOSE"));
        }

        @Test
        void args中的数字元素转换为字符串() throws IOException {
            Files.writeString(zhiweiMcpDir.resolve("servers.json"), """
                    {
                      "mcpServers": {
                        "numeric-args": {
                          "command": "cmd",
                          "args": ["--port", 8080]
                        }
                      }
                    }
                    """);

            var discovery = 创建被测对象();
            var result = 以临时系统属性执行发现(discovery);

            assertEquals(1, result.size());
            assertEquals(List.of("--port", "8080"), result.getFirst().args());
        }
    }
}
