<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import api from '../../api'

const router = useRouter()
const { t } = useI18n()

interface Agent {
  id: number
  name: string
  description: string
  systemPrompt: string
  model: string
  temperature: number
  maxTokens: number
  status: string
  icon: string
  createdBy: number
  createdAt: string
  updatedAt: string
}

const agents = ref<Agent[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const isEdit = ref(false)
const editId = ref<number | null>(null)
const saving = ref(false)
const search = ref('')
const statusFilter = ref('all')

const form = ref({
  name: '',
  description: '',
  systemPrompt: '',
  model: 'gpt-4o',
  temperature: 0.7,
  maxTokens: 4096,
})

const formTitle = computed(() => isEdit.value ? t('agent.editAgent') : t('agent.createAgent'))
const filteredAgents = computed(() => agents.value.filter((agent) => {
  const matchesStatus = statusFilter.value === 'all' || agent.status === statusFilter.value
  const keyword = search.value.trim().toLowerCase()
  const matchesKeyword = !keyword || [agent.name, agent.description, agent.model]
    .filter(Boolean)
    .some(value => value.toLowerCase().includes(keyword))
  return matchesStatus && matchesKeyword
}))

const modelOptions = ref<Array<{ label: string; value: string }>>([
  { label: 'gpt-4o (openai)', value: 'gpt-4o' },
  { label: 'gpt-4o-mini (openai)', value: 'gpt-4o-mini' },
  { label: 'claude-sonnet-4-5 (anthropic)', value: 'claude-sonnet-4-5' },
  { label: 'deepseek-v4-flash (deepseek)', value: 'deepseek-v4-flash' },
  { label: 'deepseek-v4-pro (deepseek)', value: 'deepseek-v4-pro' },
  { label: 'qwen-plus (qwen)', value: 'qwen-plus' },
  { label: 'qwen-turbo (qwen)', value: 'qwen-turbo' },
  { label: 'moonshot-v1-32k (moonshot)', value: 'moonshot-v1-32k' },
  { label: 'glm-4-plus (zhipu)', value: 'glm-4-plus' },
  { label: 'mistral-large-latest (mistral)', value: 'mistral-large-latest' },
])

const fetchModels = async () => {
  try {
    const response = await api.get('/platform/overview') as any
    const models = response.data?.runtime?.models?.models || []
    if (models.length) {
      modelOptions.value = models.map((model: any) => ({
        label: `${model.id} (${model.provider}${model.configured ? '' : ' - not configured'})`,
        value: model.id,
      }))
      const configured = models.find((model: any) => model.configured)
      if (!isEdit.value && configured) form.value.model = configured.id
    }
  } catch { /* Keep the bundled last-known-good catalog while runtime is offline. */ }
}

const statusTagType = (status: string) => {
  const map: Record<string, string> = { draft: 'info', published: 'success', disabled: 'danger' }
  return map[status] || 'info'
}

const statusLabel = (status: string) => {
  const map: Record<string, string> = { draft: t('common.draft'), published: t('common.online'), disabled: t('common.disabled') }
  return map[status] || status
}

const fetchAgents = async () => {
  loading.value = true
  try {
    const res = await api.get('/agents?size=50&sort=updatedAt,DESC') as any
    agents.value = res.data?.content || []
  } finally {
    loading.value = false
  }
}

const openCreate = () => {
  isEdit.value = false
  editId.value = null
  form.value = { name: '', description: '', systemPrompt: '', model: 'gpt-4o', temperature: 0.7, maxTokens: 4096 }
  dialogVisible.value = true
}

const openEdit = (row: Agent) => {
  isEdit.value = true
  editId.value = row.id
  form.value = {
    name: row.name,
    description: row.description || '',
    systemPrompt: row.systemPrompt,
    model: row.model,
    temperature: row.temperature,
    maxTokens: row.maxTokens,
  }
  dialogVisible.value = true
}

const handleSave = async () => {
  saving.value = true
  try {
    if (isEdit.value) {
      await api.put(`/agents/${editId.value}`, form.value)
      ElMessage.success(t('common.success'))
    } else {
      await api.post('/agents', form.value)
      ElMessage.success(t('common.success'))
    }
    dialogVisible.value = false
    fetchAgents()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || t('common.failed'))
  } finally {
    saving.value = false
  }
}

const handlePublish = async (row: Agent) => {
  try {
    await api.put(`/agents/${row.id}/publish`)
    ElMessage.success(t('common.success'))
    fetchAgents()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || t('common.failed'))
  }
}

const handleDisable = async (row: Agent) => {
  try {
    const action = row.status === 'published' ? t('agent.unpublish') : t('common.disable')
    await ElMessageBox.confirm(`${action} Agent "${row.name}"？`)
    await api.put(`/agents/${row.id}/disable`)
    ElMessage.success(t('common.success'))
    fetchAgents()
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error(e?.response?.data?.message || t('common.failed'))
  }
}

const handleDelete = async (row: Agent) => {
  try {
    await ElMessageBox.confirm(t('agent.deleteConfirm').replace('{name}', row.name), t('common.confirm'), { type: 'warning' })
    await api.delete(`/agents/${row.id}`)
    ElMessage.success(t('common.success'))
    fetchAgents()
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error(e?.response?.data?.message || t('common.failed'))
  }
}

const goDetail = (row: Agent) => {
  router.push(`/console/agents/${row.id}`)
}

onMounted(() => Promise.all([fetchAgents(), fetchModels()]))
</script>

<template>
  <div class="console-page agent-page">
    <div class="console-page-head">
      <div class="console-page-head__copy"><span>创建与发布</span><h1>Agent</h1><p>创建、验证、发布和维护组织中的业务 Agent。</p></div>
      <div class="console-page-actions"><button class="console-icon-button" aria-label="刷新" @click="fetchAgents"><el-icon><Refresh /></el-icon></button><button class="console-primary" @click="openCreate"><el-icon><Plus /></el-icon> 新建 Agent</button></div>
    </div>

    <div class="agent-toolbar">
      <div class="console-segmented"><button v-for="item in [{ label: '全部', value: 'all' }, { label: '已发布', value: 'published' }, { label: '草稿', value: 'draft' }, { label: '已停用', value: 'disabled' }]" :key="item.value" :class="{ active: statusFilter === item.value }" @click="statusFilter = item.value">{{ item.label }}</button></div>
      <label class="console-search"><el-icon><Search /></el-icon><input v-model="search" placeholder="搜索名称、描述或模型" /></label>
      <span class="agent-count">{{ filteredAgents.length }} / {{ agents.length }} AGENTS</span>
    </div>

    <div class="agent-table-shell">

    <el-table :data="filteredAgents" v-loading="loading">
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="name" :label="t('agent.name')" min-width="160">
        <template #default="{ row }">
          <el-link type="primary" @click="goDetail(row)">{{ row.name }}</el-link>
        </template>
      </el-table-column>
      <el-table-column prop="model" :label="t('agent.model')" width="140" />
      <el-table-column :label="t('common.status')" width="80">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('common.time')" width="170">
        <template #default="{ row }">
          {{ row.createdAt?.substring(0, 16)?.replace('T', ' ') }}
        </template>
      </el-table-column>
      <el-table-column :label="t('common.actions')" width="320" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="row.status === 'published'"
            type="success" link size="small"
            @click="router.push(`/console/agents/${row.id}/chat`)"
          >{{ t('agent.chat') }}</el-button>
          <el-button type="primary" link size="small" @click="openEdit(row)">{{ t('common.edit') }}</el-button>
          <el-button
            v-if="row.status === 'draft'"
            type="success" link size="small"
            @click="handlePublish(row)"
          >{{ t('agent.publish') }}</el-button>
          <el-button
            v-if="row.status === 'published'"
            type="warning" link size="small"
            @click="handleDisable(row)"
          >{{ t('agent.unpublish') }}</el-button>
          <el-button
            v-if="row.status === 'disabled'"
            type="warning" link size="small"
            @click="handleDisable(row)"
          >{{ t('common.disable') }}</el-button>
          <el-button type="danger" link size="small" @click="handleDelete(row)">{{ t('common.delete') }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-empty v-if="!loading && filteredAgents.length === 0" description="没有符合条件的 Agent" />
    </div>

  <!-- 创建/编辑弹窗 -->
  <el-dialog v-model="dialogVisible" :title="formTitle" width="640px" @close="dialogVisible = false">
    <el-form :model="form" label-width="100px">
      <el-form-item :label="t('agent.name')" required>
        <el-input v-model="form.name" :placeholder="t('agent.namePlaceholder')" maxlength="100" />
      </el-form-item>
      <el-form-item :label="t('agent.description')">
        <el-input v-model="form.description" type="textarea" :rows="2" :placeholder="t('agent.descPlaceholder')" />
      </el-form-item>
      <el-form-item :label="t('agent.systemPrompt')" required>
        <el-input v-model="form.systemPrompt" type="textarea" :rows="6"
          :placeholder="t('agent.promptPlaceholder')" />
      </el-form-item>
      <el-form-item :label="t('agent.model')">
        <el-select v-model="form.model" style="width: 100%">
          <el-option v-for="m in modelOptions" :key="m.value" :label="m.label" :value="m.value" />
        </el-select>
      </el-form-item>
      <el-row :gutter="20">
        <el-col :span="12">
          <el-form-item :label="t('agent.temperature')">
            <el-slider v-model="form.temperature" :min="0" :max="2" :step="0.1" show-input />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item :label="t('agent.maxTokens')">
            <el-input-number v-model="form.maxTokens" :min="256" :max="128000" :step="256" style="width: 100%" />
          </el-form-item>
        </el-col>
      </el-row>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
      <el-button type="primary" :loading="saving" @click="handleSave">{{ t('common.save') }}</el-button>
    </template>
  </el-dialog>
  </div>
</template>

<style scoped>
.agent-toolbar { min-height: 58px; padding: 8px; display: flex; align-items: center; gap: 10px; border: 1px solid var(--console-line); border-bottom: 0; background: #e4e6e0; }
.agent-toolbar .console-search { margin-left: auto; }
.agent-count { padding: 0 10px; color: #838980; font-family: ui-monospace, monospace; font-size: 8px; }
.agent-table-shell { border: 1px solid var(--console-line); background: white; }
.agent-table-shell :deep(.el-table__header th) { height: 46px; color: #757b72; font-size: 9px; }
.agent-table-shell :deep(.el-table__row td) { height: 64px; }
.agent-table-shell :deep(.el-link) { color: var(--console-ink); font-weight: 800; }
.agent-table-shell :deep(.el-link:hover) { color: var(--console-orange); }
.agent-table-shell :deep(.el-table__empty-block) { min-height: 260px; }
@media (max-width: 720px) {
  .agent-toolbar { align-items: stretch; flex-direction: column; }
  .agent-toolbar .console-search { width: 100%; margin-left: 0; }
  .agent-count { padding: 6px 2px; }
}
</style>
