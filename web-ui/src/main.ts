import { createApp, watch } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import en from 'element-plus/es/locale/lang/en'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

import App from './App.vue'
import router from './router'
import { i18n } from './i18n'
import './style.css'
import './styles/site.css'
import './styles/console.css'
import './styles/console-refine.css'

const app = createApp(App)

// 注册所有 Element Plus 图标
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(createPinia())
app.use(router)
app.use(i18n)

// Element Plus 默认中文，i18n 切换时跟随
const epLocales: Record<string, any> = { 'zh-CN': zhCn, 'en-US': en }
const epLocale = epLocales[i18n.global.locale.value] || zhCn
app.use(ElementPlus, { locale: epLocale })

// 监听语言切换，动态更新 Element Plus 语言
watch(
  () => i18n.global.locale.value,
  (val) => {
    const elLocale = epLocales[val] || zhCn
    // Element Plus 的 locale 通过 config 更新
    const elConfig = app.config.globalProperties.$ELEMENT
    if (elConfig) elConfig.locale = elLocale
  }
)

app.mount('#app')
