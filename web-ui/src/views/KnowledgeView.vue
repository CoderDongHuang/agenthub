<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Collection, Delete, Document, Plus, Refresh, Upload } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api, { runtimeApi } from '../api'

interface KnowledgeDoc {
  id: number
  filename: string
  file_type?: string
  file_size?: number
  status?: string
  created_at?: string
}

const docs = ref<KnowledgeDoc[]>([])
const loading = ref(false)
const indexingId = ref<number | null>(null)
const uploadVisible = ref(false)
const textVisible = ref(false)
const textForm = ref({ title: '', content: '' })
const stats = ref({ total_chunks: 0, documents: 0 })
const uploadFile = ref<File | null>(null)

const indexedCount = computed(() => docs.value.filter(doc => doc.status === 'indexed').length)
const totalSize = computed(() => docs.value.reduce((sum, doc) => sum + Number(doc.file_size || 0), 0))

function formatSize(size: number) {
  if (!size) return '0 KB'
  return size >= 1024 * 1024 ? `${(size / 1024 / 1024).toFixed(1)} MB` : `${(size / 1024).toFixed(1)} KB`
}

async function fetchDocs() {
  loading.value = true
  try {
    const response = await api.get('/knowledge/docs') as any
    docs.value = response.data || []
  } finally {
    loading.value = false
  }
}

async function fetchStats() {
  try {
    const response = await runtimeApi.get('/rag/stats')
    stats.value = response.data?.stats || { total_chunks: 0, documents: 0 }
  } catch {
    stats.value = { total_chunks: 0, documents: 0 }
  }
}

async function refreshAll() {
  await Promise.all([fetchDocs(), fetchStats()])
}

async function handleUpload() {
  if (!uploadFile.value) return
  if (uploadFile.value.size > 25 * 1024 * 1024) {
    ElMessage.error('文件不能超过 25 MB')
    return
  }
  const formData = new FormData()
  formData.append('file', uploadFile.value)
  try {
    const response = await api.post('/knowledge/upload', formData, { headers: { 'Content-Type': 'multipart/form-data' } }) as any
    if (response.code !== 200) throw new Error(response.message || '文档解析失败')
    const documentId = Number(response.data?.id)
    if (documentId) {
      const indexResponse = await runtimeApi.post('/rag/index', null, { params: { doc_id: documentId } })
      if (indexResponse.data?.status !== 'ok') throw new Error(indexResponse.data?.message || '索引失败')
      ElMessage.success(`文档已解析并建立 ${indexResponse.data.chunks} 个索引分块`)
    } else {
      ElMessage.success('文档已上传')
    }
    uploadVisible.value = false
    uploadFile.value = null
    await fetchDocs()
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message || error?.message || '上传失败')
  }
}

async function handleAddText() {
  if (!textForm.value.title || !textForm.value.content) {
    ElMessage.warning('请填写标题和内容')
    return
  }
  try {
    const response = await api.post('/knowledge/text', textForm.value) as any
    if (response.code !== 200) throw new Error(response.message || '文本保存失败')
    const documentId = Number(response.data?.id)
    const indexResponse = await runtimeApi.post('/rag/index', null, { params: { doc_id: documentId } })
    if (indexResponse.data?.status !== 'ok') throw new Error(indexResponse.data?.message || '索引失败')
    ElMessage.success(`文本知识已添加并建立 ${indexResponse.data.chunks} 个索引分块`)
    textVisible.value = false
    textForm.value = { title: '', content: '' }
    await fetchDocs()
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message || error?.message || '添加失败')
  }
}

async function handleIndex(docId: number) {
  indexingId.value = docId
  try {
    const response = await runtimeApi.post('/rag/index', null, { params: { doc_id: docId } })
    if (response.data?.status !== 'ok') throw new Error(response.data?.message)
    ElMessage.success(`索引完成：${response.data.chunks} 个分块`)
    await refreshAll()
  } catch (error: any) {
    ElMessage.error(error?.message || '索引失败')
  } finally {
    indexingId.value = null
  }
}

async function handleDelete(doc: KnowledgeDoc) {
  try {
    await ElMessageBox.confirm(`确定删除“${doc.filename}”吗？`, '删除知识资产', { type: 'warning' })
    await runtimeApi.delete(`/rag/docs/${doc.id}`).catch(() => undefined)
    const response = await api.delete(`/knowledge/docs/${doc.id}`) as any
    if (response.code !== 200) throw new Error(response.message || '删除失败')
    ElMessage.success('文档已删除')
    await fetchDocs()
  } catch (error) {
    if (error !== 'cancel') ElMessage.error('删除失败')
  }
}

onMounted(refreshAll)
</script>

<template>
  <div class="console-page knowledge-page" v-loading="loading">
    <div class="console-page-head">
      <div class="console-page-head__copy"><span>检索与引用</span><h1>知识库</h1><p>把内部文档转成可检索、可复用、可追踪的 Agent 上下文。</p></div>
      <div class="console-page-actions"><button class="console-icon-button" aria-label="刷新" @click="refreshAll"><el-icon><Refresh /></el-icon></button><button class="console-secondary" @click="textVisible = true"><el-icon><Plus /></el-icon> 添加文本</button><button class="console-primary" @click="uploadVisible = true"><el-icon><Upload /></el-icon> 上传文档</button></div>
    </div>

    <section class="knowledge-summary">
      <div><span>文档</span><strong>{{ docs.length }}</strong><small>已纳入知识库</small></div>
      <div><span>已索引</span><strong>{{ indexedCount }}</strong><small>可以参与检索</small></div>
      <div><span>分块</span><strong>{{ stats.total_chunks || 0 }}</strong><small>向量检索分块</small></div>
      <div><span>存储</span><strong>{{ formatSize(totalSize) }}</strong><small>原始文档大小</small></div>
    </section>

    <div class="knowledge-layout">
      <section class="asset-library">
        <div class="library-heading"><div><span>知识资产</span><h2>文档目录</h2></div><small>{{ docs.length }} 项</small></div>
        <div v-if="docs.length" class="asset-list">
          <article v-for="doc in docs" :key="doc.id">
            <div class="asset-icon"><el-icon><Document /></el-icon></div>
            <div class="asset-copy"><span>{{ (doc.file_type || 'TEXT').toUpperCase() }} · {{ formatSize(doc.file_size || 0) }}</span><strong>{{ doc.filename }}</strong><small>ID {{ String(doc.id).padStart(4, '0') }} · {{ doc.created_at?.substring(0, 10) || '本地资产' }}</small></div>
            <div class="asset-status" :class="doc.status"><i />{{ doc.status || 'uploaded' }}</div>
            <div class="asset-actions"><button :disabled="indexingId === doc.id" @click="handleIndex(doc.id)">{{ indexingId === doc.id ? '索引中' : '建立索引' }}</button><button aria-label="删除" @click="handleDelete(doc)"><el-icon><Delete /></el-icon></button></div>
          </article>
        </div>
        <div v-else class="knowledge-empty"><el-icon><Collection /></el-icon><strong>知识库还是空的</strong><p>上传 PDF、Word、Excel、PPT、Markdown 或文本资料，建立第一个可检索资产。</p><button class="console-primary" @click="uploadVisible = true"><el-icon><Upload /></el-icon> 上传文档</button></div>
      </section>

      <aside class="index-inspector">
        <div class="inspector-head"><span>索引流程</span><strong>检索链路</strong></div>
        <ol><li><span>01</span><div><strong>内容接入</strong><small>文件或结构化文本</small></div><i :class="{ done: docs.length }" /></li><li><span>02</span><div><strong>文档分块</strong><small>500 字符 · 100 重叠</small></div><i :class="{ done: stats.total_chunks }" /></li><li><span>03</span><div><strong>向量索引</strong><small>pgvector / fallback</small></div><i :class="{ done: stats.total_chunks }" /></li><li><span>04</span><div><strong>Agent 检索</strong><small>按相关度注入 Prompt</small></div><i :class="{ done: stats.total_chunks }" /></li></ol>
        <div class="source-fragment"><span>retriever.py</span><pre><code><b>context</b> = retriever.search(
  query=message,
  top_k=<i>4</i>,
  threshold=<i>0.72</i>
)</code></pre></div>
      </aside>
    </div>

    <el-dialog v-model="uploadVisible" title="上传知识文档" width="460px">
      <label class="upload-drop"><el-icon><Upload /></el-icon><strong>{{ uploadFile?.name || '选择本地文档' }}</strong><span>支持 PDF、DOCX、XLSX、PPTX、TXT、MD、CSV、JSON、HTML，最大 25 MB</span><input type="file" accept=".pdf,.docx,.xlsx,.pptx,.txt,.md,.csv,.json,.html,.htm,.xml,.yaml,.yml" @change="(event: any) => uploadFile = event.target?.files?.[0] || null" /></label>
      <template #footer><el-button @click="uploadVisible = false">取消</el-button><el-button type="primary" :disabled="!uploadFile" @click="handleUpload">上传文档</el-button></template>
    </el-dialog>

    <el-dialog v-model="textVisible" title="添加文本知识" width="560px">
      <el-form :model="textForm" label-position="top"><el-form-item label="标题"><el-input v-model="textForm.title" placeholder="例如：差旅报销制度" /></el-form-item><el-form-item label="内容"><el-input v-model="textForm.content" type="textarea" :rows="10" placeholder="粘贴需要被 Agent 检索的内容" /></el-form-item></el-form>
      <template #footer><el-button @click="textVisible = false">取消</el-button><el-button type="primary" @click="handleAddText">添加到知识库</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.knowledge-summary { display: grid; grid-template-columns: repeat(4, 1fr); border-left: 1px solid var(--console-line); }
.knowledge-summary > div { min-height: 118px; padding: 18px; display: flex; flex-direction: column; border-top: 1px solid var(--console-line); border-right: 1px solid var(--console-line); border-bottom: 1px solid var(--console-line); background: white; }
.knowledge-summary span { color: var(--console-orange); font-family: ui-monospace, monospace; font-size: 7px; }
.knowledge-summary strong { margin-top: auto; font-size: 24px; }
.knowledge-summary small { margin-top: 4px; color: #8c9289; font-size: 8px; }
.knowledge-layout { margin-top: 16px; display: grid; grid-template-columns: 1fr 310px; gap: 16px; }
.asset-library { min-width: 0; border: 1px solid var(--console-line); background: white; }
.library-heading { min-height: 66px; padding: 0 20px; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid var(--console-line); }
.library-heading span { color: var(--console-orange); font-family: ui-monospace, monospace; font-size: 7px; }
.library-heading h2 { margin-top: 5px; font-size: 14px; }
.library-heading > small { color: #8c9289; font-family: ui-monospace, monospace; font-size: 7px; }
.asset-list article { min-height: 88px; padding: 14px 18px; display: grid; grid-template-columns: 42px minmax(160px, 1fr) 90px 128px; align-items: center; gap: 12px; border-bottom: 1px solid var(--console-line); }
.asset-list article:last-child { border-bottom: 0; }
.asset-list article:hover { background: #f7f8f4; }
.asset-icon { width: 38px; height: 38px; display: grid; place-items: center; background: var(--console-ink); color: white; }
.asset-copy { min-width: 0; display: flex; flex-direction: column; gap: 4px; }
.asset-copy > span { color: var(--console-orange); font-family: ui-monospace, monospace; font-size: 7px; }
.asset-copy strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 11px; }
.asset-copy small { color: #8b9188; font-size: 7px; }
.asset-status { display: flex; align-items: center; gap: 6px; color: #777d74; font-family: ui-monospace, monospace; font-size: 7px; text-transform: uppercase; }
.asset-status i { width: 6px; height: 6px; background: var(--console-yellow); }
.asset-status.indexed i { background: var(--console-green); }
.asset-actions { display: flex; justify-content: flex-end; gap: 6px; }
.asset-actions button { min-height: 30px; padding: 0 9px; border: 1px solid var(--console-line); background: white; color: var(--console-ink); font: inherit; font-size: 8px; cursor: pointer; }
.asset-actions button:hover { border-color: var(--console-orange); color: var(--console-orange); }
.asset-actions button:disabled { opacity: .5; cursor: wait; }
.knowledge-empty { min-height: 430px; display: flex; flex-direction: column; align-items: center; justify-content: center; text-align: center; }
.knowledge-empty > .el-icon { color: #90968d; font-size: 30px; }
.knowledge-empty strong { margin-top: 14px; font-size: 13px; }
.knowledge-empty p { max-width: 340px; margin: 8px 0 20px; color: var(--console-muted); font-size: 9px; line-height: 1.6; }
.index-inspector { padding: 20px; background: #171916; color: white; }
.inspector-head span { color: var(--console-orange); font-family: ui-monospace, monospace; font-size: 7px; }
.inspector-head strong { display: block; margin-top: 6px; font-size: 14px; }
.index-inspector ol { margin: 24px 0 0; padding: 0; list-style: none; }
.index-inspector li { min-height: 68px; display: grid; grid-template-columns: 30px 1fr 8px; align-items: center; border-top: 1px solid #3a3f38; }
.index-inspector li > span { color: #626960; font-family: ui-monospace, monospace; font-size: 7px; }
.index-inspector li div { display: flex; flex-direction: column; gap: 4px; }
.index-inspector li strong { font-size: 9px; }
.index-inspector li small { color: #747b71; font-size: 7px; }
.index-inspector li > i { width: 6px; height: 6px; background: #555c53; }
.index-inspector li > i.done { background: var(--console-green); }
.source-fragment { margin-top: 24px; border: 1px solid #3a3f38; background: #121411; }
.source-fragment > span { min-height: 30px; padding: 0 10px; display: flex; align-items: center; border-bottom: 1px solid #3a3f38; color: #737a70; font-family: ui-monospace, monospace; font-size: 7px; }
.source-fragment pre { margin: 0; padding: 14px; overflow: auto; color: #aeb4ab; font-family: ui-monospace, monospace; font-size: 8px; line-height: 1.7; }
.source-fragment b { color: #86b1d0; }
.source-fragment i { color: var(--console-orange); font-style: normal; }
.upload-drop { min-height: 190px; display: flex; flex-direction: column; align-items: center; justify-content: center; border: 1px dashed #aeb3ab; background: #f5f6f2; cursor: pointer; }
.upload-drop .el-icon { font-size: 26px; }
.upload-drop strong { margin-top: 12px; font-size: 12px; }
.upload-drop span { margin-top: 6px; color: #858b82; font-size: 9px; }
.upload-drop input { display: none; }
@media (max-width: 1000px) { .knowledge-layout { grid-template-columns: 1fr; } .index-inspector { min-height: 420px; } }
@media (max-width: 720px) { .knowledge-summary { grid-template-columns: 1fr 1fr; } .asset-list article { grid-template-columns: 38px 1fr auto; } .asset-status { grid-column: 2; } .asset-actions { grid-column: 3; grid-row: 1 / 3; flex-direction: column; } }
</style>
