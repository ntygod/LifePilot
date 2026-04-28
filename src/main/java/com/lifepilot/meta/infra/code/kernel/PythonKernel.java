package com.lifepilot.meta.infra.code.kernel;

import java.util.List;
import java.util.Map;

/**
 * Python 持久内核 — 通过长驻 python3 进程实现跨调用状态保持。
 *
 * <p>内部启动一个 {@code python3 -u} 进程运行嵌入式 REPL 脚本，
 * 通过 stdin/stdout 进行行级 JSON 通信。支持 execute、inspect、reset 三种操作，
 * 以及 {@code %pip} 魔法命令安装包。</p>
 *
 * @author zsg
 * @since 2026-03-31
 */
public final class PythonKernel extends ProcessKernelBase {

    /**
     * 嵌入式 Python REPL 脚本 — 部署时无需外部文件。
     *
     * <p>通信协议：每行一个 JSON 请求，返回每行一个 JSON 响应。
     * 支持 execute / inspect / reset 三种 action。</p>
     *
     * <p>脚本开头强制把 stdin/stdout/stderr 重配为 UTF-8（Windows 默认 cp936/GBK），
     * 避免 Java 端 UTF-8 字节流被 GBK 解码产生 lone surrogate（如 \\udcad）导致
     * compile/exec 报 {@code UnicodeEncodeError: surrogates not allowed}。</p>
     */
    private static final String PYTHON_KERNEL_SCRIPT = """
            import sys, json, io, traceback, contextlib
            for _stream_name in ("stdin", "stdout", "stderr"):
                _stream = getattr(sys, _stream_name, None)
                if _stream is not None and hasattr(_stream, "reconfigure"):
                    try:
                        _stream.reconfigure(encoding="utf-8", errors="replace")
                    except Exception:
                        pass
            _g = {"__builtins__": __builtins__}
            for line in sys.stdin:
                try:
                    req = json.loads(line.strip())
                    action = req.get("action", "execute")
                    if action == "execute":
                        code = req.get("code", "")
                        if code.strip().startswith("%pip"):
                            import subprocess
                            parts = code.strip().split(None, 2)
                            cmd = ["pip"] + parts[1:]
                            r = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
                            res = {"stdout": r.stdout, "stderr": r.stderr, "error": None if r.returncode == 0 else r.stderr}
                        else:
                            so, se = io.StringIO(), io.StringIO()
                            err = None
                            try:
                                with contextlib.redirect_stdout(so), contextlib.redirect_stderr(se):
                                    exec(compile(code, "<kernel>", "exec"), _g)
                            except Exception:
                                err = traceback.format_exc()
                            res = {"stdout": so.getvalue(), "stderr": se.getvalue(), "error": err}
                    elif action == "inspect":
                        variables = {}
                        for k, v in _g.items():
                            if not k.startswith("_"):
                                try:
                                    variables[k] = type(v).__name__
                                except Exception:
                                    variables[k] = "unknown"
                        res = {"variables": variables}
                    elif action == "reset":
                        _g.clear()
                        _g["__builtins__"] = __builtins__
                        res = {"message": "已清空"}
                    else:
                        res = {"error": "未知操作: " + action}
                except Exception as e:
                    res = {"error": str(e)}
                sys.stdout.write(json.dumps(res, ensure_ascii=False) + "\\n")
                sys.stdout.flush()
            """;

    /**
     * 创建 Python 持久内核。
     *
     * @param kernelId       内核唯一标识
     * @param pythonRuntime  Python 运行时路径（如 "python3"）
     * @param maxOutputChars 输出最大字符数
     */
    public PythonKernel(String kernelId, String pythonRuntime, int maxOutputChars) {
        super(kernelId, maxOutputChars, pythonRuntime, List.of("-u"), ".py", "Python", PYTHON_KERNEL_SCRIPT);
    }

    /**
     * 强制 Python 进程 stdio + 文件 IO 用 UTF-8（Windows 默认 cp936/GBK 会让
     * Java UTF-8 写入的 stdin 被 GBK 解码产生 lone surrogate，触发后续
     * compile/exec 抛 {@code UnicodeEncodeError: surrogates not allowed}）：
     * <ul>
     *   <li>PYTHONIOENCODING — 控制 Python 3 stdin/stdout/stderr 编码</li>
     *   <li>PYTHONUTF8=1 — 启用 Python UTF-8 mode（PEP 540），文件系统也走 UTF-8</li>
     *   <li>LANG — 兜底其他 runtime / 子进程</li>
     * </ul>
     */
    @Override
    protected void configureProcessEnvironment(Map<String, String> env) {
        env.put("PYTHONIOENCODING", "utf-8");
        env.put("PYTHONUTF8", "1");
        env.putIfAbsent("LANG", "en_US.UTF-8");
    }
}
