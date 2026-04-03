---
globs: "zhiwei-web/src/**/*.{vue,ts,tsx}"
---

# 前端编码规范

## 组件开发
- UI 组件库：**Reka UI 2.x**（禁止使用 shadcn-vue）
- 必须使用 `<script setup lang="ts">`，配合 typed `defineProps` / `defineEmits`
- 组件文件名 PascalCase；路由页面放 `src/views/`

## 样式系统
- Tailwind CSS 4.x，仅使用命名尺度：
  - `xs`(4px/0.25rem) `sm`(8px/0.5rem) `md`(14px/0.875rem) `lg`(17.6px/1.1rem) `xl`(24px/1.5rem) `2xl`(32px/2rem)
  - **禁止**任意值如 `p-3`、`px-5`、`mt-7`
- CSS 变量用于主题色

## 状态管理
- Pinia stores 放 `src/stores/`
- 可组合函数放 `src/composables/useX.ts`
- 图标库：lucide-vue-next

## 所有 UI 文本使用中文
