<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ArrowRight, CircleCheck, Connection, Document, Operation, Plus, Refresh, Tools } from '@element-plus/icons-vue'
import api from '../../api'

const router = useRouter()
const { locale } = useI18n()
const loading = ref(false)
const lastUpdated = ref<Date | null>(null)
const overview = ref<any>({
  agents: { total: 0, published: 0, draft: 0, disabled: 0 },
  governance: { pendingApprovals: 0, auditEvents: 0, users: 0, tools: 0, highRiskTools: 0 },
  usage: { monthlyInputTokens: 0, monthlyOutputTokens: 0, monthlyCost: 0, last7Days: [] },
  runtime: { status: 'UNKNOWN', models: {}, tools: {}, rag: {} },
  recentActivity: [],
})

const tokenSeries = computed(() => {
  const incoming = overview.value.usage?.last7Days || []
  if (incoming.length) return incoming
  return ['D-6', 'D-5', 'D-4', 'D-3', 'D-2', 'D-1', 'TODAY'].map(day => ({ day, tokens: 0, cost: 0 }))
})

const maxTokens = computed(() => Math.max(1, ...tokenSeries.value.map((item: any) => Number(item.tokens || 0))))
const runtimeUp = computed(() => overview.value.runtime?.status === 'UP')

const metricItems = computed(() => [
  { key: 'agents', label: 'Agent 总数', value: overview.value.agents?.total || 0, note: `${overview.value.agents?.published || 0} 个已发布`, icon: Operation },
  { key: 'approvals', label: '待审批', value: overview.value.governance?.pendingApprovals || 0, note: '需要人工处理', icon: CircleCheck },
  { key: 'tools', label: '工具资产', value: overview.value.governance?.tools || 0, note: `${overview.value.governance?.highRiskTools || 0} 个高风险`, icon: Tools },
  { key: 'cost', label: '本月成本', value: formatCurrency(overview.value.usage?.monthlyCost), note: `${formatNumber(totalTokens.value)} tokens`, icon: Document },
])

const totalTokens = computed(() => Number(overview.value.usage?.monthlyInputTokens || 0) + Number(overview.value.usage?.monthlyOutputTokens || 0))

const serviceNodes = computed(() => [
  { code: 'WEB', name: 'Vue Console', status: 'UP', detail: '当前界面' },
  { code: 'JAVA', name: 'Control Plane', status: 'UP', detail: 'REST :8080 · gRPC :9090' },
  { code: 'PY', name: 'Agent Runtime', status: overview.value.runtime?.status || 'DOWN', detail: 'FastAPI :8000 · gRPC :9091' },
  { code: 'DATA', name: 'Data Layer', status: 'UP', detail: 'PostgreSQL · Redis' },
])

function formatNumber(value: unknown) {
  return new Intl.NumberFormat(locale.value, { notation: 'compact', maximumFractionDigits: 1 }).format(Number(value || 0))
}

function formatCurrency(value: unknown) {
  return `¥${Number(value || 0).toFixed(2)}`
}

function formatTime(value?: string) {
  if (!value) return '-'
  return value.substring(0, 16).replace('T', ' ')
}

async function fetchOverview() {
  loading.value = true
  try {
    const response = await api.get('/platform/overview') as any
    overview.value = response.data || overview.value
    lastUpdated.value = new Date()
  } finally {
    loading.value = false
  }
}

onMounted(fetchOverview)
</script>

<template>
  <div class="console-page operations-page" v-loading="loading">
    <div class="console-page-head">
      <div class="console-page-head__copy"><span>今天</span><h1>工作台</h1><p>查看正在运行的 Agent、需要处理的事项，以及本月用量和服务状态。</p></div>
      <div class="console-page-actions"><span v-if="lastUpdated" class="updated-at">更新于 {{ lastUpdated.toLocaleTimeString('zh-CN', { hour12: false }) }}</span><button class="console-icon-button" aria-label="刷新" @click="fetchOverview"><el-icon><Refresh /></el-icon></button><button class="console-primary" @click="router.push('/console/agents')"><el-icon><Plus /></el-icon> 新建 Agent</button></div>
    </div>

    <section class="status-banner">
      <div class="status-summary"><span><i :class="{ up: runtimeUp }" /> PLATFORM STATUS</span><h2>{{ runtimeUp ? '所有核心服务运行正常' : 'Python 运行时当前不可达' }}</h2><p>{{ runtimeUp ? '控制面、执行面与数据层已连接，可以执行 Agent 工作流。' : '管理功能仍可使用，但对话、RAG 和工具执行会受到影响。' }}</p></div>
      <div class="status-actions"><button @click="router.push('/console/approvals')"><span>需要处理</span><strong>{{ overview.governance?.pendingApprovals || 0 }} 个审批待办</strong><el-icon><ArrowRight /></el-icon></button><button @click="router.push('/console/audit')"><span>最近记录</span><strong>{{ overview.governance?.auditEvents || 0 }} 条审计事件</strong><el-icon><ArrowRight /></el-icon></button></div>
    </section>

    <section class="metric-grid">
      <article v-for="metric in metricItems" :key="metric.key"><div class="metric-top"><span>{{ metric.label }}</span><el-icon><component :is="metric.icon" /></el-icon></div><strong>{{ metric.value }}</strong><small>{{ metric.note }}</small></article>
    </section>

    <div class="operations-grid">
      <section class="usage-panel">
        <div class="panel-head"><div><span>最近 7 天</span><h2>Token 使用趋势</h2></div><strong>{{ formatNumber(totalTokens) }}<small>本月 Token</small></strong></div>
        <div class="token-chart">
          <div v-for="item in tokenSeries" :key="item.day" class="chart-column"><span>{{ formatNumber(item.tokens) }}</span><div><i :style="{ height: `${Math.max(4, Number(item.tokens || 0) / maxTokens * 100)}%` }" /></div><small>{{ item.day }}</small></div>
        </div>
        <div class="usage-foot"><span>输入 {{ formatNumber(overview.usage?.monthlyInputTokens) }}</span><span>输出 {{ formatNumber(overview.usage?.monthlyOutputTokens) }}</span><span>成本 {{ formatCurrency(overview.usage?.monthlyCost) }}</span></div>
      </section>

      <section class="runtime-panel">
        <div class="panel-head"><div><span>运行能力</span><h2>执行引擎</h2></div><b :class="['runtime-badge', { up: runtimeUp }]">{{ overview.runtime?.status || 'DOWN' }}</b></div>
        <div class="runtime-metrics"><div><strong>{{ overview.runtime?.models?.configured_provider_count || 0 }} / {{ overview.runtime?.models?.provider_count || 0 }}</strong><span>模型供应商已配置</span></div><div><strong>{{ overview.runtime?.tools?.count || 0 }}</strong><span>运行时工具</span></div><div><strong>{{ overview.runtime?.rag?.total_chunks || 0 }}</strong><span>RAG 索引分块</span></div><div><strong>{{ formatNumber(overview.runtime?.uptime_seconds || 0) }}s</strong><span>持续运行</span></div></div>
        <div class="risk-distribution"><span>工具风险分布</span><div><b class="low" :style="{ flex: overview.runtime?.tools?.risk_distribution?.low || 0 }" /><b class="medium" :style="{ flex: overview.runtime?.tools?.risk_distribution?.medium || 0 }" /><b class="high" :style="{ flex: overview.runtime?.tools?.risk_distribution?.high || 0 }" /></div><small>低风险 / 中风险 / 高风险</small></div>
      </section>

      <section class="service-panel">
        <div class="panel-head"><div><span>服务关系</span><h2>三端连接状态</h2></div><el-icon><Connection /></el-icon></div>
        <div class="service-map"><div v-for="(node, index) in serviceNodes" :key="node.code" class="service-node"><span>{{ node.code }}</span><div><strong>{{ node.name }}</strong><small>{{ node.detail }}</small></div><b :class="node.status.toLowerCase()"><i />{{ node.status }}</b><em v-if="index < serviceNodes.length - 1">↓</em></div></div>
      </section>

      <section class="activity-panel">
        <div class="panel-head"><div><span>审计记录</span><h2>最近活动</h2></div><button @click="router.push('/console/audit')">全部记录 <el-icon><ArrowRight /></el-icon></button></div>
        <div v-if="overview.recentActivity?.length" class="activity-list"><article v-for="activity in overview.recentActivity" :key="activity.id"><span class="activity-result" :class="activity.result?.toLowerCase()" /><div><strong>{{ activity.action }}</strong><small>{{ activity.username || 'system' }} · {{ activity.eventType }}</small></div><time>{{ formatTime(activity.createdAt) }}</time></article></div>
        <div v-else class="activity-empty"><el-icon><Document /></el-icon><strong>暂无审计活动</strong><span>Agent、用户和审批操作会出现在这里。</span></div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.updated-at { color: var(--console-muted); font-size: 8px; }
.status-banner { min-height: 180px; padding: 30px; display: grid; grid-template-columns: 1fr .75fr; gap: 50px; background: #171916; color: white; }
.status-summary > span { display: flex; align-items: center; gap: 8px; color: #747b71; font-family: ui-monospace, monospace; font-size: 8px; }
.status-summary > span i { width: 7px; height: 7px; background: var(--console-red); }
.status-summary > span i.up { background: var(--console-green); box-shadow: 0 0 0 4px rgba(34,184,102,.12); }
.status-summary h2 { margin-top: 24px; font-size: 25px; }
.status-summary p { margin-top: 9px; color: #8f968c; font-size: 11px; line-height: 1.6; }
.status-actions { display: grid; grid-template-columns: 1fr 1fr; border-top: 1px solid #3b4039; border-left: 1px solid #3b4039; }
.status-actions button { position: relative; padding: 18px; display: flex; flex-direction: column; align-items: flex-start; border: 0; border-right: 1px solid #3b4039; border-bottom: 1px solid #3b4039; background: #1d201c; color: white; font: inherit; text-align: left; cursor: pointer; }
.status-actions button:hover { background: #252923; }
.status-actions span { color: var(--console-orange); font-family: ui-monospace, monospace; font-size: 7px; }
.status-actions strong { margin-top: auto; font-size: 11px; }
.status-actions .el-icon { position: absolute; right: 16px; bottom: 17px; color: #777e74; }
.metric-grid { display: grid; grid-template-columns: repeat(4, 1fr); border-left: 1px solid var(--console-line); }
.metric-grid article { min-height: 142px; padding: 20px; display: flex; flex-direction: column; border-right: 1px solid var(--console-line); border-bottom: 1px solid var(--console-line); background: white; }
.metric-top { display: flex; justify-content: space-between; color: #7b8178; font-size: 9px; }
.metric-top .el-icon { color: var(--console-orange); font-size: 17px; }
.metric-grid article > strong { margin-top: auto; font-size: 27px; }
.metric-grid article > small { margin-top: 5px; color: #8d938a; font-size: 8px; }
.operations-grid { margin-top: 22px; display: grid; grid-template-columns: 1.2fr .8fr; gap: 16px; }
.usage-panel, .runtime-panel, .service-panel, .activity-panel { padding: 22px; border: 1px solid var(--console-line); background: white; }
.panel-head { min-height: 42px; display: flex; align-items: flex-start; justify-content: space-between; }
.panel-head span { color: var(--console-orange); font-family: ui-monospace, monospace; font-size: 7px; font-weight: 800; }
.panel-head h2 { margin-top: 6px; font-size: 15px; }
.panel-head > strong { font-size: 20px; text-align: right; }
.panel-head > strong small { display: block; margin-top: 4px; color: var(--console-muted); font-size: 7px; font-weight: 500; }
.token-chart { height: 210px; margin-top: 18px; padding-top: 20px; display: grid; grid-template-columns: repeat(7, 1fr); gap: 8px; border-top: 1px solid var(--console-line); }
.chart-column { min-width: 0; display: grid; grid-template-rows: 18px 1fr 18px; justify-items: center; }
.chart-column > span, .chart-column small { color: #8c9289; font-family: ui-monospace, monospace; font-size: 7px; }
.chart-column > div { width: 100%; height: 145px; display: flex; align-items: flex-end; background: #eff0ec; }
.chart-column i { width: 100%; display: block; background: var(--console-orange); transition: height 240ms ease; }
.usage-foot { padding-top: 12px; display: flex; justify-content: space-between; border-top: 1px solid var(--console-line); color: #747a71; font-size: 8px; }
.runtime-badge { padding: 5px 8px; border: 1px solid #e3b4ad; color: var(--console-red); font-family: ui-monospace, monospace; font-size: 8px; }
.runtime-badge.up { border-color: #9ed9b7; color: var(--console-green); }
.runtime-metrics { margin-top: 18px; display: grid; grid-template-columns: 1fr 1fr; border-top: 1px solid var(--console-line); border-left: 1px solid var(--console-line); }
.runtime-metrics div { min-height: 92px; padding: 14px; display: flex; flex-direction: column; justify-content: flex-end; border-right: 1px solid var(--console-line); border-bottom: 1px solid var(--console-line); background: #f6f7f3; }
.runtime-metrics strong { font-size: 17px; }
.runtime-metrics span { margin-top: 5px; color: var(--console-muted); font-size: 8px; }
.risk-distribution { margin-top: 18px; }
.risk-distribution > span { color: var(--console-muted); font-size: 8px; }
.risk-distribution > div { height: 8px; margin-top: 8px; display: flex; background: #e5e7e1; }
.risk-distribution b.low { background: var(--console-green); }
.risk-distribution b.medium { background: var(--console-yellow); }
.risk-distribution b.high { background: var(--console-red); }
.risk-distribution small { display: block; margin-top: 7px; color: #969c93; font-size: 7px; }
.service-panel .panel-head > .el-icon { color: var(--console-orange); font-size: 20px; }
.service-map { margin-top: 18px; }
.service-node { position: relative; min-height: 64px; padding: 12px; display: grid; grid-template-columns: 46px 1fr auto; align-items: center; border: 1px solid var(--console-line); background: #f7f8f4; }
.service-node + .service-node { margin-top: 10px; }
.service-node > span { color: var(--console-orange); font-family: ui-monospace, monospace; font-size: 8px; }
.service-node div { display: flex; flex-direction: column; gap: 4px; }
.service-node strong { font-size: 10px; }
.service-node small { color: #8b9188; font-size: 7px; }
.service-node > b { display: flex; align-items: center; gap: 5px; color: #838980; font-family: ui-monospace, monospace; font-size: 7px; }
.service-node > b i { width: 5px; height: 5px; background: #999; }
.service-node > b.up i { background: var(--console-green); }
.service-node > b.down i { background: var(--console-red); }
.service-node em { position: absolute; left: 31px; bottom: -12px; z-index: 1; color: #8b9188; font-style: normal; font-size: 8px; }
.activity-panel .panel-head button { padding: 0; display: inline-flex; align-items: center; gap: 5px; border: 0; background: transparent; color: var(--console-ink); font: inherit; font-size: 8px; font-weight: 800; cursor: pointer; }
.activity-list { margin-top: 18px; border-top: 1px solid var(--console-line); }
.activity-list article { min-height: 58px; display: grid; grid-template-columns: 8px 1fr auto; align-items: center; gap: 10px; border-bottom: 1px solid var(--console-line); }
.activity-result { width: 6px; height: 6px; background: var(--console-yellow); }
.activity-result.success { background: var(--console-green); }
.activity-result.failed { background: var(--console-red); }
.activity-list article div { display: flex; flex-direction: column; gap: 4px; }
.activity-list strong { font-size: 9px; }
.activity-list small, .activity-list time { color: #8c9289; font-size: 7px; }
.activity-empty { min-height: 270px; display: flex; flex-direction: column; align-items: center; justify-content: center; color: #91978e; }
.activity-empty .el-icon { font-size: 26px; }
.activity-empty strong { margin-top: 12px; color: var(--console-ink); font-size: 11px; }
.activity-empty span { margin-top: 6px; font-size: 8px; }
@media (max-width: 1100px) { .status-banner, .operations-grid { grid-template-columns: 1fr; } }
@media (max-width: 760px) {
  .status-banner { padding: 22px; }
  .status-actions, .metric-grid { grid-template-columns: 1fr 1fr; }
  .metric-grid article { min-height: 120px; }
  .token-chart { gap: 4px; }
  .chart-column > span { display: none; }
  .usage-foot { gap: 8px; flex-wrap: wrap; }
}
</style>
