<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ArrowRight, Check, Filter, Search } from '@element-plus/icons-vue'
import SiteHeader from '../components/SiteHeader.vue'
import SiteFooter from '../components/SiteFooter.vue'
import { translateUiText } from '../i18n/uiText'

const { locale } = useI18n()

const providers = [
  { name: 'OpenAI', code: 'OA', region: '全球', models: ['GPT-4o', 'GPT-4o mini'], abilities: ['多模态', '工具调用'], context: '128K', latency: '680 ms', modes: ['ReAct', 'Function Calling'], status: 'ready', color: '#547d68' },
  { name: 'Anthropic', code: 'AN', region: '全球', models: ['Claude 3.5 Sonnet', 'Claude Opus'], abilities: ['长文本', '推理'], context: '200K', latency: '1.2 s', modes: ['ReAct', 'Tool Use'], status: 'ready', color: '#8a6b58' },
  { name: 'DeepSeek', code: 'DS', region: '中国', models: ['DeepSeek V3', 'DeepSeek R1'], abilities: ['推理', '代码'], context: '128K', latency: '520 ms', modes: ['ReAct', 'Function Calling'], status: 'ready', color: '#4f7189' },
  { name: '通义千问', code: 'QW', region: '中国', models: ['Qwen Max', 'Qwen Plus'], abilities: ['中文', '多模态'], context: '128K', latency: '430 ms', modes: ['ReAct', 'Function Calling'], status: 'ready', color: '#4f715e' },
  { name: 'Moonshot', code: 'KM', region: '中国', models: ['Kimi K2.6'], abilities: ['长文本', '搜索'], context: '256K', latency: '760 ms', modes: ['ReAct'], status: 'ready', color: '#71617d' },
  { name: '智谱 AI', code: 'GL', region: '中国', models: ['GLM-4.7', 'GLM Flash'], abilities: ['中文', '工具调用'], context: '128K', latency: '--', modes: ['Function Calling'], status: 'config', color: '#8b6f36' },
  { name: 'Mistral', code: 'MI', region: '欧洲', models: ['Mistral Large'], abilities: ['推理', '低延迟'], context: '128K', latency: '--', modes: ['ReAct', 'Tool Use'], status: 'config', color: '#925b4c' },
  { name: '本地模型', code: 'LL', region: '私有部署', models: ['Ollama', 'vLLM'], abilities: ['数据私有', '自定义'], context: '按模型', latency: 'LOCAL', modes: ['OpenAI Compatible'], status: 'ready', color: '#53635b' },
]
const regions = ['全部', '中国', '全球', '欧洲', '私有部署']
const region = ref('全部')
const search = ref('')
const workload = ref('balanced')
const filtered = computed(() => providers.filter(item => {
  const source = [item.name, ...item.models, ...item.abilities].join(' ')
  const searchable = `${source} ${translateUiText(source, locale.value as 'zh-CN' | 'en-US')}`.toLowerCase()
  return (region.value === '全部' || item.region === region.value) && (!search.value || searchable.includes(search.value.toLowerCase()))
}))
const recommendations = {
  balanced: { label: '综合平衡', primary: 'DeepSeek V3', fallback: 'Qwen Plus', reason: '中文业务质量稳定，成本和延迟适中', cost: '¥0.032' },
  quality: { label: '质量优先', primary: 'Claude Sonnet', fallback: 'GPT-4o', reason: '复杂分析与长文本理解更稳定', cost: '¥0.096' },
  speed: { label: '速度优先', primary: 'GPT-4o mini', fallback: 'GLM Flash', reason: '适合高频客服和轻量分类任务', cost: '¥0.012' },
}
const recommendation = computed(() => recommendations[workload.value as keyof typeof recommendations])
</script>

<template>
  <div class="public-page models-page"><SiteHeader /><main>
    <section class="model-hero"><div><span class="site-kicker">统一模型网关</span><h1>选择合适的模型，<br>不被某一个模型锁住。</h1><p>统一配置供应商、凭证和路由策略。Agent 只描述任务需求，平台负责质量、速度、成本与可用性的平衡。</p></div><div class="model-selector"><span>为任务推荐模型</span><div class="selector-modes"><button v-for="item in [{ id: 'balanced', label: '综合平衡' }, { id: 'quality', label: '质量优先' }, { id: 'speed', label: '速度优先' }]" :key="item.id" :class="{ active: workload === item.id }" @click="workload = item.id">{{ item.label }}</button></div><div class="recommendation"><small>推荐路由</small><strong>{{ recommendation.primary }}</strong><p>{{ recommendation.reason }}</p><div><span>故障切换 <b>{{ recommendation.fallback }}</b></span><span>预计单次 <b>{{ recommendation.cost }}</b></span></div></div></div></section>
    <section class="model-principles"><div><span>01</span><strong>任务匹配</strong><p>不同任务使用不同模型，不让“大模型”承担所有工作。</p></div><div><span>02</span><strong>自动降级</strong><p>供应商异常时切换备用路线，Agent 不需要改配置。</p></div><div><span>03</span><strong>预算保护</strong><p>在请求执行前检查成本与限额，避免意外消耗。</p></div><div><span>04</span><strong>统一审计</strong><p>无论来自哪个模型，都使用同一套身份和记录方式。</p></div></section>
    <section class="provider-section"><div class="provider-heading"><div><span class="site-kicker">MODEL STATUS MATRIX</span><h2>模型矩阵与实时状态墙</h2><p>延迟为当前本地配置下的展示基线；待配置供应商不会伪装成已连接。</p></div><div class="provider-tools"><div class="region-filter"><el-icon><Filter /></el-icon><button v-for="item in regions" :key="item" :class="{ active: region === item }" @click="region = item">{{ item }}</button></div><label><el-icon><Search /></el-icon><input v-model="search" placeholder="搜索模型或能力" /></label></div></div><div class="status-legend"><span><i class="ready" />已连接</span><span><i />待配置</span><b>{{ filtered.length }} PROVIDERS</b></div><div class="provider-grid"><article v-for="provider in filtered" :key="provider.name" :class="provider.status"><div class="provider-top"><span :style="{ background: provider.color }">{{ provider.code }}</span><p><strong>{{ provider.name }}</strong><small>{{ provider.region }} / {{ provider.models.length }} models</small></p><b :class="provider.status"><i />{{ provider.status === 'ready' ? '已连接' : '待配置' }}</b></div><div class="provider-models"><span v-for="model in provider.models" :key="model">{{ model }}</span></div><div class="model-metrics"><div><span>LATENCY</span><strong>{{ provider.latency }}</strong></div><div><span>CONTEXT</span><strong>{{ provider.context }}</strong></div></div><div class="agent-modes"><span>AGENT MODES</span><div><code v-for="mode in provider.modes" :key="mode">{{ mode }}</code></div></div><div class="provider-abilities"><span v-for="ability in provider.abilities" :key="ability"><el-icon><Check /></el-icon>{{ ability }}</span></div><div class="provider-foot"><span>{{ provider.status === 'ready' ? 'Gateway heartbeat normal' : '需要配置 API Key' }}</span><router-link to="/docs">接入说明 <el-icon><ArrowRight /></el-icon></router-link></div></article><div v-if="!filtered.length" class="empty-models">没有匹配的模型供应商</div></div></section>
    <section class="route-policy"><div class="policy-copy"><span class="site-kicker">路由策略</span><h2>先定义偏好，平台再做选择。</h2><p>在质量、成本和延迟之间建立清晰规则，并为生产故障准备备用模型。</p></div><div class="policy-board"><div><span>客服问答</span><strong>时延优先</strong><small>Qwen Plus → GPT-4o mini</small><b>420 ms</b></div><div><span>合同审阅</span><strong>质量优先</strong><small>Claude Sonnet → DeepSeek V3</small><b>3.2 s</b></div><div><span>批量分类</span><strong>成本优先</strong><small>GLM Flash → Qwen Turbo</small><b>¥0.006</b></div><div><span>敏感分析</span><strong>私有优先</strong><small>本地 vLLM → 人工处理</small><b>LOCAL</b></div></div></section>
    <section class="site-cta-band"><div><span class="site-kicker">一次配置，全局复用</span><h2>在模型网关中连接供应商，现有 Agent 即可按策略使用。</h2><p>凭证不会进入 Agent 配置，切换模型也不需要改业务代码。</p></div><router-link to="/docs">查看接入文档 <el-icon><ArrowRight /></el-icon></router-link></section>
  </main><SiteFooter /></div>
</template>

<style scoped>
.model-hero { min-height: 590px; padding: 92px max(30px, calc((100vw - 1240px) / 2)); display: grid; grid-template-columns: 1.12fr .88fr; gap: 90px; align-items: center; background: #edf2f5; }.model-hero h1 { max-width: 760px; margin-top: 20px; font-size: 58px; line-height: 1.08; }.model-hero > div:first-child > p { max-width: 700px; margin-top: 24px; color: #687984; font-size: 16px; line-height: 1.8; }.model-selector { padding: 24px; border: 1px solid #cad8e0; border-radius: 8px; background: rgba(255,255,255,.72); box-shadow: 16px 16px 0 #dce6eb; }.model-selector > span { color: var(--site-blue); font-size: 12px; font-weight: 700; }.selector-modes { margin-top: 16px; padding: 3px; display: grid; grid-template-columns: repeat(3, 1fr); border: 1px solid var(--site-line); border-radius: 7px; background: #eef3f5; }.selector-modes button { min-height: 36px; border: 0; border-radius: 5px; background: transparent; color: #71838e; font: inherit; font-size: 12px; cursor: pointer; }.selector-modes button.active { background: white; color: #4d6e83; box-shadow: 0 2px 7px rgba(69,92,106,.08); }.recommendation { margin-top: 18px; padding: 20px; border-radius: 7px; background: #54748a; color: white; }.recommendation small { color: #cddbe3; font-size: 12px; }.recommendation > strong { display: block; margin-top: 10px; font-size: 22px; }.recommendation > p { margin-top: 8px; color: #d8e3e9; font-size: 12px; line-height: 1.6; }.recommendation > div { margin-top: 20px; padding-top: 14px; display: flex; justify-content: space-between; border-top: 1px solid rgba(255,255,255,.2); color: #c9d8e0; font-size: 12px; }.recommendation b { display: block; margin-top: 4px; color: white; }
.model-principles { padding: 0 max(30px, calc((100vw - 1240px) / 2)); display: grid; grid-template-columns: repeat(4, 1fr); background: white; }.model-principles > div { min-height: 190px; padding: 28px 24px; display: flex; flex-direction: column; border-right: 1px solid var(--site-line); }.model-principles > div:first-child { border-left: 1px solid var(--site-line); }.model-principles span { color: var(--site-blue); font-size: 12px; font-weight: 700; }.model-principles strong { margin-top: 38px; font-size: 15px; }.model-principles p { margin-top: 9px; color: var(--site-muted); font-size: 12px; line-height: 1.65; }
.provider-section { padding: 100px max(30px, calc((100vw - 1240px) / 2)); background: var(--site-paper); }.provider-heading { display: flex; align-items: end; justify-content: space-between; gap: 30px; }.provider-heading h2 { margin-top: 14px; font-size: 39px; }.provider-tools { display: flex; gap: 10px; }.region-filter, .provider-tools label { min-height: 42px; display: flex; align-items: center; border: 1px solid var(--site-line); border-radius: 7px; background: white; overflow: hidden; }.region-filter > .el-icon { margin-left: 12px; color: #8a958f; }.region-filter button { align-self: stretch; padding: 0 11px; border: 0; background: transparent; color: var(--site-muted); font: inherit; font-size: 12px; cursor: pointer; }.region-filter button.active { background: var(--site-blue-soft); color: #527289; }.provider-tools label { width: 220px; padding: 0 11px; gap: 7px; }.provider-tools input { min-width: 0; width: 100%; border: 0; outline: 0; font: inherit; font-size: 12px; }.provider-grid { margin-top: 28px; display: grid; grid-template-columns: repeat(2, 1fr); gap: 14px; }.provider-grid article { min-height: 250px; padding: 22px; display: flex; flex-direction: column; border: 1px solid var(--site-line); border-radius: 8px; background: white; }.provider-top { display: grid; grid-template-columns: 42px 1fr auto; gap: 11px; align-items: center; }.provider-top > span { width: 42px; height: 42px; display: grid; place-items: center; border-radius: 8px; color: white; font-size: 12px; font-weight: 800; }.provider-top p { display: flex; flex-direction: column; gap: 4px; }.provider-top strong { font-size: 13px; }.provider-top small { color: var(--site-muted); font-size: 12px; }.provider-top > b { display: flex; align-items: center; gap: 5px; color: #8b958f; font-size: 12px; }.provider-top > b i { width: 7px; height: 7px; border-radius: 50%; background: var(--site-amber); }.provider-top > b.ready i { background: var(--site-primary); }.provider-models { margin-top: 22px; display: flex; flex-wrap: wrap; gap: 7px; }.provider-models span { padding: 7px 9px; border-radius: 5px; background: #f2f5f2; color: var(--site-ink-soft); font-size: 12px; }.provider-abilities { margin-top: 14px; display: flex; gap: 15px; }.provider-abilities span { display: flex; align-items: center; gap: 5px; color: var(--site-muted); font-size: 12px; }.provider-abilities .el-icon { color: var(--site-primary); }.provider-foot { margin-top: auto; padding-top: 15px; display: flex; justify-content: space-between; border-top: 1px solid var(--site-line); color: #87928c; font-size: 12px; }.provider-foot a { display: flex; align-items: center; gap: 5px; color: #55778c; text-decoration: none; font-weight: 700; }.empty-models { grid-column: 1 / -1; padding: 60px; text-align: center; color: var(--site-muted); }.route-policy { padding: 96px max(30px, calc((100vw - 1240px) / 2)); display: grid; grid-template-columns: .65fr 1.35fr; gap: 80px; background: #f3eee5; }.policy-copy h2 { margin-top: 18px; font-size: 39px; line-height: 1.16; }.policy-copy p { margin-top: 16px; color: var(--site-muted); font-size: 13px; line-height: 1.7; }.policy-board { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }.policy-board > div { min-height: 170px; padding: 20px; display: flex; flex-direction: column; border: 1px solid #ddd6ca; border-radius: 8px; background: rgba(255,255,255,.72); }.policy-board span { color: #8b958f; font-size: 12px; }.policy-board strong { margin-top: 18px; font-size: 16px; }.policy-board small { margin-top: 7px; color: var(--site-muted); font-size: 12px; }.policy-board b { margin-top: auto; color: var(--site-coral); font-size: 12px; }
@media (max-width: 980px) { .model-hero, .route-policy { grid-template-columns: 1fr; }.provider-heading { align-items: start; flex-direction: column; }.model-principles { grid-template-columns: 1fr 1fr; } }
@media (max-width: 700px) { .model-hero { padding: 65px 16px; }.model-hero h1 { font-size: 39px; }.model-principles { padding: 0 16px; grid-template-columns: 1fr; }.model-principles > div { min-height: 150px; border-left: 1px solid var(--site-line); border-bottom: 1px solid var(--site-line); }.provider-section, .route-policy { padding: 72px 16px; }.provider-tools { width: 100%; flex-direction: column; }.region-filter { overflow-x: auto; }.provider-tools label { width: 100%; }.provider-grid, .policy-board { grid-template-columns: 1fr; } }
</style>

<style scoped>
.models-page { color: #edf3ef; background: #0d1013; }
.model-hero { background: #0b0f12; }
.model-hero h1,
.provider-heading h2,
.policy-copy h2,
.model-principles strong,
.policy-board strong { color: #edf3ef; }
.model-hero > div:first-child > p,
.policy-copy p { color: #879198; }
.model-selector {
  border-color: #303a40;
  background: #12171a;
  box-shadow: 16px 16px 0 #111a16;
}
.model-selector > span { color: #76bce8; }
.selector-modes { border-color: #30383e; background: #0b0f11; }
.selector-modes button { color: #758087; }
.selector-modes button.active {
  background: #192a22;
  color: #80e6b2;
  box-shadow: inset 0 0 0 1px #365342;
}
.recommendation { background: #234158; }
.model-principles { background: #101417; }
.model-principles > div { border-color: #293137; }
.model-principles span { color: #75bce6; }
.provider-section { background: #0d1013; }
.region-filter,
.provider-tools label { border-color: #30383e; background: #111518; }
.region-filter button.active { background: #182a35; color: #86c7ed; }
.provider-tools input { background: transparent; color: #dce5df; }
.provider-tools input::placeholder { color: #5f6a71; }
.provider-top strong { color: #e4ebe7; }
.provider-models span { background: #1a211e; color: #bdc9c2; }
.provider-foot a { color: #7cc3ea; }
.route-policy { background: #141611; }
.policy-board > div {
  border-color: #37392f;
  background: #181b16;
}
.policy-board b { color: #f28a72; }
</style>

<style scoped>
.provider-heading > div:first-child > p { margin-top: 8px; color: var(--site-muted); font-size: 12px; }
.status-legend { min-height: 42px; margin-top: 24px; padding: 0 12px; display: flex; align-items: center; gap: 16px; border: 1px solid #2d353a; border-radius: 6px; background: #101417; color: #778289; font-size: 12px; }.status-legend span { display: flex; align-items: center; gap: 6px; }.status-legend i { width: 6px; height: 6px; border-radius: 50%; background: #e4b65e; }.status-legend i.ready { background: #5ce09d; box-shadow: 0 0 8px rgba(92,224,157,.55); }.status-legend b { margin-left: auto; color: #5d676e; font: 12px ui-monospace, monospace; }
.provider-grid article { position: relative; overflow: hidden; }.provider-grid article::before { content: ''; position: absolute; inset: 0 auto 0 0; width: 2px; background: #4bd891; }.provider-grid article.config::before { background: #dba94d; }.model-metrics { margin-top: 16px; display: grid; grid-template-columns: 1fr 1fr; border: 1px solid #2c3439; border-radius: 6px; overflow: hidden; }.model-metrics div { min-height: 64px; padding: 11px; display: flex; flex-direction: column; justify-content: flex-end; background: #101417; }.model-metrics div + div { border-left: 1px solid #2c3439; }.model-metrics span, .agent-modes > span { color: #5b656c; font: 6px ui-monospace, monospace; }.model-metrics strong { margin-top: 6px; color: #cbd4ce; font-size: 12px; }.agent-modes { margin-top: 14px; }.agent-modes > div { margin-top: 7px; display: flex; flex-wrap: wrap; gap: 5px; }.agent-modes code { padding: 5px 7px; border: 1px solid #2f3d36; border-radius: 4px; background: #142019; color: #7dddaa; font-size: 12px; }
@media (max-width: 700px) { .model-metrics { grid-template-columns: 1fr 1fr; }.status-legend { flex-wrap: wrap; }.status-legend b { margin-left: 0; } }
</style>
