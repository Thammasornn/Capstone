import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ConsentStep from '@/features/face-registration/components/ConsentStep.vue'

describe('ขั้นยินยอม (ลงทะเบียนใบหน้า)', () => {
  it('มี checkbox ยินยอมข้อเดียว ไม่ถามอายุ 20 ปี', () => {
    const wrapper = mount(ConsentStep)
    expect(wrapper.findAll('input[type="checkbox"]')).toHaveLength(1)
    expect(wrapper.text()).not.toContain('20 ปี')
    expect(wrapper.text()).not.toContain('ผู้ปกครอง')
  })

  it('กดยินยอมได้หลังติ๊กข้อยินยอมเท่านั้น', async () => {
    const wrapper = mount(ConsentStep)
    const accept = wrapper.findAll('button').find((b) => b.text().includes('ยินยอมและ'))
    expect(accept.attributes('disabled')).toBeDefined()

    await wrapper.find('input[type="checkbox"]').setValue(true)
    expect(accept.attributes('disabled')).toBeUndefined()

    await accept.trigger('click')
    expect(wrapper.emitted('accept')).toHaveLength(1)
  })
})
