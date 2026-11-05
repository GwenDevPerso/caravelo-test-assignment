import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { ApiError, getRoutes } from '@/api/client'
import TenantRoutesView from '@/views/TenantRoutesView.vue'
import { mountView, pending } from './mountView'

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  getRoutes: vi.fn(),
}))

beforeEach(() => {
  vi.mocked(getRoutes).mockReset()
})

describe('TenantRoutesView', () => {
  it('shows a loading message', async () => {
    vi.mocked(getRoutes).mockReturnValue(pending())
    const { wrapper } = await mountView(TenantRoutesView, '/tenants/tenant-a/routes')
    expect(wrapper.text()).toContain('Loading routes')
  })

  it('shows a server error', async () => {
    vi.mocked(getRoutes).mockRejectedValue(new ApiError(404, 'Unknown tenant: nope'))
    const { wrapper } = await mountView(TenantRoutesView, '/tenants/nope/routes')
    expect(wrapper.text()).toContain('Unknown tenant: nope')
  })

  it('shows an empty state', async () => {
    vi.mocked(getRoutes).mockResolvedValue({})
    const { wrapper } = await mountView(TenantRoutesView, '/tenants/tenant-a/routes')
    expect(wrapper.text()).toContain('No route matches these filters.')
  })

  it('renders the grid and passes the query filters to the API', async () => {
    vi.mocked(getRoutes).mockResolvedValue({ ACA: ['GDL', 'MEX', 'TIJ'] })
    const { wrapper } = await mountView(TenantRoutesView, '/tenants/tenant-a/routes?origin=ACA&type=DIRECT')
    expect(getRoutes).toHaveBeenCalledWith('tenant-a', { origin: 'ACA', destination: undefined, type: 'DIRECT' })
    expect(wrapper.get('tbody th').text()).toContain('ACA')
    expect(wrapper.findAll('li').map((item) => item.text())).toEqual(['GDL', 'MEX', 'TIJ'])
    expect((wrapper.get('input').element as HTMLInputElement).value).toBe('ACA')
  })

  it('writes the filters into the query string and reloads', async () => {
    vi.mocked(getRoutes).mockResolvedValue({})
    const { wrapper, router } = await mountView(TenantRoutesView, '/tenants/tenant-a/routes')
    await wrapper.findAll('input')[1]?.setValue(' mex ')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(router.currentRoute.value.query).toEqual({ destination: 'MEX' })
    expect(getRoutes).toHaveBeenLastCalledWith('tenant-a', { origin: undefined, destination: 'MEX', type: undefined })
  })

  it('shows a 400 on a parameter as an error of that field', async () => {
    vi.mocked(getRoutes).mockRejectedValue(new ApiError(400, 'Invalid IATA code: AC1', 'origin'))
    const { wrapper } = await mountView(TenantRoutesView, '/tenants/tenant-a/routes?origin=AC1')
    const origin = wrapper.findAll('input')[0]
    expect(origin?.attributes('aria-invalid')).toBe('true')
    const errorId = origin?.attributes('aria-describedby') ?? ''
    expect(wrapper.get(`[id="${errorId}"]`).text()).toBe('Invalid IATA code: AC1')
  })
})
