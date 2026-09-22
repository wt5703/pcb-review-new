import { identity } from '@/stores/identity'

export class ApiError extends Error {
  constructor(message: string, readonly status: number, readonly traceId?: string) {
    super(message)
  }
}

interface Envelope<T> {
  data: T
  traceId?: string
}

function baseUrl(): string {
  return import.meta.env.VITE_API_BASE_URL ?? '/leapmotor/pcb_review'
}

export async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers)
  headers.set('Accept', 'application/json')
  headers.set('X-Mock-User-Id', String(identity.userId))
  headers.set('X-Mock-Roles', identity.roles)
  if (options.body && !(options.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json')
  }
  let response: Response
  try {
    response = await fetch(`${baseUrl()}${path}`, { ...options, headers })
  } catch {
    throw new ApiError('无法连接后端服务，请确认 Spring Boot 已启动。', 0)
  }
  const payload = await response.json().catch(() => undefined) as Envelope<T> | { message?: string; traceId?: string } | undefined
  if (!response.ok) {
    const failure = payload as { message?: string; traceId?: string } | undefined
    throw new ApiError(failure?.message ?? `请求失败（HTTP ${response.status}）`, response.status, failure?.traceId)
  }
  return (payload as Envelope<T>).data
}

/** 以与 JSON 接口一致的当前用户身份下载二进制内容。 */
export async function requestBinary(path: string, options: RequestInit = {}): Promise<Blob> {
  const headers = new Headers(options.headers)
  headers.set('Accept', '*/*')
  headers.set('X-Mock-User-Id', String(identity.userId))
  headers.set('X-Mock-Roles', identity.roles)
  let response: Response
  try {
    response = await fetch(`${baseUrl()}${path}`, { ...options, headers })
  } catch {
    throw new ApiError('无法连接后端服务，请确认 Spring Boot 已启动。', 0)
  }
  if (!response.ok) {
    const payload = await response.json().catch(() => undefined) as { message?: string; traceId?: string } | undefined
    throw new ApiError(payload?.message ?? `下载失败（HTTP ${response.status}）`, response.status, payload?.traceId)
  }
  return response.blob()
}

export function queryString(values: Record<string, string | number | undefined | null>): string {
  const search = new URLSearchParams()
  Object.entries(values).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') search.set(key, String(value))
  })
  const result = search.toString()
  return result ? `?${result}` : ''
}
