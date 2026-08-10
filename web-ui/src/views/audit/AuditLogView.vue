<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Document, Refresh, Search } from '@element-plus/icons-vue'
import api from '../../api'

const logs = ref<any[]>([])
const loading = ref(false)
const filterType = ref('all')
const search = ref('')
const eventTypes = ['all', 'agent_execute', 'agent_create', 'agent_publish', 'approval_action', 'user_login']

const filteredLogs = computed(() => {
  const keyword = search.value.trim().toLowerCase()
  return logs.value.filter(log => !keyword || [log.action, log.detail, log.username, log.eventType].filter(Boolean).some(value => String(value).toLowerCase().includes(keyword)))
})

const successCount = computed(() => logs.value.filter(log => log.result === 'success').length)
const failedCount = computed(() => logs.value.filter(log => log.result === 'failed').length)

function formatDate(value?: string) {
  if (!value) return { date: '-', time: '-' }
  return { date: value.substring(0, 10), time: value.substring(11, 19) }
}

async function fetchLogs() {
  loading.value = true
  try {
    const params = filterType.value === 'all' ? '' : `?eventType=${encodeURIComponent(filterType.value)}`
    const response = await api.get(`/audit-logs${params}`) as any
    logs.value = response.data?.content || []
  } finally {
    loading.value = false
  }
}

onMounted(fetchLogs)
</script>

<template>
  <div class="console-page audit-page" v-loading="loading">
    <div class="console-page-head">
      <div class="console-page-head__copy"><span>完整记录</span><h1>审计记录</h1><p>按时间回放用户、Agent、工具和审批操作，定位每一次状态变化。</p></div>
      <div class="console-page-actions"><button class="console-icon-button" aria-label="刷新" @click="fetchLogs"><el-icon><Refresh /></el-icon></button></div>
    </div>

    <section class="audit-summary"><div><span>事件</span><strong>{{ logs.length }}</strong><small>当前筛选结果</small></div><div><span>成功</span><strong>{{ successCount }}</strong><small>成功事件</small></div><div><span>失败</span><strong>{{ failedCount }}</strong><small>失败事件</small></div><div class="audit-integrity"><el-icon><Document /></el-icon><span>完整轨迹</span><strong>事件按时间顺序保留</strong><small>用户 · Agent · 工具 · 结果</small></div></section>

    <div class="audit-toolbar"><div class="console-segmented"><button v-for="type in eventTypes" :key="type" :class="{ active: filterType === type }" @click="filterType = type; fetchLogs()">{{ type === 'all' ? '全部事件' : type }}</button></div><label class="console-search"><el-icon><Search /></el-icon><input v-model="search" placeholder="搜索用户、动作或详情" /></label></div>

    <section v-if="filteredLogs.length" class="audit-ledger">
      <article v-for="log in filteredLogs" :key="log.id">
        <time><strong>{{ formatDate(log.createdAt).time }}</strong><span>{{ formatDate(log.createdAt).date }}</span></time>
        <div class="ledger-line"><i :class="log.result" /></div>
        <div class="ledger-entry"><div class="entry-head"><span>{{ log.eventType }}</span><b :class="log.result"><i />{{ log.result || 'recorded' }}</b></div><h2>{{ log.action }}</h2><p>{{ log.detail || '该事件没有附加详情。' }}</p><div class="entry-meta"><span>USER <b>{{ log.username || 'system' }}</b></span><span>AGENT <b>{{ log.agentName || log.agentId || '-' }}</b></span><span>TOOL <b>{{ log.toolName || '-' }}</b></span><span>TRACE <b>#{{ log.id }}</b></span></div></div>
      </article>
    </section>
    <div v-else class="audit-empty"><el-icon><Document /></el-icon><strong>没有匹配的审计事件</strong><span>调整筛选条件，或在控制台执行一次操作。</span></div>
  </div>
</template>

<style scoped>
.audit-summary { display: grid; grid-template-columns: 150px 150px 150px 1fr; border: 1px solid var(--console-line); background: white; }
.audit-summary > div { min-height: 112px; padding: 18px; display: flex; flex-direction: column; border-right: 1px solid var(--console-line); }
.audit-summary > div > span { color: #858b82; font-family: ui-monospace, monospace; font-size: 7px; }
.audit-summary > div > strong { margin-top: auto; font-size: 22px; }
.audit-summary > div > small { margin-top: 4px; color: #8c9289; font-size: 8px; }
.audit-summary .audit-integrity { display: grid; grid-template-columns: 36px 1fr; align-content: center; border-right: 0; }
.audit-integrity .el-icon { grid-row: 1 / 4; color: var(--console-orange); font-size: 22px; }
.audit-integrity > strong { margin-top: 6px !important; font-size: 13px !important; }
.audit-toolbar { min-height: 58px; margin-top: 16px; padding: 8px; display: flex; align-items: center; gap: 10px; border: 1px solid var(--console-line); background: #e4e6e0; }
.audit-toolbar .console-segmented { max-width: calc(100% - 260px); overflow-x: auto; }
.audit-toolbar .console-search { margin-left: auto; }
.audit-ledger { margin-top: 16px; border: 1px solid var(--console-line); background: white; }
.audit-ledger article { min-height: 150px; padding: 22px; display: grid; grid-template-columns: 90px 20px 1fr; gap: 12px; border-bottom: 1px solid var(--console-line); }
.audit-ledger article:last-child { border-bottom: 0; }
.audit-ledger time { display: flex; flex-direction: column; gap: 5px; }
.audit-ledger time strong { font-family: ui-monospace, monospace; font-size: 13px; }
.audit-ledger time span { color: #8d938a; font-size: 8px; }
.ledger-line { position: relative; display: flex; justify-content: center; }
.ledger-line::before { content: ''; position: absolute; top: 0; bottom: -23px; width: 1px; background: var(--console-line); }
.audit-ledger article:last-child .ledger-line::before { bottom: 0; }
.ledger-line > i { position: relative; z-index: 1; width: 9px; height: 9px; margin-top: 3px; background: var(--console-yellow); box-shadow: 0 0 0 5px white; }
.ledger-line > i.success { background: var(--console-green); }
.ledger-line > i.failed { background: var(--console-red); }
.entry-head { display: flex; justify-content: space-between; }
.entry-head > span { color: var(--console-orange); font-family: ui-monospace, monospace; font-size: 7px; }
.entry-head > b { display: flex; align-items: center; gap: 5px; color: #7c8279; font-family: ui-monospace, monospace; font-size: 7px; }
.entry-head > b i { width: 5px; height: 5px; background: var(--console-yellow); }
.entry-head > b.success i { background: var(--console-green); }
.entry-head > b.failed i { background: var(--console-red); }
.ledger-entry h2 { margin-top: 8px; font-size: 13px; }
.ledger-entry > p { margin-top: 7px; color: var(--console-muted); font-size: 9px; line-height: 1.6; }
.entry-meta { margin-top: 17px; display: flex; flex-wrap: wrap; gap: 18px; color: #969c93; font-family: ui-monospace, monospace; font-size: 6px; }
.entry-meta b { display: block; margin-top: 4px; color: var(--console-ink); font-size: 7px; }
.audit-empty { min-height: 430px; margin-top: 16px; display: flex; flex-direction: column; align-items: center; justify-content: center; border: 1px solid var(--console-line); background: white; color: #8d938a; }
.audit-empty .el-icon { font-size: 28px; }
.audit-empty strong { margin-top: 13px; color: var(--console-ink); font-size: 11px; }
.audit-empty span { margin-top: 6px; font-size: 8px; }
@media (max-width: 800px) { .audit-summary { grid-template-columns: 1fr 1fr 1fr; } .audit-integrity { grid-column: 1 / -1; border-top: 1px solid var(--console-line); } .audit-toolbar { align-items: stretch; flex-direction: column; } .audit-toolbar .console-segmented { max-width: 100%; } .audit-toolbar .console-search { width: 100%; margin-left: 0; } }
@media (max-width: 560px) { .audit-ledger article { grid-template-columns: 1fr; } .ledger-line { display: none; } .audit-ledger time { flex-direction: row; } }
</style>
