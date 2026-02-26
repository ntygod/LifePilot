package com.lifepilot.sandbox.validator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lifepilot.sandbox.config.SandboxConfigProperties;
import com.lifepilot.sandbox.model.Language;
import com.lifepilot.sandbox.model.ValidationResult;
import com.lifepilot.sandbox.model.Violation;

/**
 * 代码预检器 — 静态模式匹配检测危险操作。
 *
 * <p>通过正则表达式按语言和严重程度组织检测规则，逐行扫描代码并记录所有违规项。
 * 支持三级严重程度：CRITICAL（系统命令执行）、HIGH（文件系统破坏）、MEDIUM（网络访问）。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
public class CodeValidator {

    private static final Logger log = LoggerFactory.getLogger(CodeValidator.class);

    private final SandboxConfigProperties config;

    /** 按语言组织的检测规则 */
    private final Map<Language, List<DangerousPattern>> patternsByLanguage;

    /**
     * 创建代码预检器。
     *
     * @param config 沙箱配置属性
     */
    public CodeValidator(SandboxConfigProperties config) {
        this.config = config;
        this.patternsByLanguage = buildPatterns();
    }

    /**
     * 校验代码是否包含危险模式。
     *
     * @param language 编程语言
     * @param code     待校验的代码
     * @return 校验结果，包含通过状态和违规列表
     */
    public ValidationResult validate(Language language, String code) {
        // validator.enabled=false 时直接返回 ok
        if (!config.getValidator().isEnabled()) {
            log.debug("代码预检已禁用，跳过检测");
            return ValidationResult.ok();
        }

        List<DangerousPattern> patterns = patternsByLanguage.getOrDefault(language, List.of());
        List<Violation> violations = new ArrayList<>();

        String[] lines = code.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineNumber = i + 1;
            for (DangerousPattern dp : patterns) {
                if (dp.pattern().matcher(line).find()) {
                    violations.add(new Violation(
                            dp.name(),
                            dp.description(),
                            dp.severity(),
                            lineNumber
                    ));
                }
            }
        }

        if (violations.isEmpty()) {
            log.debug("代码预检通过: language={}", language);
            return ValidationResult.ok();
        }

        // reject-critical 判定逻辑
        boolean hasCritical = violations.stream()
                .anyMatch(v -> "CRITICAL".equals(v.severity()));

        if (config.getValidator().isRejectCritical() && hasCritical) {
            log.warn("代码预检拒绝: language={}, violations={}", language, violations.size());
            return ValidationResult.rejected(violations);
        }

        // 存在违规但不阻止（非 CRITICAL 或 reject-critical=false）
        log.info("代码预检通过（含警告）: language={}, violations={}", language, violations.size());
        return new ValidationResult(true, List.copyOf(violations));
    }

    // ==================== 内部数据结构 ====================

    /**
     * 危险模式定义。
     *
     * @param name        模式名称（用于 Violation.pattern）
     * @param pattern     正则表达式
     * @param description 违规描述
     * @param severity    严重程度（CRITICAL / HIGH / MEDIUM）
     */
    private record DangerousPattern(
            String name,
            Pattern pattern,
            String description,
            String severity
    ) {}

    // ==================== 模式构建 ====================

    /**
     * 构建所有语言的检测规则。
     */
    private static Map<Language, List<DangerousPattern>> buildPatterns() {
        return Map.of(
                Language.PYTHON, buildPythonPatterns(),
                Language.JAVASCRIPT, buildJavaScriptPatterns(),
                Language.SHELL, buildShellPatterns()
        );
    }

    /**
     * 构建 Python 危险模式。
     */
    private static List<DangerousPattern> buildPythonPatterns() {
        List<DangerousPattern> patterns = new ArrayList<>();

        // CRITICAL — 系统命令执行
        patterns.add(new DangerousPattern(
                "os.system", Pattern.compile("\\bos\\.system\\s*\\("),
                "调用 os.system() 执行系统命令", "CRITICAL"));
        patterns.add(new DangerousPattern(
                "subprocess.call", Pattern.compile("\\bsubprocess\\.call\\s*\\("),
                "调用 subprocess.call() 执行子进程", "CRITICAL"));
        patterns.add(new DangerousPattern(
                "os.exec*", Pattern.compile("\\bos\\.exec[a-z]*\\s*\\("),
                "调用 os.exec*() 执行系统命令", "CRITICAL"));
        patterns.add(new DangerousPattern(
                "eval", Pattern.compile("\\beval\\s*\\("),
                "调用 eval() 动态执行代码", "CRITICAL"));
        patterns.add(new DangerousPattern(
                "exec", Pattern.compile("\\bexec\\s*\\("),
                "调用 exec() 动态执行代码", "CRITICAL"));
        patterns.add(new DangerousPattern(
                "compile", Pattern.compile("\\bcompile\\s*\\("),
                "调用 compile() 编译动态代码", "CRITICAL"));
        patterns.add(new DangerousPattern(
                "__import__", Pattern.compile("__import__\\s*\\("),
                "调用 __import__() 动态导入模块", "CRITICAL"));

        // HIGH — 文件系统破坏
        patterns.add(new DangerousPattern(
                "os.remove", Pattern.compile("\\bos\\.remove\\s*\\("),
                "调用 os.remove() 删除文件", "HIGH"));
        patterns.add(new DangerousPattern(
                "shutil.rmtree", Pattern.compile("\\bshutil\\.rmtree\\s*\\("),
                "调用 shutil.rmtree() 递归删除目录", "HIGH"));
        patterns.add(new DangerousPattern(
                "open('/etc/')", Pattern.compile("\\bopen\\s*\\(\\s*['\"]\\s*/etc/"),
                "尝试打开系统配置文件 /etc/", "HIGH"));

        // MEDIUM — 网络访问
        patterns.add(new DangerousPattern(
                "urllib.request", Pattern.compile("\\burllib\\.request\\b"),
                "使用 urllib.request 进行网络请求", "MEDIUM"));
        patterns.add(new DangerousPattern(
                "requests.get", Pattern.compile("\\brequests\\.get\\s*\\("),
                "调用 requests.get() 进行 HTTP 请求", "MEDIUM"));
        patterns.add(new DangerousPattern(
                "socket.connect", Pattern.compile("\\bsocket\\.connect\\s*\\("),
                "调用 socket.connect() 建立网络连接", "MEDIUM"));

        return List.copyOf(patterns);
    }

    /**
     * 构建 JavaScript 危险模式。
     */
    private static List<DangerousPattern> buildJavaScriptPatterns() {
        List<DangerousPattern> patterns = new ArrayList<>();

        // CRITICAL — 系统命令执行
        patterns.add(new DangerousPattern(
                "child_process.exec", Pattern.compile("\\bchild_process\\.exec\\s*\\("),
                "调用 child_process.exec() 执行系统命令", "CRITICAL"));
        patterns.add(new DangerousPattern(
                "child_process.spawn", Pattern.compile("\\bchild_process\\.spawn\\s*\\("),
                "调用 child_process.spawn() 执行子进程", "CRITICAL"));
        patterns.add(new DangerousPattern(
                "require('child_process')", Pattern.compile("\\brequire\\s*\\(\\s*['\"]child_process['\"]\\s*\\)"),
                "导入 child_process 模块", "CRITICAL"));

        // HIGH — 文件系统破坏
        patterns.add(new DangerousPattern(
                "fs.unlinkSync", Pattern.compile("\\bfs\\.unlinkSync\\s*\\("),
                "调用 fs.unlinkSync() 同步删除文件", "HIGH"));
        patterns.add(new DangerousPattern(
                "fs.rmdirSync", Pattern.compile("\\bfs\\.rmdirSync\\s*\\("),
                "调用 fs.rmdirSync() 同步删除目录", "HIGH"));
        patterns.add(new DangerousPattern(
                "fs.writeFileSync('/')", Pattern.compile("\\bfs\\.writeFileSync\\s*\\(\\s*['\"]\\s*/"),
                "尝试写入系统根路径", "HIGH"));

        // MEDIUM — 网络访问
        patterns.add(new DangerousPattern(
                "http.request", Pattern.compile("\\bhttp\\.request\\s*\\("),
                "调用 http.request() 进行 HTTP 请求", "MEDIUM"));
        patterns.add(new DangerousPattern(
                "fetch", Pattern.compile("\\bfetch\\s*\\("),
                "调用 fetch() 进行网络请求", "MEDIUM"));
        patterns.add(new DangerousPattern(
                "net.connect", Pattern.compile("\\bnet\\.connect\\s*\\("),
                "调用 net.connect() 建立网络连接", "MEDIUM"));

        return List.copyOf(patterns);
    }

    /**
     * 构建 Shell 危险模式。
     */
    private static List<DangerousPattern> buildShellPatterns() {
        List<DangerousPattern> patterns = new ArrayList<>();

        // CRITICAL — 系统破坏性命令
        patterns.add(new DangerousPattern(
                "rm -rf /", Pattern.compile("\\brm\\s+(-[a-zA-Z]*r[a-zA-Z]*f|(-[a-zA-Z]*f[a-zA-Z]*r))[a-zA-Z]*\\s+/(?!\\S)"),
                "执行 rm -rf / 删除根目录", "CRITICAL"));
        patterns.add(new DangerousPattern(
                "dd if=", Pattern.compile("\\bdd\\s+if="),
                "执行 dd 命令直接操作磁盘", "CRITICAL"));
        patterns.add(new DangerousPattern(
                "mkfs", Pattern.compile("\\bmkfs\\b"),
                "执行 mkfs 格式化文件系统", "CRITICAL"));
        patterns.add(new DangerousPattern(
                "fork bomb", Pattern.compile(":\\(\\)\\s*\\{\\s*:\\|:\\s*&\\s*\\}\\s*;\\s*:"),
                "Fork bomb 拒绝服务攻击", "CRITICAL"));
        patterns.add(new DangerousPattern(
                "sudo", Pattern.compile("\\bsudo\\b"),
                "使用 sudo 提权执行命令", "CRITICAL"));
        patterns.add(new DangerousPattern(
                "su", Pattern.compile("\\bsu\\b"),
                "使用 su 切换用户", "CRITICAL"));
        patterns.add(new DangerousPattern(
                "chmod 777", Pattern.compile("\\bchmod\\s+777\\b"),
                "设置 chmod 777 开放所有权限", "CRITICAL"));
        patterns.add(new DangerousPattern(
                "chown root", Pattern.compile("\\bchown\\s+root\\b"),
                "将文件所有者改为 root", "CRITICAL"));

        // MEDIUM — 网络工具
        patterns.add(new DangerousPattern(
                "curl", Pattern.compile("\\bcurl\\b"),
                "使用 curl 进行网络请求", "MEDIUM"));
        patterns.add(new DangerousPattern(
                "wget", Pattern.compile("\\bwget\\b"),
                "使用 wget 下载文件", "MEDIUM"));
        patterns.add(new DangerousPattern(
                "nc", Pattern.compile("\\bnc\\b"),
                "使用 nc (netcat) 网络工具", "MEDIUM"));
        patterns.add(new DangerousPattern(
                "ssh", Pattern.compile("\\bssh\\b"),
                "使用 ssh 远程连接", "MEDIUM"));

        return List.copyOf(patterns);
    }
}
