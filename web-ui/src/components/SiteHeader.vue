<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ArrowRight, Close, Menu, Promotion, Search } from '@element-plus/icons-vue'
import PersonalizationPanel from './PersonalizationPanel.vue'
import { usePreferences } from '../preferences'

const route = useRoute()
const router = useRouter()
const { locale } = useI18n()
const preferences = usePreferences()
const mobileOpen = ref(false)
const searchOpen = ref(false)
const query = ref('')
const searchInput = ref<HTMLInputElement | null>(null)

const linksZh = [
  { label: '使用场景', short: '场景', path: '/scenarios', hint: '金融风控、客服、运维与 RAG' },
  { label: '模型生态', short: '模型', path: '/models', hint: '模型矩阵、状态与路由策略' },
  { label: '核心特色', short: '特色', path: '/features', hint: '双引擎、审批、Trace 与工具' },
  { label: '文档中心', short: '文档', path: '/docs', hint: '快速开始、SDK 与 API 参考' },
  { label: '关于', short: '关于', path: '/about', hint: '架构拓扑、合规与开源路线' },
]
const linksEn = [
  { label: 'Use Cases', short: 'Cases', path: '/scenarios', hint: 'Risk, support, operations and RAG' },
  { label: 'Model Ecosystem', short: 'Models', path: '/models', hint: 'Model matrix, status and routing' },
  { label: 'Core Features', short: 'Features', path: '/features', hint: 'Dual engine, approvals, trace and tools' },
  { label: 'Documentation', short: 'Docs', path: '/docs', hint: 'Quick start, SDK and API reference' },
  { label: 'About', short: 'About', path: '/about', hint: 'Architecture, governance and roadmap' },
]
const links = computed(() => locale.value === 'en-US' ? linksEn : linksZh)
const searchShortcutLabel = computed(() => preferences.searchShortcut === 'slash' ? '/' : '⌘K')
const currentPath = computed(() => route.path)
const results = computed(() => {
  const keyword = query.value.trim().toLowerCase()
  const home = locale.value === 'en-US'
    ? { label: 'AgentHub Home', short: 'Home', path: '/', hint: 'Platform overview and live sandbox' }
    : { label: 'AgentHub 首页', short: '首页', path: '/', hint: '平台能力总览与动态沙箱' }
  const all = [home, ...links.value]
  return keyword ? all.filter(item => `${item.label}${item.hint}`.toLowerCase().includes(keyword)) : all
})

function navigate(path: string) {
  mobileOpen.value = false
  searchOpen.value = false
  query.value = ''
  router.push(path)
}
function openSearch() {
  searchOpen.value = true
  mobileOpen.value = false
  nextTick(() => searchInput.value?.focus())
}
function handleShortcut(event: KeyboardEvent) {
  const ctrlK = (event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k'
  const slash = event.key === '/' && !['INPUT', 'TEXTAREA', 'SELECT'].includes((event.target as HTMLElement)?.tagName)
  const shouldOpen = (preferences.searchShortcut === 'both' && (ctrlK || slash))
    || (preferences.searchShortcut === 'ctrl-k' && ctrlK)
    || (preferences.searchShortcut === 'slash' && slash)
  if (shouldOpen) {
    event.preventDefault()
    searchOpen.value ? (searchOpen.value = false) : openSearch()
  } else if (event.key === 'Escape') searchOpen.value = false
}
onMounted(() => window.addEventListener('keydown', handleShortcut))
onBeforeUnmount(() => window.removeEventListener('keydown', handleShortcut))
</script>

<template>
  <header class="site-header">
    <div class="site-header__inner">
      <button class="site-brand" aria-label="返回首页" @click="navigate('/')">
        <img class="site-brand__logo" src="/bg.svg" alt="AgentHub" />
        <span class="site-brand__name">AgentHub</span>
      </button>

      <nav class="site-nav" aria-label="主导航">
        <router-link v-for="link in links" :key="link.path" :to="link.path" :class="{ active: currentPath === link.path }">{{ link.short }}</router-link>
      </nav>

      <div class="site-header__actions">
        <button class="site-search-button" aria-label="打开全局搜索" @click="openSearch"><el-icon><Search /></el-icon><span>搜索</span><kbd>{{ searchShortcutLabel }}</kbd></button>
        <PersonalizationPanel />
        <button class="site-console-link" @click="navigate('/login')"><span>进入工作台</span><el-icon><ArrowRight /></el-icon></button>
        <button class="site-menu-button" :aria-label="mobileOpen ? '关闭菜单' : '打开菜单'" @click="mobileOpen = !mobileOpen"><el-icon><component :is="mobileOpen ? Close : Menu" /></el-icon></button>
      </div>
    </div>

    <div v-if="mobileOpen" class="site-mobile-menu">
      <button v-for="link in links" :key="link.path" :class="{ active: currentPath === link.path }" @click="navigate(link.path)"><span>{{ link.label }}</span><span>0{{ links.indexOf(link) + 1 }}</span></button>
      <button class="mobile-search" @click="openSearch"><el-icon><Search /></el-icon>搜索页面与文档</button>
      <button class="mobile-console" @click="navigate('/login')">进入 Agent 工作台 <el-icon><ArrowRight /></el-icon></button>
    </div>

    <Teleport to="body">
      <div v-if="searchOpen" class="site-search-backdrop" @pointerdown.self="searchOpen = false">
        <section class="site-search-dialog" role="dialog" aria-modal="true" aria-label="官网搜索">
          <header><el-icon><Search /></el-icon><input ref="searchInput" v-model="query" placeholder="搜索场景、模型、特色或文档…" @keydown.enter="results[0] && navigate(results[0].path)" /><button class="search-close" aria-label="关闭搜索" title="关闭" @click="searchOpen = false"><el-icon><Close /></el-icon></button></header>
          <div class="search-summary"><span>快速导航</span><b>{{ results.length }} RESULTS</b></div>
          <div class="search-results">
            <button v-for="item in results" :key="item.path" @click="navigate(item.path)"><span>{{ item.short.slice(0, 1) }}</span><p><strong>{{ item.label }}</strong><small>{{ item.hint }}</small></p><el-icon><Promotion /></el-icon></button>
            <div v-if="!results.length" class="search-empty">没有匹配的页面</div>
          </div>
          <footer><span><kbd>↵</kbd> 打开首项</span></footer>
        </section>
      </div>
    </Teleport>
  </header>
</template>

<style scoped>
.site-search-button { min-height: 34px; padding: 0 8px; display: inline-flex; align-items: center; gap: 6px; border: 1px solid #30373c; border-radius: 6px; background: #121619; color: #818b92; font: inherit; font-size: 9px; cursor: pointer; }.site-search-button:hover { border-color: #465058; color: #c9d2cc; }.site-search-button kbd, .site-search-dialog kbd { padding: 2px 5px; border: 1px solid #343c42; border-radius: 4px; background: #0c0f11; color: #697279; font: 7px ui-monospace, monospace; }
.site-search-backdrop { position: fixed; inset: 0; z-index: 200; padding-top: min(14vh, 110px); display: flex; align-items: flex-start; justify-content: center; background: rgba(0,0,0,.72); backdrop-filter: blur(5px); }.site-search-dialog { width: min(600px, calc(100% - 28px)); max-height: 70vh; display: flex; flex-direction: column; border: 1px solid #3b444a; border-radius: 8px; background: #101417; box-shadow: 0 32px 90px rgba(0,0,0,.62); overflow: hidden; }.site-search-dialog > header { min-height: 58px; padding: 0 10px 0 15px; display: flex; align-items: center; gap: 10px; border-bottom: 1px solid #293035; color: #5de49f; }.site-search-dialog input { min-width: 0; flex: 1; border: 0; outline: 0; background: transparent; color: #edf3ef; font: inherit; font-size: 12px; }.site-search-dialog input::placeholder { color: #5f686f; }.search-close { width: 36px; height: 36px; display: grid; place-items: center; border: 1px solid #343c42; border-radius: 6px; background: #14191d; color: #aab5bf; cursor: pointer; }.search-close:hover { border-color: #6366f1; color: #fff; }.search-summary { min-height: 38px; padding: 0 14px; display: flex; align-items: center; justify-content: space-between; color: #636d74; font: 7px ui-monospace, monospace; }.search-results { min-height: 0; padding: 0 7px 7px; overflow-y: auto; }.search-results button { width: 100%; min-height: 58px; padding: 8px 10px; display: grid; grid-template-columns: 36px 1fr 18px; gap: 10px; align-items: center; border: 0; border-radius: 6px; background: transparent; color: #d7dfda; font: inherit; text-align: left; cursor: pointer; }.search-results button:hover { background: #19231d; }.search-results button > span { width: 34px; height: 34px; display: grid; place-items: center; border: 1px solid #324139; border-radius: 6px; background: #16221b; color: #7ce6ad; font-size: 10px; font-weight: 800; }.search-results p { display: flex; flex-direction: column; gap: 4px; }.search-results strong { font-size: 10px; }.search-results small { color: #6d777e; font-size: 8px; }.search-results button > .el-icon { color: #515a61; }.search-empty { min-height: 180px; display: grid; place-items: center; color: #667078; font-size: 9px; }.site-search-dialog > footer { min-height: 40px; padding: 0 14px; display: flex; align-items: center; gap: 18px; border-top: 1px solid #293035; color: #606a71; font-size: 8px; }.site-search-dialog > footer span { display: flex; align-items: center; gap: 5px; }.mobile-search { align-items: center; justify-content: flex-start !important; gap: 8px; }
@media (max-width: 1080px) { .site-search-button span { display: none; }.site-search-button { width: 34px; padding: 0; justify-content: center; }.site-search-button kbd { display: none; } }
@media (max-width: 980px) { .site-search-button { display: none; } }
</style>
