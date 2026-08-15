import { createI18n } from 'vue-i18n'
import zhCN from './locales/zh-CN'
import enUS from './locales/en-US'

export type AppLocale = 'zh-CN' | 'en-US'

const storedLang = localStorage.getItem('lang')
const savedLang: AppLocale = storedLang === 'en-US' ? 'en-US' : 'zh-CN'

export const i18n = createI18n({
  legacy: false,
  locale: savedLang,
  fallbackLocale: 'zh-CN',
  messages: {
    'zh-CN': zhCN,
    'en-US': enUS,
  },
})

export function switchLang(locale: AppLocale) {
  i18n.global.locale.value = locale
  localStorage.setItem('lang', locale)
  document.documentElement.lang = locale
}

export function toggleLang() {
  switchLang(i18n.global.locale.value === 'zh-CN' ? 'en-US' : 'zh-CN')
}

document.documentElement.lang = savedLang
