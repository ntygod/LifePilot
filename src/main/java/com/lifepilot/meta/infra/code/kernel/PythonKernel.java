package com.lifepilot.meta.infra.code.kernel;

/**
 * Python 持久内��� — 通过长驻 python3 进程实��跨调用状态保持。
 *
 * <p>内部启动一个 {@code python3 -u} 进程运行嵌��式 REPL 脚本，
 * 通过 stdin/stdout ���行行级 JSON 通信。支持 execute、inspect、reset 三种操作，
 * 以及 {@code %pip} 魔法命令安装包。</p>
 *
 * @author zsg
 * @since 2026-03-31
 */
public final class PythonKernel extends ProcessKernelBase {

    /**
     * 嵌入式 Python REPL 脚本 — 部署时无需外部文件。
     *
     * <p>通信协议：每行一个 JSON 请求，返���每行一个 JSON 响��。
     * 支持 execute / inspect / reset 三种 action。</p>
     */
    private static final String PYTHON_KERNEL_SCRIPT = """
            import sys, json, io, traceback, contextlib
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
        super(kernelId, maxOutputChars, pythonRuntime, ".py", "Python", PYTHON_KERNEL_SCRIPT);
    }
}
