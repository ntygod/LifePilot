package com.lifepilot.skill.validation;

import com.lifepilot.skill.spec.SkillRequires;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Skill 运行期依赖门控器 —— 加载期判断 bins / env / os / tools 是否满足。
 * 不满足的 skill 将从 ContextAssembler.buildSkillCatalog() 输出中剔除。
 *
 * <p>bin 探测防护：</p>
 * <ul>
 *   <li>白名单正则：仅接受 {@code ^[a-zA-Z0-9._-]{1,64}$}，拒绝含 {@code /} / 超长等可控字符</li>
 *   <li>结果缓存：{@link ConcurrentHashMap} 按 bin 名字缓存探测结果，避免每次 buildSkillCatalog fork 子进程</li>
 *   <li>子进程清理：{@code ProcessBuilder + redirectErrorStream} + drain stdout + 超时后 {@code destroyForcibly()}</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-24
 */
@Component
public class SkillRequirementGate {

    private static final Logger log = LoggerFactory.getLogger(SkillRequirementGate.class);

    /** bin 名字白名单 —— 拒绝含 {@code /} / 超长等可控字符，避免 exec 注入。 */
    private static final Pattern BIN_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]{1,64}$");

    /** bin 探测超时（秒）。 */
    private static final int BIN_PROBE_TIMEOUT_SECONDS = 2;

    /** bin 探测结果缓存 —— 按 bin 名字字符串映射。不过期，进程生命周期内稳定。 */
    private final ConcurrentHashMap<String, Boolean> binCache = new ConcurrentHashMap<>();

    private final DynamicToolRegistry toolRegistry;
    private final Supplier<String> osSupplier;
    private final Function<String, Boolean> binResolver;
    private final Function<String, String> envResolver;

    /** 生产构造器：自动检测当前 OS + 缓存驱动的 bin 探测 + 读 env 变量。 */
    @Autowired
    public SkillRequirementGate(DynamicToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
        this.osSupplier = SkillRequirementGate::currentOs;
        this.binResolver = this::cachedBinaryExists;
        this.envResolver = System::getenv;
    }

    /** 测试用构造器：允许注入自定义 OS / bin / env 解析器。 */
    public SkillRequirementGate(DynamicToolRegistry toolRegistry,
                                 Supplier<String> osSupplier,
                                 Function<String, Boolean> binResolver,
                                 Function<String, String> envResolver) {
        this.toolRegistry = toolRegistry;
        this.osSupplier = osSupplier;
        this.binResolver = binResolver;
        this.envResolver = envResolver;
    }

    /**
     * 判断指定 requires 是否全部满足。
     * 任一维度（bin / env / os / tool）不满足即拒绝。
     */
    public boolean satisfies(SkillRequires requires) {
        for (String bin : requires.bins()) {
            if (!binResolver.apply(bin)) {
                return false;
            }
        }
        for (String env : requires.env()) {
            String val = envResolver.apply(env);
            if (val == null || val.isBlank()) {
                return false;
            }
        }
        if (!requires.os().isEmpty() && !requires.os().contains(osSupplier.get())) {
            return false;
        }
        for (String toolId : requires.tools()) {
            if (toolRegistry.resolve(toolId).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** 当前操作系统标识：windows / darwin / linux。 */
    private static String currentOs() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac")) {
            return "darwin";
        }
        return "linux";
    }

    /** 查缓存的 bin 探测：首次进入计算后缓存，后续 O(1) 返回。 */
    private boolean cachedBinaryExists(String bin) {
        return binCache.computeIfAbsent(bin, this::probeBin);
    }

    /**
     * 单次 bin 探测：先走白名单，再 fork {@code bin --version}；无论退出路径都清理子进程。
     *
     * <p>注意：Windows 下对无 {@code --version} 支持的工具可能误判；后续可迭代为 PATH 搜索。</p>
     */
    private boolean probeBin(String bin) {
        if (bin == null || !BIN_NAME_PATTERN.matcher(bin).matches()) {
            log.warn("SkillRequirementGate: 非法 bin 名 '{}'，拒绝探测", bin);
            return false;
        }
        Process process = null;
        try {
            process = new ProcessBuilder(bin, "--version")
                    .redirectErrorStream(true)
                    .start();
            drainQuietly(process.getInputStream());
            boolean finished = process.waitFor(BIN_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /** 读尽 stdout 防止管道缓冲阻塞子进程，异常静默。 */
    private static void drainQuietly(InputStream in) {
        try (in) {
            byte[] buf = new byte[1024];
            while (in.read(buf) > 0) {
                // 丢弃读到的字节
            }
        } catch (Exception ignored) {
            // drain 阶段异常不影响探测结论
        }
    }
}
