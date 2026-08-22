<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ArrowLeft, Check, Close, Connection, Document, Lock, Promotion, Setting, Timer } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import MarkdownIt from 'markdown-it'
import api, { getCsrfHeaders } from '../../api'
import { usePreferences } from '../../preferences'

interface Message {
  role: 'user' | 'assistant' | 'tool_start' | 'tool_end' | 'error' | 'system'
  content: string
  toolName?: string
  time?: string
  failed?: boolean
}

const route = useRoute()
const router = useRouter()
const { locale } = useI18n()
const preferences = usePreferences()
const agentId = route.params.id as string
const apiBaseUrl = import.meta.env.VITE_API_URL || '/api'
const messages = ref<Message[]>([])
const inputText = ref('')
const sending = ref(false)
const agent = ref<any>(null)
const chatContainer = ref<HTMLElement | null>(null)
const sessionId = ref(`web-${agentId}-${Date.now()}`)
const pendingApprovals = ref<any[]>([])
const approvalBusy = ref<number | null>(null)
const expandedApprovalIds = ref<Set<number>>(new Set())
const markdown = new MarkdownIt({ html: false, linkify: true, breaks: true })

const traceEvents = computed(() => messages.value.filter(message => ['tool_start', 'tool_end', 'error'].includes(message.role)))
const userRequests = computed(() => messages.value.filter(message => message.role === 'user'))
const turnCount = computed(() => userRequests.value.length)
const toolCallCount = computed(() => traceEvents.value.filter(event => event.role === 'tool_start').length)
const composerHint = computed(() => preferences.sendShortcut === 'ctrl-enter'
  ? (locale.value === 'en-US' ? 'Ctrl/Cmd + Enter to send · Enter for new line' : 'Ctrl/Cmd + Enter 发送 · Enter 换行')
  : (locale.value === 'en-US' ? 'Enter to send · Shift + Enter for new line' : 'Enter 发送 · Shift + Enter 换行'))

function now() { return new Date().toLocaleTimeString('zh-CN', { hour12: false }) }
function renderAssistantMessage(content: string) { return markdown.render(content || '') }
function formatApprovalContext(value: unknown) {
  if (!value) return '{}'
  if (typeof value === 'string') return value
  return JSON.stringify(value, null, 2)
}
function approvalContextLines(value: unknown) { return formatApprovalContext(value).split('\n').length }
function isApprovalCollapsed(item: any) {
  return preferences.collapseLargeJson && approvalContextLines(item.context) > preferences.jsonLineThreshold && !expandedApprovalIds.value.has(item.id)
}
function toggleApprovalContext(id: number) {
  const next = new Set(expandedApprovalIds.value)
  next.has(id) ? next.delete(id) : next.add(id)
  expandedApprovalIds.value = next
}
async function fetchAgent() {
  try {
    const response = await api.get(`/agents/${agentId}`) as any
    agent.value = response.data
    messages.value.push({ role: 'system', content: `${agent.value.name} 已就绪。输入真实业务请求以检查回复、工具调用和审批卡点。`, time: now() })
  } catch {
    ElMessage.error('Agent 不存在')
    router.push('/console/agents')
  }
}
async function fetchApprovals() {
  try {
    const response = await api.get('/approvals/pending?size=50') as any
    const incoming = response.data?.content || []
    pendingApprovals.value = incoming.filter((item: any) => String(item.agentId || '') === String(agentId))
  } catch { pendingApprovals.value = [] }
}
async function approve(item: any) {
  try {
    await ElMessageBox.confirm('批准后，运行时将继续执行这个高风险工具。', '批准执行', { type: 'warning' })
    approvalBusy.value = item.id
    await api.put(`/approvals/${item.id}/approve`)
    ElMessage.success('已批准，运行时将继续执行')
    await fetchApprovals()
  } catch (error) { if (error !== 'cancel') ElMessage.error('审批失败') }
  finally { approvalBusy.value = null }
}
async function reject(item: any) {
  try {
    const result = await ElMessageBox.prompt('说明拒绝原因，内容会进入审计记录。', '拒绝执行', { inputValidator: value => Boolean(value?.trim()) || '请输入拒绝原因' })
    approvalBusy.value = item.id
    await api.put(`/approvals/${item.id}/reject`, { reason: result.value })
    ElMessage.success('已拒绝并终止本次工具执行')
    await fetchApprovals()
  } catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error('操作失败') }
  finally { approvalBusy.value = null }
}
function scrollToBottom() { nextTick(() => { if (chatContainer.value) chatContainer.value.scrollTop = chatContainer.value.scrollHeight }) }
function delay(ms: number) { return new Promise(resolve => window.setTimeout(resolve, ms)) }
async function appendStreamText(data: string, assistantMessage: Message) {
  const profiles = {
    instant: { size: Number.MAX_SAFE_INTEGER, wait: 0 }, fast: { size: 24, wait: 4 },
    standard: { size: 8, wait: 12 }, realistic: { size: 3, wait: 28 },
  }
  const profile = profiles[preferences.streamingSpeed]
  for (let index = 0; index < data.length; index += profile.size) {
    assistantMessage.content += data.slice(index, index + profile.size)
    if (profile.wait && index + profile.size < data.length) { scrollToBottom(); await delay(profile.wait) }
  }
}
async function handleSSE(event: string, data: string, assistantMessage: Message) {
  if (event === 'text') await appendStreamText(data, assistantMessage)
  if (event === 'tool_start') {
    messages.value.push({ role: 'tool_start', content: data, toolName: data, time: now() })
    window.setTimeout(fetchApprovals, 350)
  }
  if (event === 'tool_end') messages.value.push({ role: 'tool_end', content: data, time: now() })
  if (event === 'error') {
    assistantMessage.failed = true
    if (!assistantMessage.content) {
      const index = messages.value.indexOf(assistantMessage)
      if (index >= 0) messages.value.splice(index, 1)
    }
    messages.value.push({ role: 'error', content: data, time: now() })
  }
  if ((event === 'done' || event === 'complete') && !assistantMessage.content && !assistantMessage.failed) assistantMessage.content = '本次执行没有返回文本结果。'
}
async function parseSSEBlock(block: string, assistantMessage: Message) {
  let eventName = 'message'
  const dataLines: string[] = []
  for (const line of block.split('\n')) {
    if (line.startsWith('event:')) eventName = line.slice(6).trim()
    if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''))
  }
  if (!dataLines.length) return eventName
  const rawData = dataLines.join('\n')
  let content = rawData
  try {
    const payload = JSON.parse(rawData)
    content = typeof payload?.content === 'string' ? payload.content : rawData
  } catch { content = rawData }
  await handleSSE(eventName, content, assistantMessage)
  return eventName
}
async function sendMessage() {
  const text = inputText.value.trim()
  if (!text || sending.value) return
  messages.value.push({ role: 'user', content: text, time: now() })
  inputText.value = ''
  sending.value = true
  const assistantMessage: Message = { role: 'assistant', content: '', time: now() }
  messages.value.push(assistantMessage)
  scrollToBottom()
  try {
    const csrfHeaders = await getCsrfHeaders()
    const response = await fetch(`${apiBaseUrl}/agents/${agentId}/chat`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', ...csrfHeaders }, credentials: 'include',
      body: JSON.stringify({ message: text, sessionId: sessionId.value }),
    })
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    const reader = response.body?.getReader()
    if (!reader) throw new Error('流式响应不可用')
    const decoder = new TextDecoder()
    let buffer = ''
    let completed = false
    while (true) {
      const { done, value } = await reader.read()
      buffer += done ? decoder.decode() : decoder.decode(value, { stream: true })
      buffer = buffer.replace(/\r\n/g, '\n')
      const blocks = buffer.split('\n\n')
      buffer = blocks.pop() || ''
      for (const block of blocks) {
        const eventName = await parseSSEBlock(block, assistantMessage)
        if (eventName === 'done' || eventName === 'complete') completed = true
      }
      scrollToBottom()
      if (done) break
    }
    if (buffer.trim()) {
      const eventName = await parseSSEBlock(buffer, assistantMessage)
      if (eventName === 'done' || eventName === 'complete') completed = true
    }
    if (!completed) throw new Error('流式连接提前结束')
  } catch (error: any) {
    if (assistantMessage.content) assistantMessage.content += `\n\n[执行中断：${error.message}]`
    else { messages.value.pop(); messages.value.push({ role: 'error', content: `请求失败：${error.message}`, time: now() }) }
  } finally {
    sending.value = false
    await fetchApprovals()
    scrollToBottom()
  }
}
function handleKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter') return
  const ctrlOrMeta = event.ctrlKey || event.metaKey
  const shouldSend = preferences.sendShortcut === 'ctrl-enter' ? ctrlOrMeta : !event.shiftKey
  if (shouldSend) { event.preventDefault(); sendMessage() }
}
function clearSession() {
  messages.value = [{ role: 'system', content: '新会话已创建，历史上下文已清空。', time: now() }]
  sessionId.value = `web-${agentId}-${Date.now()}`
  pendingApprovals.value = []
}
function focusRequest(index: number) {
  const rows = chatContainer.value?.querySelectorAll('.message-row.user')
  rows?.[index]?.scrollIntoView({ behavior: 'smooth', block: 'center' })
}
onMounted(async () => { await Promise.all([fetchAgent(), fetchApprovals()]) })
</script>

<template>
  <div class="console-page chat-lab">
    <header class="lab-header">
      <button class="back-button" @click="router.push(`/console/agents/${agentId}`)"><el-icon><ArrowLeft /></el-icon><span>Agent 详情</span></button>
      <div class="lab-agent"><span>PLAYGROUND / LIVE TRACE</span><h1>{{ agent?.name || 'Agent 调试台' }}</h1></div>
      <div class="lab-status"><span><i :class="agent?.status" />{{ agent?.status || 'loading' }}</span><b>{{ agent?.model || '-' }}</b><button @click="clearSession">新会话</button></div>
    </header>

    <div class="lab-workspace">
      <aside class="request-panel">
        <div class="request-head"><span>REQUESTS</span><strong>会话请求</strong><small>{{ turnCount }} 轮</small></div>
        <button class="new-request" @click="clearSession"><span>+</span> 新建会话</button>
        <div class="request-list">
          <button v-for="(request, index) in userRequests" :key="index" @click="focusRequest(index)"><span>{{ String(index + 1).padStart(2, '0') }}</span><p><strong>{{ request.content }}</strong><small>{{ request.time }} · 已执行</small></p><i /></button>
          <div v-if="!userRequests.length" class="request-empty"><el-icon><Document /></el-icon><strong>暂无请求</strong><span>发送消息后，请求会按轮次出现在这里。</span></div>
        </div>
        <div class="request-config"><span>会话上下文</span><dl><div><dt>模型</dt><dd>{{ agent?.model || '-' }}</dd></div><div><dt>温度</dt><dd>{{ agent?.temperature ?? 0.7 }}</dd></div><div><dt>会话 ID</dt><dd>{{ sessionId.slice(-8) }}</dd></div></dl></div>
      </aside>

      <section class="conversation-panel">
        <div class="conversation-toolbar"><div><i :class="{ active: !sending, running: sending }" /><span>{{ sending ? 'RUNNING' : 'READY' }}</span></div><p><span>STREAM</span><b>SSE</b><span>TRACE</span><b>LIVE</b></p></div>
        <div ref="chatContainer" class="conversation-stream">
          <template v-for="(message, index) in messages" :key="index">
            <div v-if="message.role === 'system'" class="system-event"><span>SYSTEM</span><p>{{ message.content }}</p><time>{{ message.time }}</time></div>
            <div v-else-if="message.role === 'tool_start'" class="inline-tool start"><el-icon><Setting /></el-icon><span>TOOL CALL</span><strong>{{ message.content }}</strong><time>{{ message.time }}</time></div>
            <div v-else-if="message.role === 'tool_end'" class="inline-tool end"><el-icon><Check /></el-icon><span>TOOL RESULT</span><strong>{{ message.content }}</strong><time>{{ message.time }}</time></div>
            <div v-else-if="message.role === 'error'" class="inline-error"><span>ERROR</span><p>{{ message.content }}</p></div>
            <article v-else :class="['message-row', message.role]">
              <div class="message-meta"><span>{{ message.role === 'user' ? 'YOU' : 'AGENT' }}</span><time>{{ message.time }}</time></div>
              <div v-if="message.role === 'assistant' && message.content" class="message-body markdown-message" v-html="renderAssistantMessage(message.content)" />
              <div v-else class="message-body">{{ message.content || (sending && index === messages.length - 1 ? '正在生成响应…' : '') }}</div>
            </article>
          </template>
        </div>
        <div class="composer">
          <div class="composer-meta"><span>{{ sessionId }}</span><small data-no-ui-translate>{{ composerHint }}</small></div>
          <div class="composer-box"><textarea v-model="inputText" :disabled="sending" placeholder="输入真实业务请求，观察模型、工具与审批轨迹…" @keydown="handleKeydown" /><button aria-label="发送消息" :disabled="!inputText.trim() || sending" @click="sendMessage"><el-icon><Promotion /></el-icon><span>{{ sending ? '执行中' : '发送' }}</span></button></div>
        </div>
      </section>

      <aside class="trace-panel">
        <div class="trace-head"><el-icon><Connection /></el-icon><span>TRACE / TIMELINE</span><strong>执行链路</strong></div>
        <div class="trace-stats"><div><span>TURN</span><strong>{{ turnCount }}</strong></div><div><span>TOOLS</span><strong>{{ toolCallCount }}</strong></div><div><span>MODEL</span><strong>{{ agent?.model || '-' }}</strong></div></div>
        <div class="trace-list">
          <div class="trace-base"><i class="done" /><span>SESSION</span><strong>会话已建立</strong><small>身份上下文可用</small></div>
          <div class="trace-base"><i class="done" /><span>POLICY</span><strong>权限与护栏已加载</strong><small>输入 / 输出双向检查</small></div>
          <article v-for="(event, index) in traceEvents" :key="index"><i :class="event.role" /><span>{{ event.role === 'tool_start' ? 'TOOL CALL' : event.role === 'tool_end' ? 'TOOL RESULT' : 'ERROR' }}</span><strong>{{ event.content }}</strong><small>{{ event.time }}</small></article>
          <article v-for="item in pendingApprovals" :key="`approval-${item.id}`" class="approval-trace"><i class="approval" /><span>APPROVAL GATE</span><strong>{{ item.toolName || '高风险工具' }}</strong><small>#{{ item.id }} · 运行时已暂停</small><div class="approval-card"><header><el-icon><Timer /></el-icon><p><b>等待人工审批</b><em>{{ item.reason || '该工具被风险策略拦截。' }}</em></p></header><pre :class="{ collapsed: isApprovalCollapsed(item) }">{{ formatApprovalContext(item.context) }}</pre><button v-if="preferences.collapseLargeJson && approvalContextLines(item.context) > preferences.jsonLineThreshold" class="json-toggle" @click="toggleApprovalContext(item.id)">{{ isApprovalCollapsed(item) ? '展开 JSON' : '收起 JSON' }}</button><footer><button :disabled="approvalBusy === item.id" @click="reject(item)"><el-icon><Close /></el-icon>拒绝</button><button :disabled="approvalBusy === item.id" @click="approve(item)"><el-icon><Check /></el-icon>批准并继续</button></footer></div></article>
          <div v-if="sending" class="trace-running"><i /><span>RUNTIME</span><strong>Python 正在执行</strong><small>等待下一个 SSE 事件</small></div>
          <div v-else class="trace-base idle"><i /><span>RUNTIME</span><strong>等待请求</strong><small>运行时连接可用</small></div>
        </div>
        <div class="trace-foot"><el-icon><Lock /></el-icon><span>执行记录将写入审计日志</span></div>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.chat-lab { height: calc(100vh - 108px); min-height: 620px; display: flex; flex-direction: column; overflow: hidden; border: 1px solid var(--console-line); border-radius: 8px; background: #101417; box-shadow: 0 20px 50px rgba(0,0,0,.24); }
.lab-header { min-height: 64px; padding: 0 16px; display: grid; grid-template-columns: 1fr auto 1fr; align-items: center; border-bottom: 1px solid var(--console-line); background: #111518; }.back-button { justify-self: start; padding: 0; display: inline-flex; align-items: center; gap: 7px; border: 0; background: transparent; color: #8c969d; font: inherit; font-size: 12px; font-weight: 700; cursor: pointer; }.back-button:hover { color: var(--console-ink); }.lab-agent { text-align: center; }.lab-agent span { color: var(--console-accent); font: 12px ui-monospace, monospace; }.lab-agent h1 { margin: 4px 0 0; color: #edf3ef; font-size: 14px; }.lab-status { justify-self: end; display: flex; align-items: center; gap: 9px; }.lab-status > span { display: flex; align-items: center; gap: 5px; color: #727c83; font: 12px ui-monospace, monospace; text-transform: uppercase; }.lab-status > span i { width: 6px; height: 6px; border-radius: 50%; background: var(--console-warning); }.lab-status > span i.published { background: var(--console-accent); box-shadow: 0 0 7px rgba(82,231,160,.55); }.lab-status > b { max-width: 140px; overflow: hidden; color: #7b858c; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }.lab-status button { min-height: 30px; padding: 0 10px; border: 1px solid #353c42; border-radius: 5px; background: #171c20; color: #b7c0ba; font: inherit; font-size: 12px; cursor: pointer; }
.lab-workspace { min-height: 0; flex: 1; display: grid; grid-template-columns: 218px minmax(360px, 1fr) 330px; }.request-panel { min-width: 0; display: flex; flex-direction: column; border-right: 1px solid var(--console-line); background: #0d1113; }.request-head { min-height: 70px; padding: 15px; display: grid; grid-template-columns: 1fr auto; align-content: center; border-bottom: 1px solid var(--console-line); }.request-head span { grid-column: 1 / -1; color: #59636a; font: 12px ui-monospace, monospace; }.request-head strong { margin-top: 5px; color: #dbe3de; font-size: 12px; }.request-head small { margin-top: 5px; color: #6f7980; font-size: 12px; }.new-request { min-height: 38px; margin: 10px; display: flex; align-items: center; gap: 8px; border: 1px dashed #363e43; border-radius: 6px; background: transparent; color: #8e989f; font: inherit; font-size: 12px; cursor: pointer; }.new-request span { width: 28px; font-size: 15px; }.new-request:hover { border-color: #48735d; color: #85dfac; }.request-list { min-height: 0; flex: 1; padding: 0 8px; overflow-y: auto; }.request-list > button { width: 100%; min-height: 64px; padding: 9px; display: grid; grid-template-columns: 26px 1fr 6px; gap: 8px; align-items: center; border: 0; border-bottom: 1px solid #242a2e; background: transparent; color: #cbd4ce; font: inherit; text-align: left; cursor: pointer; }.request-list > button:hover { border-radius: 6px; background: #151b18; }.request-list > button > span { color: #596269; font: 12px ui-monospace, monospace; }.request-list p { min-width: 0; display: flex; flex-direction: column; gap: 5px; }.request-list strong { overflow: hidden; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }.request-list small { color: #606a71; font-size: 12px; }.request-list i { width: 5px; height: 5px; border-radius: 50%; background: var(--console-accent); }.request-empty { min-height: 200px; display: flex; flex-direction: column; align-items: center; justify-content: center; color: #4f585e; text-align: center; }.request-empty .el-icon { font-size: 20px; }.request-empty strong { margin-top: 10px; color: #879198; font-size: 12px; }.request-empty span { max-width: 140px; margin-top: 6px; font-size: 12px; line-height: 1.5; }.request-config { margin: 10px; padding: 12px; border: 1px solid #262c31; border-radius: 6px; background: #111518; }.request-config > span { color: #69737a; font-size: 12px; }.request-config dl { margin: 9px 0 0; }.request-config dl div { min-height: 25px; display: grid; grid-template-columns: 52px 1fr; align-items: center; }.request-config dt { color: #596269; font-size: 12px; }.request-config dd { margin: 0; overflow: hidden; color: #a1aca5; font: 12px ui-monospace, monospace; text-align: right; text-overflow: ellipsis; white-space: nowrap; }
.conversation-panel { min-width: 0; display: flex; flex-direction: column; background: #111518; }.conversation-toolbar { min-height: 40px; padding: 0 14px; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid var(--console-line); }.conversation-toolbar > div, .conversation-toolbar p { display: flex; align-items: center; gap: 6px; }.conversation-toolbar i { width: 6px; height: 6px; border-radius: 50%; background: #626b71; }.conversation-toolbar i.active { background: var(--console-accent); }.conversation-toolbar i.running { background: var(--console-warning); animation: pulse 1.4s infinite; }.conversation-toolbar span { color: #667077; font: 12px ui-monospace, monospace; }.conversation-toolbar b { color: #9aa59e; font: 12px ui-monospace, monospace; }.conversation-stream { flex: 1; min-height: 0; padding: 20px; overflow-y: auto; background: #0f1315; }.system-event { min-height: 42px; padding: 9px 11px; display: grid; grid-template-columns: 58px 1fr auto; align-items: center; border: 1px solid #273028; border-radius: 6px; background: #131a16; }.system-event span { color: #68d89d; font: 12px ui-monospace, monospace; }.system-event p { color: #8d9891; font-size: 12px; }.system-event time, .inline-tool time { color: #596269; font-size: 12px; }.message-row { max-width: 82%; margin-top: 19px; }.message-row.user { margin-left: auto; }.message-meta { margin-bottom: 6px; display: flex; justify-content: space-between; color: #626b72; font: 12px ui-monospace, monospace; }.message-body { padding: 14px 16px; border: 1px solid #30373c; border-radius: 7px; background: #171c20; color: #cbd4ce; font-size: 12px; line-height: 1.72; white-space: pre-wrap; }.message-row.user .message-body { border-color: #345b47; background: #183026; color: #d8eee1; }.message-row.user .message-meta span { color: var(--console-accent); }.markdown-message { white-space: normal; }.markdown-message :deep(p) { margin: 0; }.markdown-message :deep(p + p), .markdown-message :deep(ul), .markdown-message :deep(ol), .markdown-message :deep(pre) { margin-top: 11px; }.markdown-message :deep(ul), .markdown-message :deep(ol) { padding-left: 18px; }.markdown-message :deep(code) { padding: 2px 5px; border-radius: 3px; background: #0d1113; color: #e8bd73; font: .92em ui-monospace, monospace; }.markdown-message :deep(pre) { padding: 12px; overflow-x: auto; background: #0b0e10; color: #bdc7c0; }.inline-tool { min-height: 42px; max-width: 88%; margin: 12px auto 0; padding: 8px 10px; display: grid; grid-template-columns: 24px 70px 1fr auto; align-items: center; border: 1px solid #343320; border-radius: 6px; background: #1b1912; }.inline-tool .el-icon { color: var(--console-warning); }.inline-tool.end { border-color: #263b30; background: #131c17; }.inline-tool.end .el-icon { color: var(--console-accent); }.inline-tool span { color: #7b7567; font: 12px ui-monospace, monospace; }.inline-tool strong { overflow: hidden; color: #b7b09f; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }.inline-error { margin-top: 12px; padding: 11px; border: 1px solid #512e2d; border-left: 3px solid var(--console-danger); border-radius: 5px; background: #231414; color: #e98a83; font-size: 12px; }.inline-error span { font: 12px ui-monospace, monospace; }.inline-error p { margin: 5px 0 0; }.composer { padding: 12px 14px; border-top: 1px solid var(--console-line); background: #111518; }.composer-meta { margin-bottom: 7px; display: flex; justify-content: space-between; color: #5b646b; font: 12px ui-monospace, monospace; }.composer-box { min-height: 74px; display: grid; grid-template-columns: 1fr 70px; border: 1px solid #343c42; border-radius: 7px; background: #0e1214; overflow: hidden; }.composer-box:focus-within { border-color: #4a8b68; box-shadow: 0 0 0 3px rgba(82,226,157,.06); }.composer textarea { min-height: 72px; padding: 13px; resize: none; border: 0; outline: 0; background: transparent; color: var(--console-ink); font: inherit; font-size: 12px; line-height: 1.6; }.composer button { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 5px; border: 0; background: #4ee09a; color: #06110b; font: inherit; font-size: 12px; font-weight: 800; cursor: pointer; }.composer button:disabled { background: #26322c; color: #657069; cursor: not-allowed; }
.trace-panel { min-height: 0; padding: 16px; overflow-y: auto; border-left: 1px solid var(--console-line); background: #0b0e10; color: var(--console-ink); }.trace-head { display: grid; grid-template-columns: 28px 1fr; }.trace-head .el-icon { grid-row: 1 / 3; align-self: center; color: var(--console-accent); font-size: 18px; }.trace-head span { color: #5f696f; font: 12px ui-monospace, monospace; }.trace-head strong { margin-top: 4px; font-size: 12px; }.trace-stats { margin-top: 16px; display: grid; grid-template-columns: .6fr .6fr 1fr; border: 1px solid #292f34; border-radius: 6px; overflow: hidden; }.trace-stats div { min-width: 0; min-height: 54px; padding: 9px; display: flex; flex-direction: column; justify-content: flex-end; border-right: 1px solid #292f34; }.trace-stats div:last-child { border-right: 0; }.trace-stats span { color: #535c62; font: 6px ui-monospace, monospace; }.trace-stats strong { margin-top: 5px; overflow: hidden; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }.trace-list { position: relative; margin-top: 15px; padding-left: 1px; }.trace-list::before { content: ''; position: absolute; top: 8px; bottom: 8px; left: 4px; width: 1px; background: #2d3438; }.trace-list > div, .trace-list > article { position: relative; min-height: 56px; padding: 9px 0 9px 20px; display: grid; grid-template-columns: 1fr; border-top: 1px solid #242a2e; }.trace-list i { position: absolute; z-index: 1; left: 0; top: 13px; width: 9px; height: 9px; border: 2px solid #0b0e10; border-radius: 50%; background: #555e64; }.trace-list i.done, .trace-list i.tool_end { background: var(--console-accent); box-shadow: 0 0 8px rgba(82,232,160,.35); }.trace-list i.tool_start, .trace-running i { background: var(--console-warning); }.trace-list i.error { background: var(--console-danger); }.trace-list i.approval { background: var(--console-warning); box-shadow: 0 0 0 4px rgba(231,184,93,.12), 0 0 13px rgba(231,184,93,.35); animation: pulse 1.5s infinite; }.trace-list span { color: #596269; font: 6px ui-monospace, monospace; }.trace-list strong { margin-top: 4px; overflow: hidden; color: #aab4ad; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }.trace-list small { margin-top: 4px; color: #525b61; font-size: 12px; }.trace-list .idle i { background: #434c51; }.approval-trace { padding-bottom: 13px !important; }.approval-card { margin-top: 10px; padding: 11px; border: 1px solid #5a4320; border-radius: 7px; background: #21190d; box-shadow: 0 0 22px rgba(224,171,72,.08); }.approval-card header { display: flex; gap: 8px; color: #eab75a; }.approval-card header .el-icon { position: static; width: auto; height: auto; border: 0; border-radius: 0; background: transparent; }.approval-card header p { display: flex; flex-direction: column; gap: 4px; }.approval-card b { font-size: 12px; }.approval-card em { color: #ad9262; font-size: 12px; font-style: normal; line-height: 1.4; }.approval-card pre { max-height: 74px; margin: 10px 0 0; padding: 8px; overflow: auto; border: 1px solid #3e321f; border-radius: 4px; background: #141009; color: #9c8d72; font: 12px/1.5 ui-monospace, monospace; white-space: pre-wrap; }.approval-card footer { margin-top: 9px; display: grid; grid-template-columns: .8fr 1.2fr; gap: 6px; }.approval-card button { min-height: 30px; display: flex; align-items: center; justify-content: center; gap: 5px; border: 1px solid #57462d; border-radius: 5px; background: transparent; color: #c5ad83; font: inherit; font-size: 12px; cursor: pointer; }.approval-card button:last-child { border-color: #d5a84e; background: #dbad50; color: #1c1407; font-weight: 800; }.trace-foot { min-height: 38px; margin-top: 14px; display: flex; align-items: center; gap: 7px; border-top: 1px solid #282e32; color: #596269; font-size: 12px; }
@keyframes pulse { 0%,100% { opacity: 1; } 50% { opacity: .45; } }
@media (max-width: 1180px) { .lab-workspace { grid-template-columns: 180px minmax(340px, 1fr) 290px; } }
@media (max-width: 980px) { .lab-workspace { grid-template-columns: 190px 1fr; }.trace-panel { display: none; } }
@media (max-width: 700px) { .chat-lab { height: calc(100vh - 92px); min-height: 520px; }.lab-header { grid-template-columns: auto 1fr; padding: 0 11px; }.lab-agent { text-align: left; }.lab-status { display: none; }.back-button span { display: none; }.lab-workspace { grid-template-columns: 1fr; }.request-panel { display: none; }.conversation-stream { padding: 13px; }.message-row { max-width: 94%; }.composer-box { grid-template-columns: 1fr 58px; } }
.approval-card pre.collapsed { max-height: 78px; overflow: hidden; mask-image: linear-gradient(#000 55%, transparent); }
.approval-card .json-toggle { width: 100%; min-height: 28px; margin-top: 6px; border: 1px solid #4d4029; border-radius: 4px; background: rgba(245,158,11,.08); color: #f4c873; font: inherit; font-size: 12px; cursor: pointer; }
</style>
