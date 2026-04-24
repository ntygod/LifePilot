<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { AlertCircle, CheckCircle2, CloudDownload, FileUp, GitBranch, Loader2, Search } from 'lucide-vue-next'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Badge } from '@/components/ui/badge'
import { useSkillStore } from '@/stores/skill'
import { useUiStore } from '@/stores/ui'
import { marketplaceApi } from '@/api/marketplace'
import type { ExtensionPackage, SkillInstallation } from '@/types'

// Phase B.7：Skill 导入对话框 —— 三 Tab（上传 / Git / 市场）
const props = defineProps<{
  open: boolean
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  installed: [installation: SkillInstallation]
}>()

const store = useSkillStore()
const uiStore = useUiStore()

// 当前激活 Tab
const activeTab = ref<'upload' | 'git' | 'marketplace'>('upload')

// ── 上传压缩包 Tab ──────────────────────────────────────
const selectedFile = ref<File | null>(null)
const isDragOver = ref(false)
const uploadBusy = ref(false)
const fileInputRef = ref<HTMLInputElement | null>(null)

const hasValidExtension = computed(() => {
  if (!selectedFile.value) return false
  const name = selectedFile.value.name.toLowerCase()
  return name.endsWith('.skill') || name.endsWith('.zip')
})

function openFilePicker() {
  fileInputRef.value?.click()
}

function onFileChange(event: Event) {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]
  selectedFile.value = file ?? null
  // 允许重复选中同一文件
  target.value = ''
}

function onDragEnter(event: DragEvent) {
  event.preventDefault()
  isDragOver.value = true
}

function onDragOver(event: DragEvent) {
  event.preventDefault()
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'copy'
  isDragOver.value = true
}

function onDragLeave(event: DragEvent) {
  event.preventDefault()
  isDragOver.value = false
}

function onDrop(event: DragEvent) {
  event.preventDefault()
  isDragOver.value = false
  const file = event.dataTransfer?.files?.[0]
  if (file) selectedFile.value = file
}

async function submitUpload() {
  if (!selectedFile.value) {
    uiStore.showToast('error', '请先选择 .skill 压缩包。')
    return
  }
  if (!hasValidExtension.value) {
    uiStore.showToast('error', '文件类型不支持，需 .skill 或 .zip 后缀。')
    return
  }
  uploadBusy.value = true
  try {
    const install = await store.importPackage(selectedFile.value)
    uiStore.showToast('success', `Skill “${install.name}” 导入成功。`)
    emit('installed', install)
    close()
  } catch (error: any) {
    uiStore.showToast('error', error?.message ?? '导入失败。')
  } finally {
    uploadBusy.value = false
  }
}

// ── 市场 Tab ────────────────────────────────────────────
const marketplaceSearch = ref('')
const marketplaceLoading = ref(false)
const marketplaceError = ref<string | null>(null)
const marketplacePackages = ref<ExtensionPackage[]>([])
const installingId = ref<string | null>(null)

async function loadMarketplace() {
  marketplaceLoading.value = true
  marketplaceError.value = null
  try {
    const result = await marketplaceApi.getSkills({
      type: 'SKILL',
      search: marketplaceSearch.value.trim() || undefined,
      page: 0,
      size: 30,
    })
    marketplacePackages.value = result.content ?? []
  } catch (error: any) {
    marketplaceError.value = error?.message ?? '市场目录加载失败。'
    marketplacePackages.value = []
  } finally {
    marketplaceLoading.value = false
  }
}

async function installFromMarketplace(pkg: ExtensionPackage) {
  installingId.value = pkg.id
  try {
    const install = await store.installFromMarketplace(pkg.id)
    uiStore.showToast('success', `Skill “${install.name}” 安装成功。`)
    emit('installed', install)
    close()
  } catch (error: any) {
    uiStore.showToast('error', error?.message ?? '安装失败。')
  } finally {
    installingId.value = null
  }
}

// ── 生命周期 ────────────────────────────────────────────
function close() {
  emit('update:open', false)
}

function resetState() {
  selectedFile.value = null
  isDragOver.value = false
  marketplaceSearch.value = ''
  marketplaceError.value = null
  marketplacePackages.value = []
  activeTab.value = 'upload'
}

watch(() => props.open, open => {
  if (!open) {
    resetState()
  }
})

watch(activeTab, tab => {
  if (tab === 'marketplace' && marketplacePackages.value.length === 0 && !marketplaceLoading.value) {
    void loadMarketplace()
  }
})
</script>

<template>
  <Dialog :open="props.open" @update:open="value => emit('update:open', value)">
    <DialogContent class="sm:max-w-[40rem]">
      <DialogHeader>
        <DialogTitle>导入技能</DialogTitle>
        <DialogDescription>
          从本地压缩包、Git 仓库或技能市场安装 Skill。导入成功后会自动刷新目录。
        </DialogDescription>
      </DialogHeader>

      <Tabs v-model="activeTab" class="gap-md">
        <TabsList class="grid w-full grid-cols-3">
          <TabsTrigger value="upload">
            <FileUp class="h-sm w-sm" />
            本地压缩包
          </TabsTrigger>
          <TabsTrigger value="git">
            <GitBranch class="h-sm w-sm" />
            Git 仓库
          </TabsTrigger>
          <TabsTrigger value="marketplace">
            <CloudDownload class="h-sm w-sm" />
            技能市场
          </TabsTrigger>
        </TabsList>

        <!-- 本地压缩包 -->
        <TabsContent value="upload" class="space-y-md">
          <div
            class="flex flex-col items-center justify-center gap-sm rounded-xl border-2 border-dashed transition-colors"
            :class="isDragOver
              ? 'border-primary bg-primary/5'
              : 'border-border/60 bg-muted/20 hover:border-border hover:bg-muted/30'"
            style="min-height: 10rem;"
            @dragenter="onDragEnter"
            @dragover="onDragOver"
            @dragleave="onDragLeave"
            @drop="onDrop"
          >
            <FileUp class="h-xl w-xl text-muted-foreground" />
            <p class="text-sm text-muted-foreground">
              拖拽 .skill 压缩包到此，或
              <button
                type="button"
                class="font-medium text-primary hover:underline"
                @click="openFilePicker"
              >
                点击选择文件
              </button>
            </p>
            <p class="text-xs text-muted-foreground">
              仅支持 .skill 或 .zip 格式。包内必须包含 SKILL.md
            </p>
            <input
              ref="fileInputRef"
              type="file"
              class="hidden"
              accept=".skill,.zip"
              @change="onFileChange"
            >
          </div>

          <div
            v-if="selectedFile"
            class="flex items-center justify-between gap-sm rounded-lg border border-border/60 bg-card/60 px-md py-sm"
          >
            <div class="flex min-w-0 items-center gap-sm">
              <CheckCircle2 v-if="hasValidExtension" class="h-sm w-sm text-emerald-500" />
              <AlertCircle v-else class="h-sm w-sm text-amber-500" />
              <div class="min-w-0">
                <p class="truncate text-sm font-medium text-foreground">{{ selectedFile.name }}</p>
                <p class="text-xs text-muted-foreground">
                  {{ (selectedFile.size / 1024).toFixed(1) }} KB
                  <span v-if="!hasValidExtension" class="text-amber-600">
                    · 建议使用 .skill 或 .zip 后缀
                  </span>
                </p>
              </div>
            </div>
            <Button variant="ghost" size="sm" @click="selectedFile = null">
              移除
            </Button>
          </div>
        </TabsContent>

        <!-- Git 仓库（即将上线） -->
        <TabsContent value="git" class="space-y-md">
          <div class="rounded-xl border border-border/60 bg-muted/20 p-md">
            <div class="flex items-start gap-sm">
              <GitBranch class="h-md w-md text-muted-foreground" />
              <div class="space-y-xs">
                <p class="text-sm font-medium text-foreground">Git 仓库导入（即将上线）</p>
                <p class="text-sm text-muted-foreground">
                  后续将支持通过 Git URL 克隆安装 Skill；当前请使用本地压缩包或市场渠道。
                </p>
              </div>
            </div>
          </div>
          <div class="space-y-sm opacity-60">
            <label class="text-sm font-medium text-foreground">仓库 URL</label>
            <Input disabled placeholder="https://github.com/user/repo.git" />
          </div>
        </TabsContent>

        <!-- 技能市场 -->
        <TabsContent value="marketplace" class="space-y-md">
          <div class="flex items-center gap-sm">
            <div class="relative flex-1">
              <Search class="pointer-events-none absolute left-sm top-1/2 h-sm w-sm -translate-y-1/2 text-muted-foreground" />
              <Input
                v-model="marketplaceSearch"
                placeholder="搜索市场里的 Skill..."
                class="pl-xl"
                @keyup.enter="loadMarketplace"
              />
            </div>
            <Button variant="outline" :disabled="marketplaceLoading" @click="loadMarketplace">
              <Loader2 v-if="marketplaceLoading" class="h-sm w-sm animate-spin" />
              <Search v-else class="h-sm w-sm" />
              搜索
            </Button>
          </div>

          <div v-if="marketplaceError" class="rounded-lg border border-destructive/40 bg-destructive/10 p-md text-sm text-destructive">
            {{ marketplaceError }}
          </div>

          <div
            v-else-if="marketplaceLoading"
            class="flex items-center justify-center gap-sm py-xl text-sm text-muted-foreground"
          >
            <Loader2 class="h-sm w-sm animate-spin" />
            正在加载市场目录...
          </div>

          <div
            v-else-if="marketplacePackages.length === 0"
            class="rounded-xl border border-border/60 bg-muted/20 p-xl text-center text-sm text-muted-foreground"
          >
            还没有匹配的市场 Skill。试试更换关键词？
          </div>

          <!-- max-h-80 保留：Tailwind 默认高度尺度，项目未定义 max-h-* 命名别名 -->
          <div v-else class="max-h-80 space-y-sm overflow-y-auto pr-xs">
            <article
              v-for="pkg in marketplacePackages"
              :key="pkg.id"
              class="rounded-lg border border-border/60 bg-card/60 p-md transition-colors hover:border-primary/60"
            >
              <div class="flex items-start justify-between gap-sm">
                <div class="min-w-0 space-y-xs">
                  <div class="flex flex-wrap items-center gap-xs">
                    <h4 class="truncate text-sm font-semibold text-foreground">
                      {{ pkg.name }}
                    </h4>
                    <Badge variant="outline">v{{ pkg.version }}</Badge>
                    <Badge v-if="pkg.verified" variant="secondary">已认证</Badge>
                  </div>
                  <p class="line-clamp-2 text-xs leading-5 text-muted-foreground">
                    {{ pkg.description || '这个 Skill 暂时还没有说明。' }}
                  </p>
                  <p class="text-xs text-muted-foreground">
                    作者 · {{ pkg.author || '未知' }}
                  </p>
                </div>
                <Button
                  size="sm"
                  :disabled="installingId === pkg.id || pkg.installed"
                  @click="installFromMarketplace(pkg)"
                >
                  <Loader2 v-if="installingId === pkg.id" class="h-sm w-sm animate-spin" />
                  {{ pkg.installed ? '已安装' : '安装' }}
                </Button>
              </div>
            </article>
          </div>
        </TabsContent>
      </Tabs>

      <DialogFooter>
        <Button variant="outline" @click="close">
          关闭
        </Button>
        <Button
          v-if="activeTab === 'upload'"
          :disabled="!selectedFile || uploadBusy"
          @click="submitUpload"
        >
          <Loader2 v-if="uploadBusy" class="h-sm w-sm animate-spin" />
          开始导入
        </Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
