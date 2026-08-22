<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Check, Close, Document, Refresh, Timer } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../../api'

const activeTab = ref<'pending' | 'my'>('pending')
const pendingList = ref<any[]>([])
const myList = ref<any[]>([])
const loading = ref(false)
const selectedId = ref<number | null>(null)
const activeList = computed(() => activeTab.value === 'pending' ? pendingList.value : myList.value)
const selected = computed(() => activeList.value.find(item => item.id === selectedId.value) || activeList.value[0] || null)

function formatTime(value?: string) {
  return value?.substring(0, 16)?.replace('T', ' ') || '-'
}

async function fetchData() {
  loading.value = true
  try {
    const [pendingResponse, myResponse] = await Promise.all([
      api.get('/approvals/pending?size=50') as any,
      api.get('/approvals/my?size=50') as any,
    ])
    pendingList.value = pendingResponse.data?.content || []
    myList.value = myResponse.data?.content || []
    if (!selectedId.value && activeList.value.length) selectedId.value = activeList.value[0].id
  } finally {
    loading.value = false
  }
}

async function approve(id: number) {
  try {
    await ElMessageBox.confirm('批准后，Python 运行时将继续执行这个工具调用。', '批准执行', { type: 'warning' })
    await api.put(`/approvals/${id}/approve`)
    ElMessage.success('审批已通过')
    selectedId.value = null
    await fetchData()
  } catch (error) {
    if (error !== 'cancel') ElMessage.error('审批失败')
  }
}

async function reject(id: number) {
  try {
    const result = await ElMessageBox.prompt('说明拒绝原因，内容会写入审计记录。', '拒绝执行', { inputPlaceholder: '例如：缺少业务负责人确认', inputValidator: value => Boolean(value?.trim()) || '请输入拒绝原因' })
    await api.put(`/approvals/${id}/reject`, { reason: result.value })
    ElMessage.success('已拒绝执行')
    selectedId.value = null
    await fetchData()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error('操作失败')
  }
}

watch(activeTab, () => {
  selectedId.value = activeList.value[0]?.id || null
})

onMounted(fetchData)
</script>

<template>
  <div class="console-page approvals-page" v-loading="loading">
    <div class="console-page-head">
      <div class="console-page-head__copy"><span>人工决策</span><h1>审批中心</h1><p>在高风险工具真正执行前，核对请求上下文并作出决策。</p></div>
      <div class="console-page-actions"><button class="console-icon-button" aria-label="刷新" @click="fetchData"><el-icon><Refresh /></el-icon></button></div>
    </div>

    <section class="approval-stats"><div class="approval-stat urgent"><span>待处理</span><strong>{{ pendingList.length }}</strong><small>等待处理</small></div><div class="approval-stat"><span>我的申请</span><strong>{{ myList.length }}</strong><small>由当前账号发起</small></div><div class="approval-rule"><el-icon><Timer /></el-icon><span>执行卡点</span><strong>审批期间，运行时保持暂停</strong><small>决策完成后继续或终止执行</small></div></section>

    <div class="approval-workspace">
      <section class="approval-queue">
        <div class="queue-tabs"><button :class="{ active: activeTab === 'pending' }" @click="activeTab = 'pending'">待我处理 <b>{{ pendingList.length }}</b></button><button :class="{ active: activeTab === 'my' }" @click="activeTab = 'my'">我发起的 <b>{{ myList.length }}</b></button></div>
        <div v-if="activeList.length" class="queue-list"><button v-for="item in activeList" :key="item.id" :class="{ active: selected?.id === item.id }" @click="selectedId = item.id"><span class="queue-risk" :class="item.status"><i />{{ item.status || 'pending' }}</span><strong>{{ item.toolName || '未知工具' }}</strong><p>{{ item.reason }}</p><small>#{{ item.id }} · {{ formatTime(item.createdAt) }}</small></button></div>
        <div v-else class="queue-empty"><el-icon><Document /></el-icon><strong>当前队列为空</strong><span>{{ activeTab === 'pending' ? '没有等待处理的高风险调用。' : '当前账号还没有发起审批。' }}</span></div>
      </section>

      <section v-if="selected" class="approval-detail">
        <div class="detail-head"><div><span>申请 #{{ selected.id }}</span><h2>{{ selected.toolName || '工具调用审批' }}</h2></div><b :class="selected.status"><i />{{ selected.status?.toUpperCase() }}</b></div>
        <div class="detail-grid"><div><span>Agent</span><strong>{{ selected.agentId || '-' }}</strong></div><div><span>会话</span><strong>{{ selected.sessionId || '-' }}</strong></div><div><span>申请人</span><strong>#{{ selected.requesterId || '-' }}</strong></div><div><span>申请时间</span><strong>{{ formatTime(selected.createdAt) }}</strong></div></div>
        <div class="detail-section"><span>申请原因</span><p>{{ selected.reason || '未提供申请原因。' }}</p></div>
        <div class="detail-section context"><span>执行上下文</span><pre>{{ selected.context || '{\n  "message": "No execution context provided"\n}' }}</pre></div>
        <div class="decision-notice"><el-icon><Timer /></el-icon><p><strong>这是执行前审批</strong><span>批准会继续调用工具；拒绝会终止本次执行并记录原因。</span></p></div>
        <div v-if="selected.status === 'pending' && activeTab === 'pending'" class="decision-actions"><button class="reject" @click="reject(selected.id)"><el-icon><Close /></el-icon> 拒绝执行</button><button class="approve" @click="approve(selected.id)"><el-icon><Check /></el-icon> 批准并继续</button></div>
      </section>
      <section v-else class="approval-placeholder"><el-icon><Document /></el-icon><strong>选择一个审批请求</strong><span>右侧将展示完整上下文与决策入口。</span></section>
    </div>
  </div>
</template>

<style scoped>
.approval-stats { display: grid; grid-template-columns: 170px 170px 1fr; border: 1px solid var(--console-line); background: white; }
.approval-stat { min-height: 124px; padding: 18px; display: flex; flex-direction: column; border-right: 1px solid var(--console-line); }
.approval-stat > span, .approval-rule > span { color: #858b82; font-family: ui-monospace, monospace; font-size: 12px; }
.approval-stat strong { margin-top: auto; font-size: 24px; }
.approval-stat small { margin-top: 4px; color: #8c9289; font-size: 12px; }
.approval-stat.urgent strong { color: var(--console-orange); }
.approval-rule { padding: 20px 24px; display: grid; grid-template-columns: 34px 1fr; align-content: center; }
.approval-rule .el-icon { grid-row: 1 / 4; color: var(--console-orange); font-size: 21px; }
.approval-rule strong { margin-top: 7px; font-size: 13px; }
.approval-rule small { margin-top: 5px; color: var(--console-muted); font-size: 12px; }
.approval-workspace { min-height: 590px; margin-top: 16px; display: grid; grid-template-columns: 360px 1fr; border: 1px solid var(--console-line); background: white; }
.approval-queue { border-right: 1px solid var(--console-line); background: #e9ebe5; }
.queue-tabs { height: 52px; padding: 6px; display: grid; grid-template-columns: 1fr 1fr; border-bottom: 1px solid var(--console-line); }
.queue-tabs button { border: 0; background: transparent; color: #777d74; font: inherit; font-size: 12px; cursor: pointer; }
.queue-tabs button.active { background: var(--console-ink); color: white; }
.queue-tabs b { margin-left: 5px; color: var(--console-orange); }
.queue-list > button { width: 100%; min-height: 126px; padding: 18px; display: flex; flex-direction: column; align-items: flex-start; border: 0; border-bottom: 1px solid var(--console-line); background: transparent; color: var(--console-ink); font: inherit; text-align: left; cursor: pointer; }
.queue-list > button.active { background: white; box-shadow: inset 3px 0 0 var(--console-orange); }
.queue-risk { display: flex; align-items: center; gap: 6px; color: #888e85; font-family: ui-monospace, monospace; font-size: 12px; text-transform: uppercase; }
.queue-risk i { width: 6px; height: 6px; background: var(--console-yellow); }
.queue-risk.approved i { background: var(--console-green); }
.queue-risk.rejected i { background: var(--console-red); }
.queue-list strong { margin-top: 11px; font-size: 12px; }
.queue-list p { width: 100%; margin-top: 7px; overflow: hidden; color: var(--console-muted); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.queue-list small { margin-top: auto; color: #92988f; font-size: 12px; }
.queue-empty, .approval-placeholder { min-height: 420px; display: flex; flex-direction: column; align-items: center; justify-content: center; text-align: center; color: #8d938a; }
.queue-empty .el-icon, .approval-placeholder .el-icon { font-size: 28px; }
.queue-empty strong, .approval-placeholder strong { margin-top: 13px; color: var(--console-ink); font-size: 12px; }
.queue-empty span, .approval-placeholder span { margin-top: 6px; font-size: 12px; }
.approval-detail { padding: 28px; }
.detail-head { display: flex; justify-content: space-between; align-items: flex-start; }
.detail-head span, .detail-section > span { color: var(--console-orange); font-family: ui-monospace, monospace; font-size: 12px; }
.detail-head h2 { margin-top: 7px; font-size: 20px; }
.detail-head > b { padding: 5px 8px; display: flex; align-items: center; gap: 6px; border: 1px solid var(--console-line); color: #7a8077; font-family: ui-monospace, monospace; font-size: 12px; }
.detail-head > b i { width: 6px; height: 6px; background: var(--console-yellow); }
.detail-head > b.approved i { background: var(--console-green); }
.detail-head > b.rejected i { background: var(--console-red); }
.detail-grid { margin-top: 28px; display: grid; grid-template-columns: repeat(4, 1fr); border-top: 1px solid var(--console-line); border-left: 1px solid var(--console-line); }
.detail-grid div { min-height: 76px; padding: 13px; display: flex; flex-direction: column; justify-content: flex-end; border-right: 1px solid var(--console-line); border-bottom: 1px solid var(--console-line); background: #f6f7f3; }
.detail-grid span { color: #8b9188; font-family: ui-monospace, monospace; font-size: 6px; }
.detail-grid strong { margin-top: 7px; overflow: hidden; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.detail-section { margin-top: 24px; }
.detail-section p { margin-top: 9px; color: var(--console-muted); font-size: 12px; line-height: 1.7; }
.detail-section pre { max-height: 150px; margin: 9px 0 0; padding: 16px; overflow: auto; border: 1px solid var(--console-line); background: #171916; color: #aeb4ab; font-family: ui-monospace, monospace; font-size: 12px; line-height: 1.6; white-space: pre-wrap; }
.decision-notice { margin-top: 22px; padding: 14px; display: flex; gap: 11px; background: #fff5e1; color: #8d6514; }
.decision-notice .el-icon { flex: 0 0 auto; margin-top: 2px; }
.decision-notice p { display: flex; flex-direction: column; gap: 4px; }
.decision-notice strong { font-size: 12px; }
.decision-notice span { font-size: 12px; }
.decision-actions { margin-top: 22px; display: grid; grid-template-columns: 1fr 1.3fr; gap: 8px; }
.decision-actions button { min-height: 44px; display: flex; align-items: center; justify-content: center; gap: 7px; border: 1px solid var(--console-line); background: white; font: inherit; font-size: 12px; font-weight: 800; cursor: pointer; }
.decision-actions button.reject { color: var(--console-red); }
.decision-actions button.approve { border-color: var(--console-ink); background: var(--console-ink); color: white; }
.decision-actions button.approve:hover { background: var(--console-orange); border-color: var(--console-orange); }
@media (max-width: 900px) { .approval-workspace { grid-template-columns: 1fr; } .approval-queue { border-right: 0; border-bottom: 1px solid var(--console-line); } }
@media (max-width: 640px) { .approval-stats { grid-template-columns: 1fr 1fr; } .approval-rule { grid-column: 1 / -1; border-top: 1px solid var(--console-line); } .detail-grid { grid-template-columns: 1fr 1fr; } .decision-actions { grid-template-columns: 1fr; } }
</style>
