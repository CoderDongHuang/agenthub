<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Check, CircleCheck, Connection, Plus, Promotion, SetUp, VideoPlay } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import api from '../../api'

type NodeType = 'entry' | 'agent' | 'approval' | 'tool' | 'output'
interface FlowNode { id: number; type: NodeType; title: string; detail: string; x: number; y: number; failurePolicy?: string; auditEnabled?: boolean }
interface WorkflowResource { id: number; name: string; description: string; status: string; updated_at: string; config: { version?: number; nodes?: FlowNode[] } }
interface WorkflowExecution { id: number; status: string; started_at: string; result: { steps?: Array<{ nodeId: number; status: string }> } }

const defaultNodes: FlowNode[] = [
  { id: 1, type: 'entry', title: '客户请求', detail: 'Web 对话入口', x: 40, y: 135 },
  { id: 2, type: 'agent', title: '理赔材料助手', detail: '读取材料并判断意图', x: 250, y: 60 },
  { id: 3, type: 'approval', title: '金额复核', detail: '超过 ¥5,000 时触发', x: 475, y: 60 },
  { id: 4, type: 'tool', title: '赔付系统', detail: 'refund.execute', x: 700, y: 60 },
  { id: 5, type: 'agent', title: '结果解释', detail: '生成清晰的客户答复', x: 475, y: 220 },
  { id: 6, type: 'output', title: '返回客户', detail: '记录结果与审计', x: 700, y: 220 },
]

const workflow = ref<WorkflowResource | null>(null)
const executions = ref<WorkflowExecution[]>([])
const nodes = ref<FlowNode[]>([])
const selectedId = ref(1)
const loading = ref(false)
const saving = ref(false)
const running = ref(false)
const activeStep = ref(0)
const selected = computed(() => nodes.value.find(node => node.id === selectedId.value) || nodes.value[0] || defaultNodes[0])
const lastExecution = computed(() => executions.value[0])

const typeLabel: Record<NodeType, string> = { entry: '入口', agent: 'Agent', approval: '审批', tool: '工具', output: '输出' }

async function load() {
  loading.value = true
  try {
    const response = await api.get('/workspace/workflow') as any
    const resources = response.data || []
    workflow.value = resources[0] || null
    nodes.value = workflow.value?.config?.nodes?.length ? workflow.value.config.nodes : defaultNodes
    selectedId.value = nodes.value[0]?.id || 1
    if (workflow.value) await loadExecutions()
  } finally {
    loading.value = false
  }
}
async function loadExecutions() {
  if (!workflow.value) return
  const response = await api.get(`/workspace/workflow/${workflow.value.id}/executions`) as any
  executions.value = response.data || []
}
async function save() {
  if (!workflow.value || saving.value) return
  saving.value = true
  try {
    const response = await api.put(`/workspace/workflow/${workflow.value.id}`, {
      name: workflow.value.name,
      description: workflow.value.description,
      status: 'draft',
      config: { ...workflow.value.config, version: Number(workflow.value.config.version || 1) + 1, nodes: nodes.value },
    }) as any
    workflow.value = response.data
    ElMessage.success('流程草稿已保存到工作区')
  } finally {
    saving.value = false
  }
}
function addApproval() {
  const id = Math.max(0, ...nodes.value.map(node => node.id)) + 1
  nodes.value.push({ id, type: 'approval', title: '新的审批节点', detail: '点击右侧配置触发条件', x: 270, y: 300, failurePolicy: 'stop', auditEnabled: true })
  selectedId.value = id
}
function addNode(type: NodeType, title: string, detail: string) {
  const id = Math.max(0, ...nodes.value.map(node => node.id)) + 1
  nodes.value.push({ id, type, title, detail, x: 270 + (nodes.value.length % 2) * 220, y: 300, failurePolicy: 'stop', auditEnabled: true })
  selectedId.value = id
}
async function runFlow() {
  if (running.value || !workflow.value) return
  running.value = true
  activeStep.value = 0
  try {
    await save()
    const response = await api.post(`/workspace/workflow/${workflow.value.id}/run`) as any
    if (response.code !== 200) throw new Error(response.message)
    const completedIds = new Set((response.data?.steps || [])
      .filter((step: any) => step.status === 'completed').map((step: any) => Number(step.nodeId)))
    activeStep.value = nodes.value.filter(node => completedIds.has(node.id)).length
    await loadExecutions()
    const statusMessages: Record<string, string> = {
      completed: '流程执行完成',
      waiting_for_approval: '流程正在等待人工审批',
      queued: '节点已进入执行队列',
      failed: '流程因无效节点停止',
    }
    const executionStatus = response.data?.status || 'queued'
    const message = statusMessages[executionStatus] || `流程状态：${executionStatus}`
    executionStatus === 'failed' ? ElMessage.error(message) : ElMessage.success(message)
  } catch (error: any) {
    ElMessage.error(error?.message || '流程运行失败')
  } finally {
    running.value = false
  }
}
onMounted(load)
</script>

<template>
  <div class="console-page workflow-page" v-loading="loading">
    <div class="console-page-head">
      <div class="console-page-head__copy"><span>多 Agent 协作</span><h1>流程编排</h1><p>把 Agent、工具、判断与人工审批组成一条可观察、可复用的业务流程。</p></div>
      <div class="console-page-actions"><button class="console-secondary" :disabled="saving" @click="save"><el-icon><Check /></el-icon> {{ saving ? '保存中' : '保存草稿' }}</button><button class="console-primary" :disabled="running || !workflow" @click="runFlow"><el-icon><VideoPlay /></el-icon> {{ running ? '正在执行' : '运行流程' }}</button></div>
    </div>

    <section class="workflow-summary">
      <div><span class="summary-icon sage"><el-icon><Connection /></el-icon></span><strong>{{ workflow?.name || '流程编排' }}</strong><small>{{ nodes.length }} 个节点 · 配置持久化到工作区</small></div>
      <div><span>预计耗时</span><strong>18 秒</strong><small>不含人工审批等待</small></div>
      <div><span>风险节点</span><strong>1 个</strong><small>金额复核会暂停执行</small></div>
      <div><span>最近执行</span><strong>{{ lastExecution?.status || '暂无' }}</strong><small>{{ lastExecution?.started_at || '尚未产生执行记录' }}</small></div>
    </section>

    <div class="studio-shell">
      <aside class="node-palette">
        <div class="studio-title"><span>添加节点</span><small>单击添加到画布</small></div>
        <button @click="addApproval"><el-icon><CircleCheck /></el-icon><span><strong>人工审批</strong><small>在高风险动作前暂停</small></span><el-icon><Plus /></el-icon></button>
        <button @click="addNode('agent', '条件判断', '按字段或意图分流')"><el-icon><SetUp /></el-icon><span><strong>条件判断</strong><small>按字段或意图分流</small></span><el-icon><Plus /></el-icon></button>
        <button @click="addNode('output', '消息通知', '发送到协作渠道')"><el-icon><Promotion /></el-icon><span><strong>消息通知</strong><small>发送到协作渠道</small></span><el-icon><Plus /></el-icon></button>
        <div class="template-note"><strong>常用模板</strong><p>客服转人工、合同审阅、报销核验和周报生成可从场景模板快速创建。</p></div>
      </aside>

      <section class="flow-canvas">
        <div class="canvas-toolbar"><span>{{ workflow?.name || '流程' }} / {{ workflow?.status || 'draft' }}</span><b><i /> 更改后需保存</b></div>
        <div class="canvas-grid">
          <span class="connector c1" /><span class="connector c2" /><span class="connector c3" /><span class="connector c4" /><span class="connector c5" />
          <button v-for="(node, nodeIndex) in nodes" :key="node.id" :class="['flow-node-card', node.type, { selected: selectedId === node.id, passed: activeStep >= nodeIndex + 1 }]" :style="{ left: `${node.x}px`, top: `${node.y}px` }" @click="selectedId = node.id">
            <span>{{ typeLabel[node.type] }}</span><strong>{{ node.title }}</strong><small>{{ node.detail }}</small><i />
          </button>
        </div>
      </section>

      <aside class="property-panel">
        <div class="studio-title"><span>节点设置</span><small>#{{ selected.id }}</small></div>
        <label><span>节点名称</span><input v-model="selected.title" /></label>
        <label><span>说明</span><textarea v-model="selected.detail" rows="3" /></label>
        <label><span>失败处理</span><select v-model="selected.failurePolicy"><option value="stop">停止并通知负责人</option><option value="retry">重试 2 次后继续</option><option value="manual">转人工处理</option></select></label>
        <div class="policy-toggle"><span><strong>记录输入与输出</strong><small>写入审计轨迹</small></span><el-switch v-model="selected.auditEnabled" /></div>
        <div class="property-tip"><el-icon><CircleCheck /></el-icon><p><strong>节点配置有效</strong><span>发布前会再次检查模型、工具权限和审批策略。</span></p></div>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.workflow-summary { display: grid; grid-template-columns: 1.4fr repeat(3, .65fr); border: 1px solid var(--console-line); border-radius: 8px; background: white; overflow: hidden; }
.workflow-summary > div { min-height: 108px; padding: 18px; display: flex; flex-direction: column; border-right: 1px solid var(--console-line); }
.workflow-summary > div:last-child { border-right: 0; }
.workflow-summary > div:first-child { display: grid; grid-template-columns: 44px 1fr; align-content: center; gap: 0 12px; }
.summary-icon { grid-row: 1 / 3; width: 44px; height: 44px; display: grid; place-items: center; border-radius: 8px; background: var(--console-primary-soft); color: var(--console-primary-dark); font-size: 20px; }
.workflow-summary span:not(.summary-icon) { color: var(--console-muted); font-size: 10px; }
.workflow-summary strong { margin-top: auto; font-size: 18px; }
.workflow-summary > div:first-child strong { margin-top: 3px; font-size: 14px; }
.workflow-summary small { margin-top: 5px; color: #8b958f; font-size: 9px; }
.studio-shell { height: 610px; margin-top: 16px; display: grid; grid-template-columns: 220px minmax(650px, 1fr) 250px; border: 1px solid var(--console-line); border-radius: 8px; background: white; overflow: hidden; }
.node-palette, .property-panel { padding: 16px; background: #fbfcfa; }
.node-palette { border-right: 1px solid var(--console-line); }
.property-panel { border-left: 1px solid var(--console-line); }
.studio-title { min-height: 36px; display: flex; justify-content: space-between; align-items: start; }
.studio-title span { font-size: 12px; font-weight: 750; }
.studio-title small { color: #929c96; font-size: 9px; }
.node-palette > button { width: 100%; min-height: 64px; margin-top: 8px; padding: 10px; display: grid; grid-template-columns: 28px 1fr 18px; gap: 7px; align-items: center; border: 1px solid var(--console-line); border-radius: 7px; background: white; color: var(--console-ink); font: inherit; text-align: left; cursor: pointer; }
.node-palette > button:hover { border-color: #a9bbae; background: var(--console-primary-soft); }
.node-palette > button > .el-icon:first-child { color: var(--console-primary); font-size: 18px; }
.node-palette > button span { display: flex; flex-direction: column; gap: 4px; }
.node-palette > button strong { font-size: 10px; }
.node-palette > button small { color: var(--console-muted); font-size: 8px; line-height: 1.4; }
.template-note { margin-top: 22px; padding: 14px; border-radius: 7px; background: var(--console-blue-soft); color: #526c7d; }
.template-note strong { font-size: 10px; }
.template-note p { margin-top: 7px; font-size: 9px; line-height: 1.6; }
.flow-canvas { min-width: 0; display: flex; flex-direction: column; overflow-x: auto; }
.canvas-toolbar { height: 48px; flex: 0 0 48px; padding: 0 16px; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid var(--console-line); color: var(--console-muted); font-size: 10px; }
.canvas-toolbar b { display: flex; align-items: center; gap: 6px; color: #849089; font-size: 9px; font-weight: 500; }
.canvas-toolbar i { width: 6px; height: 6px; border-radius: 50%; background: var(--console-green); }
.canvas-grid { position: relative; width: 900px; min-height: 560px; background-color: #f8faf7; background-image: radial-gradient(#d9e0da 1px, transparent 1px); background-size: 20px 20px; }
.flow-node-card { position: absolute; width: 174px; min-height: 82px; padding: 12px 14px; display: flex; flex-direction: column; align-items: flex-start; border: 1px solid var(--console-line-strong); border-radius: 8px; background: white; color: var(--console-ink); box-shadow: 0 7px 20px rgba(48,66,56,.07); font: inherit; text-align: left; cursor: pointer; }
.flow-node-card > span { color: var(--console-muted); font-size: 8px; }
.flow-node-card strong { margin-top: 8px; font-size: 11px; }
.flow-node-card small { margin-top: 4px; color: #89938d; font-size: 8px; }
.flow-node-card > i { position: absolute; right: 10px; top: 11px; width: 8px; height: 8px; border-radius: 50%; background: #b8c1bb; }
.flow-node-card.agent { border-top: 3px solid var(--console-blue); }
.flow-node-card.approval { border-top: 3px solid var(--console-yellow); }
.flow-node-card.tool { border-top: 3px solid var(--console-coral); }
.flow-node-card.output, .flow-node-card.entry { border-top: 3px solid var(--console-primary); }
.flow-node-card.selected { outline: 3px solid rgba(82,115,99,.14); border-color: var(--console-primary); }
.flow-node-card.passed > i { background: var(--console-green); box-shadow: 0 0 0 4px rgba(86,134,108,.12); }
.connector { position: absolute; z-index: 0; height: 2px; background: #bfc9c1; transform-origin: left center; }
.connector.c1 { left: 214px; top: 177px; width: 78px; transform: rotate(-23deg); }
.connector.c2 { left: 424px; top: 102px; width: 51px; }
.connector.c3 { left: 649px; top: 102px; width: 51px; }
.connector.c4 { left: 560px; top: 144px; width: 120px; transform: rotate(90deg); }
.connector.c5 { left: 649px; top: 262px; width: 51px; }
.property-panel label { display: block; margin-top: 16px; }
.property-panel label > span { display: block; margin-bottom: 7px; color: var(--console-muted); font-size: 9px; font-weight: 700; }
.property-panel input, .property-panel textarea, .property-panel select { width: 100%; padding: 10px; border: 1px solid var(--console-line); border-radius: 6px; outline: 0; background: white; color: var(--console-ink); font: inherit; font-size: 10px; resize: none; }
.property-panel input:focus, .property-panel textarea:focus { border-color: var(--console-primary); }
.policy-toggle { margin-top: 18px; padding: 12px 0; display: flex; justify-content: space-between; align-items: center; border-top: 1px solid var(--console-line); border-bottom: 1px solid var(--console-line); }
.policy-toggle > span { display: flex; flex-direction: column; gap: 4px; }
.policy-toggle strong { font-size: 10px; }
.policy-toggle small { color: var(--console-muted); font-size: 8px; }
.property-tip { margin-top: 18px; padding: 12px; display: flex; gap: 9px; border-radius: 7px; background: var(--console-primary-soft); color: var(--console-primary-dark); }
.property-tip p { display: flex; flex-direction: column; gap: 4px; }
.property-tip strong { font-size: 9px; }
.property-tip span { font-size: 8px; line-height: 1.5; }
@media (max-width: 1200px) { .studio-shell { grid-template-columns: 190px minmax(650px, 1fr); } .property-panel { display: none; } }
@media (max-width: 760px) { .workflow-summary { grid-template-columns: 1fr 1fr; } .workflow-summary > div { border-bottom: 1px solid var(--console-line); } .workflow-summary > div:first-child { grid-column: 1 / -1; } .studio-shell { height: auto; grid-template-columns: 1fr; overflow: visible; } .node-palette { border-right: 0; border-bottom: 1px solid var(--console-line); } .flow-canvas { min-height: 520px; overflow-x: auto; } }
</style>
