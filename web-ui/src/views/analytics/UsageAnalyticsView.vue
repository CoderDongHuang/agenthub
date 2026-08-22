<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Download, Refresh, TrendCharts, Warning } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import api from '../../api'

const loading = ref(false)
const budget = ref(1000)
const alertThreshold = ref(.8)
const usage = ref<any>({ monthly_input: 0, monthly_output: 0, monthly_cost: 0, calls: 0, byAgent: [], byModel: [], last7Days: [] })
const totalTokens = computed(() => Number(usage.value.monthly_input || 0) + Number(usage.value.monthly_output || 0))
const budgetRate = computed(() => Math.min(100, Number(usage.value.monthly_cost || 0) / Math.max(1, budget.value) * 100))
const series = computed(() => usage.value.last7Days || [])
const maxTokens = computed(() => Math.max(...series.value.map((item: any) => Number(item.tokens || 0)), 1))
const modelRows = computed(() => usage.value.byModel || [])
const agentRows = computed(() => usage.value.byAgent || [])
const averageCost = computed(() => Number(usage.value.calls || 0) ? Number(usage.value.monthly_cost || 0) / Number(usage.value.calls) : 0)
const highestDay = computed(() => [...series.value].sort((a: any, b: any) => Number(b.tokens) - Number(a.tokens))[0])
function format(value: number) { return new Intl.NumberFormat('zh-CN', { notation: 'compact', maximumFractionDigits: 1 }).format(value || 0) }
async function load() {
  loading.value = true
  try {
    const [usageResponse, budgetResponse] = await Promise.all([api.get('/billing/usage') as any, api.get('/billing/budget') as any])
    usage.value = usageResponse.data || usage.value
    budget.value = Number(budgetResponse.data?.total_budget || 1000)
    alertThreshold.value = Number(budgetResponse.data?.alert_threshold || .8)
  } finally { loading.value = false }
}
async function saveBudget() {
  const response = await api.put('/billing/budget', { totalBudget: budget.value, alertThreshold: alertThreshold.value }) as any
  if (response.code !== 200) return ElMessage.error(response.message || '预算保存失败')
  ElMessage.success('本月预算已保存')
}
function exportReport() { const payload = JSON.stringify({ generatedAt: new Date().toISOString(), budget: budget.value, alertThreshold: alertThreshold.value, usage: usage.value }, null, 2); const link = document.createElement('a'); link.href = URL.createObjectURL(new Blob([payload], { type: 'application/json' })); link.download = 'agenthub-usage-report.json'; link.click(); URL.revokeObjectURL(link.href); ElMessage.success('用量报告已导出') }
onMounted(load)
</script>

<template>
  <div class="console-page analytics-page" v-loading="loading">
    <div class="console-page-head"><div class="console-page-head__copy"><span>成本与效率</span><h1>用量分析</h1><p>从组织、Agent 和模型三个维度查看 Token、成本与调用变化，提前发现异常消耗。</p></div><div class="console-page-actions"><button class="console-icon-button" aria-label="刷新" @click="load"><el-icon><Refresh /></el-icon></button><button class="console-primary" @click="exportReport"><el-icon><Download /></el-icon> 导出报告</button></div></div>
    <section class="analytics-overview"><div class="budget-card"><div><span>本月预算使用</span><strong>¥{{ Number(usage.monthly_cost || 0).toFixed(2) }} <small>/ ¥{{ budget.toFixed(0) }}</small></strong></div><div class="budget-track"><i :style="{ width: `${budgetRate}%` }" /></div><label><span>预算上限</span><input v-model.number="budget" type="range" min="100" max="5000" step="100" @change="saveBudget" /></label></div><div><span>总 Token</span><strong>{{ format(totalTokens) }}</strong><small>输入 {{ format(usage.monthly_input) }} · 输出 {{ format(usage.monthly_output) }}</small></div><div><span>本月调用</span><strong>{{ format(usage.calls) }}</strong><small>{{ agentRows.length }} 个 Agent 产生用量</small></div><div><span>单次均价</span><strong>¥{{ averageCost.toFixed(4) }}</strong><small>按真实调用记录计算</small></div></section>
    <div class="analytics-grid">
      <section class="trend-panel"><div class="panel-heading"><div><span>最近 7 天</span><h2>Token 消耗趋势</h2></div><b><el-icon><TrendCharts /></el-icon> 日均 {{ format(totalTokens / 30) }}</b></div><div class="usage-chart"><div v-for="item in series" :key="item.day" class="chart-day"><span>{{ format(item.tokens) }}</span><div><i :style="{ height: `${Math.max(8, item.tokens / maxTokens * 100)}%` }" /></div><small>{{ item.day }}</small></div></div><div class="chart-legend"><span><i /> Token 用量</span><span>每日零点汇总 · 数据来自 Java 管理面</span></div></section>
      <aside class="saving-panel"><span>数据口径</span><h2>只展示运行链路实际写入的 Token 和成本</h2><div class="saving-item"><b>01</b><p><strong>调用记录</strong><span>Agent 完成对话后写入 token_usage 表</span></p></div><div class="saving-item"><b>02</b><p><strong>预算预警</strong><span>当前阈值为 {{ Math.round(alertThreshold * 100) }}%，预算可在上方直接调整</span></p></div></aside>
      <section class="model-cost-panel"><div class="panel-heading"><div><span>模型分布</span><h2>供应商成本明细</h2></div><small>按实际费用从高到低</small></div><div class="cost-table"><div class="cost-head"><span>模型</span><span>调用次数</span><span>Token</span><span>实际成本</span><span>单次均价</span></div><div v-for="model in modelRows" :key="model.model" class="cost-row"><strong>{{ model.model }}</strong><span>{{ Number(model.calls).toLocaleString() }}</span><span>{{ format(model.total_tokens) }}</span><span>¥{{ Number(model.total_cost).toFixed(4) }}</span><b>¥{{ (Number(model.total_cost) / Math.max(1, Number(model.calls))).toFixed(4) }}</b></div><div v-if="!modelRows.length" class="cost-empty">暂无模型调用记录</div></div></section>
      <aside class="anomaly-panel"><div class="panel-heading"><div><span>用量观察</span><h2>{{ budgetRate >= alertThreshold * 100 ? '预算需要关注' : '当前状态正常' }}</h2></div><el-icon><Warning /></el-icon></div><article v-if="budgetRate >= alertThreshold * 100"><span>预算预警</span><strong>已使用 {{ budgetRate.toFixed(1) }}%</strong><p>当前用量已达到设定的预算预警阈值。</p></article><article v-if="highestDay" class="quiet"><span>近七日峰值</span><strong>{{ highestDay.day }} · {{ format(highestDay.tokens) }} Token</strong><p>数据来自实际调用记录，没有调用时显示为 0。</p></article></aside>
    </div>
  </div>
</template>

<style scoped>
.analytics-overview { display: grid; grid-template-columns: 1.5fr repeat(3, .65fr); border: 1px solid var(--console-line); border-radius: 8px; background: white; overflow: hidden; }
.analytics-overview > div { min-height: 132px; padding: 20px; display: flex; flex-direction: column; border-right: 1px solid var(--console-line); }.analytics-overview > div:last-child { border-right: 0; }
.analytics-overview span { color: var(--console-muted); font-size: 12px; }.analytics-overview strong { margin-top: auto; font-size: 23px; }.analytics-overview strong small { color: #8b958f; font-size: 12px; font-weight: 500; }.analytics-overview > div > small { margin-top: 6px; color: #89938d; font-size: 12px; }
.budget-card { background: var(--console-primary-soft); }.budget-track { height: 9px; margin-top: 18px; border-radius: 5px; background: rgba(82,115,99,.14); overflow: hidden; }.budget-track i { height: 100%; display: block; border-radius: 5px; background: var(--console-primary); }
.budget-card label { margin-top: 12px; display: grid; grid-template-columns: 65px 1fr; align-items: center; }.budget-card input { width: 100%; accent-color: var(--console-primary); }
.analytics-grid { margin-top: 16px; display: grid; grid-template-columns: 1.4fr .6fr; gap: 14px; }.trend-panel, .saving-panel, .model-cost-panel, .anomaly-panel { padding: 20px; border: 1px solid var(--console-line); border-radius: 8px; background: white; }
.panel-heading { display: flex; align-items: start; justify-content: space-between; }.panel-heading span { color: var(--console-primary); font-size: 12px; font-weight: 700; }.panel-heading h2 { margin-top: 5px; font-size: 15px; }.panel-heading > b { display: flex; align-items: center; gap: 6px; color: var(--console-primary-dark); font-size: 12px; }.panel-heading > small { color: var(--console-muted); font-size: 12px; }
.usage-chart { height: 240px; margin-top: 20px; padding-top: 16px; display: grid; grid-template-columns: repeat(7, 1fr); gap: 10px; border-top: 1px solid var(--console-line); }.chart-day { display: grid; grid-template-rows: 20px 1fr 20px; justify-items: center; }.chart-day > span, .chart-day small { color: #87918b; font-size: 12px; }.chart-day > div { width: 100%; height: 170px; display: flex; align-items: flex-end; border-radius: 5px 5px 0 0; background: #f0f4f0; overflow: hidden; }.chart-day i { width: 100%; display: block; border-radius: 5px 5px 0 0; background: var(--console-primary); }.chart-legend { padding-top: 13px; display: flex; justify-content: space-between; border-top: 1px solid var(--console-line); color: #8b958f; font-size: 12px; }.chart-legend span:first-child { display: flex; align-items: center; gap: 6px; }.chart-legend i { width: 8px; height: 8px; border-radius: 2px; background: var(--console-primary); }
.saving-panel { background: var(--console-blue-soft); border-color: #d7e2e8; }.saving-panel > span { color: #607d91; font-size: 12px; font-weight: 700; }.saving-panel h2 { margin-top: 10px; font-size: 18px; line-height: 1.4; }.saving-item { margin-top: 22px; display: grid; grid-template-columns: 26px 1fr; gap: 9px; }.saving-item > b { width: 26px; height: 26px; display: grid; place-items: center; border-radius: 6px; background: white; color: #607d91; font-size: 12px; }.saving-item p { display: flex; flex-direction: column; gap: 5px; }.saving-item strong { font-size: 12px; }.saving-item span { color: #657e8f; font-size: 12px; line-height: 1.4; }.saving-panel > button { width: 100%; min-height: 40px; margin-top: 25px; border: 0; border-radius: 7px; background: #5e7b90; color: white; font: inherit; font-size: 12px; font-weight: 700; cursor: pointer; }
.model-cost-panel { min-width: 0; }.cost-table { margin-top: 18px; border: 1px solid var(--console-line); border-radius: 7px; overflow: hidden; }.cost-head, .cost-row { min-height: 48px; padding: 0 14px; display: grid; grid-template-columns: 1.4fr repeat(4, .65fr); gap: 8px; align-items: center; }.cost-head { min-height: 40px; background: #f5f7f4; color: var(--console-muted); font-size: 12px; }.cost-row { border-top: 1px solid var(--console-line); }.cost-row strong { font-size: 12px; }.cost-row span { color: #6f7c75; font-size: 12px; }.cost-row b { color: var(--console-coral); font-size: 12px; }.cost-row b.down { color: var(--console-green); }
.cost-empty { padding: 30px 14px; color: var(--console-muted); font-size: 12px; text-align: center; }
.anomaly-panel .panel-heading > .el-icon { color: var(--console-yellow); font-size: 20px; }.anomaly-panel article { margin-top: 18px; padding: 14px; border-radius: 7px; background: #f8efe2; color: #755f3c; }.anomaly-panel article > span { font-size: 12px; font-weight: 700; }.anomaly-panel article strong { display: block; margin-top: 7px; font-size: 12px; }.anomaly-panel article p { margin-top: 6px; font-size: 12px; line-height: 1.55; }.anomaly-panel article small { display: block; margin-top: 12px; color: #9b845d; font-size: 12px; }.anomaly-panel article.quiet { background: var(--console-primary-soft); color: var(--console-primary-dark); }
@media (max-width: 1000px) { .analytics-overview { grid-template-columns: 1fr 1fr 1fr; }.budget-card { grid-column: 1 / -1; }.analytics-grid { grid-template-columns: 1fr; } }
@media (max-width: 640px) { .analytics-overview { grid-template-columns: 1fr 1fr; }.budget-card { grid-column: 1 / -1; }.usage-chart { gap: 4px; }.chart-day > span { display: none; }.cost-table { overflow-x: auto; }.cost-head, .cost-row { min-width: 650px; } }
</style>
