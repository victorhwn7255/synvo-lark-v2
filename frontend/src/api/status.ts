export interface ServiceStatus {
  service: string
  status: 'ready'
}

const baseUrl = (import.meta.env.VITE_API_BASE_URL ?? '').trim().replace(/\/$/, '')

export async function getServiceStatus(signal?: AbortSignal): Promise<ServiceStatus> {
  const response = await fetch(`${baseUrl}/api/status`, {
    headers: { Accept: 'application/json' },
    signal,
  })

  if (!response.ok) {
    throw new Error(`Backend returned HTTP ${response.status}`)
  }

  const payload: unknown = await response.json()
  if (!isServiceStatus(payload)) {
    throw new Error('Backend returned an invalid status response')
  }

  return payload
}

function isServiceStatus(value: unknown): value is ServiceStatus {
  if (typeof value !== 'object' || value === null) {
    return false
  }

  const candidate = value as Record<string, unknown>
  return candidate.service === 'synvo-backend' && candidate.status === 'ready'
}
