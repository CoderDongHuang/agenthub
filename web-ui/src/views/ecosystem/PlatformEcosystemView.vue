<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  Connection, Cpu, DocumentChecked, Download, Files, Key, Lock, MagicStick,
  Monitor, Plus, Promotion, Refresh, Upload,
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../../api'

const { locale } = useI18n()
const en = computed(() => locale.value === 'en-US')
const tx = (zh: string, english: string) => en.value ? english : zh
const activeTab = ref('sdk')
const loading = ref(false)
const overview = ref<any>({ counts: {} })
const packages = ref<any[]>([])
const mcpConnections = ref<any[]>([])
const developerApps = ref<any[]>([])
const mediaJobs = ref<any[]>([])
const reviewQueue = ref<any[]>([])
const workerPools = ref<any[]>([])
const drills = ref<any[]>([])
const portal = ref<any>(null)
const deployment = ref<any>(null)
const result = ref<any>(null)
const reviewVisible = ref(false)
const selectedReview = ref<any>(null)
const reviewForm = ref({ decision: 'approved', notes: '', correctedExtraction: '{}' })
const oneTimeApp = ref<any>(null)

const packageForm = ref({
  packageName: 'sample.echo', version: '1.0.0', packageType: 'tool', visibility: 'private',
  sourceUri: 'registry://private/sample.echo/1.0.0', artifact: 'AgentHub signed sample package',
  entrypoint: 'sample:EchoTool', permissions: '', minPlatformVersion: '0.1.0',
})
const sandboxForm = ref({ timeoutSeconds: 30, memoryMb: 256, cpuCores: 0.5, networkHosts: '', mounts: '' })
const mcpForm = ref({ name: 'Local Python Runtime', direction: 'client', transport: 'http', endpoint: 'http://localhost:8000/mcp', protocolVersion: '2025-03-26' })
const appForm = ref({ appName: 'Local SDK App', apiVersion: 'v1', quotaPerMinute: 60, tenantRoute: 'primary', allowedOperations: ['platform.echo', 'platform.capabilities', 'agent.chat'] })
const gatewayInput = ref('signed request from developer portal')
const gatewayAgentId = ref(1)
const mediaFile = ref<File | null>(null)
const semanticRequested = ref(false)
const scaleForm = ref({ poolName: 'agent-worker', region: 'local-primary', minReplicas: 1, maxReplicas: 10, targetQueueDepth: 10, currentReplicas: 1, queueDepth: 42 })
const drillForm = ref({ drillType: 'dependency_probe', sourceRegion: 'local-primary', targetRegion: 'local-secondary' })

const tabs = computed(() => [
  { name: 'sdk', label: tx('SDK 与私有仓库', 'SDK & Registry'), icon: Files },
  { name: 'mcp', label: 'MCP Client / Server', icon: Connection },
  { name: 'gateway', label: tx('开发者网关', 'Developer Gateway'), icon: Promotion },
  { name: 'multimodal', label: tx('多模态流水线', 'Multimodal Pipeline'), icon: MagicStick },
  { name: 'deployment', label: tx('弹性与容灾', 'Scaling & DR'), icon: Cpu },
  { name: 'supply', label: tx('供应链安全', 'Supply Chain'), icon: Lock },
  { name: 'devkit', label: tx('本地开发套件', 'Local Dev Kit'), icon: Monitor },
])

async function loadAll() {
  loading.value = true
  const requests = await Promise.allSettled([
    api.get('/ecosystem/overview'), api.get('/ecosystem/packages'), api.get('/ecosystem/mcp/connections'),
    api.get('/ecosystem/developer-apps'), api.get('/ecosystem/multimodal/jobs'), api.get('/ecosystem/worker-pools'),
    api.get('/ecosystem/resilience/drills'), api.get('/ecosystem/developer-portal'), api.get('/ecosystem/deployment/plan'),
    api.get('/ecosystem/multimodal/review-queue?status=pending'),
  ])
  const data = requests.map(item => item.status === 'fulfilled' ? (item.value as any).data : null)
  overview.value = data[0] || { counts: {} }
  packages.value = data[1] || []
  mcpConnections.value = data[2] || []
  developerApps.value = data[3] || []
  mediaJobs.value = data[4] || []
  workerPools.value = data[5] || []
  drills.value = data[6] || []
  portal.value = data[7]
  deployment.value = data[8]
  reviewQueue.value = data[9] || []
  loading.value = false
}

async function run(message: string, action: () => Promise<any>, reload = true) {
  try {
    const response = await action()
    result.value = response?.data ?? response
    ElMessage.success(message)
    if (reload) await loadAll()
    return response?.data ?? response
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message || error?.message || tx('操作失败', 'Operation failed'))
    return null
  }
}

function csv(value: string) {
  return value.split(',').map(item => item.trim()).filter(Boolean)
}

function utf8Base64(value: string) {
  const bytes = new TextEncoder().encode(value)
  let binary = ''
  bytes.forEach(byte => { binary += String.fromCharCode(byte) })
  return btoa(binary)
}

async function registerPackage() {
  const form = packageForm.value
  await run(tx('私有包已签名并入库', 'Private package signed and stored'), () => api.post('/ecosystem/packages', {
    packageName: form.packageName, version: form.version, packageType: form.packageType,
    visibility: form.visibility, sourceUri: form.sourceUri, artifactBase64: utf8Base64(form.artifact),
    manifest: { entrypoint: form.entrypoint, permissions: csv(form.permissions), dependencies: {} },
    compatibility: { minPlatformVersion: form.minPlatformVersion },
  }))
}

async function verifyPackage(item: any) {
  await run(tx('制品摘要与签名一致', 'Artifact digest and signature verified'), () => api.post(`/ecosystem/packages/${item.id}/verify`), false)
}

async function scanPackage(item: any) {
  await run(tx('供应链扫描完成', 'Supply-chain scan completed'), () => api.post(`/ecosystem/packages/${item.id}/scan`))
}

async function downloadPackage(item: any) {
  const data = await run(tx('私有制品已读取', 'Private artifact retrieved'), () => api.get(`/ecosystem/packages/${item.id}/artifact`), false)
  if (!data?.contentBase64) return
  const binary = atob(data.contentBase64)
  const bytes = Uint8Array.from(binary, char => char.charCodeAt(0))
  downloadBlob(new Blob([bytes]), data.fileName || 'package.zip')
}

async function evaluateSandbox() {
  const form = sandboxForm.value
  await run(tx('沙箱约束评估完成', 'Sandbox policy evaluated'), () => api.post('/ecosystem/sandbox/evaluate', {
    timeoutSeconds: form.timeoutSeconds, memoryMb: form.memoryMb, cpuCores: form.cpuCores,
    networkHosts: csv(form.networkHosts), mounts: csv(form.mounts),
  }), false)
}

async function saveMcp() {
  await run(tx('MCP 连接已保存', 'MCP connection saved'), () => api.post('/ecosystem/mcp/connections', mcpForm.value))
}

async function probeMcp(item: any) {
  await run(tx('MCP 初始化握手已执行', 'MCP initialize handshake executed'), () => api.post(`/ecosystem/mcp/connections/${item.id}/probe`))
}

async function createDeveloperApp() {
  const data = await run(tx('开发者应用已创建', 'Developer application created'), () => api.post('/ecosystem/developer-apps', appForm.value))
  if (!data?.secret) return
  oneTimeApp.value = data
  await ElMessageBox.alert(`${data.publicKey}\n${data.secret}`, tx('公开标识与密钥仅显示一次', 'Public key and secret shown once'), {
    confirmButtonText: tx('我已记录', 'Recorded'), dangerouslyUseHTMLString: false,
  })
}

async function sha256Hex(bytes: Uint8Array) {
  const digest = await crypto.subtle.digest('SHA-256', Uint8Array.from(bytes).buffer)
  return Array.from(new Uint8Array(digest)).map(byte => byte.toString(16).padStart(2, '0')).join('')
}

function base64Url(bytes: Uint8Array) {
  let binary = ''
  bytes.forEach(byte => { binary += String.fromCharCode(byte) })
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

async function testGateway(operation = 'platform.echo') {
  if (!oneTimeApp.value?.secret) {
    ElMessage.warning(tx('请先创建应用；密钥仅在创建时可用', 'Create an app first; its secret is only available at creation'))
    return
  }
  const path = `/api/gateway/${oneTimeApp.value.apiVersion}/invoke`
  const input = operation === 'agent.chat'
    ? { agentId: gatewayAgentId.value, message: gatewayInput.value }
    : { message: gatewayInput.value }
  const body = JSON.stringify({ operation, input })
  const timestamp = Math.floor(Date.now() / 1000)
  const nonce = crypto.randomUUID().replace(/-/g, '')
  const bytes = new TextEncoder().encode(body)
  const canonical = `POST\n${path}\n${timestamp}\n${nonce}\n${await sha256Hex(bytes)}`
  const secretBytes = Uint8Array.from(new TextEncoder().encode(oneTimeApp.value.secret)).buffer
  const canonicalBytes = Uint8Array.from(new TextEncoder().encode(canonical)).buffer
  const key = await crypto.subtle.importKey('raw', secretBytes, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign'])
  const signed = new Uint8Array(await crypto.subtle.sign('HMAC', key, canonicalBytes))
  const response = await fetch(path, { method: 'POST', headers: {
    'Content-Type': 'application/json', 'X-Developer-Key': oneTimeApp.value.publicKey,
    'X-Timestamp': String(timestamp), 'X-Nonce': nonce, 'X-Signature': base64Url(signed),
  }, body })
  const envelope = await response.json()
  if (!response.ok || envelope.code !== 200) throw new Error(envelope.message || `HTTP ${response.status}`)
  result.value = envelope.data
  ElMessage.success(tx('签名网关调用成功', 'Signed gateway call succeeded'))
  await loadAll()
}

function selectMedia(event: Event) {
  mediaFile.value = (event.target as HTMLInputElement).files?.[0] || null
}

async function extractMedia() {
  if (!mediaFile.value) return ElMessage.warning(tx('请选择文件', 'Select a file'))
  const bytes = new Uint8Array(await mediaFile.value.arrayBuffer())
  let binary = ''
  for (let offset = 0; offset < bytes.length; offset += 8192) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + 8192))
  }
  await run(tx('媒体结构抽取完成', 'Media structure extracted'), () => api.post('/ecosystem/multimodal/extract', {
    fileName: mediaFile.value?.name, mediaType: mediaFile.value?.type,
    contentBase64: btoa(binary), semantic: semanticRequested.value,
  }))
}

async function claimReview(item: any) {
  const claimed = await run(tx('复核任务已认领', 'Review job claimed'), () => api.post(`/ecosystem/multimodal/jobs/${item.id}/claim`))
  if (!claimed) return
  selectedReview.value = claimed
  reviewForm.value = { decision: 'approved', notes: '', correctedExtraction: JSON.stringify(claimed.extraction || {}, null, 2) }
  reviewVisible.value = true
}

async function submitReview() {
  if (!selectedReview.value) return
  let correctedExtraction: any
  try { correctedExtraction = JSON.parse(reviewForm.value.correctedExtraction) }
  catch { return ElMessage.error(tx('修订结果必须是有效 JSON', 'Corrected extraction must be valid JSON')) }
  const completed = await run(tx('人工复核已完成', 'Human review completed'), () => api.post(`/ecosystem/multimodal/jobs/${selectedReview.value.id}/review`, {
    decision: reviewForm.value.decision, notes: reviewForm.value.notes, correctedExtraction,
  }))
  if (completed) reviewVisible.value = false
}

async function scaleWorkers() {
  await run(tx('Worker 弹性计划已计算', 'Worker scaling plan calculated'), () => api.post('/ecosystem/worker-pools/scale-plan', scaleForm.value))
}

async function runDrill() {
  await run(tx('容灾预演完成', 'Resilience dry run completed'), () => api.post('/ecosystem/resilience/drills', drillForm.value))
}

async function downloadHealth() {
  const data = await run(tx('脱敏健康报告已生成', 'Redacted health report generated'), () => api.get('/ecosystem/health-report'), false)
  if (data) downloadBlob(new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' }), 'agenthub-health-report.json')
}

function downloadBlob(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = name
  anchor.click()
  URL.revokeObjectURL(url)
}

onMounted(loadAll)
</script>

<template>
  <div class="console-page ecosystem-page" data-no-ui-translate v-loading="loading">
    <div class="console-page-head">
      <div class="console-page-head__copy"><span>PLATFORM ECOSYSTEM</span><h1>{{ tx('平台生态控制台', 'Platform Ecosystem') }}</h1><p>{{ tx('从签名工具包到 MCP、开发者网关、多模态流水线与弹性部署，统一验证生态接入边界。', 'Validate ecosystem integration boundaries from signed tool packages to MCP, developer gateway, multimodal pipelines, and elastic deployment.') }}</p></div>
      <div class="console-page-actions"><button class="console-secondary" @click="loadAll"><el-icon><Refresh /></el-icon>{{ tx('刷新', 'Refresh') }}</button><button class="console-primary" @click="downloadHealth"><el-icon><Download /></el-icon>{{ tx('健康报告', 'Health report') }}</button></div>
    </div>

    <section class="ecosystem-summary">
      <div><span>{{ tx('签名包', 'Signed packages') }}</span><strong>{{ overview.counts?.packages || 0 }}</strong></div>
      <div><span>{{ tx('健康 MCP', 'Healthy MCP') }}</span><strong>{{ overview.counts?.healthy_mcp || 0 }}</strong></div>
      <div><span>{{ tx('开发者应用', 'Developer apps') }}</span><strong>{{ overview.counts?.developer_apps || 0 }}</strong></div>
      <div><span>{{ tx('媒体任务', 'Media jobs') }}</span><strong>{{ overview.counts?.multimodal_jobs || 0 }}</strong></div>
      <div><span>{{ tx('容灾通过', 'Passed drills') }}</span><strong>{{ overview.counts?.passed_drills || 0 }}</strong></div>
    </section>

    <nav class="ecosystem-tabs" :aria-label="tx('平台生态能力', 'Platform ecosystem capabilities')">
      <button v-for="tab in tabs" :key="tab.name" :class="{ active: activeTab === tab.name }" @click="activeTab = tab.name"><el-icon><component :is="tab.icon" /></el-icon><span>{{ tab.label }}</span></button>
    </nav>

    <section v-if="activeTab === 'sdk'" class="ecosystem-workspace">
      <header><div><h2>{{ tx('工具 SDK、签名包与私有仓库', 'Tool SDK, signed packages and private registry') }}</h2><p>{{ tx('制品正文存入租户私有仓库，摘要、Manifest 和租户签名联合防篡改。', 'Artifact bytes are stored in a tenant-private registry and protected by digest, manifest, and tenant signature.') }}</p></div><el-icon><Files /></el-icon></header>
      <div class="ecosystem-grid two">
        <form @submit.prevent="registerPackage"><h3>{{ tx('发布工具包', 'Publish package') }}</h3><div class="field-row"><el-input v-model="packageForm.packageName" placeholder="package.name" /><el-input v-model="packageForm.version" placeholder="1.0.0" /></div><div class="field-row"><el-select v-model="packageForm.packageType"><el-option value="tool" label="Tool" /><el-option value="plugin" label="Plugin" /><el-option value="mcp" label="MCP" /></el-select><el-select v-model="packageForm.visibility"><el-option value="private" label="Private" /><el-option value="tenant" label="Tenant" /><el-option value="public" label="Public" /></el-select></div><el-input v-model="packageForm.sourceUri" placeholder="registry://private/name/version" /><el-input v-model="packageForm.entrypoint" placeholder="module:ToolClass" /><el-input v-model="packageForm.permissions" :placeholder="tx('权限，逗号分隔', 'Permissions, comma separated')" /><el-input v-model="packageForm.artifact" type="textarea" :rows="3" :placeholder="tx('制品内容（演示文本）', 'Artifact content (demo text)')" /><button class="console-primary" type="submit"><el-icon><Upload /></el-icon>{{ tx('签名并发布', 'Sign & publish') }}</button></form>
        <form @submit.prevent="evaluateSandbox"><h3>{{ tx('执行沙箱策略', 'Execution sandbox policy') }}</h3><div class="field-row"><el-input-number v-model="sandboxForm.timeoutSeconds" :min="1" :max="300" /><el-input-number v-model="sandboxForm.memoryMb" :min="32" :max="2048" /></div><el-input-number v-model="sandboxForm.cpuCores" :min="0.1" :max="4" :step="0.1" /><el-input v-model="sandboxForm.networkHosts" :placeholder="tx('网络白名单主机', 'Network allowlist hosts')" /><el-input v-model="sandboxForm.mounts" :placeholder="tx('只读相对挂载', 'Read-only relative mounts')" /><p class="form-note">{{ tx('默认断网、只读根文件系统、无提权、RuntimeDefault seccomp。', 'Default-deny network, read-only root, no privilege escalation, RuntimeDefault seccomp.') }}</p><button class="console-secondary" type="submit"><el-icon><Lock /></el-icon>{{ tx('评估约束', 'Evaluate constraints') }}</button></form>
      </div>
      <el-table :data="packages"><el-table-column prop="packageName" :label="tx('包', 'Package')" /><el-table-column prop="version" :label="tx('版本', 'Version')" width="100" /><el-table-column prop="visibility" :label="tx('范围', 'Scope')" width="90" /><el-table-column prop="scanStatus" :label="tx('扫描', 'Scan')" width="100" /><el-table-column prop="riskScore" :label="tx('风险', 'Risk')" width="80" /><el-table-column width="220"><template #default="scope"><el-button link @click="verifyPackage(scope.row)">{{ tx('验签', 'Verify') }}</el-button><el-button link @click="scanPackage(scope.row)">{{ tx('扫描', 'Scan') }}</el-button><el-button link @click="downloadPackage(scope.row)">{{ tx('下载', 'Download') }}</el-button></template></el-table-column></el-table>
    </section>

    <section v-else-if="activeTab === 'mcp'" class="ecosystem-workspace">
      <header><div><h2>MCP Client / Server</h2><p>{{ tx('通过 Streamable HTTP JSON-RPC 完成初始化、工具发现和工具调用互通。', 'Interoperate through Streamable HTTP JSON-RPC initialization, tool discovery, and tool calls.') }}</p></div><el-icon><Connection /></el-icon></header>
      <form class="horizontal-form" @submit.prevent="saveMcp"><el-input v-model="mcpForm.name" :placeholder="tx('连接名称', 'Connection name')" /><el-select v-model="mcpForm.direction"><el-option value="client" label="Client" /><el-option value="server" label="Server" /></el-select><el-select v-model="mcpForm.transport"><el-option value="http" label="HTTP" /><el-option value="sse" label="SSE" /><el-option value="stdio" label="stdio" /></el-select><el-input v-model="mcpForm.endpoint" placeholder="http://localhost:8000/mcp" /><button class="console-primary" type="submit"><el-icon><Plus /></el-icon>{{ tx('保存连接', 'Save connection') }}</button></form>
      <el-table :data="mcpConnections"><el-table-column prop="name" :label="tx('连接', 'Connection')" /><el-table-column prop="direction" :label="tx('方向', 'Direction')" /><el-table-column prop="transport" :label="tx('传输', 'Transport')" /><el-table-column prop="protocolVersion" :label="tx('协议版本', 'Protocol')" /><el-table-column prop="status" :label="tx('状态', 'Status')" /><el-table-column width="120"><template #default="scope"><el-button link @click="probeMcp(scope.row)">{{ tx('握手探测', 'Probe') }}</el-button></template></el-table-column></el-table>
    </section>

    <section v-else-if="activeTab === 'gateway'" class="ecosystem-workspace">
      <header><div><h2>{{ tx('Agent API 网关与开发者门户', 'Agent API gateway and developer portal') }}</h2><p>{{ tx('租户路由、API 版本、分钟配额、HMAC 签名和 Nonce 防重放。', 'Tenant routing, API versions, per-minute quotas, HMAC signatures, and nonce replay protection.') }}</p></div><el-icon><Promotion /></el-icon></header>
      <div class="ecosystem-grid two"><form @submit.prevent="createDeveloperApp"><h3>{{ tx('创建开发者应用', 'Create developer app') }}</h3><el-input v-model="appForm.appName" :placeholder="tx('应用名称', 'Application name')" /><div class="field-row"><el-input v-model="appForm.apiVersion" /><el-input-number v-model="appForm.quotaPerMinute" :min="1" :max="100000" /></div><el-input v-model="appForm.tenantRoute" :placeholder="tx('租户路由', 'Tenant route')" /><el-checkbox-group v-model="appForm.allowedOperations"><el-checkbox value="platform.echo">platform.echo</el-checkbox><el-checkbox value="platform.capabilities">platform.capabilities</el-checkbox><el-checkbox value="agent.chat">agent.chat</el-checkbox></el-checkbox-group><button class="console-primary" type="submit"><el-icon><Key /></el-icon>{{ tx('创建并签发密钥', 'Create & issue secret') }}</button></form><form @submit.prevent="testGateway('platform.echo')"><h3>{{ tx('真实签名调用', 'Live signed invocation') }}</h3><el-input-number v-model="gatewayAgentId" :min="1" :aria-label="tx('Agent ID', 'Agent ID')" /><el-input v-model="gatewayInput" type="textarea" :rows="4" /><p class="form-note">{{ tx('创建应用后，本页用 Web Crypto 生成 HMAC；agent.chat 会通过 Runtime 调用已发布 Agent。', 'After app creation, this page generates an HMAC; agent.chat invokes a published Agent through the Runtime.') }}</p><button class="console-secondary" type="submit" :disabled="!oneTimeApp"><el-icon><Promotion /></el-icon>{{ tx('发送签名请求', 'Send signed request') }}</button><button class="console-secondary" type="button" :disabled="!oneTimeApp" @click="testGateway('platform.capabilities')">{{ tx('读取能力', 'Read capabilities') }}</button><button class="console-secondary" type="button" :disabled="!oneTimeApp" @click="testGateway('agent.chat')">{{ tx('调用 Agent', 'Invoke Agent') }}</button></form></div>
      <el-table :data="developerApps"><el-table-column prop="appName" :label="tx('应用', 'Application')" /><el-table-column prop="publicKey" :label="tx('公开标识', 'Public key')" show-overflow-tooltip /><el-table-column prop="apiVersion" :label="tx('版本', 'Version')" /><el-table-column prop="quotaPerMinute" :label="tx('分钟配额', 'Quota/min')" /><el-table-column prop="tenantRoute" :label="tx('路由', 'Route')" /><el-table-column prop="status" :label="tx('状态', 'Status')" /></el-table>
    </section>

    <section v-else-if="activeTab === 'multimodal'" class="ecosystem-workspace">
      <header><div><h2>{{ tx('多模态与结构化抽取', 'Multimodal and structured extraction') }}</h2><p>{{ tx('本地解析基础结构；开启语义理解后，阿里云百炼 Qwen-VL 与 Qwen Omni 执行图片 OCR、视觉理解、音频转写和视频音轨转写。', 'Extracts basic structure locally; with semantic understanding enabled, Alibaba Cloud Qwen-VL and Qwen Omni perform image OCR, visual understanding, audio transcription, and video soundtrack transcription.') }}</p></div><el-icon><MagicStick /></el-icon></header>
      <form class="media-form" @submit.prevent="extractMedia"><input type="file" accept=".txt,.md,.json,.csv,.pdf,.docx,.xlsx,.pptx,image/*,audio/wav" @change="selectMedia" /><el-switch v-model="semanticRequested" :active-text="tx('请求语义理解', 'Request semantic understanding')" /><button class="console-primary" type="submit"><el-icon><Upload /></el-icon>{{ tx('上传并抽取', 'Upload & extract') }}</button></form>
      <el-table :data="mediaJobs"><el-table-column prop="fileName" :label="tx('文件', 'File')" /><el-table-column prop="mediaType" label="MIME" /><el-table-column prop="pipeline" :label="tx('流水线', 'Pipeline')" /><el-table-column prop="provider" :label="tx('供应商', 'Provider')" /><el-table-column prop="status" :label="tx('抽取状态', 'Extraction')" /><el-table-column prop="reviewStatus" :label="tx('复核状态', 'Review status')" /></el-table>
      <h3 class="review-heading">{{ tx('人工复核队列', 'Human review queue') }}</h3>
      <el-table :data="reviewQueue"><el-table-column prop="fileName" :label="tx('工单文件', 'Ticket file')" /><el-table-column prop="mediaType" label="MIME" /><el-table-column prop="pipeline" :label="tx('处理流水线', 'Pipeline')" /><el-table-column prop="provider" :label="tx('供应商', 'Provider')" /><el-table-column width="120"><template #default="scope"><el-button link @click="claimReview(scope.row)">{{ tx('认领复核', 'Claim') }}</el-button></template></el-table-column></el-table>
    </section>

    <section v-else-if="activeTab === 'deployment'" class="ecosystem-workspace">
      <header><div><h2>{{ tx('Kubernetes、Worker 弹性与多地域容灾', 'Kubernetes, worker scaling, and multi-region DR') }}</h2><p>{{ tx('HPA、PDB、网络隔离、拓扑分散和双地域 overlay 已提供；数据库与 Redis 使用外部一致性服务。', 'HPA, PDB, network isolation, topology spread, and two regional overlays are included; PostgreSQL and Redis use external consistency services.') }}</p></div><el-icon><Cpu /></el-icon></header>
      <div class="ecosystem-grid two"><form @submit.prevent="scaleWorkers"><h3>{{ tx('Worker 伸缩计算', 'Worker scaling calculation') }}</h3><div class="field-row"><el-input v-model="scaleForm.poolName" /><el-input v-model="scaleForm.region" /></div><div class="number-grid"><el-input-number v-model="scaleForm.minReplicas" :min="0" /><el-input-number v-model="scaleForm.maxReplicas" :min="1" /><el-input-number v-model="scaleForm.currentReplicas" :min="0" /><el-input-number v-model="scaleForm.queueDepth" :min="0" /></div><button class="console-primary" type="submit">{{ tx('计算期望副本', 'Calculate desired replicas') }}</button></form><form @submit.prevent="runDrill"><h3>{{ tx('容灾安全预演', 'Resilience dry run') }}</h3><el-select v-model="drillForm.drillType"><el-option value="dependency_probe" label="Dependency probe" /><el-option value="regional_failover" label="Regional failover dry run" /><el-option value="worker_recovery" label="Worker recovery" /></el-select><div class="field-row"><el-input v-model="drillForm.sourceRegion" /><el-input v-model="drillForm.targetRegion" /></div><button class="console-secondary" type="submit"><el-icon><DocumentChecked /></el-icon>{{ tx('执行预演', 'Run dry run') }}</button></form></div>
      <div class="deployment-note"><code>{{ deployment?.apply }}</code><span>{{ deployment?.dataPlane }}</span></div>
      <el-table :data="workerPools"><el-table-column prop="poolName" :label="tx('Worker 池', 'Worker pool')" /><el-table-column prop="region" :label="tx('地域', 'Region')" /><el-table-column prop="currentReplicas" :label="tx('当前', 'Current')" /><el-table-column prop="desiredReplicas" :label="tx('期望', 'Desired')" /><el-table-column prop="status" :label="tx('状态', 'Status')" /></el-table>
      <el-table :data="drills"><el-table-column prop="drillType" :label="tx('预演', 'Drill')" /><el-table-column prop="sourceRegion" :label="tx('源地域', 'Source')" /><el-table-column prop="targetRegion" :label="tx('目标地域', 'Target')" /><el-table-column prop="status" :label="tx('状态', 'Status')" /><el-table-column prop="rtoSeconds" label="RTO(s)" /><el-table-column prop="rpoSeconds" label="RPO(s)" /></el-table>
    </section>

    <section v-else-if="activeTab === 'supply'" class="ecosystem-workspace">
      <header><div><h2>{{ tx('插件与 MCP 供应链安全', 'Plugin and MCP supply-chain security') }}</h2><p>{{ tx('验证签名、来源协议、SemVer 兼容、固定依赖与高危权限，风险达到阈值即阻断。', 'Verifies signatures, source schemes, SemVer compatibility, pinned dependencies, and broad permissions; threshold breaches are blocked.') }}</p></div><el-icon><Lock /></el-icon></header>
      <section class="risk-strip"><div><span>BLOCKED</span><strong>{{ packages.filter(item => item.scanStatus === 'blocked').length }}</strong></div><div><span>WARNING</span><strong>{{ packages.filter(item => item.scanStatus === 'warning').length }}</strong></div><div><span>PASSED</span><strong>{{ packages.filter(item => item.scanStatus === 'passed').length }}</strong></div></section>
      <el-table :data="packages"><el-table-column prop="packageName" :label="tx('来源', 'Source')" /><el-table-column prop="sourceUri" label="URI" show-overflow-tooltip /><el-table-column prop="signatureAlgorithm" :label="tx('签名', 'Signature')" /><el-table-column prop="scanStatus" :label="tx('结论', 'Decision')" /><el-table-column prop="riskScore" :label="tx('分数', 'Score')" /><el-table-column width="100"><template #default="scope"><el-button link @click="scanPackage(scope.row)">{{ tx('重扫', 'Rescan') }}</el-button></template></el-table-column></el-table>
    </section>

    <section v-else class="ecosystem-workspace">
      <header><div><h2>{{ tx('本地优先开发套件', 'Local-first development kit') }}</h2><p>{{ tx('一键启动、示例配置、自检和可分享的脱敏健康报告；真实密钥永不写入报告。', 'One-command startup, sample configuration, self-checks, and shareable redacted health reports; real secrets never enter reports.') }}</p></div><el-icon><Monitor /></el-icon></header>
      <div class="devkit-grid"><article><span>START</span><code>powershell -File scripts/dev.ps1 up</code><p>{{ tx('生成本地随机服务密钥并启动 PostgreSQL、Redis、Java、Python 与 Web。', 'Generates local random service secrets and starts PostgreSQL, Redis, Java, Python, and Web.') }}</p></article><article><span>CHECK</span><code>powershell -File scripts/dev.ps1 check</code><p>{{ tx('核验 Docker、Java、Maven、Node、Python 环境与依赖。', 'Checks Docker, Java, Maven, Node, Python, and dependencies.') }}</p></article><article><span>REPORT</span><code>powershell -File scripts/health-report.ps1</code><p>{{ tx('只输出状态、计数、路径和错误类型，不输出凭据值。', 'Outputs status, counts, paths, and error types without credential values.') }}</p></article></div>
      <div class="portal-spec"><pre>{{ JSON.stringify(portal, null, 2) }}</pre><button class="console-primary" @click="downloadHealth"><el-icon><Download /></el-icon>{{ tx('下载脱敏报告', 'Download redacted report') }}</button></div>
    </section>

    <aside v-if="result" class="ecosystem-result"><header><span>{{ tx('最近执行结果', 'Latest execution result') }}</span><button aria-label="Close" @click="result = null">×</button></header><pre>{{ JSON.stringify(result, null, 2) }}</pre></aside>
    <el-dialog v-model="reviewVisible" :title="tx('多模态人工复核', 'Multimodal human review')" width="680px"><el-form label-position="top"><el-form-item :label="tx('结论', 'Decision')"><el-segmented v-model="reviewForm.decision" :options="[{ label: tx('批准', 'Approve'), value: 'approved' }, { label: tx('驳回', 'Reject'), value: 'rejected' }]" /></el-form-item><el-form-item :label="tx('复核备注', 'Review notes')"><el-input v-model="reviewForm.notes" /></el-form-item><el-form-item :label="tx('修订后的结构化结果', 'Corrected structured extraction')"><el-input v-model="reviewForm.correctedExtraction" type="textarea" :rows="14" /></el-form-item></el-form><template #footer><el-button @click="reviewVisible = false">{{ tx('取消', 'Cancel') }}</el-button><el-button type="primary" @click="submitReview">{{ tx('完成复核', 'Complete review') }}</el-button></template></el-dialog>
  </div>
</template>

<style scoped>
.ecosystem-page { position: relative; }
.ecosystem-summary { margin-bottom: 14px; display: grid; grid-template-columns: repeat(5,minmax(0,1fr)); border: 1px solid var(--console-line); border-radius: 8px; background: var(--console-panel); overflow: hidden; }
.ecosystem-summary div { min-height: 76px; padding: 14px 16px; display: flex; flex-direction: column; justify-content: space-between; border-right: 1px solid var(--console-line); }.ecosystem-summary div:last-child { border-right: 0; }.ecosystem-summary span { color: var(--console-muted); font-size: 9px; }.ecosystem-summary strong { color: var(--console-ink); font: 22px ui-monospace,monospace; }
.ecosystem-tabs { margin-bottom: 14px; padding: 4px; display: grid; grid-template-columns: repeat(7,minmax(0,1fr)); gap: 3px; border: 1px solid var(--console-line); border-radius: 8px; background: var(--console-panel-soft); }.ecosystem-tabs button { min-height: 48px; padding: 6px; display: flex; align-items: center; justify-content: center; gap: 7px; border: 0; border-radius: 5px; background: transparent; color: var(--console-muted); font: inherit; font-size: 9px; cursor: pointer; }.ecosystem-tabs button.active { background: var(--console-panel); color: var(--console-ink); box-shadow: inset 0 0 0 1px var(--console-accent); }
.ecosystem-workspace { border: 1px solid var(--console-line); border-radius: 8px; background: var(--console-panel); overflow: hidden; }.ecosystem-workspace > header { min-height: 82px; padding: 16px 18px; display: flex; align-items: center; justify-content: space-between; gap: 20px; border-bottom: 1px solid var(--console-line); }.ecosystem-workspace > header h2 { margin: 0; font-size: 15px; }.ecosystem-workspace > header p { max-width: 780px; margin: 6px 0 0; color: var(--console-muted); font-size: 9px; line-height: 1.6; }.ecosystem-workspace > header > .el-icon { color: var(--console-accent); font-size: 24px; }
.ecosystem-grid { display: grid; border-bottom: 1px solid var(--console-line); }.ecosystem-grid.two { grid-template-columns: repeat(2,minmax(0,1fr)); }.ecosystem-grid form { min-width: 0; padding: 16px; display: flex; flex-direction: column; gap: 10px; border-right: 1px solid var(--console-line); }.ecosystem-grid form:last-child { border-right: 0; } form h3 { margin: 0 0 3px; color: var(--console-ink); font-size: 11px; }.form-note { margin: 0; color: var(--console-muted); font-size: 8px; line-height: 1.6; }.field-row,.number-grid { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 8px; }.number-grid { grid-template-columns: repeat(4,minmax(0,1fr)); }
.horizontal-form,.media-form { padding: 16px; display: grid; grid-template-columns: 1fr 140px 140px minmax(220px,2fr) auto; gap: 9px; align-items: center; border-bottom: 1px solid var(--console-line); }.media-form { grid-template-columns: minmax(260px,1fr) auto auto; }.media-form input { min-width: 0; color: var(--console-ink); font-size: 9px; }
.deployment-note { padding: 12px 16px; display: flex; gap: 14px; align-items: center; justify-content: space-between; border-bottom: 1px solid var(--console-line); background: var(--console-panel-soft); color: var(--console-muted); font-size: 8px; }.deployment-note code { color: var(--console-accent); }
.risk-strip { display: grid; grid-template-columns: repeat(3,1fr); border-bottom: 1px solid var(--console-line); }.risk-strip div { min-height: 92px; padding: 16px; display: flex; justify-content: space-between; align-items: flex-end; border-right: 1px solid var(--console-line); }.risk-strip div:last-child { border: 0; }.risk-strip span { color: var(--console-muted); font: 8px ui-monospace,monospace; }.risk-strip strong { font: 28px ui-monospace,monospace; }
.review-heading { margin: 0; padding: 14px 16px; border-top: 1px solid var(--console-line); border-bottom: 1px solid var(--console-line); font-size: 11px; }
.devkit-grid { display: grid; grid-template-columns: repeat(3,1fr); border-bottom: 1px solid var(--console-line); }.devkit-grid article { min-height: 180px; padding: 20px; border-right: 1px solid var(--console-line); }.devkit-grid article:last-child { border: 0; }.devkit-grid span { color: var(--console-accent); font: 8px ui-monospace,monospace; }.devkit-grid code { display: block; margin-top: 24px; padding: 10px; background: #0c1012; color: #8fe1b4; font-size: 8px; overflow-wrap: anywhere; }.devkit-grid p { margin-top: 15px; color: var(--console-muted); font-size: 9px; line-height: 1.7; }.portal-spec { padding: 16px; display: grid; grid-template-columns: 1fr auto; gap: 16px; align-items: end; }.portal-spec pre { max-height: 260px; margin: 0; padding: 14px; overflow: auto; background: #0c1012; color: #8fe1b4; font: 8px/1.55 ui-monospace,monospace; white-space: pre-wrap; word-break: break-word; }
.ecosystem-result { position: fixed; z-index: 70; right: 20px; bottom: 18px; width: min(470px,calc(100vw - 32px)); max-height: 42vh; border: 1px solid var(--console-line-strong); border-radius: 8px; background: #0c1012; box-shadow: var(--console-shadow); overflow: hidden; }.ecosystem-result header { min-height: 38px; padding: 0 10px 0 13px; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid var(--console-line); color: #dce4df; font-size: 9px; }.ecosystem-result header button { width: 28px; height: 28px; border: 0; background: transparent; color: #98a29c; font-size: 18px; cursor: pointer; }.ecosystem-result pre { max-height: calc(42vh - 39px); margin: 0; padding: 13px; overflow: auto; color: #8fe1b4; font: 8px/1.55 ui-monospace,monospace; white-space: pre-wrap; word-break: break-word; }
@media (max-width: 1100px) { .ecosystem-tabs { grid-template-columns: repeat(4,1fr); }.ecosystem-summary { grid-template-columns: repeat(3,1fr); }.horizontal-form { grid-template-columns: repeat(2,minmax(0,1fr)); }.number-grid { grid-template-columns: repeat(2,1fr); } }
@media (max-width: 720px) { .ecosystem-summary { grid-template-columns: repeat(2,1fr); }.ecosystem-tabs { grid-template-columns: repeat(2,1fr); }.ecosystem-tabs button { justify-content: flex-start; padding-inline: 10px; }.ecosystem-grid.two,.horizontal-form,.media-form,.devkit-grid,.portal-spec { grid-template-columns: 1fr; }.ecosystem-grid form,.devkit-grid article { border-right: 0; border-bottom: 1px solid var(--console-line); }.field-row,.number-grid { grid-template-columns: 1fr; }.ecosystem-workspace > header,.deployment-note { align-items: flex-start; flex-direction: column; } }
</style>
