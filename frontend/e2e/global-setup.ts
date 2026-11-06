const backendUrl = 'http://localhost:8080/api/tenants'

export default async function globalSetup() {
  try {
    const response = await fetch(backendUrl, { signal: AbortSignal.timeout(5000) })
    if (response.ok) return
    throw new Error(`HTTP ${response.status}`)
  } catch (cause) {
    throw new Error(
      `Backend not reachable at ${backendUrl} (${(cause as Error).message}): start the backend first: docker compose up --build`,
    )
  }
}
