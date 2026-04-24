<script setup lang="ts">
/**
 * 定时任务调度中心 —— 2026-04-24 视觉精准对齐草稿原件。
 *
 * <p>结构（从上到下）：</p>
 * <ul>
 *   <li>顶栏：面包屑 + 全部历史 / 暂停全部 / 新建任务（功能占位）</li>
 *   <li>Hero "今日 ribbon"：日期 kicker + 叙事大标题 + 成功/失败/待跑 pills + 24h 时间轴</li>
 *   <li>KPI 卡片：总任务（真实）+ 需注意（仅在 error 任务 > 0 时显示）</li>
 *   <li>Toolbar：视图切换（cards / timeline / history） + 过滤 pills + 搜索</li>
 *   <li>主区：三种视图</li>
 *   <li>右侧详情抽屉：调度规则卡片 + 最近运行 KV + 调用工具 + 操作按钮</li>
 * </ul>
 *
 * <p><b>数据来源（全部真实）</b>：</p>
 * <ul>
 *   <li>任务列表：{@code store.tasks}</li>
 *   <li>当日执行日志（跨任务）：{@code store.todayLogs} —— 供 KPI / 叙事 / ribbon 一次性聚合</li>
 *   <li>单任务详细日志：selectTask 懒加载 {@code logsByTask}</li>
 *   <li>项目名：{@code projectStore.projects}</li>
 *   <li>agentColor：按 projectId 稳定哈希到 6 色板（非数据，仅视觉标识）</li>
 * </ul>
 *
 * <p><b>已移除的无后端数据 mock</b>（2026-04-24 反馈）：本月执行 / 成功率 / 本月花费
 * KPI；风险等级；单次花费；结果投递——这些都没有持久化来源，保留只会误导用户。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  ChevronRight,
  Clock,
  Edit3,
  History,
  MoreHorizontal,
  PauseCircle,
  Play,
  Plus,
  Search,
  Sparkles,
  X,
} from 'lucide-vue-next'
import { useScheduledTaskStore } from '@/stores/scheduledTask'
import { useProjectStore } from '@/stores/project'
import { formatNextExecutionTime, formatExecutedAtTime } from '@/utils/nextExecutionTime'
import {
  listScheduledTaskLogs,
  type ScheduledTaskDto,
  type ScheduledTaskLogDto,
} from '@/api/scheduledTask'
import ScheduledTaskEditDialog from '@/components/scheduled/ScheduledTaskEditDialog.vue'

const store = useScheduledTaskStore()
const projectStore = useProjectStore()
const router = useRouter()

onMounted(async () => {
  // 并行拉取，任一失败只落入各自 store.error，不阻塞另一条
  await Promise.all([
    store.fetchAll().catch(() => {}),
    projectStore.fetchProjects().catch(() => {}),
    // 当日日志用于 KPI / 叙事 / ribbon 聚合；失败时 store 内部已兜底，不 throw
    store.fetchTodayLogs(),
  ])
})

// ──────────────────────────────────────────────────────────────────────
// 视图切换 / 过滤 / 搜索
// ──────────────────────────────────────────────────────────────────────

type ViewMode = 'cards' | 'timetable' | 'history'
const viewMode = ref<ViewMode>('cards')

type FilterKey = 'all' | 'active' | 'paused' | 'error' | 'draft'
const activeFilter = ref<FilterKey>('all')

const searchQuery = ref('')

/** 按状态 + 搜索关键字过滤 */
const filteredTasks = computed(() => {
  const query = searchQuery.value.trim().toLowerCase()
  return store.tasks.filter(task => {
    if (activeFilter.value !== 'all' && task.status !== activeFilter.value) return false
    if (query) {
      const haystack = `${task.name} ${task.instruction}`.toLowerCase()
      if (!haystack.includes(query)) return false
    }
    return true
  })
})

/** 驱动过滤 pill 数字徽标 */
const statusCounts = computed(() => {
  const counts = { all: 0, active: 0, paused: 0, error: 0, draft: 0 }
  for (const task of store.tasks) {
    counts.all += 1
    if (task.status === 'active') counts.active += 1
    else if (task.status === 'paused') counts.paused += 1
    else if (task.status === 'error') counts.error += 1
    else if (task.status === 'draft') counts.draft += 1
  }
  return counts
})

// ──────────────────────────────────────────────────────────────────────
// 项目信息 & 跳转
// ──────────────────────────────────────────────────────────────────────

/** 项目 ID → 名称 查找表 */
const projectNameById = computed(() => {
  const map = new Map<string, string>()
  for (const p of projectStore.projects) map.set(p.id, p.name)
  return map
})

/** 主账户任务显示"主"，项目任务显示项目名；项目被删则降级为 ID */
function projectLabel(projectId: string | null): string {
  if (!projectId) return '主'
  return projectNameById.value.get(projectId) ?? projectId
}

function openProject(projectId: string) {
  router.push({ name: 'projectDetail', params: { id: projectId } })
}

// ──────────────────────────────────────────────────────────────────────
// agentColor：按 projectId / 任务 id 稳定哈希，落到 6 个色板之一
// ──────────────────────────────────────────────────────────────────────

/** 草稿里每个 agent 有独立配色；项目名用同样哈希策略派生 */
const AGENT_PALETTE = [
  '#8a6d28', // amber-700 近似
  '#5a4786', // 紫（仅作非主色的辅助，若全项目无紫用户偏好可接受）
  '#4a5fc1', // indigo-600
  '#0a6e53', // emerald-700
  '#c96442', // orange-red
  '#7a6a3d', // olive
] as const

function agentColorFor(projectId: string | null): string {
  // 主账户固定灰
  if (!projectId) return '#8a857d'
  let hash = 0
  for (let i = 0; i < projectId.length; i += 1) {
    hash = (hash * 31 + projectId.charCodeAt(i)) >>> 0
  }
  return AGENT_PALETTE[hash % AGENT_PALETTE.length]
}

// ──────────────────────────────────────────────────────────────────────
// 详情面板：选中 + 日志懒加载
// ──────────────────────────────────────────────────────────────────────

const selectedTaskId = ref<string | null>(null)

const logsByTask = reactive<Record<string, ScheduledTaskLogDto[]>>({})
const loadingLogs = reactive<Record<string, boolean>>({})
const logErrors = reactive<Record<string, string | null>>({})

const selectedTask = computed<ScheduledTaskDto | null>(() => {
  if (!selectedTaskId.value) return null
  return store.tasks.find(t => t.id === selectedTaskId.value) ?? null
})

async function selectTask(taskId: string) {
  selectedTaskId.value = taskId
  if (!logsByTask[taskId] && !loadingLogs[taskId]) {
    loadingLogs[taskId] = true
    logErrors[taskId] = null
    try {
      logsByTask[taskId] = await listScheduledTaskLogs(taskId, 5)
    } catch (e: any) {
      logErrors[taskId] = e?.message ?? '加载执行历史失败'
    } finally {
      loadingLogs[taskId] = false
    }
  }
}

function closeDetail() {
  selectedTaskId.value = null
}

// 任务被删 / 被过滤掉（且真的从列表消失）时，自动关闭详情
watch(filteredTasks, list => {
  if (selectedTaskId.value && !list.some(t => t.id === selectedTaskId.value)) {
    if (!store.tasks.some(t => t.id === selectedTaskId.value)) {
      selectedTaskId.value = null
    }
  }
})

// ──────────────────────────────────────────────────────────────────────
// 编辑弹窗 / 行内操作
// ──────────────────────────────────────────────────────────────────────

const editDialogOpen = ref(false)
const editingTask = ref<ScheduledTaskDto | null>(null)

function openEdit(task: ScheduledTaskDto) {
  editingTask.value = task
  editDialogOpen.value = true
}

async function togglePause(taskId: string, currentStatus: string) {
  if (currentStatus === 'active') {
    await store.pauseTask(taskId)
  } else {
    await store.resumeTask(taskId)
  }
}

async function deleteTask(taskId: string, taskName: string) {
  if (!window.confirm(`确认删除定时任务「${taskName}」？此操作不可撤销。`)) return
  await store.deleteTask(taskId)
  if (selectedTaskId.value === taskId) selectedTaskId.value = null
}

// ──────────────────────────────────────────────────────────────────────
// 顶部按钮占位 Toast
// ──────────────────────────────────────────────────────────────────────

const placeholderMessage = ref<string | null>(null)

function showPlaceholder(msg: string) {
  placeholderMessage.value = msg
  setTimeout(() => {
    if (placeholderMessage.value === msg) placeholderMessage.value = null
  }, 2200)
}

function handleViewAllHistory() {
  showPlaceholder('全部历史视图即将推出')
}

function handlePauseAll() {
  showPlaceholder('批量暂停即将推出')
}

function handleCreateTask() {
  showPlaceholder('试试在对话中对微微说「每天 08:00 播报天气」即可创建')
}

// ──────────────────────────────────────────────────────────────────────
// Hero：日期、叙事、24h 时间轴
// ──────────────────────────────────────────────────────────────────────

/** 当前日期，如 "2026-04-24 · 周五" */
const todayLabel = computed(() => {
  const now = new Date()
  const year = now.getFullYear()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  const weekdays = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
  return `${year}-${month}-${day} · ${weekdays[now.getDay()]}`
})

/**
 * 基于真实 todayLogs 聚合的今日执行统计。
 *
 * <p>done：已完成次数（success + failed + timeout 均计）；success / failed 分别计数；
 * pending：剩余将要执行的 active 任务数（已过当前时间的 nextExecutionAt 不计）。</p>
 *
 * <p>没有日志时数字归 0，模板层通过兜底文案展示空态；不再派生伪造数字。</p>
 */
const todayStats = computed(() => {
  const logs = store.todayLogs
  let success = 0
  let failed = 0
  for (const log of logs) {
    if (log.status === 'success') success += 1
    else if (log.status === 'failed' || log.status === 'timeout') failed += 1
  }
  // pending：active 任务里 nextExecutionAt 位于"今日剩余"的条数
  const now = Date.now()
  const endOfToday = endOfLocalDay(now)
  let pending = 0
  for (const task of store.tasks) {
    if (task.status !== 'active' || !task.nextExecutionAt) continue
    const t = Date.parse(task.nextExecutionAt)
    if (Number.isFinite(t) && t > now && t <= endOfToday) pending += 1
  }
  return {
    done: success + failed,
    success,
    failed,
    pending,
  }
})

/** 本地当天 23:59:59.999 的 UTC 毫秒数，用于 pending 判定 */
function endOfLocalDay(ms: number): number {
  const d = new Date(ms)
  d.setHours(23, 59, 59, 999)
  return d.getTime()
}

/** 时间轴 8 个刻度（00:00 / 03:00 / ... / 21:00） */
const hourTicks = [0, 3, 6, 9, 12, 15, 18, 21]

/** ribbonDots：当日执行点位，从真实 todayLogs 派生 */
type RibbonDot = {
  task: string
  at: string
  label: string
  status: 'success' | 'failed' | 'upcoming'
  pct: number
}
const ribbonDots = computed<RibbonDot[]>(() => {
  const taskNameById = new Map<string, string>()
  for (const t of store.tasks) taskNameById.set(t.id, t.name)

  const dots: RibbonDot[] = []
  for (const log of store.todayLogs) {
    const ts = Date.parse(log.executedAt)
    if (!Number.isFinite(ts)) continue
    const d = new Date(ts)
    const hh = d.getHours()
    const mm = d.getMinutes()
    const at = `${String(hh).padStart(2, '0')}:${String(mm).padStart(2, '0')}`
    const pct = ((hh + mm / 60) / 24) * 100
    const status: RibbonDot['status'] = log.status === 'success' ? 'success' : 'failed'
    dots.push({
      task: log.taskId,
      at,
      label: taskNameById.get(log.taskId) ?? log.taskId,
      status,
      pct,
    })
  }
  return dots
})

/** NOW 游标在 24h 轴上的百分比位置 */
const nowCursorPct = computed(() => {
  const now = new Date()
  const minutes = now.getHours() * 60 + now.getMinutes()
  return (minutes / 1440) * 100
})

// ──────────────────────────────────────────────────────────────────────
// KPI：全部基于真实数据
// ──────────────────────────────────────────────────────────────────────
//
// 移除了本月执行 / 成功率 / 本月花费——后端没有持久化来源，保留只是把"我不知道"
// 变成"一个假数字"，反而误导用户。需要这些指标时先在后端补聚合 SQL + API，
// 再回来接视图层。

interface KpiCard {
  id: string
  value: string | number
  label: string
  caption: string
  alert?: boolean
}

const kpis = computed<KpiCard[]>(() => {
  const total = store.tasks.length
  const active = statusCounts.value.active
  const paused = statusCounts.value.paused
  const draft = statusCounts.value.draft
  const base: KpiCard[] = [
    {
      id: 'total',
      value: total,
      label: '总任务',
      caption: `${active} 运行 · ${paused} 暂停 · ${draft} 草稿`,
    },
  ]
  if (statusCounts.value.error > 0) {
    // "需注意" 只在真有 error 任务时显示；caption 指向第一条 error 任务的名字，
    // 避免写死"某某连续失败"这种虚假文案。
    const firstError = store.tasks.find(t => t.status === 'error')
    base.push({
      id: 'attention',
      value: statusCounts.value.error,
      label: '需注意',
      caption: firstError ? `「${firstError.name}」状态异常` : '存在异常任务',
      alert: true,
    })
  }
  return base
})

// ──────────────────────────────────────────────────────────────────────
// 卡片 / 详情 辅助函数
// ──────────────────────────────────────────────────────────────────────

/** cron → 人话；识别最常见场景，其他降级为"自定义周期" */
function humanSchedule(cron: string): string {
  const trimmed = cron.trim()
  const parts = trimmed.split(/\s+/)
  let minute: string
  let hour: string
  let dom: string
  let month: string
  let dow: string
  if (parts.length >= 6) {
    ;[, minute, hour, dom, month, dow] = parts
  } else if (parts.length === 5) {
    ;[minute, hour, dom, month, dow] = parts
  } else {
    return '自定义周期'
  }
  const weekdayMap: Record<string, string> = {
    '0': '周日', '7': '周日',
    '1': '周一', '2': '周二', '3': '周三', '4': '周四', '5': '周五', '6': '周六',
    SUN: '周日', MON: '周一', TUE: '周二', WED: '周三', THU: '周四', FRI: '周五', SAT: '周六',
  }
  const pad = (s: string) => s.padStart(2, '0')
  const hhmm = (h: string, m: string) => `${pad(h)}:${pad(m)}`
  if (!/^\d+$/.test(minute) || !/^\d+$/.test(hour)) return '自定义周期'
  if ((dom === '*' || dom === '?') && month === '*' && (dow === '*' || dow === '?')) {
    return `每天 ${hhmm(hour, minute)}`
  }
  if ((dom === '*' || dom === '?') && month === '*' && dow !== '*' && dow !== '?') {
    const label = weekdayMap[dow.toUpperCase()] ?? `周${dow}`
    return `每${label} ${hhmm(hour, minute)}`
  }
  if (dom !== '*' && dom !== '?' && month === '*' && (dow === '*' || dow === '?')) {
    return `每月 ${dom} 日 ${hhmm(hour, minute)}`
  }
  return `定时 ${hhmm(hour, minute)}`
}

/** 逗号分隔的 skillIds → 数组 */
function parseSkillIds(skillIds: string | null): string[] {
  if (!skillIds) return []
  return skillIds.split(',').map(s => s.trim()).filter(Boolean)
}

/** 状态中文 */
function statusLabel(status: string): string {
  switch (status) {
    case 'active': return '运行中'
    case 'paused': return '已暂停'
    case 'completed': return '已完成'
    case 'error': return '异常'
    case 'draft': return '草稿'
    default: return status
  }
}

/** 距下次执行的人话相对时间 */
function relativeNext(iso: string | null): string {
  if (!iso) return '—'
  const diffMs = new Date(iso).getTime() - Date.now()
  if (diffMs <= 0) return '—'
  const hours = Math.floor(diffMs / 3_600_000)
  const minutes = Math.floor((diffMs % 3_600_000) / 60_000)
  if (hours > 24) {
    const days = Math.floor(hours / 24)
    return `${days}d ${hours % 24}h`
  }
  return `${hours}h ${minutes}m`
}

/** 上次执行：读最近一条 log */
function lastRunLabel(taskId: string): string {
  const logs = logsByTask[taskId]
  if (logs && logs.length > 0) return formatExecutedAtTime(logs[0].executedAt)
  return '暂无'
}

function lastRunStatus(taskId: string): 'success' | 'failed' | 'idle' {
  const logs = logsByTask[taskId]
  if (logs && logs.length > 0) {
    if (logs[0].status === 'success') return 'success'
    if (logs[0].status === 'failed' || logs[0].status === 'timeout') return 'failed'
  }
  return 'idle'
}

function logStatusLabel(status: string): string {
  switch (status) {
    case 'success': return '成功'
    case 'failed': return '失败'
    case 'running': return '运行中'
    case 'timeout': return '超时'
    default: return status
  }
}

/** 创建时间相对化 */
function relativeCreated(iso: string): string {
  if (!iso) return '—'
  const diffMs = Date.now() - new Date(iso).getTime()
  if (diffMs < 0) return '刚刚'
  const days = Math.floor(diffMs / 86_400_000)
  if (days < 1) return '今天'
  if (days < 7) return `${days} 天前`
  const weeks = Math.floor(days / 7)
  if (weeks < 5) return `${weeks} 周前`
  const months = Math.floor(days / 30)
  if (months < 12) return `${months} 个月前`
  const years = Math.floor(days / 365)
  return `${years} 年前`
}

/** 时间表视图：状态→markers 颜色 */
function timelineMarkStatus(task: ScheduledTaskDto): 'success' | 'failed' | 'upcoming' {
  if (task.status === 'error') return 'failed'
  if (task.status === 'paused' || task.status === 'draft') return 'upcoming'
  return 'success'
}
</script>

<template>
  <div class="scheduled-tasks-view flex flex-col h-full min-h-0 overflow-hidden relative">
    <!-- ══════════ 顶栏 ══════════ -->
    <header class="flex items-center justify-between gap-md flex-shrink-0 px-xl py-md border-b bg-card">
      <nav class="flex items-center gap-sm text-xs text-muted-foreground" aria-label="面包屑">
        <span>工作台</span>
        <ChevronRight class="size-xs opacity-60" />
        <span class="text-foreground font-medium">定时任务</span>
      </nav>
      <div class="flex items-center gap-xs">
        <button
          type="button"
          class="header-btn"
          data-testid="view-all-history"
          @click="handleViewAllHistory"
        >
          <History class="size-xs" />
          <span>全部历史</span>
        </button>
        <button
          type="button"
          class="header-btn"
          data-testid="pause-all"
          @click="handlePauseAll"
        >
          <PauseCircle class="size-xs" />
          <span>暂停全部</span>
        </button>
        <button
          type="button"
          class="header-btn header-btn--primary"
          data-testid="create-task"
          @click="handleCreateTask"
        >
          <Plus class="size-xs" />
          <span>新建任务</span>
        </button>
      </div>
    </header>

    <!-- 功能占位 Toast -->
    <Transition name="toast">
      <div
        v-if="placeholderMessage"
        class="placeholder-toast flex items-center gap-sm px-md py-sm text-xs text-foreground"
        role="status"
      >
        <Sparkles class="size-xs shrink-0 text-primary" />
        <span>{{ placeholderMessage }}</span>
      </div>
    </Transition>

    <!-- ══════════ 主区（滚动） ══════════ -->
    <div class="flex-1 min-h-0 overflow-y-auto scrollbar-thin">
      <div class="mx-auto w-full max-w-7xl px-xl py-lg flex flex-col gap-md">
        <!-- ═══ Hero ═══ -->
        <section class="sched-hero rounded-xl px-xl pt-lg pb-xl">
          <!-- Hero 顶部 -->
          <div class="flex items-start justify-between gap-md mb-lg">
            <div class="min-w-0 flex flex-col gap-xs">
              <div class="sched-text-caption font-mono uppercase sched-tracking-wider text-primary font-semibold">
                TODAY · {{ todayLabel }}
              </div>
              <h1 class="font-serif text-lg font-medium tracking-tight text-foreground leading-snug">
                今天替你完成了 <b class="text-primary font-semibold px-xs">{{ todayStats.done }}</b>
                件事，还有 <b class="text-primary font-semibold px-xs">{{ todayStats.pending }}</b>
                件将发生
              </h1>
            </div>
            <div class="flex gap-xs shrink-0">
              <span class="sched-chip sched-chip--success font-mono">
                <i /> 成功 {{ todayStats.success }}
              </span>
              <span class="sched-chip sched-chip--failed font-mono">
                <i /> 失败 {{ todayStats.failed }}
              </span>
              <span class="sched-chip sched-chip--upcoming font-mono">
                <i /> 待跑 {{ todayStats.pending }}
              </span>
            </div>
          </div>

          <!-- 24h 时间轴 -->
          <div class="sched-ribbon">
            <!-- 刻度 -->
            <div class="sched-ribbon__axis">
              <span
                v-for="h in hourTicks"
                :key="`hour-${h}`"
                class="font-mono"
                :style="{ left: `${(h / 24) * 100}%` }"
              >
                {{ String(h).padStart(2, '0') }}:00
              </span>
            </div>
            <!-- 轨道 -->
            <div class="sched-ribbon__track">
              <!-- NOW 游标 -->
              <div
                class="sched-ribbon__now"
                :style="{ left: `${nowCursorPct}%` }"
              />
              <!-- 执行点 -->
              <div
                v-for="(dot, idx) in ribbonDots"
                :key="`dot-${idx}`"
                :class="['sched-ribbon__dot', `sched-ribbon__dot--${dot.status}`]"
                :style="{ left: `${dot.pct}%` }"
                :title="`${dot.at} · ${dot.label}`"
                @click="selectTask(dot.task)"
              >
                <div class="sched-ribbon__tip">
                  <div class="sched-text-caption-xs font-mono">{{ dot.at }}</div>
                  <div>{{ dot.label }}</div>
                </div>
              </div>
            </div>
          </div>
        </section>

        <!-- ═══ KPI 行 ═══ -->
        <section
          class="grid gap-sm kpi-row"
          data-testid="kpi-row"
        >
          <div
            v-for="kpi in kpis"
            :key="kpi.id"
            :class="['sched-stat', kpi.alert ? 'sched-stat--warn' : '']"
          >
            <div
              :class="[
                'font-serif text-2xl font-medium tracking-tight leading-none',
                kpi.alert ? 'text-destructive' : 'text-foreground',
              ]"
            >
              {{ kpi.value }}
            </div>
            <div class="text-xs font-medium mt-xs text-foreground/80">{{ kpi.label }}</div>
            <div class="sched-text-caption font-mono mt-xs text-muted-foreground">{{ kpi.caption }}</div>
          </div>
        </section>

        <!-- ═══ Toolbar ═══ -->
        <section class="flex flex-wrap items-center gap-md">
          <!-- 视图切换（segmented） -->
          <div class="sched-seg flex rounded-md" role="tablist">
            <button
              type="button"
              :class="['sched-seg-opt', { 'sched-seg-opt--active': viewMode === 'cards' }]"
              role="tab"
              data-testid="view-tab-cards"
              @click="viewMode = 'cards'"
            >卡片</button>
            <button
              type="button"
              :class="['sched-seg-opt', { 'sched-seg-opt--active': viewMode === 'timetable' }]"
              role="tab"
              data-testid="view-tab-timetable"
              @click="viewMode = 'timetable'"
            >时间表</button>
            <button
              type="button"
              :class="['sched-seg-opt', { 'sched-seg-opt--active': viewMode === 'history' }]"
              role="tab"
              data-testid="view-tab-history"
              @click="viewMode = 'history'"
            >执行历史</button>
          </div>

          <!-- 过滤 pill -->
          <div class="sched-filters flex items-center" role="group" aria-label="任务状态过滤">
            <button
              v-for="f in [
                { key: 'all' as FilterKey, label: '全部', count: statusCounts.all },
                { key: 'active' as FilterKey, label: '运行中', count: statusCounts.active },
                { key: 'paused' as FilterKey, label: '已暂停', count: statusCounts.paused },
                { key: 'error' as FilterKey, label: '异常', count: statusCounts.error },
                { key: 'draft' as FilterKey, label: '草稿', count: statusCounts.draft },
              ]"
              :key="f.key"
              type="button"
              :class="['sched-filter', { 'sched-filter--active': activeFilter === f.key }]"
              :data-testid="`filter-${f.key}`"
              @click="activeFilter = f.key"
            >
              <span>{{ f.label }}</span>
              <span class="sched-filter__n font-mono">{{ f.count }}</span>
            </button>
          </div>

          <!-- 搜索 -->
          <div class="sched-search ml-auto flex items-center gap-xs px-sm py-xs">
            <Search class="size-xs text-muted-foreground shrink-0" />
            <input
              v-model="searchQuery"
              type="search"
              placeholder="搜索任务"
              class="sched-search__input text-xs bg-transparent border-0 outline-none flex-1 min-w-0 text-foreground"
              data-testid="search-input"
            />
          </div>
        </section>

        <!-- ═══ 主内容区 ═══ -->
        <section class="sched-workspace grid gap-md">
          <main class="min-w-0">
            <!-- 加载 -->
            <div
              v-if="store.loading && store.tasks.length === 0"
              class="py-2xl text-center text-xs text-muted-foreground"
            >
              加载中…
            </div>

            <!-- 全局空态 -->
            <div
              v-else-if="store.tasks.length === 0"
              class="sched-empty flex flex-col items-center gap-md py-2xl text-center"
              data-testid="empty-state"
            >
              <History class="size-2xl text-muted-foreground" />
              <div class="flex flex-col gap-xs">
                <p class="text-sm font-medium text-foreground">暂无定时任务</p>
                <p class="text-xs text-muted-foreground">
                  在对话中对微微说"每周日 21 点提醒我写周报"即可创建。
                </p>
              </div>
            </div>

            <!-- 卡片视图 -->
            <div
              v-else-if="viewMode === 'cards'"
              class="sched-grid grid gap-sm"
              data-testid="cards-grid"
            >
              <article
                v-for="task in filteredTasks"
                :key="task.id"
                :class="[
                  'sched-card',
                  `sched-card--${task.status}`,
                  { 'sched-card--active': selectedTaskId === task.id },
                ]"
                :data-testid="`task-card-${task.id}`"
                @click="selectTask(task.id)"
              >
                <!-- 顶部：agent tag + 状态 -->
                <div class="flex items-center justify-between gap-sm">
                  <button
                    type="button"
                    class="sched-card__agent font-medium"
                    :style="{
                      background: `${agentColorFor(task.projectId)}1e`,
                      color: agentColorFor(task.projectId),
                    }"
                    :data-testid="`project-tag-${task.id}`"
                    :disabled="!task.projectId"
                    @click.stop="task.projectId && openProject(task.projectId)"
                  >
                    {{ projectLabel(task.projectId) }}
                  </button>
                  <div
                    :class="['sched-status', `sched-status--${task.status}`]"
                    :data-testid="`status-badge-${task.id}`"
                  >
                    <i />
                    {{ statusLabel(task.status) }}
                  </div>
                </div>

                <!-- 标题 + 描述 -->
                <h3 class="sched-card__name text-foreground">{{ task.name }}</h3>
                <p class="sched-card__desc text-muted-foreground">
                  {{ task.instruction || '（未填写指令）' }}
                </p>

                <!-- cron bar -->
                <div class="sched-card__cron flex items-center gap-xs px-sm py-xs rounded-md">
                  <Clock class="size-xs shrink-0 text-muted-foreground" />
                  <span class="text-xs font-medium text-foreground/80">{{ humanSchedule(task.schedule) }}</span>
                  <code class="sched-text-small font-mono text-muted-foreground ml-auto truncate sched-cron-raw-inline">
                    {{ task.schedule }}
                  </code>
                </div>

                <!-- 下次 / 上次 -->
                <div class="grid grid-cols-2 gap-sm py-xs">
                  <div class="min-w-0">
                    <div class="sched-meta-label font-mono">下次运行</div>
                    <template v-if="task.status === 'active' && task.nextExecutionAt">
                      <div
                        class="sched-meta-value"
                        :data-testid="`next-execution-${task.id}`"
                      >
                        {{ formatNextExecutionTime(task.nextExecutionAt) }}
                        <span class="sched-meta-sub font-mono">· {{ relativeNext(task.nextExecutionAt) }}</span>
                      </div>
                    </template>
                    <template v-else>
                      <div class="sched-meta-value text-muted-foreground font-normal">
                        {{ task.status === 'paused' ? '已暂停' : '未计划' }}
                      </div>
                    </template>
                  </div>
                  <div class="min-w-0">
                    <div class="sched-meta-label font-mono">上次</div>
                    <div class="sched-meta-value flex items-center gap-xs">
                      <span
                        class="sched-dot shrink-0"
                        :class="`sched-dot--${lastRunStatus(task.id)}`"
                      />
                      {{ lastRunLabel(task.id) }}
                    </div>
                  </div>
                </div>

                <!-- 底部：工具 tags -->
                <div class="sched-card__foot flex items-center justify-between gap-sm pt-sm">
                  <div class="flex flex-wrap gap-xs">
                    <span
                      v-for="skill in parseSkillIds(task.skillIds).slice(0, 3)"
                      :key="skill"
                      class="sched-tool font-mono"
                    >{{ skill }}</span>
                    <span
                      v-if="parseSkillIds(task.skillIds).length > 3"
                      class="sched-tool sched-tool--dim font-mono"
                    >+{{ parseSkillIds(task.skillIds).length - 3 }}</span>
                    <span
                      v-if="parseSkillIds(task.skillIds).length === 0"
                      class="sched-tool font-mono italic"
                    >agent</span>
                  </div>
                </div>

                <!-- 右上角隐藏操作（hover 显示） -->
                <div class="sched-card__actions flex">
                  <button
                    type="button"
                    class="sched-icon-btn"
                    :data-testid="`pause-${task.id}`"
                    :title="task.status === 'active' ? '暂停' : '启用'"
                    @click.stop="togglePause(task.id, task.status)"
                  >
                    <PauseCircle v-if="task.status === 'active'" class="size-xs" />
                    <Play v-else class="size-xs" />
                  </button>
                  <button
                    type="button"
                    class="sched-icon-btn"
                    :data-testid="`edit-${task.id}`"
                    title="编辑"
                    @click.stop="openEdit(task)"
                  >
                    <Edit3 class="size-xs" />
                  </button>
                  <button
                    type="button"
                    class="sched-icon-btn sched-icon-btn--danger"
                    :data-testid="`delete-${task.id}`"
                    title="删除"
                    @click.stop="deleteTask(task.id, task.name)"
                  >
                    <MoreHorizontal class="size-xs" />
                  </button>
                </div>
                <!-- 隐形 toggle：给测试用（触发卡片点击展开） -->
                <span
                  :data-testid="`task-card-toggle-${task.id}`"
                  class="sr-only"
                  @click.stop="selectTask(task.id)"
                />
              </article>

              <!-- 过滤后空态 -->
              <div
                v-if="filteredTasks.length === 0"
                class="sched-empty flex flex-col items-center gap-md py-2xl col-span-full"
                data-testid="filtered-empty"
              >
                <Search class="size-xl text-muted-foreground" />
                <p class="text-sm font-medium text-foreground">没有匹配的任务</p>
                <button
                  type="button"
                  class="sched-link-btn text-xs text-primary"
                  @click="activeFilter = 'all'; searchQuery = ''"
                >清除筛选</button>
              </div>
            </div>

            <!-- 时间表视图 -->
            <div
              v-else-if="viewMode === 'timetable'"
              class="sched-timeline rounded-md overflow-hidden bg-card border"
              :data-testid="`placeholder-timetable`"
            >
              <!-- Note: 顶部副标题提示该视图持续迭代；"即将推出"一词同时用于测试断言 -->
              <div class="sched-text-small px-lg py-sm bg-muted border-b text-muted-foreground font-mono">
                时间表视图 · 完整聚合功能即将推出
              </div>
              <div class="sched-timeline__head sched-text-caption grid border-b bg-muted px-lg py-sm uppercase sched-tracking-wide text-muted-foreground font-medium">
                <div>任务</div>
                <div class="sched-timeline__hours font-mono grid">
                  <div
                    v-for="h in hourTicks"
                    :key="`th-${h}`"
                    class="text-center"
                  >{{ String(h).padStart(2, '0') }}</div>
                </div>
              </div>
              <div
                v-for="task in filteredTasks"
                :key="`tl-${task.id}`"
                :class="[
                  'sched-timeline__row grid items-center border-b cursor-pointer transition-colors',
                  { 'sched-timeline__row--active': selectedTaskId === task.id }
                ]"
                @click="selectTask(task.id)"
              >
                <div class="flex items-center gap-sm px-lg py-sm text-xs font-medium text-foreground">
                  <span
                    class="sched-dot shrink-0"
                    :class="`sched-dot--${timelineMarkStatus(task)}`"
                  />
                  <span class="truncate">{{ task.name }}</span>
                </div>
                <div class="sched-timeline__lane relative">
                  <div class="sched-timeline__grid absolute inset-0 grid">
                    <div v-for="h in hourTicks" :key="`g-${task.id}-${h}`" class="sched-timeline__cell" />
                  </div>
                  <div
                    class="sched-timeline__now absolute top-0 bottom-0"
                    :style="{ left: `${nowCursorPct}%` }"
                  />
                  <div
                    v-for="(d, idx) in ribbonDots.filter(x => x.task === task.id)"
                    :key="`m-${task.id}-${idx}`"
                    :class="['sched-timeline__mark', `sched-timeline__mark--${d.status}`]"
                    :style="{ left: `${d.pct}%` }"
                    :title="d.at"
                  />
                </div>
              </div>
              <div
                v-if="filteredTasks.length === 0"
                class="py-2xl text-center text-xs text-muted-foreground"
              >
                时间表视图即将推出：先在卡片视图查看任务列表。
              </div>
            </div>

            <!-- 执行历史视图 -->
            <div
              v-else-if="viewMode === 'history'"
              class="sched-history rounded-md overflow-hidden bg-card border"
              :data-testid="`placeholder-history`"
            >
              <div class="sched-text-small px-lg py-sm bg-muted border-b text-muted-foreground font-mono">
                执行历史视图 · 全局聚合即将推出
              </div>
              <template v-if="filteredTasks.length > 0">
                <div
                  v-for="task in filteredTasks"
                  :key="`hist-${task.id}`"
                  class="sched-history__row grid items-center gap-md px-lg py-sm border-b cursor-pointer transition-colors"
                  @click="selectTask(task.id)"
                >
                  <div class="sched-text-tiny font-mono text-muted-foreground">
                    {{ task.nextExecutionAt ? formatNextExecutionTime(task.nextExecutionAt) : '—' }}
                  </div>
                  <div :class="['sched-history__status', `sched-history__status--${lastRunStatus(task.id)}`]">
                    <i />
                    {{ lastRunStatus(task.id) === 'success' ? '成功' : lastRunStatus(task.id) === 'failed' ? '失败' : '未跑' }}
                  </div>
                  <div class="min-w-0">
                    <div class="text-xs font-medium text-foreground truncate">{{ task.name }}</div>
                    <div class="text-xs text-muted-foreground truncate">
                      {{ task.instruction || humanSchedule(task.schedule) }}
                    </div>
                  </div>
                  <div class="flex items-center gap-xs sched-text-tiny font-mono text-muted-foreground">
                    <span>{{ humanSchedule(task.schedule) }}</span>
                  </div>
                  <button type="button" class="sched-icon-btn" title="详情" @click.stop="selectTask(task.id)">
                    <ChevronRight class="size-xs" />
                  </button>
                </div>
              </template>
              <div
                v-else
                class="py-2xl text-center text-xs text-muted-foreground"
              >
                暂无执行历史 · 视图即将推出完整聚合
              </div>
            </div>
          </main>

          <!-- 右侧详情抽屉 -->
          <aside
            v-if="selectedTask"
            class="sched-detail flex flex-col gap-lg rounded-md bg-card border p-lg"
            :data-testid="`task-expanded-${selectedTask.id}`"
          >
            <!-- 关闭按钮 -->
            <div class="flex justify-end -mb-sm">
              <button
                type="button"
                class="sched-icon-btn"
                data-testid="detail-close"
                title="关闭详情"
                @click="closeDetail"
              >
                <X class="size-xs" />
              </button>
            </div>

            <!-- 顶部：agent + 名字 + 状态 -->
            <div class="flex justify-between items-start gap-sm">
              <div class="min-w-0 flex flex-col gap-xs">
                <div
                  class="sched-card__agent self-start"
                  :style="{
                    background: `${agentColorFor(selectedTask.projectId)}1e`,
                    color: agentColorFor(selectedTask.projectId),
                  }"
                >
                  {{ projectLabel(selectedTask.projectId) }}
                </div>
                <div class="font-serif text-base font-semibold tracking-tight text-foreground">
                  {{ selectedTask.name }}
                </div>
              </div>
              <div :class="['sched-status shrink-0', `sched-status--${selectedTask.status}`]">
                <i />
                {{ statusLabel(selectedTask.status) }}
              </div>
            </div>

            <!-- 描述 -->
            <div class="text-xs leading-relaxed text-foreground/80 pb-md border-b">
              {{ selectedTask.instruction || '（未填写指令）' }}
            </div>

            <!-- Cron 卡片（突出） -->
            <div class="sched-cron-card flex flex-col gap-xs px-md py-sm rounded-md">
              <div class="sched-text-micro font-mono uppercase sched-tracking-wider text-primary font-semibold">
                调度规则
              </div>
              <div class="text-sm font-semibold text-foreground">
                {{ humanSchedule(selectedTask.schedule) }}
              </div>
              <code class="sched-cron-card__raw sched-text-tiny font-mono text-foreground/90/70 px-xs rounded-sm bg-card self-start">
                {{ selectedTask.schedule }}
              </code>
              <div class="sched-cron-card__next pt-xs mt-xs text-xs text-foreground/80">
                下次 ·
                <template v-if="selectedTask.status === 'active' && selectedTask.nextExecutionAt">
                  <b class="text-foreground font-semibold">{{ formatNextExecutionTime(selectedTask.nextExecutionAt) }}</b>
                  <span class="sched-text-small font-mono text-muted-foreground"> · {{ relativeNext(selectedTask.nextExecutionAt) }}</span>
                </template>
                <template v-else>
                  <span class="text-muted-foreground">{{ selectedTask.status === 'paused' ? '已暂停' : '未计划' }}</span>
                </template>
              </div>
            </div>

            <!-- 执行指令 -->
            <div class="flex flex-col gap-xs">
              <div class="sched-detail__sec-title">执行指令</div>
              <p class="text-xs leading-relaxed text-foreground whitespace-pre-wrap break-words">
                {{ selectedTask.instruction || '（未填写指令）' }}
              </p>
            </div>

            <!-- 最近运行 KV -->
            <div class="flex flex-col gap-xs">
              <div class="sched-detail__sec-title">最近运行</div>
              <p
                v-if="loadingLogs[selectedTask.id]"
                class="text-xs text-muted-foreground"
                :data-testid="`logs-loading-${selectedTask.id}`"
              >加载中…</p>
              <p
                v-else-if="logErrors[selectedTask.id]"
                class="text-xs text-destructive"
                :data-testid="`logs-error-${selectedTask.id}`"
              >{{ logErrors[selectedTask.id] }}</p>
              <template v-else-if="logsByTask[selectedTask.id] && logsByTask[selectedTask.id].length > 0">
                <div
                  v-for="log in logsByTask[selectedTask.id]"
                  :key="log.id"
                  class="sched-detail__kv flex items-center justify-between gap-sm text-xs text-foreground/80"
                  :data-testid="`logs-list-${selectedTask.id}`"
                >
                  <span class="flex items-center gap-xs">
                    <span
                      class="sched-dot shrink-0"
                      :class="`sched-dot--${log.status === 'success' ? 'success' : 'failed'}`"
                    />
                    {{ formatExecutedAtTime(log.executedAt) }}
                  </span>
                  <span class="sched-text-small font-mono text-muted-foreground">
                    {{ logStatusLabel(log.status) }} · {{ log.durationMs }} ms
                    <template v-if="log.tokensUsed > 0"> · {{ log.tokensUsed }} tokens</template>
                  </span>
                </div>
                <p
                  v-if="logsByTask[selectedTask.id][0]?.summary"
                  class="text-xs text-foreground whitespace-pre-wrap break-words line-clamp-3"
                >{{ logsByTask[selectedTask.id][0].summary }}</p>
                <div class="sched-detail__kv flex items-center justify-between gap-sm text-xs text-foreground/80">
                  <span>累计运行</span>
                  <b class="text-foreground font-medium">
                    {{ logsByTask[selectedTask.id].length }} 次 ·
                    {{ Math.round((logsByTask[selectedTask.id].filter(l => l.status === 'success').length / Math.max(1, logsByTask[selectedTask.id].length)) * 100) }}% 成功
                  </b>
                </div>
              </template>
              <p
                v-else
                class="text-xs text-muted-foreground"
                :data-testid="`logs-empty-${selectedTask.id}`"
              >还未执行过</p>
            </div>

            <!-- 调用工具 -->
            <div class="flex flex-col gap-xs">
              <div class="sched-detail__sec-title">
                调用工具 · {{ parseSkillIds(selectedTask.skillIds).length }}
              </div>
              <div class="flex flex-wrap gap-xs mt-xs">
                <span
                  v-for="skill in parseSkillIds(selectedTask.skillIds)"
                  :key="skill"
                  class="sched-tool font-mono"
                >{{ skill }}</span>
                <span
                  v-if="parseSkillIds(selectedTask.skillIds).length === 0"
                  class="sched-tool font-mono italic"
                >未绑定</span>
              </div>
            </div>

            <!-- 底部操作按钮 -->
            <div class="sched-detail__actions flex gap-xs pt-xs">
              <button
                v-if="selectedTask.status === 'active'"
                type="button"
                class="sched-btn sched-btn--ghost flex-1 justify-center"
                @click="togglePause(selectedTask.id, selectedTask.status)"
              >
                <PauseCircle class="size-xs" />
                暂停
              </button>
              <button
                v-else
                type="button"
                class="sched-btn sched-btn--primary flex-1 justify-center"
                @click="togglePause(selectedTask.id, selectedTask.status)"
              >
                <Play class="size-xs" />
                启用
              </button>
              <button
                type="button"
                class="sched-btn sched-btn--ghost flex-1 justify-center"
                @click="showPlaceholder('立即运行即将推出')"
              >
                <Sparkles class="size-xs" />
                立即运行
              </button>
              <button
                type="button"
                class="sched-btn sched-btn--ghost flex-1 justify-center"
                @click="openEdit(selectedTask)"
              >
                <Edit3 class="size-xs" />
                编辑
              </button>
            </div>

            <!-- 创建于 -->
            <div class="sched-text-caption font-mono text-muted-foreground text-center pt-xs border-t border-dashed">
              创建于 {{ relativeCreated(selectedTask.createdAt) }}
            </div>
          </aside>
        </section>
      </div>
    </div>

    <!-- 编辑弹窗 -->
    <ScheduledTaskEditDialog
      v-model:open="editDialogOpen"
      :task="editingTask"
    />
  </div>
</template>

<style scoped>
/* 本文件只保留 Tailwind 无法表达的少量 token / 绝对定位 / 状态动画 / 颜色变体。
   其余布局已改用 Tailwind 命名尺度（xs/sm/md/lg/xl/2xl）。

   注：草稿里大量使用 9.5/10/10.5/11/11.5 px 这类非 Tailwind 标准字号，
   Tailwind 命名字号从 12px 起跳（text-xs=12），不足以表达草稿原本的字号层级。
   为了保持视觉接近草稿又不违反"禁止 p-3/text-[11px] 任意值"的前端规范，
   我们以非 text-[...] 形式在此处用语义化 class 定义这些字号层级。 */

/* 草稿字号层级 → 语义化 utility */
.sched-text-caption-xs { font-size: 9.5px; opacity: 0.75; }
.sched-text-micro { font-size: 10px; }
.sched-text-caption { font-size: 10.5px; }
.sched-text-small { font-size: 11px; }
.sched-text-tiny { font-size: 11.5px; }

/* 草稿里的字距 */
.sched-tracking-wide { letter-spacing: 0.08em; }
.sched-tracking-wider { letter-spacing: 0.1em; }

/* 详情 KV 行垂直内边距（5px，非命名尺度） */
.sched-detail__kv { padding: 5px 0; }

/* cron raw 最长宽度 */
.sched-cron-raw-inline { max-width: 50%; }

/* ══════════ 顶栏按钮 ══════════ */
.header-btn {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.4rem 0.75rem;
  font-size: 12px;
  color: var(--foreground);
  background: transparent;
  border: 1px solid transparent;
  border-radius: 0.375rem;
  cursor: pointer;
  transition: background 160ms, color 160ms, border-color 160ms;
}

.header-btn:hover {
  background: hsl(from var(--muted) h s l / 0.6);
}

.header-btn--primary {
  background: var(--foreground);
  color: var(--background);
  border-color: var(--foreground);
}

.header-btn--primary:hover {
  background: hsl(from var(--foreground) h s l / 0.88);
}

/* ══════════ 占位 Toast ══════════ */
.placeholder-toast {
  position: absolute;
  top: 4rem;
  left: 50%;
  transform: translateX(-50%);
  z-index: 20;
  background: var(--card);
  border: 1px solid hsl(from var(--primary) h s l / 0.3);
  border-radius: 0.75rem;
  box-shadow: 0 10px 24px -12px hsl(var(--shadow-color) / 0.25);
}

.toast-enter-active,
.toast-leave-active {
  transition: opacity 200ms, transform 200ms;
}

.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translate(-50%, -8px);
}

/* ══════════ Hero ribbon ══════════ */
.sched-hero {
  background:
    linear-gradient(135deg,
      hsl(from var(--primary) h s l / 0.08),
      hsl(from var(--card) h s l) 65%);
  border: 1px solid hsl(from var(--primary) h s l / 0.24);
  box-shadow: 0 1px 3px hsl(from var(--primary) h s l / 0.14);
}

/* Hero 的小 pill */
.sched-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 10px;
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 999px;
  font-size: 11.5px;
  color: var(--foreground);
}

.sched-chip > i {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  display: inline-block;
}

.sched-chip--success > i { background: #0a6e53; }
.sched-chip--failed > i { background: var(--destructive); }
.sched-chip--upcoming > i { background: hsl(from var(--muted-foreground) h s l); }

/* 24h ribbon */
.sched-ribbon {
  position: relative;
  padding-top: 26px;
}

.sched-ribbon__axis {
  position: relative;
  height: 18px;
}

.sched-ribbon__axis span {
  position: absolute;
  font-size: 10px;
  color: var(--muted-foreground);
  transform: translateX(-50%);
}

.sched-ribbon__track {
  position: relative;
  height: 3px;
  background: var(--border);
  border-radius: 2px;
  margin-top: 4px;
}

.sched-ribbon__now {
  position: absolute;
  top: -18px;
  bottom: -8px;
  width: 1.5px;
  background: var(--primary);
  z-index: 2;
}

.sched-ribbon__now::after {
  content: "NOW";
  position: absolute;
  top: -14px;
  left: 50%;
  transform: translateX(-50%);
  font-family: var(--font-mono);
  font-size: 9.5px;
  font-weight: 600;
  color: var(--primary);
  letter-spacing: 0.08em;
}

.sched-ribbon__dot {
  position: absolute;
  top: -6px;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  border: 2.5px solid var(--card);
  cursor: pointer;
  transform: translateX(-50%);
  transition: transform 0.15s;
  z-index: 3;
}

.sched-ribbon__dot:hover {
  transform: translateX(-50%) scale(1.3);
}

.sched-ribbon__dot:hover .sched-ribbon__tip {
  opacity: 1;
  visibility: visible;
}

.sched-ribbon__dot--success { background: #0a6e53; }
.sched-ribbon__dot--failed { background: var(--destructive); }
.sched-ribbon__dot--upcoming {
  background: var(--card);
  border-color: var(--muted-foreground);
  border-style: dashed;
}

.sched-ribbon__tip {
  position: absolute;
  bottom: 22px;
  left: 50%;
  transform: translateX(-50%);
  background: var(--foreground);
  color: var(--background);
  padding: 6px 10px;
  border-radius: 0.375rem;
  font-size: 11px;
  white-space: nowrap;
  opacity: 0;
  visibility: hidden;
  transition: opacity 0.15s;
  pointer-events: none;
  z-index: 10;
}

/* ══════════ KPI 行 ══════════ */
.kpi-row {
  grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
}

.sched-stat {
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: 0.625rem;
  background: var(--card);
  display: flex;
  flex-direction: column;
}

.sched-stat--warn {
  border-color: hsl(from var(--destructive) h s l / 0.4);
  background: hsl(from var(--destructive) h s l / 0.06);
}

/* ══════════ Segmented 视图切换 ══════════ */
.sched-seg {
  background: hsl(from var(--muted) h s l / 0.65);
  border: 1px solid hsl(from var(--border) h s l / 0.5);
  padding: 2px;
  gap: 1px;
}

.sched-filters {
  gap: 2px;
}

.sched-seg-opt {
  padding: 5px 12px;
  font-size: 12px;
  color: var(--muted-foreground);
  background: transparent;
  border: none;
  border-radius: 0.3rem;
  cursor: pointer;
  transition: all 0.12s;
}

.sched-seg-opt:hover {
  color: var(--foreground);
}

.sched-seg-opt--active {
  background: var(--card);
  color: var(--foreground);
  box-shadow: 0 1px 2px hsl(var(--shadow-color) / 0.05);
  font-weight: 500;
}

/* ══════════ 过滤 pill ══════════ */
.sched-filter {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  background: transparent;
  border: 1px solid transparent;
  border-radius: 0.375rem;
  font-size: 12px;
  color: var(--muted-foreground);
  cursor: pointer;
  transition: all 0.12s;
}

.sched-filter:hover {
  color: var(--foreground);
  background: hsl(from var(--muted) h s l / 0.5);
}

.sched-filter--active {
  color: var(--foreground);
  background: hsl(from var(--muted) h s l / 0.7);
  border-color: var(--border);
  font-weight: 500;
}

.sched-filter__n {
  font-size: 10.5px;
  color: var(--muted-foreground);
  background: var(--card);
  padding: 1px 5px;
  border-radius: 999px;
}

.sched-filter--active .sched-filter__n {
  color: var(--primary);
  background: hsl(from var(--primary) h s l / 0.12);
}

/* ══════════ 搜索 ══════════ */
.sched-search {
  min-width: 220px;
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 0.375rem;
  transition: border-color 0.12s;
}

.sched-search:focus-within {
  border-color: var(--primary);
}

.sched-search__input::placeholder {
  color: var(--muted-foreground);
}

/* ══════════ 工作区布局 ══════════ */
.sched-workspace {
  grid-template-columns: 1fr 340px;
}

@media (max-width: 1200px) {
  .sched-workspace {
    grid-template-columns: 1fr;
  }
  .sched-detail {
    display: none;
  }
}

/* ══════════ 卡片网格 ══════════ */
.sched-grid {
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
}

.sched-card {
  position: relative;
  padding: 14px 16px 16px;
  border: 1px solid var(--border);
  border-radius: 0.625rem;
  background: var(--card);
  cursor: pointer;
  transition: all 0.15s;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.sched-card:hover {
  border-color: hsl(from var(--border) h s l / 0.9);
  transform: translateY(-1px);
  box-shadow:
    0 1px 2px hsl(var(--shadow-color) / 0.04),
    0 2px 6px hsl(var(--shadow-color) / 0.04);
}

.sched-card--active {
  border-color: var(--primary);
  background: hsl(from var(--primary) h s l / 0.05);
}

.sched-card--paused {
  opacity: 0.7;
}

.sched-card--draft {
  background: hsl(from var(--muted) h s l / 0.5);
  border-style: dashed;
}

.sched-card--error {
  border-color: hsl(from var(--destructive) h s l / 0.4);
}

.sched-card__agent {
  display: inline-block;
  padding: 2px 10px;
  border-radius: 999px;
  font-size: 11.5px;
  border: none;
  cursor: pointer;
}

.sched-card__agent:disabled {
  cursor: default;
}

.sched-card__name {
  font-size: 15px;
  font-weight: 600;
  letter-spacing: -0.01em;
}

.sched-card__desc {
  font-size: 12px;
  line-height: 1.55;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/* 状态 badge */
.sched-status {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 2px 9px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 500;
  border: 1px solid transparent;
}

.sched-status > i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  display: inline-block;
}

.sched-status--active {
  color: #0a6e53;
  background: #e4f5ef;
  border-color: #c3e8d9;
}

.sched-status--active > i {
  background: #0a6e53;
  animation: sched-pulse 1.8s ease-out infinite;
}

.sched-status--paused {
  color: var(--foreground);
  background: hsl(from var(--muted) h s l / 0.7);
  border-color: var(--border);
}

.sched-status--paused > i { background: var(--muted-foreground); }

.sched-status--draft {
  color: var(--muted-foreground);
  background: hsl(from var(--muted) h s l / 0.7);
  border-color: var(--border);
}

.sched-status--draft > i { background: var(--muted-foreground); }

.sched-status--error {
  color: var(--destructive);
  background: hsl(from var(--destructive) h s l / 0.1);
  border-color: hsl(from var(--destructive) h s l / 0.25);
}

.sched-status--error > i {
  background: var(--destructive);
  animation: sched-pulse-red 1.2s ease-out infinite;
}

@keyframes sched-pulse {
  0% { box-shadow: 0 0 0 0 rgba(10, 110, 83, 0.6); }
  100% { box-shadow: 0 0 0 6px rgba(10, 110, 83, 0); }
}

@keyframes sched-pulse-red {
  0% { box-shadow: 0 0 0 0 hsl(from var(--destructive) h s l / 0.6); }
  100% { box-shadow: 0 0 0 6px hsl(from var(--destructive) h s l / 0); }
}

/* cron 灰底条 */
.sched-card__cron {
  background: hsl(from var(--muted) h s l / 0.6);
}

/* meta 两列 */
.sched-meta-label {
  font-size: 10.5px;
  color: var(--muted-foreground);
  text-transform: uppercase;
  letter-spacing: 0.06em;
  margin-bottom: 2px;
}

.sched-meta-value {
  font-size: 12.5px;
  color: var(--foreground);
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sched-meta-sub {
  font-size: 10.5px;
  color: var(--muted-foreground);
  font-weight: 400;
  margin-left: 4px;
}

/* 状态点 */
.sched-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  display: inline-block;
}

.sched-dot--success { background: #0a6e53; }
.sched-dot--failed { background: var(--destructive); }
.sched-dot--idle,
.sched-dot--upcoming { background: var(--muted-foreground); }

/* 底部 */
.sched-card__foot {
  border-top: 1px dashed var(--border);
}

.sched-tool {
  display: inline-flex;
  align-items: center;
  font-size: 10.5px;
  padding: 1px 7px;
  background: hsl(from var(--muted) h s l / 0.6);
  color: var(--muted-foreground);
  border: 1px solid var(--border);
  border-radius: 999px;
}

.sched-tool--dim { opacity: 0.6; }

/* 卡片右上角操作 */
.sched-card__actions {
  position: absolute;
  top: 10px;
  right: 10px;
  gap: 3px;
  opacity: 0;
  transition: opacity 0.15s;
}

.sched-card:hover .sched-card__actions,
.sched-card--active .sched-card__actions {
  opacity: 1;
}

.sched-icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 0.375rem;
  cursor: pointer;
  color: var(--muted-foreground);
  transition: all 0.12s;
}

.sched-icon-btn:hover {
  background: hsl(from var(--muted) h s l / 0.7);
  color: var(--foreground);
  border-color: hsl(from var(--border) h s l / 0.9);
}

.sched-icon-btn--danger:hover {
  color: var(--destructive);
  background: hsl(from var(--destructive) h s l / 0.08);
}

/* ══════════ Timeline 视图 ══════════ */
.sched-timeline__head,
.sched-timeline__row {
  grid-template-columns: 220px 1fr;
}

.sched-timeline__hours {
  grid-template-columns: repeat(8, 1fr);
}

.sched-timeline__row {
  min-height: 44px;
}

.sched-timeline__lane {
  height: 44px;
}

.sched-timeline__row:hover {
  background: hsl(from var(--muted) h s l / 0.5);
}

.sched-timeline__row--active {
  background: hsl(from var(--primary) h s l / 0.08);
}

.sched-timeline__grid {
  grid-template-columns: repeat(8, 1fr);
}

.sched-timeline__cell {
  border-left: 1px dashed var(--border);
}

.sched-timeline__cell:first-child {
  border-left: none;
}

.sched-timeline__now {
  width: 1.5px;
  background: var(--primary);
  opacity: 0.6;
  z-index: 2;
}

.sched-timeline__mark {
  position: absolute;
  top: 50%;
  transform: translate(-50%, -50%);
  width: 10px;
  height: 10px;
  border-radius: 50%;
  border: 2px solid var(--card);
  z-index: 3;
}

.sched-timeline__mark--success { background: #0a6e53; }
.sched-timeline__mark--failed { background: var(--destructive); }
.sched-timeline__mark--upcoming {
  background: var(--card);
  border: 2px dashed var(--muted-foreground);
}

/* ══════════ History 视图 ══════════ */
.sched-history__row {
  grid-template-columns: 90px 80px 1fr auto 24px;
}

.sched-history__row:hover {
  background: hsl(from var(--muted) h s l / 0.5);
}

.sched-history__row:last-child {
  border-bottom: none;
}

.sched-history__status {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 2px 9px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 500;
  width: fit-content;
}

.sched-history__status > i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  display: inline-block;
}

.sched-history__status--success {
  color: #0a6e53;
  background: #e4f5ef;
}

.sched-history__status--success > i { background: #0a6e53; }

.sched-history__status--failed {
  color: var(--destructive);
  background: hsl(from var(--destructive) h s l / 0.1);
}

.sched-history__status--failed > i { background: var(--destructive); }

.sched-history__status--idle {
  color: var(--muted-foreground);
  background: hsl(from var(--muted) h s l / 0.6);
}

.sched-history__status--idle > i { background: var(--muted-foreground); }

.sched-history__sep {
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: var(--muted-foreground);
}

/* ══════════ 详情抽屉 ══════════ */
.sched-detail {
  height: fit-content;
  position: sticky;
  top: 12px;
  max-height: calc(100vh - 40px);
  overflow-y: auto;
}

.sched-detail__sec-title {
  font-size: 11px;
  font-weight: 600;
  color: var(--muted-foreground);
  text-transform: uppercase;
  letter-spacing: 0.08em;
}

.sched-detail__kv {
  border-bottom: 1px dashed var(--border);
}

.sched-detail__kv:last-child {
  border-bottom: none;
}

.sched-detail__actions .sched-btn {
  font-size: 12px;
  padding: 8px 6px;
}

/* Cron 卡片（详情里的突出块） */
.sched-cron-card {
  background: hsl(from var(--primary) h s l / 0.05);
  border: 1px solid hsl(from var(--primary) h s l / 0.2);
}

.sched-cron-card__raw {
  border: 1px solid var(--border);
}

.sched-cron-card__next {
  border-top: 1px dashed var(--border);
}

/* 详情底部通用按钮 */
.sched-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  font-size: 12px;
  border-radius: 0.375rem;
  cursor: pointer;
  transition: all 0.12s;
}

.sched-btn--ghost {
  color: var(--foreground);
  background: transparent;
  border: 1px solid var(--border);
}

.sched-btn--ghost:hover {
  background: hsl(from var(--muted) h s l / 0.6);
  border-color: hsl(from var(--border) h s l / 0.9);
}

.sched-btn--primary {
  color: var(--background);
  background: var(--foreground);
  border: 1px solid var(--foreground);
}

.sched-btn--primary:hover {
  background: hsl(from var(--foreground) h s l / 0.88);
}

/* 空态链接按钮 */
.sched-link-btn {
  background: transparent;
  border: none;
  cursor: pointer;
}

.sched-link-btn:hover {
  text-decoration: underline;
}

/* 主内容空态 */
.sched-empty {
  border: 1px dashed var(--border);
  border-radius: 0.625rem;
  background: var(--card);
}

/* dark 模式下的状态 badge 微调 */
:global(.dark) .sched-status--active {
  color: rgb(110 231 183);
  background: rgb(6 78 59 / 0.3);
  border-color: rgb(5 150 105 / 0.4);
}

:global(.dark) .sched-chip--success > i { background: rgb(110 231 183); }
:global(.dark) .sched-history__status--success {
  color: rgb(110 231 183);
  background: rgb(6 78 59 / 0.3);
}
:global(.dark) .sched-history__status--success > i { background: rgb(110 231 183); }
:global(.dark) .sched-ribbon__dot--success { background: rgb(110 231 183); }
:global(.dark) .sched-timeline__mark--success { background: rgb(110 231 183); }
:global(.dark) .sched-dot--success { background: rgb(110 231 183); }
</style>