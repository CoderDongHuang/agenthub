<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Connection, Refresh, Search, Tools } from '@element-plus/icons-vue'
import api from '../../api'

const tools = ref<any[]>([])
const loading = ref(false)
const category = ref('all')
const risk = ref('all')
const search = ref('')

const categories = computed(() => ['all', ...Array.from(new Set(tools.value.map(tool => tool.category || 'General')))])
const filteredTools = computed(() => tools.value.filter(tool => {
  const matchesCategory = category.value === 'all' || (tool.category || 'General') === category.value
  const matchesRisk = risk.value === 'all' || tool.risk_level === risk.value
  const keyword = search.value.trim().toLowerCase()
  const matchesSearch = !keyword || [tool.tool_name, tool.tool_code, tool.description].filter(Boolean).some(value => String(value).toLowerCase().includes(keyword))
  return matchesCategory && matchesRisk && matchesSearch
}))

const riskCounts = computed(() => ({
  low: tools.value.filter(tool => tool.risk_level === 'low').length,
  medium: tools.value.filter(tool => tool.risk_level === 'medium').length,
  high: tools.value.filter(tool => tool.risk_level === 'high').length,
}))

function formatSchema(schema: any) {
  try {
    const parsed = typeof schema === 'string' ? JSON.parse(schema) : schema
    return Object.entries(parsed?.properties || {}).map(([name, value]: [string, any]) => `${name}: ${value.type || 'string'}`)
  } catch {
    return []
  }
}

async function fetchTools() {
  loading.value = true
  try {
    const response = await api.get('/tools?size=100') as any
    tools.value = response.data?.content || []
  } finally {
    loading.value = false
  }
}

onMounted(fetchTools)
</script>

<template>
  <div class="console-page tools-page" v-loading="loading">
    <div class="console-page-head">
      <div class="console-page-head__copy"><span>可复用能力</span><h1>工具与插件</h1><p>查看运行时已注册的业务能力、输入参数、限流和审批风险。</p></div>
      <div class="console-page-actions"><button class="console-icon-button" aria-label="刷新" @click="fetchTools"><el-icon><Refresh /></el-icon></button></div>
    </div>

    <section class="tool-risk-board">
      <div class="risk-intro"><el-icon><Connection /></el-icon><span>RISK POLICY</span><strong>工具风险决定执行路径</strong><p>低风险自动执行，中风险单人审批，高风险需要双人审批。</p></div>
      <button :class="{ active: risk === 'low' }" @click="risk = risk === 'low' ? 'all' : 'low'"><span>LOW</span><strong>{{ riskCounts.low }}</strong><small>自动执行</small></button>
      <button :class="{ active: risk === 'medium' }" @click="risk = risk === 'medium' ? 'all' : 'medium'"><span>MEDIUM</span><strong>{{ riskCounts.medium }}</strong><small>单人审批</small></button>
      <button :class="{ active: risk === 'high' }" @click="risk = risk === 'high' ? 'all' : 'high'"><span>HIGH</span><strong>{{ riskCounts.high }}</strong><small>双人审批</small></button>
    </section>

    <div class="tool-toolbar">
      <div class="category-tabs"><button v-for="item in categories" :key="item" :class="{ active: category === item }" @click="category = item">{{ item === 'all' ? '全部分类' : item }}</button></div>
      <label class="console-search"><el-icon><Search /></el-icon><input v-model="search" placeholder="搜索工具名称或代码" /></label>
      <span>{{ filteredTools.length }} TOOLS</span>
    </div>

    <section v-if="filteredTools.length" class="tool-catalog">
      <article v-for="tool in filteredTools" :key="tool.id" class="tool-item">
        <div class="tool-item__head"><span class="tool-code-mark">{{ String(tool.tool_code || 'TL').slice(0, 2).toUpperCase() }}</span><div><small>{{ tool.category || 'General' }}</small><h2>{{ tool.tool_name }}</h2><code>{{ tool.tool_code }}</code></div><b :class="tool.risk_level"><i />{{ tool.risk_level?.toUpperCase() }}</b></div>
        <p>{{ tool.description || '暂无工具说明。' }}</p>
        <div class="tool-schema"><span>INPUT SCHEMA</span><div v-if="formatSchema(tool.json_schema).length"><code v-for="param in formatSchema(tool.json_schema)" :key="param">{{ param }}</code></div><small v-else>无输入参数</small></div>
        <div class="tool-item__foot"><span>RATE <b>{{ tool.rate_limit || '∞' }}/day</b></span><span>TIMEOUT <b>{{ tool.timeout_seconds || 30 }}s</b></span><span>STATUS <b>{{ tool.status || 'active' }}</b></span></div>
      </article>
    </section>
    <div v-else class="tool-empty"><el-icon><Tools /></el-icon><strong>没有匹配的工具</strong><p>Python 运行时启动后会自动发现并同步内置与自定义工具。</p></div>
  </div>
</template>

<style scoped>
.tool-risk-board { min-height: 150px; display: grid; grid-template-columns: 1.4fr repeat(3, .6fr); border: 1px solid var(--console-line); background: white; }
.risk-intro { padding: 24px; display: grid; grid-template-columns: 28px 1fr; align-content: center; border-right: 1px solid var(--console-line); }
.risk-intro .el-icon { grid-row: 1 / 4; color: var(--console-orange); font-size: 22px; }
.risk-intro span { color: var(--console-orange); font-family: ui-monospace, monospace; font-size: 7px; }
.risk-intro strong { margin-top: 7px; font-size: 14px; }
.risk-intro p { margin-top: 7px; color: var(--console-muted); font-size: 8px; }
.tool-risk-board > button { padding: 20px; display: flex; flex-direction: column; align-items: flex-start; border: 0; border-right: 1px solid var(--console-line); background: #f6f7f3; color: var(--console-ink); font: inherit; cursor: pointer; }
.tool-risk-board > button:last-child { border-right: 0; }
.tool-risk-board > button:hover, .tool-risk-board > button.active { background: var(--console-ink); color: white; }
.tool-risk-board > button span { color: #8a9087; font-family: ui-monospace, monospace; font-size: 8px; }
.tool-risk-board > button.active span { color: var(--console-orange); }
.tool-risk-board > button strong { margin-top: auto; font-size: 25px; }
.tool-risk-board > button small { margin-top: 5px; color: #8b9188; font-size: 8px; }
.tool-toolbar { min-height: 58px; margin-top: 16px; padding: 8px; display: flex; align-items: center; gap: 10px; border: 1px solid var(--console-line); background: #e4e6e0; }
.category-tabs { display: flex; gap: 3px; overflow-x: auto; }
.category-tabs button { min-height: 34px; padding: 0 12px; border: 0; background: transparent; color: #747a71; font: inherit; font-size: 9px; white-space: nowrap; cursor: pointer; }
.category-tabs button.active { background: var(--console-ink); color: white; }
.tool-toolbar .console-search { margin-left: auto; }
.tool-toolbar > span { color: #858b82; font-family: ui-monospace, monospace; font-size: 7px; }
.tool-catalog { display: grid; grid-template-columns: repeat(3, 1fr); border-left: 1px solid var(--console-line); }
.tool-item { min-height: 350px; padding: 22px; display: flex; flex-direction: column; border-right: 1px solid var(--console-line); border-bottom: 1px solid var(--console-line); background: white; }
.tool-item__head { display: grid; grid-template-columns: 42px 1fr auto; gap: 12px; align-items: start; }
.tool-code-mark { width: 38px; height: 38px; display: grid; place-items: center; background: var(--console-ink); color: white; font-family: ui-monospace, monospace; font-size: 9px; }
.tool-item__head small { color: var(--console-orange); font-size: 7px; }
.tool-item__head h2 { margin-top: 4px; font-size: 13px; }
.tool-item__head code { display: block; margin-top: 4px; color: #959b92; font-size: 7px; }
.tool-item__head > b { padding: 4px 6px; display: flex; align-items: center; gap: 5px; border: 1px solid var(--console-line); color: #777d74; font-family: ui-monospace, monospace; font-size: 7px; }
.tool-item__head > b i { width: 5px; height: 5px; background: var(--console-green); }
.tool-item__head > b.medium i { background: var(--console-yellow); }
.tool-item__head > b.high i { background: var(--console-red); }
.tool-item > p { min-height: 54px; margin-top: 22px; color: var(--console-muted); font-size: 10px; line-height: 1.7; }
.tool-schema { margin-top: 18px; padding: 13px; background: #f0f1ed; }
.tool-schema > span { color: #858b82; font-family: ui-monospace, monospace; font-size: 7px; }
.tool-schema > div { margin-top: 9px; display: flex; flex-wrap: wrap; gap: 5px; }
.tool-schema code { padding: 5px 6px; border: 1px solid var(--console-line); background: white; color: #555b52; font-size: 7px; }
.tool-schema small { display: block; margin-top: 9px; color: #91978e; font-size: 8px; }
.tool-item__foot { margin-top: auto; padding-top: 14px; display: flex; justify-content: space-between; border-top: 1px solid var(--console-line); color: #969c93; font-family: ui-monospace, monospace; font-size: 6px; }
.tool-item__foot b { display: block; margin-top: 4px; color: var(--console-ink); font-size: 7px; }
.tool-empty { min-height: 420px; display: flex; flex-direction: column; align-items: center; justify-content: center; border: 1px solid var(--console-line); background: white; text-align: center; }
.tool-empty .el-icon { font-size: 30px; color: #989e95; }
.tool-empty strong { margin-top: 14px; font-size: 13px; }
.tool-empty p { max-width: 360px; margin-top: 8px; color: var(--console-muted); font-size: 9px; line-height: 1.6; }
@media (max-width: 1050px) { .tool-catalog { grid-template-columns: 1fr 1fr; } }
@media (max-width: 720px) { .tool-risk-board { grid-template-columns: 1fr 1fr 1fr; } .risk-intro { grid-column: 1 / -1; border-right: 0; border-bottom: 1px solid var(--console-line); } .tool-toolbar { align-items: stretch; flex-direction: column; } .tool-toolbar .console-search { width: 100%; margin-left: 0; } .tool-catalog { grid-template-columns: 1fr; } }
</style>
