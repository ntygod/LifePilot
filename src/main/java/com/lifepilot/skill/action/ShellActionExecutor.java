package com.lifepilot.skill.action;

import com.lifepilot.skill.config.SkillConfigProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Shell 命令执行器 — 使用 ProcessBuilder 在独立进程中执行命令。
 *
 * <p>安全特性：
 * <ul>
 *   <li>危险命令检测：通过 {@link DangerousCommandDetector} 拦截</li>
 *   <li>强制超时：超时后 {@code destroyForcibly()}</li>
 *   <li>变量替换后的 Shell 注入检查</li>
 * </ul></p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class ShellActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(ShellActionExecutor.class);

    private final DangerousCommandDetector dangerousCommandDetector;
    private final VariableResolver variableResolver;
    private final SkillConfigProperties config;

    /**
     * 构造 Shell 命令执行器。
     *
     * @param dangerousCommandDetector 危险命令检测器
     * @param variableResolver         变量替换引擎
     * @param config                   Skill 配置属性
     */
    public ShellActionExecutor(DangerousCommandDetector dangerousCommandDetector,
                               VariableResolver variableResolver,
                               SkillConfigProperties config) {
        this.dangerousCommandDetector = dangerousCommandDetector;
        this.variableResolver = variableResolver;
        this.config = config;
    }

    /**
     * 执行 Shell 命令。
     *
     * <p>执行流程：
     * <ol>
     *   <li>变量替换 — 替换命令中的 ${params.xxx} 占位符</li>
     *   <li>Shell 注入检查 — 检测替换后的参数值是否包含注入字符</li>
     *   <li>危险命令检测 — 通过黑名单模式拦截危险命令</li>
     *   <li>ProcessBuilder 执行 — 在独立进程中运行命令</li>
     *   <li>超时控制 — 超时后 destroyForcibly() 强制终止</li>
     * </ol></p>
     *
     * @param action Shell 动作定义
     * @param params 输入参数
     * @return 包含 exitCode、stdout、stderr 的执行结果
     */
    public ActionResult execute(SkillAction.ShellAction action, Map<String, Object> params) {
        // 1. 变量替换
        String resolvedCommand = variableResolver.resolve(action.command(), params, null);

        // 2. Shell 注入检查 — 对每个参数值检测注入字符
        for (Object value : params.values()) {
            if (value != null && variableResolver.containsShellInjection(value.toString())) {
                log.warn("Shell 注入检测拦截: command={}, 参数值={}", action.command(), value);
                return ActionResult.error("Shell 注入检测: 参数值包含危险字符");
            }
        }

        // 3. 危险命令检测
        if (dangerousCommandDetector.isDangerous(resolvedCommand)) {
            var patterns = dangerousCommandDetector.detectPatterns(resolvedCommand);
            log.warn("危险命令拦截: command={}, 匹配模式={}", resolvedCommand, patterns);
            return ActionResult.error("危险命令拦截: " + String.join(", ", patterns));
        }

        // 4. 计算实际超时时间 = min(action.timeoutSeconds, config.maxTimeoutSeconds)
        int maxTimeout = config.getShellAction().getMaxTimeoutSeconds();
        int actualTimeout = Math.min(action.timeoutSeconds(), maxTimeout);

        // 5. 构建并执行进程
        try {
            ProcessBuilder processBuilder = createProcessBuilder(resolvedCommand);
            processBuilder.redirectErrorStream(false);
            Process process = processBuilder.start();

            // 6. 在独立线程中捕获 stdout 和 stderr，避免管道阻塞
            var stdoutFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try (var reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    return reader.lines().collect(Collectors.joining("\n"));
                } catch (Exception e) {
                    return "";
                }
            });
            var stderrFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try (var reader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    return reader.lines().collect(Collectors.joining("\n"));
                } catch (Exception e) {
                    return "";
                }
            });

            // 7. 等待进程完成，超时则强制终止
            boolean completed = process.waitFor(actualTimeout, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                log.warn("Shell 命令超时: command={}, timeout={}s", resolvedCommand, actualTimeout);
                return ActionResult.error("Shell 命令超时（" + actualTimeout + "秒）: " + resolvedCommand);
            }

            int exitCode = process.exitValue();
            String stdout = stdoutFuture.get(5, TimeUnit.SECONDS);
            String stderr = stderrFuture.get(5, TimeUnit.SECONDS);

            // 8. 构建结果
            Map<String, Object> data = Map.of(
                    "exitCode", exitCode,
                    "stdout", stdout,
                    "stderr", stderr
            );

            String output = "exitCode=" + exitCode + "\n" + stdout;

            if (exitCode != 0) {
                log.debug("Shell 命令非零退出: command={}, exitCode={}", resolvedCommand, exitCode);
                return ActionResult.error("exitCode=" + exitCode + "\n" + stderr);
            }

            return ActionResult.success(output, data);

        } catch (Exception e) {
            log.warn("Shell 命令执行异常: command={}, error={}", resolvedCommand, e.getMessage());
            return ActionResult.error("Shell 命令执行失败: " + e.getMessage());
        }
    }

    /**
     * 根据操作系统创建 ProcessBuilder。
     *
     * <p>Windows 使用 {@code cmd /c}，Unix 使用 {@code sh -c}。</p>
     *
     * @param command Shell 命令
     * @return 配置好的 ProcessBuilder
     */
    ProcessBuilder createProcessBuilder(String command) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return new ProcessBuilder("cmd", "/c", command);
        }
        return new ProcessBuilder("sh", "-c", command);
    }
}
