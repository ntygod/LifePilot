<script setup lang="ts">
/**
 * 定时任务全局管理页 —— 2026-04-24 视觉重做。
 *
 * <p>草稿还原：</p>
 * <ul>
 *   <li>顶部：面包屑 + 全部历史/暂停全部/新建任务 3 个按钮（功能占位）</li>
 *   <li>Hero：日期 + 叙事大标题 + 成功/失败/待跑 badges + 24h 时间轴</li>
 *   <li>KPI：5 张指标卡片（数据由任务列表派生或 mock）</li>
 *   <li>工具栏：视图切换（卡片/时间表/执行历史）+ 过滤 pill + 搜索框</li>
 *   <li>主区：3 列卡片网格；点击卡片展开右侧 ~360px 详情面板</li>
 *   <li>详情面板：调度规则、下次时间、最近运行、权限信任、工具、结果投递</li>
 * </ul>
 *
 * <p><b>数据来源</b>：真实数据包括任务列表、状态、cron、skillIds、下次执行时间、
 * 执行日志；mock / 静态数据包括时间轴点位、KPI 数值（花费/预算/同比）、
 * 风险等级、单次花费估算、结果投递渠道。</p>
 *
 * <p><b>创建入口</b>：由 LLM 在对话中自然语言触发创建（如"每周日 21 点提醒我..."），
 * 本页「新建任务」按钮是视觉占位。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  CalendarClock,
  ChevronRight,
  Clock,
  Edit3,
  History,
  MoreHorizontal,
  PauseCircle,
  Plus,
  RefreshCw,
  Search,
  Send,
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
  // 并行拉取，失败只写入各自 store.error，不阻塞另一条
  await Promise.all([
    store.fetchAll().catch(() => {}),
    projectStore.fetchProjects().catch(() => {}),
  ])
})

/** 项目 ID → 名称 的查找表 */
const projectNameById = computed(() => {
  const map = new Map<string, string>()
  for (const p of projectStore.projects) map.set(p.id, p.name)
  return map
})

/** 主账户任务显示"主"，项目任务显示项目名；项目已删除则退化为 ID */
function projectLabel(projectId: string | null): string {
  if (!projectId) return '主'
  return projectNameById.value.get(projectId) ?? projectId
}

/** 跳转到项目详情页 */
function openProject(projectId: string) {
  router.push({ name: 'projectDetail', params: { id: projectId } })
}

/** 根据当前状态在 active/paused 之间切换 */
async function togglePause(taskId: string, currentStatus: string) {
  if (currentStatus === 'active') {
    await store.pauseTask(taskId)
  } else {
    await store.resumeTask(taskId)
  }
}

/** 删除前弹窗确认；取消则直接返回 */
async function deleteTask(taskId: string, taskName: string) {
  if (!window.confirm(`确认删除定时任务「${taskName}」？此操作不可撤销。`)) return
  await store.deleteTask(taskId)
  if (selectedTaskId.value === taskId) selectedTaskId.value = null
}

// ──────────────────────────────────────────────────────────────────────
// 视图切换 & 过滤 & 搜索
// ──────────────────────────────────────────────────────────────────────

type ViewMode = 'cards' | 'timetable' | 'history'
const viewMode = ref<ViewMode>('cards')

type FilterKey = 'all' | 'active' | 'paused' | 'error' | 'draft'
const activeFilter = ref<FilterKey>('all')

const searchQuery = ref('')

/** 过滤后的任务列表（按 status + 搜索关键字） */
const filteredTasks = computed(() => {
  const query = searchQuery.value.trim().toLowerCase()
  return store.tasks.filter(task => {
    // status 过滤
    if (activeFilter.value !== 'all') {
      if (task.status !== activeFilter.value) return false
    }
    // 关键字搜索（name + instruction）
    if (query) {
      const haystack = `${task.name} ${task.instruction}`.toLowerCase()
      if (!haystack.includes(query)) return false
    }
    return true
  })
})

/** 各状态的任务数量——驱动过滤 pill 的数字徽标 */
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
// 详情面板：选中任务 + 日志懒加载
// ──────────────────────────────────────────────────────────────────────

/** 当前选中（展开详情面板）的任务 id；null 表示未选中 */
const selectedTaskId = ref<string | null>(null)

/** 已拉取的日志缓存；按 taskId 索引 */
const logsByTask = reactive<Record<string, ScheduledTaskLogDto[]>>({})
/** 正在加载日志的任务 id 集合 */
const loadingLogs = reactive<Record<string, boolean>>({})
/** 日志加载失败提示 */
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

// 如果当前选中的任务被删除或过滤掉，自动关闭详情
watch(filteredTasks, list => {
  if (selectedTaskId.value && !list.some(t => t.id === selectedTaskId.value)) {
    // 注意：被过滤掉（而非真的删了）时也关闭，避免孤悬详情
    if (!store.tasks.some(t => t.id === selectedTaskId.value)) {
      selectedTaskId.value = null
    }
  }
})

// ──────────────────────────────────────────────────────────────────────
// 编辑弹窗
// ──────────────────────────────────────────────────────────────────────

const editDialogOpen = ref(false)
const editingTask = ref<ScheduledTaskDto | null>(null)

function openEdit(task: ScheduledTaskDto) {
  editingTask.value = task
  editDialogOpen.value = true
}

// ──────────────────────────────────────────────────────────────────────
// 顶部按钮占位 Toast（功能未实装）
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
// Hero / 时间轴 / KPI —— 大部分 mock；从真实数据派生的地方会标注
// ──────────────────────────────────────────────────────────────────────

/** 今天日期串，如 2026-04-24 · 周五 */
const todayLabel = computed(() => {
  const now = new Date()
  const year = now.getFullYear()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  const weekdays = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
  return `${year}-${month}-${day} · ${weekdays[now.getDay()]}`
})

/** "今天已完成 / 待跑" 叙事派生：基于 status 的粗略近似（mock） */
const narrativeCounts = computed(() => {
  const total = store.tasks.length
  // mock 分配：3/4 作为"已完成"，1/4 作为"待跑"，最少 1
  const done = Math.max(1, Math.floor((total * 3) / 4))
  const pending = Math.max(0, total - done)
  return { done: total === 0 ? 6 : done, pending: total === 0 ? 2 : pending }
})

/** 成功/失败/待跑 的 badges 数（mock 派生） */
const heroBadges = computed(() => ({
  success: narrativeCounts.value.done > 1 ? narrativeCounts.value.done - 1 : narrativeCounts.value.done,
  failed: narrativeCounts.value.done > 0 ? Math.min(2, Math.max(0, Math.floor(narrativeCounts.value.done / 3))) : 0,
  pending: narrativeCounts.value.pending,
}))

/** 24h 时间轴刻度 */
const timelineHours = ['00:00', '03:00', '06:00', '09:00', '12:00', '15:00', '18:00', '21:00']

/**
 * 时间轴执行点位（mock）。
 *
 * <p>真实实现需要查询当天所有任务的 logs 并按小时聚合——目前 mock 5 个点
 * 分散在时间轴上，代表"今天已经跑过的任务"。</p>
 */
const timelinePoints = [
  { hourPct: 4, status: 'success' as const },
  { hourPct: 22, status: 'success' as const },
  { hourPct: 33, status: 'failed' as const },
  { hourPct: 50, status: 'success' as const },
  { hourPct: 58, status: 'success' as const },
  { hourPct: 70, status: 'success' as const },
  { hourPct: 82, status: 'failed' as const },
]

/** 当前时间在 24h 轴上的百分比位置（now 游标） */
const nowCursorPct = computed(() => {
  const now = new Date()
  const minutes = now.getHours() * 60 + now.getMinutes()
  return (minutes / 1440) * 100
})

interface KpiCard {
  id: string
  value: string | number
  label: string
  caption: string
  alert?: boolean
}

/** KPI 卡片：大多 mock，从真实 tasks 派生「总任务」行 */
const kpis = computed<KpiCard[]>(() => {
  const total = store.tasks.length
  const active = statusCounts.value.active
  const paused = statusCounts.value.paused
  const draft = statusCounts.value.draft
  return [
    {
      id: 'total',
      value: total,
      label: '总任务',
      caption: `${active} 运行 · ${paused} 暂停 · ${draft} 草稿`,
    },
    {
      id: 'monthly-runs',
      value: '2,675',
      label: '本月执行',
      caption: '相比上月 +12%',
    },
    {
      id: 'success-rate',
      value: '96.2%',
      label: '成功率',
      caption: '7 日滚动平均',
    },
    {
      id: 'cost',
      value: '$8.42',
      label: '本月花费',
      caption: '预算 $20 · 41%',
    },
    {
      id: 'attention',
      value: '1',
      label: '需注意',
      caption: '「API 用量告警」连续失败',
      alert: true,
    },
  ]
})

// ──────────────────────────────────────────────────────────────────────
// 卡片展示辅助
// ──────────────────────────────────────────────────────────────────────

/** 把 cron 表达式翻译成一句人话（做最常见情况的识别，其他降级为 "自定义"） */
function humanSchedule(cron: string): string {
  const trimmed = cron.trim()
  const parts = trimmed.split(/\s+/)
  // Spring 6 位：秒 分 时 日 月 周；UNIX 5 位：分 时 日 月 周
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

  // 固定时分才有意义
  if (!/^\d+$/.test(minute) || !/^\d+$/.test(hour)) return '自定义周期'

  // 每天 HH:mm
  if ((dom === '*' || dom === '?') && month === '*' && (dow === '*' || dow === '?')) {
    return `每天 ${hhmm(hour, minute)}`
  }
  // 每周 X HH:mm
  if ((dom === '*' || dom === '?') && month === '*' && dow !== '*' && dow !== '?') {
    const label = weekdayMap[dow.toUpperCase()] ?? `周${dow}`
    return `每${label} ${hhmm(hour, minute)}`
  }
  // 每月 D 日 HH:mm
  if (dom !== '*' && dom !== '?' && month === '*' && (dow === '*' || dow === '?')) {
    return `每月 ${dom} 日 ${hhmm(hour, minute)}`
  }
  return `定时 ${hhmm(hour, minute)}`
}

/** 把 skillIds (逗号分隔) 拆成数组 */
function parseSkillIds(skillIds: string | null): string[] {
  if (!skillIds) return []
  return skillIds.split(',').map(s => s.trim()).filter(Boolean)
}

/** 项目 tag 的颜色类——按 projectId 哈希派生一个稳定颜色（mock） */
function projectTagClass(projectId: string | null): string {
  if (!projectId) return 'tag-neutral'
  const palette = ['tag-orange', 'tag-blue', 'tag-teal', 'tag-violet', 'tag-rose']
  let hash = 0
  for (let i = 0; i < projectId.length; i += 1) {
    hash = (hash * 31 + projectId.charCodeAt(i)) >>> 0
  }
  return palette[hash % palette.length]
}

/** 状态文案 */
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

/** 状态点的颜色 class */
function statusDotClass(status: string): string {
  switch (status) {
    case 'active': return 'bg-emerald-500'
    case 'paused': return 'bg-zinc-400'
    case 'completed': return 'bg-sky-500'
    case 'error': return 'bg-rose-500'
    case 'draft': return 'bg-zinc-300'
    default: return 'bg-zinc-400'
  }
}

/** 状态 badge 样式 */
function statusBadgeClass(status: string): string {
  switch (status) {
    case 'active': return 'badge-success'
    case 'paused': return 'badge-muted'
    case 'completed': return 'badge-info'
    case 'error': return 'badge-danger'
    case 'draft': return 'badge-draft'
    default: return 'badge-muted'
  }
}

/** 日志状态点（细节面板） */
function logStatusDotClass(status: string): string {
  switch (status) {
    case 'success': return 'bg-emerald-500'
    case 'failed': return 'bg-rose-500'
    case 'running': return 'bg-sky-500'
    case 'timeout': return 'bg-amber-500'
    default: return 'bg-zinc-400'
  }
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

/** 距下次执行的人话相对时间（如 "13h 24m"），null 返回空串 */
function relativeNext(iso: string | null): string {
  if (!iso) return ''
  const diffMs = new Date(iso).getTime() - Date.now()
  if (diffMs <= 0) return ''
  const hours = Math.floor(diffMs / 3_600_000)
  const minutes = Math.floor((diffMs % 3_600_000) / 60_000)
  if (hours > 24) {
    const days = Math.floor(hours / 24)
    return `${days}d ${hours % 24}h`
  }
  return `${hours}h ${minutes}m`
}

/** 上次执行时间（mock + 真实结合）：优先读 logsByTask 里的第一条 */
function lastRunLabel(taskId: string): string {
  const logs = logsByTask[taskId]
  if (logs && logs.length > 0) {
    return formatExecutedAtTime(logs[0].executedAt)
  }
  return '暂无'
}

function lastRunStatus(taskId: string): string {
  const logs = logsByTask[taskId]
  if (logs && logs.length > 0) return logs[0].status
  return 'unknown'
}

/** 单次花费（mock） */
function singleRunCost(taskId: string): string {
  // 按 id 长度派生一个稳定伪随机的 $0.0x 值
  const hash = taskId.length * 7 + taskId.charCodeAt(0)
  const cents = (hash % 20) + 5 // 5~24
  return `~$${(cents / 100).toFixed(2)}`
}

/** 风险等级（mock，全部按"低风险"显示） */
function riskLevel(_task: ScheduledTaskDto): { label: string; cls: string } {
  return { label: '低风险', cls: 'risk-low' }
}
</script>

<template>
  <div class="scheduled-tasks-view">
    <!-- ══════════ 顶部 header bar ══════════ -->
    <header class="page-header-bar">
      <nav class="breadcrumb" aria-label="面包屑">
        <span class="breadcrumb-item">工作台</span>
        <ChevronRight class="breadcrumb-sep" />
        <span class="breadcrumb-current">定时任务</span>
      </nav>
      <div class="header-actions">
        <button
          type="button"
          class="header-btn"
          data-testid="view-all-history"
          @click="handleViewAllHistory"
        >
          <History class="size-4" />
          <span>全部历史</span>
        </button>
        <button
          type="button"
          class="header-btn"
          data-testid="pause-all"
          @click="handlePauseAll"
        >
          <PauseCircle class="size-4" />
          <span>暂停全部</span>
        </button>
        <button
          type="button"
          class="header-btn header-btn--primary"
          data-testid="create-task"
          @click="handleCreateTask"
        >
          <Plus class="size-4" />
          <span>新建任务</span>
        </button>
      </div>
    </header>

    <!-- 功能占位 Toast -->
    <Transition name="toast">
      <div v-if="placeholderMessage" class="placeholder-toast" role="status">
        <Sparkles class="size-4 shrink-0 text-primary" />
        <span>{{ placeholderMessage }}</span>
      </div>
    </Transition>

    <!-- ══════════ 主 + 侧 详情布局 ══════════ -->
    <div class="content-split">
      <div class="main-column scrollbar-thin">
        <!-- ═════ Hero：叙事 + 时间轴 ═════ -->
        <section class="hero-zone">
          <div class="hero-top">
            <div class="hero-left">
              <div class="today-tag">
                <span class="today-label">TODAY</span>
                <span class="today-divider" />
                <span>{{ todayLabel }}</span>
              </div>
              <h1 class="hero-headline">
                今天知微替你完成了
                <strong>{{ narrativeCounts.done }}</strong>
                件事，还有
                <strong>{{ narrativeCounts.pending }}</strong>
                件将发生
              </h1>
            </div>
            <div class="hero-right">
              <div class="hero-badge hero-badge--success">
                <span class="dot bg-emerald-500" />
                成功 {{ heroBadges.success }}
              </div>
              <div class="hero-badge hero-badge--failed">
                <span class="dot bg-rose-500" />
                失败 {{ heroBadges.failed }}
              </div>
              <div class="hero-badge hero-badge--pending">
                <span class="dot bg-zinc-400" />
                待跑 {{ heroBadges.pending }}
              </div>
            </div>
          </div>

          <!-- 24h 时间轴 -->
          <div class="timeline" aria-label="今日执行时间轴">
            <div class="timeline-track">
              <!-- 执行点位 -->
              <span
                v-for="(p, idx) in timelinePoints"
                :key="idx"
                class="timeline-point"
                :class="p.status === 'success' ? 'bg-emerald-500' : 'bg-rose-500'"
                :style="{ left: `${p.hourPct}%` }"
                :aria-label="`${p.status === 'success' ? '成功' : '失败'} 执行点`"
              />
              <!-- NOW 游标 -->
              <span
                class="timeline-cursor"
                :style="{ left: `${nowCursorPct}%` }"
              >
                <span class="timeline-cursor-label">NOW</span>
              </span>
            </div>
            <div class="timeline-scale">
              <span v-for="h in timelineHours" :key="h" class="timeline-scale-tick">
                {{ h }}
              </span>
            </div>
          </div>
        </section>

        <!-- ═════ KPI 卡片行 ═════ -->
        <section class="kpi-row" data-testid="kpi-row">
          <div
            v-for="kpi in kpis"
            :key="kpi.id"
            class="kpi-card"
            :class="{ 'kpi-card--alert': kpi.alert }"
          >
            <div class="kpi-value">{{ kpi.value }}</div>
            <div class="kpi-label">{{ kpi.label }}</div>
            <div class="kpi-caption">{{ kpi.caption }}</div>
          </div>
        </section>

        <!-- ═════ 工具栏：视图切换 + 过滤 pill + 搜索 ═════ -->
        <section class="toolbar">
          <div class="view-tabs" role="tablist">
            <button
              type="button"
              class="view-tab"
              :class="{ 'view-tab--active': viewMode === 'cards' }"
              data-testid="view-tab-cards"
              role="tab"
              @click="viewMode = 'cards'"
            >
              卡片
            </button>
            <button
              type="button"
              class="view-tab"
              :class="{ 'view-tab--active': viewMode === 'timetable' }"
              data-testid="view-tab-timetable"
              role="tab"
              @click="viewMode = 'timetable'"
            >
              时间表
            </button>
            <button
              type="button"
              class="view-tab"
              :class="{ 'view-tab--active': viewMode === 'history' }"
              data-testid="view-tab-history"
              role="tab"
              @click="viewMode = 'history'"
            >
              执行历史
            </button>
          </div>

          <div class="filter-pills" role="group" aria-label="任务状态过滤">
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
              class="filter-chip"
              :class="{ 'filter-chip--active': activeFilter === f.key }"
              :data-testid="`filter-${f.key}`"
              @click="activeFilter = f.key"
            >
              <span>{{ f.label }}</span>
              <span class="filter-chip-count">{{ f.count }}</span>
            </button>
          </div>

          <div class="search-box">
            <Search class="search-icon" />
            <input
              v-model="searchQuery"
              type="search"
              placeholder="搜索任务名 / 指令"
              class="search-input"
              data-testid="search-input"
            />
          </div>
        </section>

        <!-- ═════ 主内容区：卡片 / 占位 ═════ -->
        <section class="cards-section">
          <div
            v-if="store.loading && store.tasks.length === 0"
            class="loading-state"
          >
            加载中…
          </div>

          <!-- 空态 -->
          <div
            v-else-if="store.tasks.length === 0"
            class="flex h-full flex-col items-center justify-center gap-md py-2xl text-center"
            data-testid="empty-state"
          >
            <div class="flex size-2xl items-center justify-center rounded-full bg-muted">
              <CalendarClock class="size-xl text-muted-foreground" />
            </div>
            <div class="flex flex-col gap-xs">
              <p class="text-md font-medium">暂无定时任务</p>
              <p class="text-sm text-muted-foreground">
                在对话中对微微说"每周日 21 点提醒我写周报"即可创建。
              </p>
            </div>
          </div>

          <!-- 卡片视图 -->
          <div
            v-else-if="viewMode === 'cards'"
            class="cards-grid"
            data-testid="cards-grid"
          >
            <article
              v-for="task in filteredTasks"
              :key="task.id"
              :data-testid="`task-card-${task.id}`"
              class="task-card"
              :class="{ 'task-card--selected': selectedTaskId === task.id }"
            >
              <!-- 卡片顶部：tag + 状态 badge + 操作按钮（hover 浮现） -->
              <div class="task-card-top">
                <button
                  type="button"
                  class="project-tag"
                  :class="projectTagClass(task.projectId)"
                  :data-testid="`project-tag-${task.id}`"
                  :disabled="!task.projectId"
                  @click.stop="task.projectId && openProject(task.projectId)"
                >
                  {{ projectLabel(task.projectId) }}
                </button>
                <div class="ml-auto flex items-center gap-xs">
                  <span
                    class="status-badge"
                    :class="statusBadgeClass(task.status)"
                    :data-testid="`status-badge-${task.id}`"
                  >
                    <span class="dot" :class="statusDotClass(task.status)" />
                    {{ statusLabel(task.status) }}
                  </span>
                  <!-- 卡片 hover / 选中态显示的行内操作按钮——data-testid 一直存在保证测试可达 -->
                  <div class="task-card-inline-actions">
                    <button
                      type="button"
                      class="icon-btn"
                      :data-testid="`pause-${task.id}`"
                      :title="task.status === 'active' ? '暂停' : '恢复'"
                      @click.stop="togglePause(task.id, task.status)"
                    >
                      <PauseCircle v-if="task.status === 'active'" class="size-4" />
                      <RefreshCw v-else class="size-4" />
                    </button>
                    <button
                      type="button"
                      class="icon-btn"
                      :data-testid="`edit-${task.id}`"
                      title="编辑"
                      @click.stop="openEdit(task)"
                    >
                      <Edit3 class="size-4" />
                    </button>
                    <button
                      type="button"
                      class="icon-btn icon-btn--danger"
                      :data-testid="`delete-${task.id}`"
                      title="删除"
                      @click.stop="deleteTask(task.id, task.name)"
                    >
                      <X class="size-4" />
                    </button>
                    <button
                      type="button"
                      class="icon-btn"
                      :data-testid="`more-${task.id}`"
                      title="更多"
                      @click.stop="selectTask(task.id)"
                    >
                      <MoreHorizontal class="size-4" />
                    </button>
                  </div>
                </div>
              </div>

              <!-- 卡片主体：点击展开详情面板 -->
              <div
                role="button"
                tabindex="0"
                class="task-card-body"
                :data-testid="`task-card-toggle-${task.id}`"
                @click="selectTask(task.id)"
                @keydown.enter.prevent="selectTask(task.id)"
                @keydown.space.prevent="selectTask(task.id)"
              >
                <h3 class="task-title">{{ task.name }}</h3>
                <p class="task-desc">
                  {{ task.instruction || '（未填写指令）' }}
                </p>

                <!-- schedule 条（人话 + cron） -->
                <div class="schedule-bar">
                  <span class="schedule-human">
                    <Clock class="size-xs shrink-0" />
                    {{ humanSchedule(task.schedule) }}
                  </span>
                  <code class="schedule-cron">{{ task.schedule }}</code>
                </div>

                <!-- 下次 / 上次 两列 -->
                <div class="run-times">
                  <div class="run-times-col">
                    <div class="run-times-label">下次运行</div>
                    <template v-if="task.status === 'active' && task.nextExecutionAt">
                      <div
                        class="run-times-primary"
                        :data-testid="`next-execution-${task.id}`"
                      >
                        {{ formatNextExecutionTime(task.nextExecutionAt) }}
                      </div>
                      <div class="run-times-secondary">
                        {{ relativeNext(task.nextExecutionAt) }}
                      </div>
                    </template>
                    <template v-else>
                      <div class="run-times-primary run-times-muted">
                        {{ task.status === 'paused' ? '已暂停' : '未计划' }}
                      </div>
                      <div class="run-times-secondary">—</div>
                    </template>
                  </div>
                  <div class="run-times-col">
                    <div class="run-times-label">上次</div>
                    <div class="run-times-primary run-times-with-dot">
                      <span
                        class="dot"
                        :class="logStatusDotClass(lastRunStatus(task.id))"
                      />
                      {{ lastRunLabel(task.id) }}
                    </div>
                    <div class="run-times-secondary">—</div>
                  </div>
                </div>

                <!-- 底部：工具 pill + 风险 + 花费 -->
                <div class="task-footer">
                  <div class="tool-pills">
                    <span
                      v-for="skill in parseSkillIds(task.skillIds).slice(0, 3)"
                      :key="skill"
                      class="tool-pill"
                    >
                      {{ skill }}
                    </span>
                    <span
                      v-if="parseSkillIds(task.skillIds).length === 0"
                      class="tool-pill tool-pill--muted"
                    >
                      agent
                    </span>
                  </div>
                  <div class="flex items-center gap-sm">
                    <span class="risk-badge" :class="riskLevel(task).cls">
                      {{ riskLevel(task).label }}
                    </span>
                    <span class="cost-text">{{ singleRunCost(task.id) }}</span>
                  </div>
                </div>
              </div>

            </article>
          </div>

          <!-- "时间表"/"执行历史" 视图：占位 -->
          <div
            v-else
            class="placeholder-view"
            :data-testid="`placeholder-${viewMode}`"
          >
            <Sparkles class="size-xl text-muted-foreground" />
            <p class="text-md font-medium">{{ viewMode === 'timetable' ? '时间表视图' : '执行历史视图' }}即将推出</p>
            <p class="text-sm text-muted-foreground">
              先切回「卡片」查看所有任务；这两个视图会在后续版本补齐。
            </p>
          </div>

          <!-- 过滤后为空 -->
          <div
            v-if="store.tasks.length > 0 && filteredTasks.length === 0 && viewMode === 'cards'"
            class="placeholder-view"
            data-testid="filtered-empty"
          >
            <Search class="size-xl text-muted-foreground" />
            <p class="text-md font-medium">没有匹配的任务</p>
            <p class="text-sm text-muted-foreground">
              试试切换过滤条件或清空搜索框
            </p>
          </div>
        </section>
      </div>

      <!-- ═════ 右侧详情面板 ═════ -->
      <aside
        v-if="selectedTask"
        class="detail-panel scrollbar-thin"
        :data-testid="`task-expanded-${selectedTask.id}`"
      >
        <div class="detail-panel-close-row">
          <button
            type="button"
            class="icon-btn"
            data-testid="detail-close"
            title="关闭详情"
            @click="closeDetail"
          >
            <X class="size-4" />
          </button>
        </div>

        <!-- 调度规则 -->
        <section class="detail-section">
          <div class="detail-section-head">
            <span class="detail-section-title">调度规则</span>
            <button
              type="button"
              class="detail-edit-link"
              @click="openEdit(selectedTask)"
            >
              编辑
            </button>
          </div>
          <div class="schedule-readonly">
            <div class="schedule-readonly-human">
              {{ humanSchedule(selectedTask.schedule) }}
            </div>
            <code class="schedule-readonly-cron">{{ selectedTask.schedule }}</code>
          </div>
          <div
            v-if="selectedTask.status === 'active' && selectedTask.nextExecutionAt"
            class="detail-next-line"
          >
            下次 · {{ formatNextExecutionTime(selectedTask.nextExecutionAt) }}
            <span class="text-muted-foreground"> · {{ relativeNext(selectedTask.nextExecutionAt) }}</span>
          </div>
        </section>

        <!-- 执行指令（测试兼容：需要 "执行指令" 文案 + 日志里的 "1234 ms" 和 summary） -->
        <section class="detail-section">
          <div class="detail-section-head">
            <span class="detail-section-title">执行指令</span>
          </div>
          <p class="detail-instruction">
            {{ selectedTask.instruction || '（未填写指令）' }}
          </p>
        </section>

        <!-- 最近运行 -->
        <section class="detail-section">
          <div class="detail-section-head">
            <span class="detail-section-title">最近运行</span>
          </div>
          <p
            v-if="loadingLogs[selectedTask.id]"
            class="text-xs text-muted-foreground"
            :data-testid="`logs-loading-${selectedTask.id}`"
          >
            加载中…
          </p>
          <p
            v-else-if="logErrors[selectedTask.id]"
            class="text-xs text-destructive"
            :data-testid="`logs-error-${selectedTask.id}`"
          >
            {{ logErrors[selectedTask.id] }}
          </p>
          <p
            v-else-if="!logsByTask[selectedTask.id] || logsByTask[selectedTask.id].length === 0"
            class="text-xs text-muted-foreground"
            :data-testid="`logs-empty-${selectedTask.id}`"
          >
            还未执行过
          </p>
          <ul
            v-else
            class="flex flex-col gap-sm"
            :data-testid="`logs-list-${selectedTask.id}`"
          >
            <li
              v-for="log in logsByTask[selectedTask.id]"
              :key="log.id"
              class="detail-log-row"
            >
              <span
                class="mt-xs inline-block size-xs shrink-0 rounded-full"
                :class="logStatusDotClass(log.status)"
              />
              <div class="flex min-w-0 flex-1 flex-col gap-xs">
                <div class="flex flex-wrap items-center gap-sm text-xs text-muted-foreground">
                  <span>{{ formatExecutedAtTime(log.executedAt) }}</span>
                  <span>·</span>
                  <span>{{ logStatusLabel(log.status) }}</span>
                  <span>·</span>
                  <span>{{ log.durationMs }} ms</span>
                  <template v-if="log.tokensUsed > 0">
                    <span>·</span>
                    <span>{{ log.tokensUsed }} tokens</span>
                  </template>
                </div>
                <p
                  v-if="log.summary"
                  class="text-xs text-foreground whitespace-pre-wrap break-words line-clamp-3"
                >
                  {{ log.summary }}
                </p>
              </div>
            </li>
          </ul>
          <div class="detail-accumulated">
            累计运行 {{ (logsByTask[selectedTask.id]?.length ?? 0) }} 次
            <span v-if="(logsByTask[selectedTask.id]?.length ?? 0) > 0" class="text-muted-foreground">
              · {{ Math.round(
                  ((logsByTask[selectedTask.id] ?? []).filter(l => l.status === 'success').length /
                  Math.max(1, (logsByTask[selectedTask.id] ?? []).length)) * 100
              ) }}% 成功
            </span>
          </div>
        </section>

        <!-- 权限与信任 -->
        <section class="detail-section">
          <div class="detail-section-head">
            <span class="detail-section-title">权限与信任</span>
          </div>
          <div class="detail-grid-2">
            <div>
              <div class="detail-subtitle">风险等级</div>
              <div class="detail-value">
                <span class="risk-badge" :class="riskLevel(selectedTask).cls">
                  {{ riskLevel(selectedTask).label }}
                </span>
              </div>
            </div>
            <div>
              <div class="detail-subtitle">单次花费</div>
              <div class="detail-value">{{ singleRunCost(selectedTask.id) }}</div>
            </div>
          </div>
        </section>

        <!-- 调用工具 -->
        <section class="detail-section">
          <div class="detail-section-head">
            <span class="detail-section-title">
              调用工具 · {{ parseSkillIds(selectedTask.skillIds).length }}
            </span>
          </div>
          <div class="flex flex-wrap gap-xs">
            <span
              v-for="skill in parseSkillIds(selectedTask.skillIds)"
              :key="skill"
              class="tool-pill tool-pill--detail"
            >
              {{ skill }}
            </span>
            <span
              v-if="parseSkillIds(selectedTask.skillIds).length === 0"
              class="tool-pill tool-pill--muted"
            >
              未绑定
            </span>
          </div>
        </section>

        <!-- 结果投递（mock） -->
        <section class="detail-section">
          <div class="detail-section-head">
            <span class="detail-section-title">结果投递</span>
          </div>
          <div class="flex flex-col gap-xs">
            <div class="delivery-row">
              <Send class="size-4 shrink-0 text-muted-foreground" />
              <div class="flex flex-col">
                <span class="delivery-name">Obsidian</span>
                <span class="delivery-caption">AI 早报</span>
              </div>
            </div>
            <div class="delivery-row">
              <Send class="size-4 shrink-0 text-muted-foreground" />
              <div class="flex flex-col">
                <span class="delivery-name">Telegram</span>
                <span class="delivery-caption">推送摘要</span>
              </div>
            </div>
          </div>
        </section>
      </aside>
    </div>

    <!-- 右下角帮助浮标（装饰） -->
    <div class="help-floater" aria-hidden="true">
      <Sparkles class="size-4" />
    </div>

    <!-- 编辑弹窗 -->
    <ScheduledTaskEditDialog
      v-model:open="editDialogOpen"
      :task="editingTask"
    />
  </div>
</template>

<style scoped>
/* ══════════ 页面容器 ══════════ */

.scheduled-tasks-view {
  position: relative;
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

/* ══════════ 顶部 header bar ══════════ */

.page-header-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
  flex-shrink: 0;
  padding: 0.875rem 1.5rem;
  border-bottom: 1px solid hsl(from var(--border) h s l / 0.4);
  background: hsl(from var(--background) h s l / 0.6);
  backdrop-filter: blur(8px);
}

.breadcrumb {
  display: flex;
  align-items: center;
  gap: 0.4rem;
  font-size: 13px;
}

.breadcrumb-item {
  color: var(--muted-foreground);
}

.breadcrumb-sep {
  width: 0.9rem;
  height: 0.9rem;
  color: hsl(from var(--muted-foreground) h s l / 0.5);
}

.breadcrumb-current {
  color: var(--foreground);
  font-weight: 600;
  letter-spacing: -0.01em;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.header-btn {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.45rem 0.85rem;
  border-radius: 0.625rem;
  border: 1px solid hsl(from var(--border) h s l / 0.6);
  background: hsl(from var(--card) h s l / 0.8);
  font-size: 13px;
  color: var(--foreground);
  cursor: pointer;
  transition: background 160ms ease, border-color 160ms ease, transform 160ms ease;
}

.header-btn:hover {
  background: hsl(from var(--muted) h s l / 0.5);
  border-color: hsl(from var(--primary) h s l / 0.3);
}

.header-btn:active {
  transform: scale(0.97);
}

.header-btn--primary {
  background: var(--primary);
  border-color: var(--primary);
  color: var(--primary-foreground);
}

.header-btn--primary:hover {
  background: hsl(from var(--primary) h s l / 0.9);
  border-color: hsl(from var(--primary) h s l / 0.8);
}

/* ══════════ 占位 Toast ══════════ */

.placeholder-toast {
  position: absolute;
  top: 4.5rem;
  left: 50%;
  transform: translateX(-50%);
  z-index: 20;
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 0.875rem;
  border-radius: 0.75rem;
  border: 1px solid hsl(from var(--primary) h s l / 0.3);
  background: hsl(from var(--card) h s l / 0.98);
  color: var(--foreground);
  font-size: 13px;
  box-shadow: 0 10px 24px -12px hsl(var(--shadow-color) / 0.25);
}

.toast-enter-active,
.toast-leave-active {
  transition: opacity 200ms ease, transform 200ms ease;
}

.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translate(-50%, -8px);
}

/* ══════════ 内容分栏 ══════════ */

.content-split {
  display: flex;
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.main-column {
  flex: 1;
  min-width: 0;
  overflow-y: auto;
  padding: 1.25rem 1.5rem 2rem;
}

/* ══════════ Hero zone ══════════ */

.hero-zone {
  margin-bottom: 1.25rem;
  padding: 1.25rem 1.5rem;
  border: 1px solid hsl(from var(--border) h s l / 0.4);
  border-radius: 1rem;
  background:
    linear-gradient(180deg, hsl(from var(--primary) h s l / 0.04), transparent 70%),
    hsl(from var(--card) h s l / 0.8);
  box-shadow: 0 1px 0 hsl(from var(--card) h s l / 0.5) inset;
}

.hero-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1.5rem;
  margin-bottom: 1rem;
}

.hero-left {
  display: flex;
  flex-direction: column;
  gap: 0.625rem;
  min-width: 0;
}

.today-tag {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.3rem 0.7rem;
  border-radius: 999px;
  border: 1px solid hsl(from var(--border) h s l / 0.5);
  background: hsl(from var(--background) h s l / 0.7);
  font-size: 11px;
  color: var(--muted-foreground);
  letter-spacing: 0.05em;
  align-self: flex-start;
}

.today-label {
  font-weight: 700;
  color: var(--primary);
  letter-spacing: 0.1em;
}

.today-divider {
  width: 1px;
  height: 10px;
  background: hsl(from var(--border) h s l / 0.7);
}

.hero-headline {
  font-size: 1.5rem;
  font-weight: 600;
  line-height: 1.3;
  letter-spacing: -0.02em;
  color: var(--foreground);
  max-width: 38rem;
}

.hero-headline strong {
  color: var(--primary);
  font-weight: 700;
  font-size: 1.6rem;
}

.hero-right {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
  align-items: flex-end;
}

.hero-badge {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.3rem 0.75rem;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 500;
  background: hsl(from var(--background) h s l / 0.8);
  border: 1px solid hsl(from var(--border) h s l / 0.5);
}

.hero-badge--success {
  color: rgb(4 120 87);
  border-color: rgb(167 243 208 / 0.8);
  background: rgb(236 253 245 / 0.7);
}

.hero-badge--failed {
  color: rgb(159 18 57);
  border-color: rgb(253 164 175 / 0.6);
  background: rgb(255 241 242 / 0.7);
}

.hero-badge--pending {
  color: var(--muted-foreground);
}

:global(.dark) .hero-badge--success {
  color: rgb(110 231 183);
  border-color: rgb(5 150 105 / 0.5);
  background: rgb(6 78 59 / 0.3);
}

:global(.dark) .hero-badge--failed {
  color: rgb(253 164 175);
  border-color: rgb(225 29 72 / 0.5);
  background: rgb(136 19 55 / 0.3);
}

/* 通用状态点 */
.dot {
  display: inline-block;
  width: 0.5rem;
  height: 0.5rem;
  border-radius: 999px;
  flex-shrink: 0;
}

/* ══════════ 24h 时间轴 ══════════ */

.timeline {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.timeline-track {
  position: relative;
  height: 2.25rem;
  border-radius: 0.5rem;
  background: hsl(from var(--muted) h s l / 0.4);
  border: 1px solid hsl(from var(--border) h s l / 0.4);
}

.timeline-point {
  position: absolute;
  top: 50%;
  width: 0.625rem;
  height: 0.625rem;
  border-radius: 999px;
  transform: translate(-50%, -50%);
  box-shadow: 0 0 0 2px hsl(from var(--card) h s l / 0.9);
}

.timeline-cursor {
  position: absolute;
  top: -0.25rem;
  bottom: -0.25rem;
  width: 2px;
  background: var(--primary);
  border-radius: 2px;
  box-shadow: 0 0 0 1px hsl(from var(--card) h s l / 0.9);
  transform: translateX(-50%);
}

.timeline-cursor-label {
  position: absolute;
  top: -1.4rem;
  left: 50%;
  transform: translateX(-50%);
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--primary);
  color: var(--primary-foreground);
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0.08em;
  white-space: nowrap;
}

.timeline-scale {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: var(--muted-foreground);
  letter-spacing: 0.03em;
  font-variant-numeric: tabular-nums;
}

.timeline-scale-tick {
  text-align: center;
}

/* ══════════ KPI row ══════════ */

.kpi-row {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 0.75rem;
  margin-bottom: 1rem;
}

@media (max-width: 1280px) {
  .kpi-row {
    grid-template-columns: repeat(3, 1fr);
  }
}

@media (max-width: 768px) {
  .kpi-row {
    grid-template-columns: repeat(2, 1fr);
  }
}

.kpi-card {
  padding: 0.875rem 1rem;
  border: 1px solid hsl(from var(--border) h s l / 0.45);
  border-radius: 0.75rem;
  background: hsl(from var(--card) h s l / 0.85);
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  transition: border-color 160ms ease, background 160ms ease;
}

.kpi-card:hover {
  border-color: hsl(from var(--primary) h s l / 0.3);
  background: hsl(from var(--card) h s l / 0.95);
}

.kpi-card--alert {
  border-color: rgb(253 164 175 / 0.5);
  background: linear-gradient(180deg, rgb(255 241 242 / 0.4), hsl(from var(--card) h s l / 0.85));
}

:global(.dark) .kpi-card--alert {
  border-color: rgb(225 29 72 / 0.3);
  background: linear-gradient(180deg, rgb(136 19 55 / 0.15), hsl(from var(--card) h s l / 0.85));
}

.kpi-value {
  font-size: 1.6rem;
  font-weight: 700;
  line-height: 1;
  color: var(--foreground);
  letter-spacing: -0.03em;
  font-variant-numeric: tabular-nums;
}

.kpi-card--alert .kpi-value {
  color: rgb(159 18 57);
}

:global(.dark) .kpi-card--alert .kpi-value {
  color: rgb(253 164 175);
}

.kpi-label {
  font-size: 12px;
  color: var(--muted-foreground);
  letter-spacing: 0.02em;
}

.kpi-caption {
  font-size: 11px;
  color: hsl(from var(--muted-foreground) h s l / 0.85);
  line-height: 1.4;
}

/* ══════════ 工具栏 ══════════ */

.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.75rem;
  margin-bottom: 1rem;
}

.view-tabs {
  display: flex;
  gap: 2px;
  padding: 3px;
  border-radius: 0.625rem;
  background: hsl(from var(--muted) h s l / 0.45);
  border: 1px solid hsl(from var(--border) h s l / 0.4);
}

.view-tab {
  padding: 0.35rem 0.85rem;
  border: none;
  border-radius: 0.5rem;
  background: transparent;
  color: var(--muted-foreground);
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: background 160ms ease, color 160ms ease;
}

.view-tab:hover {
  color: var(--foreground);
}

.view-tab--active {
  background: var(--card);
  color: var(--foreground);
  box-shadow: 0 1px 2px hsl(var(--shadow-color) / 0.08);
}

.filter-pills {
  display: flex;
  gap: 0.4rem;
  flex-wrap: wrap;
}

.filter-chip {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.35rem 0.7rem;
  border: 1px solid hsl(from var(--border) h s l / 0.5);
  border-radius: 999px;
  background: hsl(from var(--card) h s l / 0.7);
  font-size: 12px;
  color: var(--muted-foreground);
  cursor: pointer;
  transition: all 160ms ease;
}

.filter-chip:hover {
  border-color: hsl(from var(--primary) h s l / 0.3);
  color: var(--foreground);
}

.filter-chip--active {
  border-color: var(--primary);
  background: hsl(from var(--primary) h s l / 0.1);
  color: var(--primary);
  font-weight: 600;
}

.filter-chip-count {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
  padding: 0 0.25rem;
  min-width: 1.1rem;
  text-align: center;
}

.filter-chip--active .filter-chip-count {
  color: var(--primary);
}

.search-box {
  position: relative;
  margin-left: auto;
  flex: 0 1 18rem;
  min-width: 10rem;
}

.search-icon {
  position: absolute;
  left: 0.625rem;
  top: 50%;
  transform: translateY(-50%);
  width: 0.95rem;
  height: 0.95rem;
  color: var(--muted-foreground);
  pointer-events: none;
}

.search-input {
  width: 100%;
  padding: 0.45rem 0.75rem 0.45rem 2rem;
  border: 1px solid hsl(from var(--border) h s l / 0.5);
  border-radius: 999px;
  background: hsl(from var(--card) h s l / 0.85);
  font-size: 13px;
  color: var(--foreground);
  transition: border-color 160ms ease, background 160ms ease;
}

.search-input:focus {
  outline: none;
  border-color: var(--primary);
  background: var(--card);
}

/* ══════════ 卡片区 ══════════ */

.cards-section {
  min-height: 20rem;
}

.cards-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1rem;
}

@media (max-width: 1400px) {
  .cards-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 860px) {
  .cards-grid {
    grid-template-columns: 1fr;
  }
}

.loading-state {
  padding: 2rem;
  text-align: center;
  font-size: 13px;
  color: var(--muted-foreground);
}

.placeholder-view {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  padding: 3rem 1rem;
  text-align: center;
}

/* ═══ 任务卡片 ═══ */

.task-card {
  position: relative;
  display: flex;
  flex-direction: column;
  border: 1px solid hsl(from var(--border) h s l / 0.5);
  border-radius: 0.875rem;
  background: hsl(from var(--card) h s l / 0.95);
  transition: border-color 180ms ease, box-shadow 180ms ease, transform 180ms ease;
  overflow: hidden;
}

.task-card:hover {
  border-color: hsl(from var(--primary) h s l / 0.4);
  box-shadow: 0 8px 24px -16px hsl(var(--shadow-color) / 0.2);
  transform: translateY(-1px);
}

.task-card--selected {
  border-color: rgb(249 115 22);
  box-shadow:
    0 0 0 1px rgb(249 115 22 / 0.3),
    0 10px 28px -12px hsl(var(--shadow-color) / 0.25);
}

.task-card--selected::before {
  content: "";
  position: absolute;
  top: 0;
  bottom: 0;
  left: 0;
  width: 3px;
  background: rgb(249 115 22);
}

.task-card-top {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.75rem 1rem 0.25rem;
}

.project-tag {
  display: inline-flex;
  align-items: center;
  padding: 0.15rem 0.55rem;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.02em;
  border: 1px solid transparent;
  cursor: pointer;
  transition: all 160ms ease;
  max-width: 10rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.project-tag:disabled {
  cursor: default;
}

.tag-neutral {
  background: hsl(from var(--muted) h s l / 0.6);
  color: var(--muted-foreground);
}

.tag-orange {
  background: rgb(255 237 213 / 0.9);
  color: rgb(154 52 18);
}

.tag-blue {
  background: rgb(219 234 254 / 0.9);
  color: rgb(30 64 175);
}

.tag-teal {
  background: rgb(204 251 241 / 0.9);
  color: rgb(15 118 110);
}

.tag-violet {
  background: rgb(237 233 254 / 0.9);
  color: rgb(109 40 217);
}

.tag-rose {
  background: rgb(255 228 230 / 0.9);
  color: rgb(159 18 57);
}

:global(.dark) .tag-orange {
  background: rgb(154 52 18 / 0.25);
  color: rgb(254 215 170);
}

:global(.dark) .tag-blue {
  background: rgb(30 64 175 / 0.25);
  color: rgb(191 219 254);
}

:global(.dark) .tag-teal {
  background: rgb(15 118 110 / 0.25);
  color: rgb(153 246 228);
}

:global(.dark) .tag-violet {
  background: rgb(109 40 217 / 0.25);
  color: rgb(221 214 254);
}

:global(.dark) .tag-rose {
  background: rgb(159 18 57 / 0.25);
  color: rgb(254 205 211);
}

/* 状态 badge */
.status-badge {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  padding: 0.15rem 0.5rem;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 500;
  border: 1px solid transparent;
}

.badge-success {
  background: rgb(236 253 245 / 0.9);
  color: rgb(4 120 87);
  border-color: rgb(167 243 208 / 0.6);
}

.badge-muted {
  background: hsl(from var(--muted) h s l / 0.5);
  color: var(--muted-foreground);
  border-color: hsl(from var(--border) h s l / 0.4);
}

.badge-info {
  background: rgb(219 234 254 / 0.8);
  color: rgb(30 64 175);
  border-color: rgb(191 219 254 / 0.6);
}

.badge-danger {
  background: rgb(255 241 242 / 0.9);
  color: rgb(159 18 57);
  border-color: rgb(253 164 175 / 0.6);
}

.badge-draft {
  background: hsl(from var(--muted) h s l / 0.4);
  color: hsl(from var(--muted-foreground) h s l / 0.8);
  border-color: hsl(from var(--border) h s l / 0.3);
  font-style: italic;
}

:global(.dark) .badge-success {
  background: rgb(6 78 59 / 0.25);
  color: rgb(110 231 183);
  border-color: rgb(5 150 105 / 0.3);
}

:global(.dark) .badge-info {
  background: rgb(30 64 175 / 0.25);
  color: rgb(147 197 253);
  border-color: rgb(59 130 246 / 0.3);
}

:global(.dark) .badge-danger {
  background: rgb(136 19 55 / 0.25);
  color: rgb(253 164 175);
  border-color: rgb(225 29 72 / 0.3);
}

.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 1.625rem;
  height: 1.625rem;
  border: none;
  border-radius: 0.45rem;
  background: transparent;
  color: var(--muted-foreground);
  cursor: pointer;
  transition: background 160ms ease, color 160ms ease;
}

.icon-btn:hover {
  background: hsl(from var(--muted) h s l / 0.6);
  color: var(--foreground);
}

.icon-btn--danger:hover {
  color: var(--destructive);
  background: hsl(from var(--destructive) h s l / 0.08);
}

/* 行内操作按钮组：默认半透明，hover 或选中态完全显现 */
.task-card-inline-actions {
  display: flex;
  align-items: center;
  gap: 0.1rem;
  opacity: 0.35;
  transition: opacity 180ms ease;
}

.task-card:hover .task-card-inline-actions,
.task-card--selected .task-card-inline-actions,
.task-card-inline-actions:focus-within {
  opacity: 1;
}

/* 卡片主体 */
.task-card-body {
  display: flex;
  flex-direction: column;
  gap: 0.625rem;
  padding: 0.5rem 1rem 1rem;
  cursor: pointer;
}

.task-title {
  font-size: 15px;
  font-weight: 600;
  line-height: 1.3;
  color: var(--foreground);
  letter-spacing: -0.01em;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-desc {
  font-size: 12px;
  line-height: 1.55;
  color: var(--muted-foreground);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/* schedule 灰底条 */
.schedule-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  padding: 0.45rem 0.65rem;
  border-radius: 0.5rem;
  background: hsl(from var(--muted) h s l / 0.45);
  border: 1px solid hsl(from var(--border) h s l / 0.3);
}

.schedule-human {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  font-size: 12px;
  font-weight: 500;
  color: var(--foreground);
}

.schedule-cron {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--muted-foreground);
  background: hsl(from var(--background) h s l / 0.7);
  padding: 0.1rem 0.35rem;
  border-radius: 0.25rem;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 50%;
}

/* 下次 / 上次 两列 */
.run-times {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.5rem;
  padding-top: 0.25rem;
}

.run-times-col {
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
  min-width: 0;
}

.run-times-label {
  font-size: 10.5px;
  color: hsl(from var(--muted-foreground) h s l / 0.85);
  letter-spacing: 0.02em;
}

.run-times-primary {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--foreground);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.run-times-with-dot {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
}

.run-times-secondary {
  font-size: 11px;
  color: var(--muted-foreground);
  font-variant-numeric: tabular-nums;
}

.run-times-muted {
  color: var(--muted-foreground);
  font-weight: 400;
}

/* 卡片底部：工具 pill + 风险 + 花费 */
.task-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  padding-top: 0.3rem;
  border-top: 1px dashed hsl(from var(--border) h s l / 0.4);
  margin-top: 0.2rem;
}

.tool-pills {
  display: flex;
  gap: 0.25rem;
  flex-wrap: wrap;
}

.tool-pill {
  display: inline-flex;
  padding: 0.1rem 0.45rem;
  border-radius: 0.3rem;
  background: hsl(from var(--muted) h s l / 0.6);
  color: var(--muted-foreground);
  font-family: var(--font-mono);
  font-size: 10.5px;
  border: 1px solid hsl(from var(--border) h s l / 0.3);
}

.tool-pill--muted {
  font-style: italic;
}

.tool-pill--detail {
  padding: 0.2rem 0.55rem;
  font-size: 11.5px;
  background: hsl(from var(--primary) h s l / 0.1);
  color: var(--primary);
  border-color: hsl(from var(--primary) h s l / 0.25);
}

.risk-badge {
  display: inline-flex;
  padding: 0.1rem 0.45rem;
  border-radius: 0.3rem;
  font-size: 10.5px;
  font-weight: 500;
}

.risk-low {
  background: rgb(220 252 231 / 0.8);
  color: rgb(22 101 52);
  border: 1px solid rgb(187 247 208 / 0.5);
}

.risk-med {
  background: rgb(254 243 199 / 0.8);
  color: rgb(133 77 14);
  border: 1px solid rgb(253 230 138 / 0.5);
}

.risk-high {
  background: rgb(255 228 230 / 0.9);
  color: rgb(159 18 57);
  border: 1px solid rgb(253 164 175 / 0.5);
}

:global(.dark) .risk-low {
  background: rgb(20 83 45 / 0.3);
  color: rgb(134 239 172);
  border-color: rgb(22 163 74 / 0.3);
}

.cost-text {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--muted-foreground);
  font-variant-numeric: tabular-nums;
}

/* ══════════ 右侧详情面板 ══════════ */

.detail-panel {
  width: 22rem;
  flex-shrink: 0;
  overflow-y: auto;
  padding: 0.75rem 1rem 1.5rem;
  border-left: 1px solid hsl(from var(--border) h s l / 0.4);
  background: hsl(from var(--card) h s l / 0.5);
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

@media (max-width: 1024px) {
  .detail-panel {
    width: 18rem;
  }
}

.detail-panel-close-row {
  display: flex;
  justify-content: flex-end;
  margin-bottom: -0.5rem;
}

.detail-section {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.detail-section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
}

.detail-section-title {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--muted-foreground);
}

.detail-edit-link {
  font-size: 12px;
  color: var(--primary);
  border: none;
  background: transparent;
  cursor: pointer;
  padding: 0;
}

.detail-edit-link:hover {
  text-decoration: underline;
}

.schedule-readonly {
  display: flex;
  flex-direction: column;
  gap: 0.3rem;
  padding: 0.6rem 0.75rem;
  border: 1px solid hsl(from var(--border) h s l / 0.5);
  border-radius: 0.5rem;
  background: hsl(from var(--card) h s l / 0.7);
}

.schedule-readonly-human {
  font-size: 13px;
  font-weight: 500;
  color: var(--foreground);
}

.schedule-readonly-cron {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--muted-foreground);
}

.detail-next-line {
  font-size: 12px;
  color: var(--foreground);
}

.detail-instruction {
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--foreground);
  white-space: pre-wrap;
  word-break: break-word;
  padding: 0.6rem 0.75rem;
  border: 1px solid hsl(from var(--border) h s l / 0.4);
  border-radius: 0.5rem;
  background: hsl(from var(--muted) h s l / 0.3);
}

.detail-log-row {
  display: flex;
  align-items: flex-start;
  gap: 0.5rem;
  padding: 0.45rem 0.6rem;
  border-radius: 0.45rem;
  background: hsl(from var(--muted) h s l / 0.3);
}

.detail-accumulated {
  font-size: 11px;
  color: var(--foreground);
  margin-top: 0.35rem;
}

.detail-grid-2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.75rem;
}

.detail-subtitle {
  font-size: 10.5px;
  color: var(--muted-foreground);
  letter-spacing: 0.02em;
  margin-bottom: 0.25rem;
}

.detail-value {
  font-size: 12.5px;
  color: var(--foreground);
  font-weight: 500;
}

.delivery-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 0.65rem;
  border: 1px solid hsl(from var(--border) h s l / 0.4);
  border-radius: 0.5rem;
  background: hsl(from var(--card) h s l / 0.85);
}

.delivery-name {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--foreground);
}

.delivery-caption {
  font-size: 11px;
  color: var(--muted-foreground);
}

/* ══════════ 帮助浮标 ══════════ */

.help-floater {
  position: absolute;
  right: 1.25rem;
  bottom: 1.25rem;
  width: 2.5rem;
  height: 2.5rem;
  border-radius: 999px;
  background: linear-gradient(135deg, hsl(267 60% 59%), hsl(200 80% 55%));
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 10px 24px -10px hsl(var(--shadow-color) / 0.3);
  pointer-events: none;
  opacity: 0.55;
}
</style>
