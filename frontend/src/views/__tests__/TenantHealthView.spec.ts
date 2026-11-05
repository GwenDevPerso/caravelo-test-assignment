import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { ApiError, getHealth, triggerSync, type TenantHealth } from '@/api/client'
import TenantHealthView from '@/views/TenantHealthView.vue'
import { mountView, pending } from './mountView'

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  getHealth: vi.fn(),
  triggerSync: vi.fn(),
}))

const path = '/tenants/tenant-a/health'
const health: TenantHealth = {
  tenantId: 'tenant-a',
  enabled: true,
  lastSuccessAt: '2026-11-05T10:00:00Z',
  lastStatus: 'DEGRADED',
  lastSourceUsed: 'generic-backup-provider',
  lastError: null,
  errorCount: 2,
  stale: false,
  routeCount: 412,
}

beforeEach(() => {
  vi.mocked(getHealth).mockReset()
  vi.mocked(triggerSync).mockReset()
})

describe('TenantHealthView', () => {
  it('shows a loading message', async () => {
    vi.mocked(getHealth).mockReturnValue(pending())
    const { wrapper } = await mountView(TenantHealthView, path)
    expect(wrapper.text()).toContain('Loading health')
  })

  it('shows the error', async () => {
    vi.mocked(getHealth).mockRejectedValue(new ApiError(404, 'Unknown tenant'))
    const { wrapper } = await mountView(TenantHealthView, path)
    expect(wrapper.text()).toContain('Unknown tenant')
  })

  it('shows an empty state when no sync has run', async () => {
    vi.mocked(getHealth).mockResolvedValue({ ...health, lastStatus: null, lastSuccessAt: null, lastSourceUsed: null })
    const { wrapper } = await mountView(TenantHealthView, path)
    expect(wrapper.text()).toContain('No sync has run for this tenant yet.')
  })

  it('shows the health figures', async () => {
    vi.mocked(getHealth).mockResolvedValue(health)
    const { wrapper } = await mountView(TenantHealthView, path)
    const text = wrapper.text()
    expect(text).toContain('2026-11-05 10:00 UTC')
    expect(text).toContain('DEGRADED')
    expect(text).toContain('FRESH')
    expect(text).toContain('412')
    expect(text).toContain('generic-backup-provider')
  })

  it('triggers a sync, shows the run and reloads the health', async () => {
    vi.mocked(getHealth).mockResolvedValue(health)
    vi.mocked(triggerSync).mockResolvedValue({
      runId: '1234', tenantId: 'tenant-a', status: 'SUCCESS', sourceUsed: 'tenant-a-stations',
      startedAt: '', finishedAt: '', routeCount: 413, added: 1, removed: 0, modified: 0, issueCount: 0, error: null,
    })
    const { wrapper } = await mountView(TenantHealthView, path)
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(triggerSync).toHaveBeenCalledWith('tenant-a')
    expect(getHealth).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('SUCCESS via tenant-a-stations')
  })

  it('reports a 409 as a sync already running', async () => {
    vi.mocked(getHealth).mockResolvedValue(health)
    vi.mocked(triggerSync).mockRejectedValue(new ApiError(409, 'Conflict'))
    const { wrapper } = await mountView(TenantHealthView, path)
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.get('[aria-live="polite"][role="status"]').text()).toContain('A sync is already running.')
  })
})
