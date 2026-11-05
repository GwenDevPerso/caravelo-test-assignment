import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, getChanges, type RouteChange } from '@/api/client'
import TenantChangesView from '@/views/TenantChangesView.vue'
import { mountView, pending } from './mountView'

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  getChanges: vi.fn(),
}))

const path = '/tenants/tenant-a/changes'

function change(runId: string, changeType: RouteChange['changeType'], destination: string): RouteChange {
  return {
    runId,
    changeType,
    origin: 'ACA',
    destination,
    oldType: changeType === 'ADDED' ? null : 'CONNECTING',
    newType: changeType === 'REMOVED' ? null : 'DIRECT',
    oldVia: changeType === 'ADDED' ? null : ['MEX'],
    newVia: changeType === 'REMOVED' ? null : [],
    detectedAt: '2026-11-05T10:00:00Z',
  }
}

beforeEach(() => {
  vi.mocked(getChanges).mockReset()
})

describe('TenantChangesView', () => {
  it('shows a loading message', async () => {
    vi.mocked(getChanges).mockReturnValue(pending())
    const { wrapper } = await mountView(TenantChangesView, path)
    expect(wrapper.text()).toContain('Loading changes')
  })

  it('shows the error', async () => {
    vi.mocked(getChanges).mockRejectedValue(new ApiError(404, 'Unknown tenant'))
    const { wrapper } = await mountView(TenantChangesView, path)
    expect(wrapper.text()).toContain('Unknown tenant')
  })

  it('shows an empty state', async () => {
    vi.mocked(getChanges).mockResolvedValue([])
    const { wrapper } = await mountView(TenantChangesView, path)
    expect(wrapper.text()).toContain('No change recorded yet.')
  })

  it('groups changes by sync run, newest first', async () => {
    vi.mocked(getChanges).mockResolvedValue([
      change('bbbbbbbb-2', 'MODIFIED', 'CUN'),
      change('bbbbbbbb-2', 'REMOVED', 'TIJ'),
      change('aaaaaaaa-1', 'ADDED', 'GDL'),
    ])
    const { wrapper } = await mountView(TenantChangesView, path)
    const tables = wrapper.findAll('table')
    expect(tables).toHaveLength(2)
    expect(wrapper.findAll('h2').map((title) => title.text())).toEqual([
      'run bbbbbbbb · 2026-11-05 10:00 UTC',
      'run aaaaaaaa · 2026-11-05 10:00 UTC',
    ])
    expect(tables[0]?.findAll('tbody tr')).toHaveLength(2)
    expect(tables[0]?.text()).toContain('CONNECTING via MEX → DIRECT')
    expect(tables[1]?.text()).toContain('ADDED')
  })
})
