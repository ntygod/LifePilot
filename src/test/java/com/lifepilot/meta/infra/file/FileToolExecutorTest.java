package com.lifepilot.meta.infra.file;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件系统工具单元测试 — 含路径安全拒绝、原子写入、大文件截断场景。
 *
 * @author zsg
 * @since 2026-03-08
 */
class FileToolExecutorTest {

    @TempDir
    Path tempDir;

    private MetaProperties properties;

    @BeforeEach
    void setUp() {
        properties = new MetaProperties();
        // 将 tempDir 加入白名单，使测试路径可访问
        properties.getInfra().getFile().setAllowedDirectories(
                List.of(tempDir.toAbsolutePath().toString()));
    }

    // ─────────────────────────────────────────────
    //  PathSecurityChecker 测试
    // ─────────────────────────────────────────────

    @Nested
    class PathSecurityCheckerTest {

        @Test
        void check_白名单内路径通过() {
            var checker = new PathSecurityChecker(properties.getInfra().getFile());
            var result = checker.check(tempDir.resolve("test.txt"));
            assertThat(result).isEmpty();
        }

        @Test
        void check_白名单外路径被拒绝() {
            var checker = new PathSecurityChecker(properties.getInfra().getFile());
            var result = checker.check(Path.of("/some/other/path"));
            assertThat(result).isPresent();
            assertThat(result.get()).contains("不在允许的目录列表中");
        }

        @Test
        void check_黑名单路径被拒绝() {
            // 配置白名单为空，仅依赖黑名单
            properties.getInfra().getFile().setAllowedDirectories(List.of());
            properties.getInfra().getFile().setDeniedDirectories(
                    List.of(tempDir.toAbsolutePath().toString()));
            var checker = new PathSecurityChecker(properties.getInfra().getFile());

            var result = checker.check(tempDir.resolve("test.txt"));
            assertThat(result).isPresent();
            assertThat(result.get()).contains("黑名单");
        }

        @Test
        void checkForWrite_不存在文件路径通过安全检查() {
            var checker = new PathSecurityChecker(properties.getInfra().getFile());
            var result = checker.checkForWrite(tempDir.resolve("new-file.txt"));
            assertThat(result).isEmpty();
        }

        @Test
        void check_白名单为空时_非黑名单路径通过() {
            properties.getInfra().getFile().setAllowedDirectories(List.of());
            properties.getInfra().getFile().setDeniedDirectories(List.of());
            var checker = new PathSecurityChecker(properties.getInfra().getFile());

            Path arbitraryPath = tempDir.resolveSibling("outside-whitelist.txt");
            var result = checker.check(arbitraryPath);
            assertThat(result).isEmpty();
        }
    }

    // ─────────────────────────────────────────────
    //  FileReadToolExecutor 测试
    // ─────────────────────────────────────────────

    @Nested
    class FileReadTest {

        private FileReadToolExecutor executor;

        @BeforeEach
        void setUp() {
            executor = new FileReadToolExecutor(properties);
        }

        @Test
        void execute_读取普通文件成功() throws IOException {
            Path file = tempDir.resolve("hello.txt");
            Files.writeString(file, "Hello, World!");

            ToolResult result = executor.execute(buildInput(Map.of("path", file.toString())));

            assertThat(result.ok()).isTrue();
            assertThat((String) result.data().get("content")).isEqualTo("Hello, World!");
            assertThat((long) result.data().get("size")).isEqualTo(13L);
            assertThat((boolean) result.data().get("truncated")).isFalse();
        }

        @Test
        void execute_大文件被截断() throws IOException {
            // 设置极小的 maxReadSize
            properties.getInfra().getFile().setDefaultMaxChars(10);
            executor = new FileReadToolExecutor(properties);

            Path file = tempDir.resolve("large.txt");
            Files.writeString(file, "a".repeat(100));

            ToolResult result = executor.execute(buildInput(Map.of("path", file.toString())));

            assertThat(result.ok()).isTrue();
            assertThat((boolean) result.data().get("truncated")).isTrue();
            String content = (String) result.data().get("content");
            assertThat(content).contains("文件已截断");
        }

        @Test
        void execute_文件不存在返回错误() {
            ToolResult result = executor.execute(buildInput(Map.of(
                    "path", tempDir.resolve("nonexistent.txt").toString())));

            assertThat(result.ok()).isFalse();
            assertThat(result.error()).contains("文件不存在");
        }

        @Test
        void execute_按行范围读取并保留总行数() throws IOException {
            Path file = tempDir.resolve("range.txt");
            Files.writeString(file, "line1\nline2\nline3\nline4");

            ToolResult result = executor.execute(buildInput(Map.of(
                    "path", file.toString(),
                    "startLine", 2,
                    "endLine", 3)));

            assertThat(result.ok()).isTrue();
            assertThat(result.data().get("content")).isEqualTo("line2\nline3");
            assertThat(result.data().get("totalLines")).isEqualTo(4);
            assertThat(result.data().get("startLine")).isEqualTo(2);
            assertThat(result.data().get("endLine")).isEqualTo(3);
        }

        @Test
        void execute_路径安全拒绝() {
            ToolResult result = executor.execute(buildInput(Map.of("path", "/etc/passwd")));

            assertThat(result.ok()).isFalse();
            assertThat(result.error()).contains("安全策略拒绝");
        }

        @Test
        void execute_缺少path参数返回错误() {
            ToolResult result = executor.execute(buildInput(Map.of()));

            assertThat(result.ok()).isFalse();
            assertThat(result.error()).contains("path");
        }

        @Test
        void execute_目录路径返回错误() {
            ToolResult result = executor.execute(buildInput(Map.of("path", tempDir.toString())));

            assertThat(result.ok()).isFalse();
            assertThat(result.error()).contains("不是普通文件");
        }
    }

    // ─────────────────────────────────────────────
    //  FileWriteToolExecutor 测试
    // ─────────────────────────────────────────────

    @Nested
    class FileWriteTest {

        private FileWriteToolExecutor executor;

        @BeforeEach
        void setUp() {
            executor = new FileWriteToolExecutor(properties);
        }

        @Test
        void execute_原子写入文件成功() {
            Path file = tempDir.resolve("output.txt");

            ToolResult result = executor.execute(buildInput(Map.of(
                    "path", file.toString(),
                    "content", "Hello, File!")));

            assertThat(result.ok()).isTrue();
            assertThat((int) result.data().get("bytesWritten")).isEqualTo(12);
            assertThat(Files.exists(file)).isTrue();
        }

        @Test
        void execute_原子写入内容正确() throws IOException {
            Path file = tempDir.resolve("verify.txt");

            executor.execute(buildInput(Map.of(
                    "path", file.toString(),
                    "content", "验证内容")));

            String content = Files.readString(file, StandardCharsets.UTF_8);
            assertThat(content).isEqualTo("验证内容");
        }

        @Test
        void execute_自动创建父目录() {
            Path file = tempDir.resolve("sub/dir/file.txt");

            ToolResult result = executor.execute(buildInput(Map.of(
                    "path", file.toString(),
                    "content", "nested")));

            assertThat(result.ok()).isTrue();
            assertThat(Files.exists(file)).isTrue();
        }

        @Test
        void execute_禁止创建父目录时返回错误() {
            Path file = tempDir.resolve("no-create/file.txt");

            ToolResult result = executor.execute(buildInput(Map.of(
                    "path", file.toString(),
                    "content", "test",
                    "createDirectories", false)));

            assertThat(result.ok()).isFalse();
            assertThat(result.error()).contains("父目录不存在");
        }

        @Test
        void execute_路径安全拒绝() {
            ToolResult result = executor.execute(buildInput(Map.of(
                    "path", "/etc/test.txt",
                    "content", "hack")));

            assertThat(result.ok()).isFalse();
            assertThat(result.error()).contains("安全策略拒绝");
        }

        @Test
        void execute_覆盖已有文件() throws IOException {
            Path file = tempDir.resolve("overwrite.txt");
            Files.writeString(file, "old content");

            ToolResult result = executor.execute(buildInput(Map.of(
                    "path", file.toString(),
                    "content", "new content")));

            assertThat(result.ok()).isTrue();
            assertThat(Files.readString(file)).isEqualTo("new content");
        }
    }

    // ─────────────────────────────────────────────
    //  FileListToolExecutor 测试
    // ─────────────────────────────────────────────

    @Nested
    class FileListTest {

        private FileListToolExecutor executor;

        @BeforeEach
        void setUp() {
            executor = new FileListToolExecutor(properties);
        }

        @Test
        @SuppressWarnings("unchecked")
        void execute_列出目录内容() throws IOException {
            Files.writeString(tempDir.resolve("a.txt"), "a");
            Files.writeString(tempDir.resolve("b.java"), "b");
            Files.createDirectory(tempDir.resolve("subdir"));

            ToolResult result = executor.execute(buildInput(Map.of("path", tempDir.toString())));

            assertThat(result.ok()).isTrue();
            List<Map<String, Object>> entries = (List<Map<String, Object>>) result.data().get("entries");
            assertThat(entries).hasSize(3);
        }

        @Test
        @SuppressWarnings("unchecked")
        void execute_glob过滤() throws IOException {
            Files.writeString(tempDir.resolve("a.txt"), "a");
            Files.writeString(tempDir.resolve("b.java"), "b");

            ToolResult result = executor.execute(buildInput(Map.of(
                    "path", tempDir.toString(),
                    "pattern", "*.java")));

            assertThat(result.ok()).isTrue();
            List<Map<String, Object>> entries = (List<Map<String, Object>>) result.data().get("entries");
            assertThat(entries).hasSize(1);
            assertThat(entries.getFirst().get("name")).isEqualTo("b.java");
        }

        @Test
        void execute_目录不存在返回错误() {
            ToolResult result = executor.execute(buildInput(Map.of(
                    "path", tempDir.resolve("nonexistent").toString())));

            assertThat(result.ok()).isFalse();
            assertThat(result.error()).contains("目录不存在");
        }

        @Test
        void execute_路径安全拒绝() {
            ToolResult result = executor.execute(buildInput(Map.of("path", "/etc")));

            assertThat(result.ok()).isFalse();
            assertThat(result.error()).contains("安全策略拒绝");
        }

        @Test
        @SuppressWarnings("unchecked")
        void execute_maxEntries截断时仍保持目录优先排序() throws IOException {
            Files.createDirectory(tempDir.resolve("dir-b"));
            Files.createDirectory(tempDir.resolve("dir-a"));
            Files.writeString(tempDir.resolve("c.txt"), "c");
            Files.writeString(tempDir.resolve("a.txt"), "a");

            ToolResult result = executor.execute(buildInput(Map.of(
                    "path", tempDir.toString(),
                    "maxEntries", 2)));

            assertThat(result.ok()).isTrue();
            assertThat(result.data().get("totalEntries")).isEqualTo(4);
            assertThat(result.data().get("truncated")).isEqualTo(true);
            List<Map<String, Object>> entries = (List<Map<String, Object>>) result.data().get("entries");
            assertThat(entries).extracting(entry -> entry.get("name"))
                    .containsExactly("dir-a", "dir-b");
        }
    }

    // ─────────────────────────────────────────────
    //  FileSearchToolExecutor 测试
    // ─────────────────────────────────────────────

    @Nested
    class FileSearchTest {

        private FileSearchToolExecutor executor;

        @BeforeEach
        void setUp() {
            executor = new FileSearchToolExecutor(properties);
        }

        @Test
        @SuppressWarnings("unchecked")
        void execute_正则搜索匹配() throws IOException {
            Files.writeString(tempDir.resolve("test.txt"), "hello world\nfoo bar\nhello again");

            ToolResult result = executor.execute(buildInput(Map.of(
                    "path", tempDir.toString(),
                    "pattern", "hello")));

            assertThat(result.ok()).isTrue();
            List<Map<String, Object>> matches = (List<Map<String, Object>>) result.data().get("matches");
            assertThat(matches).hasSize(2);
            assertThat((int) result.data().get("totalMatches")).isEqualTo(2);
        }

        @Test
        @SuppressWarnings("unchecked")
        void execute_glob文件过滤() throws IOException {
            Files.writeString(tempDir.resolve("a.txt"), "target");
            Files.writeString(tempDir.resolve("b.java"), "target");

            ToolResult result = executor.execute(buildInput(Map.of(
                    "path", tempDir.toString(),
                    "pattern", "target",
                    "filePattern", "*.java")));

            assertThat(result.ok()).isTrue();
            List<Map<String, Object>> matches = (List<Map<String, Object>>) result.data().get("matches");
            assertThat(matches).hasSize(1);
            assertThat((String) matches.getFirst().get("file")).endsWith("b.java");
        }

        @Test
        @SuppressWarnings("unchecked")
        void execute_maxResults限制() throws IOException {
            // 创建包含多行匹配的文件
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10; i++) {
                sb.append("match line ").append(i).append("\n");
            }
            Files.writeString(tempDir.resolve("many.txt"), sb.toString());

            ToolResult result = executor.execute(buildInput(Map.of(
                    "path", tempDir.toString(),
                    "pattern", "match",
                    "maxResults", 3)));

            assertThat(result.ok()).isTrue();
            List<Map<String, Object>> matches = (List<Map<String, Object>>) result.data().get("matches");
            assertThat(matches).hasSizeLessThanOrEqualTo(3);
        }

        @Test
        void execute_无效正则返回错误() {
            ToolResult result = executor.execute(buildInput(Map.of(
                    "path", tempDir.toString(),
                    "pattern", "[invalid")));

            assertThat(result.ok()).isFalse();
            assertThat(result.error()).contains("无效的正则表达式");
        }

        @Test
        void execute_路径安全拒绝() {
            ToolResult result = executor.execute(buildInput(Map.of(
                    "path", "/etc",
                    "pattern", "test")));

            assertThat(result.ok()).isFalse();
            assertThat(result.error()).contains("安全策略拒绝");
        }

        @Test
        @SuppressWarnings("unchecked")
        void execute_offsetLimit与上下文行应正确生效() throws IOException {
            Files.writeString(tempDir.resolve("context.txt"), """
                    alpha
                    hit one
                    beta
                    hit two
                    gamma
                    """);

            ToolResult result = executor.execute(buildInput(Map.of(
                    "path", tempDir.toString(),
                    "pattern", "hit",
                    "offset", 1,
                    "limit", 1,
                    "contextLines", 1)));

            assertThat(result.ok()).isTrue();
            assertThat(result.data().get("totalMatches")).isEqualTo(2);
            List<Map<String, Object>> matches = (List<Map<String, Object>>) result.data().get("matches");
            assertThat(matches).hasSize(1);
            assertThat(matches.getFirst().get("content")).isEqualTo("hit two");
            assertThat(matches.getFirst().get("beforeContext")).isEqualTo(List.of("beta"));
            assertThat(matches.getFirst().get("afterContext")).isEqualTo(List.of("gamma"));
        }
    }

    // ─────────────────────────────────────────────
    //  辅助方法
    // ─────────────────────────────────────────────

    private ToolInput buildInput(Map<String, Object> params) {
        return new ToolInput("file.test", params, JsonSchema.empty(), null, null);
    }
}
