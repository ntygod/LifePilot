import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import HumanTakeoverModal from './HumanTakeoverModal.vue'

/** Dialog 基于 Teleport，stub 掉避免 portal 依赖 DOM body */
const dialogStubs = {
  Dialog: { template: '<div><slot /></div>' },
  DialogContent: { template: '<div><slot /></div>' },
  DialogTitle: { template: '<h2><slot /></h2>' },
  DialogDescription: { template: '<p><slot /></p>' },
}

describe('HumanTakeoverModal 浏览器人工接管弹窗', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('点击"已完成，继续"按钮触发 continue 事件', async () => {
    const wrapper = mount(HumanTakeoverModal, {
      props: {
        open: true,
        reason: '需要你扫码登录',
        timeoutSeconds: 300,
      },
      global: { stubs: dialogStubs },
    })

    const continueButton = wrapper.findAll('button').find(b => b.text().includes('已完成'))
    expect(continueButton?.exists()).toBe(true)
    await continueButton!.trigger('click')

    expect(wrapper.emitted('continue')).toBeTruthy()
    expect(wrapper.emitted('continue')!.length).toBe(1)
  })

  it('点击"取消任务"按钮触发 cancel 事件', async () => {
    const wrapper = mount(HumanTakeoverModal, {
      props: {
        open: true,
        reason: '需要你扫码登录',
        timeoutSeconds: 300,
      },
      global: { stubs: dialogStubs },
    })

    const cancelButton = wrapper.findAll('button').find(b => b.text().includes('取消任务'))
    expect(cancelButton?.exists()).toBe(true)
    await cancelButton!.trigger('click')

    expect(wrapper.emitted('cancel')).toBeTruthy()
  })

  it('倒计时到 0 时自动触发 cancel 事件', async () => {
    const wrapper = mount(HumanTakeoverModal, {
      props: {
        open: true,
        reason: '需要你扫码登录',
        timeoutSeconds: 3,
      },
      global: { stubs: dialogStubs },
    })

    // 未到 0 秒不应 emit cancel
    vi.advanceTimersByTime(2000)
    await nextTick()
    expect(wrapper.emitted('cancel')).toBeFalsy()

    // 推进到 3 秒整
    vi.advanceTimersByTime(1000)
    await nextTick()
    expect(wrapper.emitted('cancel')).toBeTruthy()
  })

  it('渲染剩余时间为 mm:ss 格式', () => {
    const wrapper = mount(HumanTakeoverModal, {
      props: {
        open: true,
        reason: '需要你扫码登录',
        timeoutSeconds: 125,
      },
      global: { stubs: dialogStubs },
    })

    expect(wrapper.text()).toContain('剩余 2:05')
  })

  it('渲染传入的 reason 描述文本', () => {
    const wrapper = mount(HumanTakeoverModal, {
      props: {
        open: true,
        reason: '请完成滑块验证',
        timeoutSeconds: 60,
      },
      global: { stubs: dialogStubs },
    })

    expect(wrapper.text()).toContain('请完成滑块验证')
  })

  it('传入 error 时渲染错误提示并提示用户重试', () => {
    const wrapper = mount(HumanTakeoverModal, {
      props: {
        open: true,
        reason: '需要你扫码登录',
        timeoutSeconds: 300,
        error: 'Network timeout',
      },
      global: { stubs: dialogStubs },
    })

    expect(wrapper.text()).toContain('恢复请求失败：Network timeout')
    expect(wrapper.text()).toContain('请重试或取消任务')
  })

  it('未传 error 时不渲染错误提示块', () => {
    const wrapper = mount(HumanTakeoverModal, {
      props: {
        open: true,
        reason: '需要你扫码登录',
        timeoutSeconds: 300,
        error: null,
      },
      global: { stubs: dialogStubs },
    })

    expect(wrapper.text()).not.toContain('恢复请求失败')
  })

  it('修改 timeoutSeconds prop 后倒计时重新开始', async () => {
    const wrapper = mount(HumanTakeoverModal, {
      props: {
        open: true,
        reason: '需要你扫码登录',
        timeoutSeconds: 100,
        error: null,
      },
      global: { stubs: dialogStubs },
    })

    // 推进 50 秒，剩余 50 秒，未到 0 不应 cancel
    vi.advanceTimersByTime(50_000)
    await nextTick()
    expect(wrapper.emitted('cancel')).toBeFalsy()

    // 重置为 200 秒，倒计时应重新开始
    await wrapper.setProps({ timeoutSeconds: 200 })

    // 重置后再推进 150 秒（小于 200），不应触发 cancel
    vi.advanceTimersByTime(150_000)
    await nextTick()
    expect(wrapper.emitted('cancel')).toBeFalsy()

    // 再推进 50 秒（累计 200 秒），应触发 cancel
    vi.advanceTimersByTime(50_000)
    await nextTick()
    expect(wrapper.emitted('cancel')).toBeTruthy()
  })
})
