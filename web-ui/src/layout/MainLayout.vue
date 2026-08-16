<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import {
  ArrowLeft, ArrowRight, Bell, CircleCheck, Collection, Connection,
  DataAnalysis, Document, House, Lock, Menu, Operation, Search,
  Share, SwitchButton, Tools, User, Close, TopRight, Promotion,
} from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth'
import api from '../api'
import PersonalizationPanel from '../components/PersonalizationPanel.vue'
import { usePreferences } from '../preferences'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const { locale } = useI18n()
const preferences = usePreferences()
const collapsed = ref(false)
const mobileOpen = ref(false)
const noticeOpen = ref(false)
const commandOpen = ref(false)
const commandQuery = ref('')
const commandInput = ref<HTMLInputElement | null>(null)
const runtimeStatus = ref('UNKNOWN')
const pendingApprovals = ref(0)
let refreshTimer: number | undefined
let previousPendingApprovals = 0

const groupsZh = [
  { label: '构建', items: [
    { path: '/console/dashboard', title: '工作台', hint: '运行概览与待办', icon: DataAnalysis },
    { path: '/console/agents', title: 'Agent', hint: '构建、测试与发布', icon: Operation },
    { path: '/console/workflows', title: '流程编排', hint: '多 Agent 工作流', icon: Share },
    { path: '/console/knowledge', title: '知识库', hint: 'RAG 数据资产', icon: Collection },
    { path: '/console/tools', title: '工具与插件', hint: '运行时能力目录', icon: Tools },
  ] },
  { label: '运营', items: [
    { path: '/console/operations', title: '发布与运行', hint: '版本、评测、Trace 与诊断', icon: Operation },
    { path: '/console/channels', title: '渠道接入', hint: '消息与 API 分发', icon: Connection },
    { path: '/console/analytics', title: '用量分析', hint: 'Token、延迟与成本', icon: DataAnalysis },
  ] },
  { label: '治理', items: [
    { path: '/console/guardrails', title: '安全护栏', hint: '输入输出策略', icon: Lock },
    { path: '/console/approvals', title: '审批中心', hint: '高风险动作卡点', icon: CircleCheck, badge: true },
    { path: '/console/audit', title: '审计记录', hint: '全链路追溯', icon: Document },
    { path: '/console/users', title: '成员与权限', hint: '组织访问控制', icon: User },
  ] },
]
const groupsEn = [
  { label: 'BUILD', items: [
    { path: '/console/dashboard', title: 'Dashboard', hint: 'Runtime overview and tasks', icon: DataAnalysis },
    { path: '/console/agents', title: 'Agents', hint: 'Build, test and publish', icon: Operation },
    { path: '/console/workflows', title: 'Workflows', hint: 'Multi-Agent orchestration', icon: Share },
    { path: '/console/knowledge', title: 'Knowledge', hint: 'RAG data assets', icon: Collection },
    { path: '/console/tools', title: 'Tools & Plugins', hint: 'Runtime capability catalog', icon: Tools },
  ] },
  { label: 'OPERATE', items: [
    { path: '/console/operations', title: 'Release & Runtime', hint: 'Versions, evals, traces and diagnostics', icon: Operation },
    { path: '/console/channels', title: 'Channels', hint: 'Message and API delivery', icon: Connection },
    { path: '/console/analytics', title: 'Analytics', hint: 'Tokens, latency and cost', icon: DataAnalysis },
  ] },
  { label: 'GOVERN', items: [
    { path: '/console/guardrails', title: 'Guardrails', hint: 'Input and output policies', icon: Lock },
    { path: '/console/approvals', title: 'Approvals', hint: 'High-risk action gates', icon: CircleCheck, badge: true },
    { path: '/console/audit', title: 'Audit Log', hint: 'End-to-end traceability', icon: Document },
    { path: '/console/users', title: 'Members & Access', hint: 'Organization access control', icon: User },
  ] },
]
const groups = computed(() => locale.value === 'en-US' ? groupsEn : groupsZh)

const allItems = computed(() => groups.value.flatMap(group => group.items.map(item => ({ ...item, group: group.label }))))
const filteredCommands = computed(() => {
  const keyword = commandQuery.value.trim().toLowerCase()
  if (!keyword) return allItems.value
  return allItems.value.filter(item => `${item.title} ${item.hint} ${item.group}`.toLowerCase().includes(keyword))
})
const englishPageTitles: Record<string, string> = {
  Dashboard: 'Dashboard', Agents: 'Agent Management', AgentChat: 'Agent Chat', AgentDetail: 'Agent Details',
  Users: 'User Management', Approvals: 'Approval Center', Audit: 'Audit Log', Tools: 'Tool Market',
  Knowledge: 'Knowledge Base', Workflows: 'Workflow Studio', Guardrails: 'Guardrails',
  Analytics: 'Usage Analytics', Channels: 'Channel Integrations',
}
const pageTitle = computed(() => locale.value === 'en-US'
  ? englishPageTitles[String(route.name)] || 'AgentHub'
  : (route.meta.title as string) || 'AgentHub')
const displayName = computed(() => authStore.user?.displayName || authStore.user?.username || '用户')
const initials = computed(() => displayName.value.trim().slice(0, 2).toUpperCase())
const runtimeUp = computed(() => runtimeStatus.value === 'UP')
const runtimeChecking = computed(() => runtimeStatus.value === 'UNKNOWN')
const runtimeLabel = computed(() => locale.value === 'en-US'
  ? (runtimeUp.value ? 'Operational' : runtimeChecking.value ? 'Checking' : 'Unavailable')
  : (runtimeUp.value ? '运行正常' : runtimeChecking.value ? '检查中' : '运行异常'))
const searchShortcutLabel = computed(() => preferences.searchShortcut === 'slash' ? '/' : '⌘ K')

function isActive(path: string) {
  return route.path === path || (path === '/console/agents' && route.path.startsWith('/console/agents/'))
}
function navigate(path: string) {
  mobileOpen.value = false
  noticeOpen.value = false
  commandOpen.value = false
  commandQuery.value = ''
  router.push(path)
}
function openCommand() {
  commandOpen.value = true
  noticeOpen.value = false
  nextTick(() => commandInput.value?.focus())
}
function handleShortcut(event: KeyboardEvent) {
  const target = event.target as HTMLElement
  const isTyping = ['INPUT', 'TEXTAREA', 'SELECT'].includes(target?.tagName) || target?.isContentEditable
  const ctrlK = (event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k'
  const slash = event.key === '/' && !isTyping
  const shouldOpen = (preferences.searchShortcut === 'both' && (ctrlK || slash))
    || (preferences.searchShortcut === 'ctrl-k' && ctrlK)
    || (preferences.searchShortcut === 'slash' && slash)
  if (shouldOpen) {
    event.preventDefault()
    commandOpen.value ? (commandOpen.value = false) : openCommand()
  } else if (event.key === 'Escape') {
    commandOpen.value = false
    noticeOpen.value = false
  }
}
async function logout() { await authStore.logout(); router.push('/login') }
function playApprovalTone() {
  try {
    const AudioContextClass = window.AudioContext || (window as any).webkitAudioContext
    if (!AudioContextClass) return
    const context = new AudioContextClass()
    const oscillator = context.createOscillator()
    const gain = context.createGain()
    oscillator.frequency.value = 620
    gain.gain.setValueAtTime(.05, context.currentTime)
    gain.gain.exponentialRampToValueAtTime(.001, context.currentTime + .22)
    oscillator.connect(gain); gain.connect(context.destination)
    oscillator.start(); oscillator.stop(context.currentTime + .22)
  } catch { /* Audio can be blocked until the first user interaction. */ }
}
function notifyNewApprovals(count: number) {
  if (count <= previousPendingApprovals) { previousPendingApprovals = count; return }
  const incoming = count - previousPendingApprovals
  if (previousPendingApprovals > 0 && preferences.approvalSound) playApprovalTone()
  if (previousPendingApprovals > 0 && preferences.desktopNotifications && typeof Notification !== 'undefined' && Notification.permission === 'granted') {
    new Notification(locale.value === 'en-US' ? 'AgentHub approval required' : 'AgentHub 有新的审批待办', {
      body: locale.value === 'en-US' ? `${incoming} new high-risk action(s) require a decision.` : `${incoming} 个高风险动作等待处理。`,
    })
  }
  previousPendingApprovals = count
}
async function refreshShellStatus() {
  try {
    const response = await api.get('/platform/overview') as any
    runtimeStatus.value = response.data?.runtime?.status || 'UNKNOWN'
    pendingApprovals.value = Number(response.data?.governance?.pendingApprovals || 0)
    notifyNewApprovals(pendingApprovals.value)
  } catch { runtimeStatus.value = 'DOWN' }
}

watch(() => route.fullPath, () => { mobileOpen.value = false; noticeOpen.value = false })
onMounted(async () => {
  window.addEventListener('keydown', handleShortcut)
  await authStore.restoreSession()
  await refreshShellStatus()
  refreshTimer = window.setInterval(refreshShellStatus, 60000)
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleShortcut)
  if (refreshTimer) window.clearInterval(refreshTimer)
})
</script>

<template>
  <div class="console-shell">
    <aside :class="['console-sidebar', { collapsed, mobile: mobileOpen }]">
      <div class="console-brand-row">
        <button class="console-brand" aria-label="AgentHub 首页" @click="navigate('/console/dashboard')">
          <span class="brand-mark"><i /><i /><b /></span>
          <span v-if="!collapsed" class="brand-copy"><strong>AgentHub</strong><small>CONTROL PLANE</small></span>
        </button>
        <button v-if="!collapsed" class="sidebar-collapse" aria-label="收起侧栏" @click="collapsed = true"><el-icon><ArrowLeft /></el-icon></button>
      </div>

      <button v-if="!collapsed" class="workspace-switcher" @click="openCommand">
        <span class="workspace-avatar">AH</span>
        <span><strong>企业 Agent 空间</strong><small><i :class="{ up: runtimeUp, checking: runtimeChecking }" />{{ runtimeLabel }}</small></span>
        <kbd>⌘K</kbd>
      </button>

      <nav class="console-navigation" aria-label="控制台导航">
        <div v-for="group in groups" :key="group.label" class="nav-group">
          <span v-if="!collapsed" class="nav-group__label">{{ group.label }}</span>
          <button v-for="item in group.items" :key="item.path" :class="{ active: isActive(item.path) }" :title="collapsed ? item.title : undefined" @click="navigate(item.path)">
            <el-icon><component :is="item.icon" /></el-icon>
            <span v-if="!collapsed">{{ item.title }}</span>
            <b v-if="item.badge && preferences.approvalBadge && pendingApprovals > 0">{{ pendingApprovals }}</b>
          </button>
        </div>
      </nav>

      <div class="console-sidebar__footer">
        <button @click="navigate('/')"><el-icon><House /></el-icon><span v-if="!collapsed">产品主页</span><el-icon v-if="!collapsed" class="footer-tail"><TopRight /></el-icon></button>
        <button v-if="collapsed" aria-label="展开侧栏" @click="collapsed = false"><el-icon><ArrowRight /></el-icon></button>
        <div v-if="!collapsed" class="sidebar-user">
          <span>{{ initials }}</span><p><strong>{{ displayName }}</strong><small>{{ authStore.isAdmin ? 'Workspace Admin' : 'Member' }}</small></p>
          <button aria-label="退出登录" title="退出登录" @click.stop="logout"><el-icon><SwitchButton /></el-icon></button>
        </div>
      </div>
    </aside>

    <button v-if="mobileOpen" class="console-overlay" aria-label="关闭导航" @click="mobileOpen = false" />

    <section class="console-main">
      <header class="console-topbar">
        <div class="topbar-leading">
          <button class="mobile-nav-trigger" aria-label="打开导航" @click="mobileOpen = !mobileOpen"><el-icon><Menu /></el-icon></button>
          <div class="breadcrumb"><span>AgentHub</span><i>/</i><strong>{{ pageTitle }}</strong></div>
        </div>
        <div class="topbar-actions">
          <button class="command-search" @click="openCommand"><el-icon><Search /></el-icon><span>搜索资源或跳转</span><kbd>{{ searchShortcutLabel }}</kbd></button>
          <PersonalizationPanel compact />
          <div class="runtime-pill"><i :class="{ up: runtimeUp, checking: runtimeChecking }" /><span>Java</span><b>UP</b><em /><span>Runtime</span><b>{{ runtimeUp ? 'UP' : 'DOWN' }}</b></div>
          <div class="notice-wrap">
            <button class="notification-button" aria-label="通知" @click="noticeOpen = !noticeOpen"><el-icon><Bell /></el-icon><i v-if="preferences.approvalBadge && pendingApprovals" /></button>
            <div v-if="noticeOpen" class="notice-popover">
              <header><span>待处理事项</span><button aria-label="关闭" @click="noticeOpen = false"><el-icon><Close /></el-icon></button></header>
              <button class="notice-item" @click="navigate('/console/approvals')"><span class="notice-icon"><el-icon><CircleCheck /></el-icon></span><p><strong>审批请求</strong><small>高风险工具等待人工决策</small></p><b>{{ pendingApprovals }}</b></button>
              <button class="notice-item muted" @click="navigate('/console/audit')"><span class="notice-icon"><el-icon><Document /></el-icon></span><p><strong>系统事件</strong><small>暂无未读运行告警</small></p><b>0</b></button>
            </div>
          </div>
        </div>
      </header>
      <main class="console-content"><router-view /></main>
    </section>

    <div v-if="commandOpen" class="command-backdrop" @pointerdown.self="commandOpen = false">
      <section class="command-palette" role="dialog" aria-modal="true" aria-label="全局命令">
        <header><el-icon><Search /></el-icon><input ref="commandInput" v-model="commandQuery" placeholder="搜索 Agent、审批、工具或页面…" @keydown.enter="filteredCommands[0] && navigate(filteredCommands[0].path)" /><button class="command-close" aria-label="关闭搜索" title="关闭" @click="commandOpen = false"><el-icon><Close /></el-icon></button></header>
        <div class="command-context"><span>企业 Agent 空间</span><b>{{ filteredCommands.length }} 个结果</b></div>
        <div class="command-results">
          <button v-for="item in filteredCommands" :key="item.path" @click="navigate(item.path)">
            <span class="command-icon"><el-icon><component :is="item.icon" /></el-icon></span>
            <p><strong>{{ item.title }}</strong><small>{{ item.hint }}</small></p>
            <em>{{ item.group }}</em><el-icon><Promotion /></el-icon>
          </button>
          <div v-if="!filteredCommands.length" class="command-empty"><Search /><strong>没有匹配结果</strong><span>换一个关键词试试</span></div>
        </div>
        <footer><span><kbd>↑</kbd><kbd>↓</kbd> 浏览</span><span><kbd>↵</kbd> 打开</span><span><kbd>ESC</kbd> 关闭</span></footer>
      </section>
    </div>
  </div>
</template>

<style scoped>
.console-shell { height: 100vh; display: flex; overflow: hidden; background: var(--console-bg); color: var(--console-ink); font-family: Inter, "PingFang SC", "Microsoft YaHei", system-ui, sans-serif; }
.console-sidebar { position: relative; z-index: 40; width: 236px; flex: 0 0 236px; display: flex; flex-direction: column; border-right: 1px solid var(--console-line); background: #0b0d0f; transition: width 180ms ease, flex-basis 180ms ease, transform 180ms ease; }
.console-sidebar.collapsed { width: 68px; flex-basis: 68px; }
.console-brand-row { height: 64px; padding: 0 12px; display: flex; align-items: center; border-bottom: 1px solid var(--console-line); }
.console-brand { min-width: 0; flex: 1; display: flex; align-items: center; gap: 10px; border: 0; background: transparent; color: var(--console-ink); font: inherit; text-align: left; cursor: pointer; }
.brand-mark { position: relative; width: 31px; height: 31px; flex: 0 0 31px; display: block; border: 1px solid #41474d; border-radius: 7px; background: #171a1e; box-shadow: 0 0 18px rgba(85, 231, 166, .09); }
.brand-mark i { position: absolute; top: 8px; width: 7px; height: 14px; border: 2px solid #e9f1ec; }
.brand-mark i:first-child { left: 7px; border-right: 0; }.brand-mark i:nth-child(2) { right: 7px; border-left: 0; border-color: var(--console-accent); }.brand-mark b { position: absolute; left: 13px; top: 14px; width: 5px; height: 2px; background: #d9e6de; }
.brand-copy { min-width: 0; display: flex; flex-direction: column; gap: 3px; }.brand-copy strong { color: #f5f8f6; font-size: 14px; }.brand-copy small { color: #626a70; font: 8px ui-monospace, monospace; letter-spacing: .08em; }
.sidebar-collapse { width: 28px; height: 28px; display: grid; place-items: center; border: 0; border-radius: 5px; background: transparent; color: #697178; cursor: pointer; }.sidebar-collapse:hover { background: #191d21; color: #d6ded9; }
.collapsed .console-brand { justify-content: center; }.collapsed .console-brand-row { padding: 0; }
.workspace-switcher { min-height: 58px; margin: 12px 10px 4px; padding: 9px; display: grid; grid-template-columns: 32px 1fr auto; gap: 9px; align-items: center; border: 1px solid #272c31; border-radius: 7px; background: #111417; color: #dfe7e2; font: inherit; text-align: left; cursor: pointer; }.workspace-switcher:hover { border-color: #3c444a; background: #15191c; }
.workspace-avatar { width: 32px; height: 32px; display: grid; place-items: center; border-radius: 6px; background: #24392f; color: #85efbd; font: 9px ui-monospace, monospace; font-weight: 800; }.workspace-switcher > span:nth-child(2) { min-width: 0; display: flex; flex-direction: column; gap: 4px; }.workspace-switcher strong { overflow: hidden; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }.workspace-switcher small { display: flex; align-items: center; gap: 5px; color: #707981; font-size: 8px; }.workspace-switcher small i { width: 6px; height: 6px; border-radius: 50%; background: var(--console-danger); }.workspace-switcher small i.up { background: var(--console-accent); box-shadow: 0 0 8px rgba(87,229,161,.55); }.workspace-switcher small i.checking { background: var(--console-warning); }.workspace-switcher kbd { padding: 3px 5px; border: 1px solid #30363b; border-radius: 4px; color: #656e75; font: 7px ui-monospace, monospace; }
.console-navigation { flex: 1; padding: 4px 8px 18px; overflow-y: auto; scrollbar-width: thin; scrollbar-color: #2c3237 transparent; }.nav-group { margin-top: 15px; }.nav-group__label { display: block; padding: 0 10px 6px; color: #515960; font-size: 9px; font-weight: 700; }.nav-group button { position: relative; width: 100%; min-height: 38px; padding: 0 10px; display: flex; align-items: center; gap: 10px; border: 0; border-radius: 6px; background: transparent; color: #858e95; font: inherit; font-size: 11px; text-align: left; cursor: pointer; }.nav-group button::before { content: ''; position: absolute; left: 0; width: 2px; height: 16px; border-radius: 2px; background: transparent; }.nav-group button:hover { background: #15191d; color: #dbe3de; }.nav-group button.active { background: #19211d; color: #e8f5ed; }.nav-group button.active::before { background: var(--console-accent); box-shadow: 0 0 10px rgba(84,234,164,.7); }.nav-group button .el-icon { width: 19px; flex: 0 0 19px; color: #707981; font-size: 15px; }.nav-group button.active .el-icon { color: var(--console-accent); }.nav-group button b { min-width: 19px; height: 18px; margin-left: auto; padding: 0 5px; display: grid; place-items: center; border-radius: 9px; background: #5a3326; color: #ffc1a5; font-size: 8px; }.collapsed .console-navigation { padding-inline: 8px; }.collapsed .nav-group button { padding: 0; justify-content: center; }.collapsed .nav-group button b { position: absolute; right: 1px; top: 1px; min-width: 13px; height: 13px; padding: 0 3px; }
.console-sidebar__footer { padding: 10px 8px; border-top: 1px solid var(--console-line); }.console-sidebar__footer > button { width: 100%; min-height: 36px; padding: 0 10px; display: flex; align-items: center; gap: 10px; border: 0; border-radius: 6px; background: transparent; color: #737c83; font: inherit; font-size: 10px; cursor: pointer; }.console-sidebar__footer > button:hover { background: #15191d; color: #dce3df; }.footer-tail { margin-left: auto; }
.sidebar-user { min-height: 50px; margin-top: 7px; padding: 8px 7px; display: grid; grid-template-columns: 31px 1fr 28px; gap: 8px; align-items: center; border-top: 1px solid #20252a; }.sidebar-user > span { width: 31px; height: 31px; display: grid; place-items: center; border-radius: 6px; background: #202832; color: #9ccff3; font-size: 9px; font-weight: 800; }.sidebar-user p { min-width: 0; display: flex; flex-direction: column; gap: 3px; }.sidebar-user strong { overflow: hidden; color: #d9e1dc; font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }.sidebar-user small { color: #5d666d; font: 7px ui-monospace, monospace; }.sidebar-user button { width: 28px; height: 28px; display: grid; place-items: center; border: 0; border-radius: 5px; background: transparent; color: #606970; cursor: pointer; }.sidebar-user button:hover { background: #22272c; color: #e5ebe7; }
.console-main { min-width: 0; flex: 1; display: flex; flex-direction: column; }.console-topbar { height: 64px; flex: 0 0 64px; padding: 0 20px; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid var(--console-line); background: rgba(13, 16, 19, .88); backdrop-filter: blur(16px); }.topbar-leading { min-width: 0; display: flex; align-items: center; gap: 12px; }.mobile-nav-trigger { width: 34px; height: 34px; display: none; place-items: center; border: 1px solid var(--console-line); border-radius: 6px; background: var(--console-panel); color: var(--console-ink); }.breadcrumb { min-width: 0; display: flex; align-items: center; gap: 8px; font-size: 11px; }.breadcrumb span, .breadcrumb i { color: #555e65; font-style: normal; }.breadcrumb strong { overflow: hidden; color: #cfd8d2; text-overflow: ellipsis; white-space: nowrap; }.topbar-actions { display: flex; align-items: center; gap: 8px; }.command-search { width: min(240px, 25vw); min-height: 34px; padding: 0 8px; display: flex; align-items: center; gap: 7px; border: 1px solid #30363b; border-radius: 6px; background: #13171a; color: #707980; font: inherit; font-size: 9px; cursor: pointer; }.command-search:hover { border-color: #485057; color: #aab3ad; }.command-search span { flex: 1; text-align: left; }.command-search kbd, .command-palette kbd { padding: 2px 5px; border: 1px solid #353c42; border-radius: 4px; background: #0d1012; color: #6e777e; font: 7px ui-monospace, monospace; }.runtime-pill { min-height: 34px; padding: 0 9px; display: flex; align-items: center; gap: 6px; border: 1px solid #292f34; border-radius: 6px; background: #111518; font-size: 8px; }.runtime-pill > i { width: 6px; height: 6px; border-radius: 50%; background: var(--console-danger); }.runtime-pill > i.up { background: var(--console-accent); box-shadow: 0 0 8px rgba(84,234,164,.6); }.runtime-pill > i.checking { background: var(--console-warning); }.runtime-pill span { color: #697279; }.runtime-pill b { color: #aeb8b1; font: 7px ui-monospace, monospace; }.runtime-pill em { width: 1px; height: 13px; background: #30363a; }
.notice-wrap { position: relative; }.notification-button { position: relative; width: 34px; height: 34px; display: grid; place-items: center; border: 1px solid #30363b; border-radius: 6px; background: #13171a; color: #8c969d; cursor: pointer; }.notification-button > i { position: absolute; right: 6px; top: 6px; width: 6px; height: 6px; border: 2px solid #13171a; border-radius: 50%; background: var(--console-warning); }.notice-popover { position: absolute; z-index: 60; top: 42px; right: 0; width: 310px; padding: 8px; border: 1px solid #343b41; border-radius: 8px; background: #121619; box-shadow: 0 22px 55px rgba(0,0,0,.42); }.notice-popover header { min-height: 38px; padding: 0 6px 5px 8px; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid #282e33; color: #d7dfda; font-size: 10px; font-weight: 700; }.notice-popover header button { width: 28px; height: 28px; display: grid; place-items: center; border: 0; background: transparent; color: #6e777e; }.notice-item { width: 100%; min-height: 62px; margin-top: 5px; padding: 8px; display: grid; grid-template-columns: 34px 1fr auto; gap: 9px; align-items: center; border: 0; border-radius: 6px; background: #171c1f; color: #dce4df; font: inherit; text-align: left; cursor: pointer; }.notice-item:hover { background: #1c2420; }.notice-icon { width: 34px; height: 34px; display: grid; place-items: center; border-radius: 6px; background: #3b311d; color: #ffd17b; }.notice-item p { display: flex; flex-direction: column; gap: 4px; }.notice-item strong { font-size: 10px; }.notice-item small { color: #717a81; font-size: 8px; }.notice-item > b { color: #ffc76c; font-size: 11px; }.notice-item.muted .notice-icon { background: #1e2932; color: #88bde1; }.notice-item.muted > b { color: #667078; }
.console-content { flex: 1; min-height: 0; overflow-y: auto; padding: 22px; background: radial-gradient(circle at 88% 4%, rgba(77, 152, 121, .055), transparent 25%), var(--console-bg); }.console-overlay { display: none; }
.command-backdrop { position: fixed; inset: 0; z-index: 100; padding-top: min(14vh, 120px); display: flex; justify-content: center; align-items: flex-start; background: rgba(0,0,0,.68); backdrop-filter: blur(4px); }.command-palette { width: min(620px, calc(100% - 28px)); max-height: min(620px, 72vh); display: flex; flex-direction: column; border: 1px solid #3d454b; border-radius: 8px; background: #101417; box-shadow: 0 35px 90px rgba(0,0,0,.62), 0 0 0 1px rgba(123,241,182,.04); overflow: hidden; }.command-palette > header { min-height: 58px; padding: 0 15px; display: flex; align-items: center; gap: 11px; border-bottom: 1px solid #2a3035; color: var(--console-accent); }.command-palette input { min-width: 0; flex: 1; border: 0; outline: 0; background: transparent; color: #edf3ef; font: inherit; font-size: 13px; }.command-palette input::placeholder { color: #5d666d; }.command-context { min-height: 38px; padding: 0 15px; display: flex; align-items: center; justify-content: space-between; color: #636c73; font: 8px ui-monospace, monospace; }.command-results { min-height: 0; padding: 0 7px 7px; overflow-y: auto; }.command-results > button { width: 100%; min-height: 57px; padding: 7px 9px; display: grid; grid-template-columns: 38px 1fr auto 18px; gap: 10px; align-items: center; border: 0; border-radius: 6px; background: transparent; color: #d8e0db; font: inherit; text-align: left; cursor: pointer; }.command-results > button:hover, .command-results > button:focus { outline: 0; background: #1a211d; }.command-icon { width: 36px; height: 36px; display: grid; place-items: center; border: 1px solid #30373c; border-radius: 6px; background: #151a1d; color: #8abfa6; }.command-results p { display: flex; flex-direction: column; gap: 4px; }.command-results strong { font-size: 10px; }.command-results small { color: #687179; font-size: 8px; }.command-results em { color: #697279; font-size: 8px; font-style: normal; }.command-results > button > .el-icon { color: #505960; }.command-empty { min-height: 220px; display: flex; flex-direction: column; align-items: center; justify-content: center; color: #5e676e; }.command-empty svg { width: 28px; }.command-empty strong { margin-top: 12px; color: #bbc4be; font-size: 11px; }.command-empty span { margin-top: 5px; font-size: 8px; }.command-palette > footer { min-height: 40px; padding: 0 14px; display: flex; align-items: center; gap: 18px; border-top: 1px solid #292f34; color: #616a71; font-size: 8px; }.command-palette > footer span { display: flex; align-items: center; gap: 4px; }
.command-close { width: 36px; height: 36px; display: grid; place-items: center; border: 1px solid #343c42; border-radius: 6px; background: #14191d; color: #aab5bf; cursor: pointer; }.command-close:hover { border-color: var(--console-primary); color: #fff; }
@media (max-width: 1100px) { .command-search { width: 170px; }.runtime-pill { display: none; } }
@media (max-width: 900px) { .console-sidebar { position: fixed; inset: 0 auto 0 0; width: 236px !important; transform: translateX(-100%); }.console-sidebar.mobile { transform: translateX(0); }.console-overlay { position: fixed; inset: 0; z-index: 30; display: block; border: 0; background: rgba(0,0,0,.65); }.mobile-nav-trigger { display: grid; }.command-search span { display: none; }.command-search { width: 36px; padding: 0; justify-content: center; }.command-search kbd { display: none; } }
@media (max-width: 640px) { .console-topbar { padding: 0 12px; }.console-content { padding: 14px 12px; }.breadcrumb span, .breadcrumb i { display: none; }.command-palette > footer { display: none; }.notice-popover { position: fixed; top: 58px; right: 10px; left: 10px; width: auto; } }
</style>
