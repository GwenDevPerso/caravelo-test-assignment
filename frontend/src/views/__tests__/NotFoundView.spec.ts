import { describe, expect, it } from 'vitest'
import NotFoundView from '@/views/NotFoundView.vue'
import { mountView } from './mountView'

describe('NotFoundView', () => {
  it('links back to the tenant list', async () => {
    const { wrapper } = await mountView(NotFoundView, '/nowhere')
    expect(wrapper.text()).toContain('This page does not exist.')
    expect(wrapper.get('a').attributes('href')).toBe('/')
  })
})
