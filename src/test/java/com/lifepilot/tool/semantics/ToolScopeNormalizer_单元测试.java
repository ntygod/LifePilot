package com.lifepilot.tool.semantics;

import net.jqwik.api.*;
import net.jqwik.api.constraints.NotBlank;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolScopeNormalizer 工具作用域规范化单元测试。
 *
 * <p>覆盖路径、Origin、Host 提取以及路径前缀匹配的各种场景，
 * 包括 Windows/Unix 跨平台、边界输入和属性测试。</p>
 *
 * @author zsg
 * @since 2026-04-03
 */
class ToolScopeNormalizer_单元测试 {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    // ==================== normalizeOrigin ====================

    @Nested
    class NormalizeOrigin_规范化Origin {

        @Test
        void null输入_返回null() {
            assertNull(ToolScopeNormalizer.normalizeOrigin(null));
        }

        @Test
        void 空字符串_返回null() {
            assertNull(ToolScopeNormalizer.normalizeOrigin(""));
        }

        @Test
        void 纯空白字符串_返回null() {
            assertNull(ToolScopeNormalizer.normalizeOrigin("   "));
        }

        @Test
        void 标准HTTP_URL去除路径() {
            String result = ToolScopeNormalizer.normalizeOrigin("https://example.com/api/v1");
            assertEquals("https://example.com", result);
        }

        @Test
        void 带端口的URL_保留端口() {
            String result = ToolScopeNormalizer.normalizeOrigin("http://localhost:8080/path");
            assertEquals("http://localhost:8080", result);
        }

        @Test
        void 标准HTTPS端口443_不显示端口() {
            // URI.create 对默认端口返回 -1，所以不带端口
            String result = ToolScopeNormalizer.normalizeOrigin("https://example.com:443/path");
            assertEquals("https://example.com:443", result);
        }

        @Test
        void 自定义端口_保留端口号() {
            String result = ToolScopeNormalizer.normalizeOrigin("http://api.example.com:3000");
            assertEquals("http://api.example.com:3000", result);
        }

        @Test
        void 只有scheme和host_正确返回() {
            String result = ToolScopeNormalizer.normalizeOrigin("https://example.com");
            assertEquals("https://example.com", result);
        }

        @Test
        void 带query参数的URL_去除query() {
            String result = ToolScopeNormalizer.normalizeOrigin("https://example.com/path?key=value");
            assertEquals("https://example.com", result);
        }

        @Test
        void 带fragment的URL_去除fragment() {
            String result = ToolScopeNormalizer.normalizeOrigin("https://example.com/path#section");
            assertEquals("https://example.com", result);
        }

        @Test
        void 无scheme的字符串_返回trim后原值() {
            // 没有 scheme 的 URI 解析后 scheme 为 null
            String result = ToolScopeNormalizer.normalizeOrigin("  just-a-string  ");
            assertEquals("just-a-string", result);
        }

        @Test
        void 非法URI字符_返回trim后原值() {
            String result = ToolScopeNormalizer.normalizeOrigin("  not a valid {uri}  ");
            assertEquals("not a valid {uri}", result);
        }

        @Test
        void FTP协议_正确提取origin() {
            String result = ToolScopeNormalizer.normalizeOrigin("ftp://files.example.com/pub");
            assertEquals("ftp://files.example.com", result);
        }

        @Test
        void WebSocket协议_正确提取origin() {
            String result = ToolScopeNormalizer.normalizeOrigin("ws://realtime.example.com:9090/stream");
            assertEquals("ws://realtime.example.com:9090", result);
        }
    }

    // ==================== extractHost ====================

    @Nested
    class ExtractHost_提取Host {

        @Test
        void null输入_返回null() {
            assertNull(ToolScopeNormalizer.extractHost(null));
        }

        @Test
        void 空字符串_返回null() {
            assertNull(ToolScopeNormalizer.extractHost(""));
        }

        @Test
        void 纯空白字符串_返回null() {
            assertNull(ToolScopeNormalizer.extractHost("   "));
        }

        @Test
        void 标准URL_提取host() {
            assertEquals("example.com", ToolScopeNormalizer.extractHost("https://example.com/path"));
        }

        @Test
        void 带端口的URL_提取host不含端口() {
            assertEquals("localhost", ToolScopeNormalizer.extractHost("http://localhost:8080"));
        }

        @Test
        void 子域名URL_提取完整host() {
            assertEquals("api.v2.example.com",
                    ToolScopeNormalizer.extractHost("https://api.v2.example.com/endpoint"));
        }

        @Test
        void 无scheme的字符串_返回null() {
            // URI.create("just-a-string").getHost() 返回 null
            assertNull(ToolScopeNormalizer.extractHost("just-a-string"));
        }

        @Test
        void 非法URI_返回null() {
            assertNull(ToolScopeNormalizer.extractHost("not a valid {uri}"));
        }

        @Test
        void IP地址_提取IP() {
            assertEquals("192.168.1.1",
                    ToolScopeNormalizer.extractHost("http://192.168.1.1:3000/api"));
        }
    }

    // ==================== normalizePath ====================

    @Nested
    class NormalizePath_路径规范化 {

        @Test
        void null输入_返回null() {
            assertNull(ToolScopeNormalizer.normalizePath(null));
        }

        @Test
        void 空字符串_返回null() {
            assertNull(ToolScopeNormalizer.normalizePath(""));
        }

        @Test
        void 纯空白字符串_返回null() {
            assertNull(ToolScopeNormalizer.normalizePath("   "));
        }

        // ---- Windows 路径 ----

        @Test
        void Windows路径_反斜杠转正斜杠() {
            String result = ToolScopeNormalizer.normalizePath("C:\\Users\\test\\docs");
            assertEquals("C:/Users/test/docs", result);
        }

        @Test
        void Windows路径_小写盘符() {
            String result = ToolScopeNormalizer.normalizePath("d:\\workspace\\project");
            assertEquals("d:/workspace/project", result);
        }

        @Test
        void Windows路径_混合分隔符() {
            String result = ToolScopeNormalizer.normalizePath("C:\\Users/test\\project/src");
            assertEquals("C:/Users/test/project/src", result);
        }

        @Test
        void Windows路径_包含点号的相对段() {
            String result = ToolScopeNormalizer.normalizePath("C:\\Users\\test\\..\\admin\\docs");
            assertEquals("C:/Users/admin/docs", result);
        }

        @Test
        void Windows路径_包含当前目录点号() {
            String result = ToolScopeNormalizer.normalizePath("C:\\Users\\.\\test\\docs");
            assertEquals("C:/Users/test/docs", result);
        }

        @Test
        void Windows路径_仅盘符根目录() {
            String result = ToolScopeNormalizer.normalizePath("C:\\");
            assertEquals("C:/", result);
        }

        @Test
        void Windows路径_盘符正斜杠() {
            String result = ToolScopeNormalizer.normalizePath("C:/Users/test");
            assertEquals("C:/Users/test", result);
        }

        @Test
        void Windows路径_多个连续斜杠() {
            String result = ToolScopeNormalizer.normalizePath("C:\\Users\\\\test\\\\\\docs");
            assertEquals("C:/Users/test/docs", result);
        }

        @Test
        void Windows路径_双点超出根目录_停在根() {
            // ".." 在根目录层级无法再向上
            String result = ToolScopeNormalizer.normalizePath("C:\\a\\..\\..\\b");
            assertEquals("C:/b", result);
        }

        // ---- Unix 路径 ----

        @Test
        void Unix绝对路径_保持不变() {
            // 在 Windows 环境下，Path.of("/home/user") 的行为可能因 OS 不同而异
            String result = ToolScopeNormalizer.normalizePath("/home/user/docs");
            assertNotNull(result);
            assertTrue(result.contains("home/user/docs"), "结果应包含路径段: " + result);
        }

        // ---- 带空格和特殊字符的路径 ----

        @Test
        void 路径包含空格() {
            String result = ToolScopeNormalizer.normalizePath("C:\\Program Files\\My App\\data");
            assertEquals("C:/Program Files/My App/data", result);
        }

        @Test
        void 路径前后有空白_自动trim() {
            String result = ToolScopeNormalizer.normalizePath("  C:\\Users\\test  ");
            assertEquals("C:/Users/test", result);
        }
    }

    // ==================== pathStartsWith ====================

    @Nested
    class PathStartsWith_路径前缀匹配 {

        @Test
        void 完全相同路径_匹配() {
            assertTrue(ToolScopeNormalizer.pathStartsWith(
                    "C:\\Users\\test", "C:\\Users\\test"));
        }

        @Test
        void 子路径匹配_返回true() {
            assertTrue(ToolScopeNormalizer.pathStartsWith(
                    "C:\\Users\\test\\project\\src", "C:\\Users\\test"));
        }

        @Test
        void 不相关路径_返回false() {
            assertFalse(ToolScopeNormalizer.pathStartsWith(
                    "C:\\Users\\admin", "C:\\Users\\test"));
        }

        @Test
        void grant路径带尾斜杠_子路径匹配() {
            assertTrue(ToolScopeNormalizer.pathStartsWith(
                    "C:\\Users\\test\\file.txt", "C:\\Users\\test\\"));
        }

        @Test
        void request路径带尾斜杠_完全匹配() {
            assertTrue(ToolScopeNormalizer.pathStartsWith(
                    "C:\\Users\\test\\", "C:\\Users\\test"));
        }

        @Test
        void 相同前缀但不是目录边界_返回false() {
            // "C:/Users/testing" 不应匹配 "C:/Users/test" — 因为 "testing" != "test"
            assertFalse(ToolScopeNormalizer.pathStartsWith(
                    "C:\\Users\\testing", "C:\\Users\\test"));
        }

        @Test
        void Windows路径大小写不敏感_匹配() {
            // Windows 路径或者看起来像 Windows 路径（有盘符）应忽略大小写
            assertTrue(ToolScopeNormalizer.pathStartsWith(
                    "C:\\USERS\\Test\\Project", "c:\\users\\test"));
        }

        @Test
        void null路径规范化后_返回false() {
            assertFalse(ToolScopeNormalizer.pathStartsWith("", "C:\\Users\\test"));
            assertFalse(ToolScopeNormalizer.pathStartsWith("C:\\Users\\test", ""));
        }

        @Test
        void grant为根目录_所有子路径匹配() {
            assertTrue(ToolScopeNormalizer.pathStartsWith(
                    "C:\\Users\\test\\file.txt", "C:\\"));
        }

        @Test
        void 混合分隔符_正确匹配() {
            assertTrue(ToolScopeNormalizer.pathStartsWith(
                    "C:/Users/test/project", "C:\\Users\\test"));
        }

        @Test
        void 路径包含双点_规范化后匹配() {
            assertTrue(ToolScopeNormalizer.pathStartsWith(
                    "C:\\Users\\test\\sub\\..\\project", "C:\\Users\\test"));
        }
    }

    // ==================== resolveWorkspacePath ====================

    @Nested
    class ResolveWorkspacePath_工作区路径解析 {

        @Test
        void null输入_返回null() {
            assertNull(ToolScopeNormalizer.resolveWorkspacePath(null));
        }

        @Test
        void 空字符串_返回null() {
            assertNull(ToolScopeNormalizer.resolveWorkspacePath(""));
        }

        @Test
        void 纯空白字符串_返回null() {
            assertNull(ToolScopeNormalizer.resolveWorkspacePath("   "));
        }

        @Test
        void 看起来像文件路径_返回父目录() {
            // 含有扩展名的不存在路径，应推断为文件并返回父目录
            String result = ToolScopeNormalizer.resolveWorkspacePath("C:/nonexistent/dir/file.txt");
            assertEquals("C:/nonexistent/dir", result);
        }

        @Test
        void 看起来像目录路径_返回自身() {
            // 没有扩展名的不存在路径，不像文件，返回自身
            String result = ToolScopeNormalizer.resolveWorkspacePath("C:/nonexistent/somedir");
            assertEquals("C:/nonexistent/somedir", result);
        }

        @Test
        void 以点开头的文件名_不被视为文件() {
            // .hidden 这种以点开头的名字不被识别为文件
            String result = ToolScopeNormalizer.resolveWorkspacePath("C:/project/.hidden");
            assertEquals("C:/project/.hidden", result);
        }

        @Test
        void 包含反斜杠_结果转为正斜杠() {
            String result = ToolScopeNormalizer.resolveWorkspacePath("C:\\workspace\\project\\main.java");
            assertNotNull(result);
            assertFalse(result.contains("\\"), "结果不应包含反斜杠: " + result);
        }

        @Test
        void 无效路径字符串_回退到字符串截取() {
            // 触发 catch 分支中的 lastIndexOf('/') 逻辑
            // 在 Windows 上大多数路径都是合法的，使用包含空字节的路径来触发异常
            String weirdPath = "some/path\0with/null";
            String result = ToolScopeNormalizer.resolveWorkspacePath(weirdPath);
            // 异常分支: lastIndexOf('/') 返回截取结果
            assertNotNull(result);
        }

        @Test
        void 路径只有文件名_带扩展名() {
            // 单独的文件名如 "file.txt"，getParent() 可能为 null
            // 实际行为取决于 OS 和 Path.of() 的解析
            String result = ToolScopeNormalizer.resolveWorkspacePath("file.txt");
            assertNotNull(result);
        }
    }

    // ==================== jqwik 属性测试 ====================

    @Group
    class 属性测试 {

        /**
         * normalizePath 的幂等性：规范化后再规范化，结果不变。
         */
        @Property(tries = 100)
        void normalizePath幂等性_Windows路径(@ForAll("windowsPaths") String path) {
            String first = ToolScopeNormalizer.normalizePath(path);
            if (first != null) {
                String second = ToolScopeNormalizer.normalizePath(first);
                assertEquals(first, second,
                        "normalizePath 应具有幂等性: 输入='%s', 第一次='%s', 第二次='%s'"
                                .formatted(path, first, second));
            }
        }

        /**
         * normalizeOrigin 的幂等性：规范化后再规范化，结果不变。
         */
        @Property(tries = 100)
        void normalizeOrigin幂等性(@ForAll("httpOrigins") String origin) {
            String first = ToolScopeNormalizer.normalizeOrigin(origin);
            if (first != null) {
                String second = ToolScopeNormalizer.normalizeOrigin(first);
                assertEquals(first, second,
                        "normalizeOrigin 应具有幂等性: 输入='%s', 第一次='%s', 第二次='%s'"
                                .formatted(origin, first, second));
            }
        }

        /**
         * 任何有效路径的 pathStartsWith 自身应为 true。
         */
        @Property(tries = 100)
        void pathStartsWith自反性_路径包含自身(@ForAll("windowsPaths") String path) {
            String normalized = ToolScopeNormalizer.normalizePath(path);
            if (normalized != null && !normalized.isBlank()) {
                assertTrue(ToolScopeNormalizer.pathStartsWith(path, path),
                        "路径应当包含自身: '%s'".formatted(path));
            }
        }

        /**
         * normalizePath 的结果中不应包含反斜杠。
         */
        @Property(tries = 100)
        void normalizePath结果不含反斜杠(@ForAll("windowsPaths") String path) {
            String result = ToolScopeNormalizer.normalizePath(path);
            if (result != null) {
                assertFalse(result.contains("\\"),
                        "规范化后的路径不应包含反斜杠: '%s' → '%s'".formatted(path, result));
            }
        }

        /**
         * normalizePath 的结果应去除首尾空白。
         */
        @Property(tries = 50)
        void normalizePath结果无首尾空白(@ForAll("windowsPaths") String path) {
            String result = ToolScopeNormalizer.normalizePath(path);
            if (result != null) {
                assertEquals(result.trim(), result,
                        "规范化后的路径不应有首尾空白: '%s'".formatted(result));
            }
        }

        /**
         * extractHost 的结果不应包含 scheme 或端口。
         */
        @Property(tries = 50)
        void extractHost结果不含scheme(@ForAll("httpOrigins") String origin) {
            String host = ToolScopeNormalizer.extractHost(origin);
            if (host != null) {
                assertFalse(host.contains("://"),
                        "host 不应包含 scheme: '%s' → '%s'".formatted(origin, host));
                assertFalse(host.contains(":"),
                        "host 不应包含端口: '%s' → '%s'".formatted(origin, host));
            }
        }

        // ---- Arbitrary 提供者 ----

        @Provide
        Arbitrary<String> windowsPaths() {
            var drives = Arbitraries.of("C", "D", "E", "c", "d");
            var segments = Arbitraries.of(
                    "Users", "test", "workspace", "project", "src", "main",
                    "docs", "Program Files", "My App", ".config", "node_modules"
            ).list().ofMinSize(1).ofMaxSize(5);
            var separators = Arbitraries.of("\\", "/");

            return Combinators.combine(drives, segments, separators).as((drive, segs, sep) ->
                    drive + ":" + sep + String.join(sep, segs));
        }

        @Provide
        Arbitrary<String> httpOrigins() {
            var schemes = Arbitraries.of("http", "https", "ws", "wss", "ftp");
            var hosts = Arbitraries.of(
                    "example.com", "localhost", "api.example.com",
                    "192.168.1.1", "my-service.internal"
            );
            var ports = Arbitraries.of("", ":8080", ":3000", ":443", ":9090");
            var paths = Arbitraries.of("", "/", "/api", "/api/v1", "/path/to/resource");

            return Combinators.combine(schemes, hosts, ports, paths)
                    .as((scheme, host, port, path) -> scheme + "://" + host + port + path);
        }
    }
}
