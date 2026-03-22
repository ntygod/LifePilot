package com.lifepilot.sandbox.validator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.lifepilot.sandbox.config.SandboxConfigProperties;
import com.lifepilot.sandbox.model.Language;
import com.lifepilot.sandbox.model.ValidationResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CodeValidator} 单元测试。
 *
 * <p>覆盖三种语言的危险模式检测、严重程度分级、reject-critical 策略和禁用场景。</p>
 *
 * @author zsg
 * @since 2026-03-22
 */
class CodeValidatorTest {

    private SandboxConfigProperties config;
    private CodeValidator validator;

    @BeforeEach
    void setUp() {
        config = new SandboxConfigProperties();
        validator = new CodeValidator(config);
    }

    // ─────────────────────────────────────────────
    //  禁用场景
    // ─────────────────────────────────────────────

    @Test
    void 预检禁用时直接通过() {
        config.getValidator().setEnabled(false);
        validator = new CodeValidator(config);

        ValidationResult result = validator.validate(Language.PYTHON, "os.system('rm -rf /')");

        assertThat(result.passed()).isTrue();
        assertThat(result.violations()).isEmpty();
    }

    // ─────────────────────────────────────────────
    //  Python 规则
    // ─────────────────────────────────────────────

    @Nested
    class Python规则 {

        @Test
        void 安全代码通过() {
            ValidationResult result = validator.validate(Language.PYTHON,
                    "x = 1 + 2\nprint(x)");
            assertThat(result.passed()).isTrue();
            assertThat(result.violations()).isEmpty();
        }

        @Test
        void os_system被拦截() {
            ValidationResult result = validator.validate(Language.PYTHON,
                    "import os\nos.system('whoami')");
            assertThat(result.passed()).isFalse();
            assertThat(result.violations()).anyMatch(v ->
                    "CRITICAL".equals(v.severity()) && v.pattern().contains("os.system"));
        }

        @Test
        void subprocess_run被拦截() {
            ValidationResult result = validator.validate(Language.PYTHON,
                    "import subprocess\nsubprocess.run(['ls'])");
            assertThat(result.passed()).isFalse();
            assertThat(result.violations()).anyMatch(v ->
                    v.pattern().contains("subprocess"));
        }

        @Test
        void subprocess_Popen被拦截() {
            ValidationResult result = validator.validate(Language.PYTHON,
                    "subprocess.Popen(['ls'])");
            assertThat(result.passed()).isFalse();
        }

        @Test
        void os_popen被拦截() {
            ValidationResult result = validator.validate(Language.PYTHON,
                    "os.popen('ls')");
            assertThat(result.passed()).isFalse();
        }

        @Test
        void eval被拦截() {
            ValidationResult result = validator.validate(Language.PYTHON,
                    "eval('1+1')");
            assertThat(result.passed()).isFalse();
            assertThat(result.violations()).anyMatch(v ->
                    "CRITICAL".equals(v.severity()) && v.pattern().equals("eval"));
        }

        @Test
        void exec被拦截() {
            ValidationResult result = validator.validate(Language.PYTHON,
                    "exec('print(1)')");
            assertThat(result.passed()).isFalse();
        }

        @Test
        void __import__被拦截() {
            ValidationResult result = validator.validate(Language.PYTHON,
                    "__import__('os')");
            assertThat(result.passed()).isFalse();
        }

        @Test
        void getattr反射访问os被拦截() {
            ValidationResult result = validator.validate(Language.PYTHON,
                    "getattr(os, 'system')('whoami')");
            assertThat(result.passed()).isFalse();
            assertThat(result.violations()).anyMatch(v ->
                    v.pattern().equals("getattr"));
        }

        @Test
        void base64_b64decode被标记为HIGH() {
            ValidationResult result = validator.validate(Language.PYTHON,
                    "import base64\nbase64.b64decode('dGVzdA==')");
            // base64 是 HIGH 不是 CRITICAL，reject-critical 下仍通过
            assertThat(result.passed()).isTrue();
            assertThat(result.violations()).anyMatch(v ->
                    "HIGH".equals(v.severity()) && v.pattern().contains("base64"));
        }

        @Test
        void ctypes被拦截() {
            ValidationResult result = validator.validate(Language.PYTHON,
                    "ctypes.cdll.LoadLibrary('libc.so')");
            assertThat(result.passed()).isFalse();
        }

        @Test
        void shutil_rmtree被标记为HIGH() {
            ValidationResult result = validator.validate(Language.PYTHON,
                    "shutil.rmtree('/tmp/test')");
            assertThat(result.passed()).isTrue(); // HIGH 不阻止
            assertThat(result.violations()).anyMatch(v ->
                    "HIGH".equals(v.severity()));
        }

        @Test
        void 读取proc被标记为HIGH() {
            ValidationResult result = validator.validate(Language.PYTHON,
                    "open('/proc/cpuinfo')");
            assertThat(result.passed()).isTrue(); // HIGH 不阻止
            assertThat(result.violations()).anyMatch(v ->
                    v.pattern().contains("/proc/"));
        }

        @Test
        void 网络请求被标记为MEDIUM() {
            ValidationResult result = validator.validate(Language.PYTHON,
                    "requests.get('http://example.com')");
            assertThat(result.passed()).isTrue(); // MEDIUM 不阻止
            assertThat(result.violations()).anyMatch(v ->
                    "MEDIUM".equals(v.severity()));
        }

        @Test
        void httpx被标记为MEDIUM() {
            ValidationResult result = validator.validate(Language.PYTHON,
                    "httpx.get('http://example.com')");
            assertThat(result.passed()).isTrue();
            assertThat(result.violations()).anyMatch(v ->
                    v.pattern().equals("httpx"));
        }

        @Test
        void 违规行号正确() {
            ValidationResult result = validator.validate(Language.PYTHON,
                    "x = 1\ny = 2\nos.system('ls')");
            assertThat(result.violations()).anyMatch(v -> v.lineNumber() == 3);
        }
    }

    // ─────────────────────────────────────────────
    //  JavaScript 规则
    // ─────────────────────────────────────────────

    @Nested
    class JavaScript规则 {

        @Test
        void 安全代码通过() {
            ValidationResult result = validator.validate(Language.JAVASCRIPT,
                    "const x = 1 + 2;\nconsole.log(x);");
            assertThat(result.passed()).isTrue();
        }

        @Test
        void require_child_process被拦截() {
            ValidationResult result = validator.validate(Language.JAVASCRIPT,
                    "const cp = require('child_process');");
            assertThat(result.passed()).isFalse();
        }

        @Test
        void child_process_execSync被拦截() {
            ValidationResult result = validator.validate(Language.JAVASCRIPT,
                    "child_process.execSync('ls')");
            assertThat(result.passed()).isFalse();
        }

        @Test
        void child_process_spawn被拦截() {
            ValidationResult result = validator.validate(Language.JAVASCRIPT,
                    "child_process.spawn('ls')");
            assertThat(result.passed()).isFalse();
        }

        @Test
        void eval被拦截() {
            ValidationResult result = validator.validate(Language.JAVASCRIPT,
                    "eval('1+1')");
            assertThat(result.passed()).isFalse();
        }

        @Test
        void new_Function被拦截() {
            ValidationResult result = validator.validate(Language.JAVASCRIPT,
                    "new Function('return 1')()");
            assertThat(result.passed()).isFalse();
        }

        @Test
        void 动态import_child_process被拦截() {
            ValidationResult result = validator.validate(Language.JAVASCRIPT,
                    "import('child_process').then(cp => cp.exec('ls'))");
            assertThat(result.passed()).isFalse();
        }

        @Test
        void fs_rmSync被标记为HIGH() {
            ValidationResult result = validator.validate(Language.JAVASCRIPT,
                    "fs.rmSync('/tmp/test', {recursive: true})");
            assertThat(result.passed()).isTrue(); // HIGH 不阻止
            assertThat(result.violations()).anyMatch(v -> "HIGH".equals(v.severity()));
        }

        @Test
        void fetch被标记为MEDIUM() {
            ValidationResult result = validator.validate(Language.JAVASCRIPT,
                    "fetch('http://example.com')");
            assertThat(result.passed()).isTrue();
            assertThat(result.violations()).anyMatch(v -> "MEDIUM".equals(v.severity()));
        }
    }

    // ─────────────────────────────────────────────
    //  Shell 规则
    // ─────────────────────────────────────────────

    @Nested
    class Shell规则 {

        @Test
        void 安全代码通过() {
            ValidationResult result = validator.validate(Language.SHELL,
                    "echo hello\nls -la");
            assertThat(result.passed()).isTrue();
            assertThat(result.violations()).isEmpty();
        }

        @Test
        void sudo被拦截() {
            ValidationResult result = validator.validate(Language.SHELL,
                    "sudo apt install vim");
            assertThat(result.passed()).isFalse();
        }

        @Test
        void mkfs被拦截() {
            ValidationResult result = validator.validate(Language.SHELL,
                    "mkfs.ext4 /dev/sda1");
            assertThat(result.passed()).isFalse();
        }

        @Test
        void dd被拦截() {
            ValidationResult result = validator.validate(Language.SHELL,
                    "dd if=/dev/zero of=/dev/sda");
            assertThat(result.passed()).isFalse();
        }

        @Test
        void chmod_777被拦截() {
            ValidationResult result = validator.validate(Language.SHELL,
                    "chmod 777 /etc/passwd");
            assertThat(result.passed()).isFalse();
        }

        @Test
        void su切换用户被拦截() {
            ValidationResult result = validator.validate(Language.SHELL,
                    "su - root");
            assertThat(result.passed()).isFalse();
        }

        @Test
        void su不误报普通单词() {
            // "result" 等包含 su 子串的单词不应被匹配
            ValidationResult result = validator.validate(Language.SHELL,
                    "echo result\necho sum");
            assertThat(result.passed()).isTrue();
            assertThat(result.violations()).isEmpty();
        }

        @Test
        void 写入设备文件被拦截() {
            ValidationResult result = validator.validate(Language.SHELL,
                    "echo data > /dev/sda");
            assertThat(result.passed()).isFalse();
        }

        @Test
        void curl被标记为MEDIUM() {
            ValidationResult result = validator.validate(Language.SHELL,
                    "curl http://example.com");
            assertThat(result.passed()).isTrue();
            assertThat(result.violations()).anyMatch(v -> "MEDIUM".equals(v.severity()));
        }

        @Test
        void netcat被标记为MEDIUM() {
            ValidationResult result = validator.validate(Language.SHELL,
                    "ncat -l 8080");
            assertThat(result.passed()).isTrue();
            assertThat(result.violations()).anyMatch(v -> v.pattern().equals("nc"));
        }
    }

    // ─────────────────────────────────────────────
    //  reject-critical 策略
    // ─────────────────────────────────────────────

    @Nested
    class RejectCritical策略 {

        @Test
        void rejectCritical关闭时_CRITICAL违规仍通过() {
            config.getValidator().setRejectCritical(false);
            validator = new CodeValidator(config);

            ValidationResult result = validator.validate(Language.PYTHON,
                    "os.system('whoami')");

            assertThat(result.passed()).isTrue();
            assertThat(result.violations()).isNotEmpty();
            assertThat(result.violations()).anyMatch(v -> "CRITICAL".equals(v.severity()));
        }

        @Test
        void rejectCritical开启时_仅HIGH违规通过() {
            ValidationResult result = validator.validate(Language.PYTHON,
                    "shutil.rmtree('/tmp')");

            assertThat(result.passed()).isTrue();
            assertThat(result.violations()).allMatch(v -> !"CRITICAL".equals(v.severity()));
        }

        @Test
        void 多个违规项全部记录() {
            ValidationResult result = validator.validate(Language.PYTHON,
                    "os.system('ls')\neval('1+1')\nexec('pass')");

            assertThat(result.passed()).isFalse();
            assertThat(result.violations().size()).isGreaterThanOrEqualTo(3);
        }
    }
}
