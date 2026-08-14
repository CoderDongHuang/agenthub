import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import api from '../api'

interface UserInfo {
  userId: number
  username: string
  displayName: string
  roles: string[]
  email?: string
}

export const useAuthStore = defineStore('auth', () => {
  const authenticated = ref(false)
  const user = ref<UserInfo | null>(null)

  const isLoggedIn = computed(() => authenticated.value)
  const isAdmin = computed(() => user.value?.roles?.includes('admin') ?? false)

  async function login(username: string, password: string) {
    const res = await api.post('/auth/login', { username, password }) as any
    const data = res.data
    authenticated.value = true
    user.value = {
      userId: data.userId,
      username: data.username,
      displayName: data.displayName,
      roles: data.roles,
    }
    return data
  }

  async function restoreSession() {
    if (user.value) return user.value
    try {
      const response = await api.get('/auth/me') as any
      user.value = response.data
      authenticated.value = true
      return user.value
    } catch {
      logout()
      return null
    }
  }

  async function logout() {
    try { await api.post('/auth/logout') } catch { /* Clear local state even if the session expired. */ }
    authenticated.value = false
    user.value = null
  }

  return { user, isLoggedIn, isAdmin, login, restoreSession, logout }
})
