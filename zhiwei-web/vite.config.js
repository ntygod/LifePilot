import path from 'node:path';
import tailwindcss from '@tailwindcss/vite';
import vue from '@vitejs/plugin-vue';
import { defineConfig } from 'vite';
export default defineConfig({
    plugins: [vue(), tailwindcss()],
    resolve: {
        alias: {
            '@': path.resolve(__dirname, './src')
        }
    },
    // 单页面入口：主应用（浮窗随旧主动引擎一并移除）
    build: {
        rollupOptions: {
            input: {
                main: path.resolve(__dirname, 'index.html'),
            },
        },
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
    }
});
