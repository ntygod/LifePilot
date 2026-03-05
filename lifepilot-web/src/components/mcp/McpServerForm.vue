<script setup lang="ts">
import { ref, watch } from 'vue'
import { useSkillStore } from '@/stores/skill'
import type { McpServer } from '@/types'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Checkbox } from '@/components/ui/checkbox'
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select'
import {
  Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle,
} from '@/components/ui/dialog'

const props = defineProps<{ server?: McpServer | null; mode: 'create' | 'edit' }>()
const emit = defineEmits<{ close: []; saved: [server: McpServer] }>()
const store = useSkillStore()

const formData = ref({
  name: '', transport: 'STDIO' as 'STDIO' | 'STREAMABLE_HTTP' | 'SSE_LEGACY',
  command: '', args: [] as string[], url: '', env: {} as Record<string, string>,
  timeout: 30, autoConnect: true, reconnect: true, reconnectDelay: 1000,
  maxReconnectAttempts: 5, healthCheckInterval: 60
})

const loading = ref(false)
const errors = ref<Record<string, string>>({})

watch(() => props.server, (server) => {
  if (server?.config) {
    const config = server.config
    formData.value = {
      name: server.name,
      transport: (config.transport === 'stdio' ? 'STDIO' : config.transport === 'sse' ? 'SSE_LEGACY' : config.transport) as any,
      command: config.command || '', args: config.args || [], url: config.url || config.baseUrl || '',
      env: config.env || {},
      timeout: typeof config.timeout === 'number' ? config.timeout : (typeof config.timeoutSeconds === 'number' ? config.timeoutSeconds : 30),
      autoConnect: config.autoConnect ?? true, reconnect: config.reconnect ?? true,
      reconnectDelay: typeof config.reconnectDelay === 'number' ? config.reconnectDelay : 1000,
      maxReconnectAttempts: config.maxReconnectAttempts ?? (typeof config.maxRetries === 'number' ? config.maxRetries : 5),
      healthCheckInterval: typeof config.healthCheckInterval === 'number' ? config.healthCheckInterval : 60
    }
  } else if (props.mode === 'create') {
    formData.value = {
      name: '', transport: 'STDIO', command: '', args: [], url: '', env: {},
      timeout: 30, autoConnect: true, reconnect: true, reconnectDelay: 1000,
      maxReconnectAttempts: 5, healthCheckInterval: 60
    }
  }
}, { immediate: true })

function validate(): boolean {
  errors.value = {}
  if (!formData.value.name.trim()) errors.value.name = '名称不能为空'
  if (formData.value.transport === 'STDIO' && !formData.value.command.trim()) errors.value.command = '命令不能为空'
  if (formData.value.transport !== 'STDIO' && !formData.value.url.trim()) errors.value.url = 'URL 不能为空'
  return Object.keys(errors.value).length === 0
}

async function handleSubmit() {
  if (!validate()) return
  loading.value = true
  try {
    const data: any = {
      name: formData.value.name, transport: formData.value.transport,
      timeout: formData.value.timeout, autoConnect: formData.value.autoConnect,
      reconnect: formData.value.reconnect, reconnectDelay: formData.value.reconnectDelay,
      maxReconnectAttempts: formData.value.maxReconnectAttempts,
      healthCheckInterval: formData.value.healthCheckInterval
    }
    if (formData.value.transport === 'STDIO') {
      data.command = formData.value.command
      if (formData.value.args.length > 0) data.args = formData.value.args
    } else { data.url = formData.value.url }
    if (Object.keys(formData.value.env).length > 0) data.env = formData.value.env

    let server: McpServer
    if (props.mode === 'create') server = await store.createMcpServer(data)
    else server = await store.updateMcpServer(props.server!.name, data)
    emit('saved', server)
    emit('close')
  } catch (e) { /* 错误已在 store 中处理 */ }
  finally { loading.value = false }
}

function addArg() {
  const arg = prompt('请输入参数:')
  if (arg) formData.value.args.push(arg)
}

function removeArg(index: number) { formData.value.args.splice(index, 1) }
</script>
<template>
  <Dialog :open="true" @update:open="(v: boolean) => { if (!v) emit('close') }">
    <DialogContent class="max-w-[672px] max-h-[90vh] overflow-y-auto">
      <DialogHeader>
        <DialogTitle>{{ mode === 'create' ? '新建 MCP Server' : '编辑 MCP Server' }}</DialogTitle>
      </DialogHeader>

      <form @submit.prevent="handleSubmit" class="space-y-4">
        <div class="space-y-2">
          <Label>名称 <span class="text-destructive">*</span></Label>
          <Input v-model="formData.name" :disabled="mode === 'edit'" :class="{ 'border-destructive': errors.name }" />
          <p v-if="errors.name" class="text-xs text-destructive">{{ errors.name }}</p>
        </div>

        <div class="space-y-2">
          <Label>传输类型 <span class="text-destructive">*</span></Label>
          <Select v-model="formData.transport">
            <SelectTrigger><SelectValue /></SelectTrigger>
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
            <Input v-model="formData.command" placeholder="例如: node" :class="{ 'border-destructive': errors.command }" />
            <p v-if="errors.command" class="text-xs text-destructive">{{ errors.command }}</p>
          </div>
          <div class="space-y-2">
            <Label>参数</Label>
            <div class="flex flex-wrap gap-2 mb-2">
              <span v-for="(arg, index) in formData.args" :key="index"
                class="inline-flex items-center gap-1 px-2 py-1 bg-accent text-accent-foreground rounded-md text-sm">
                {{ arg }}
                <button type="button" class="hover:text-destructive" @click="removeArg(index)">×</button>
              </span>
            </div>
            <Button type="button" variant="outline" size="sm" @click="addArg">+ 添加参数</Button>
          </div>
        </template>

        <template v-else>
          <div class="space-y-2">
            <Label>URL <span class="text-destructive">*</span></Label>
            <Input v-model="formData.url" placeholder="例如: http://localhost:3000" :class="{ 'border-destructive': errors.url }" />
            <p v-if="errors.url" class="text-xs text-destructive">{{ errors.url }}</p>
          </div>
        </template>

        <div class="space-y-2">
          <Label>超时（秒）</Label>
          <Input v-model.number="formData.timeout" type="number" :min="1" />
        </div>

        <div class="flex items-center gap-2">
          <Checkbox id="autoConnect" :checked="formData.autoConnect" @update:checked="(v: boolean) => formData.autoConnect = v" />
          <Label for="autoConnect" class="cursor-pointer">自动连接</Label>
        </div>

        <div class="flex items-center gap-2">
          <Checkbox id="reconnect" :checked="formData.reconnect" @update:checked="(v: boolean) => formData.reconnect = v" />
          <Label for="reconnect" class="cursor-pointer">自动重连</Label>
        </div>

        <DialogFooter>
          <Button type="button" variant="outline" @click="emit('close')">取消</Button>
          <Button type="submit" :disabled="loading">{{ loading ? '保存中...' : '保存' }}</Button>
        </DialogFooter>
      </form>
    </DialogContent>
  </Dialog>
</template>