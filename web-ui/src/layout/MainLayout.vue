<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowLeft, ArrowRight, Bell, CircleCheck, Collection, Connection,
  DataAnalysis, Document, House, Lock, Menu, Operation, Search,
  Share, SwitchButton, Tools, User,
} from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth'
import api from '../api'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const collapsed = ref(false)
const mobileOpen = ref(false)
const noticeOpen = ref(false)
const runtimeStatus = ref('UNKNOWN')
const pendingApprovals = ref(0)
let refreshTimer: number | undefined

const groups = [
  { label: '工作', items: [
    { path: '/console/dashboard', title: '今日工作台', icon: DataAnalysis },
    { path: '/console/agents', title: 'Agent', icon: Operation },
    { path: '/console/workflows', title: '流程编排', icon: Share },
    { path: '/console/knowledge', title: '知识库', icon: Collection },
  ] },
  { label: '能力', items: [
    { path: '/console/tools', title: '工具与插件', icon: Tools },
    { path: '/console/channels', title: '渠道接入', icon: Connection },
    { path: '/console/analytics', title: '用量分析', icon: DataAnalysis },
  ] },
  { label: '治理', items: [
    { path: '/console/guardrails', title: '安全护栏', icon: Lock },
    { path: '/console/approvals', title: '审批中心', icon: CircleCheck, badge: true },
    { path: '/console/audit', title: '审计记录', icon: Document },
    { path: '/console/users', title: '成员与权限', icon: User },
  ] },
]

const pageTitle = computed(() => (route.meta.title as string) || 'AgentHub')
const displayName = computed(() => authStore.user?.displayName || authStore.user?.username || '用户')
const initials = computed(() => displayName.value.trim().slice(0, 2).toUpperCase())
const runtimeUp = computed(() => runtimeStatus.value === 'UP')
const runtimeChecking = computed(() => runtimeStatus.value === 'UNKNOWN')
const runtimeLabel = computed(() => runtimeUp.value ? '可用' : runtimeChecking.value ? '检查中' : '离线')
const workspaceRuntimeLabel = computed(() => runtimeUp.value ? '运行正常' : runtimeChecking.value ? '正在检查运行时' : '运行时离线')

function isActive(path: string) {
  return route.path === path || (path === '/console/agents' && route.path.startsWith('/console/agents/'))
}
function navigate(path: string) { mobileOpen.value = false; router.push(path) }
async function logout() { await authStore.logout(); router.push('/login') }
async function refreshShellStatus() {
  try {
    const response = await api.get('/platform/overview') as any
    runtimeStatus.value = response.data?.runtime?.status || 'UNKNOWN'
    pendingApprovals.value = Number(response.data?.governance?.pendingApprovals || 0)
  } catch { runtimeStatus.value = 'DOWN' }
}
onMounted(async () => {
  await authStore.restoreSession()
  await refreshShellStatus()
  refreshTimer = window.setInterval(refreshShellStatus, 60000)
})
onBeforeUnmount(() => { if (refreshTimer) window.clearInterval(refreshTimer) })
</script>

<template>
  <div class="console-shell">
    <aside :class="['console-sidebar', { collapsed, mobile: mobileOpen }]">
      <button class="console-brand" aria-label="返回官网" @click="navigate('/')">
        <img src="/bg.svg" alt="AgentHub" />
        <span v-if="!collapsed" class="brand-copy"><strong>AgentHub</strong><small>企业工作空间</small></span>
      </button>

      <div v-if="!collapsed" class="workspace-switcher">
        <span class="workspace-avatar">企</span>
        <span><strong>企业 Agent 空间</strong><small><i :class="{ up: runtimeUp, checking: runtimeChecking }" />{{ workspaceRuntimeLabel }}</small></span>
        <b>⌄</b>
      </div>

      <nav class="console-navigation">
        <div v-for="group in groups" :key="group.label" class="nav-group">
          <span v-if="!collapsed" class="nav-group__label">{{ group.label }}</span>
          <button v-for="item in group.items" :key="item.path" :class="{ active: isActive(item.path) }" :title="collapsed ? item.title : undefined" @click="navigate(item.path)">
            <el-icon><component :is="item.icon" /></el-icon>
            <span v-if="!collapsed">{{ item.title }}</span>
            <b v-if="item.badge && pendingApprovals > 0">{{ pendingApprovals }}</b>
          </button>
        </div>
      </nav>

      <div class="console-sidebar__footer">
        <button @click="navigate('/')"><el-icon><House /></el-icon><span v-if="!collapsed">返回官网</span></button>
        <button @click="collapsed = !collapsed"><el-icon><component :is="collapsed ? ArrowRight : ArrowLeft" /></el-icon><span v-if="!collapsed">收起侧栏</span></button>
      </div>
    </aside>

    <button v-if="mobileOpen" class="console-overlay" aria-label="关闭导航" @click="mobileOpen = false" />

    <section class="console-main">
      <header class="console-topbar">
        <div class="topbar-leading">
          <button class="mobile-nav-trigger" aria-label="打开导航" @click="mobileOpen = !mobileOpen"><el-icon><Menu /></el-icon></button>
          <div><span>企业 Agent 空间</span><h1>{{ pageTitle }}</h1></div>
        </div>
        <div class="topbar-actions">
          <button class="command-search" @click="navigate('/console/agents')"><el-icon><Search /></el-icon><span>搜索 Agent、工具或成员</span><kbd>/</kbd></button>
          <div class="runtime-pill"><i :class="{ up: runtimeUp, checking: runtimeChecking }" /><span>执行引擎</span><strong>{{ runtimeLabel }}</strong></div>
          <div class="notice-wrap">
            <button class="notification-button" aria-label="通知" @click="noticeOpen = !noticeOpen"><el-icon><Bell /></el-icon><i v-if="pendingApprovals" /></button>
            <div v-if="noticeOpen" class="notice-popover"><strong>待处理事项</strong><button @click="navigate('/console/approvals')"><el-icon><CircleCheck /></el-icon><span>审批请求</span><b>{{ pendingApprovals }} 项</b></button><small><el-icon><Document /></el-icon>暂无其他系统告警</small></div>
          </div>
          <div class="user-block"><span class="user-avatar">{{ initials }}</span><span class="user-copy"><strong>{{ displayName }}</strong><small>{{ authStore.isAdmin ? '管理员' : '成员' }}</small></span></div>
          <button class="logout-button" title="退出登录" @click="logout"><el-icon><SwitchButton /></el-icon></button>
        </div>
      </header>
      <main class="console-content"><router-view /></main>
    </section>
  </div>
</template>

<style scoped>
.console-shell { height: 100vh; display: flex; overflow: hidden; background: var(--console-bg); color: var(--console-ink); font-family: Inter, "PingFang SC", "Microsoft YaHei", system-ui, sans-serif; }
.console-sidebar { position: relative; z-index: 40; width: 248px; flex: 0 0 248px; display: flex; flex-direction: column; border-right: 1px solid var(--console-line); background: #fbfcfa; transition: width 180ms ease, flex-basis 180ms ease; }
.console-sidebar.collapsed { width: 76px; flex-basis: 76px; }
.console-brand { height: 72px; padding: 0 18px; display: flex; align-items: center; gap: 11px; border: 0; background: transparent; color: var(--console-ink); font: inherit; text-align: left; cursor: pointer; }
.console-brand > img { width: 37px; height: 37px; flex: 0 0 37px; object-fit: contain; }
.hub-mark { position: relative; width: 37px; height: 37px; flex: 0 0 37px; display: block; border-radius: 8px; background: var(--console-primary-dark); overflow: hidden; }
.hub-mark i:first-child { position: absolute; left: 9px; top: 9px; width: 8px; height: 19px; border: 3px solid white; border-right: 0; border-radius: 3px 0 0 3px; }
.hub-mark i:nth-child(2) { position: absolute; right: 9px; top: 9px; width: 8px; height: 19px; border: 3px solid #bcd1c4; border-left: 0; border-radius: 0 3px 3px 0; }
.hub-mark b { position: absolute; left: 16px; top: 17px; width: 5px; height: 3px; background: white; }
.brand-copy { display: flex; flex-direction: column; gap: 2px; }
.brand-copy strong { font-size: 15px; }
.brand-copy small { color: #8b958f; font-size: 10px; }
.collapsed .console-brand { justify-content: center; padding: 0; }
.workspace-switcher { min-height: 66px; margin: 4px 12px 10px; padding: 10px; display: grid; grid-template-columns: 34px 1fr auto; gap: 9px; align-items: center; border: 1px solid var(--console-line); border-radius: 8px; background: #f3f6f2; }
.workspace-avatar { width: 34px; height: 34px; display: grid; place-items: center; border-radius: 7px; background: #dbe8df; color: var(--console-primary-dark); font-size: 13px; font-weight: 800; }
.workspace-switcher > span:nth-child(2) { min-width: 0; display: flex; flex-direction: column; gap: 4px; }
.workspace-switcher strong { overflow: hidden; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.workspace-switcher small { display: flex; align-items: center; gap: 5px; color: #87918b; font-size: 9px; }
.workspace-switcher small i { width: 6px; height: 6px; border-radius: 50%; background: var(--console-red); }
.workspace-switcher small i.up { background: var(--console-green); }
.workspace-switcher small i.checking, .runtime-pill i.checking { background: var(--console-yellow); }
.workspace-switcher > b { color: #98a19c; font-size: 11px; }
.console-navigation { flex: 1; padding: 2px 10px 16px; overflow-y: auto; }
.nav-group { margin-top: 16px; }
.nav-group__label { display: block; padding: 0 11px 7px; color: #98a19c; font-size: 10px; font-weight: 700; }
.nav-group button { position: relative; width: 100%; min-height: 42px; padding: 0 11px; display: flex; align-items: center; gap: 11px; border: 0; border-radius: 7px; background: transparent; color: #69756f; font: inherit; font-size: 12px; text-align: left; cursor: pointer; }
.nav-group button:hover { background: #f0f4f0; color: var(--console-ink); }
.nav-group button.active { background: var(--console-primary-soft); color: var(--console-primary-dark); font-weight: 700; }
.nav-group button .el-icon { width: 22px; flex: 0 0 22px; font-size: 17px; }
.nav-group button b { min-width: 20px; height: 20px; margin-left: auto; display: grid; place-items: center; border-radius: 10px; background: var(--console-coral); color: white; font-size: 9px; }
.collapsed .console-navigation { padding-inline: 10px; }
.collapsed .nav-group button { padding: 0; justify-content: center; }
.collapsed .nav-group button b { position: absolute; right: 0; top: 2px; min-width: 14px; height: 14px; }
.console-sidebar__footer { padding: 12px 10px; border-top: 1px solid var(--console-line); }
.console-sidebar__footer button { width: 100%; min-height: 38px; padding: 0 11px; display: flex; align-items: center; gap: 11px; border: 0; border-radius: 7px; background: transparent; color: #7d8882; font: inherit; font-size: 11px; cursor: pointer; }
.console-sidebar__footer button:hover { background: #f0f4f0; color: var(--console-ink); }
.collapsed .console-sidebar__footer button { padding: 0; justify-content: center; }
.console-main { min-width: 0; flex: 1; display: flex; flex-direction: column; }
.console-topbar { height: 72px; flex: 0 0 72px; padding: 0 24px; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid var(--console-line); background: rgba(255, 255, 255, .9); backdrop-filter: blur(14px); }
.topbar-leading { display: flex; align-items: center; gap: 12px; }
.topbar-leading span { color: #929c96; font-size: 9px; }
.topbar-leading h1 { margin-top: 2px; font-size: 16px; }
.mobile-nav-trigger { width: 38px; height: 38px; display: none; place-items: center; border: 1px solid var(--console-line); border-radius: 7px; background: white; color: var(--console-ink); }
.topbar-actions { display: flex; align-items: center; gap: 9px; }
.command-search { width: min(260px, 24vw); min-height: 38px; padding: 0 10px; display: flex; align-items: center; gap: 8px; border: 1px solid var(--console-line); border-radius: 7px; background: #f8faf7; color: #89938d; font: inherit; font-size: 10px; cursor: pointer; }
.command-search span { overflow: hidden; flex: 1; text-align: left; text-overflow: ellipsis; white-space: nowrap; }
.command-search kbd { min-width: 20px; padding: 2px 5px; border: 1px solid var(--console-line); border-radius: 4px; background: white; color: #87918b; }
.runtime-pill { min-height: 36px; padding: 0 10px; display: flex; align-items: center; gap: 7px; border: 1px solid var(--console-line); border-radius: 7px; background: #f7faf7; font-size: 9px; }
.runtime-pill i { width: 7px; height: 7px; border-radius: 50%; background: var(--console-red); }
.runtime-pill i.up { background: var(--console-green); }
.runtime-pill span { color: var(--console-muted); }
.notice-wrap { position: relative; }
.notification-button, .logout-button { position: relative; width: 36px; height: 36px; display: grid; place-items: center; border: 1px solid var(--console-line); border-radius: 7px; background: white; color: var(--console-ink); cursor: pointer; }
.notification-button > i { position: absolute; right: 6px; top: 6px; width: 6px; height: 6px; border: 2px solid white; border-radius: 50%; background: var(--console-coral); }
.notice-popover { position: absolute; z-index: 20; top: 44px; right: 0; width: 250px; padding: 14px; border: 1px solid var(--console-line); border-radius: 8px; background: white; box-shadow: var(--console-shadow); }
.notice-popover > strong { font-size: 12px; }
.notice-popover button { width: 100%; min-height: 44px; margin-top: 10px; padding: 0 10px; display: grid; grid-template-columns: 22px 1fr auto; gap: 7px; align-items: center; border: 0; border-radius: 6px; background: var(--console-primary-soft); color: var(--console-primary-dark); font: inherit; font-size: 11px; text-align: left; cursor: pointer; }
.notice-popover button > .el-icon { font-size: 16px; }
.notice-popover small { margin-top: 12px; display: flex; align-items: center; gap: 6px; color: #929c96; font-size: 9px; }
.user-block { min-height: 38px; padding-left: 10px; display: flex; align-items: center; gap: 8px; border-left: 1px solid var(--console-line); }
.user-avatar { width: 32px; height: 32px; display: grid; place-items: center; border-radius: 8px; background: var(--console-blue-soft); color: #4c6b82; font-size: 10px; font-weight: 800; }
.user-copy { display: flex; flex-direction: column; gap: 2px; }
.user-copy strong { font-size: 10px; }
.user-copy small { color: #8b958f; font-size: 8px; }
.console-content { flex: 1; min-height: 0; overflow-y: auto; padding: 26px; }
.console-overlay { display: none; }
@media (max-width: 1100px) { .command-search { display: none; } }
@media (max-width: 980px) {
  .console-sidebar { position: fixed; inset: 0 auto 0 0; width: 248px !important; transform: translateX(-100%); transition: transform 180ms ease; }
  .console-sidebar.mobile { transform: translateX(0); }
  .console-sidebar.mobile .console-brand { justify-content: flex-start; padding: 0 18px; }
  .console-overlay { position: fixed; inset: 0; z-index: 30; display: block; border: 0; background: rgba(31, 44, 37, .38); }
  .mobile-nav-trigger { display: grid; }
  .runtime-pill { display: none; }
}
@media (max-width: 640px) {
  .console-topbar { padding: 0 14px; }
  .console-content { padding: 18px 14px; }
  .user-copy, .notification-button { display: none; }
}
</style>
