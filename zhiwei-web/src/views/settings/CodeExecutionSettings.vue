<script setup lang="ts">
/**
 * 代码执行环境设置子页 —— 捆绑 Python 运行时（Code Sandbox）的安装/启用/禁用/卸载/重装入口。
 *
 * <p>状态机来源：{@link useRuntimeStatus}（包装 {@code /api/runtime/python/status} 与 SSE 进度流）。
 * 状态枚举与 UI 显示映射：
 * <ul>
 *   <li>{@code NOT_INSTALLED} / {@code DISABLED} → 显示"启用并下载"按钮（onEnable，会自动触发 install）</li>
 *   <li>{@code INSTALLING} → 显示进度条 + 当前阶段</li>
 *   <li>{@code READY} → 显示禁用 / 卸载 / 重新安装 + 预装库列表</li>
 *   <li>{@code INSTALL_FAILED} → 显示失败原因 + 重试按钮</li>
 * </ul>
 * </p>
 *
 * <p>错误显示分两类：
 * <ul>
 *   <li>{@code error.value}（传输层）：网络断开 / 接口失败 / SSE 解析错误</li>
 *   <li>{@code status.value.reason}（业务层）：仅在 {@code INSTALL_FAILED} 时显示，
 *       例如下载超时、SHA-256 校验失败</li>
 * </ul>
 * </p>
 *
 * @author zsg
 * @since 2026-04-26
 */
import { computed, onMounted } from 'vue'
import { AlertCircle, Check, Cpu, Loader2 } from 'lucide-vue-next'
import { useRuntimeStatus } from '@/composables/useRuntimeStatus'
import { runtimeApi } from '@/api/runtime'
import StatePanel from '@/components/common/StatePanel.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { logger } from '@/utils/logger'

const { status, error, refresh, install, extractErrorMessage } = useRuntimeStatus()

onMounted(() => {
  void refresh()
})

/**
 * 状态徽章映射：把后端枚举映射到中文标签 + Badge variant。
 * 用 outline / default / secondary / destructive 这套 Badge 内置 variant，
 * 而不是自造 css 类，保持与项目其它设置页（如模型路由）一致。
 */
const statusBadge = computed<{
  label: string
  variant: 'default' | 'secondary' | 'destructive' | 'outline'
}>(() => {
  switch (status.value?.status) {
    case 'READY':
      return { label: '已启用', variant: 'default' }
    case 'NOT_INSTALLED':
      return { label: '未安装', variant: 'outline' }
    case 'DISABLED':
      return { label: '已禁用', variant: 'secondary' }
    case 'INSTALLING':
      return { label: '安装中', variant: 'secondary' }
    case 'INSTALL_FAILED':
      return { label: '安装失败', variant: 'destructive' }
    default:
      return { label: '加载中', variant: 'outline' }
  }
})

/** 磁盘占用换算成 MB（向上取整保留整数）。 */
const diskMB = computed(() =>
  status.value?.diskBytes ? Math.round(status.value.diskBytes / 1024 / 1024) : 0,
)

/** 安装进度百分比；后端 INSTALLING 阶段提供 percent 字段。 */
const installPercent = computed(() => {
  const p = status.value?.percent
  if (typeof p !== 'number') return 0
  return Math.max(0, Math.min(100, Math.round(p)))
})

/** 预装库列表（与后端镜像构建脚本保持一致）。 */
const PRELOADED_LIBS = [
  'pandas',
  'numpy',
  'scipy',
  'scikit-learn',
  'matplotlib',
  'seaborn',
  'openpyxl',
  'pillow',
  'python-pptx',
  'python-docx',
  'pypdf',
  'pdfplumber',
  'sympy',
  'requests',
  'httpx',
  'beautifulsoup4',
] as const

/**
 * 卸载运行时：删除磁盘文件，状态回到 NOT_INSTALLED。
 * 二次确认通过浏览器 confirm 即可（与 SettingsGeneral 数据目录重置同款交互）。
 *
 * <p>错误处理：catch 写 error.value 让 banner 可见；finally refresh 兜底，
 * 即便接口失败也以后端真实状态为准，避免 UI 显示 stale READY。</p>
 */
async function onUninstall() {
  if (!window.confirm('确认卸载代码执行环境？文件将被删除。')) return
  try {
    await runtimeApi.uninstall()
  } catch (e) {
    logger.error('卸载运行时失败:', e)
    error.value = extractErrorMessage(e)
  } finally {
    await refresh()
  }
}

/** 禁用运行时：保留文件，状态切到 DISABLED。 */
async function onDisable() {
  try {
    await runtimeApi.disable()
  } catch (e) {
    logger.error('禁用运行时失败:', e)
    error.value = extractErrorMessage(e)
  } finally {
    await refresh()
  }
}

/**
 * 启用 / 下载：后端 enable 端点在 NOT_INSTALLED 状态下会自动触发 install
 * 并返回 200 + {installTriggered:true}。这里改为直接走 install() 以便订阅 SSE 进度，
 * 避免错过早期事件。
 *
 * <p>两条路径分别处理：
 * <ul>
 *   <li>DISABLED：调 enable，finally 走 refresh</li>
 *   <li>NOT_INSTALLED：调 install（已订阅 SSE，进度事件会自动更新 status），
 *       仅在异常时才走 finally refresh 兜底</li>
 * </ul>
 * 用 fromStatus 锁定路径，避免 await 期间 status.value 变化导致 finally 误判。</p>
 */
async function onEnable() {
  const fromStatus = status.value?.status
  try {
    if (fromStatus === 'DISABLED') {
      await runtimeApi.enable()
    } else {
      // NOT_INSTALLED：直接 install（自动订阅 SSE 进度）
      await install()
    }
  } catch (e) {
    logger.error('启用运行时失败:', e)
    error.value = extractErrorMessage(e)
    // 异常路径：refresh 兜底拉一次后端真实状态
    await refresh()
    return
  }
  // 成功路径：DISABLED 路径需要 refresh 看到新状态；
  // NOT_INSTALLED 路径已订阅 SSE，进度事件会自动 refresh，无需重复
  if (fromStatus === 'DISABLED') {
    await refresh()
  }
}

/** 重新安装：先卸载再装。 */
async function onReinstall() {
  if (!window.confirm('重新安装会先卸载现有运行时再重新下载，确认继续？')) return
  try {
    await runtimeApi.uninstall()
    await install()
  } catch (e) {
    logger.error('重装运行时失败:', e)
    error.value = extractErrorMessage(e)
  } finally {
    await refresh()
  }
}
</script>

<template>
  <div class="space-y-6">
    <StatePanel
      title="代码执行环境"
      description="知微为代码沙箱预下载并管理一份独立的 Python 运行时，包含数据分析、文档处理、图像处理等常用库。下载约 250MB，仅在你启用后才会拉取。"
    >
      <template #icon>
        <Cpu class="size-5" />
      </template>

      <div class="space-y-4">
        <!-- 状态摘要行：徽章 + 版本 + 磁盘占用 -->
        <div class="flex flex-wrap items-center gap-sm">
          <Badge :variant="statusBadge.variant">{{ statusBadge.label }}</Badge>
          <span v-if="status?.version" class="text-sm text-muted-foreground">
            Python {{ status.version }}
          </span>
          <span v-if="diskMB > 0" class="text-sm text-muted-foreground">
            占用 {{ diskMB }} MB
          </span>
        </div>

        <!-- 安装进度条（仅 INSTALLING 阶段显示） -->
        <div
          v-if="status?.status === 'INSTALLING'"
          class="rounded-md border border-border/64 bg-muted/30 px-md py-sm"
        >
          <div class="flex items-center gap-sm text-sm">
            <Loader2 class="size-4 animate-spin text-primary" />
            <span class="font-medium">{{ status.phase || '准备中' }}</span>
            <span class="ml-auto text-muted-foreground">{{ installPercent }}%</span>
          </div>
          <div class="mt-sm h-1.5 w-full overflow-hidden rounded-full bg-border/60">
            <div
              class="h-full bg-primary transition-[width] duration-300"
              :style="{ width: `${installPercent}%` }"
            />
          </div>
        </div>

        <!-- 传输层错误（refresh / install 调用失败、SSE 断开） -->
        <div
          v-if="error"
          class="flex items-start gap-sm rounded-md border border-destructive/30 bg-destructive/8 px-md py-sm text-sm text-destructive"
        >
          <AlertCircle class="mt-0.5 size-4 shrink-0" />
          <span>{{ error }}</span>
        </div>

        <!-- 业务失败原因（仅 INSTALL_FAILED 状态显示 reason） -->
        <div
          v-if="status?.status === 'INSTALL_FAILED' && status?.reason"
          class="flex items-start gap-sm rounded-md border border-destructive/30 bg-destructive/8 px-md py-sm text-sm text-destructive"
        >
          <AlertCircle class="mt-0.5 size-4 shrink-0" />
          <span>安装失败：{{ status.reason }}</span>
        </div>

        <!-- 操作按钮区，按状态条件渲染 -->
        <div class="flex flex-wrap items-center gap-sm">
          <Button
            v-if="status?.status === 'NOT_INSTALLED' || status?.status === 'DISABLED'"
            @click="onEnable"
          >
            {{ status?.status === 'DISABLED' ? '启用' : '启用并下载' }}
          </Button>
          <Button
            v-if="status?.status === 'READY'"
            variant="outline"
            @click="onDisable"
          >
            禁用
          </Button>
          <Button
            v-if="status?.status === 'READY'"
            variant="destructive"
            @click="onUninstall"
          >
            卸载
          </Button>
          <Button
            v-if="status?.status === 'READY'"
            variant="ghost"
            @click="onReinstall"
          >
            重新安装
          </Button>
          <Button
            v-if="status?.status === 'INSTALL_FAILED'"
            @click="install"
          >
            重试
          </Button>
        </div>
      </div>
    </StatePanel>

    <!-- 预装库列表：仅 READY 状态展示 -->
    <StatePanel
      v-if="status?.status === 'READY'"
      title="预装库"
      description="以下 Python 库已随运行时一起下载，沙箱内可直接 import 使用。"
    >
      <ul class="grid grid-cols-2 gap-sm text-sm sm:grid-cols-3 md:grid-cols-4">
        <li
          v-for="lib in PRELOADED_LIBS"
          :key="lib"
          class="flex items-center gap-xs"
        >
          <Check class="size-4 text-emerald-500" />
          <span>{{ lib }}</span>
        </li>
      </ul>
    </StatePanel>
  </div>
</template>
