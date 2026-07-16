import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import api from '../api'

interface UserInfo {
  userId: number
  username: string
  displayName: string
  roles: string[]
}

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('token') || '')
  const user = ref<UserInfo | null>(null)

  const isLoggedIn = computed(() => !!token.value)
  const isAdmin = computed(() => user.value?.roles?.includes('admin') ?? false)

  async function login(username: string, password: string) {
    const res = await api.post('/auth/login', { username, password }) as any
    const data = res.data
    token.value = data.token
    user.value = {
      userId: data.userId,
      username: data.username,
      displayName: data.displayName,
      roles: data.roles,
    }
    localStorage.setItem('token', data.token)
    return data
  }

  function logout() {
    token.value = ''
    user.value = null
    localStorage.removeItem('token')
  }

  return { token, user, isLoggedIn, isAdmin, login, logout }
})
