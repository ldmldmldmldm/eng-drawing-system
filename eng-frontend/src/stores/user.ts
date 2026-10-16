import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

const TOKEN_KEY = 'eng_token'
const USER_KEY = 'eng_user'
const REMEMBER_KEY = 'eng_remember'

export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem(TOKEN_KEY) || '')
  const username = ref('')
  const displayName = ref('')
  const role = ref('')

  const cached = localStorage.getItem(USER_KEY)
  if (cached) {
    try {
      const u = JSON.parse(cached) as { username?: string; displayName?: string; role?: string }
      username.value = u.username || ''
      displayName.value = u.displayName || ''
      role.value = u.role || ''
    } catch {
      // ignore
    }
  }

  const isLoggedIn = computed(() => !!token.value)

  function setAuth(
    payload: { token: string; username: string; displayName: string; role: string },
    remember?: boolean
  ) {
    token.value = payload.token
    username.value = payload.username
    displayName.value = payload.displayName
    role.value = payload.role
    localStorage.setItem(TOKEN_KEY, payload.token)
    localStorage.setItem(
      USER_KEY,
      JSON.stringify({
        username: payload.username,
        displayName: payload.displayName,
        role: payload.role
      })
    )
    if (remember) {
      localStorage.setItem(REMEMBER_KEY, payload.username)
    }
  }

  function updateDisplayName(name: string) {
    displayName.value = name
    localStorage.setItem(
      USER_KEY,
      JSON.stringify({
        username: username.value,
        displayName: name,
        role: role.value
      })
    )
  }

  function clearAuth() {
    token.value = ''
    username.value = ''
    displayName.value = ''
    role.value = ''
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
  }

  function rememberedUsername() {
    return localStorage.getItem(REMEMBER_KEY) || ''
  }

  return {
    token,
    username,
    displayName,
    role,
    isLoggedIn,
    setAuth,
    updateDisplayName,
    clearAuth,
    rememberedUsername
  }
})
