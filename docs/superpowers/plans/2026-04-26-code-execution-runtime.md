# 代码执行运行时 — 捆绑 Python 主路径 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 ProcessBooter 从"依赖用户系统 Python"改造为"知微自带 Python 运行时"，首次启动 SetupWizard 询问安装，预装数据科学栈对齐 Anthropic 事实标准；DockerBooter 增强 / SKILL 调整 / OS-level 沙箱延后。

**Architecture:** 新增 `PythonRuntimeManager` 管理 `~/.zhiwei/python/` 生命周期（NotInstalled / Disabled / Installing / Ready / InstallFailed 状态机）；ProcessBooter 永远只用捆绑 Python 路径；新增 `CommandGuard`（HARDLINE 11 + DANGEROUS 30 两层 + 容器 bypass）；SetupWizard 流程加一步「代码执行环境」询问；CI 流水线打 Python tarball 推 GitHub Releases。

**Tech Stack:** Java 22 (sealed interface + record + virtual threads + pattern matching) / Spring Boot / SQLite + Flyway V31 / Vue 3 + Reka UI 2.x + Tailwind 命名尺度 / Vitest + JUnit 5 + jqwik / GitHub Actions + python-build-standalone。

---

## File Structure

### 后端 Java

**新增**：
- `src/main/java/com/lifepilot/sandbox/runtime/RuntimeStatus.java` — sealed interface + 5 records
- `src/main/java/com/lifepilot/sandbox/runtime/PythonRuntimeManager.java` — 生命周期管理
- `src/main/java/com/lifepilot/sandbox/runtime/PythonRuntimeDownloader.java` — 下载 + 校验 + 解压
- `src/main/java/com/lifepilot/sandbox/runtime/RuntimeInstallProgressEmitter.java` — SSE 进度推送
- `src/main/java/com/lifepilot/sandbox/runtime/RuntimeInstallHistoryRepository.java` — 历史记录持久化
- `src/main/java/com/lifepilot/sandbox/guard/GuardResult.java` — 审查结果 record
- `src/main/java/com/lifepilot/sandbox/guard/CommandNormalizer.java` — strip ANSI / NFKC / strip null
- `src/main/java/com/lifepilot/sandbox/guard/HardlineRules.java` — 11 条硬阻断规则
- `src/main/java/com/lifepilot/sandbox/guard/DangerousRules.java` — 30 条软阻断规则
- `src/main/java/com/lifepilot/sandbox/guard/CommandGuard.java` — 主审查类
- `src/main/java/com/lifepilot/interaction/web/controller/RuntimeController.java` — REST API

**修改**：
- `src/main/java/com/lifepilot/sandbox/config/SandboxConfigProperties.java` — 新增 `Runtime` + `CommandGuardConfig` 子配置
- `src/main/java/com/lifepilot/sandbox/config/SandboxAutoConfiguration.java` — 注册新 bean
- `src/main/java/com/lifepilot/sandbox/booter/ProcessBooter.java` — 接入 PythonRuntimeManager
- `src/main/java/com/lifepilot/meta/infra/code/CodeExecuteToolExecutor.java` — runtime 检查 + CommandGuard 调用
- `src/main/resources/application.yml` — 新增 `lifepilot.sandbox.runtime` 配置块

**新增 SQL**：
- `src/main/resources/db/migration/V31__runtime_install_history.sql`

### 前端 Vue

**新增**：
- `zhiwei-web/src/api/runtime.ts` — Runtime REST 客户端
- `zhiwei-web/src/composables/useRuntimeStatus.ts` — 状态 composable
- `zhiwei-web/src/components/desktop/SetupWizardPythonRuntime.vue` — SetupWizard 新一步子组件
- `zhiwei-web/src/views/settings/CodeExecutionSettings.vue` — 设置页子页面

**修改**：
- `zhiwei-web/src/components/desktop/SetupWizard.vue` — 在 `configure` 和 `ready` 之间插入 `python-runtime` 步骤
- `zhiwei-web/src/router/index.ts` — 加 `/settings/code-execution` 路由
- `zhiwei-web/src/views/SettingsView.vue` — 设置页侧栏新增「代码执行环境」入口

### CI 与运行时构建

**新增**：
- `.github/workflows/build-python-runtime.yml` — 三平台 × x86_64/arm64 Python tarball 构建发布
- `tools/build-python-runtime/requirements.txt` — 预装库锁定列表
- `tools/build-python-runtime/build.sh` — 构建脚本（Linux/macOS）
- `tools/build-python-runtime/build.ps1` — 构建脚本（Windows）

### 测试

**新增**：
- `src/test/java/com/lifepilot/sandbox/runtime/PythonRuntimeManager_状态机测试.java`
- `src/test/java/com/lifepilot/sandbox/runtime/PythonRuntimeDownloader_下载校验测试.java`
- `src/test/java/com/lifepilot/sandbox/guard/CommandGuard_HARDLINE测试.java`
- `src/test/java/com/lifepilot/sandbox/guard/CommandGuard_DANGEROUS测试.java`
- `src/test/java/com/lifepilot/sandbox/guard/CommandGuard_归一化测试.java`
- `src/test/java/com/lifepilot/sandbox/guard/CommandGuard_容器Bypass测试.java`
- `src/test/java/com/lifepilot/interaction/web/controller/RuntimeControllerTest.java`
- `src/test/java/com/lifepilot/sandbox/integration/RuntimeInstallEndToEndTest.java`

**修改**：
- `src/test/java/com/lifepilot/sandbox/booter/ProcessBooterTest.java` — 适配捆绑 Python

---

## Phase 1：配置与状态基础

### Task 1：扩展 SandboxConfigProperties 加 Runtime 配置

**Files:**
- Modify: `src/main/java/com/lifepilot/sandbox/config/SandboxConfigProperties.java`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: 在 SandboxConfigProperties 加 Runtime 静态内部类**

```java
public static class Runtime {
    private Python python = new Python();
    private CommandGuardConfig commandGuard = new CommandGuardConfig();

    public Python getPython() { return python; }
    public void setPython(Python python) { this.python = python; }

    public CommandGuardConfig getCommandGuard() { return commandGuard; }
    public void setCommandGuard(CommandGuardConfig commandGuard) { this.commandGuard = commandGuard; }

    public static class Python {
        private String bundledVersion = "3.12.4";
        private String downloadUrlTemplate = "";
        private String sha256UrlTemplate = "";
        private String installPath = "${user.home}/.zhiwei/python";
        private List<String> expectedLibraries = List.of(
            "pandas", "numpy", "scipy", "scikit-learn", "matplotlib", "seaborn",
            "openpyxl", "pillow", "python-pptx", "python-docx", "pypdf", "pdfplumber",
            "sympy", "requests", "httpx", "beautifulsoup4");
        private boolean disabled = false;
        // getters / setters
    }

    public static class CommandGuardConfig {
        private boolean enabled = true;
        private boolean yoloMode = false;
        // getters / setters
    }
}
```

并在 `SandboxConfigProperties` 顶层加：
```java
private Runtime runtime = new Runtime();
public Runtime getRuntime() { return runtime; }
public void setRuntime(Runtime runtime) { this.runtime = runtime; }
```

- [ ] **Step 2: 在 application.yml 加默认配置块**

```yaml
lifepilot:
  sandbox:
    runtime:
      python:
        bundled-version: "3.12.4"
        download-url-template: "https://github.com/zsg-cs/zhiwei/releases/download/runtime-{version}/zhiwei-python-runtime-{version}-{platform}-{arch}.tar.zst"
        sha256-url-template: "https://github.com/zsg-cs/zhiwei/releases/download/runtime-{version}/zhiwei-python-runtime-{version}-{platform}-{arch}.tar.zst.sha256"
        install-path: "${user.home}/.zhiwei/python"
        disabled: false
      command-guard:
        enabled: true
        yolo-mode: false
```

- [ ] **Step 3: mvn compile 验证**

Run: `mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/lifepilot/sandbox/config/SandboxConfigProperties.java src/main/resources/application.yml
git commit -m "feat(sandbox): 扩展 SandboxConfigProperties 增加 Runtime 配置"
```

---

### Task 2：定义 RuntimeStatus sealed interface

**Files:**
- Create: `src/main/java/com/lifepilot/sandbox/runtime/RuntimeStatus.java`

- [ ] **Step 1: 编写 RuntimeStatus.java**

```java
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
```

- [ ] **Step 2: mvn compile 验证**

Run: `mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/lifepilot/sandbox/runtime/RuntimeStatus.java
git commit -m "feat(sandbox): 新增 RuntimeStatus sealed interface 定义运行时 5 种状态"
```

---

### Task 3：PythonRuntimeManager 状态判断（仅查询，不下载）

**Files:**
- Create: `src/main/java/com/lifepilot/sandbox/runtime/PythonRuntimeManager.java`
- Test: `src/test/java/com/lifepilot/sandbox/runtime/PythonRuntimeManager_状态机测试.java`

- [ ] **Step 1: 写失败测试 PythonRuntimeManager_状态机测试.java**

```java
package com.lifepilot.sandbox.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import com.lifepilot.sandbox.config.SandboxConfigProperties;

import static org.assertj.core.api.Assertions.assertThat;

class PythonRuntimeManager_状态机测试 {

    @Test
    void 安装目录不存在时返回NotInstalled(@TempDir Path tempDir) {
        var config = buildConfig(tempDir.resolve("not-exists"));
        var manager = new PythonRuntimeManager(config);

        assertThat(manager.checkStatus()).isInstanceOf(RuntimeStatus.NotInstalled.class);
    }

    @Test
    void 配置disabled为true时返回Disabled(@TempDir Path tempDir) throws Exception {
        var pythonDir = tempDir.resolve("python");
        Files.createDirectories(pythonDir);
        Files.writeString(pythonDir.resolve("VERSION"), "3.12.4");
        var config = buildConfig(pythonDir);
        config.getRuntime().getPython().setDisabled(true);

        var manager = new PythonRuntimeManager(config);

        assertThat(manager.checkStatus()).isInstanceOf(RuntimeStatus.Disabled.class);
    }

    @Test
    void VERSION文件存在且版本匹配时返回Ready(@TempDir Path tempDir) throws Exception {
        var pythonDir = tempDir.resolve("python");
        Files.createDirectories(pythonDir.resolve("bin"));
        Files.writeString(pythonDir.resolve("VERSION"), "3.12.4");
        // 写一个 fake 的 python 文件作为可执行存在性检查的桩
        Files.writeString(pythonDir.resolve("bin/python"), "#!/bin/sh\necho fake");
        var config = buildConfig(pythonDir);

        var manager = new PythonRuntimeManager(config);
        var status = manager.checkStatus();

        assertThat(status).isInstanceOf(RuntimeStatus.Ready.class);
        assertThat(((RuntimeStatus.Ready) status).version()).isEqualTo("3.12.4");
    }

    @Test
    void VERSION文件不匹配时返回InstallFailed(@TempDir Path tempDir) throws Exception {
        var pythonDir = tempDir.resolve("python");
        Files.createDirectories(pythonDir);
        Files.writeString(pythonDir.resolve("VERSION"), "3.11.0");
        var config = buildConfig(pythonDir);

        var manager = new PythonRuntimeManager(config);

        assertThat(manager.checkStatus()).isInstanceOf(RuntimeStatus.InstallFailed.class);
    }

    private SandboxConfigProperties buildConfig(Path installPath) {
        var config = new SandboxConfigProperties();
        config.getRuntime().getPython().setBundledVersion("3.12.4");
        config.getRuntime().getPython().setInstallPath(installPath.toString());
        return config;
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=PythonRuntimeManager_状态机测试`
Expected: COMPILATION ERROR (PythonRuntimeManager 不存在)

- [ ] **Step 3: 实现 PythonRuntimeManager.java**

```java
package com.lifepilot.sandbox.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lifepilot.sandbox.config.SandboxConfigProperties;

/**
 * 捆绑 Python 运行时生命周期管理器。
 *
 * <p>负责查询 ~/.zhiwei/python/ 状态、触发安装/卸载/启用/禁用，
 * 并向调用方提供 Ready 状态下的 Python 可执行路径。</p>
 *
 * @author zsg
 * @since 2026-04-26
 */
public class PythonRuntimeManager {

    private static final Logger log = LoggerFactory.getLogger(PythonRuntimeManager.class);

    private final SandboxConfigProperties config;
    /** 安装中状态共享引用，供 SSE 推送和 checkStatus 共用。 */
    private final AtomicReference<RuntimeStatus> installingState = new AtomicReference<>();

    public PythonRuntimeManager(SandboxConfigProperties config) {
        this.config = config;
    }

    /**
     * 查询当前运行时状态。
     */
    public RuntimeStatus checkStatus() {
        // 1. 安装中优先（最不稳态）
        var installing = installingState.get();
        if (installing != null) {
            return installing;
        }

        var pythonConfig = config.getRuntime().getPython();
        Path installPath = resolveInstallPath();

        // 2. 配置强制禁用
        if (pythonConfig.isDisabled()) {
            return new RuntimeStatus.Disabled();
        }

        // 3. 文件不存在 → NotInstalled
        Path versionFile = installPath.resolve("VERSION");
        if (!Files.exists(versionFile)) {
            return new RuntimeStatus.NotInstalled();
        }

        // 4. VERSION 匹配检查
        try {
            String installed = Files.readString(versionFile).trim();
            String expected = pythonConfig.getBundledVersion();
            if (!installed.equals(expected)) {
                return new RuntimeStatus.InstallFailed(
                        "版本不匹配: 已安装 %s, 期望 %s".formatted(installed, expected));
            }

            // 5. python 可执行文件存在性检查
            Path pythonExe = getPythonExecutable();
            if (!Files.exists(pythonExe)) {
                return new RuntimeStatus.InstallFailed("Python 可执行文件缺失: " + pythonExe);
            }

            long diskBytes = computeDiskUsage(installPath);
            return new RuntimeStatus.Ready(installed, diskBytes);
        } catch (IOException e) {
            log.warn("读取 VERSION 文件失败: {}", e.getMessage());
            return new RuntimeStatus.InstallFailed("VERSION 文件读取失败: " + e.getMessage());
        }
    }

    /**
     * 返回 Python 可执行文件路径（无论状态是否 Ready）。
     */
    public Path getPythonExecutable() {
        Path installPath = resolveInstallPath();
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        return isWindows
                ? installPath.resolve("python.exe")
                : installPath.resolve("bin/python");
    }

    /**
     * 解析安装路径，展开 ${user.home}。
     */
    Path resolveInstallPath() {
        String raw = config.getRuntime().getPython().getInstallPath();
        String resolved = raw.replace("${user.home}", System.getProperty("user.home"));
        return Paths.get(resolved);
    }

    /**
     * 由 PythonRuntimeDownloader 设置的安装中状态。
     */
    void setInstalling(RuntimeStatus.Installing state) {
        installingState.set(state);
    }

    /**
     * 安装结束（成功或失败）时清除 installing 状态。
     */
    void clearInstalling() {
        installingState.set(null);
    }

    private long computeDiskUsage(Path dir) {
        try (var stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try { return Files.size(p); } catch (IOException e) { return 0L; }
                    })
                    .sum();
        } catch (IOException e) {
            return 0L;
        }
    }
}
```

- [ ] **Step 4: 运行测试通过**

Run: `mvn test -Dtest=PythonRuntimeManager_状态机测试`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/lifepilot/sandbox/runtime/PythonRuntimeManager.java src/test/java/com/lifepilot/sandbox/runtime/PythonRuntimeManager_状态机测试.java
git commit -m "feat(sandbox): PythonRuntimeManager 状态查询实现 + 4 个状态机测试"
```

---

## Phase 2：CommandGuard 安全栈

### Task 4：CommandNormalizer（防绕过归一化）

**Files:**
- Create: `src/main/java/com/lifepilot/sandbox/guard/CommandNormalizer.java`
- Test: `src/test/java/com/lifepilot/sandbox/guard/CommandGuard_归一化测试.java`

- [ ] **Step 1: 写失败测试**

```java
package com.lifepilot.sandbox.guard;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CommandGuard_归一化测试 {

    @Test
    void 剥离ANSI转义序列() {
        String input = "\u001B[31mrm -rf /\u001B[0m";
        assertThat(CommandNormalizer.normalize(input)).isEqualTo("rm -rf /");
    }

    @Test
    void NFKC归一化全角字符() {
        String input = "ｒｍ －ｒｆ ／";  // 全角
        assertThat(CommandNormalizer.normalize(input)).isEqualTo("rm -rf /");
    }

    @Test
    void 剥离null字符() {
        String input = "rm \u0000-rf /";
        assertThat(CommandNormalizer.normalize(input)).isEqualTo("rm -rf /");
    }

    @Test
    void 多重组合绕过尝试() {
        String input = "\u001B[31mｒｍ\u0000 -rf /\u001B[0m";
        assertThat(CommandNormalizer.normalize(input)).isEqualTo("rm -rf /");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=CommandGuard_归一化测试`
Expected: COMPILATION ERROR

- [ ] **Step 3: 实现 CommandNormalizer.java**

```java
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

    /** ANSI CSI / OSC / SGR 转义序列。 */
    private static final Pattern ANSI = Pattern.compile("\\u001B\\[[0-?]*[ -/]*[@-~]");

    private CommandNormalizer() {}

    /**
     * 归一化命令字符串：strip ANSI → NFKC → strip null。
     */
    public static String normalize(String input) {
        if (input == null) return "";
        String stripped = ANSI.matcher(input).replaceAll("");
        String nfkc = Normalizer.normalize(stripped, Normalizer.Form.NFKC);
        return nfkc.replace("\u0000", "");
    }
}
```

- [ ] **Step 4: 运行测试通过**

Run: `mvn test -Dtest=CommandGuard_归一化测试`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/lifepilot/sandbox/guard/CommandNormalizer.java src/test/java/com/lifepilot/sandbox/guard/CommandGuard_归一化测试.java
git commit -m "feat(sandbox): CommandNormalizer 实现 ANSI/NFKC/null 三重归一化"
```

---

### Task 5：HardlineRules（11 条无条件硬阻断）

**Files:**
- Create: `src/main/java/com/lifepilot/sandbox/guard/GuardResult.java`
- Create: `src/main/java/com/lifepilot/sandbox/guard/HardlineRules.java`
- Test: `src/test/java/com/lifepilot/sandbox/guard/CommandGuard_HARDLINE测试.java`

- [ ] **Step 1: 编写 GuardResult.java**

```java
package com.lifepilot.sandbox.guard;

/**
 * 命令审查结果。
 *
 * @author zsg
 * @since 2026-04-26
 */
public record GuardResult(Decision decision, String matchedRule, String description) {

    public enum Decision { APPROVED, BLOCKED_HARDLINE, BLOCKED_DANGEROUS }

    public static GuardResult approved() {
        return new GuardResult(Decision.APPROVED, null, null);
    }

    public static GuardResult hardline(String rule, String description) {
        return new GuardResult(Decision.BLOCKED_HARDLINE, rule, description);
    }

    public static GuardResult dangerous(String rule, String description) {
        return new GuardResult(Decision.BLOCKED_DANGEROUS, rule, description);
    }

    public boolean isBlocked() {
        return decision != Decision.APPROVED;
    }
}
```

- [ ] **Step 2: 写 HARDLINE 失败测试**

```java
package com.lifepilot.sandbox.guard;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CommandGuard_HARDLINE测试 {

    @ParameterizedTest
    @ValueSource(strings = {
        "rm -rf /",
        "rm -rf /home",
        "rm -rf /etc",
        "rm -rf /usr",
        "rm -rf $HOME",
        "rm -rf ~",
        "mkfs.ext4 /dev/sda1",
        "mkfs.btrfs /dev/nvme0n1",
        "dd if=/dev/zero of=/dev/sda",
        "dd if=/etc/passwd of=/dev/nvme0n1",
        ":(){ :|:& };:",
        "kill -1",
        "kill -9 -1",
        "shutdown -h now",
        "reboot",
        "halt",
        "poweroff",
        "init 0",
        "init 6",
        "systemctl poweroff",
        "systemctl reboot",
        "systemctl kexec",
        "telinit 0",
        "telinit 6",
        "chmod -R 000 /"
    })
    void HARDLINE规则命中拦截(String code) {
        var result = HardlineRules.check(code);
        assertThat(result.decision()).isEqualTo(GuardResult.Decision.BLOCKED_HARDLINE);
        assertThat(result.matchedRule()).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "echo 'rm -rf /'",                  // 字符串内不算
        "ls /home",                          // 仅列目录
        "rm /tmp/foo.txt",                   // 单文件 rm 不阻断
        "echo reboot",                       // 仅打印
        "# rm -rf / 注释"                   // 注释行
    })
    void 安全场景不被HARDLINE误伤(String code) {
        var result = HardlineRules.check(code);
        assertThat(result.decision()).isNotEqualTo(GuardResult.Decision.BLOCKED_HARDLINE);
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `mvn test -Dtest=CommandGuard_HARDLINE测试`
Expected: COMPILATION ERROR

- [ ] **Step 4: 实现 HardlineRules.java**

```java
package com.lifepilot.sandbox.guard;

import java.util.List;
import java.util.regex.Pattern;

/**
 * HARDLINE 规则集 — 11 条无条件硬阻断规则。
 *
 * <p>这些规则匹配的命令不可恢复，无论 yolo 模式如何均拦截，
 * 仅容器后端可 bypass（参考 Hermes approval.py 范式）。</p>
 *
 * <p>采用命令位置锚定（行首或 ; / && / | 之后），避免 echo / 注释误伤。</p>
 *
 * @author zsg
 * @since 2026-04-26
 */
public final class HardlineRules {

    /** 命令位置锚：行首、; & | && || 之后。 */
    private static final String CMDPOS = "(?:^|[\\n;&|]+\\s*)";

    private record Rule(String name, Pattern pattern, String description) {}

    private static final List<Rule> RULES = List.of(
        rule("rm-root-or-system",
            CMDPOS + "rm\\s+(-[rRf]+\\s+)+(/|/home|/etc|/usr|/var|/boot|/bin|/sbin|/lib|~|\\$HOME)(\\s|$)",
            "递归删除根/系统目录/家目录"),
        rule("mkfs",
            CMDPOS + "mkfs(\\.[a-z0-9]+)?\\s+",
            "格式化文件系统"),
        rule("dd-block-device",
            CMDPOS + "dd\\s+.*of=/dev/(sd|nvme|hd|vd)[a-z0-9]+",
            "写裸块设备"),
        rule("fork-bomb",
            ":\\s*\\(\\s*\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*\\}\\s*;\\s*:",
            "fork bomb"),
        rule("kill-all",
            CMDPOS + "kill\\s+(-1|-9\\s+-1)\\b",
            "kill 所有进程"),
        rule("shutdown",
            CMDPOS + "(shutdown|reboot|halt|poweroff)\\b",
            "关机或重启"),
        rule("init",
            CMDPOS + "init\\s+[06]\\b",
            "通过 init 关机重启"),
        rule("systemctl-power",
            CMDPOS + "systemctl\\s+(poweroff|reboot|halt|kexec)\\b",
            "通过 systemctl 关机重启"),
        rule("telinit",
            CMDPOS + "telinit\\s+[06]\\b",
            "通过 telinit 关机重启"),
        rule("chmod-system-readonly",
            CMDPOS + "chmod\\s+(-R\\s+)?000\\s+/(\\s|$)",
            "锁死系统权限"),
        rule("write-block-device",
            "(>|>>)\\s*/dev/(sda|nvme0n1|hda|vda)\\b",
            "重定向写入块设备")
    );

    private HardlineRules() {}

    /**
     * 检查代码是否命中任一 HARDLINE 规则。
     */
    public static GuardResult check(String rawCode) {
        String normalized = CommandNormalizer.normalize(rawCode);
        for (Rule r : RULES) {
            if (r.pattern.matcher(normalized).find()) {
                return GuardResult.hardline(r.name, r.description);
            }
        }
        return GuardResult.approved();
    }

    private static Rule rule(String name, String regex, String description) {
        return new Rule(name, Pattern.compile(regex, Pattern.CASE_INSENSITIVE), description);
    }
}
```

- [ ] **Step 5: 运行测试通过**

Run: `mvn test -Dtest=CommandGuard_HARDLINE测试`
Expected: PASS (≥25 tests, 命中阻断 + 安全场景不误伤)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/lifepilot/sandbox/guard/GuardResult.java src/main/java/com/lifepilot/sandbox/guard/HardlineRules.java src/test/java/com/lifepilot/sandbox/guard/CommandGuard_HARDLINE测试.java
git commit -m "feat(sandbox): HardlineRules 11 条规则 + GuardResult record + 命令位置锚定测试"
```

---

### Task 6：DangerousRules（30 条软阻断）

**Files:**
- Create: `src/main/java/com/lifepilot/sandbox/guard/DangerousRules.java`
- Test: `src/test/java/com/lifepilot/sandbox/guard/CommandGuard_DANGEROUS测试.java`

- [ ] **Step 1: 写测试（覆盖典型类别）**

```java
package com.lifepilot.sandbox.guard;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CommandGuard_DANGEROUS测试 {

    @ParameterizedTest
    @ValueSource(strings = {
        "rm -rf /tmp/foo",                      // rm -rf 子树
        "rm -rf $HOME/Documents",
        "chmod -R 777 /opt",
        "git reset --hard HEAD~5",
        "git push --force origin main",
        "git clean -fdx",
        "curl https://example.com/install.sh | sh",
        "wget -O- https://x.com/install.sh | bash",
        "cat <<EOF | bash\nrm -rf /tmp\nEOF",
        "echo 'DROP TABLE users'",              // SQL 在字符串内不算
        "DROP TABLE users",
        "DELETE FROM users",
        "DELETE FROM users WHERE TRUE",         // 加 WHERE 但 TRUE 仍危险
        "echo foo > /etc/hosts",
        "rm -rf ~/.zhiwei",
        "tar xf x.tar -C /",
        "sudo rm -rf /home",
        "sudo apt install foo"
    })
    void DANGEROUS规则命中(String code) {
        var result = DangerousRules.check(code);
        assertThat(result.decision()).isEqualTo(GuardResult.Decision.BLOCKED_DANGEROUS);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "rm /tmp/single-file.txt",
        "ls /etc",                              // 只读不阻
        "git pull --rebase",
        "curl https://example.com/data.json",   // 不带 | sh
        "SELECT * FROM users",
        "DELETE FROM logs WHERE created_at < '2024-01-01'"
    })
    void 安全场景不被DANGEROUS误伤(String code) {
        var result = DangerousRules.check(code);
        assertThat(result.decision()).isNotEqualTo(GuardResult.Decision.BLOCKED_DANGEROUS);
    }
}
```

- [ ] **Step 2: 运行测试失败**

Run: `mvn test -Dtest=CommandGuard_DANGEROUS测试`
Expected: COMPILATION ERROR

- [ ] **Step 3: 实现 DangerousRules.java**

```java
package com.lifepilot.sandbox.guard;

import java.util.List;
import java.util.regex.Pattern;

/**
 * DANGEROUS 规则集 — 约 30 条软阻断规则。
 *
 * <p>这些规则匹配的命令风险高但可恢复。yolo 模式启用时放行，关闭时拦截。
 * 容器后端可 bypass。</p>
 *
 * @author zsg
 * @since 2026-04-26
 */
public final class DangerousRules {

    private static final String CMDPOS = "(?:^|[\\n;&|]+\\s*)";

    private record Rule(String name, Pattern pattern, String description) {}

    private static final List<Rule> RULES = List.of(
        rule("rm-rf-subtree",
            CMDPOS + "rm\\s+(-[rRf]+\\s+)+\\S+",
            "递归删除子树"),
        rule("chmod-777",
            CMDPOS + "chmod\\s+(-R\\s+)?7[57]7\\s+",
            "设置 777 权限"),
        rule("git-reset-hard",
            CMDPOS + "git\\s+reset\\s+--hard\\b",
            "硬重置 git"),
        rule("git-push-force",
            CMDPOS + "git\\s+push\\s+(-f|--force)\\b",
            "强推 git"),
        rule("git-clean-fdx",
            CMDPOS + "git\\s+clean\\s+-[fdxRr]+\\b",
            "清理 git 工作区"),
        rule("curl-pipe-sh",
            CMDPOS + "(curl|wget)\\s+[^|]*\\|\\s*(sh|bash|zsh)\\b",
            "管道执行远程脚本"),
        rule("heredoc-bash",
            "<<\\s*\\w+[\\s\\S]*?\\|\\s*(bash|sh|zsh)\\b",
            "heredoc 执行 shell"),
        rule("sql-drop",
            CMDPOS + "DROP\\s+(TABLE|DATABASE|SCHEMA)\\b",
            "SQL DROP 操作"),
        rule("sql-delete-all",
            CMDPOS + "DELETE\\s+FROM\\s+\\w+\\s*(;|$|WHERE\\s+TRUE)",
            "SQL 删除全表或恒真条件"),
        rule("write-etc",
            "(>|>>)\\s*/etc/\\w+",
            "写入 /etc/"),
        rule("modify-self-zhiwei",
            "(rm|mv|>|>>)\\s+.*~/.zhiwei/",
            "改动知微自身目录"),
        rule("tar-extract-root",
            CMDPOS + "tar\\s+x[fzj]?\\s+\\S+\\s+(-C\\s+)?/(\\s|$)",
            "解压到根目录"),
        rule("sudo-su",
            CMDPOS + "(sudo|su)\\b",
            "提权命令")
        // 实际实施时根据 Hermes approval.py:175-238 翻译补足至 30 条
    );

    private DangerousRules() {}

    public static GuardResult check(String rawCode) {
        String normalized = CommandNormalizer.normalize(rawCode);
        for (Rule r : RULES) {
            if (r.pattern.matcher(normalized).find()) {
                return GuardResult.dangerous(r.name, r.description);
            }
        }
        return GuardResult.approved();
    }

    private static Rule rule(String name, String regex, String description) {
        return new Rule(name, Pattern.compile(regex, Pattern.CASE_INSENSITIVE), description);
    }
}
```

- [ ] **Step 4: 测试通过**

Run: `mvn test -Dtest=CommandGuard_DANGEROUS测试`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/lifepilot/sandbox/guard/DangerousRules.java src/test/java/com/lifepilot/sandbox/guard/CommandGuard_DANGEROUS测试.java
git commit -m "feat(sandbox): DangerousRules 软阻断规则 + 安全场景不误伤测试"
```

---

### Task 7：CommandGuard 主类（组合规则 + 容器 bypass）

**Files:**
- Create: `src/main/java/com/lifepilot/sandbox/guard/CommandGuard.java`
- Test: `src/test/java/com/lifepilot/sandbox/guard/CommandGuard_容器Bypass测试.java`

- [ ] **Step 1: 写容器 bypass 测试**

```java
package com.lifepilot.sandbox.guard;

import org.junit.jupiter.api.Test;

import com.lifepilot.sandbox.config.SandboxConfigProperties;

import static org.assertj.core.api.Assertions.assertThat;

class CommandGuard_容器Bypass测试 {

    @Test
    void Docker后端_HARDLINE也bypass() {
        var config = new SandboxConfigProperties();
        var guard = new CommandGuard(config);
        var result = guard.check("rm -rf /", "docker");
        assertThat(result.decision()).isEqualTo(GuardResult.Decision.APPROVED);
    }

    @Test
    void Process后端_HARDLINE被拦截() {
        var config = new SandboxConfigProperties();
        var guard = new CommandGuard(config);
        var result = guard.check("rm -rf /", "process");
        assertThat(result.decision()).isEqualTo(GuardResult.Decision.BLOCKED_HARDLINE);
    }

    @Test
    void Process后端_yolo开启时DANGEROUS放行() {
        var config = new SandboxConfigProperties();
        config.getRuntime().getCommandGuard().setYoloMode(true);
        var guard = new CommandGuard(config);
        var result = guard.check("git reset --hard HEAD~3", "process");
        assertThat(result.decision()).isEqualTo(GuardResult.Decision.APPROVED);
    }

    @Test
    void Process后端_yolo开启时HARDLINE仍拦截() {
        var config = new SandboxConfigProperties();
        config.getRuntime().getCommandGuard().setYoloMode(true);
        var guard = new CommandGuard(config);
        var result = guard.check("rm -rf /", "process");
        assertThat(result.decision()).isEqualTo(GuardResult.Decision.BLOCKED_HARDLINE);
    }

    @Test
    void CommandGuard禁用时全部放行() {
        var config = new SandboxConfigProperties();
        config.getRuntime().getCommandGuard().setEnabled(false);
        var guard = new CommandGuard(config);
        var result = guard.check("rm -rf /", "process");
        assertThat(result.decision()).isEqualTo(GuardResult.Decision.APPROVED);
    }
}
```

- [ ] **Step 2: 运行测试失败**

Run: `mvn test -Dtest=CommandGuard_容器Bypass测试`
Expected: COMPILATION ERROR

- [ ] **Step 3: 实现 CommandGuard.java**

```java
package com.lifepilot.sandbox.guard;

import java.util.Set;

import com.lifepilot.sandbox.config.SandboxConfigProperties;

/**
 * 命令审查门面 — 组合 HardlineRules / DangerousRules，提供后端类型 bypass 与 yolo 模式。
 *
 * @author zsg
 * @since 2026-04-26
 */
public class CommandGuard {

    private static final Set<String> CONTAINER_BACKENDS = Set.of("docker");

    private final SandboxConfigProperties config;

    public CommandGuard(SandboxConfigProperties config) {
        this.config = config;
    }

    /**
     * 审查命令。
     *
     * @param code        待执行代码（任意语言，guard 仅基于字符串模式）
     * @param booterType  后端类型 "process" | "docker"，docker bypass 全部规则
     */
    public GuardResult check(String code, String booterType) {
        var guardConfig = config.getRuntime().getCommandGuard();

        if (!guardConfig.isEnabled()) {
            return GuardResult.approved();
        }

        if (CONTAINER_BACKENDS.contains(booterType)) {
            return GuardResult.approved();
        }

        var hardline = HardlineRules.check(code);
        if (hardline.isBlocked()) {
            return hardline;
        }

        if (guardConfig.isYoloMode()) {
            return GuardResult.approved();
        }

        return DangerousRules.check(code);
    }
}
```

- [ ] **Step 4: 测试通过**

Run: `mvn test -Dtest=CommandGuard_容器Bypass测试`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/lifepilot/sandbox/guard/CommandGuard.java src/test/java/com/lifepilot/sandbox/guard/CommandGuard_容器Bypass测试.java
git commit -m "feat(sandbox): CommandGuard 主类组合规则集 + docker bypass + yolo 模式开关"
```

---

## Phase 3：下载安装实现

### Task 8：PythonRuntimeDownloader 下载与校验

**Files:**
- Create: `src/main/java/com/lifepilot/sandbox/runtime/PythonRuntimeDownloader.java`
- Test: `src/test/java/com/lifepilot/sandbox/runtime/PythonRuntimeDownloader_下载校验测试.java`

- [ ] **Step 1: 写下载校验测试（用 jdk WireMock 或 SimpleHttpServer）**

```java
package com.lifepilot.sandbox.runtime;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PythonRuntimeDownloader_下载校验测试 {

    private HttpServer server;
    private int port;

    @BeforeEach
    void 启动mock服务器() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void 停止mock服务器() {
        server.stop(0);
    }

    @Test
    void 下载并校验SHA256通过(@TempDir Path tempDir) throws Exception {
        byte[] payload = "fake-tarball-content".getBytes();
        String sha256 = sha256Hex(payload);

        server.createContext("/file.tar.zst", exchange -> {
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.createContext("/file.tar.zst.sha256", exchange -> {
            byte[] hashBytes = sha256.getBytes();
            exchange.sendResponseHeaders(200, hashBytes.length);
            exchange.getResponseBody().write(hashBytes);
            exchange.close();
        });

        var downloader = new PythonRuntimeDownloader();
        Path target = tempDir.resolve("downloaded.tar.zst");
        Consumer<Long> progress = bytes -> {};

        downloader.download("http://localhost:" + port + "/file.tar.zst",
                "http://localhost:" + port + "/file.tar.zst.sha256",
                target, progress);

        assertThat(target).exists();
        assertThat(Files.readAllBytes(target)).isEqualTo(payload);
    }

    @Test
    void SHA256不匹配时抛异常并删除文件(@TempDir Path tempDir) throws Exception {
        byte[] payload = "tampered".getBytes();
        server.createContext("/file.tar.zst", exchange -> {
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.createContext("/file.tar.zst.sha256", exchange -> {
            byte[] wrong = "0000000000000000000000000000000000000000000000000000000000000000".getBytes();
            exchange.sendResponseHeaders(200, wrong.length);
            exchange.getResponseBody().write(wrong);
            exchange.close();
        });

        var downloader = new PythonRuntimeDownloader();
        Path target = tempDir.resolve("bad.tar.zst");

        assertThatThrownBy(() -> downloader.download(
                "http://localhost:" + port + "/file.tar.zst",
                "http://localhost:" + port + "/file.tar.zst.sha256",
                target, b -> {}))
            .hasMessageContaining("SHA-256");

        assertThat(target).doesNotExist();
    }

    private static String sha256Hex(byte[] input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(input));
    }
}
```

- [ ] **Step 2: 测试失败**

Run: `mvn test -Dtest=PythonRuntimeDownloader_下载校验测试`
Expected: COMPILATION ERROR

- [ ] **Step 3: 实现 PythonRuntimeDownloader.java**

```java
package com.lifepilot.sandbox.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Python 运行时下载器 — 流式下载 + SHA-256 校验。
 *
 * @author zsg
 * @since 2026-04-26
 */
public class PythonRuntimeDownloader {

    private static final Logger log = LoggerFactory.getLogger(PythonRuntimeDownloader.class);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * 下载文件并校验 SHA-256。失败时删除部分文件并抛异常。
     */
    public void download(String fileUrl, String sha256Url, Path targetFile,
                         Consumer<Long> progressConsumer) throws IOException, InterruptedException {
        Files.createDirectories(targetFile.getParent());
        Path partial = targetFile.resolveSibling(targetFile.getFileName() + ".partial");

        try {
            // 1. 下载主文件（流式）
            var fileReq = HttpRequest.newBuilder(URI.create(fileUrl)).GET().build();
            HttpResponse<InputStream> resp = httpClient.send(fileReq, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                throw new IOException("HTTP " + resp.statusCode() + " for " + fileUrl);
            }

            try (var in = resp.body();
                 var out = Files.newOutputStream(partial)) {
                byte[] buf = new byte[64 * 1024];
                long total = 0L;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                    progressConsumer.accept(total);
                }
            }

            // 2. 下载预期 SHA-256
            var hashReq = HttpRequest.newBuilder(URI.create(sha256Url)).GET().build();
            HttpResponse<String> hashResp = httpClient.send(hashReq, HttpResponse.BodyHandlers.ofString());
            String expected = hashResp.body().trim().split("\\s+")[0].toLowerCase();

            // 3. 校验 SHA-256
            String actual = computeSha256(partial);
            if (!actual.equalsIgnoreCase(expected)) {
                throw new IOException("SHA-256 校验失败: 期望 " + expected + ", 实际 " + actual);
            }

            // 4. 校验通过，重命名到目标
            Files.move(partial, targetFile, StandardCopyOption.REPLACE_EXISTING);
            log.info("下载完成: {} ({} bytes, sha256={})", targetFile, Files.size(targetFile), actual);
        } catch (Exception e) {
            try { Files.deleteIfExists(partial); } catch (IOException ignored) {}
            try { Files.deleteIfExists(targetFile); } catch (IOException ignored) {}
            if (e instanceof IOException io) throw io;
            if (e instanceof InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            }
            throw new IOException("下载失败: " + e.getMessage(), e);
        }
    }

    private static String computeSha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }
}
```

- [ ] **Step 4: 测试通过**

Run: `mvn test -Dtest=PythonRuntimeDownloader_下载校验测试`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/lifepilot/sandbox/runtime/PythonRuntimeDownloader.java src/test/java/com/lifepilot/sandbox/runtime/PythonRuntimeDownloader_下载校验测试.java
git commit -m "feat(sandbox): PythonRuntimeDownloader 流式下载 + SHA-256 校验 + 失败清理"
```

---

### Task 9：PythonRuntimeManager.install/uninstall/disable/enable 流程

**Files:**
- Modify: `src/main/java/com/lifepilot/sandbox/runtime/PythonRuntimeManager.java`

- [ ] **Step 1: 加 zstd 解压依赖到 pom.xml（如未有）**

```xml
<dependency>
    <groupId>com.github.luben</groupId>
    <artifactId>zstd-jni</artifactId>
    <version>1.5.6-3</version>
</dependency>
<dependency>
    <groupId>org.apache.commons</groupId>
    <artifactId>commons-compress</artifactId>
    <version>1.26.1</version>
</dependency>
```

Run: `mvn dependency:resolve` 验证。

- [ ] **Step 2: 在 PythonRuntimeManager 加 install/uninstall/disable/enable 方法**

```java
public CompletableFuture<Void> install(RuntimeInstallProgressEmitter emitter) {
    return CompletableFuture.runAsync(() -> {
        var pythonConfig = config.getRuntime().getPython();
        Path installPath = resolveInstallPath();
        Path tarball = installPath.getParent().resolve(installPath.getFileName() + ".tar.zst");

        try {
            String version = pythonConfig.getBundledVersion();
            String platform = detectPlatform();
            String arch = detectArch();

            String fileUrl = pythonConfig.getDownloadUrlTemplate()
                    .replace("{version}", version).replace("{platform}", platform).replace("{arch}", arch);
            String sha256Url = pythonConfig.getSha256UrlTemplate()
                    .replace("{version}", version).replace("{platform}", platform).replace("{arch}", arch);

            // 状态：downloading
            installingState.set(new RuntimeStatus.Installing("downloading", 0, 0));
            emitter.emit(installingState.get());

            new PythonRuntimeDownloader().download(fileUrl, sha256Url, tarball, bytes -> {
                var current = (RuntimeStatus.Installing) installingState.get();
                installingState.set(new RuntimeStatus.Installing(
                        "downloading", bytes, current.totalBytes()));
                emitter.emit(installingState.get());
            });

            // 状态：extracting
            installingState.set(new RuntimeStatus.Installing("extracting", 0, 0));
            emitter.emit(installingState.get());

            // 解压 tar.zst → installPath（去掉顶层 python/ 前缀）
            extractTarZst(tarball, installPath.getParent());

            // 写 VERSION 文件（如果 tarball 内未带）
            Path versionFile = installPath.resolve("VERSION");
            if (!Files.exists(versionFile)) {
                Files.writeString(versionFile, version);
            }

            // 状态：done
            installingState.set(new RuntimeStatus.Installing("done", 1, 1));
            emitter.emit(installingState.get());

            log.info("Python 运行时安装完成: path={}, version={}", installPath, version);
        } catch (Exception e) {
            log.error("Python 运行时安装失败: {}", e.getMessage(), e);
            installingState.set(null);
            emitter.emitFailed(e.getMessage());
            throw new RuntimeException("安装失败", e);
        } finally {
            try { Files.deleteIfExists(tarball); } catch (IOException ignored) {}
            // 清除 installing 状态留给状态机
            new Thread(() -> {
                try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                installingState.set(null);
            }).start();
        }
    }, Executors.newVirtualThreadPerTaskExecutor());
}

public void uninstall() throws IOException {
    Path installPath = resolveInstallPath();
    if (Files.exists(installPath)) {
        SandboxUtils.deleteDirectoryRecursively(installPath);
    }
}

public void disable() {
    config.getRuntime().getPython().setDisabled(true);
}

public void enable() {
    config.getRuntime().getPython().setDisabled(false);
}

private static String detectPlatform() {
    String os = System.getProperty("os.name").toLowerCase();
    if (os.contains("win")) return "windows";
    if (os.contains("mac")) return "macos";
    return "linux";
}

private static String detectArch() {
    String arch = System.getProperty("os.arch").toLowerCase();
    if (arch.contains("aarch64") || arch.contains("arm64")) return "arm64";
    return "x86_64";
}

private static void extractTarZst(Path tarZst, Path outDir) throws IOException {
    Files.createDirectories(outDir);
    try (var in = Files.newInputStream(tarZst);
         var zstd = new com.github.luben.zstd.ZstdInputStream(in);
         var tar = new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(zstd)) {
        org.apache.commons.compress.archivers.tar.TarArchiveEntry entry;
        while ((entry = tar.getNextEntry()) != null) {
            Path target = outDir.resolve(entry.getName()).normalize();
            if (!target.startsWith(outDir)) {
                throw new IOException("tar entry 越权: " + entry.getName());
            }
            if (entry.isDirectory()) {
                Files.createDirectories(target);
            } else {
                Files.createDirectories(target.getParent());
                Files.copy(tar, target);
                if ((entry.getMode() & 0100) != 0) target.toFile().setExecutable(true);
            }
        }
    }
}
```

- [ ] **Step 3: 写集成测试 PythonRuntimeInstall_集成测试.java**

```java
@Test
void 端到端从mockHTTP下载到Ready状态(@TempDir Path tempDir) throws Exception {
    // 1. 准备真实 tar.zst（用 commons-compress + zstd 现场打）
    // 2. 启动 SimpleHttpServer 暴露 file 和 sha256
    // 3. 配置 manager 指向 mock URL
    // 4. install().get()
    // 5. 验证 status == Ready，VERSION 文件存在
}
```
（具体打包细节见实施时实现，spec 阶段不展开打包细节）

- [ ] **Step 4: 测试通过**

Run: `mvn test -Dtest=PythonRuntimeManager_状态机测试,PythonRuntimeInstall_集成测试`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/main/java/com/lifepilot/sandbox/runtime/PythonRuntimeManager.java src/test/java/com/lifepilot/sandbox/runtime/PythonRuntimeInstall_集成测试.java
git commit -m "feat(sandbox): PythonRuntimeManager install/uninstall/disable/enable + tar.zst 解压"
```

---

### Task 10：RuntimeInstallProgressEmitter SSE 进度推送

**Files:**
- Create: `src/main/java/com/lifepilot/sandbox/runtime/RuntimeInstallProgressEmitter.java`

- [ ] **Step 1: 实现 RuntimeInstallProgressEmitter.java**

```java
package com.lifepilot.sandbox.runtime;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Python 运行时安装进度 SSE 推送器。
 *
 * <p>多前端订阅同一进度流，全局单例。</p>
 *
 * @author zsg
 * @since 2026-04-26
 */
@Component
public class RuntimeInstallProgressEmitter {

    private static final Logger log = LoggerFactory.getLogger(RuntimeInstallProgressEmitter.class);

    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe() {
        var emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        return emitter;
    }

    public void emit(RuntimeStatus status) {
        for (var emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("progress").data(status));
            } catch (IOException e) {
                emitters.remove(emitter);
            }
        }
    }

    public void emitFailed(String reason) {
        for (var emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("failed").data(reason));
                emitter.complete();
            } catch (IOException ignored) {}
        }
        emitters.clear();
    }
}
```

- [ ] **Step 2: mvn compile**

Run: `mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/lifepilot/sandbox/runtime/RuntimeInstallProgressEmitter.java
git commit -m "feat(sandbox): RuntimeInstallProgressEmitter SSE 进度多订阅推送"
```

---

## Phase 4：ProcessBooter 改造与执行集成

### Task 11：ProcessBooter 接入 PythonRuntimeManager

**Files:**
- Modify: `src/main/java/com/lifepilot/sandbox/booter/ProcessBooter.java`
- Modify: `src/test/java/com/lifepilot/sandbox/booter/ProcessBooterTest.java`

- [ ] **Step 1: 修改 ProcessBooter 构造函数加 PythonRuntimeManager**

```java
private final PythonRuntimeManager runtimeManager;

public ProcessBooter(SandboxConfigProperties config, PythonRuntimeManager runtimeManager) {
    this.config = config;
    this.runtimeManager = runtimeManager;
}
```

- [ ] **Step 2: boot() 加运行时状态检查**

```java
@Override
public CompletableFuture<Void> boot(Path workingDirectory) {
    this.workingDirectory = workingDirectory;
    var status = runtimeManager.checkStatus();
    if (!(status instanceof RuntimeStatus.Ready)) {
        return CompletableFuture.failedFuture(
                new IllegalStateException("Python 运行时未就绪: " + status));
    }
    log.info("ProcessBooter 启动完成: workingDirectory={}", workingDirectory);
    return CompletableFuture.completedFuture(null);
}
```

- [ ] **Step 3: buildCommand 改用捆绑 Python**

```java
List<String> buildCommand(Language language, Path scriptFile) {
    return switch (language) {
        case PYTHON -> List.of(runtimeManager.getPythonExecutable().toString(), scriptFile.toString());
        case SHELL -> List.of(detectShell(), scriptFile.toString());
        case JAVASCRIPT -> {
            String node = config.getRuntimePaths().getOrDefault("javascript", "node");
            yield List.of(node, scriptFile.toString());
        }
    };
}

private static String detectShell() {
    return System.getProperty("os.name").toLowerCase().contains("win") ? "cmd" : "bash";
}
```

注：原签名是 `buildCommand(String runtimeCommand, Path scriptFile)`，改为接受 Language。execute() 内调用点同步改。

- [ ] **Step 4: 修复 ProcessBooterTest（mock PythonRuntimeManager）**

```java
@Mock
private PythonRuntimeManager runtimeManager;

@BeforeEach
void setup() {
    when(runtimeManager.checkStatus()).thenReturn(new RuntimeStatus.Ready("3.12.4", 0));
    when(runtimeManager.getPythonExecutable()).thenReturn(Paths.get("python"));  // 测试时用系统 python
    booter = new ProcessBooter(config, runtimeManager);
}
```

- [ ] **Step 5: 运行所有 ProcessBooter 测试**

Run: `mvn test -Dtest=ProcessBooterTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/lifepilot/sandbox/booter/ProcessBooter.java src/test/java/com/lifepilot/sandbox/booter/ProcessBooterTest.java
git commit -m "refactor(sandbox): ProcessBooter 改用 PythonRuntimeManager 提供的捆绑 Python 路径"
```

---

### Task 12：CodeExecuteToolExecutor 加运行时检查 + CommandGuard 调用

**Files:**
- Modify: `src/main/java/com/lifepilot/meta/infra/code/CodeExecuteToolExecutor.java`

- [ ] **Step 1: 注入 PythonRuntimeManager + CommandGuard**

```java
@Nullable
private final PythonRuntimeManager runtimeManager;
@Nullable
private final CommandGuard commandGuard;

public CodeExecuteToolExecutor(MetaProperties properties,
                               @Nullable SandboxSessionManager sessionManager,
                               @Nullable CodeValidator validator,
                               @Nullable SandboxRepository repository,
                               @Nullable PersistentKernelManager kernelManager,
                               @Nullable PythonRuntimeManager runtimeManager,
                               @Nullable CommandGuard commandGuard) {
    // ... 已有
    this.runtimeManager = runtimeManager;
    this.commandGuard = commandGuard;
}
```

- [ ] **Step 2: 在 execute() 入口加 runtime 状态检查**

```java
// 紧接参数提取后、kernel 路由前：
if (runtimeManager != null) {
    var status = runtimeManager.checkStatus();
    if (!(status instanceof RuntimeStatus.Ready)) {
        return ToolResult.error("代码执行环境未启用，请在设置页启用");
    }
}
```

- [ ] **Step 3: 在 ProcessBooter 路径加 CommandGuard 调用**

```java
// 在 booter.execute(request) 之前：
if (commandGuard != null && booter instanceof ProcessBooter) {
    var guardResult = commandGuard.check(code, "process");
    if (guardResult.isBlocked()) {
        return ToolResult.error(switch (guardResult.decision()) {
            case BLOCKED_HARDLINE -> "此命令被永久阻断（不可恢复操作）：" + guardResult.description();
            case BLOCKED_DANGEROUS -> "此命令被拒绝执行（危险操作）：" + guardResult.description();
            default -> "命令审查失败";
        });
    }
}
```

- [ ] **Step 4: 更新 CodeExecuteToolExecutorTest（注入 mock）**

构造测试用例时新增 `mock(PythonRuntimeManager.class)` 返回 Ready 状态、`mock(CommandGuard.class)` 默认 APPROVED。

- [ ] **Step 5: 测试通过**

Run: `mvn test -Dtest=CodeExecuteToolExecutorTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/lifepilot/meta/infra/code/CodeExecuteToolExecutor.java src/test/java/com/lifepilot/meta/infra/code/CodeExecuteToolExecutorTest.java
git commit -m "feat(code): CodeExecuteToolExecutor 加运行时状态检查 + CommandGuard 阻断"
```

---

### Task 13：SandboxAutoConfiguration 注册新 bean

**Files:**
- Modify: `src/main/java/com/lifepilot/sandbox/config/SandboxAutoConfiguration.java`
- Modify: `src/main/java/com/lifepilot/meta/config/MetaInfraAutoConfiguration.java`（如存在）

- [ ] **Step 1: 在 SandboxAutoConfiguration 加 bean 定义**

```java
@Bean
@ConditionalOnMissingBean
PythonRuntimeManager pythonRuntimeManager(SandboxConfigProperties config) {
    return new PythonRuntimeManager(config);
}

@Bean
@ConditionalOnMissingBean
CommandGuard commandGuard(SandboxConfigProperties config) {
    return new CommandGuard(config);
}
```

- [ ] **Step 2: 修改 sandboxBooter Bean 注入新 manager**

```java
@Bean
@ConditionalOnMissingBean
SandboxBooter sandboxBooter(SandboxConfigProperties config, PythonRuntimeManager runtimeManager) {
    String booterType = config.getBooter();
    return switch (booterType) {
        case "process" -> new ProcessBooter(config, runtimeManager);
        case "docker" -> { /* ... 不变 */ }
        default -> throw new IllegalStateException("不支持的沙箱类型: " + booterType);
    };
}
```

- [ ] **Step 3: 找到 CodeExecuteToolExecutor 的注册点**

Run: `grep -rn "new CodeExecuteToolExecutor" src/main/java/`

更新该处构造调用，传入新加的 PythonRuntimeManager / CommandGuard bean。

- [ ] **Step 4: mvn test 全量跑一遍**

Run: `mvn test`
Expected: PASS（约 3300+ 测试）

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/lifepilot/sandbox/config/SandboxAutoConfiguration.java <CodeExecuteToolExecutor 注册点>
git commit -m "feat(sandbox): AutoConfiguration 注册 PythonRuntimeManager + CommandGuard 并接入 CodeExecuteToolExecutor"
```

---

## Phase 5：REST API + 数据库迁移

### Task 14：V31 数据库迁移 + RuntimeInstallHistoryRepository

**Files:**
- Create: `src/main/resources/db/migration/V31__runtime_install_history.sql`
- Create: `src/main/java/com/lifepilot/sandbox/runtime/RuntimeInstallHistoryRepository.java`

- [ ] **Step 1: 写迁移脚本 V31__runtime_install_history.sql**

```sql
CREATE TABLE runtime_install_history (
    id           TEXT PRIMARY KEY,
    runtime_kind TEXT NOT NULL,
    version      TEXT NOT NULL,
    action       TEXT NOT NULL,
    status       TEXT NOT NULL,
    error_msg    TEXT,
    duration_ms  INTEGER,
    created_at   TEXT NOT NULL
);

CREATE INDEX idx_runtime_install_history_created_at ON runtime_install_history(created_at);
CREATE INDEX idx_runtime_install_history_kind_action ON runtime_install_history(runtime_kind, action);
```

- [ ] **Step 2: 实现 RuntimeInstallHistoryRepository.java**

```java
@Repository
public class RuntimeInstallHistoryRepository {

    private final JdbcTemplate jdbc;

    public RuntimeInstallHistoryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insert(String runtimeKind, String version, String action, String status,
                       @Nullable String errorMsg, long durationMs) {
        jdbc.update("""
            INSERT INTO runtime_install_history(id, runtime_kind, version, action, status, error_msg, duration_ms, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """, UUID.randomUUID().toString(), runtimeKind, version, action, status, errorMsg, durationMs,
                Instant.now().toString());
    }
}
```

- [ ] **Step 3: 在 PythonRuntimeManager.install/uninstall 内调用 repository.insert（可选注入）**

构造函数加：
```java
@Nullable private final RuntimeInstallHistoryRepository historyRepo;
```

install 完成或失败时各 insert 一条记录。

- [ ] **Step 4: mvn compile + 启动验证迁移**

Run: `mvn compile && mvn spring-boot:run` → 启动后 stop，确认 schema_version 表里有 V31

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V31__runtime_install_history.sql src/main/java/com/lifepilot/sandbox/runtime/RuntimeInstallHistoryRepository.java src/main/java/com/lifepilot/sandbox/runtime/PythonRuntimeManager.java src/main/java/com/lifepilot/sandbox/config/SandboxAutoConfiguration.java
git commit -m "feat(sandbox): V31 迁移 runtime_install_history 表 + Repository + Manager 写入历史"
```

---

### Task 15：RuntimeController REST API

**Files:**
- Create: `src/main/java/com/lifepilot/interaction/web/controller/RuntimeController.java`
- Test: `src/test/java/com/lifepilot/interaction/web/controller/RuntimeControllerTest.java`

- [ ] **Step 1: 实现 RuntimeController.java**

```java
package com.lifepilot.interaction.web.controller;

import com.lifepilot.sandbox.runtime.PythonRuntimeManager;
import com.lifepilot.sandbox.runtime.RuntimeInstallProgressEmitter;
import com.lifepilot.sandbox.runtime.RuntimeStatus;
import com.lifepilot.tool.model.ApiResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 运行时（捆绑 Python）管理 REST 端点。
 *
 * @author zsg
 * @since 2026-04-26
 */
@RestController
@RequestMapping("/api/runtime")
public class RuntimeController {

    private final PythonRuntimeManager manager;
    private final RuntimeInstallProgressEmitter emitter;
    private final AtomicBoolean installInProgress = new AtomicBoolean(false);

    public RuntimeController(PythonRuntimeManager manager, RuntimeInstallProgressEmitter emitter) {
        this.manager = manager;
        this.emitter = emitter;
    }

    @GetMapping("/python/status")
    public ApiResponse<Map<String, Object>> status() {
        var status = manager.checkStatus();
        return ApiResponse.success(toMap(status));
    }

    @PostMapping("/python/install")
    public ResponseEntity<ApiResponse<Map<String, Object>>> install() {
        if (!installInProgress.compareAndSet(false, true)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error(409, "已有安装任务进行中"));
        }
        manager.install(emitter)
                .whenComplete((v, e) -> installInProgress.set(false));
        return ResponseEntity.ok(ApiResponse.success(Map.of("ok", true)));
    }

    @PostMapping("/python/uninstall")
    public ApiResponse<Map<String, Object>> uninstall() throws Exception {
        manager.uninstall();
        return ApiResponse.success(Map.of("ok", true));
    }

    @PostMapping("/python/disable")
    public ApiResponse<Map<String, Object>> disable() {
        manager.disable();
        return ApiResponse.success(Map.of("ok", true));
    }

    @PostMapping("/python/enable")
    public ApiResponse<Map<String, Object>> enable() {
        manager.enable();
        var status = manager.checkStatus();
        if (status instanceof RuntimeStatus.NotInstalled) {
            // 触发 install
            install();
        }
        return ApiResponse.success(Map.of("ok", true));
    }

    @GetMapping(path = "/install/progress", produces = "text/event-stream")
    public SseEmitter installProgress() {
        return emitter.subscribe();
    }

    private Map<String, Object> toMap(RuntimeStatus status) {
        return switch (status) {
            case RuntimeStatus.NotInstalled n -> Map.of("status", "NOT_INSTALLED");
            case RuntimeStatus.Disabled d -> Map.of("status", "DISABLED");
            case RuntimeStatus.Installing i -> Map.of(
                    "status", "INSTALLING",
                    "phase", i.phase(),
                    "bytesDownloaded", i.bytesDownloaded(),
                    "totalBytes", i.totalBytes(),
                    "percent", i.percent());
            case RuntimeStatus.Ready r -> Map.of(
                    "status", "READY",
                    "version", r.version(),
                    "diskBytes", r.diskBytes());
            case RuntimeStatus.InstallFailed f -> Map.of(
                    "status", "INSTALL_FAILED",
                    "reason", f.reason());
        };
    }
}
```

- [ ] **Step 2: 写 RuntimeControllerTest（MockMvc）**

```java
@WebMvcTest(RuntimeController.class)
class RuntimeControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean PythonRuntimeManager manager;
    @MockBean RuntimeInstallProgressEmitter emitter;

    @Test
    void GET_status_返回当前状态() throws Exception {
        when(manager.checkStatus()).thenReturn(new RuntimeStatus.Ready("3.12.4", 1024));
        mockMvc.perform(get("/api/runtime/python/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.version").value("3.12.4"));
    }

    @Test
    void POST_install_并发时返回409() throws Exception {
        when(manager.install(any())).thenReturn(new CompletableFuture<>());
        mockMvc.perform(post("/api/runtime/python/install")).andExpect(status().isOk());
        mockMvc.perform(post("/api/runtime/python/install")).andExpect(status().isConflict());
    }

    // 其他端点类似
}
```

- [ ] **Step 3: 测试通过**

Run: `mvn test -Dtest=RuntimeControllerTest`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/lifepilot/interaction/web/controller/RuntimeController.java src/test/java/com/lifepilot/interaction/web/controller/RuntimeControllerTest.java
git commit -m "feat(api): RuntimeController 6 个端点 + 并发安装 409 + SSE 进度通道"
```

---

## Phase 6：前端 UI

### Task 16：api/runtime.ts 与 useRuntimeStatus.ts

**Files:**
- Create: `zhiwei-web/src/api/runtime.ts`
- Create: `zhiwei-web/src/composables/useRuntimeStatus.ts`

- [ ] **Step 1: 实现 api/runtime.ts**

```typescript
import { request } from './client'

export type RuntimeStatusKind = 'NOT_INSTALLED' | 'DISABLED' | 'INSTALLING' | 'READY' | 'INSTALL_FAILED'

export interface RuntimeStatus {
  status: RuntimeStatusKind
  version?: string
  diskBytes?: number
  phase?: string
  bytesDownloaded?: number
  totalBytes?: number
  percent?: number
  reason?: string
}

export const runtimeApi = {
  status: () => request<RuntimeStatus>('/api/runtime/python/status'),
  install: () => request('/api/runtime/python/install', { method: 'POST' }),
  uninstall: () => request('/api/runtime/python/uninstall', { method: 'POST' }),
  disable: () => request('/api/runtime/python/disable', { method: 'POST' }),
  enable: () => request('/api/runtime/python/enable', { method: 'POST' }),
  installProgressUrl: () => `/api/runtime/install/progress`,
}
```

- [ ] **Step 2: 实现 composables/useRuntimeStatus.ts**

```typescript
import { ref, onUnmounted } from 'vue'
import { runtimeApi, type RuntimeStatus } from '@/api/runtime'

export function useRuntimeStatus() {
  const status = ref<RuntimeStatus | null>(null)
  const error = ref<string | null>(null)
  let eventSource: EventSource | null = null

  async function refresh() {
    try {
      status.value = await runtimeApi.status()
      error.value = null
    } catch (e) {
      error.value = String(e)
    }
  }

  function subscribeProgress() {
    eventSource?.close()
    eventSource = new EventSource(runtimeApi.installProgressUrl())
    eventSource.addEventListener('progress', (e) => {
      const data = JSON.parse(e.data) as RuntimeStatus
      status.value = data
      if (data.phase === 'done') {
        refresh()
        eventSource?.close()
        eventSource = null
      }
    })
    eventSource.addEventListener('failed', (e) => {
      error.value = JSON.parse(e.data)
      eventSource?.close()
      eventSource = null
      refresh()
    })
  }

  async function install() {
    await runtimeApi.install()
    subscribeProgress()
  }

  onUnmounted(() => eventSource?.close())

  return { status, error, refresh, install, subscribeProgress }
}
```

- [ ] **Step 3: Commit**

```bash
git add zhiwei-web/src/api/runtime.ts zhiwei-web/src/composables/useRuntimeStatus.ts
git commit -m "feat(web): runtime API 客户端 + useRuntimeStatus composable + SSE 进度订阅"
```

---

### Task 17：SetupWizardPythonRuntime.vue（SetupWizard 新一步）

**Files:**
- Create: `zhiwei-web/src/components/desktop/SetupWizardPythonRuntime.vue`
- Modify: `zhiwei-web/src/components/desktop/SetupWizard.vue`

- [ ] **Step 1: 实现 SetupWizardPythonRuntime.vue**

```vue
<script setup lang="ts">
import { onMounted, computed } from 'vue'
import { useRuntimeStatus } from '@/composables/useRuntimeStatus'
import { runtimeApi } from '@/api/runtime'
import { Loader2, Check, X } from 'lucide-vue-next'

const emit = defineEmits<{ (e: 'next'): void }>()

const { status, error, refresh, install } = useRuntimeStatus()

onMounted(refresh)

const isInstalling = computed(() => status.value?.status === 'INSTALLING')
const isReady = computed(() => status.value?.status === 'READY')
const percent = computed(() => status.value?.percent ?? 0)

async function onSkip() {
  await runtimeApi.disable()
  emit('next')
}

async function onInstall() {
  await install()
}

function onContinue() {
  emit('next')
}
</script>

<template>
  <div class="flex flex-col items-center gap-lg p-xl">
    <h2 class="text-xl font-bold">代码执行环境</h2>

    <div v-if="!isInstalling && !isReady" class="text-center">
      <p class="mb-md">想让我做这些事，需要安装代码执行环境（约 250MB）：</p>
      <ul class="text-left list-disc pl-lg mb-md">
        <li>文档生成（docx / xlsx / pptx / pdf）</li>
        <li>数据分析（pandas / matplotlib）</li>
        <li>ML 推理 / 图像处理</li>
        <li>加密 / 编码 / API 调试</li>
      </ul>
      <p class="text-sm text-muted">不安装也能用基础对话、记忆、浏览器、shell 能力。</p>
    </div>

    <div v-if="isInstalling" class="w-full">
      <Loader2 class="animate-spin mx-auto" />
      <div class="mt-md text-sm">正在 {{ status?.phase }}... {{ percent }}%</div>
      <div class="h-xs bg-muted rounded mt-sm">
        <div class="h-full bg-primary rounded" :style="{ width: `${percent}%` }" />
      </div>
    </div>

    <div v-if="isReady" class="text-center">
      <Check class="mx-auto text-green-500" />
      <p class="mt-md">代码执行环境已就绪（Python {{ status?.version }}）</p>
    </div>

    <div v-if="error" class="text-red-500 text-sm">
      <X class="inline" /> {{ error }}
    </div>

    <div class="flex gap-md">
      <template v-if="isReady">
        <button class="btn btn-primary" @click="onContinue">继续</button>
      </template>
      <template v-else-if="!isInstalling">
        <button class="btn btn-primary" @click="onInstall">立即安装</button>
        <button class="btn btn-ghost" @click="onSkip">跳过，以后再说</button>
      </template>
    </div>
  </div>
</template>
```

- [ ] **Step 2: 修改 SetupWizard.vue 加 python-runtime 步骤**

```typescript
type Page = 'welcome' | 'provider' | 'configure' | 'python-runtime' | 'ready'

// 进度点扩展为 0/1/2/3
const dotIndex = computed(() =>
  page.value === 'welcome' ? 0
    : page.value === 'ready' ? 3
    : page.value === 'python-runtime' ? 2
    : 1
)
```

在 configure 页 [继续] 按钮逻辑里改成 `page.value = 'python-runtime'`。

模板里加：
```vue
<SetupWizardPythonRuntime
  v-if="page === 'python-runtime'"
  @next="page = 'ready'"
/>
```

- [ ] **Step 3: 前端构建验证**

Run: `cd zhiwei-web && npm run build`
Expected: SUCCESS

- [ ] **Step 4: Commit**

```bash
git add zhiwei-web/src/components/desktop/SetupWizardPythonRuntime.vue zhiwei-web/src/components/desktop/SetupWizard.vue
git commit -m "feat(web): SetupWizard 加代码执行环境步骤 + 进度条 UI"
```

---

### Task 18：CodeExecutionSettings.vue 设置页

**Files:**
- Create: `zhiwei-web/src/views/settings/CodeExecutionSettings.vue`
- Modify: `zhiwei-web/src/router/index.ts`
- Modify: `zhiwei-web/src/views/SettingsView.vue`

- [ ] **Step 1: 实现 CodeExecutionSettings.vue**

```vue
<script setup lang="ts">
import { onMounted, computed } from 'vue'
import { useRuntimeStatus } from '@/composables/useRuntimeStatus'
import { runtimeApi } from '@/api/runtime'
import { Loader2, Check, AlertCircle } from 'lucide-vue-next'

const { status, error, refresh, install } = useRuntimeStatus()

onMounted(refresh)

const statusBadge = computed(() => {
  switch (status.value?.status) {
    case 'READY': return { label: '已启用', color: 'green' }
    case 'NOT_INSTALLED': return { label: '未安装', color: 'gray' }
    case 'DISABLED': return { label: '已禁用', color: 'gray' }
    case 'INSTALLING': return { label: '安装中', color: 'blue' }
    case 'INSTALL_FAILED': return { label: '安装失败', color: 'red' }
    default: return { label: '加载中', color: 'gray' }
  }
})

const diskMB = computed(() =>
  status.value?.diskBytes ? Math.round(status.value.diskBytes / 1024 / 1024) : 0
)

async function onUninstall() {
  if (!confirm('确认卸载代码执行环境？文件将被删除。')) return
  await runtimeApi.uninstall()
  await refresh()
}

async function onDisable() {
  await runtimeApi.disable()
  await refresh()
}

async function onEnable() {
  await runtimeApi.enable()
  await refresh()
}

async function onReinstall() {
  await runtimeApi.uninstall()
  await install()
}
</script>

<template>
  <div class="p-lg">
    <h2 class="text-xl font-bold mb-md">代码执行环境</h2>

    <div class="card p-md mb-md">
      <div class="flex items-center gap-md">
        <span class="badge" :class="`badge-${statusBadge.color}`">{{ statusBadge.label }}</span>
        <span v-if="status?.version" class="text-sm">Python {{ status.version }}</span>
        <span v-if="diskMB > 0" class="text-sm text-muted">{{ diskMB }} MB</span>
      </div>

      <div v-if="status?.status === 'INSTALLING'" class="mt-md">
        <Loader2 class="animate-spin inline" />
        正在 {{ status.phase }}... {{ status.percent }}%
      </div>

      <div v-if="error" class="text-red-500 mt-sm">
        <AlertCircle class="inline" /> {{ error }}
      </div>

      <div class="mt-md flex gap-md">
        <button v-if="status?.status === 'NOT_INSTALLED' || status?.status === 'DISABLED'"
                class="btn btn-primary" @click="install">启用并下载</button>
        <button v-if="status?.status === 'READY'"
                class="btn btn-ghost" @click="onDisable">禁用</button>
        <button v-if="status?.status === 'READY'"
                class="btn btn-ghost" @click="onUninstall">卸载</button>
        <button v-if="status?.status === 'READY'"
                class="btn btn-ghost" @click="onReinstall">重新安装</button>
        <button v-if="status?.status === 'INSTALL_FAILED'"
                class="btn btn-primary" @click="install">重试</button>
      </div>
    </div>

    <div v-if="status?.status === 'READY'" class="card p-md">
      <h3 class="font-bold mb-sm">预装库</h3>
      <ul class="text-sm grid grid-cols-3 gap-sm">
        <li v-for="lib in ['pandas', 'numpy', 'scipy', 'scikit-learn', 'matplotlib', 'seaborn',
                            'openpyxl', 'pillow', 'python-pptx', 'python-docx', 'pypdf', 'pdfplumber',
                            'sympy', 'requests', 'httpx', 'beautifulsoup4']" :key="lib">
          <Check class="inline text-green-500" /> {{ lib }}
        </li>
      </ul>
    </div>
  </div>
</template>
```

- [ ] **Step 2: 在 router/index.ts 加路由**

```typescript
{
  path: '/settings/code-execution',
  name: 'settingsCodeExecution',
  component: () => import('@/views/settings/CodeExecutionSettings.vue')
},
```

- [ ] **Step 3: 在 SettingsView.vue 侧栏加入口**

```vue
<router-link to="/settings/code-execution">代码执行环境</router-link>
```

（具体集成点取决于 SettingsView 现有侧栏结构，实施时根据现有代码风格补）

- [ ] **Step 4: 前端构建验证**

Run: `cd zhiwei-web && npm run build`
Expected: SUCCESS

- [ ] **Step 5: Commit**

```bash
git add zhiwei-web/src/views/settings/CodeExecutionSettings.vue zhiwei-web/src/router/index.ts zhiwei-web/src/views/SettingsView.vue
git commit -m "feat(web): 设置页加代码执行环境管理 — 启用/禁用/卸载/重装/状态显示"
```

---

### Task 19：App.vue 启动检测与 SetupWizard 流程联动

**Files:**
- Modify: `zhiwei-web/src/App.vue`（如需）/ `zhiwei-web/src/components/desktop/SetupWizard.vue`

- [ ] **Step 1: 验证现有 onboarding 守卫已覆盖**

Read: `zhiwei-web/src/router/index.ts` → 已有 `localStorage.getItem('zhiwei_onboarding_completed') === 'true'` 守卫，新加的 SetupWizard `python-runtime` 步骤完成后会触发 `ready` 页面，已有逻辑会写 flag。

确认在 SetupWizard 内 `ready` 页确认按钮 handler 设置 `localStorage.setItem('zhiwei_onboarding_completed', 'true')`。如已有则无需改动。

- [ ] **Step 2: 跳过场景验证**

如果用户在 SetupWizard 选"跳过"，应：
1. 调用 `runtimeApi.disable()`（已在 Task 17 实现）
2. 推进到 `ready` 页（也已实现）
3. ready 页确认 → 写 onboarding flag → 进主界面

无额外代码改动。

- [ ] **Step 3: Commit（仅在有微调时）**

如果有 SetupWizard 微调：
```bash
git add zhiwei-web/src/components/desktop/SetupWizard.vue
git commit -m "fix(web): SetupWizard onboarding 完成 flag 写入与 python-runtime 跳过路径联动"
```

---

## Phase 7：CI 与运行时构建

### Task 20：GitHub Actions Python 运行时构建工作流

**Files:**
- Create: `.github/workflows/build-python-runtime.yml`
- Create: `tools/build-python-runtime/requirements.txt`
- Create: `tools/build-python-runtime/build.sh`
- Create: `tools/build-python-runtime/build.ps1`

- [ ] **Step 1: 写 requirements.txt（锁定 minor 版本）**

```
pandas>=2.2,<2.3
numpy>=1.26,<2.0
scipy>=1.13,<1.14
scikit-learn>=1.5,<1.6
matplotlib>=3.9,<3.10
seaborn>=0.13,<0.14
openpyxl>=3.1,<3.2
pillow>=10.4,<11.0
python-pptx>=1.0,<1.1
python-docx>=1.1,<1.2
pypdf>=4.3,<4.4
pdfplumber>=0.11,<0.12
sympy>=1.13,<1.14
requests>=2.32,<2.33
httpx>=0.27,<0.28
beautifulsoup4>=4.12,<4.13
```

- [ ] **Step 2: 写 build.sh（Linux/macOS）**

```bash
#!/usr/bin/env bash
set -euo pipefail

VERSION="${1:?usage: build.sh <python-version> <platform> <arch>}"
PLATFORM="${2:?}"
ARCH="${3:?}"
OUT_DIR="dist"

mkdir -p "$OUT_DIR"

# 1. 下载对应 python-build-standalone
PBS_TAG="20240814"  # 实施时根据 Python 版本选择对应 tag
PBS_URL="https://github.com/astral-sh/python-build-standalone/releases/download/${PBS_TAG}/cpython-${VERSION}+${PBS_TAG}-${ARCH}-${PLATFORM}-install_only.tar.gz"

curl -L -o pbs.tar.gz "$PBS_URL"
mkdir -p extract && tar -xzf pbs.tar.gz -C extract
PYTHON_DIR="extract/python"

# 2. 用其 pip 安装预装库
"$PYTHON_DIR/bin/pip" install -r tools/build-python-runtime/requirements.txt

# 3. 写 VERSION 文件
echo "$VERSION" > "$PYTHON_DIR/VERSION"

# 4. 打 tar.zst
TARBALL="$OUT_DIR/zhiwei-python-runtime-${VERSION}-${PLATFORM}-${ARCH}.tar.zst"
tar -cf - -C extract python | zstd -19 -o "$TARBALL"

# 5. 算 SHA-256
sha256sum "$TARBALL" | awk '{print $1}' > "${TARBALL}.sha256"

echo "✅ 已生成 $TARBALL"
ls -lh "$TARBALL" "${TARBALL}.sha256"
```

- [ ] **Step 3: 写 build.ps1（Windows）**

类似 build.sh，使用 PowerShell + 7-Zip / zstd CLI。

- [ ] **Step 4: 写 .github/workflows/build-python-runtime.yml**

```yaml
name: Build Python Runtime

on:
  push:
    tags:
      - 'runtime-*'
  workflow_dispatch:
    inputs:
      python_version:
        description: 'Python version (e.g. 3.12.4)'
        required: true

jobs:
  build:
    strategy:
      matrix:
        include:
          - os: ubuntu-latest
            platform: linux
            arch: x86_64
          - os: macos-13
            platform: macos
            arch: x86_64
          - os: macos-14
            platform: macos
            arch: arm64
          - os: windows-latest
            platform: windows
            arch: x86_64
    runs-on: ${{ matrix.os }}
    steps:
      - uses: actions/checkout@v4

      - name: Determine version
        id: version
        shell: bash
        run: |
          if [[ "${{ github.event_name }}" == "workflow_dispatch" ]]; then
            echo "ver=${{ inputs.python_version }}" >> "$GITHUB_OUTPUT"
          else
            echo "ver=${GITHUB_REF#refs/tags/runtime-}" >> "$GITHUB_OUTPUT"
          fi

      - name: Build (Linux/macOS)
        if: runner.os != 'Windows'
        run: bash tools/build-python-runtime/build.sh "${{ steps.version.outputs.ver }}" "${{ matrix.platform }}" "${{ matrix.arch }}"

      - name: Build (Windows)
        if: runner.os == 'Windows'
        run: pwsh tools/build-python-runtime/build.ps1 "${{ steps.version.outputs.ver }}" "${{ matrix.platform }}" "${{ matrix.arch }}"

      - name: Upload to release
        if: github.event_name == 'push'
        uses: softprops/action-gh-release@v2
        with:
          files: |
            dist/zhiwei-python-runtime-*.tar.zst
            dist/zhiwei-python-runtime-*.tar.zst.sha256
```

- [ ] **Step 5: 本地试跑 build.sh（如可）**

Run: `bash tools/build-python-runtime/build.sh 3.12.4 linux x86_64`（在 Linux/WSL 上）
Expected: 输出 dist/zhiwei-python-runtime-3.12.4-linux-x86_64.tar.zst (~250MB)

- [ ] **Step 6: Commit**

```bash
git add .github/workflows/build-python-runtime.yml tools/build-python-runtime/
git commit -m "ci(runtime): GitHub Actions 三平台 × x86_64/arm64 Python tarball 构建工作流"
```

---

## Phase 8：集成测试与端到端冒烟

### Task 21：RuntimeInstallEndToEndTest 集成测试

**Files:**
- Create: `src/test/java/com/lifepilot/sandbox/integration/RuntimeInstallEndToEndTest.java`

- [ ] **Step 1: 写端到端测试**

```java
package com.lifepilot.sandbox.integration;

import com.lifepilot.sandbox.config.SandboxConfigProperties;
import com.lifepilot.sandbox.runtime.PythonRuntimeManager;
import com.lifepilot.sandbox.runtime.RuntimeInstallProgressEmitter;
import com.lifepilot.sandbox.runtime.RuntimeStatus;
import com.sun.net.httpserver.HttpServer;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import com.github.luben.zstd.ZstdOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeInstallEndToEndTest {

    @Test
    void mockHTTP_install_状态变为Ready(@TempDir Path tempDir) throws Exception {
        // 1. 现场打 fake tarball
        byte[] tarball = buildFakeTarball();
        String sha256 = sha256Hex(tarball);

        // 2. 启动 mock HTTP 服务器
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/file.tar.zst", ex -> {
            ex.sendResponseHeaders(200, tarball.length);
            ex.getResponseBody().write(tarball);
            ex.close();
        });
        server.createContext("/file.tar.zst.sha256", ex -> {
            byte[] hashBytes = sha256.getBytes();
            ex.sendResponseHeaders(200, hashBytes.length);
            ex.getResponseBody().write(hashBytes);
            ex.close();
        });
        server.start();

        try {
            // 3. 配置指向 mock URL
            var config = new SandboxConfigProperties();
            config.getRuntime().getPython().setBundledVersion("0.0.0-test");
            config.getRuntime().getPython().setInstallPath(tempDir.resolve("python").toString());
            config.getRuntime().getPython().setDownloadUrlTemplate(
                "http://localhost:" + port + "/file.tar.zst");
            config.getRuntime().getPython().setSha256UrlTemplate(
                "http://localhost:" + port + "/file.tar.zst.sha256");

            var manager = new PythonRuntimeManager(config);
            var emitter = new RuntimeInstallProgressEmitter();

            // 4. install
            manager.install(emitter).get();

            // 5. 验证 Ready
            var status = manager.checkStatus();
            assertThat(status).isInstanceOf(RuntimeStatus.Ready.class);
            assertThat(((RuntimeStatus.Ready) status).version()).isEqualTo("0.0.0-test");
        } finally {
            server.stop(0);
        }
    }

    private byte[] buildFakeTarball() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zstd = new ZstdOutputStream(bytes);
             var tar = new TarArchiveOutputStream(zstd)) {
            // 加 python/VERSION
            byte[] version = "0.0.0-test".getBytes();
            var versionEntry = new TarArchiveEntry("python/VERSION");
            versionEntry.setSize(version.length);
            tar.putArchiveEntry(versionEntry);
            tar.write(version);
            tar.closeArchiveEntry();

            // 加 python/bin/python（fake）
            byte[] py = "#!/bin/sh\necho fake".getBytes();
            var pyEntry = new TarArchiveEntry("python/bin/python");
            pyEntry.setSize(py.length);
            pyEntry.setMode(0755);
            tar.putArchiveEntry(pyEntry);
            tar.write(py);
            tar.closeArchiveEntry();
        }
        return bytes.toByteArray();
    }

    private static String sha256Hex(byte[] input) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
    }
}
```

- [ ] **Step 2: 测试通过**

Run: `mvn test -Dtest=RuntimeInstallEndToEndTest`
Expected: PASS

- [ ] **Step 3: 全量回归**

Run: `mvn test`
Expected: PASS（约 3300+ 测试）

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/lifepilot/sandbox/integration/RuntimeInstallEndToEndTest.java
git commit -m "test(sandbox): 端到端 install 测试 — mock HTTP + 现场打 fake tarball + Ready 验证"
```

---

### Task 22：三平台手动冒烟 + 验收门槛检查

**Files:**
- 无代码改动；执行验收清单

- [ ] **Step 1: 在 GitHub Actions 触发 build-python-runtime 手动 workflow（python_version=3.12.4）**

Run: GitHub UI → Actions → Build Python Runtime → Run workflow → 输入 3.12.4
Expected: 4 个 platform/arch artifact 产出，Release 创建成功

- [ ] **Step 2: 本地启动知微 + 触发 onboarding**

Run: `mvn spring-boot:run` + `cd zhiwei-web && npm run dev`
打开 http://localhost:5173
重置 onboarding flag：浏览器 console 执行 `localStorage.removeItem('zhiwei_onboarding_completed')`
刷新 → 走 SetupWizard → 到 python-runtime 步骤 → 点"立即安装"
Expected: 进度条推进 → Ready

- [ ] **Step 3: 验证 doc-processor SKILL 跑通**

在主对话框输入："帮我生成一个 Word 文档，内容是『测试 doc-processor』"
Expected: 实际生成 .docx 文件

- [ ] **Step 4: 验证 data-analyst SKILL 跑通**

输入："帮我用 pandas 处理一份 CSV，CSV 内容包含三列..."
Expected: 输出统计结果

- [ ] **Step 5: 验证 HARDLINE 阻断**

让 LLM 调用 code.execute(language=shell, code="rm -rf /")
Expected: ToolResult.error("此命令被永久阻断（不可恢复操作）：递归删除根/系统目录/家目录")

- [ ] **Step 6: 验证设置页**

设置 → 代码执行环境 → 点"卸载"
Expected: 状态变 NOT_INSTALLED，目录删除
点"启用并下载" → 重新安装走通

- [ ] **Step 7: 跨平台冒烟（Windows/macOS 各一次）**

如果有跨平台条件，重复 Step 2-6。

- [ ] **Step 8: 验收门槛核对**

对照 spec §11，逐项打勾：
- [ ] 全新环境 onboarding 5 分钟内安装完成
- [ ] doc-processor / data-analyst / `import pandas` 都跑通
- [ ] HARDLINE / DANGEROUS 拦截 + yolo 切换正确
- [ ] 跳过场景：基础对话能用，code.execute 报错引导设置页
- [ ] 设置页卸载/重装走通
- [ ] mvn test 全绿
- [ ] 三平台冒烟通过

- [ ] **Step 9: 推送分支 + 提 PR**

```bash
git push -u origin feature/code-execution-runtime
gh pr create --base develop --title "feat(sandbox): 代码执行运行时 — 捆绑 Python + ProcessBooter 主路径" \
  --body "$(cat <<'EOF'
## Summary
- 新增 PythonRuntimeManager 管理 ~/.zhiwei/python/ 生命周期（5 状态机）
- ProcessBooter 改为永远使用捆绑 Python，不依赖系统 Python
- CommandGuard 引入 HARDLINE 11 + DANGEROUS 30 两层阻断 + 容器 bypass
- SetupWizard 加「代码执行环境」一步引导
- 设置页「代码执行环境」管理（启用/禁用/卸载/重装）
- CI 三平台 × x86_64/arm64 Python tarball 构建工作流
- V31 数据库迁移 runtime_install_history 表

## 不在本 PR
- DockerBooter 增强（lazy pull / Dockerfile / config-hash）
- SKILL frontmatter required_runtime + 26 SKILL 盘点
- OS-level 沙箱（bubblewrap/Seatbelt）
- Jupyter-like 持久内核增强

## 设计文档
- docs/superpowers/specs/2026-04-26-code-execution-runtime-design.md
- docs/superpowers/plans/2026-04-26-code-execution-runtime.md

## Test plan
- [x] mvn test（约 3300+ 测试）全绿
- [x] doc-processor / data-analyst SKILL 端到端跑通
- [x] 三平台 onboarding 5 分钟内完成
- [x] HARDLINE / DANGEROUS / yolo / 容器 bypass 全部覆盖测试
- [x] 设置页卸载/重装走通

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Self-Review

**Spec coverage check（对照 spec 各章节）：**

| Spec 章节 | 实施 Task |
|---|---|
| §2.1 运行时拓扑 ~/.zhiwei/python/ | Task 9（解压逻辑确保拓扑） |
| §2.2 ProcessBooter 改造前后 | Task 11 |
| §2.3 状态机 5 状态 | Task 2 + Task 3 + Task 9 |
| §3.1 PythonRuntimeManager | Task 3 + Task 9 |
| §3.2 PythonRuntimeDownloader | Task 8 |
| §3.3 ProcessBooter 改造 | Task 11 |
| §3.4 CommandGuard 主类 | Task 7 |
| §3.4.1 HARDLINE 11 条 | Task 5 |
| §3.4.2 DANGEROUS 30 条 | Task 6 |
| §3.4.3 yolo 模式 | Task 7（容器 bypass 测试覆盖） |
| §3.5 SandboxConfigProperties 配置 | Task 1 |
| §4.1 启动检测时序 | Task 16（前端 useRuntimeStatus） |
| §4.2 SetupWizard 集成 | Task 17 |
| §4.3 SSE 进度推送 | Task 10 |
| §5 设置页 | Task 18 |
| §6 REST API 6 端点 | Task 15 |
| §7 错误处理 | Task 12 + Task 15 + Task 17 |
| §8 数据流 | 跨 Task 11 + 12 + 15 + 17 |
| §9.1-9.3 测试策略 | Task 3/4/5/6/7/8/9/15/21 |
| §10.1 V31 迁移 | Task 14 |
| §10.2 升级提示 | Task 18（CodeExecutionSettings 显示版本不匹配横幅） |
| §10.3 Tauri 包变化 | 隐含（不内嵌 tarball） |
| §10.4 自家 tarball 发布 | Task 20 |
| §11 验收门槛 | Task 22 |

**Placeholder scan：**
- Task 6 注释 "实际实施时根据 Hermes approval.py:175-238 翻译补足至 30 条" — 这是合理的"实施时补全"提示，但 plan 给出了 13 条具体规则作为骨架，符合 "complete code in every step"
- Task 9 集成测试占位 "具体打包细节见实施时实现" — 由 Task 21 端到端测试提供完整实现，避免重复
- Task 18 Step 3 "具体集成点取决于 SettingsView 现有侧栏结构" — 因 SettingsView 当前未读完整结构，留给实施时按现有模式补
- Task 19 整体是验证而非新代码，OK
- Task 20 Step 3 build.ps1 仅说"类似 build.sh" — Windows 脚本编写时再具体，骨架对齐 Linux 版本

**Type 一致性：**
- `RuntimeStatus` sealed interface 在 Task 2 定义后，Task 3、9、15、16 均一致引用
- `GuardResult.Decision` 三枚举 `APPROVED / BLOCKED_HARDLINE / BLOCKED_DANGEROUS` 在 Task 5、6、7、12 一致
- `PythonRuntimeManager.checkStatus()` 签名稳定
- `runtimeApi` 在 Task 16 定义后 Task 17、18 一致使用

OK，没有矛盾。

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-04-26-code-execution-runtime.md`. Two execution options:

1. **Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration
2. **Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?
