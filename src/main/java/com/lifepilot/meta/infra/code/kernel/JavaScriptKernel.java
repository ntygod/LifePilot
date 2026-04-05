package com.lifepilot.meta.infra.code.kernel;

/**
 * JavaScript 持久内核 — 通过长驻 Node.js 进程实现跨调用状态保持。
 *
 * <p>内部启动一个 {@code node} 进程运行嵌入式 REPL 脚本，
 * 使用 {@code vm.createContext()} 进行状态隔离。
 * 通过 stdin/stdout 进行行级 JSON 通信。</p>
 *
 * @author zsg
 * @since 2026-03-31
 */
public final class JavaScriptKernel extends ProcessKernelBase {

    /**
     * 嵌入式 Node.js REPL 脚本。
     *
     * <p>使用 {@code vm.createContext()} 隔离执行上下文，
     * 通过 stdin 逐行读取 JSON 请求，stdout 逐行返回 JSON 响应。</p>
     */
    private static final String NODE_KERNEL_SCRIPT = """
            const vm = require('vm');
            const readline = require('readline');

            let ctx = vm.createContext({ console: console, require: require, process: process });

            const rl = readline.createInterface({ input: process.stdin, terminal: false });

            rl.on('line', (line) => {
                let res;
                try {
                    const req = JSON.parse(line.trim());
                    const action = req.action || 'execute';

                    if (action === 'execute') {
                        const code = req.code || '';
                        let stdout = '', stderr = '';
                        const origLog = console.log;
                        const origErr = console.error;
                        console.log = (...args) => { stdout += args.map(String).join(' ') + '\\n'; };
                        console.error = (...args) => { stderr += args.map(String).join(' ') + '\\n'; };
                        let error = null;
                        try {
                            const result = vm.runInContext(code, ctx, { timeout: 120000 });
                            if (result !== undefined) {
                                stdout += String(result) + '\\n';
                            }
                        } catch (e) {
                            error = e.stack || String(e);
                        } finally {
                            console.log = origLog;
                            console.error = origErr;
                        }
                        res = { stdout, stderr, error };
                    } else if (action === 'inspect') {
                        const variables = {};
                        for (const key of Object.getOwnPropertyNames(ctx)) {
                            if (!key.startsWith('_')) {
                                try {
                                    variables[key] = typeof ctx[key];
                                } catch (e) {
                                    variables[key] = 'unknown';
                                }
                            }
                        }
                        res = { variables };
                    } else if (action === 'reset') {
                        ctx = vm.createContext({ console: console, require: require, process: process });
                        res = { message: '已清空' };
                    } else {
                        res = { error: '未知操作: ' + action };
                    }
                } catch (e) {
                    res = { error: String(e) };
                }
                process.stdout.write(JSON.stringify(res) + '\\n');
            });
            """;

    /**
     * 创建 JavaScript 持久内核。
     *
     * @param kernelId       内核唯一标识
     * @param nodeRuntime    Node.js 运行时路径（如 "node"）
     * @param maxOutputChars 输出最大字符数
     */
    public JavaScriptKernel(String kernelId, String nodeRuntime, int maxOutputChars) {
        super(kernelId, maxOutputChars, nodeRuntime, ".js", "JavaScript", NODE_KERNEL_SCRIPT);
    }
}
