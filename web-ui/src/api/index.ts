import axios from 'axios'

const api = axios.create({
  baseURL: import.meta.env.VITE_API_URL || '/api',
  timeout: 30000,
  withCredentials: true,
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
  headers: {
    'Content-Type': 'application/json',
  },
})

let csrfToken = ''
let csrfRequest: Promise<string> | null = null
const csrfMethods = new Set(['post', 'put', 'patch', 'delete'])

async function getCsrfToken() {
  if (csrfToken) return csrfToken
  if (!csrfRequest) {
    csrfRequest = (api.get('/auth/csrf') as Promise<any>)
      .then(response => {
        const cookieToken = document.cookie.split('; ')
          .find(item => item.startsWith('XSRF-TOKEN='))?.split('=').slice(1).join('=') || ''
        csrfToken = cookieToken ? decodeURIComponent(cookieToken) : response.data?.token || ''
        if (!csrfToken) throw new Error('CSRF token is unavailable')
        return csrfToken
      })
      .finally(() => { csrfRequest = null })
  }
  return csrfRequest
}

export async function getCsrfHeaders() {
  return { 'X-XSRF-TOKEN': await getCsrfToken() }
}

api.interceptors.request.use(async (config) => {
  if (csrfMethods.has((config.method || 'get').toLowerCase())) {
    const token = await getCsrfToken()
    config.headers.set('X-XSRF-TOKEN', token)
  }
  return config
})

// 响应拦截器 — 401 跳登录
api.interceptors.response.use(
  (response) => response.data,
  async (error) => {
    const config = error.config as any
    const method = (config?.method || 'get').toLowerCase()
    if (error.response?.status === 403 && csrfMethods.has(method) && !config?._csrfRetried) {
      config._csrfRetried = true
      csrfToken = ''
      const token = await getCsrfToken()
      config.headers.set('X-XSRF-TOKEN', token)
      return api.request(config)
    }
    if (error.response?.status === 401) {
      window.location.href = '/login'
    }
    return Promise.reject(error)
  }
)

export const runtimeApi = axios.create({
  baseURL: import.meta.env.VITE_RUNTIME_URL || '/runtime-api',
  timeout: 30000,
  withCredentials: true,
})

export default api
