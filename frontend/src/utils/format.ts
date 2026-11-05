import type { RouteType } from '@/api/client'

/** `2026-11-05T10:00:00Z` -> `2026-11-05 10:00 UTC`; `null` -> fallback. */
export function formatTimestamp(value: string | null, fallback = '—'): string {
  if (!value) return fallback
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : `${date.toISOString().slice(0, 16).replace('T', ' ')} UTC`
}

/** `CONNECTING` + `['MEX']` -> `CONNECTING via MEX`. */
export function formatRouteType(type: RouteType | null, via: string[] | null): string {
  if (!type) return '—'
  return via?.length ? `${type} via ${via.join(', ')}` : type
}
