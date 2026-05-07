import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import RelationPanel from './RelationPanel.vue'

const mocks = vi.hoisted(() => ({
  listRelations: vi.fn(),
  memoryStore: {
    activeTab: 'relations',
    requestEntityDetail: vi.fn(),
  },
}))

vi.mock('@/api/client', () => ({
  memoryApi: {
    listRelations: mocks.listRelations,
  },
}))

vi.mock('@/stores/memory', () => ({
  useMemoryStore: () => mocks.memoryStore,
}))

function mountPanel() {
  return shallowMount(RelationPanel, {
    global: {
      renderStubDefaultSlot: true,
      stubs: {
        Badge: { template: '<span><slot /></span>' },
        Button: {
          template: '<button v-bind="$attrs" @click="$emit(\'click\', $event)"><slot /></button>',
        },
        Input: {
          props: ['modelValue'],
          template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
        },
        Skeleton: { template: '<div />' },
        Select: { template: '<div><slot /></div>' },
        SelectContent: { template: '<div><slot /></div>' },
        SelectItem: { template: '<div><slot /></div>' },
        SelectTrigger: { template: '<div><slot /></div>' },
        SelectValue: { template: '<div><slot /></div>' },
        Pagination: { template: '<div />' },
      },
    },
  })
}

beforeEach(() => {
  mocks.listRelations.mockReset()
  mocks.memoryStore.requestEntityDetail.mockReset()
  mocks.listRelations.mockResolvedValue({
    items: [
      {
        id: 'relation-1',
        sourceEntityId: 'entity-1',
        sourceEntityName: '林夜',
        sourceEntityType: 'PERSON',
        sourceEntitySpaceId: 'domain:knowledge-base:novel-workspace',
        sourceEntityMemoryScope: 'DOMAIN_MEMORY',
        sourceEntityRealityType: 'FICTIONAL',
        targetEntityId: 'entity-2',
        targetEntityName: '青牛宗',
        targetEntityType: 'ORGANIZATION',
        targetEntitySpaceId: 'domain:knowledge-base:novel-workspace',
        targetEntityMemoryScope: 'DOMAIN_MEMORY',
        targetEntityRealityType: 'FICTIONAL',
        relationType: 'BELONGS_TO',
        strength: 0.91,
        validFrom: '2026-03-27T00:00:00Z',
        validTo: null,
        createdAt: '2026-03-27T00:00:00Z',
      },
    ],
    page: 0,
    size: 20,
    total: 1,
  })
})

describe('RelationPanel 记忆归属展示', () => {
  it('会展示关系两端实体的记忆归属信息', async () => {
    const wrapper = mountPanel()
    await flushPromises()

    expect(mocks.listRelations).toHaveBeenCalledWith({ page: 0, size: 20 })
    expect(wrapper.text()).toContain('领域记忆')
    expect(wrapper.text()).toContain('虚构')
    expect(wrapper.text()).toContain('KB 图谱冷召回')
    expect(wrapper.text()).toContain('domain:knowledge-base:novel-workspace')
    expect(wrapper.text()).toContain('林夜')
    expect(wrapper.text()).toContain('青牛宗')
  })

  it('点击实体名称会请求打开实体详情', async () => {
    const wrapper = mountPanel()
    await flushPromises()

    const buttons = wrapper.findAll('button')
    const entityButton = buttons.find(button => button.text().includes('林夜'))
    expect(entityButton).toBeTruthy()

    await entityButton!.trigger('click')

    expect(mocks.memoryStore.requestEntityDetail).toHaveBeenCalledWith('entity-1')
  })
})
