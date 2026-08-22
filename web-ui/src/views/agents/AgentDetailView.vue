<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, ChatDotRound, Check, Close, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import api from '../../api'

const route = useRoute()
const router = useRouter()
const agent = ref<any>(null)
const loading = ref(true)

const statusLabels: Record<string, string> = { draft: '草稿', published: '已发布', disabled: '已停用' }

async function fetchAgent() {
  loading.value = true
  try {
    const response = await api.get(`/agents/${route.params.id}`) as any
    agent.value = response.data
  } catch {
    ElMessage.error('Agent 不存在')
    router.push('/console/agents')
  } finally {
    loading.value = false
  }
}

async function publishAgent() {
  await api.put(`/agents/${agent.value.id}/publish`)
  ElMessage.success('Agent 已发布')
  await fetchAgent()
}

async function disableAgent() {
  await api.put(`/agents/${agent.value.id}/disable`)
  ElMessage.success('Agent 已停用')
  await fetchAgent()
}

onMounted(fetchAgent)
</script>

<template>
  <div class="console-page agent-detail-page" v-loading="loading">
    <template v-if="agent">
      <div class="detail-nav"><button @click="router.push('/console/agents')"><el-icon><ArrowLeft /></el-icon> Agent 工作台</button><span>AGENT / {{ String(agent.id).padStart(4, '0') }}</span><button aria-label="刷新" @click="fetchAgent"><el-icon><Refresh /></el-icon></button></div>

      <section class="agent-identity">
        <div class="agent-monogram">{{ agent.icon || agent.name.slice(0, 2).toUpperCase() }}</div>
        <div class="agent-title"><span>业务 Agent</span><h1>{{ agent.name }}</h1><p>{{ agent.description || '尚未填写业务说明。' }}</p></div>
        <div class="agent-state"><span>STATUS</span><b :class="agent.status"><i />{{ statusLabels[agent.status] || agent.status }}</b><small>更新于 {{ agent.updatedAt?.substring(0, 16)?.replace('T', ' ') }}</small></div>
        <div class="agent-main-action"><button v-if="agent.status === 'published'" @click="router.push(`/console/agents/${agent.id}/chat`)"><el-icon><ChatDotRound /></el-icon><span>打开对话实验室</span><small>测试模型与工具链路</small></button><button v-else-if="agent.status === 'draft'" @click="publishAgent"><el-icon><Check /></el-icon><span>发布 Agent</span><small>允许成员开始使用</small></button><button v-else @click="publishAgent"><el-icon><Check /></el-icon><span>重新启用</span><small>恢复 Agent 服务</small></button></div>
      </section>

      <div class="agent-blueprint">
        <section class="prompt-dossier">
          <div class="dossier-head"><div><span>系统提示词</span><h2>角色与行为边界</h2></div><code>prompt.md</code></div>
          <pre><code><span class="prompt-comment"># Agent instruction</span>
{{ agent.systemPrompt }}</code></pre>
          <div class="prompt-foot"><span>CHARACTERS <b>{{ agent.systemPrompt?.length || 0 }}</b></span><span>MODEL <b>{{ agent.model }}</b></span><span>MAX TOKENS <b>{{ agent.maxTokens }}</b></span></div>
        </section>

        <aside class="configuration-rail">
          <div class="rail-head"><span>配置</span><strong>运行参数</strong></div>
          <div class="config-item"><span>MODEL ROUTE</span><strong>{{ agent.model }}</strong><small>由模型网关提供</small></div>
          <div class="config-item"><span>TEMPERATURE</span><strong>{{ agent.temperature }}</strong><div class="temperature-scale"><i :style="{ width: `${Number(agent.temperature) / 2 * 100}%` }" /></div></div>
          <div class="config-item"><span>CONTEXT LIMIT</span><strong>{{ agent.maxTokens }}</strong><small>单次输出上限</small></div>
          <div class="config-item"><span>OWNER</span><strong>#{{ agent.createdBy || '-' }}</strong><small>创建者账号</small></div>
          <button v-if="agent.status === 'published'" class="disable-action" @click="disableAgent"><el-icon><Close /></el-icon> 停用 Agent</button>
        </aside>
      </div>

      <section class="lifecycle-track"><div><span>01</span><i class="done" /><strong>已创建</strong><small>{{ agent.createdAt?.substring(0, 10) }}</small></div><div><span>02</span><i :class="{ done: agent.publishedAt }" /><strong>已发布</strong><small>{{ agent.publishedAt?.substring(0, 10) || '等待发布' }}</small></div><div><span>03</span><i :class="{ done: agent.status === 'published' }" /><strong>生产运行</strong><small>{{ agent.status === 'published' ? '当前可用' : '当前未运行' }}</small></div></section>
    </template>
  </div>
</template>

<style scoped>
.detail-nav { min-height: 48px; display: grid; grid-template-columns: 1fr auto 1fr; align-items: center; border-bottom: 1px solid var(--console-line); }
.detail-nav button { padding: 0; display: inline-flex; align-items: center; gap: 7px; justify-self: start; border: 0; background: transparent; color: var(--console-ink); font: inherit; font-size: 12px; font-weight: 800; cursor: pointer; }
.detail-nav button:last-child { justify-self: end; }
.detail-nav > span { color: #8c9289; font-family: ui-monospace, monospace; font-size: 12px; }
.agent-identity { min-height: 200px; display: grid; grid-template-columns: 100px minmax(280px, 1fr) 180px 230px; align-items: stretch; border: 1px solid var(--console-line); border-top: 0; background: white; }
.agent-monogram { display: grid; place-items: center; background: var(--console-orange); color: white; font-size: 24px; font-weight: 900; }
.agent-title { padding: 28px; }
.agent-title > span { color: var(--console-orange); font-family: ui-monospace, monospace; font-size: 12px; }
.agent-title h1 { margin-top: 12px; font-size: 31px; }
.agent-title p { max-width: 560px; margin-top: 10px; color: var(--console-muted); font-size: 12px; line-height: 1.7; }
.agent-state { padding: 28px 20px; display: flex; flex-direction: column; border-left: 1px solid var(--console-line); }
.agent-state > span { color: #878d84; font-family: ui-monospace, monospace; font-size: 12px; }
.agent-state b { margin-top: 18px; display: flex; align-items: center; gap: 7px; font-size: 12px; }
.agent-state b i { width: 7px; height: 7px; background: var(--console-yellow); }
.agent-state b.published i { background: var(--console-green); }
.agent-state b.disabled i { background: var(--console-red); }
.agent-state small { margin-top: auto; color: #959b92; font-size: 12px; }
.agent-main-action { padding: 20px; border-left: 1px solid var(--console-line); background: #f2f3ef; }
.agent-main-action button { width: 100%; height: 100%; padding: 20px; display: grid; grid-template-columns: 30px 1fr; align-content: center; border: 0; background: var(--console-ink); color: white; font: inherit; text-align: left; cursor: pointer; }
.agent-main-action button:hover { background: var(--console-orange); }
.agent-main-action .el-icon { grid-row: 1 / 3; align-self: center; font-size: 20px; }
.agent-main-action span { font-size: 12px; font-weight: 800; }
.agent-main-action small { margin-top: 5px; color: #888f85; font-size: 12px; }
.agent-main-action button:hover small { color: #ffd7c8; }
.agent-blueprint { margin-top: 16px; display: grid; grid-template-columns: 1fr 300px; gap: 16px; }
.prompt-dossier { min-width: 0; border: 1px solid var(--console-line); background: white; }
.dossier-head { min-height: 70px; padding: 0 20px; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid var(--console-line); }
.dossier-head span { color: var(--console-orange); font-family: ui-monospace, monospace; font-size: 12px; }
.dossier-head h2 { margin-top: 5px; font-size: 14px; }
.dossier-head code { color: #8e948b; font-size: 12px; }
.prompt-dossier pre { min-height: 350px; max-height: 500px; margin: 0; padding: 26px; overflow: auto; background: #171916; color: #c8cdc5; font-family: ui-monospace, monospace; font-size: 12px; line-height: 1.8; white-space: pre-wrap; }
.prompt-comment { color: #5e655c; }
.prompt-foot { min-height: 50px; padding: 0 18px; display: flex; align-items: center; gap: 24px; color: #92988f; font-family: ui-monospace, monospace; font-size: 6px; }
.prompt-foot b { display: block; margin-top: 3px; color: var(--console-ink); font-size: 12px; }
.configuration-rail { padding: 20px; background: #dedfd9; }
.rail-head span { color: var(--console-orange); font-family: ui-monospace, monospace; font-size: 12px; }
.rail-head strong { display: block; margin-top: 6px; font-size: 14px; }
.config-item { min-height: 88px; padding: 16px 0; display: flex; flex-direction: column; border-bottom: 1px solid #bfc2ba; }
.config-item > span { color: #888e85; font-family: ui-monospace, monospace; font-size: 6px; }
.config-item strong { margin-top: auto; font-size: 12px; }
.config-item small { margin-top: 4px; color: #858b82; font-size: 12px; }
.temperature-scale { height: 4px; margin-top: 9px; background: #c5c8c0; }
.temperature-scale i { height: 100%; display: block; background: var(--console-orange); }
.disable-action { width: 100%; min-height: 38px; margin-top: 20px; display: flex; align-items: center; justify-content: center; gap: 7px; border: 1px solid #c8a49d; background: transparent; color: var(--console-red); font: inherit; font-size: 12px; cursor: pointer; }
.lifecycle-track { min-height: 110px; margin-top: 16px; display: grid; grid-template-columns: repeat(3, 1fr); border: 1px solid var(--console-line); background: white; }
.lifecycle-track div { position: relative; padding: 18px; display: grid; grid-template-columns: 28px 12px 1fr; align-content: center; border-right: 1px solid var(--console-line); }
.lifecycle-track div:last-child { border-right: 0; }
.lifecycle-track span { color: #969c93; font-family: ui-monospace, monospace; font-size: 12px; }
.lifecycle-track i { width: 7px; height: 7px; background: #b2b7af; }
.lifecycle-track i.done { background: var(--console-green); }
.lifecycle-track strong { font-size: 12px; }
.lifecycle-track small { grid-column: 3; margin-top: 4px; color: #8c9289; font-size: 12px; }
@media (max-width: 1000px) { .agent-identity { grid-template-columns: 80px 1fr 160px; } .agent-main-action { grid-column: 1 / -1; min-height: 110px; border-top: 1px solid var(--console-line); border-left: 0; } .agent-blueprint { grid-template-columns: 1fr; } }
@media (max-width: 640px) { .detail-nav { grid-template-columns: 1fr auto; } .detail-nav > span { display: none; } .agent-identity { grid-template-columns: 72px 1fr; } .agent-state { grid-column: 1 / -1; min-height: 100px; border-top: 1px solid var(--console-line); border-left: 0; } .lifecycle-track { grid-template-columns: 1fr; } .lifecycle-track div { border-right: 0; border-bottom: 1px solid var(--console-line); } }
</style>
