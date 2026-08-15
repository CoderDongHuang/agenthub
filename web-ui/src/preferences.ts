import { reactive, readonly, watch } from 'vue'

export type ThemeMode = 'dark' | 'light' | 'custom'
export type DensityMode = 'comfortable' | 'compact'
export type StreamingSpeed = 'instant' | 'fast' | 'standard' | 'realistic'
export type CodeTheme = 'vscode-dark' | 'github-light' | 'monokai'
export type SearchShortcut = 'ctrl-k' | 'slash' | 'both'
export type SendShortcut = 'enter' | 'ctrl-enter'

export interface AppPreferences {
  theme: ThemeMode
  customBackground: string
  overlayOpacity: number
  backgroundBlur: number
  density: DensityMode
  reduceMotion: boolean
  language: 'zh-CN' | 'en-US'
  streamingSpeed: StreamingSpeed
  codeTheme: CodeTheme
  collapseLargeJson: boolean
  jsonLineThreshold: number
  searchShortcut: SearchShortcut
  sendShortcut: SendShortcut
  approvalSound: boolean
  desktopNotifications: boolean
  approvalBadge: boolean
}

const STORAGE_KEY = 'agenthub-preferences-v1'
export const defaultPreferences: AppPreferences = {
  theme: 'dark',
  customBackground: '',
  overlayOpacity: 62,
  backgroundBlur: 6,
  density: 'comfortable',
  reduceMotion: false,
  language: 'zh-CN',
  streamingSpeed: 'standard',
  codeTheme: 'vscode-dark',
  collapseLargeJson: true,
  jsonLineThreshold: 50,
  searchShortcut: 'both',
  sendShortcut: 'enter',
  approvalSound: false,
  desktopNotifications: false,
  approvalBadge: true,
}

function loadPreferences(): AppPreferences {
  try {
    const stored = JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}')
    const language = localStorage.getItem('lang') === 'en-US' ? 'en-US' : 'zh-CN'
    return { ...defaultPreferences, ...stored, language }
  } catch {
    return { ...defaultPreferences }
  }
}

const state = reactive<AppPreferences>(loadPreferences())

function escapeCssUrl(value: string) {
  return value.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n/g, '')
}

function applyPreferences() {
  const root = document.documentElement
  root.dataset.theme = state.theme
  root.dataset.density = state.density
  root.dataset.reduceMotion = String(state.reduceMotion)
  root.dataset.codeTheme = state.codeTheme
  root.style.setProperty('--preference-overlay', String(state.overlayOpacity / 100))
  root.style.setProperty('--preference-blur', `${state.backgroundBlur}px`)
  root.style.setProperty('--preference-background', state.customBackground ? `url("${escapeCssUrl(state.customBackground)}")` : 'none')
}

watch(state, value => {
  applyPreferences()
  try { localStorage.setItem(STORAGE_KEY, JSON.stringify(value)) } catch { /* Image data may exceed the browser quota. */ }
}, { deep: true })

export function usePreferences() {
  return state
}

export function resetPreferences() {
  Object.assign(state, { ...defaultPreferences })
}

export function initializePreferences() {
  applyPreferences()
  return readonly(state)
}
