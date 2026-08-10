<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Check, Connection, Promotion, Setting } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import MarkdownIt from 'markdown-it'
import api from '../../api'

interface Message {
  role: 'user' | 'assistant' | 'tool_start' | 'tool_end' | 'error' | 'system'
  content: string
  toolName?: string
  time?: string
}

const route = useRoute()
const router = useRouter()
const agentId = route.params.id as string
const apiBaseUrl = import.meta.env.VITE_API_URL || 'http://localhost:8080/api'
const messages = ref<Message[]>([])
const inputText = ref('')
const sending = ref(false)
const agent = ref<any>(null)
const chatContainer = ref<HTMLElement | null>(null)
const sessionId = ref(`web-${agentId}-${Date.now()}`)
const markdown = new MarkdownIt({ html: false, linkify: true, breaks: true })

const traceEvents = computed(() => messages.value.filter(message => ['tool_start', 'tool_end', 'error'].includes(message.role)))
const turnCount = computed(() => messages.value.filter(message => message.role === 'user').length)

function now() {
  return new Date().toLocaleTimeString('zh-CN', { hour12: false })
}

function renderAssistantMessage(content: string) {
  return markdown.render(content || '')
}

async function fetchAgent() {
  try {
    const response = await api.get(`/agents/${agentId}`) as any
    agent.value = response.data
    messages.value.push({ role: 'system', content: `${agent.value.name} 已就绪。发送一条真实业务请求开始测试。`, time: now() })
  } catch {
    ElMessage.error('Agent 不存在')
    router.push('/console/agents')
  }
}

function scrollToBottom() {
  nextTick(() => {
    if (chatContainer.value) chatContainer.value.scrollTop = chatContainer.value.scrollHeight
  })
}

function handleSSE(event: string, data: string, assistantMessage: Message) {
  if (event === 'text') assistantMessage.content += data
  if (event === 'tool_start') messages.value.push({ role: 'tool_start', content: data, toolName: data, time: now() })
  if (event === 'tool_end') messages.value.push({ role: 'tool_end', content: data, time: now() })
  if (event === 'error') messages.value.push({ role: 'error', content: data, time: now() })
  if ((event === 'done' || event === 'complete') && !assistantMessage.content) assistantMessage.content = '本次执行没有返回文本结果。'
}

function parseSSEBlock(block: string, assistantMessage: Message) {
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
  } catch {
    content = rawData
  }
  handleSSE(eventName, content, assistantMessage)
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
    const response = await fetch(`${apiBaseUrl}/agents/${agentId}/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${localStorage.getItem('token') || ''}` },
      body: JSON.stringify({ message: text, sessionId: sessionId.value, userId: '1' }),
    })
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    const reader = response.body?.getReader()
    if (!reader) throw new Error('流式响应不可用')
    const decoder = new TextDecoder()
    let buffer = ''
    let completed = false
    while (true) {
      const { done, value } = await reader.read()
      if (done) {
        buffer += decoder.decode()
      } else {
        buffer += decoder.decode(value, { stream: true })
      }
      buffer = buffer.replace(/\r\n/g, '\n')
      const blocks = buffer.split('\n\n')
      buffer = blocks.pop() || ''
      for (const block of blocks) {
        const eventName = parseSSEBlock(block, assistantMessage)
        if (eventName === 'done' || eventName === 'complete') completed = true
      }
      scrollToBottom()
      if (done) break
    }
    if (buffer.trim()) {
      const eventName = parseSSEBlock(buffer, assistantMessage)
      if (eventName === 'done' || eventName === 'complete') completed = true
    }
    if (!completed) throw new Error('流式连接提前结束')
  } catch (error: any) {
    if (assistantMessage.content) assistantMessage.content += `\n\n[执行中断：${error.message}]`
    else {
      messages.value.pop()
      messages.value.push({ role: 'error', content: `请求失败：${error.message}`, time: now() })
    }
  } finally {
    sending.value = false
    scrollToBottom()
  }
}

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    sendMessage()
  }
}

function clearSession() {
  messages.value = [{ role: 'system', content: '新会话已创建，历史上下文已清空。', time: now() }]
  sessionId.value = `web-${agentId}-${Date.now()}`
}

onMounted(fetchAgent)
</script>

<template>
  <div class="console-page chat-lab">
    <header class="lab-header">
      <button @click="router.push(`/console/agents/${agentId}`)"><el-icon><ArrowLeft /></el-icon> Agent 详情</button>
      <div class="lab-agent"><span>实时对话测试</span><h1>{{ agent?.name || 'Agent 对话' }}</h1></div>
      <div class="lab-status"><span><i :class="agent?.status" />{{ agent?.status || 'loading' }}</span><b>{{ agent?.model || '-' }}</b><button @click="clearSession">新会话</button></div>
    </header>

    <div class="lab-workspace">
      <section class="conversation-panel">
        <div ref="chatContainer" class="conversation-stream">
          <template v-for="(message, index) in messages" :key="index">
            <div v-if="message.role === 'system'" class="system-event"><span>系统</span><p>{{ message.content }}</p><time>{{ message.time }}</time></div>
            <div v-else-if="message.role === 'tool_start'" class="inline-tool start"><el-icon><Setting /></el-icon><span>调用工具</span><strong>{{ message.content }}</strong><time>{{ message.time }}</time></div>
            <div v-else-if="message.role === 'tool_end'" class="inline-tool end"><el-icon><Check /></el-icon><span>工具返回</span><strong>{{ message.content }}</strong><time>{{ message.time }}</time></div>
            <div v-else-if="message.role === 'error'" class="inline-error"><span>错误</span><p>{{ message.content }}</p></div>
            <article v-else :class="['message-row', message.role]">
              <div class="message-meta"><span>{{ message.role === 'user' ? 'YOU' : 'AGENT' }}</span><time>{{ message.time }}</time></div>
              <div v-if="message.role === 'assistant' && message.content" class="message-body markdown-message" v-html="renderAssistantMessage(message.content)" />
              <div v-else class="message-body">{{ message.content || (sending && index === messages.length - 1 ? '正在生成响应…' : '') }}</div>
            </article>
          </template>
        </div>

        <div class="composer">
          <div class="composer-meta"><span>会话 {{ sessionId }}</span><small>Enter 发送 · Shift + Enter 换行</small></div>
          <div class="composer-box"><textarea v-model="inputText" :disabled="sending" placeholder="输入一条真实业务请求，观察模型和工具执行轨迹…" @keydown="handleKeydown" /><button :disabled="!inputText.trim() || sending" @click="sendMessage"><el-icon><Promotion /></el-icon><span>{{ sending ? '执行中' : '发送' }}</span></button></div>
        </div>
      </section>

      <aside class="trace-panel">
        <div class="trace-head"><el-icon><Connection /></el-icon><span>执行记录</span><strong>运行轨迹</strong></div>
        <div class="trace-stats"><div><span>轮次</span><strong>{{ turnCount }}</strong></div><div><span>工具</span><strong>{{ traceEvents.filter(event => event.role === 'tool_start').length }}</strong></div><div><span>模型</span><strong>{{ agent?.model || '-' }}</strong></div></div>
        <div class="trace-list"><div class="trace-base"><i class="done" /><span>SESSION</span><strong>会话已建立</strong></div><div class="trace-base"><i class="done" /><span>POLICY</span><strong>身份与权限已校验</strong></div><article v-for="(event, index) in traceEvents" :key="index"><i :class="event.role" /><span>{{ event.role === 'tool_start' ? 'TOOL CALL' : event.role === 'tool_end' ? 'TOOL RESULT' : 'ERROR' }}</span><strong>{{ event.content }}</strong><small>{{ event.time }}</small></article><div v-if="sending" class="trace-running"><i /><span>RUNTIME</span><strong>Python 正在执行</strong></div></div>
        <div class="trace-source"><span>stream_execute.py</span><pre><code><b>async for</b> event <b>in</b> engine.run():
  <i>yield</i> event.to_sse()</code></pre></div>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.chat-lab { height: calc(100vh - 124px); display: flex; flex-direction: column; overflow: hidden; border: 1px solid var(--console-line); background: white; }
.lab-header { min-height: 78px; padding: 0 20px; display: grid; grid-template-columns: 1fr auto 1fr; align-items: center; border-bottom: 1px solid var(--console-line); background: #f3f4f0; }
.lab-header > button { justify-self: start; padding: 0; display: inline-flex; align-items: center; gap: 7px; border: 0; background: transparent; color: var(--console-ink); font: inherit; font-size: 8px; font-weight: 800; cursor: pointer; }
.lab-agent { text-align: center; }
.lab-agent span { color: var(--console-orange); font-family: ui-monospace, monospace; font-size: 7px; }
.lab-agent h1 { margin-top: 5px; font-size: 15px; }
.lab-status { justify-self: end; display: flex; align-items: center; gap: 10px; }
.lab-status > span { display: flex; align-items: center; gap: 6px; color: #777d74; font-family: ui-monospace, monospace; font-size: 7px; }
.lab-status > span i { width: 6px; height: 6px; background: var(--console-yellow); }
.lab-status > span i.published { background: var(--console-green); }
.lab-status > b { color: #777d74; font-size: 8px; }
.lab-status button { min-height: 32px; padding: 0 10px; border: 1px solid var(--console-line); background: white; font: inherit; font-size: 8px; cursor: pointer; }
.lab-workspace { min-height: 0; flex: 1; display: grid; grid-template-columns: 1fr 310px; }
.conversation-panel { min-width: 0; display: flex; flex-direction: column; }
.conversation-stream { flex: 1; min-height: 0; padding: 24px; overflow-y: auto; background: #eceee9; }
.system-event { min-height: 42px; padding: 10px 12px; display: grid; grid-template-columns: 60px 1fr auto; align-items: center; border: 1px solid #d6d9d1; background: #e2e5de; }
.system-event span { color: var(--console-orange); font-family: ui-monospace, monospace; font-size: 7px; }
.system-event p { color: #636960; font-size: 9px; }
.system-event time, .inline-tool time { color: #90968d; font-size: 7px; }
.message-row { max-width: 76%; margin-top: 22px; }
.message-row.user { margin-left: auto; }
.message-meta { margin-bottom: 6px; display: flex; justify-content: space-between; color: #858b82; font-family: ui-monospace, monospace; font-size: 7px; }
.message-body { padding: 16px 18px; border: 1px solid var(--console-line); background: white; color: #333730; font-size: 11px; line-height: 1.75; white-space: pre-wrap; }
.markdown-message { white-space: normal; }
.markdown-message :deep(p) { margin: 0; }
.markdown-message :deep(p + p), .markdown-message :deep(ul), .markdown-message :deep(ol), .markdown-message :deep(pre) { margin-top: 12px; }
.markdown-message :deep(ul), .markdown-message :deep(ol) { padding-left: 20px; }
.markdown-message :deep(code) { padding: 2px 5px; border-radius: 3px; background: #edf1ec; color: #8f4c3d; font: .92em ui-monospace, SFMono-Regular, Menlo, monospace; }
.markdown-message :deep(pre) { padding: 12px; overflow-x: auto; background: #20241f; color: #e6ebe5; }
.markdown-message :deep(pre code) { padding: 0; background: transparent; color: inherit; }
.message-row.user .message-body { border-color: var(--console-ink); background: var(--console-ink); color: white; }
.message-row.user .message-meta span { color: var(--console-orange); }
.inline-tool { min-height: 44px; max-width: 82%; margin: 14px auto 0; padding: 9px 12px; display: grid; grid-template-columns: 24px 80px 1fr auto; align-items: center; border: 1px solid #d6d9d1; background: #f8f9f5; }
.inline-tool .el-icon { color: var(--console-yellow); }
.inline-tool.end .el-icon { color: var(--console-green); }
.inline-tool span { color: #858b82; font-size: 8px; }
.inline-tool strong { overflow: hidden; font-size: 8px; text-overflow: ellipsis; white-space: nowrap; }
.inline-error { margin-top: 14px; padding: 12px; border-left: 3px solid var(--console-red); background: #fff0ed; color: #a63a31; font-size: 9px; }
.inline-error span { font-family: ui-monospace, monospace; font-size: 7px; }
.inline-error p { margin-top: 5px; }
.composer { padding: 14px 18px; border-top: 1px solid var(--console-line); background: white; }
.composer-meta { margin-bottom: 8px; display: flex; justify-content: space-between; color: #949a91; font-family: ui-monospace, monospace; font-size: 6px; }
.composer-box { min-height: 78px; display: grid; grid-template-columns: 1fr 80px; border: 1px solid var(--console-line); }
.composer-box:focus-within { border-color: var(--console-ink); box-shadow: inset 3px 0 0 var(--console-orange); }
.composer textarea { min-height: 76px; padding: 14px; resize: none; border: 0; outline: 0; background: transparent; color: var(--console-ink); font: inherit; font-size: 10px; line-height: 1.6; }
.composer button { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 5px; border: 0; background: var(--console-ink); color: white; font: inherit; font-size: 8px; cursor: pointer; }
.composer button:hover:not(:disabled) { background: var(--console-orange); }
.composer button:disabled { opacity: .45; cursor: not-allowed; }
.trace-panel { min-height: 0; padding: 18px; overflow-y: auto; border-left: 1px solid #363b34; background: #171916; color: white; }
.trace-head { display: grid; grid-template-columns: 28px 1fr; }
.trace-head .el-icon { grid-row: 1 / 3; align-self: center; color: var(--console-orange); font-size: 19px; }
.trace-head span { color: var(--console-orange); font-family: ui-monospace, monospace; font-size: 7px; }
.trace-head strong { margin-top: 5px; font-size: 13px; }
.trace-stats { margin-top: 20px; display: grid; grid-template-columns: 1fr 1fr; border-top: 1px solid #3b4039; border-left: 1px solid #3b4039; }
.trace-stats div { min-height: 62px; padding: 10px; display: flex; flex-direction: column; justify-content: flex-end; border-right: 1px solid #3b4039; border-bottom: 1px solid #3b4039; }
.trace-stats div:last-child { grid-column: 1 / -1; }
.trace-stats span { color: #6e756b; font-family: ui-monospace, monospace; font-size: 6px; }
.trace-stats strong { margin-top: 5px; overflow: hidden; font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.trace-list { margin-top: 20px; }
.trace-list > div, .trace-list > article { min-height: 55px; padding: 10px 0; display: grid; grid-template-columns: 14px 1fr; border-top: 1px solid #343832; }
.trace-list i { grid-row: 1 / 4; width: 6px; height: 6px; margin-top: 2px; background: #626960; }
.trace-list i.done, .trace-list i.tool_end { background: var(--console-green); }
.trace-list i.tool_start, .trace-running i { background: var(--console-yellow); }
.trace-list i.error { background: var(--console-red); }
.trace-list span { color: #686f65; font-family: ui-monospace, monospace; font-size: 6px; }
.trace-list strong { margin-top: 4px; overflow: hidden; color: #bcc2b9; font-size: 8px; text-overflow: ellipsis; white-space: nowrap; }
.trace-list small { margin-top: 4px; color: #626960; font-size: 6px; }
.trace-source { margin-top: 24px; border: 1px solid #393e37; background: #121411; }
.trace-source > span { min-height: 28px; padding: 0 9px; display: flex; align-items: center; border-bottom: 1px solid #393e37; color: #696f66; font-family: ui-monospace, monospace; font-size: 6px; }
.trace-source pre { margin: 0; padding: 12px; color: #8f968c; font-family: ui-monospace, monospace; font-size: 7px; line-height: 1.6; }
.trace-source b { color: #81abc9; }
.trace-source i { color: var(--console-orange); font-style: normal; }
@media (max-width: 920px) { .lab-workspace { grid-template-columns: 1fr; } .trace-panel { display: none; } }
@media (max-width: 640px) { .chat-lab { height: calc(100vh - 110px); } .lab-header { grid-template-columns: auto 1fr; padding: 0 12px; } .lab-agent { text-align: left; } .lab-status { display: none; } .conversation-stream { padding: 14px; } .message-row { max-width: 90%; } .composer-box { grid-template-columns: 1fr 60px; } }
</style>
