<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  CircleCheck, Connection, DataAnalysis, Document, Files, Plus,
  Refresh, RefreshLeft, Search, Setting, Switch, Timer, VideoPlay, Warning,
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../../api'
import StructuredResult from '../../components/StructuredResult.vue'

const { locale } = useI18n()
const en = computed(() => locale.value === 'en-US')
const tx = (zh: string, english: string) => en.value ? english : zh
const diagnosticsActionLabel = computed(() => en.value ? 'Run diagnostics' : '运行诊断')

const loading = ref(false)
const activeTab = ref('versions')
const agents = ref<any[]>([])
const selectedAgentId = ref<number | null>(null)
const versions = ref<any[]>([])
const datasets = ref<any[]>([])
const runs = ref<any[]>([])
const traces = ref<any[]>([])
const traceSummary = ref<any>({ totals: {} })
const observability = ref<any>({ totals: {}, attribution: [], spanAttribution: [], anomalies: [] })
const selectedTrace = ref<any>(null)
const replayResult = ref<any>(null)
const endpoints = ref<any[]>([])
const routeDecision = ref<any>(null)
const diagnostics = ref<any>({ summary: {}, checks: [] })
const knowledgeSources = ref<any[]>([])
const knowledgeRuns = ref<any[]>([])
const templates = ref<any[]>([])
const triggers = ref<any[]>([])
const workflows = ref<any[]>([])
const diffResult = ref<any>(null)

const datasetDialog = ref(false)
const evaluationDialog = ref(false)
const feedbackDialog = ref(false)
const endpointDialog = ref(false)
const sourceDialog = ref(false)
const syncDialog = ref(false)
const templateDialog = ref(false)
const triggerDialog = ref(false)

const datasetForm = ref({ name: '', targetType: 'prompt', threshold: 80, caseName: '', expected: '' })
const evaluationForm = ref({ datasetId: null as number | null, versionId: null as number | null, actual: '' })
const feedbackForm = ref({ rating: 5, comment: '' })
const endpointForm = ref({ model: '', provider: '', region: 'global', baseUrl: '', costPer1k: 0, qualityScore: 85, latencySloMs: 5000 })
const sourceForm = ref({ sourceKey: '', sourceType: 'api', roles: 'member,admin' })
const syncForm = ref({ sourceId: null as number | null, externalId: '', title: '', content: '', cursor: '' })
const templateForm = ref({ workflowId: null as number | null, name: '' })
const triggerForm = ref({ workflowId: null as number | null, triggerType: 'schedule', intervalSeconds: 3600 })
const knowledgeQuery = ref('')
const knowledgeExpected = ref<number | null>(null)

const selectedAgent = computed(() => agents.value.find(item => item.id === selectedAgentId.value))
const latestVersion = computed(() => versions.value[0])
const passedRuns = computed(() => runs.value.filter(item => item.status === 'passed').length)
const healthyEndpoints = computed(() => endpoints.value.filter(item => item.status === 'healthy').length)
const diagnosticReady = computed(() => diagnostics.value.summary?.failed === 0)

const capabilityStats = computed(() => [
  { label: tx('Agent 版本', 'Agent versions'), value: versions.value.length, hint: tx('可比较与回滚', 'Comparable and reversible'), icon: Files },
  { label: tx('评测门禁', 'Evaluation gates'), value: `${passedRuns.value}/${runs.value.length}`, hint: tx('通过 / 全部运行', 'Passed / all runs'), icon: CircleCheck },
  { label: tx('24h Trace', '24h traces'), value: traceSummary.value.totals?.traces || 0, hint: `${traceSummary.value.totals?.avg_latency_ms || 0} ms`, icon: DataAnalysis },
  { label: tx('健康模型', 'Healthy models'), value: `${healthyEndpoints.value}/${endpoints.value.length}`, hint: tx('自动熔断与恢复', 'Circuit break and recovery'), icon: Connection },
])

async function loadAll() {
  loading.value = true
  const requests = await Promise.allSettled([
    api.get('/agents', { params: { size: 100 } }),
    api.get('/product/evaluations/datasets'), api.get('/product/evaluations/runs'),
    api.get('/product/traces'), api.get('/product/traces/summary'), api.get('/product/observability/overview'),
    api.get('/product/routing/endpoints'), api.get('/product/diagnostics'),
    api.get('/product/knowledge/1/sources'), api.get('/product/knowledge/1/evaluation-runs'),
    api.get('/product/workflows/templates'), api.get('/product/workflows/triggers'),
    api.get('/workspace/workflow', { params: { size: 100 } }),
  ])
  const data = requests.map(result => result.status === 'fulfilled' ? (result.value as any).data : null)
  agents.value = data[0]?.content || []
  datasets.value = data[1] || []
  runs.value = data[2] || []
  traces.value = data[3] || []
  traceSummary.value = data[4] || { totals: {} }
  observability.value = data[5] || { totals: {}, attribution: [], spanAttribution: [], anomalies: [] }
  endpoints.value = data[6] || []
  diagnostics.value = data[7] || { summary: {}, checks: [] }
  knowledgeSources.value = data[8] || []
  knowledgeRuns.value = data[9] || []
  templates.value = data[10] || []
  triggers.value = data[11] || []
  workflows.value = data[12] || []
  if (!selectedAgentId.value && agents.value.length) selectedAgentId.value = agents.value[0].id
  loading.value = false
}

async function loadVersions() {
  if (!selectedAgentId.value) return
  const response = await api.get(`/product/agents/${selectedAgentId.value}/versions`) as any
  versions.value = response.data || []
}

async function createSnapshot() {
  if (!selectedAgentId.value) return
  const { value } = await ElMessageBox.prompt(tx('说明本次变更', 'Describe this change'), tx('创建版本快照', 'Create version snapshot'), {
    inputValue: tx('手动发布快照', 'Manual release snapshot'),
  })
  await api.post(`/product/agents/${selectedAgentId.value}/versions`, { note: value })
  ElMessage.success(tx('版本快照已创建', 'Version snapshot created'))
  await loadVersions()
}

async function releaseVersion(version: any, rolloutPercent: number) {
  await api.post(`/product/agents/${selectedAgentId.value}/versions/${version.id}/release`, { rolloutPercent })
  ElMessage.success(rolloutPercent === 100 ? tx('已完成全量发布', 'Full release completed') : tx('已开始灰度发布', 'Canary rollout started'))
  await loadVersions()
}

async function rollbackVersion(version: any) {
  await ElMessageBox.confirm(tx(`确认回滚到 v${version.version_no}？`, `Roll back to v${version.version_no}?`), tx('版本回滚', 'Version rollback'), { type: 'warning' })
  await api.post(`/product/agents/${selectedAgentId.value}/versions/${version.id}/rollback`)
  ElMessage.success(tx('回滚完成', 'Rollback completed'))
  await loadVersions()
}

async function compareLatest() {
  if (versions.value.length < 2 || !selectedAgentId.value) return
  const response = await api.get(`/product/agents/${selectedAgentId.value}/versions/diff`, {
    params: { left: versions.value[1].id, right: versions.value[0].id },
  }) as any
  diffResult.value = response.data
}

async function createDataset() {
  const form = datasetForm.value
  await api.post('/product/evaluations/datasets', {
    name: form.name, targetType: form.targetType, passThreshold: form.threshold,
    cases: [{ name: form.caseName, input: { prompt: 'offline fixture' }, expected: { value: form.expected }, assertionType: 'contains' }],
  })
  datasetDialog.value = false
  ElMessage.success(tx('评测集已创建', 'Evaluation dataset created'))
  await loadAll()
}

async function runEvaluation() {
  if (!evaluationForm.value.datasetId) return
  const detail = await api.get(`/product/evaluations/datasets/${evaluationForm.value.datasetId}`) as any
  const outputs: Record<string, string> = {}
  for (const item of detail.data?.cases || []) outputs[String(item.id)] = evaluationForm.value.actual
  const response = await api.post(`/product/evaluations/datasets/${evaluationForm.value.datasetId}/run`, { outputs }, {
    params: { agentId: selectedAgentId.value, versionId: evaluationForm.value.versionId },
  }) as any
  evaluationDialog.value = false
  ElMessage[response.data?.status === 'passed' ? 'success' : 'warning'](`${tx('评测得分', 'Evaluation score')}: ${response.data?.score}`)
  await Promise.all([loadAll(), loadVersions()])
}

async function inspectTrace(trace: any) {
  const response = await api.get(`/product/traces/${trace.trace_id}`) as any
  selectedTrace.value = response.data
}

async function replayTrace() {
  if (!selectedTrace.value?.trace_id) return
  const response = await api.post(`/product/observability/traces/${selectedTrace.value.trace_id}/replay`) as any
  replayResult.value = response.data
}

async function submitFeedback() {
  if (!selectedTrace.value?.trace_id) return
  await api.post(`/product/observability/traces/${selectedTrace.value.trace_id}/feedback`, feedbackForm.value)
  feedbackDialog.value = false
  ElMessage.success(tx('反馈已记录', 'Feedback recorded'))
}

async function saveEndpoint() {
  await api.post('/product/routing/endpoints', endpointForm.value)
  endpointDialog.value = false
  ElMessage.success(tx('模型端点已保存', 'Model endpoint saved'))
  await loadAll()
}

async function decideRoute() {
  const response = await api.post('/product/routing/decide', {
    preferredModel: selectedAgent.value?.model || endpoints.value[0]?.model || 'deepseek-v4-flash',
    constraints: { region: 'global', minQuality: 70, maxLatencyMs: 8000 },
  }) as any
  routeDecision.value = response.data
}

async function reportEndpoint(endpoint: any, success: boolean) {
  await api.post(`/product/routing/endpoints/${endpoint.id}/health`, { success, latencyMs: success ? 320 : 9000, error: success ? '' : 'Manual failure drill' })
  await loadAll()
}

async function probeEndpoint(endpoint: any) {
  try {
    await api.post(`/product/routing/endpoints/${endpoint.id}/probe`)
    ElMessage.success(tx('真实网络探测完成', 'Live network probe completed'))
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message || tx('探测失败', 'Probe failed'))
  }
  await loadAll()
}

async function createSource() {
  await api.post('/product/knowledge/1/sources', {
    sourceKey: sourceForm.value.sourceKey, sourceType: sourceForm.value.sourceType,
    inheritedAcl: { roles: sourceForm.value.roles.split(',').map(item => item.trim()).filter(Boolean) }, config: {},
  })
  sourceDialog.value = false
  ElMessage.success(tx('知识源已创建', 'Knowledge source created'))
  await loadAll()
}

async function syncSource() {
  if (!syncForm.value.sourceId) return
  const response = await api.post(`/product/knowledge/sources/${syncForm.value.sourceId}/sync`, {
    cursor: syncForm.value.cursor,
    documents: [{ externalId: syncForm.value.externalId, title: syncForm.value.title, content: syncForm.value.content }],
  }) as any
  syncDialog.value = false
  ElMessage.success(`${tx('增量同步完成', 'Incremental sync completed')}: +${response.data?.created || 0} / ~${response.data?.updated || 0}`)
  await loadAll()
}

async function evaluateKnowledge() {
  if (!knowledgeQuery.value.trim()) return
  const response = await api.post('/product/knowledge/1/evaluate', {
    query: knowledgeQuery.value, expectedDocumentId: knowledgeExpected.value,
  }) as any
  ElMessage[response.data?.passed ? 'success' : 'warning'](`${tx('检索得分', 'Retrieval score')}: ${Number(response.data?.score || 0).toFixed(2)}`)
  await loadAll()
}

async function createTemplate() {
  const workflow = workflows.value.find(item => item.id === templateForm.value.workflowId)
  if (!workflow) return
  await api.post('/product/workflows/templates', {
    name: templateForm.value.name || workflow.name, description: workflow.description,
    config: workflow.config,
  })
  templateDialog.value = false
  ElMessage.success(tx('工作流模板已创建', 'Workflow template created'))
  await loadAll()
}

async function instantiateTemplate(template: any) {
  await api.post(`/product/workflows/templates/${template.id}/instantiate`, { name: `${template.name} Copy` })
  ElMessage.success(tx('模板已实例化为草稿', 'Template instantiated as draft'))
  await loadAll()
}

async function createTrigger() {
  const response = await api.post('/product/workflows/triggers', triggerForm.value) as any
  triggerDialog.value = false
  if (response.data?.secret) {
    await ElMessageBox.alert(`${tx('密钥仅显示一次', 'Secret is shown once')}:\n${response.data.secret}`, tx('Webhook 密钥', 'Webhook secret'))
  } else ElMessage.success(tx('定时触发器已创建', 'Scheduled trigger created'))
  await loadAll()
}

function openEvaluation(dataset?: any) {
  evaluationForm.value.datasetId = dataset?.id || datasets.value[0]?.id || null
  evaluationForm.value.versionId = latestVersion.value?.id || null
  evaluationDialog.value = true
}

watch(selectedAgentId, loadVersions)
onMounted(loadAll)
</script>

<template>
  <div class="console-page release-page" v-loading="loading">
    <div class="console-page-head">
      <div class="console-page-head__copy"><span>RELEASE & RUNTIME</span><h1>{{ tx('发布与运行中心', 'Release & Runtime Center') }}</h1><p>{{ tx('把版本、质量、可观测性、模型韧性、知识和流程自动化收敛到一条可验证链路。', 'Operate versions, quality, observability, model resilience, knowledge and automation as one verifiable lifecycle.') }}</p></div>
      <div class="console-page-actions"><button class="console-icon-button" :aria-label="tx('刷新', 'Refresh')" @click="loadAll"><el-icon><Refresh /></el-icon></button><button class="console-primary" @click="activeTab = 'diagnostics'"><el-icon><Setting /></el-icon>{{ diagnosticsActionLabel }}</button></div>
    </div>

    <section class="ops-summary">
      <div v-for="item in capabilityStats" :key="item.label"><span class="summary-icon"><el-icon><component :is="item.icon" /></el-icon></span><p><small>{{ item.label }}</small><strong>{{ item.value }}</strong><em>{{ item.hint }}</em></p></div>
    </section>

    <div class="ops-tabs" role="tablist">
      <button v-for="tab in [
        ['observability', tx('观测与质量', 'Observability')], ['versions', tx('版本与评测', 'Versions & evals')], ['traces', 'Trace'], ['routing', tx('模型路由', 'Model routing')],
        ['knowledge', tx('知识同步', 'Knowledge sync')], ['workflows', tx('流程自动化', 'Workflow automation')], ['diagnostics', tx('配置诊断', 'Diagnostics')]
      ]" :key="tab[0]" :class="{ active: activeTab === tab[0] }" @click="activeTab = tab[0]">{{ tab[1] }}</button>
    </div>

    <section v-if="activeTab === 'observability'" class="ops-band observability-band">
      <header><div><span>01 / OBSERVABILITY</span><h2>{{ tx('SLO 与质量运营', 'SLO and quality operations') }}</h2></div><small>{{ tx('分位延迟、供应商错误、重试、取消、工具成功率与成本归因', 'Latency percentiles, provider errors, retries, cancellations, tool success and cost attribution') }}</small></header>
      <div class="slo-grid"><article><span>LATENCY P50</span><strong>{{ observability.totals?.latency_p50_ms || 0 }} ms</strong></article><article><span>LATENCY P95</span><strong>{{ observability.totals?.latency_p95_ms || 0 }} ms</strong></article><article><span>FIRST TOKEN P95</span><strong>{{ observability.totals?.first_token_p95_ms || 0 }} ms</strong></article><article><span>PROVIDER ERRORS</span><strong>{{ Number(observability.providerErrorRatePercent || 0).toFixed(2) }}%</strong></article><article><span>RETRIES / CANCELS</span><strong>{{ observability.totals?.retries || 0 }} / {{ observability.totals?.cancellations || 0 }}</strong></article><article><span>TOOL SUCCESS</span><strong>{{ Number(observability.toolSuccessRatePercent || 0).toFixed(2) }}%</strong></article><article><span>QUEUE DEPTH</span><strong>{{ observability.totals?.queue_depth || 0 }}</strong></article><article><span>24H COST</span><strong>{{ observability.totals?.cost || 0 }}</strong></article></div>
      <div class="quality-columns"><div><h3>{{ tx('模型与 Agent 成本归因', 'Model and Agent cost attribution') }}</h3><article v-for="item in observability.attribution" :key="`${item.model}-${item.agent_id}`"><p><strong>{{ item.model }}</strong><small>Agent {{ item.agent_id }} · {{ item.traces }} traces · {{ item.successful_traces }} success</small></p><b>{{ item.cost }}</b></article><div v-if="!observability.attribution?.length" class="empty-row">{{ tx('暂无观测数据', 'No observability data yet') }}</div></div><div><h3>{{ tx('工具 / 检索质量', 'Tool / retrieval quality') }}</h3><article v-for="item in observability.spanAttribution" :key="`${item.dimension}-${item.name}`"><p><strong>{{ item.name }}</strong><small>{{ item.dimension }} · {{ item.calls }} calls</small></p><b>{{ item.successful_calls }} OK</b></article><div v-if="!observability.spanAttribution?.length" class="empty-row">{{ tx('暂无工具或检索 Span', 'No tool or retrieval spans yet') }}</div></div></div>
      <div class="anomaly-strip"><span>{{ tx('异常信号', 'Anomaly signals') }}</span><b v-for="item in observability.anomalies" :key="item.type" :class="item.severity">{{ item.type }} · {{ item.value }}</b><small v-if="!observability.anomalies?.length">{{ tx('当前窗口未发现超过门槛的异常', 'No threshold breach in the current window') }}</small></div>
    </section>

    <section v-if="activeTab === 'versions'" class="ops-band">
      <header><div><span>01 / 02</span><h2>{{ tx('Agent 版本与发布门禁', 'Agent versions and release gates') }}</h2></div><div class="band-actions"><el-select v-model="selectedAgentId" style="width: 210px"><el-option v-for="agent in agents" :key="agent.id" :value="agent.id" :label="agent.name" /></el-select><button class="console-secondary" @click="compareLatest" :disabled="versions.length < 2"><el-icon><Switch /></el-icon>{{ tx('比较最近版本', 'Compare latest') }}</button><button class="console-primary" @click="createSnapshot"><el-icon><Plus /></el-icon>{{ tx('创建快照', 'Create snapshot') }}</button></div></header>
      <div class="split-grid">
        <div class="data-table"><div class="table-head"><span>{{ tx('版本', 'Version') }}</span><span>{{ tx('状态', 'Status') }}</span><span>{{ tx('评测', 'Evaluation') }}</span><span>{{ tx('发布时间', 'Published') }}</span><span>{{ tx('操作', 'Actions') }}</span></div><div v-for="version in versions" :key="version.id" class="table-row"><strong>v{{ version.version_no }}</strong><span class="status-dot" :class="version.status"><i />{{ version.status }}<b v-if="version.rollout_percent">{{ version.rollout_percent }}%</b></span><span>{{ version.evaluation_status }}</span><span>{{ version.published_at?.replace('T', ' ')?.slice(0, 16) || '-' }}</span><div class="row-actions"><button @click="releaseVersion(version, 10)">{{ tx('灰度', 'Canary') }}</button><button @click="releaseVersion(version, 100)">{{ tx('发布', 'Release') }}</button><button @click="rollbackVersion(version)"><el-icon><RefreshLeft /></el-icon></button></div></div><div v-if="!versions.length" class="empty-row">{{ tx('选择 Agent 后创建第一个版本快照', 'Select an Agent and create its first version snapshot') }}</div></div>
        <aside class="diff-panel"><span>CONFIG DIFF</span><template v-if="diffResult"><strong>{{ diffResult.changedFields }} {{ tx('项变更', 'changes') }}</strong><ul><li v-for="change in diffResult.changes" :key="change.field"><b>{{ change.field }}</b><small>{{ String(change.before ?? '-') }}</small><em>{{ String(change.after ?? '-') }}</em></li></ul></template><template v-else><el-icon><Files /></el-icon><strong>{{ tx('草稿差异', 'Draft differences') }}</strong><p>{{ tx('选择“比较最近版本”查看 Prompt、模型、温度和工具配置变化。', 'Compare the latest snapshots to inspect Prompt, model, temperature and tool configuration changes.') }}</p></template></aside>
      </div>
      <div class="eval-head"><div><span>QUALITY GATE</span><h3>{{ tx('离线回归评测', 'Offline regression evaluations') }}</h3></div><div class="band-actions"><button class="console-secondary" @click="datasetDialog = true"><el-icon><Plus /></el-icon>{{ tx('新建评测集', 'New dataset') }}</button><button class="console-primary" @click="openEvaluation()"><el-icon><VideoPlay /></el-icon>{{ tx('运行评测', 'Run evaluation') }}</button></div></div>
      <div class="dataset-grid"><article v-for="dataset in datasets" :key="dataset.id"><span>{{ dataset.target_type }}</span><strong>{{ dataset.name }}</strong><p>{{ dataset.case_count }} {{ tx('个用例', 'cases') }} · {{ dataset.pass_threshold }}% gate</p><button @click="openEvaluation(dataset)"><el-icon><VideoPlay /></el-icon>{{ tx('运行', 'Run') }}</button></article><div v-if="!datasets.length" class="empty-row">{{ tx('创建 Prompt、工具或 RAG 评测集后，发布门禁会自动生效。', 'Create Prompt, tool or RAG datasets to activate release gating.') }}</div></div>
    </section>

    <section v-else-if="activeTab === 'traces'" class="ops-band">
      <header><div><span>03</span><h2>{{ tx('完整会话 Trace', 'End-to-end conversation traces') }}</h2></div><div class="band-actions"><button class="console-secondary" :disabled="!selectedTrace" @click="replayTrace"><el-icon><Refresh /></el-icon>{{ tx('脱敏回放', 'Redacted replay') }}</button><button class="console-primary" :disabled="!selectedTrace" @click="feedbackDialog = true"><el-icon><CircleCheck /></el-icon>{{ tx('质量反馈', 'Quality feedback') }}</button><small>{{ tx('模型、工具、检索、审批、Token 与费用', 'Model, tools, retrieval, approvals, tokens and cost') }}</small></div></header>
      <div class="trace-layout"><div class="trace-list"><button v-for="trace in traces" :key="trace.trace_id" :class="{ selected: selectedTrace?.trace_id === trace.trace_id }" @click="inspectTrace(trace)"><i :class="trace.status" /><p><strong>{{ trace.model || 'unrouted' }}</strong><span>{{ trace.trace_id }}</span></p><em>{{ trace.latency_ms || 0 }} ms</em><b>{{ trace.status }}</b></button><div v-if="!traces.length" class="empty-row">{{ tx('完成一次 Agent 对话后会生成真实 Trace。', 'A real trace appears after an Agent conversation completes.') }}</div></div><aside class="span-view"><template v-if="selectedTrace"><div class="trace-meta"><span>{{ selectedTrace.trace_id }}</span><strong>{{ selectedTrace.model }}</strong><small>{{ selectedTrace.route_reason }}</small></div><ol><li v-for="span in selectedTrace.spans" :key="span.id"><i /><p><strong>{{ span.name }}</strong><span>{{ span.span_type }} · {{ span.status }}</span></p><em>{{ span.duration_ms || 0 }} ms</em></li></ol></template><template v-else><el-icon><DataAnalysis /></el-icon><strong>{{ tx('选择一条 Trace', 'Select a trace') }}</strong><p>{{ tx('查看调用时间线和路由原因。', 'Inspect its timeline and routing decision.') }}</p></template></aside></div>
      <StructuredResult v-if="replayResult" class="replay-output" :data="replayResult" :raw-label="tx('查看原始 JSON', 'View raw JSON')" />
    </section>

    <section v-else-if="activeTab === 'routing'" class="ops-band">
      <header><div><span>04 / 07</span><h2>{{ tx('模型路由、健康与故障转移', 'Model routing, health and failover') }}</h2></div><div class="band-actions"><button class="console-secondary" @click="decideRoute"><el-icon><Search /></el-icon>{{ tx('模拟路由', 'Simulate route') }}</button><button class="console-primary" @click="endpointDialog = true"><el-icon><Plus /></el-icon>{{ tx('模型端点', 'Model endpoint') }}</button></div></header>
      <div v-if="routeDecision" class="route-decision"><el-icon><Connection /></el-icon><p><span>{{ tx('选中模型', 'Selected model') }}</span><strong>{{ routeDecision.model }}</strong><small>{{ routeDecision.reason }}</small></p><b>{{ routeDecision.score }}</b></div>
      <div class="endpoint-list"><article v-for="endpoint in endpoints" :key="endpoint.id"><i :class="endpoint.status" /><p><span>{{ endpoint.provider }} · {{ endpoint.region }}</span><strong>{{ endpoint.model }}</strong><small>{{ endpoint.last_latency_ms || endpoint.latency_slo_ms }} ms · ${{ endpoint.cost_per_1k }}/1K · Q{{ endpoint.quality_score }}</small></p><div><em :class="endpoint.circuit_state">{{ endpoint.circuit_state }}</em><button @click="reportEndpoint(endpoint, true)"><el-icon><CircleCheck /></el-icon></button><button @click="reportEndpoint(endpoint, false)"><el-icon><Warning /></el-icon></button><button :disabled="!endpoint.base_url" @click="probeEndpoint(endpoint)"><el-icon><Connection /></el-icon></button></div></article><div v-if="!endpoints.length" class="empty-row">{{ tx('添加至少两个端点以验证自动故障转移。', 'Add at least two endpoints to verify automatic failover.') }}</div></div>
    </section>

    <section v-else-if="activeTab === 'knowledge'" class="ops-band">
      <header><div><span>05</span><h2>{{ tx('知识增量同步、权限与效果评测', 'Knowledge sync, permissions and quality') }}</h2></div><div class="band-actions"><button class="console-secondary" @click="sourceDialog = true"><el-icon><Plus /></el-icon>{{ tx('新建知识源', 'New source') }}</button><button class="console-primary" @click="syncDialog = true"><el-icon><Refresh /></el-icon>{{ tx('增量同步', 'Incremental sync') }}</button></div></header>
      <div class="source-list"><article v-for="source in knowledgeSources" :key="source.id"><el-icon><Document /></el-icon><p><span>{{ source.source_type }}</span><strong>{{ source.source_key }}</strong><small>{{ tx('游标', 'Cursor') }}: {{ source.sync_cursor || '-' }} · ACL {{ JSON.stringify(source.inherited_acl) }}</small></p><b>{{ source.status }}</b></article><div v-if="!knowledgeSources.length" class="empty-row">{{ tx('新建 API、文件夹或业务系统知识源。', 'Create an API, folder or business-system knowledge source.') }}</div></div>
      <div class="knowledge-eval"><div><span>RETRIEVAL EVAL</span><h3>{{ tx('引用溯源与召回验证', 'Citation and retrieval validation') }}</h3></div><input v-model="knowledgeQuery" :placeholder="tx('输入验证问题', 'Enter evaluation query')" /><input v-model.number="knowledgeExpected" type="number" :placeholder="tx('期望文档 ID（可选）', 'Expected document ID (optional)')" /><button class="console-primary" @click="evaluateKnowledge"><el-icon><Search /></el-icon>{{ tx('验证', 'Evaluate') }}</button><small v-if="knowledgeRuns[0]">{{ tx('最近得分', 'Latest score') }} {{ Number(knowledgeRuns[0].score).toFixed(2) }} · {{ knowledgeRuns[0].passed ? 'PASS' : 'FAIL' }}</small></div>
    </section>

    <section v-else-if="activeTab === 'workflows'" class="ops-band">
      <header><div><span>06</span><h2>{{ tx('模板、子流程与自动触发', 'Templates, subflows and triggers') }}</h2></div><div class="band-actions"><button class="console-secondary" @click="templateDialog = true"><el-icon><Plus /></el-icon>{{ tx('保存模板', 'Save template') }}</button><button class="console-primary" @click="triggerDialog = true"><el-icon><Timer /></el-icon>{{ tx('新建触发器', 'New trigger') }}</button></div></header>
      <div class="workflow-columns"><div><h3>{{ tx('可复用模板', 'Reusable templates') }}</h3><article v-for="template in templates" :key="template.id"><el-icon><Files /></el-icon><p><span>v{{ template.version }}</span><strong>{{ template.name }}</strong><small>{{ template.description }}</small></p><button @click="instantiateTemplate(template)">{{ tx('实例化', 'Instantiate') }}</button></article><div v-if="!templates.length" class="empty-row">{{ tx('把现有工作流保存为模板。', 'Save an existing workflow as a template.') }}</div></div><div><h3>{{ tx('自动触发器', 'Automation triggers') }}</h3><article v-for="trigger in triggers" :key="trigger.id"><el-icon><component :is="trigger.trigger_type === 'schedule' ? Timer : Connection" /></el-icon><p><span>{{ trigger.trigger_type }}</span><strong>{{ trigger.workflow_name || trigger.trigger_key }}</strong><small>{{ trigger.interval_seconds ? `${trigger.interval_seconds}s` : `/api/hooks/workflows/${trigger.trigger_key}` }}</small></p><b>{{ trigger.enabled ? 'ON' : 'OFF' }}</b></article><div v-if="!triggers.length" class="empty-row">{{ tx('创建定时或密钥保护的 Webhook 触发器。', 'Create scheduled or secret-protected webhook triggers.') }}</div></div></div>
    </section>

    <section v-else class="ops-band diagnostics-band">
      <header><div><span>08</span><h2>{{ tx('配置诊断中心', 'Configuration diagnostics') }}</h2></div><div class="diagnostic-state" :class="{ ready: diagnosticReady }"><i />{{ diagnostics.status || 'unknown' }}</div></header>
      <div class="diagnostic-summary"><div><strong>{{ diagnostics.summary?.passed || 0 }}</strong><span>PASS</span></div><div><strong>{{ diagnostics.summary?.warnings || 0 }}</strong><span>WARNING</span></div><div><strong>{{ diagnostics.summary?.failed || 0 }}</strong><span>FAIL</span></div><p><el-icon><Setting /></el-icon><span>{{ tx('诊断结果不会返回任何 Key、Secret 或密码值。', 'Diagnostic results never return Key, Secret or password values.') }}</span></p></div>
      <div class="check-list"><article v-for="check in diagnostics.checks" :key="check.id"><span class="check-status" :class="check.status"><el-icon><component :is="check.status === 'pass' ? CircleCheck : Warning" /></el-icon></span><p><strong>{{ check.title }}</strong><small>{{ check.detail }}</small></p><em>{{ check.action }}</em><b>{{ check.status }}</b></article></div>
    </section>

    <el-dialog v-model="datasetDialog" :title="tx('新建离线评测集', 'New offline evaluation dataset')" width="520px"><el-form label-position="top"><el-form-item :label="tx('名称', 'Name')"><el-input v-model="datasetForm.name" /></el-form-item><el-form-item :label="tx('目标', 'Target')"><el-select v-model="datasetForm.targetType"><el-option value="prompt" label="Prompt" /><el-option value="tool" label="Tool" /><el-option value="rag" label="RAG" /></el-select></el-form-item><el-form-item :label="tx('首个用例', 'First case')"><el-input v-model="datasetForm.caseName" /></el-form-item><el-form-item :label="tx('输出必须包含', 'Output must contain')"><el-input v-model="datasetForm.expected" /></el-form-item><el-form-item :label="tx('通过阈值', 'Pass threshold')"><el-slider v-model="datasetForm.threshold" :min="0" :max="100" show-input /></el-form-item></el-form><template #footer><el-button @click="datasetDialog = false">{{ tx('取消', 'Cancel') }}</el-button><el-button type="primary" @click="createDataset">{{ tx('创建', 'Create') }}</el-button></template></el-dialog>
    <el-dialog v-model="evaluationDialog" :title="tx('运行离线评测', 'Run offline evaluation')" width="560px"><el-form label-position="top"><el-form-item :label="tx('评测集', 'Dataset')"><el-select v-model="evaluationForm.datasetId"><el-option v-for="item in datasets" :key="item.id" :value="item.id" :label="item.name" /></el-select></el-form-item><el-form-item :label="tx('目标版本', 'Target version')"><el-select v-model="evaluationForm.versionId"><el-option v-for="item in versions" :key="item.id" :value="item.id" :label="`v${item.version_no}`" /></el-select></el-form-item><el-form-item :label="tx('候选输出', 'Candidate output')"><el-input v-model="evaluationForm.actual" type="textarea" :rows="7" /></el-form-item></el-form><template #footer><el-button @click="evaluationDialog = false">{{ tx('取消', 'Cancel') }}</el-button><el-button type="primary" @click="runEvaluation">{{ tx('执行并计算门禁', 'Run and calculate gate') }}</el-button></template></el-dialog>
    <el-dialog v-model="feedbackDialog" :title="tx('记录线上质量反馈', 'Record online quality feedback')" width="480px"><el-form label-position="top"><el-form-item :label="tx('评分', 'Rating')"><el-rate v-model="feedbackForm.rating" /></el-form-item><el-form-item :label="tx('备注', 'Comment')"><el-input v-model="feedbackForm.comment" type="textarea" :rows="4" maxlength="2000" show-word-limit /></el-form-item></el-form><template #footer><el-button @click="feedbackDialog = false">{{ tx('取消', 'Cancel') }}</el-button><el-button type="primary" @click="submitFeedback">{{ tx('提交反馈', 'Submit feedback') }}</el-button></template></el-dialog>
    <el-dialog v-model="endpointDialog" :title="tx('模型端点', 'Model endpoint')" width="560px"><el-form label-position="top"><el-form-item label="Model"><el-input v-model="endpointForm.model" /></el-form-item><el-form-item label="Provider"><el-input v-model="endpointForm.provider" /></el-form-item><div class="form-row"><el-form-item label="Region"><el-input v-model="endpointForm.region" /></el-form-item><el-form-item label="Base URL (HTTPS)"><el-input v-model="endpointForm.baseUrl" /></el-form-item></div><div class="form-row"><el-form-item label="Cost / 1K"><el-input-number v-model="endpointForm.costPer1k" :min="0" /></el-form-item><el-form-item label="Quality"><el-input-number v-model="endpointForm.qualityScore" :min="0" :max="100" /></el-form-item><el-form-item label="SLO ms"><el-input-number v-model="endpointForm.latencySloMs" :min="100" /></el-form-item></div></el-form><template #footer><el-button @click="endpointDialog = false">{{ tx('取消', 'Cancel') }}</el-button><el-button type="primary" @click="saveEndpoint">{{ tx('保存', 'Save') }}</el-button></template></el-dialog>
    <el-dialog v-model="sourceDialog" :title="tx('新建知识源', 'New knowledge source')" width="500px"><el-form label-position="top"><el-form-item label="Source key"><el-input v-model="sourceForm.sourceKey" /></el-form-item><el-form-item label="Source type"><el-select v-model="sourceForm.sourceType"><el-option value="api" label="API" /><el-option value="folder" label="Folder" /><el-option value="business_system" label="Business system" /></el-select></el-form-item><el-form-item :label="tx('继承角色（逗号分隔）', 'Inherited roles (comma-separated)')"><el-input v-model="sourceForm.roles" /></el-form-item></el-form><template #footer><el-button @click="sourceDialog = false">{{ tx('取消', 'Cancel') }}</el-button><el-button type="primary" @click="createSource">{{ tx('创建', 'Create') }}</el-button></template></el-dialog>
    <el-dialog v-model="syncDialog" :title="tx('增量同步文档', 'Incrementally sync document')" width="560px"><el-form label-position="top"><el-form-item :label="tx('知识源', 'Source')"><el-select v-model="syncForm.sourceId"><el-option v-for="source in knowledgeSources" :key="source.id" :value="source.id" :label="source.source_key" /></el-select></el-form-item><div class="form-row"><el-form-item label="External ID"><el-input v-model="syncForm.externalId" /></el-form-item><el-form-item :label="tx('标题', 'Title')"><el-input v-model="syncForm.title" /></el-form-item></div><el-form-item :label="tx('内容', 'Content')"><el-input v-model="syncForm.content" type="textarea" :rows="7" /></el-form-item><el-form-item label="Cursor"><el-input v-model="syncForm.cursor" /></el-form-item></el-form><template #footer><el-button @click="syncDialog = false">{{ tx('取消', 'Cancel') }}</el-button><el-button type="primary" @click="syncSource">{{ tx('同步', 'Sync') }}</el-button></template></el-dialog>
    <el-dialog v-model="templateDialog" :title="tx('保存工作流模板', 'Save workflow template')" width="500px"><el-form label-position="top"><el-form-item :label="tx('工作流', 'Workflow')"><el-select v-model="templateForm.workflowId"><el-option v-for="workflow in workflows" :key="workflow.id" :value="workflow.id" :label="workflow.name" /></el-select></el-form-item><el-form-item :label="tx('模板名称', 'Template name')"><el-input v-model="templateForm.name" /></el-form-item></el-form><template #footer><el-button @click="templateDialog = false">{{ tx('取消', 'Cancel') }}</el-button><el-button type="primary" @click="createTemplate">{{ tx('保存模板', 'Save template') }}</el-button></template></el-dialog>
    <el-dialog v-model="triggerDialog" :title="tx('新建工作流触发器', 'New workflow trigger')" width="500px"><el-form label-position="top"><el-form-item :label="tx('工作流', 'Workflow')"><el-select v-model="triggerForm.workflowId"><el-option v-for="workflow in workflows" :key="workflow.id" :value="workflow.id" :label="workflow.name" /></el-select></el-form-item><el-form-item :label="tx('触发类型', 'Trigger type')"><el-segmented v-model="triggerForm.triggerType" :options="[{ label: tx('定时', 'Schedule'), value: 'schedule' }, { label: 'Webhook', value: 'webhook' }]" /></el-form-item><el-form-item v-if="triggerForm.triggerType === 'schedule'" :label="tx('间隔秒数', 'Interval seconds')"><el-input-number v-model="triggerForm.intervalSeconds" :min="30" /></el-form-item></el-form><template #footer><el-button @click="triggerDialog = false">{{ tx('取消', 'Cancel') }}</el-button><el-button type="primary" @click="createTrigger">{{ tx('创建', 'Create') }}</el-button></template></el-dialog>
  </div>
</template>

<style scoped>
.release-page { --ops-green: #57e5a1; --ops-line: #293138; --ops-panel: #11161a; color: #dce5df; }
.ops-summary { display: grid; grid-template-columns: repeat(4, 1fr); border: 1px solid var(--ops-line); background: #0f1316; }
.ops-summary > div { min-height: 108px; padding: 18px; display: flex; gap: 13px; border-right: 1px solid var(--ops-line); }.ops-summary > div:last-child { border: 0; }
.summary-icon { width: 34px; height: 34px; flex: 0 0 34px; display: grid; place-items: center; border: 1px solid #334039; border-radius: 6px; background: #17211c; color: var(--ops-green); }
.ops-summary p { min-width: 0; display: flex; flex-direction: column; }.ops-summary small { color: #78838a; font-size: 12px; }.ops-summary strong { margin-top: 10px; color: #f2f6f3; font-size: 23px; }.ops-summary em { margin-top: 4px; color: #626d74; font-size: 12px; font-style: normal; }
.ops-tabs { margin-top: 18px; display: flex; gap: 2px; border-bottom: 1px solid var(--ops-line); overflow-x: auto; }.ops-tabs button { min-height: 42px; padding: 0 17px; border: 0; border-bottom: 2px solid transparent; background: transparent; color: #747f86; font: inherit; font-size: 12px; white-space: nowrap; cursor: pointer; }.ops-tabs button.active { border-color: var(--ops-green); color: #e9f2ec; background: #121a16; }
.ops-band { min-height: 540px; border: 1px solid var(--ops-line); border-top: 0; background: #0e1215; }.ops-band > header { min-height: 72px; padding: 0 18px; display: flex; align-items: center; justify-content: space-between; gap: 14px; border-bottom: 1px solid var(--ops-line); }.ops-band > header span, .eval-head span, .knowledge-eval span { color: var(--ops-green); font: 12px ui-monospace, monospace; }.ops-band h2 { margin-top: 5px; font-size: 15px; }.ops-band > header > small { color: #707b82; font-size: 12px; }
.band-actions, .row-actions { display: flex; align-items: center; gap: 7px; }.band-actions button, .row-actions button { display: inline-flex; align-items: center; justify-content: center; gap: 5px; }
.split-grid { display: grid; grid-template-columns: minmax(0, 1fr) 300px; }.data-table { min-width: 0; }.table-head, .table-row { min-height: 54px; padding: 0 16px; display: grid; grid-template-columns: 70px 135px 110px minmax(120px, 1fr) 205px; align-items: center; gap: 9px; border-bottom: 1px solid #22292e; }.table-head { min-height: 38px; color: #59646b; font: 12px ui-monospace, monospace; }.table-row { color: #aab4ae; font-size: 12px; }.table-row > strong { color: #edf4ef; font-size: 12px; }.status-dot { display: flex; align-items: center; gap: 6px; }.status-dot i, .endpoint-list article > i { width: 7px; height: 7px; border-radius: 50%; background: #68737a; }.status-dot.published i, .status-dot.canary i, .endpoint-list article > i.healthy { background: var(--ops-green); }.status-dot b { color: #81d7aa; font-size: 12px; }.row-actions button { min-height: 28px; padding: 0 7px; border: 1px solid #323a40; border-radius: 4px; background: #14191d; color: #9ba6ad; font: inherit; font-size: 12px; cursor: pointer; }
.diff-panel, .span-view { min-height: 300px; padding: 18px; border-left: 1px solid var(--ops-line); background: #11161a; }.diff-panel > span { color: #69747b; font: 12px ui-monospace, monospace; }.diff-panel > .el-icon, .span-view > .el-icon { display: block; margin: 72px auto 12px; color: #4d5a62; font-size: 28px; }.diff-panel > strong, .span-view > strong { display: block; text-align: center; font-size: 12px; }.diff-panel > p, .span-view > p { margin: 8px auto; max-width: 230px; color: #667179; font-size: 12px; line-height: 1.6; text-align: center; }.diff-panel ul { margin: 14px 0 0; padding: 0; list-style: none; }.diff-panel li { padding: 10px 0; display: grid; gap: 4px; border-top: 1px solid #273036; }.diff-panel li b { font-size: 12px; }.diff-panel li small { color: #9f6f6f; text-decoration: line-through; }.diff-panel li em { color: #7bc59e; font-style: normal; }
.eval-head { min-height: 68px; padding: 0 18px; display: flex; align-items: center; justify-content: space-between; border-top: 1px solid var(--ops-line); border-bottom: 1px solid var(--ops-line); }.eval-head h3, .knowledge-eval h3 { margin-top: 4px; font-size: 12px; }.dataset-grid { padding: 12px; display: grid; grid-template-columns: repeat(3, 1fr); gap: 9px; }.dataset-grid article { min-height: 112px; padding: 14px; position: relative; border: 1px solid #293138; background: #11161a; }.dataset-grid article > span { color: #6ab68c; font: 12px ui-monospace, monospace; text-transform: uppercase; }.dataset-grid article strong { display: block; margin-top: 10px; font-size: 12px; }.dataset-grid article p { margin-top: 5px; color: #69747b; font-size: 12px; }.dataset-grid article button { position: absolute; right: 12px; bottom: 12px; display: flex; gap: 4px; border: 0; background: transparent; color: #88d7aa; font: inherit; font-size: 12px; cursor: pointer; }
.trace-layout { display: grid; grid-template-columns: minmax(0, 1fr) 360px; }.trace-list { min-height: 480px; }.trace-list > button { width: 100%; min-height: 66px; padding: 0 16px; display: grid; grid-template-columns: 8px minmax(0, 1fr) 80px 65px; align-items: center; gap: 12px; border: 0; border-bottom: 1px solid #22292e; background: transparent; color: #abb5af; font: inherit; text-align: left; cursor: pointer; }.trace-list > button.selected, .trace-list > button:hover { background: #151e19; }.trace-list > button > i { width: 7px; height: 7px; border-radius: 50%; background: #d46f67; }.trace-list > button > i.success { background: var(--ops-green); }.trace-list p { min-width: 0; display: flex; flex-direction: column; gap: 4px; }.trace-list p strong { color: #e4ebe6; font-size: 12px; }.trace-list p span { overflow: hidden; color: #5d6870; font: 12px ui-monospace, monospace; text-overflow: ellipsis; }.trace-list em { color: #7c878e; font-size: 12px; font-style: normal; }.trace-list b { color: #8f9ba2; font-size: 12px; }.span-view { min-height: 480px; }.trace-meta { padding-bottom: 14px; display: flex; flex-direction: column; gap: 5px; border-bottom: 1px solid #293138; }.trace-meta span { color: #5e6970; font: 12px ui-monospace, monospace; }.trace-meta strong { font-size: 12px; }.trace-meta small { color: #748087; font-size: 12px; line-height: 1.5; }.span-view ol { margin: 12px 0; padding: 0; list-style: none; }.span-view li { min-height: 56px; display: grid; grid-template-columns: 8px 1fr auto; gap: 10px; align-items: center; border-bottom: 1px solid #252d32; }.span-view li > i { width: 7px; height: 7px; border: 2px solid #75c99b; border-radius: 50%; }.span-view li p { display: flex; flex-direction: column; gap: 4px; }.span-view li strong { font-size: 12px; }.span-view li span, .span-view li em { color: #68747b; font-size: 12px; font-style: normal; }
.slo-grid { display: grid; grid-template-columns: repeat(4, 1fr); border-bottom: 1px solid var(--ops-line); }.slo-grid article { min-height: 90px; padding: 15px; border-right: 1px solid var(--ops-line); border-bottom: 1px solid var(--ops-line); background: #10161a; }.slo-grid article:nth-child(4n) { border-right: 0; }.slo-grid span { color: #687c78; font: 12px ui-monospace, monospace; }.slo-grid strong { display: block; margin-top: 14px; color: #e9f3ed; font-size: 18px; }.quality-columns { display: grid; grid-template-columns: 1fr 1fr; }.quality-columns > div { min-height: 280px; border-right: 1px solid var(--ops-line); }.quality-columns > div:last-child { border-right: 0; }.quality-columns h3 { min-height: 44px; padding: 0 16px; display: flex; align-items: center; border-bottom: 1px solid #252d32; color: #8c9991; font-size: 12px; }.quality-columns article { min-height: 58px; padding: 0 16px; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid #22292e; }.quality-columns article p { display: flex; flex-direction: column; gap: 5px; }.quality-columns article strong { color: #dfe9e2; font-size: 12px; }.quality-columns article small { color: #65717a; font-size: 12px; }.quality-columns article b { color: var(--ops-green); font-size: 12px; }.anomaly-strip { min-height: 46px; padding: 0 16px; display: flex; align-items: center; gap: 10px; border-top: 1px solid var(--ops-line); color: #718079; font-size: 12px; }.anomaly-strip b { padding: 5px 7px; border-radius: 4px; background: #322b1d; color: #edbb67; font: 12px ui-monospace, monospace; }.anomaly-strip b.critical { background: #372322; color: #eb8177; }.anomaly-strip small { color: #65716f; }.replay-output { max-height: 240px; margin: 0; padding: 14px 18px; overflow: auto; border-top: 1px solid var(--ops-line); background: #0a0e10; color: #92c9a8; font: 12px ui-monospace, monospace; white-space: pre-wrap; }
.route-decision { margin: 14px; min-height: 76px; padding: 14px; display: grid; grid-template-columns: 38px 1fr auto; gap: 12px; align-items: center; border: 1px solid #315441; background: #132119; }.route-decision > .el-icon { color: var(--ops-green); font-size: 22px; }.route-decision p { display: flex; flex-direction: column; gap: 4px; }.route-decision span { color: #698177; font-size: 12px; }.route-decision strong { font-size: 12px; }.route-decision small { color: #7f8d86; font-size: 12px; }.route-decision > b { color: var(--ops-green); font-size: 18px; }.endpoint-list article, .source-list article { min-height: 72px; padding: 0 18px; display: grid; grid-template-columns: 9px 1fr auto; gap: 13px; align-items: center; border-bottom: 1px solid #22292e; }.endpoint-list article > i.degraded { background: #e6b35f; }.endpoint-list article > i.unhealthy { background: #dd7169; }.endpoint-list p, .source-list p { display: flex; flex-direction: column; gap: 4px; }.endpoint-list p span, .source-list p span { color: #62bd8d; font: 12px ui-monospace, monospace; }.endpoint-list p strong, .source-list p strong { font-size: 12px; }.endpoint-list p small, .source-list p small { color: #68737a; font-size: 12px; }.endpoint-list article > div { display: flex; align-items: center; gap: 6px; }.endpoint-list em { min-width: 50px; color: #7c878e; font: 12px ui-monospace, monospace; font-style: normal; text-align: center; }.endpoint-list em.open { color: #e88075; }.endpoint-list button { width: 30px; height: 30px; display: grid; place-items: center; border: 1px solid #30383e; border-radius: 4px; background: #151a1e; color: #879198; cursor: pointer; }.endpoint-list button:disabled { opacity: .3; }
.source-list article { grid-template-columns: 30px 1fr auto; }.source-list article > .el-icon { color: #79c99e; font-size: 18px; }.source-list article > b { color: #7c878e; font-size: 12px; }.knowledge-eval { margin: 16px; padding: 16px; display: grid; grid-template-columns: minmax(170px, 1fr) minmax(180px, 2fr) 170px auto auto; gap: 10px; align-items: center; border: 1px solid #2a343a; background: #12171a; }.knowledge-eval input { min-height: 36px; padding: 0 10px; border: 1px solid #323b41; border-radius: 5px; outline: 0; background: #0d1114; color: #dce5df; font: inherit; font-size: 12px; }.knowledge-eval > small { color: #7dc79f; font-size: 12px; }
.workflow-columns { display: grid; grid-template-columns: 1fr 1fr; }.workflow-columns > div { min-height: 460px; border-right: 1px solid var(--ops-line); }.workflow-columns > div:last-child { border: 0; }.workflow-columns h3 { min-height: 45px; padding: 0 16px; display: flex; align-items: center; border-bottom: 1px solid #252d32; color: #78838a; font-size: 12px; }.workflow-columns article { min-height: 76px; padding: 0 16px; display: grid; grid-template-columns: 34px 1fr auto; gap: 11px; align-items: center; border-bottom: 1px solid #22292e; }.workflow-columns article > .el-icon { color: #76c99c; font-size: 18px; }.workflow-columns article p { display: flex; flex-direction: column; gap: 4px; }.workflow-columns article span { color: #65b989; font: 12px ui-monospace, monospace; }.workflow-columns article strong { font-size: 12px; }.workflow-columns article small { color: #68737a; font-size: 12px; }.workflow-columns article button { min-height: 30px; border: 1px solid #354039; border-radius: 4px; background: #152019; color: #81d5a6; font: inherit; font-size: 12px; }.workflow-columns article > b { color: #79ce9f; font-size: 12px; }
.diagnostic-state { display: flex; align-items: center; gap: 7px; color: #d47c70; font: 12px ui-monospace, monospace; text-transform: uppercase; }.diagnostic-state i { width: 8px; height: 8px; border-radius: 50%; background: currentColor; }.diagnostic-state.ready { color: var(--ops-green); }.diagnostic-summary { display: grid; grid-template-columns: 130px 130px 130px 1fr; border-bottom: 1px solid var(--ops-line); }.diagnostic-summary > div { min-height: 92px; padding: 16px; display: flex; flex-direction: column; border-right: 1px solid var(--ops-line); }.diagnostic-summary strong { margin-top: auto; font-size: 22px; }.diagnostic-summary span { color: #68737a; font: 12px ui-monospace, monospace; }.diagnostic-summary p { padding: 16px; display: flex; align-items: center; gap: 10px; color: #7a858c; font-size: 12px; }.check-list article { min-height: 72px; padding: 0 18px; display: grid; grid-template-columns: 34px minmax(180px, 1fr) minmax(220px, 1fr) 70px; gap: 12px; align-items: center; border-bottom: 1px solid #22292e; }.check-status { width: 30px; height: 30px; display: grid; place-items: center; border-radius: 5px; background: #382322; color: #df776e; }.check-status.pass { background: #15291f; color: var(--ops-green); }.check-status.warning { background: #332c1d; color: #edbc65; }.check-list p { display: flex; flex-direction: column; gap: 5px; }.check-list strong { font-size: 12px; }.check-list small, .check-list em { color: #6f7a81; font-size: 12px; font-style: normal; line-height: 1.5; }.check-list b { color: #89949a; font: 12px ui-monospace, monospace; text-transform: uppercase; }
.empty-row { min-height: 100px; padding: 22px; display: grid; place-items: center; color: #626d74; font-size: 12px; text-align: center; }.form-row { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
@media (max-width: 1100px) { .ops-summary { grid-template-columns: 1fr 1fr; }.ops-summary > div:nth-child(2) { border-right: 0; }.split-grid, .trace-layout { grid-template-columns: 1fr; }.diff-panel, .span-view { border-left: 0; border-top: 1px solid var(--ops-line); }.knowledge-eval { grid-template-columns: 1fr 1fr; }.knowledge-eval > div { grid-column: 1 / -1; }.slo-grid { grid-template-columns: repeat(2, 1fr); }.slo-grid article:nth-child(2n) { border-right: 0; }.quality-columns { grid-template-columns: 1fr; }.quality-columns > div { border-right: 0; border-bottom: 1px solid var(--ops-line); } }
@media (max-width: 760px) { .ops-summary, .workflow-columns { grid-template-columns: 1fr; }.ops-summary > div { border-right: 0; border-bottom: 1px solid var(--ops-line); }.ops-band > header, .eval-head { padding: 14px; align-items: flex-start; flex-direction: column; }.band-actions { width: 100%; flex-wrap: wrap; }.table-head { display: none; }.table-row { padding: 12px; grid-template-columns: 56px 1fr 1fr; }.table-row > span:nth-child(4) { display: none; }.row-actions { grid-column: 1 / -1; }.dataset-grid { grid-template-columns: 1fr; }.trace-list > button { grid-template-columns: 8px 1fr auto; }.trace-list > button > b { display: none; }.endpoint-list article { padding-block: 12px; grid-template-columns: 9px 1fr; }.endpoint-list article > div { grid-column: 2; }.knowledge-eval, .diagnostic-summary, .check-list article { grid-template-columns: 1fr; }.diagnostic-summary p { min-height: 80px; }.check-list article { padding-block: 14px; }.form-row { grid-template-columns: 1fr; }.slo-grid { grid-template-columns: 1fr; }.slo-grid article { border-right: 0; }.anomaly-strip { align-items: flex-start; flex-wrap: wrap; padding-block: 10px; } }
</style>
