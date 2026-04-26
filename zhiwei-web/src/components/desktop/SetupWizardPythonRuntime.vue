<script setup lang="ts">
/**
 * 引导向导第三步：捆绑 Python 运行时安装。
 *
 * <p>展示代码执行环境的能力收益（文档生成 / 数据分析 / ML / 加密），
 * 提供「立即安装 / 跳过」两路出口。安装中显示阶段名 + 百分比进度条；
 * 安装完成展示版本号；失败展示传输层错误（{@link useRuntimeStatus.error}）
 * 与业务原因（{@link RuntimeStatus.reason}）。</p>
 *
 * <p>跳过路径调用 {@code runtimeApi.disable()}，把后端状态显式切到
 * DISABLED，避免后续 sandbox 调用时再次提示安装。</p>
 *
 * @author zsg
 * @since 2026-04-26
 */
import { computed, onMounted } from 'vue'
import { Check, Loader2, X } from 'lucide-vue-next'
import { useRuntimeStatus } from '@/composables/useRuntimeStatus'
import { runtimeApi } from '@/api/runtime'

const emit = defineEmits<{ (e: 'next'): void }>()

const { status, error, refresh, install } = useRuntimeStatus()

onMounted(refresh)

const isInstalling = computed(() => status.value?.status === 'INSTALLING')
const isReady = computed(() => status.value?.status === 'READY')
const isFailed = computed(() => status.value?.status === 'INSTALL_FAILED')
const percent = computed(() => status.value?.percent ?? 0)
// 业务失败原因（INSTALL_FAILED 时由后端写入 status.reason），与 error.value（传输层）合并展示
const failureReason = computed(() => status.value?.reason ?? null)

async function onSkip() {
  // 即使 disable 调用失败也允许跳过，引导流程不能因此卡死；后续 sandbox 调用再统一提示
  try {
    await runtimeApi.disable()
  } catch {
    // 忽略：进入主界面后用户仍可在设置中手动操作运行时
  }
  emit('next')
}

async function onInstall() {
  await install()
}

function onContinue() {
  emit('next')
}
</script>

<template>
  <div class="w-full max-w-[512px] px-xl">
    <h2 class="text-xl font-semibold text-foreground">代码执行环境</h2>
    <p class="mt-xs text-sm text-muted-foreground">
      可选步骤 —— 跳过也能用基础对话、记忆、浏览器和 shell 能力
    </p>

    <!-- ==================== 默认 / 介绍态 ==================== -->
    <div v-if="!isInstalling && !isReady" class="mt-lg rounded-xl border border-border/60 p-lg">
      <p class="text-sm text-foreground">
        想让我做这些事，需要安装捆绑 Python 运行时（约 250MB）：
      </p>
      <ul class="mt-md flex flex-col gap-xs text-sm text-muted-foreground">
        <li class="flex items-center gap-xs">
          <span class="h-1 w-1 rounded-full bg-primary" />
          文档生成（docx / xlsx / pptx / pdf）
        </li>
        <li class="flex items-center gap-xs">
          <span class="h-1 w-1 rounded-full bg-primary" />
          数据分析（pandas / matplotlib）
        </li>
        <li class="flex items-center gap-xs">
          <span class="h-1 w-1 rounded-full bg-primary" />
          ML 推理 / 图像处理
        </li>
        <li class="flex items-center gap-xs">
          <span class="h-1 w-1 rounded-full bg-primary" />
          加密 / 编码 / API 调试
        </li>
      </ul>
    </div>

    <!-- ==================== 安装中 ==================== -->
    <div v-if="isInstalling" class="mt-lg rounded-xl border border-border/60 p-lg">
      <div class="flex items-center gap-sm">
        <Loader2 class="h-4 w-4 animate-spin text-primary" />
        <span class="text-sm font-medium text-foreground">
          正在 {{ status?.phase ?? '准备' }}... {{ percent }}%
        </span>
      </div>
      <div class="mt-md h-1.5 w-full overflow-hidden rounded-full bg-muted">
        <div
          class="h-full rounded-full bg-primary transition-all duration-200"
          :style="{ width: `${percent}%` }"
        />
      </div>
      <p class="mt-sm text-xs text-muted-foreground">
        下载完成后会自动校验并解压，整个过程通常 1-3 分钟
      </p>
    </div>

    <!-- ==================== 已就绪 ==================== -->
    <div v-if="isReady" class="mt-lg flex flex-col items-center gap-sm rounded-xl border border-primary bg-primary/5 p-lg">
      <Check class="h-8 w-8 text-primary" />
      <p class="text-sm font-medium text-foreground">
        代码执行环境已就绪
      </p>
      <p v-if="status?.version" class="text-xs text-muted-foreground">
        Python {{ status.version }}
      </p>
    </div>

    <!-- ==================== 传输层错误（SSE 断线 / refresh 失败 / install POST 失败） ==================== -->
    <!-- 任何非 READY 态都显示，含 INSTALLING 中 SSE 断线场景，避免冻结进度条无信号 -->
    <div
      v-if="error && !isReady"
      class="mt-md flex items-start gap-xs rounded-lg border border-destructive/40 bg-destructive/5 p-md text-sm text-destructive"
    >
      <X class="mt-xs h-4 w-4 flex-shrink-0" />
      <span>{{ error }}</span>
    </div>
    <!-- ==================== 业务层失败原因（仅 INSTALL_FAILED 且无传输错误） ==================== -->
    <!-- 限定 !error 避免与传输层错误同时双红条 -->
    <div
      v-if="isFailed && failureReason && !error"
      class="mt-md flex items-start gap-xs rounded-lg border border-destructive/40 bg-destructive/5 p-md text-sm text-destructive"
    >
      <X class="mt-xs h-4 w-4 flex-shrink-0" />
      <span>安装失败：{{ failureReason }}</span>
    </div>

    <!-- ==================== 操作按钮 ==================== -->
    <template v-if="isReady">
      <button
        class="mt-lg w-full rounded-lg bg-primary px-lg py-sm text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90"
        @click="onContinue"
      >
        继续
      </button>
    </template>
    <template v-else-if="!isInstalling">
      <button
        class="mt-lg w-full rounded-lg bg-primary px-lg py-sm text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90"
        @click="onInstall"
      >
        {{ isFailed ? '重试安装' : '立即安装' }}
      </button>
      <button
        class="mt-md w-full text-xs text-muted-foreground/60 transition-colors hover:text-muted-foreground"
        @click="onSkip"
      >
        跳过，以后再说
      </button>
    </template>
  </div>
</template>
