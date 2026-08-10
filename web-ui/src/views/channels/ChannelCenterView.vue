<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ChatDotRound, Check, Connection, CopyDocument, Link, Message, Plus, Promotion, RefreshRight } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../../api'

interface ChannelConfig { enabled: boolean; agentId?: number; webhookUrl?: string; secret?: string; token?: string; encodingAesKey?: string; timeoutSeconds?: number; retryCount?: number; membersOnly?: boolean; saveConversation?: boolean }
interface ChannelResource { id: number; resource_key: string; name: string; description: string; status: string; config: ChannelConfig }
interface AgentOption { id: number; name: string; status: string }
const channels = ref<ChannelResource[]>([])
const agents = ref<AgentOption[]>([])
const selected = ref<ChannelResource | null>(null)
const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const iconMap: Record<string, any> = { web: ChatDotRound, wechat: Message, dingtalk: Promotion, feishu: Connection, api: Link }
const toneMap: Record<string, string> = { web: 'sage', wechat: 'blue', dingtalk: 'amber', feishu: 'coral', api: 'sage' }
const activeCount = computed(() => channels.value.filter(channel => channel.config.enabled).length)
const credentialReadyCount = computed(() => channels.value.filter(channel => ['web', 'api'].includes(channel.resource_key) || Boolean(channel.config.webhookUrl)).length)
const selectedAgent = computed(() => agents.value.find(agent => agent.id === selected.value?.config.agentId))
const endpoint = computed(() => selected.value?.config.agentId ? `http://localhost:8080/api/agents/${selected.value.config.agentId}/chat` : '请先绑定 Agent')
function channelIcon(channel: ChannelResource) { return iconMap[channel.resource_key] || Link }
function channelTone(channel: ChannelResource) { return toneMap[channel.resource_key] || 'blue' }
async function load() {
  loading.value = true
  try {
    const [channelResponse, agentResponse] = await Promise.all([
      api.get('/workspace/channel') as any,
      api.get('/agents?size=100&sort=updatedAt,DESC') as any,
    ])
    channels.value = channelResponse.data || []
    agents.value = agentResponse.data?.content || []
    selected.value = channels.value.find(channel => channel.id === selected.value?.id) || channels.value[0] || null
  } finally { loading.value = false }
}
async function save(channel = selected.value, quiet = false) {
  if (!channel || saving.value) return
  saving.value = true
  try {
    channel.status = channel.config.enabled ? 'active' : 'inactive'
    const response = await api.put(`/workspace/channel/${channel.id}`, { name: channel.name, description: channel.description, status: channel.status, config: channel.config }) as any
    if (response.code !== 200) throw new Error(response.message)
    Object.assign(channel, response.data)
    if (!quiet) ElMessage.success(`${channel.name} 配置已保存`)
  } catch (error: any) { ElMessage.error(error?.message || '渠道保存失败') }
  finally { saving.value = false }
}
async function toggle(channel: ChannelResource) {
  channel.config.enabled = !channel.config.enabled
  await save(channel, true)
  ElMessage.success(channel.config.enabled ? `${channel.name} 已启用` : `${channel.name} 已停用`)
}
async function copyEndpoint() { await navigator.clipboard.writeText(endpoint.value); ElMessage.success('接口地址已复制') }
async function testChannel() {
  if (!selected.value) return
  testing.value = true
  try {
    await save(selected.value, true)
    const response = await api.post(`/workspace/channel/${selected.value.id}/test`, { message: 'AgentHub 渠道连通性测试' }) as any
    if (response.code !== 200) throw new Error(response.message)
    ElMessage.success(`${selected.value.name} 测试成功：${response.data?.status}`)
  } catch (error: any) { ElMessage.error(error?.message || '渠道测试失败') }
  finally { testing.value = false }
}
async function addCustomChannel() {
  try {
    const result = await ElMessageBox.prompt('输入渠道名称', '添加自定义渠道', { inputPlaceholder: '例如：内部工单系统' })
    const response = await api.post('/workspace/channel', { name: result.value, description: '自定义 Webhook 适配器', status: 'inactive', config: { enabled: false, webhookUrl: '', timeoutSeconds: 30, retryCount: 2, membersOnly: true, saveConversation: true } }) as any
    if (response.code !== 200) throw new Error(response.message)
    channels.value.unshift(response.data)
    selected.value = response.data
  } catch (error: any) { if (error !== 'cancel' && error !== 'close') ElMessage.error(error?.message || '创建失败') }
}
async function chooseAgent() {
  if (!selected.value) return
  try {
    const options = agents.value.map(agent => `${agent.id}: ${agent.name}`).join('，')
    const result = await ElMessageBox.prompt(`输入 Agent ID。可选：${options || '请先创建 Agent'}`, '绑定 Agent', { inputValue: String(selected.value.config.agentId || ''), inputPattern: /^\d+$/, inputErrorMessage: '请输入有效的 Agent ID' })
    const agentId = Number(result.value)
    if (!agents.value.some(agent => agent.id === agentId)) throw new Error('该 Agent 不存在')
    selected.value.config.agentId = agentId
    await save()
  } catch (error: any) { if (error !== 'cancel' && error !== 'close') ElMessage.error(error?.message || '绑定失败') }
}
onMounted(load)
</script>

<template>
  <div class="console-page channel-page" v-loading="loading">
    <div class="console-page-head"><div class="console-page-head__copy"><span>分发与触达</span><h1>渠道接入</h1><p>让同一个 Agent 安全地服务于网页、协作平台和业务系统，并统一回收对话与审计记录。</p></div><div class="console-page-actions"><button class="console-primary" @click="addCustomChannel"><el-icon><Plus /></el-icon> 添加自定义渠道</button></div></div>
    <section class="channel-overview"><div><span class="channel-main-icon"><el-icon><Connection /></el-icon></span><p><strong>{{ activeCount }} 个渠道已启用</strong><small>渠道状态和凭据配置保存在工作区。</small></p></div><div><span>渠道总数</span><strong>{{ channels.length }}</strong><small>包含自定义适配器</small></div><div><span>凭据就绪</span><strong>{{ credentialReadyCount }}</strong><small>本地渠道无需 Webhook</small></div><div><span>可绑定 Agent</span><strong>{{ agents.length }}</strong><small>来自 Agent 管理</small></div></section>
    <div class="channel-layout">
      <section class="channel-list"><div class="panel-title"><div><h2>渠道目录</h2><p>选择渠道查看配置与运行情况</p></div><span>{{ channels.length }} 个适配器</span></div><article v-for="channel in channels" :key="channel.id" :class="{ active: selected?.id === channel.id }" @click="selected = channel"><span :class="['channel-icon', channelTone(channel)]"><el-icon><component :is="channelIcon(channel)" /></el-icon></span><div><strong>{{ channel.name }}</strong><p>{{ channel.description }}</p></div><span class="channel-state"><i :class="{ on: channel.config.enabled }" />{{ channel.config.enabled ? '已启用' : '未启用' }}</span></article></section>
      <section v-if="selected" class="channel-config"><div class="config-head"><div><span :class="['channel-icon', channelTone(selected)]"><el-icon><component :is="channelIcon(selected)" /></el-icon></span><p><strong>{{ selected.name }}</strong><small>{{ selected.description }}</small></p></div><button :class="{ danger: selected.config.enabled }" @click="toggle(selected)">{{ selected.config.enabled ? '停用渠道' : '启用渠道' }}</button></div><div class="config-section"><span>绑定 Agent</span><div class="agent-binding"><span>{{ selectedAgent?.name || '尚未绑定' }}</span><b>{{ selectedAgent?.status || '-' }}</b><button @click="chooseAgent">更换</button></div></div><div v-if="!['web', 'api'].includes(selected.resource_key)" class="config-section"><span>Webhook 地址</span><input v-model="selected.config.webhookUrl" class="config-input" placeholder="https://..." /></div><div class="config-section"><span>服务地址</span><div class="endpoint-field"><code>{{ endpoint }}</code><button aria-label="复制地址" @click="copyEndpoint"><el-icon><CopyDocument /></el-icon></button></div></div><div class="config-grid"><label><span>消息超时</span><select v-model.number="selected.config.timeoutSeconds"><option :value="30">30 秒</option><option :value="60">60 秒</option></select></label><label><span>失败重试</span><select v-model.number="selected.config.retryCount"><option :value="2">2 次</option><option :value="3">3 次</option></select></label></div><div class="config-section"><span>接入权限</span><div class="setting-row"><p><strong>仅组织成员可用</strong><small>进入会话前校验成员身份</small></p><el-switch v-model="selected.config.membersOnly" /></div><div class="setting-row"><p><strong>保存对话记录</strong><small>用于审计与质量分析</small></p><el-switch v-model="selected.config.saveConversation" /></div></div><div class="config-actions"><button class="console-secondary" :disabled="saving" @click="save()"><el-icon><Check /></el-icon>保存配置</button><button class="test-channel" :disabled="testing || !selected.config.enabled" @click="testChannel"><el-icon><RefreshRight /></el-icon>{{ testing ? '正在测试' : '发送测试消息' }}</button></div></section>
      <aside class="delivery-panel"><span>配置检查</span><h2>{{ selected?.config.enabled ? '渠道已启用' : '等待启用' }}</h2><article><el-icon><Check /></el-icon><p><strong>Agent 绑定</strong><span>{{ selectedAgent ? `已绑定 ${selectedAgent.name}` : '尚未绑定 Agent' }}</span></p></article><article><el-icon><Check /></el-icon><p><strong>投递凭据</strong><span>{{ selected && (['web', 'api'].includes(selected.resource_key) || selected.config.webhookUrl) ? '配置就绪' : '需要填写 Webhook 地址' }}</span></p></article><article><el-icon><Check /></el-icon><p><strong>持久化状态</strong><span>点击保存配置后写入 PostgreSQL</span></p></article></aside>
    </div>
  </div>
</template>

<style scoped>
.channel-overview { display: grid; grid-template-columns: 1.5fr repeat(3, .55fr); border: 1px solid var(--console-line); border-radius: 8px; background: white; overflow: hidden; }.channel-overview > div { min-height: 116px; padding: 18px; display: flex; flex-direction: column; justify-content: center; border-right: 1px solid var(--console-line); }.channel-overview > div:first-child { flex-direction: row; justify-content: flex-start; align-items: center; gap: 14px; background: var(--console-blue-soft); }.channel-main-icon { width: 46px; height: 46px; flex: 0 0 46px; display: grid; place-items: center; border-radius: 8px; background: #5d7c91; color: white; font-size: 21px; }.channel-overview p { display: flex; flex-direction: column; gap: 6px; }.channel-overview p strong { font-size: 13px; }.channel-overview p small, .channel-overview > div > span { color: var(--console-muted); font-size: 9px; }.channel-overview > div > strong { font-size: 21px; }
.channel-layout { margin-top: 16px; display: grid; grid-template-columns: .8fr 1fr .6fr; gap: 14px; }.channel-list, .channel-config, .delivery-panel { min-width: 0; padding: 20px; border: 1px solid var(--console-line); border-radius: 8px; background: white; }.panel-title { display: flex; justify-content: space-between; align-items: start; }.panel-title h2 { font-size: 15px; }.panel-title p { margin-top: 5px; color: var(--console-muted); font-size: 9px; }.panel-title > span { color: #8b958f; font-size: 8px; }
.channel-list article { min-height: 76px; padding: 10px; display: grid; grid-template-columns: 40px 1fr auto; gap: 10px; align-items: center; border-bottom: 1px solid var(--console-line); border-radius: 7px; cursor: pointer; }.channel-list article:hover { background: #f7f9f6; }.channel-list article.active { background: var(--console-primary-soft); }.channel-icon { width: 38px; height: 38px; display: grid; place-items: center; border-radius: 8px; background: var(--console-primary-soft); color: var(--console-primary-dark); font-size: 17px; }.channel-icon.blue { background: var(--console-blue-soft); color: #5b798e; }.channel-icon.amber { background: #f5ecdd; color: #9b7334; }.channel-icon.coral { background: #f5e8e4; color: #a85e4c; }.channel-list article > div { display: flex; flex-direction: column; gap: 5px; }.channel-list article strong { font-size: 10px; }.channel-list article p { color: var(--console-muted); font-size: 8px; }.channel-state { display: flex; align-items: center; gap: 5px; color: #87918b; font-size: 8px; }.channel-state i { width: 6px; height: 6px; border-radius: 50%; background: #b8c1bb; }.channel-state i.on { background: var(--console-green); }.channel-list article > b { grid-column: 3; color: #96a099; font-size: 8px; font-weight: 500; }
.config-head { display: flex; justify-content: space-between; gap: 14px; align-items: center; }.config-head > div { display: flex; gap: 11px; align-items: center; }.config-head p { display: flex; flex-direction: column; gap: 5px; }.config-head strong { font-size: 13px; }.config-head small { color: var(--console-muted); font-size: 8px; }.config-head > button { min-height: 34px; padding: 0 11px; border: 1px solid var(--console-primary); border-radius: 6px; background: white; color: var(--console-primary-dark); font: inherit; font-size: 9px; cursor: pointer; }.config-head > button.danger { border-color: #e0c3bc; color: var(--console-red); }
.config-section { margin-top: 23px; }.config-section > span, .config-grid label > span { display: block; margin-bottom: 8px; color: var(--console-muted); font-size: 9px; font-weight: 700; }.agent-binding, .endpoint-field { min-height: 44px; padding: 0 11px; display: flex; align-items: center; gap: 9px; border: 1px solid var(--console-line); border-radius: 7px; background: #fafbf9; }.agent-binding > span { font-size: 10px; font-weight: 700; }.agent-binding b { margin-left: auto; color: var(--console-muted); font-size: 8px; font-weight: 500; }.agent-binding button { border: 0; background: transparent; color: var(--console-primary); font: inherit; font-size: 9px; cursor: pointer; }.endpoint-field code { min-width: 0; flex: 1; overflow: hidden; color: #5f6d66; font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }.endpoint-field button { width: 30px; height: 30px; display: grid; place-items: center; border: 0; border-radius: 5px; background: white; color: var(--console-ink); cursor: pointer; }
.config-input { width: 100%; min-height: 42px; padding: 0 11px; border: 1px solid var(--console-line); border-radius: 7px; outline: 0; background: #fafbf9; color: var(--console-ink); font: inherit; font-size: 9px; }
.config-input:focus { border-color: var(--console-primary); }
.config-actions { margin-top: 22px; display: grid; grid-template-columns: .8fr 1.2fr; gap: 9px; }
.config-actions .test-channel { margin-top: 0; }
.config-grid { margin-top: 22px; display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }.config-grid select { width: 100%; min-height: 40px; padding: 0 9px; border: 1px solid var(--console-line); border-radius: 7px; background: white; color: var(--console-ink); font: inherit; font-size: 9px; }.setting-row { min-height: 58px; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid var(--console-line); }.setting-row p { display: flex; flex-direction: column; gap: 5px; }.setting-row strong { font-size: 10px; }.setting-row small { color: var(--console-muted); font-size: 8px; }.test-channel { width: 100%; min-height: 42px; margin-top: 22px; display: flex; justify-content: center; align-items: center; gap: 8px; border: 0; border-radius: 7px; background: var(--console-primary-dark); color: white; font: inherit; font-size: 10px; font-weight: 700; cursor: pointer; }.test-channel:disabled { background: #b4bdb7; cursor: not-allowed; }
.delivery-panel { background: #f8faf7; }.delivery-panel > span { color: var(--console-primary); font-size: 9px; font-weight: 700; }.delivery-panel h2 { margin-top: 8px; font-size: 18px; }.delivery-chart { height: 150px; margin-top: 28px; display: grid; grid-template-columns: repeat(12, 1fr); gap: 5px; align-items: end; }.delivery-chart div { height: 100%; display: flex; align-items: flex-end; border-radius: 3px 3px 0 0; background: #e7ede8; overflow: hidden; }.delivery-chart i { width: 100%; display: block; border-radius: 3px 3px 0 0; background: var(--console-blue); }.delivery-legend { margin-top: 7px; display: flex; justify-content: space-between; color: #939d97; font-size: 7px; }.delivery-panel article { margin-top: 18px; padding-top: 16px; display: flex; gap: 9px; border-top: 1px solid var(--console-line); color: var(--console-green); }.delivery-panel article p { display: flex; flex-direction: column; gap: 5px; }.delivery-panel article strong { color: var(--console-ink); font-size: 9px; }.delivery-panel article span { color: var(--console-muted); font-size: 8px; }
@media (max-width: 1150px) { .channel-layout { grid-template-columns: .8fr 1.2fr; }.delivery-panel { grid-column: 1 / -1; } }
@media (max-width: 760px) { .channel-overview { grid-template-columns: 1fr 1fr 1fr; }.channel-overview > div:first-child { grid-column: 1 / -1; }.channel-layout { grid-template-columns: 1fr; }.delivery-panel { grid-column: 1; } }
</style>
