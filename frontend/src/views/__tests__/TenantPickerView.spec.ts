import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, getTenants } from '@/api/client'
import TenantPickerView from '@/views/TenantPickerView.vue'
import { mountView, pending } from './mountView'

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  getTenants: vi.fn(),
}))

beforeEach(() => {
  vi.mocked(getTenants).mockReset()
})

describe('TenantPickerView', () => {
  it('shows a loading message', async () => {
    vi.mocked(getTenants).mockReturnValue(pending())
    const { wrapper } = await mountView(TenantPickerView, '/')
    expect(wrapper.text()).toContain('Loading tenants')
  })

  it('shows the error', async () => {
    vi.mocked(getTenants).mockRejectedValue(new ApiError(0, 'The service cannot be reached.'))
    const { wrapper } = await mountView(TenantPickerView, '/')
    expect(wrapper.text()).toContain('The service cannot be reached.')
  })

  it('shows an empty state', async () => {
    vi.mocked(getTenants).mockResolvedValue([])
    const { wrapper } = await mountView(TenantPickerView, '/')
    expect(wrapper.text()).toContain('No tenant is configured.')
  })

  it('links each tenant to its routes', async () => {
    vi.mocked(getTenants).mockResolvedValue([
      { tenantId: 'tenant-a', airlineIata: 'Y4', enabled: true, sources: ['tenant-a-stations', 'generic-backup-provider'] },
    ])
    const { wrapper } = await mountView(TenantPickerView, '/')
    expect(wrapper.get('a').attributes('href')).toBe('/tenants/tenant-a/routes')
    expect(wrapper.text()).toContain('Y4')
    expect(wrapper.text()).toContain('tenant-a-stations → generic-backup-provider')
  })
})
