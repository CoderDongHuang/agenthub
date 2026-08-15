<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, ArrowRight, Check, CopyDocument, Document, FolderOpened, Promotion, Reading, Search, TopRight } from '@element-plus/icons-vue'
import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js/lib/core'
import bash from 'highlight.js/lib/languages/bash'
import java from 'highlight.js/lib/languages/java'
import javascript from 'highlight.js/lib/languages/javascript'
import json from 'highlight.js/lib/languages/json'
import markdown from 'highlight.js/lib/languages/markdown'
import powershell from 'highlight.js/lib/languages/powershell'
import python from 'highlight.js/lib/languages/python'
import sql from 'highlight.js/lib/languages/sql'
import typescript from 'highlight.js/lib/languages/typescript'
import xml from 'highlight.js/lib/languages/xml'
import yaml from 'highlight.js/lib/languages/yaml'
import 'highlight.js/styles/github.css'
import SiteHeader from '../components/SiteHeader.vue'
import SiteFooter from '../components/SiteFooter.vue'
import { translateUiText } from '../i18n/uiText'

hljs.registerLanguage('bash', bash)
hljs.registerLanguage('shell', bash)
hljs.registerLanguage('java', java)
hljs.registerLanguage('javascript', javascript)
hljs.registerLanguage('json', json)
hljs.registerLanguage('markdown', markdown)
hljs.registerLanguage('powershell', powershell)
hljs.registerLanguage('python', python)
hljs.registerLanguage('sql', sql)
hljs.registerLanguage('typescript', typescript)
hljs.registerLanguage('xml', xml)
hljs.registerLanguage('yaml', yaml)

interface DocItem { no: string; title: string; desc: string; file: string; category: string; time: string; icon: any }
interface TocItem { id: string; title: string; level: number }

const route = useRoute()
const router = useRouter()
const { locale } = useI18n()
const docs: DocItem[] = [
  { no: '01', title: '快速开始', desc: '启动三端服务，登录工作台并完成第一次 Agent 对话。', file: '快速开始.md', category: '入门', time: '8 分钟', icon: Reading },
  { no: '02', title: '工作台使用指南', desc: '理解首页、Agent、流程、知识库、审批和审计的协作方式。', file: '工作台使用指南.md', category: '入门', time: '16 分钟', icon: FolderOpened },
  { no: '03', title: '架构设计', desc: 'Java 管理面、Python 执行面、Vue 工作台和数据边界。', file: '架构设计.md', category: '核心概念', time: '18 分钟', icon: Document },
  { no: '04', title: '模型接入指南', desc: '配置 OpenAI 兼容模型、验证连接并绑定到 Agent。', file: '模型接入指南.md', category: '开发扩展', time: '15 分钟', icon: Document },
  { no: '05', title: '工具开发指南', desc: '定义参数、风险等级、执行器和审批要求。', file: '工具开发指南.md', category: '开发扩展', time: '18 分钟', icon: Document },
  { no: '06', title: '知识库指南', desc: '上传 PDF、DOCX 等资料，切分、索引和检索内容。', file: '知识库指南.md', category: '工作台', time: '14 分钟', icon: Document },
  { no: '07', title: '多 Agent 编排', desc: '建立流程节点、人工卡点、运行记录和失败策略。', file: '多Agent编排指南.md', category: '工作台', time: '15 分钟', icon: Document },
  { no: '08', title: '安全护栏', desc: '配置隐私脱敏、提示词攻击检测和服务端试验台。', file: '安全护栏指南.md', category: '治理', time: '12 分钟', icon: Document },
  { no: '09', title: '渠道接入', desc: '接入网页、API、企业微信、钉钉、飞书和 Webhook。', file: '渠道接入指南.md', category: '工作台', time: '13 分钟', icon: Document },
  { no: '10', title: '权限与审批', desc: '角色、权限点、高风险工具审批与审计链路。', file: '权限与审批指南.md', category: '治理', time: '14 分钟', icon: Document },
  { no: '11', title: '部署运维', desc: '环境变量、Docker、健康检查、备份和升级流程。', file: '部署运维指南.md', category: '运维', time: '20 分钟', icon: Document },
  { no: '12', title: 'API 参考', desc: '认证、Agent、知识库、工作区和用量接口示例。', file: 'API参考.md', category: '参考', time: '22 分钟', icon: Document },
  { no: '13', title: '故障排查', desc: '定位登录、CORS、模型、索引、Webhook 和数据库问题。', file: '故障排查.md', category: '参考', time: '12 分钟', icon: Document },
  { no: '14', title: '验收与配置清单', desc: '查看三端链路验收结果、当前配置状态和剩余外部限制。', file: '全链路验收与配置清单.md', category: '运维', time: '10 分钟', icon: Document },
]
const categories = ['全部', ...new Set(docs.map(doc => doc.category))]
const selected = ref<DocItem | null>(null)
const category = ref('全部')
const search = ref('')
const html = ref('')
const loading = ref(false)
const error = ref('')
const toc = ref<TocItem[]>([])
const sdkTab = ref<'curl' | 'python' | 'java'>('curl')
const copied = ref(false)
const apiRunning = ref(false)
const readyApiResponse = () => JSON.stringify({
  status: 'ready',
  message: locale.value === 'en-US' ? 'Send a request to verify the local Java Gateway' : '点击发送请求，检查本地 Java Gateway',
}, null, 2)
const apiResponse = ref(readyApiResponse())
const snippets = {
  curl: `curl -X GET http://localhost:8080/api/auth/csrf \\
  -H "Accept: application/json"`,
  python: `import requests

response = requests.get(
    "http://localhost:8080/api/auth/csrf",
    timeout=10,
)
print(response.json())`,
  java: `var client = HttpClient.newHttpClient();
var request = HttpRequest.newBuilder()
    .uri(URI.create("http://localhost:8080/api/auth/csrf"))
    .GET()
    .build();
var response = client.send(request,
    HttpResponse.BodyHandlers.ofString());`,
}
const sdkOptions = [
  { id: 'curl' as const, label: 'Curl' },
  { id: 'python' as const, label: 'Python SDK' },
  { id: 'java' as const, label: 'Java SDK' },
]

const filteredDocs = computed(() => docs.filter(doc => {
  const matchesCategory = category.value === '全部' || doc.category === category.value
  const keyword = search.value.trim().toLowerCase()
  const source = `${doc.title} ${doc.desc} ${doc.category}`
  const searchable = `${source} ${translateUiText(source, locale.value as 'zh-CN' | 'en-US')}`.toLowerCase()
  return matchesCategory && (!keyword || searchable.includes(keyword))
}))
const selectedIndex = computed(() => selected.value ? docs.findIndex(doc => doc.file === selected.value?.file) : -1)
const previousDoc = computed(() => selectedIndex.value > 0 ? docs[selectedIndex.value - 1] : null)
const nextDoc = computed(() => selectedIndex.value >= 0 && selectedIndex.value < docs.length - 1 ? docs[selectedIndex.value + 1] : null)
const activeSnippet = computed(() => snippets[sdkTab.value])

async function copyQuickStart() {
  await navigator.clipboard.writeText(activeSnippet.value)
  copied.value = true
  window.setTimeout(() => { copied.value = false }, 1200)
}

async function runApiPreview() {
  apiRunning.value = true
  const started = performance.now()
  try {
    const response = await fetch('/api/auth/csrf', { headers: { Accept: 'application/json' } })
    const payload = await response.json()
    apiResponse.value = JSON.stringify({
      status: response.ok ? 'ok' : 'error',
      httpStatus: response.status,
      latencyMs: Math.round(performance.now() - started),
      data: { tokenIssued: Boolean(payload?.data?.token || payload?.token), headerName: 'X-XSRF-TOKEN' },
    }, null, 2)
  } catch (cause) {
    apiResponse.value = JSON.stringify({ status: 'error', message: cause instanceof Error ? cause.message : '请求失败' }, null, 2)
  } finally { apiRunning.value = false }
}

function slugify(value: string, index: number) {
  const slug = value.toLowerCase().trim().replace(/[^\w\u4e00-\u9fa5]+/g, '-').replace(/^-|-$/g, '')
  return `${slug || 'section'}-${index}`
}

function renderMarkdown(source: string) {
  toc.value = []
  let headingIndex = 0
  const markdown = new MarkdownIt({
    html: false,
    linkify: true,
    typographer: true,
    highlight(code, language) {
      const highlighted = language && hljs.getLanguage(language) ? hljs.highlight(code, { language }).value : hljs.highlightAuto(code).value
      return `<pre class="hljs"><div class="code-head"><span>${language || 'text'}</span><button class="copy-code" type="button" aria-label="复制代码"><span>复制</span></button></div><code>${highlighted}</code></pre>`
    },
  })
  markdown.renderer.rules.heading_open = (tokens, index, options, _env, self) => {
    const token = tokens[index]
    const title = tokens[index + 1]?.content || ''
    const level = Number(token.tag.slice(1))
    const id = slugify(title, headingIndex++)
    token.attrSet('id', id)
    if (level === 2 || level === 3) toc.value.push({ id, title, level })
    return self.renderToken(tokens, index, options)
  }
  return markdown.render(source)
}

async function openDoc(doc: DocItem, updateRoute = true) {
  selected.value = doc
  html.value = ''
  error.value = ''
  loading.value = true
  if (updateRoute) await router.replace({ path: '/docs', query: { doc: doc.file } })
  window.scrollTo({ top: 0, behavior: 'smooth' })
  try {
    const response = await fetch(`/docs/${encodeURIComponent(doc.file)}`)
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    html.value = renderMarkdown(await response.text())
    await nextTick()
  } catch (cause) {
    error.value = `文档加载失败：${cause instanceof Error ? cause.message : '未知错误'}`
  } finally { loading.value = false }
}

async function closeDoc() {
  selected.value = null
  html.value = ''
  toc.value = []
  await router.replace('/docs')
}

function scrollToHeading(id: string) {
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

async function handleReaderClick(event: MouseEvent) {
  const button = (event.target as HTMLElement).closest('.copy-code')
  if (!button) return
  const code = button.closest('pre')?.querySelector('code')?.textContent || ''
  try {
    await navigator.clipboard.writeText(code)
  } catch {
    const textarea = document.createElement('textarea')
    textarea.value = code
    textarea.style.position = 'fixed'
    textarea.style.opacity = '0'
    document.body.appendChild(textarea)
    textarea.select()
    document.execCommand('copy')
    textarea.remove()
  }
  const label = button.querySelector('span')
  if (label) label.textContent = '已复制'
  window.setTimeout(() => { if (label) label.textContent = '复制' }, 1200)
}

watch(() => route.query.doc, (file) => {
  if (!file) return
  const doc = docs.find(item => item.file === file)
  if (doc && doc.file !== selected.value?.file) openDoc(doc, false)
})
watch(locale, () => {
  if (apiResponse.value.includes('"status": "ready"')) apiResponse.value = readyApiResponse()
})

onMounted(() => {
  const file = String(route.query.doc || '')
  const doc = docs.find(item => item.file === file)
  if (doc) openDoc(doc, false)
})
</script>

<template>
  <div class="public-page docs-page">
    <SiteHeader />
    <main>
      <template v-if="!selected">
        <section class="docs-hero">
          <div><span class="site-kicker">AgentHub 手册</span><h1>从第一次运行，<br>到真实业务接入。</h1><p>这里不是功能清单。每篇文档都给出可执行步骤、配置位置、请求示例和验证方式。</p></div>
          <aside><span>推荐起点</span><strong>快速开始</strong><p>启动 PostgreSQL、Redis、Java、Python 和 Web，完成第一次真实对话。</p><button @click="openDoc(docs[0])">开始阅读 <el-icon><ArrowRight /></el-icon></button></aside>
        </section>
        <section class="quickstart-lab">
          <div class="quickstart-copy"><span class="site-kicker">3 MINUTE QUICK START</span><h2>一段代码，确认网关已经可用。</h2><p>切换调用方式、复制示例，然后向当前本地 Java Gateway 发送真实请求。预览不会泄露 CSRF Token 的具体值。</p><ol><li><span>01</span><strong>启动三端服务</strong><small>Java :8080 · Python :8000 · Vue :5173</small></li><li><span>02</span><strong>获取安全令牌</strong><small>验证会话与网关链路</small></li><li><span>03</span><strong>进入工作台</strong><small>创建并测试第一个 Agent</small></li></ol></div>
          <div class="sdk-playground"><header><nav><button v-for="item in sdkOptions" :key="item.id" :class="{ active: sdkTab === item.id }" @click="sdkTab = item.id">{{ item.label }}</button></nav><button aria-label="复制代码" @click="copyQuickStart"><el-icon><component :is="copied ? Check : CopyDocument" /></el-icon>{{ copied ? '已复制' : '复制' }}</button></header><pre><code>{{ activeSnippet }}</code></pre><footer><span><i /> localhost:8080</span><button :disabled="apiRunning" @click="runApiPreview"><el-icon><Promotion /></el-icon>{{ apiRunning ? '请求中' : '发送 API 请求' }}</button></footer></div>
          <aside class="api-preview"><header><span>RESPONSE PREVIEW</span><b>JSON</b></header><pre>{{ apiResponse }}</pre><footer><span>真实本地请求</span><i :class="{ running: apiRunning }" /></footer></aside>
        </section>
        <section class="docs-toolbar">
          <div class="doc-search"><el-icon><Search /></el-icon><input v-model="search" placeholder="搜索文档、功能或关键词" /></div>
          <nav><button v-for="item in categories" :key="item" :class="{ active: category === item }" @click="category = item">{{ item }}</button></nav>
        </section>
        <section class="docs-library">
          <header><div><span>{{ filteredDocs.length }} 篇指南</span><h2>{{ category === '全部' ? '完整文档库' : category }}</h2></div><p>按实际任务选择文档，代码片段可以直接复制。</p></header>
          <div class="doc-grid"><article v-for="doc in filteredDocs" :key="doc.file" @click="openDoc(doc)"><span class="doc-no">{{ doc.no }}</span><div class="doc-type"><el-icon><component :is="doc.icon" /></el-icon>{{ doc.category }}</div><h3>{{ doc.title }}</h3><p>{{ doc.desc }}</p><footer><span>{{ doc.time }}</span><button :aria-label="`打开${doc.title}`"><el-icon><ArrowRight /></el-icon></button></footer></article></div>
          <div v-if="!filteredDocs.length" class="docs-empty">没有匹配的文档</div>
        </section>
      </template>

      <section v-else class="doc-reader-shell">
        <aside class="reader-nav">
          <button class="reader-back" @click="closeDoc"><el-icon><ArrowLeft /></el-icon> 返回文档库</button>
          <div class="reader-current"><span>{{ selected.category }}</span><strong>{{ selected.title }}</strong><small>{{ selected.time }}</small></div>
          <nav><button v-for="doc in docs" :key="doc.file" :class="{ active: selected.file === doc.file }" @click="openDoc(doc)"><span>{{ doc.no }}</span>{{ doc.title }}</button></nav>
        </aside>
        <article class="reader-content" @click="handleReaderClick">
          <div class="reader-meta"><span>{{ selected.category }}</span><b>最后校对：2026-08-14</b><a :href="`/docs/${encodeURIComponent(selected.file)}`" target="_blank">原始文件 <el-icon><TopRight /></el-icon></a></div>
          <div v-if="loading" class="reader-state">正在读取文档...</div>
          <div v-else-if="error" class="reader-error">{{ error }}</div>
          <div v-else class="markdown-body" v-html="html" />
          <footer class="reader-pager"><button v-if="previousDoc" @click="openDoc(previousDoc)"><el-icon><ArrowLeft /></el-icon><span><small>上一篇</small>{{ previousDoc.title }}</span></button><i /><button v-if="nextDoc" @click="openDoc(nextDoc)"><span><small>下一篇</small>{{ nextDoc.title }}</span><el-icon><ArrowRight /></el-icon></button></footer>
        </article>
        <aside class="reader-toc"><span>本页目录</span><nav><button v-for="item in toc" :key="item.id" :class="`level-${item.level}`" @click="scrollToHeading(item.id)">{{ item.title }}</button></nav></aside>
      </section>
    </main>
    <SiteFooter v-if="!selected" />
  </div>
</template>

<style scoped>
.docs-page { min-height: 100vh; background: #f7f8f5; }
.docs-hero { width: min(1240px, calc(100% - 64px)); min-height: 430px; margin: 0 auto; padding: 76px 0 58px; display: grid; grid-template-columns: 1.25fr .75fr; gap: 90px; align-items: center; border-bottom: 1px solid var(--site-line); }
.docs-hero h1 { margin-top: 18px; font-family: Georgia, "Songti SC", serif; font-size: 56px; line-height: 1.12; }
.docs-hero > div > p { max-width: 650px; margin-top: 22px; color: var(--site-muted); font-size: 15px; line-height: 1.8; }
.docs-hero aside { padding: 25px; border-left: 4px solid var(--site-primary); background: #e9efe9; }
.docs-hero aside > span { color: var(--site-primary); font-size: 10px; font-weight: 750; }.docs-hero aside strong { display: block; margin-top: 18px; font-size: 25px; }.docs-hero aside p { margin-top: 10px; color: var(--site-muted); font-size: 11px; line-height: 1.7; }.docs-hero aside button { min-height: 40px; margin-top: 20px; padding: 0 13px; display: inline-flex; align-items: center; gap: 7px; border: 0; border-radius: 6px; background: var(--site-primary-dark); color: white; font: inherit; font-size: 10px; font-weight: 700; cursor: pointer; }
.docs-toolbar { width: min(1240px, calc(100% - 64px)); margin: 30px auto 0; display: flex; align-items: center; justify-content: space-between; gap: 20px; }.doc-search { width: 330px; min-height: 42px; padding: 0 13px; display: flex; align-items: center; gap: 8px; border: 1px solid var(--site-line); background: white; }.doc-search input { width: 100%; border: 0; outline: 0; color: var(--site-ink); font: inherit; font-size: 10px; }.docs-toolbar nav { display: flex; gap: 4px; overflow-x: auto; }.docs-toolbar nav button { min-height: 34px; padding: 0 11px; border: 0; border-bottom: 2px solid transparent; background: transparent; color: var(--site-muted); font: inherit; font-size: 9px; cursor: pointer; white-space: nowrap; }.docs-toolbar nav button.active { border-color: var(--site-primary); color: var(--site-primary-dark); font-weight: 700; }
.docs-library { width: min(1240px, calc(100% - 64px)); margin: 30px auto 90px; }.docs-library > header { padding: 26px 0; display: flex; justify-content: space-between; align-items: end; border-top: 1px solid var(--site-line); border-bottom: 1px solid var(--site-line); }.docs-library header span { color: var(--site-primary); font-size: 9px; font-weight: 700; }.docs-library h2 { margin-top: 6px; font-size: 28px; }.docs-library header p { color: var(--site-muted); font-size: 10px; }
.doc-grid { display: grid; grid-template-columns: repeat(3, 1fr); border-left: 1px solid var(--site-line); }.doc-grid article { position: relative; min-height: 260px; padding: 24px; display: flex; flex-direction: column; border-right: 1px solid var(--site-line); border-bottom: 1px solid var(--site-line); background: white; cursor: pointer; transition: background .2s, transform .2s; }.doc-grid article:hover { z-index: 1; background: #edf2ed; transform: translateY(-3px); }.doc-no { position: absolute; right: 20px; top: 18px; color: #c0c8c1; font: 11px ui-monospace, monospace; }.doc-type { display: flex; align-items: center; gap: 7px; color: var(--site-primary); font-size: 9px; font-weight: 700; }.doc-grid h3 { margin-top: 37px; font-size: 20px; }.doc-grid p { margin-top: 10px; color: var(--site-muted); font-size: 10px; line-height: 1.65; }.doc-grid footer { margin-top: auto; padding-top: 22px; display: flex; align-items: center; justify-content: space-between; }.doc-grid footer span { color: #8c968f; font-size: 8px; }.doc-grid footer button { width: 34px; height: 34px; display: grid; place-items: center; border: 1px solid var(--site-line); border-radius: 6px; background: white; color: var(--site-ink); cursor: pointer; }.docs-empty { padding: 80px 0; color: var(--site-muted); text-align: center; }
.doc-reader-shell { min-height: calc(100vh - 64px); display: grid; grid-template-columns: 240px minmax(0, 860px) 200px; justify-content: center; background: white; }.reader-nav { height: calc(100vh - 64px); position: sticky; top: 64px; padding: 24px 18px; overflow-y: auto; border-right: 1px solid var(--site-line); background: #f2f4f0; }.reader-back { padding: 0; display: flex; align-items: center; gap: 7px; border: 0; background: transparent; color: var(--site-ink); font: inherit; font-size: 10px; font-weight: 700; cursor: pointer; }.reader-current { margin: 26px 0 15px; padding: 17px 9px; display: flex; flex-direction: column; gap: 6px; border-top: 1px solid var(--site-line); border-bottom: 1px solid var(--site-line); }.reader-current span, .reader-current small { color: var(--site-muted); font-size: 8px; }.reader-current strong { font-size: 13px; }.reader-nav nav button { width: 100%; min-height: 37px; padding: 0 8px; display: grid; grid-template-columns: 29px 1fr; align-items: center; border: 0; border-left: 2px solid transparent; background: transparent; color: var(--site-muted); font: inherit; font-size: 9px; text-align: left; cursor: pointer; }.reader-nav nav button.active { border-color: var(--site-primary); background: white; color: var(--site-primary-dark); font-weight: 700; }.reader-nav nav span { color: #9da69f; font: 8px ui-monospace, monospace; }
.reader-content { min-width: 0; padding: 38px 56px 80px; border-right: 1px solid var(--site-line); }.reader-meta { display: flex; align-items: center; gap: 13px; padding-bottom: 18px; border-bottom: 1px solid var(--site-line); color: #8b958f; font-size: 8px; }.reader-meta span { color: var(--site-primary); font-weight: 700; }.reader-meta a { margin-left: auto; display: flex; align-items: center; gap: 4px; color: var(--site-primary-dark); text-decoration: none; }.reader-state, .reader-error { margin-top: 30px; padding: 20px; border: 1px solid var(--site-line); font-size: 10px; }.reader-error { background: #f8e8e4; color: #934f42; }
.reader-toc { height: calc(100vh - 64px); position: sticky; top: 64px; padding: 34px 22px; overflow-y: auto; }.reader-toc > span { color: var(--site-ink); font-size: 9px; font-weight: 750; }.reader-toc nav { margin-top: 14px; border-left: 1px solid var(--site-line); }.reader-toc button { width: 100%; padding: 7px 0 7px 12px; border: 0; background: transparent; color: var(--site-muted); font: inherit; font-size: 8px; line-height: 1.4; text-align: left; cursor: pointer; }.reader-toc button.level-3 { padding-left: 22px; color: #8c968f; }.reader-toc button:hover { color: var(--site-primary-dark); }
.reader-pager { margin-top: 54px; padding-top: 24px; display: grid; grid-template-columns: 1fr 1px 1fr; gap: 18px; border-top: 1px solid var(--site-line); }.reader-pager > i { background: var(--site-line); }.reader-pager button { padding: 0; display: flex; align-items: center; gap: 9px; border: 0; background: transparent; color: var(--site-ink); font: inherit; font-size: 10px; font-weight: 700; text-align: left; cursor: pointer; }.reader-pager button:last-child { justify-content: flex-end; text-align: right; }.reader-pager button span { display: flex; flex-direction: column; gap: 5px; }.reader-pager small { color: var(--site-muted); font-size: 8px; font-weight: 500; }
.markdown-body { color: #344239; font-size: 13px; line-height: 1.85; }.markdown-body :deep(h1) { margin: 38px 0 22px; font-family: Georgia, "Songti SC", serif; font-size: 39px; line-height: 1.25; letter-spacing: 0; }.markdown-body :deep(h2) { margin: 48px 0 17px; padding-top: 8px; font-size: 23px; line-height: 1.4; scroll-margin-top: 90px; }.markdown-body :deep(h3) { margin: 30px 0 12px; font-size: 16px; scroll-margin-top: 90px; }.markdown-body :deep(p) { margin: 13px 0; }.markdown-body :deep(a) { color: #3f6e58; }.markdown-body :deep(ul), .markdown-body :deep(ol) { margin: 13px 0; padding-left: 23px; }.markdown-body :deep(li) { margin: 6px 0; }.markdown-body :deep(blockquote) { margin: 20px 0; padding: 12px 16px; border-left: 4px solid #b88b43; background: #f7f0e3; color: #65583f; }.markdown-body :deep(table) { width: 100%; margin: 22px 0; border-collapse: collapse; font-size: 10px; }.markdown-body :deep(th), .markdown-body :deep(td) { padding: 10px 12px; border: 1px solid var(--site-line); text-align: left; }.markdown-body :deep(th) { background: #edf1ec; }.markdown-body :deep(code:not(pre code)) { padding: 2px 5px; border-radius: 3px; background: #edf1ec; color: #9a4d3b; font-size: .9em; }.markdown-body :deep(pre.hljs) { margin: 22px 0; padding: 0; overflow: hidden; border: 1px solid #d9dfda; border-radius: 6px; background: #f6f8f6; }.markdown-body :deep(pre.hljs code) { display: block; padding: 17px; overflow-x: auto; font: 11px/1.65 ui-monospace, SFMono-Regular, Menlo, monospace; }.markdown-body :deep(.code-head) { min-height: 34px; padding: 0 10px 0 14px; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid #d9dfda; background: #e9ede9; color: #78837c; font: 8px ui-monospace, monospace; }.markdown-body :deep(.copy-code) { min-height: 24px; padding: 0 8px; border: 1px solid #cdd5ce; border-radius: 4px; background: white; color: #526158; font: inherit; cursor: pointer; }.markdown-body :deep(hr) { margin: 35px 0; border: 0; border-top: 1px solid var(--site-line); }
@media (max-width: 1100px) { .doc-reader-shell { grid-template-columns: 210px minmax(0, 1fr); }.reader-toc { display: none; }.doc-grid { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 760px) { .docs-hero { width: calc(100% - 32px); padding: 55px 0 40px; grid-template-columns: 1fr; gap: 35px; }.docs-hero h1 { font-size: 40px; }.docs-toolbar, .docs-library { width: calc(100% - 32px); }.docs-toolbar { align-items: stretch; flex-direction: column; }.doc-search { width: 100%; }.docs-toolbar nav { padding-bottom: 5px; }.docs-library > header { align-items: start; flex-direction: column; gap: 10px; }.doc-grid { grid-template-columns: 1fr; }.doc-reader-shell { display: block; }.reader-nav { height: auto; position: static; padding: 16px; border-right: 0; border-bottom: 1px solid var(--site-line); }.reader-current, .reader-nav nav { display: none; }.reader-content { padding: 24px 18px 60px; border-right: 0; }.markdown-body :deep(h1) { font-size: 32px; }.reader-pager { gap: 10px; }.reader-meta b { display: none; } }
</style>

<style scoped>
.docs-page { color: #edf3ef; background: #0d1013; }
.docs-hero { background: #0d1013; }
.docs-hero h1,
.docs-library h2,
.doc-grid h3,
.reader-current strong { color: #edf3ef; }
.docs-hero aside {
  border-color: #5ce3a1;
  background: #17231d;
}
.docs-hero aside > span { color: #6fe1a5; }
.docs-hero aside strong { color: #e2ebe5; }
.docs-hero aside button { background: #55df9c; color: #07130d; }
.docs-toolbar,
.docs-library { background: transparent; }
.doc-search { border-color: #343d43; background: #111518; }
.doc-search input { background: transparent; color: #e0e8e3; }
.doc-search input::placeholder { color: #626d74; }
.docs-toolbar nav button.active { color: #78e4ac; }
.doc-grid article { color: #dce5df; }
.doc-grid footer button { border-color: #343d43; background: #111619; color: #b9c5be; }
.doc-reader-shell { background: #0f1315; }
.reader-nav { background: #101417; }
.reader-back,
.reader-meta a { color: #7ae5ad; }
.reader-nav nav button.active { background: #19271f; color: #7ae7ad; }
.reader-content { background: #121619; }
.markdown-body { color: #b9c5be; }
.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3) { color: #edf3ef; }
.markdown-body :deep(a) { color: #79c8ee; }
.markdown-body :deep(blockquote) { border-color: #d7aa55; background: #211b10; color: #c9b989; }
.markdown-body :deep(th) { background: #19201d; }
.markdown-body :deep(code:not(pre code)) { background: #1a211e; color: #f29a86; }
.markdown-body :deep(pre.hljs),
.markdown-body :deep(pre.hljs code) { background: #0a0d0f; color: #b8c6be; }
.markdown-body :deep(.code-head) { border-color: #30383d; background: #14191c; color: #7f8a91; }
.markdown-body :deep(.copy-code) { border-color: #343d43; background: #101417; color: #a8b3ad; }
.reader-toc { background: #101417; }
</style>

<style scoped>
.quickstart-lab { width: min(1240px, calc(100% - 64px)); margin: 45px auto 18px; display: grid; grid-template-columns: .7fr 1fr .7fr; border: 1px solid #30383e; border-radius: 8px; background: #0f1315; box-shadow: 0 22px 58px rgba(0,0,0,.3); overflow: hidden; }.quickstart-copy { padding: 28px; }.quickstart-copy h2 { margin-top: 15px; color: #eaf1ed; font-size: 26px; line-height: 1.2; }.quickstart-copy > p { margin-top: 12px; color: #7e8990; font-size: 9px; line-height: 1.7; }.quickstart-copy ol { margin-top: 22px; list-style: none; }.quickstart-copy li { min-height: 52px; display: grid; grid-template-columns: 28px 1fr; align-content: center; border-top: 1px solid #283035; }.quickstart-copy li > span { grid-row: 1 / 3; align-self: center; color: #5b676e; font: 7px ui-monospace, monospace; }.quickstart-copy li strong { color: #bdc7c1; font-size: 9px; }.quickstart-copy li small { margin-top: 3px; color: #5e6970; font-size: 7px; }
.sdk-playground { min-width: 0; display: flex; flex-direction: column; border-left: 1px solid #2d353a; border-right: 1px solid #2d353a; background: #0b0f11; }.sdk-playground > header { min-height: 47px; padding: 0 10px; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid #283035; }.sdk-playground nav { height: 100%; display: flex; }.sdk-playground nav button { padding: 0 10px; border: 0; border-bottom: 2px solid transparent; background: transparent; color: #6a757c; font: inherit; font-size: 8px; cursor: pointer; }.sdk-playground nav button.active { border-color: #5ce09d; color: #86e6b3; }.sdk-playground > header > button { min-height: 28px; padding: 0 8px; display: inline-flex; align-items: center; gap: 5px; border: 1px solid #343d43; border-radius: 5px; background: #14191c; color: #8d989f; font: inherit; font-size: 7px; cursor: pointer; }.sdk-playground > pre { min-height: 260px; margin: 0; padding: 22px; overflow: auto; color: #a8c7b5; font: 9px/1.75 ui-monospace, SFMono-Regular, Menlo, monospace; white-space: pre-wrap; }.sdk-playground > footer { min-height: 48px; padding: 0 11px; display: flex; align-items: center; justify-content: space-between; border-top: 1px solid #283035; }.sdk-playground > footer > span { display: flex; align-items: center; gap: 6px; color: #626d74; font: 7px ui-monospace, monospace; }.sdk-playground > footer > span i { width: 6px; height: 6px; border-radius: 50%; background: #5cde9d; box-shadow: 0 0 8px rgba(92,222,157,.5); }.sdk-playground > footer button { min-height: 31px; padding: 0 10px; display: inline-flex; align-items: center; gap: 6px; border: 1px solid #55dd9c; border-radius: 5px; background: #50d994; color: #06120b; font: inherit; font-size: 8px; font-weight: 800; cursor: pointer; }
.api-preview { min-width: 0; display: flex; flex-direction: column; background: #111518; }.api-preview > header { min-height: 47px; padding: 0 13px; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid #283035; color: #626d74; font: 7px ui-monospace, monospace; }.api-preview > header b { color: #6fb8e2; }.api-preview pre { min-height: 260px; margin: 0; padding: 20px; overflow: auto; color: #8bb9d3; font: 8px/1.7 ui-monospace, monospace; white-space: pre-wrap; }.api-preview footer { min-height: 48px; padding: 0 13px; display: flex; align-items: center; justify-content: space-between; border-top: 1px solid #283035; color: #626d74; font-size: 7px; }.api-preview footer i { width: 7px; height: 7px; border-radius: 50%; background: #5cde9d; }.api-preview footer i.running { background: #e3b45a; animation: apiPulse 1s infinite; }
@keyframes apiPulse { 0%,100% { opacity: 1; } 50% { opacity: .35; } }
@media (max-width: 1040px) { .quickstart-lab { grid-template-columns: 1fr 1fr; }.quickstart-copy { grid-column: 1 / -1; border-bottom: 1px solid #2d353a; }.sdk-playground { border-left: 0; } }
@media (max-width: 760px) { .quickstart-lab { width: calc(100% - 32px); grid-template-columns: 1fr; }.quickstart-copy { grid-column: 1; }.sdk-playground { border-right: 0; border-bottom: 1px solid #2d353a; }.sdk-playground nav button { padding: 0 7px; }.api-preview pre, .sdk-playground > pre { min-height: 220px; } }
</style>
