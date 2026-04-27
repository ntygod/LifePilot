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
import { computed, onMounted, ref } from 'vue'
import { Check, Loader2, X } from 'lucide-vue-next'
import { useRuntimeStatus, humanizeInstallError } from '@/composables/useRuntimeStatus'
import { runtimeApi } from '@/api/runtime'

const emit = defineEmits<{ (e: 'next'): void }>()

const { status, error, refresh, install } = useRuntimeStatus()

onMounted(refresh)

const isInstalling = computed(() => status.value?.status === 'INSTALLING')
const isReady = computed(() => status.value?.status === 'READY')
const isFailed = computed(() => status.value?.status === 'INSTALL_FAILED')
const percent = computed(() => status.value?.percent ?? 0)

/**
 * 失败展示视图：把业务失败 reason 或传输层 error 归类为友好的标题 + 建议 + 技术详情。
 *
 * <p>合并优先级：业务失败（status.reason）优先于传输层 error，
 * 避免双红条同时出现让用户晕菜。</p>
 */
const failureView = computed(() => {
  const reason = status.value?.status === 'INSTALL_FAILED' ? status.value.reason : error.value
  return humanizeInstallError(reason)
})
/** 是否有任何失败需要展示（非 READY 时才考虑显示）。 */
const hasFailure = computed(
  () => !isReady.value && ((isFailed.value && !!status.value?.reason) || !!error.value),
)
/** 控制技术详情折叠展开（默认隐藏）。 */
const showTechnical = ref(false)

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

    <!--
      ==================== 失败友好卡片（业务+传输层合并） ====================
      - 主标题：humanizeInstallError 归类后的人话（如"运行时安装包暂未发布"）
      - 建议：用户能立即采取的下一步操作
      - 技术详情：默认折叠，展开看原始错误（含完整 URL / HTTP 状态）
      非 READY 态都显示（含 INSTALLING 中 SSE 断线场景），避免进度冻结无信号。
    -->
    <div
      v-if="hasFailure"
      class="mt-md rounded-lg border border-destructive/40 bg-destructive/5 p-md"
    >
      <div class="flex items-start gap-xs">
        <X class="mt-xs h-4 w-4 flex-shrink-0 text-destructive" />
        <div class="min-w-0 flex-1">
          <div class="text-sm font-medium text-destructive">{{ failureView.title }}</div>
          <div class="mt-xs text-xs leading-relaxed text-muted-foreground">
            {{ failureView.hint }}
          </div>
          <button
            type="button"
            class="mt-sm text-xs text-muted-foreground/70 underline-offset-2 hover:text-muted-foreground hover:underline"
            @click="showTechnical = !showTechnical"
          >
            {{ showTechnical ? '收起技术详情' : '查看技术详情' }}
          </button>
          <div
            v-if="showTechnical"
            class="mt-xs break-all rounded bg-muted/40 p-sm font-mono text-xs text-muted-foreground/80"
          >
            {{ failureView.technical }}
          </div>
        </div>
      </div>
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
