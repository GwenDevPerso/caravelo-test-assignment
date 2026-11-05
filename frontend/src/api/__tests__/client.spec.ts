import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, getRoutes, triggerSync } from '@/api/client'

function respond(status: number, body: unknown) {
  return vi.fn().mockResolvedValue(new Response(JSON.stringify(body), { status }))
}

async function caught(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise
  } catch (error) {
    return error as ApiError
  }
  throw new Error('expected a rejection')
}

afterEach(() => vi.unstubAllGlobals())

describe('api client', () => {
  it('returns the parsed JSON and builds the query string on success', async () => {
    const fetchMock = respond(200, { ACA: ['GDL', 'MEX'] })
    vi.stubGlobal('fetch', fetchMock)

    const routes = await getRoutes('tenant-a', { origin: 'ACA', type: 'DIRECT', destination: '' })

    expect(routes).toEqual({ ACA: ['GDL', 'MEX'] })
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/tenants/tenant-a/routes?origin=ACA&type=DIRECT')
  })

  it('throws an ApiError with the problem detail and parameter on 400', async () => {
    vi.stubGlobal('fetch', respond(400, { title: 'Bad Request', detail: 'Invalid IATA code: AC1', parameter: 'origin' }))

    const error = await caught(getRoutes('tenant-a', { origin: 'AC1' }))

    expect(error).toBeInstanceOf(ApiError)
    expect(error.status).toBe(400)
    expect(error.message).toBe('Invalid IATA code: AC1')
    expect(error.parameter).toBe('origin')
  })

  it('throws an ApiError with status 409 when a sync is already running', async () => {
    const fetchMock = respond(409, { title: 'Conflict' })
    vi.stubGlobal('fetch', fetchMock)

    const error = await caught(triggerSync('tenant-a'))

    expect(error.status).toBe(409)
    expect(error.message).toBe('Conflict')
    expect(fetchMock.mock.calls[0]?.[1]).toMatchObject({ method: 'POST' })
  })

  it('throws an ApiError with status 0 on a network error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))

    const error = await caught(triggerSync('tenant-a'))

    expect(error).toBeInstanceOf(ApiError)
    expect(error.status).toBe(0)
  })
})
