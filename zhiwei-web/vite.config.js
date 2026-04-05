import path from 'node:path';
import tailwindcss from '@tailwindcss/vite';
import vue from '@vitejs/plugin-vue';
import { defineConfig } from 'vite';
export default defineConfig({
    plugins: [
        vue(),
        tailwindcss(),
        // 确保 /float.html 在 SPA 回退之前被正确处理
        {
            name: 'float-html',
            configureServer(server) {
                server.middlewares.use((req, res, next) => {
                    if (req.url === '/float.html' || req.url?.startsWith('/float.html?')) {
                        req.url = '/float.html'
                    }
                    next()
                })
            }
        },
    ],
    resolve: {
        alias: {
            '@': path.resolve(__dirname, './src')
        }
    },
    // Monaco Editor Web Worker 配置，避免编辑器语法解析阻塞主线程
    worker: {
        format: 'es'
    },
    optimizeDeps: {
        include: [
            'monaco-editor/esm/vs/editor/editor.worker',
            'monaco-editor/esm/vs/language/json/json.worker',
            'monaco-editor/esm/vs/language/css/css.worker',
            'monaco-editor/esm/vs/language/html/html.worker',
            'monaco-editor/esm/vs/language/typescript/ts.worker',
            'monaco-editor'
        ]
    },
    server: {
        proxy: {
            '/api': {
                target: process.env.VITE_API_BASE || 'http://localhost:8080',
                changeOrigin: true
            }
        }
    },
    build: {
        rollupOptions: {
            input: {
                main: path.resolve(__dirname, 'index.html'),
                float: path.resolve(__dirname, 'float.html'),
            }
        }
    }
});
