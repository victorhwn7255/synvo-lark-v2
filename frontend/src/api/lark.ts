export type BotConnection =
  | 'disabled'
  | 'connecting'
  | 'connected'
  | 'reconnecting'
  | 'failed'

export type UserAuthorization = 'disabled' | 'unauthorized' | 'connected'

export interface LarkConnection {
  larkEnabled: boolean
  botConnection: BotConnection
  userAuthorization: UserAuthorization
  user: { displayName: string; avatarUrl: string | null } | null
}

export interface AuthorizationBootstrap {
  larkEnabled: boolean
  appId: string | null
  state: string | null
  csrfToken: string
}

export interface LarkApi {
  getConnection(signal?: AbortSignal): Promise<LarkConnection>
  bootstrap(signal?: AbortSignal): Promise<AuthorizationBootstrap>
  exchange(code: string, state: string, csrfToken: string): Promise<LarkConnection>
  signOut(csrfToken: string): Promise<LarkConnection>
}

export const larkApi: LarkApi = {
  getConnection: (signal) => request('/api/lark/connection', { signal }, isLarkConnection),
  bootstrap: (signal) =>
    request('/api/lark/auth/bootstrap', { signal }, isAuthorizationBootstrap),
  exchange: (code, state, csrfToken) =>
    request(
      '/api/lark/auth/exchange',
      {
        method: 'POST',
        headers: mutationHeaders(csrfToken),
        body: JSON.stringify({ code, state }),
      },
      isLarkConnection,
    ),
  signOut: (csrfToken) =>
    request(
      '/api/lark/auth/sign-out',
      { method: 'POST', headers: mutationHeaders(csrfToken) },
      isLarkConnection,
    ),
}

async function request<T>(
  path: string,
  init: RequestInit,
  validate: (value: unknown) => value is T,
): Promise<T> {
  const response = await fetch(path, {
    ...init,
    credentials: 'same-origin',
    headers: { Accept: 'application/json', ...init.headers },
  })

  const payload: unknown = await response.json().catch(() => null)
  if (!response.ok) {
    throw new Error(readSafeError(payload) ?? `Backend returned HTTP ${response.status}`)
  }
  if (!validate(payload)) {
    throw new Error('Backend returned an invalid Lark connection response')
  }
  return payload
}

function mutationHeaders(csrfToken: string): HeadersInit {
  return {
    'Content-Type': 'application/json',
    'X-SYNVO-CSRF': csrfToken,
  }
}

function isLarkConnection(value: unknown): value is LarkConnection {
  if (!isRecord(value)) return false
  const botStates: BotConnection[] = [
    'disabled',
    'connecting',
    'connected',
    'reconnecting',
    'failed',
  ]
  const userStates: UserAuthorization[] = ['disabled', 'unauthorized', 'connected']
  const user = value.user
  return (
    typeof value.larkEnabled === 'boolean' &&
    botStates.includes(value.botConnection as BotConnection) &&
    userStates.includes(value.userAuthorization as UserAuthorization) &&
    (user === null || (
      isRecord(user) &&
      typeof user.displayName === 'string' &&
      (typeof user.avatarUrl === 'string' || user.avatarUrl === null)
    ))
  )
}

function isAuthorizationBootstrap(value: unknown): value is AuthorizationBootstrap {
  return (
    isRecord(value) &&
    typeof value.larkEnabled === 'boolean' &&
    (typeof value.appId === 'string' || value.appId === null) &&
    (typeof value.state === 'string' || value.state === null) &&
    typeof value.csrfToken === 'string'
  )
}

function readSafeError(value: unknown): string | null {
  return isRecord(value) && typeof value.message === 'string' ? value.message : null
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}
