<script setup lang="ts">
import { ref, watch } from 'vue'
import { useSkillStore } from '@/stores/skill'
import type { McpServer } from '@/types'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Checkbox } from '@/components/ui/checkbox'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'

const props = defineProps<{ server?: McpServer | null; mode: 'create' | 'edit' }>()
const emit = defineEmits<{ close: []; saved: [server: McpServer] }>()

const store = useSkillStore()

const formData = ref({
  name: '',
  transport: 'STDIO' as 'STDIO' | 'STREAMABLE_HTTP' | 'SSE_LEGACY',
  command: '',
  args: [] as string[],
  url: '',
  env: {} as Record<string, string>,
  timeout: 30,
  autoConnect: true,
  reconnect: true,
  reconnectDelay: 1000,
  maxReconnectAttempts: 5,
  healthCheckInterval: 60,
})

const loading = ref(false)
const errors = ref<Record<string, string>>({})
const pendingArg = ref('')

watch(
  () => props.server,
  (server) => {
    if (server?.config) {
      const config = server.config
      formData.value = {
        name: server.name,
        transport: (config.transport === 'stdio'
          ? 'STDIO'
          : config.transport === 'sse'
            ? 'SSE_LEGACY'
            : config.transport) as 'STDIO' | 'STREAMABLE_HTTP' | 'SSE_LEGACY',
        command: config.command || '',
        args: config.args || [],
        url: config.url || config.baseUrl || '',
        env: config.env || {},
        timeout: typeof config.timeout === 'number'
          ? config.timeout
          : (typeof config.timeoutSeconds === 'number' ? config.timeoutSeconds : 30),
        autoConnect: config.autoConnect ?? true,
        reconnect: config.reconnect ?? true,
        reconnectDelay: typeof config.reconnectDelay === 'number' ? config.reconnectDelay : 1000,
        maxReconnectAttempts: config.maxReconnectAttempts
          ?? (typeof config.maxRetries === 'number' ? config.maxRetries : 5),
        healthCheckInterval: typeof config.healthCheckInterval === 'number' ? config.healthCheckInterval : 60,
      }
    } else if (props.mode === 'create') {
      formData.value = {
        name: '',
        transport: 'STDIO',
        command: '',
        args: [],
        url: '',
        env: {},
        timeout: 30,
        autoConnect: true,
        reconnect: true,
        reconnectDelay: 1000,
        maxReconnectAttempts: 5,
        healthCheckInterval: 60,
      }
    }

    pendingArg.value = ''
  },
  { immediate: true },
)

function validate(): boolean {
  errors.value = {}

  if (!formData.value.name.trim()) {
    errors.value.name = '必须填写服务器名称。'
  }

  if (formData.value.transport === 'STDIO' && !formData.value.command.trim()) {
    errors.value.command = '使用 STDIO 传输方式时必须填写命令。'
  }

  if (formData.value.transport !== 'STDIO' && !formData.value.url.trim()) {
    errors.value.url = '使用 HTTP 或 SSE 传输方式时必须填写地址。'
  }

  return Object.keys(errors.value).length === 0
}

async function handleSubmit() {
  if (!validate()) return

  loading.value = true

  try {
    const data: any = {
      name: formData.value.name,
      transport: formData.value.transport,
      timeout: formData.value.timeout,
      autoConnect: formData.value.autoConnect,
      reconnect: formData.value.reconnect,
      reconnectDelay: formData.value.reconnectDelay,
      maxReconnectAttempts: formData.value.maxReconnectAttempts,
      healthCheckInterval: formData.value.healthCheckInterval,
    }

    if (formData.value.transport === 'STDIO') {
      data.command = formData.value.command
      if (formData.value.args.length > 0) data.args = formData.value.args
    } else {
      data.url = formData.value.url
    }

    if (Object.keys(formData.value.env).length > 0) {
      data.env = formData.value.env
    }

    const server = props.mode === 'create'
      ? await store.createMcpServer(data)
      : await store.updateMcpServer(props.server!.name, data)

    emit('saved', server)
    emit('close')
  } finally {
    loading.value = false
  }
}

function addArg() {
  const arg = pendingArg.value.trim()
  if (!arg) return
  formData.value.args.push(arg)
  pendingArg.value = ''
}

function removeArg(index: number) {
  formData.value.args.splice(index, 1)
}

function normalizeCheckboxValue(value: boolean | 'indeterminate') {
  return value === true
}
</script>

<template>
  <Dialog :open="true" @update:open="(open: boolean) => { if (!open) emit('close') }">
    <DialogContent class="sm:max-w-[672px] max-h-[90vh] overflow-y-auto">
      <DialogHeader>
        <DialogTitle>{{ mode === 'create' ? '新建 MCP 服务器' : '编辑 MCP 服务器' }}</DialogTitle>
        <DialogDescription>
          设置服务器的连接方式、超时和自动重连规则。
        </DialogDescription>
      </DialogHeader>

      <form class="space-y-4" @submit.prevent="handleSubmit">
        <div class="space-y-2">
          <Label>名称 <span class="text-destructive">*</span></Label>
          <Input
            v-model="formData.name"
            :disabled="mode === 'edit'"
            :class="{ 'border-destructive': errors.name }"
            placeholder="filesystem-server"
          />
          <p v-if="errors.name" class="text-xs text-destructive">{{ errors.name }}</p>
        </div>

        <div class="space-y-2">
          <Label>传输方式 <span class="text-destructive">*</span></Label>
          <Select v-model="formData.transport">
            <SelectTrigger>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="STDIO">STDIO</SelectItem>
              <SelectItem value="STREAMABLE_HTTP">STREAMABLE_HTTP</SelectItem>
              <SelectItem value="SSE_LEGACY">SSE_LEGACY</SelectItem>
            </SelectContent>
          </Select>
        </div>

        <template v-if="formData.transport === 'STDIO'">
          <div class="space-y-2">
            <Label>命令 <span class="text-destructive">*</span></Label>
            <Input
              v-model="formData.command"
              placeholder="node"
              :class="{ 'border-destructive': errors.command }"
            />
            <p v-if="errors.command" class="text-xs text-destructive">{{ errors.command }}</p>
          </div>

          <div class="space-y-2">
            <Label>参数</Label>
            <div class="flex flex-wrap gap-2" v-if="formData.args.length > 0">
              <span
                v-for="(arg, index) in formData.args"
                :key="`${arg}-${index}`"
                class="inline-flex items-center gap-1 rounded-md bg-accent px-2 py-1 text-sm text-accent-foreground"
              >
                {{ arg }}
                <button type="button" class="hover:text-destructive" @click="removeArg(index)">x</button>
              </span>
            </div>
            <div class="flex items-center gap-2">
              <Input
                v-model="pendingArg"
                placeholder="添加命令参数"
                @keydown.enter.prevent="addArg"
              />
              <Button type="button" variant="outline" size="sm" @click="addArg">
                添加
              </Button>
            </div>
          </div>
        </template>

        <template v-else>
          <div class="space-y-2">
            <Label>地址 <span class="text-destructive">*</span></Label>
            <Input
              v-model="formData.url"
              placeholder="http://localhost:3000"
              :class="{ 'border-destructive': errors.url }"
            />
            <p v-if="errors.url" class="text-xs text-destructive">{{ errors.url }}</p>
          </div>
        </template>

        <div class="grid gap-4 sm:grid-cols-3">
          <div class="space-y-2">
            <Label>超时时间（秒）</Label>
            <Input v-model.number="formData.timeout" type="number" :min="1" />
          </div>
          <div class="space-y-2">
            <Label>重连延迟（毫秒）</Label>
            <Input v-model.number="formData.reconnectDelay" type="number" :min="0" />
          </div>
          <div class="space-y-2">
            <Label>最大重连次数</Label>
            <Input v-model.number="formData.maxReconnectAttempts" type="number" :min="0" />
          </div>
        </div>

        <div class="flex items-center gap-2">
          <Checkbox
            id="autoConnect"
            :model-value="formData.autoConnect"
            @update:model-value="(value) => formData.autoConnect = normalizeCheckboxValue(value)"
          />
          <Label for="autoConnect" class="cursor-pointer">启动时自动连接</Label>
        </div>

        <div class="flex items-center gap-2">
          <Checkbox
            id="reconnect"
            :model-value="formData.reconnect"
            @update:model-value="(value) => formData.reconnect = normalizeCheckboxValue(value)"
          />
          <Label for="reconnect" class="cursor-pointer">失败后自动重连</Label>
        </div>

        <DialogFooter>
          <Button type="button" variant="outline" @click="emit('close')">
            取消
          </Button>
          <Button type="submit" :disabled="loading">
            {{ loading ? '保存中...' : '保存' }}
          </Button>
        </DialogFooter>
      </form>
    </DialogContent>
  </Dialog>
</template>
