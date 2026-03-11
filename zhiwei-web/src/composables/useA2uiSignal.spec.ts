import { describe, expect, it } from 'vitest'
import { extractA2uiComponents } from '@/composables/useA2uiSignal'
import type { A2uiComponent } from '@/types'

function createComponent(id: string, type = 'Text'): A2uiComponent {
  return {
    id,
    type,
    properties: {},
    children: [],
  }
}

describe('extractA2uiComponents', () => {
  it('supports direct components payload', () => {
    const components = [createComponent('root')]
    expect(extractA2uiComponents({ components })).toEqual(components)
  })

  it('supports nested a2ui payload shapes', () => {
    const nestedComponents = [createComponent('nested', 'Card')]

    expect(extractA2uiComponents({ a2ui: { components: nestedComponents } })).toEqual(nestedComponents)
    expect(extractA2uiComponents({ ui: { components: nestedComponents } })).toEqual(nestedComponents)
    expect(extractA2uiComponents({ message: { a2uiComponents: nestedComponents } })).toEqual(nestedComponents)
    expect(extractA2uiComponents({ message: { a2ui: { components: nestedComponents } } })).toEqual(nestedComponents)
  })

  it('ignores invalid shapes safely', () => {
    expect(extractA2uiComponents(null)).toBeNull()
    expect(extractA2uiComponents({ components: [{ id: 'broken' }] })).toBeNull()
    expect(extractA2uiComponents({ a2ui: { components: 'not-an-array' } })).toBeNull()
  })
})
