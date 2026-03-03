<script setup lang="ts">
import { useRouter } from 'vue-router'
import { MessageSquare, BookOpen, Bot, Workflow, ArrowRight, Sparkles } from 'lucide-vue-next'

const router = useRouter()

function startUsing() {
  // 跳转到主应用（AppLayout 会处理 onboarding）
  router.push({ name: 'conversations' })
}
</script>

<template>
  <div class="min-h-screen bg-gradient-to-b from-background to-muted/20">
    <!-- Hero Section -->
    <div class="container mx-auto px-6 py-20 max-w-6xl">
      <div class="text-center mb-16">
        <div class="inline-flex items-center gap-2 px-4 py-2 rounded-full bg-primary/10 text-primary mb-6">
          <Sparkles :size="16" />
          <span class="text-sm font-medium">本地运行的个人 AI Agent 助手</span>
        </div>
        <h1 class="text-5xl md:text-6xl font-bold text-foreground mb-6">
          LifePilot
        </h1>
        <p class="text-xl text-muted-foreground max-w-[672px] mx-auto mb-8">
          一个强大的 AI 助手，帮助你管理知识、执行任务和自动化工作流。
          所有数据存储在本地，完全掌控你的隐私。
        </p>
        <button
          class="px-8 py-4 rounded-lg bg-primary text-primary-foreground hover:bg-primary/90 transition-colors font-medium text-lg flex items-center gap-2 mx-auto"
          @click="startUsing"
        >
          开始使用
          <ArrowRight :size="20" />
        </button>
      </div>

      <!-- 核心功能模块 -->
      <div class="grid grid-cols-1 md:grid-cols-3 gap-6 mb-20">
        <div class="p-8 rounded-lg border border-border bg-card hover:shadow-lg transition-shadow">
          <div class="w-12 h-12 rounded-lg bg-primary/10 flex items-center justify-center mb-4">
            <MessageSquare :size="24" class="text-primary" />
          </div>
          <h3 class="text-xl font-semibold text-foreground mb-2">智能对话</h3>
          <p class="text-muted-foreground">
            与 AI 进行多轮对话，支持上下文管理和流式响应，提供流畅的交互体验。
          </p>
        </div>

        <div class="p-8 rounded-lg border border-border bg-card hover:shadow-lg transition-shadow">
          <div class="w-12 h-12 rounded-lg bg-primary/10 flex items-center justify-center mb-4">
            <BookOpen :size="24" class="text-primary" />
          </div>
          <h3 class="text-xl font-semibold text-foreground mb-2">知识库</h3>
          <p class="text-muted-foreground">
            构建个人知识库，通过 RAG 技术增强 AI 回答的准确性，让 AI 更好地理解你的上下文。
          </p>
        </div>

        <div class="p-8 rounded-lg border border-border bg-card hover:shadow-lg transition-shadow">
          <div class="w-12 h-12 rounded-lg bg-primary/10 flex items-center justify-center mb-4">
            <Bot :size="24" class="text-primary" />
          </div>
          <h3 class="text-xl font-semibold text-foreground mb-2">智能 Agent</h3>
          <p class="text-muted-foreground">
            创建智能 Agent 和工作流，自动执行复杂任务，提高工作效率。
          </p>
        </div>
      </div>

      <!-- 典型使用场景 -->
      <div class="mb-20">
        <h2 class="text-3xl font-bold text-foreground text-center mb-12">典型使用场景</h2>
        <div class="grid grid-cols-1 md:grid-cols-2 gap-6">
          <div class="p-6 rounded-lg border border-border bg-card">
            <h3 class="text-lg font-semibold text-foreground mb-2">知识管理</h3>
            <p class="text-muted-foreground">
              将文档、笔记导入知识库，快速检索和问答，构建个人知识体系。
            </p>
          </div>

          <div class="p-6 rounded-lg border border-border bg-card">
            <h3 class="text-lg font-semibold text-foreground mb-2">研发助手</h3>
            <p class="text-muted-foreground">
              代码分析、文档生成、问题排查，AI 助手帮你提高开发效率。
            </p>
          </div>

          <div class="p-6 rounded-lg border border-border bg-card">
            <h3 class="text-lg font-semibold text-foreground mb-2">个人效率</h3>
            <p class="text-muted-foreground">
              自动化重复任务，智能提醒和规划，让 AI 成为你的效率伙伴。
            </p>
          </div>

          <div class="p-6 rounded-lg border border-border bg-card">
            <h3 class="text-lg font-semibold text-foreground mb-2">工作流自动化</h3>
            <p class="text-muted-foreground">
              创建工作流，连接多个工具和服务，实现复杂的自动化流程。
            </p>
          </div>
        </div>
      </div>

      <!-- 特性亮点 -->
      <div class="text-center">
        <h2 class="text-3xl font-bold text-foreground mb-8">为什么选择 LifePilot？</h2>
        <div class="grid grid-cols-1 md:grid-cols-3 gap-6">
          <div>
            <div class="text-2xl mb-2">🔒</div>
            <h3 class="font-semibold text-foreground mb-1">隐私保护</h3>
            <p class="text-sm text-muted-foreground">所有数据存储在本地，完全掌控你的隐私</p>
          </div>
          <div>
            <div class="text-2xl mb-2">⚡</div>
            <h3 class="font-semibold text-foreground mb-1">高性能</h3>
            <p class="text-sm text-muted-foreground">本地运行，响应迅速，无需等待云端处理</p>
          </div>
          <div>
            <div class="text-2xl mb-2">🔧</div>
            <h3 class="font-semibold text-foreground mb-1">高度可定制</h3>
            <p class="text-sm text-muted-foreground">支持自定义 Agent、工具和工作流</p>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
