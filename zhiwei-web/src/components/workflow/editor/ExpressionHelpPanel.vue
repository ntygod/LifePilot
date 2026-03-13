<!--
  表达式函数帮助面板组件。
  展示所有内置函数的分类（字符串、日期、集合、数学），支持搜索过滤。
-->
<script setup lang="ts">
import { ref, computed } from 'vue'
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Badge } from '@/components/ui/badge'
import { Search, FunctionSquare } from 'lucide-vue-next'

const props = defineProps<{
  open: boolean
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
}>()

const searchQuery = ref('')

const functionGroups = [
  {
    name: '字符串函数',
    functions: [
      { name: 'len(str)', desc: '返回字符串长度', example: '${len(inputs.name)}' },
      { name: 'upper(str)', desc: '转换为大写', example: '${upper(inputs.text)}' },
      { name: 'lower(str)', desc: '转换为小写', example: '${lower(inputs.text)}' },
      { name: 'trim(str)', desc: '去除首尾空白', example: '${trim(inputs.text)}' },
      { name: 'substring(str, start, end)', desc: '截取子串', example: '${substring(inputs.text, 0, 10)}' },
      { name: 'replace(str, old, new)', desc: '替换字符', example: '${replace(inputs.text, "a", "b")}' },
      { name: 'contains(str, sub)', desc: '是否包含子串', example: '${contains(inputs.text, "keyword")}' },
      { name: 'split(str, delimiter)', desc: '分割为数组', example: '${split(inputs.text, ",")}' },
      { name: 'join(list, delimiter)', desc: '数组合并为字符串', example: '${join(items, ",")}' }
    ]
  },
  {
    name: '日期函数',
    functions: [
      { name: 'now()', desc: '返回当前时间', example: '${now()}' },
      { name: 'formatDate(instant, pattern)', desc: '格式化日期', example: '${formatDate(now(), "yyyy-MM-dd")}' },
      { name: 'parseDate(str, pattern)', desc: '解析日期字符串', example: '${parseDate("2024-01-01", "yyyy-MM-dd")}' },
      { name: 'addDays(instant, days)', desc: '日期加减天数', example: '${addDays(now(), 7)}' },
      { name: 'addHours(instant, hours)', desc: '日期加减小时', example: '${addHours(now(), 2)}' },
      { name: 'daysBetween(instant1, instant2)', desc: '计算日期差', example: '${daysBetween(start, end)}' }
    ]
  },
  {
    name: '集合函数',
    functions: [
      { name: 'size(collection)', desc: '返回集合大小', example: '${size(items)}' },
      { name: 'first(list)', desc: '返回首个元素', example: '${first(items)}' },
      { name: 'last(list)', desc: '返回末尾元素', example: '${last(items)}' },
      { name: 'flatten(listOfLists)', desc: '扁平化嵌套数组', example: '${flatten(nestedLists)}' },
      { name: 'distinct(list)', desc: '去除重复元素', example: '${distinct(items)}' }
    ]
  },
  {
    name: '数学函数',
    functions: [
      { name: 'min(a, b)', desc: '返回最小值', example: '${min(a, b)}' },
      { name: 'max(a, b)', desc: '返回最大值', example: '${max(a, b)}' },
      { name: 'abs(n)', desc: '返回绝对值', example: '${abs(-5)}' },
      { name: 'round(n)', desc: '四舍五入', example: '${round(3.14)}' },
      { name: 'ceil(n)', desc: '向上取整', example: '${ceil(3.14)}' },
      { name: 'floor(n)', desc: '向下取整', example: '${floor(3.14)}' }
    ]
  }
]

const filteredGroups = computed(() => {
  if (!searchQuery.value) return functionGroups
  const query = searchQuery.value.toLowerCase()
  return functionGroups
    .map(group => ({
      ...group,
      functions: group.functions.filter(f =>
        f.name.toLowerCase().includes(query) ||
        f.desc.toLowerCase().includes(query)
      )
    }))
    .filter(group => group.functions.length > 0)
})

function close() {
  emit('update:open', false)
}

function copyToClipboard(text: string) {
  navigator.clipboard.writeText(text)
}
</script>

<template>
  <Dialog :open="open" @update:open="close">
    <DialogContent class="max-w-3xl max-h-[80vh] overflow-auto">
      <DialogHeader>
        <DialogTitle>表达式函数参考</DialogTitle>
        <DialogDescription>
          在步骤参数中使用内置函数进行字符串处理、日期计算、集合操作和数学运算。
        </DialogDescription>
      </DialogHeader>

      <div class="space-y-4">
        <div class="relative">
          <Search class="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            v-model="searchQuery"
            placeholder="搜索函数..."
            class="pl-9"
          />
        </div>

        <div v-for="group in filteredGroups" :key="group.name" class="space-y-2">
          <h3 class="text-sm font-medium">{{ group.name }}</h3>
          <div class="grid gap-2 md:grid-cols-2">
            <div
              v-for="fn in group.functions"
              :key="fn.name"
              class="cursor-pointer rounded-lg border p-3 transition-colors hover:bg-muted/50"
              @click="copyToClipboard(fn.example)"
            >
              <div class="flex items-center justify-between">
                <code class="font-mono text-sm">{{ fn.name }}</code>
                <Badge variant="outline" class="text-xs">点击复制</Badge>
              </div>
              <p class="mt-1 text-xs text-muted-foreground">{{ fn.desc }}</p>
              <code class="mt-1 block font-mono text-xs text-muted-foreground">{{ fn.example }}</code>
            </div>
          </div>
        </div>
      </div>
    </DialogContent>
  </Dialog>
</template>
