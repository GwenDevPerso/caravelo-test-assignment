// Typed client for the route-sync REST API (SPEC.md §6.2 / §6.3).

export type RouteType = 'DIRECT' | 'CONNECTING'
export type ChangeType = 'ADDED' | 'REMOVED' | 'MODIFIED'
export type RunStatus = 'RUNNING' | 'SUCCESS' | 'DEGRADED' | 'REJECTED' | 'FAILED'

export interface Tenant {
  tenantId: string
  airlineIata: string
  enabled: boolean
  sources: string[]
}

/** Origin IATA code -> sorted destination IATA codes. */
export type RoutesByOrigin = Record<string, string[]>

export interface RouteChange {
  runId: string
  changeType: ChangeType
  origin: string
  destination: string
  oldType: RouteType | null
  newType: RouteType | null
  oldVia: string[] | null
  newVia: string[] | null
  detectedAt: string
}

export interface SyncRun {
  runId: string
  tenantId: string
  status: RunStatus
  sourceUsed: string | null
  startedAt: string
  finishedAt: string | null
  routeCount: number
  added: number
  removed: number
  modified: number
  issueCount: number
  error: string | null
}

export interface TenantHealth {
  tenantId: string
  enabled: boolean
  lastSuccessAt: string | null
  lastStatus: RunStatus | null
  lastSourceUsed: string | null
  lastError: string | null
  errorCount: number
  stale: boolean
  routeCount: number
}

export interface RouteFilters {
  origin?: string
  destination?: string
  type?: RouteType
}

/** Non-2xx answer (status = HTTP code) or network failure (status = 0). */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    /** Query parameter named by a 400 problem+json (`"parameter": "origin"`). */
    readonly parameter?: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

interface ProblemDetail {
  title?: string
  detail?: string
  parameter?: string
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response
  try {
    response = await fetch(`/api${path}`, { ...init, headers: { Accept: 'application/json' } })
  } catch {
    throw new ApiError(0, 'The service cannot be reached.')
  }
  if (!response.ok) {
    const problem: ProblemDetail = await response.json().catch(() => ({}))
    throw new ApiError(
      response.status,
      problem.detail ?? problem.title ?? `Request failed (${response.status})`,
      problem.parameter,
    )
  }
  return response.json() as Promise<T>
}

const tenantPath = (tenantId: string) => `/tenants/${encodeURIComponent(tenantId)}`

export function getTenants(): Promise<Tenant[]> {
  return request('/tenants')
}

export function getRoutes(tenantId: string, filters: RouteFilters = {}): Promise<RoutesByOrigin> {
  const query = new URLSearchParams(
    Object.entries(filters).filter((entry): entry is [string, string] => Boolean(entry[1])),
  ).toString()
  return request(`${tenantPath(tenantId)}/routes${query ? `?${query}` : ''}`)
}

export function getChanges(tenantId: string, limit?: number): Promise<RouteChange[]> {
  return request(`${tenantPath(tenantId)}/changes${limit ? `?limit=${limit}` : ''}`)
}

export function triggerSync(tenantId: string): Promise<SyncRun> {
  return request(`${tenantPath(tenantId)}/sync`, { method: 'POST' })
}

export function getHealth(tenantId: string): Promise<TenantHealth> {
  return request(`${tenantPath(tenantId)}/health`)
}
