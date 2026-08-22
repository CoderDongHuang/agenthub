<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ChatDotRound, Check, Connection, CopyDocument, Link, Message, Plus, Promotion, Refresh, RefreshRight } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../../api'

interface ChannelConfig { enabled: boolean; agentId?: number; webhookUrl?: string; membersOnly?: boolean; saveConversation?: boolean }
interface ChannelResource { id: number; resource_key: string; name: string; description: string; status: string; config: ChannelConfig }
interface AgentOption { id: number; name: string; status: string }

const { locale } = useI18n()
const tx = (zh: string, en: string) => locale.value === 'en-US' ? en : zh
const activeView = ref('config')
const channels = ref<ChannelResource[]>([])
const agents = ref<AgentOption[]>([])
const selected = ref<ChannelResource | null>(null)
const overview = ref<any>({ counts: {} })
const deliveries = ref<any[]>([])
const conversations = ref<any[]>([])
const routes = ref<any[]>([])
const templates = ref<any>({})
const deliveryStatus = ref('')
const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const routeForm = ref({ name: 'Default route', channel: '*', chatType: '*', matchType: 'default', matchValue: '', agentId: 1, priority: 100, enabled: true })
const iconMap: Record<string, any> = { web: ChatDotRound, wechat: Message, dingtalk: Promotion, feishu: Connection, api: Link }
const activeCount = computed(() => channels.value.filter(channel => channel.config.enabled).length)
const selectedAgent = computed(() => agents.value.find(agent => agent.id === selected.value?.config.agentId))
const endpoint = computed(() => selected.value?.config.agentId ? `http://localhost:8080/api/agents/${selected.value.config.agentId}/chat` : tx('请先绑定 Agent', 'Bind an Agent first'))
const views = computed(() => [
  { id: 'config', label: tx('渠道配置', 'Channel setup') },
  { id: 'delivery', label: tx('投递与死信', 'Delivery & DLQ') },
  { id: 'routing', label: tx('会话与路由', 'Sessions & routing') },
  { id: 'cards', label: tx('消息卡片', 'Message cards') },
])

async function load() {
  loading.value = true
  const requests = await Promise.allSettled([
    api.get('/workspace/channel'), api.get('/agents?size=100&sort=updatedAt,DESC'),
    api.get('/channel/operations/overview'), api.get(`/channel/operations/deliveries?status=${deliveryStatus.value}`),
    api.get('/channel/operations/conversations'), api.get('/channel/operations/routes'),
    api.get('/channel/operations/card-templates', { params: { title: 'AgentMesh', text: tx('任务处理完成', 'Task completed') } }),
  ])
  const data = requests.map(item => item.status === 'fulfilled' ? (item.value as any).data : null)
  channels.value = data[0] || []
  agents.value = data[1]?.content || []
  overview.value = data[2] || { counts: {} }
  deliveries.value = data[3] || []
  conversations.value = data[4] || []
  routes.value = data[5] || []
  templates.value = data[6] || {}
  selected.value = channels.value.find(channel => channel.id === selected.value?.id) || channels.value[0] || null
  loading.value = false
}

async function save(channel = selected.value, quiet = false) {
  if (!channel || saving.value) return
  saving.value = true
  try {
    channel.status = channel.config.enabled ? 'active' : 'inactive'
    const response = await api.put(`/workspace/channel/${channel.id}`, { name: channel.name, description: channel.description, status: channel.status, config: channel.config }) as any
    if (response.code !== 200) throw new Error(response.message)
    Object.assign(channel, response.data)
    if (!quiet) ElMessage.success(tx('渠道配置已保存', 'Channel configuration saved'))
  } catch (error: any) { ElMessage.error(error?.message || tx('渠道保存失败', 'Unable to save channel')) }
  finally { saving.value = false }
}

async function toggle(channel: ChannelResource) { channel.config.enabled = !channel.config.enabled; await save(channel, true) }
async function copyEndpoint() { await navigator.clipboard.writeText(endpoint.value); ElMessage.success(tx('接口地址已复制', 'Endpoint copied')) }
async function testChannel() {
  if (!selected.value) return
  testing.value = true
  try {
    await save(selected.value, true)
    const response = await api.post(`/workspace/channel/${selected.value.id}/test`, { message: 'AgentMesh channel connectivity test' }) as any
    if (response.code !== 200) throw new Error(response.message)
    ElMessage.success(tx('测试消息已发送', 'Test message sent'))
  } catch (error: any) { ElMessage.error(error?.message || tx('渠道测试失败', 'Channel test failed')) }
  finally { testing.value = false }
}

async function addCustomChannel() {
  try {
    const result = await ElMessageBox.prompt(tx('输入渠道名称', 'Enter channel name'), tx('添加自定义渠道', 'Add custom channel'))
    await api.post('/workspace/channel', { name: result.value, description: 'Custom webhook adapter', status: 'inactive', config: { enabled: false, webhookUrl: '', membersOnly: true, saveConversation: true } })
    await load()
  } catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error(tx('创建失败', 'Creation failed')) }
}

async function chooseAgent() {
  if (!selected.value) return
  try {
    const result = await ElMessageBox.prompt(agents.value.map(agent => `${agent.id}: ${agent.name}`).join(', '), tx('绑定 Agent', 'Bind Agent'), { inputValue: String(selected.value.config.agentId || ''), inputPattern: /^\d+$/ })
    selected.value.config.agentId = Number(result.value)
    await save()
  } catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error(tx('绑定失败', 'Binding failed')) }
}

async function replay(item: any) {
  try { await api.post(`/channel/operations/deliveries/${item.id}/replay`); ElMessage.success(tx('死信已重放', 'Dead letter replayed')); await load() }
  catch (error: any) { ElMessage.error(error?.response?.data?.message || tx('重放失败', 'Replay failed')) }
}

async function saveRoute() {
  try { await api.post('/channel/operations/routes', routeForm.value); ElMessage.success(tx('路由规则已保存', 'Routing rule saved')); await load() }
  catch (error: any) { ElMessage.error(error?.response?.data?.message || tx('路由保存失败', 'Unable to save route')) }
}

function time(value: string) { return value ? new Date(value).toLocaleString() : '-' }
onMounted(load)
</script>

<template>
  <div class="console-page channel-page" v-loading="loading">
    <div class="console-page-head"><div class="console-page-head__copy"><span>CHANNEL OPERATIONS</span><h1>{{ tx('渠道运营中心', 'Channel Operations') }}</h1><p>{{ tx('统一管理接入、投递回执、失败重试、死信重放、会话映射和多 Agent 路由。', 'Manage integrations, receipts, retries, dead-letter replay, sessions, and multi-Agent routing.') }}</p></div><div class="console-page-actions"><button class="console-secondary" @click="load"><el-icon><Refresh /></el-icon>{{ tx('刷新', 'Refresh') }}</button><button class="console-primary" @click="addCustomChannel"><el-icon><Plus /></el-icon>{{ tx('添加渠道', 'Add channel') }}</button></div></div>
    <section class="channel-summary"><div><span>{{ tx('已启用渠道', 'Enabled channels') }}</span><strong>{{ activeCount }}</strong></div><div><span>{{ tx('已送达', 'Delivered') }}</span><strong>{{ overview.counts?.delivered || 0 }}</strong></div><div><span>{{ tx('重试中', 'Retrying') }}</span><strong>{{ overview.counts?.retrying || 0 }}</strong></div><div><span>{{ tx('死信', 'Dead letters') }}</span><strong>{{ overview.counts?.dead_letter || 0 }}</strong></div><div><span>{{ tx('活跃路由', 'Active routes') }}</span><strong>{{ overview.activeRoutes || 0 }}</strong></div></section>
    <nav class="operation-tabs"><button v-for="view in views" :key="view.id" :class="{ active: activeView === view.id }" @click="activeView = view.id">{{ view.label }}</button></nav>

    <section v-if="activeView === 'config'" class="config-workspace">
      <div class="channel-directory"><header><h2>{{ tx('渠道目录', 'Channel directory') }}</h2><span>{{ channels.length }}</span></header><button v-for="channel in channels" :key="channel.id" :class="{ active: selected?.id === channel.id }" @click="selected = channel"><el-icon><component :is="iconMap[channel.resource_key] || Link" /></el-icon><span><strong>{{ channel.name }}</strong><small>{{ channel.description }}</small></span><i :class="{ on: channel.config.enabled }" /></button></div>
      <form v-if="selected" class="channel-form" @submit.prevent="save()"><header><div><h2>{{ selected.name }}</h2><p>{{ selected.description }}</p></div><button type="button" class="console-secondary" @click="toggle(selected)">{{ selected.config.enabled ? tx('停用', 'Disable') : tx('启用', 'Enable') }}</button></header><label>{{ tx('绑定 Agent', 'Bound Agent') }}<div class="binding"><strong>{{ selectedAgent?.name || tx('尚未绑定', 'Not bound') }}</strong><button type="button" @click="chooseAgent">{{ tx('更换', 'Change') }}</button></div></label><label v-if="!['web','api'].includes(selected.resource_key)">{{ tx('Webhook 地址', 'Webhook URL') }}<el-input v-model="selected.config.webhookUrl" placeholder="https://..." /></label><label>{{ tx('服务地址', 'Service endpoint') }}<div class="binding"><code>{{ endpoint }}</code><button type="button" :aria-label="tx('复制', 'Copy')" @click="copyEndpoint"><el-icon><CopyDocument /></el-icon></button></div></label><div class="option-row"><el-switch v-model="selected.config.membersOnly" :active-text="tx('仅组织成员', 'Members only')" /><el-switch v-model="selected.config.saveConversation" :active-text="tx('保存会话', 'Save sessions')" /></div><button class="console-primary" type="submit"><el-icon><Check /></el-icon>{{ tx('保存配置', 'Save') }}</button><button class="console-secondary" type="button" :disabled="testing || !selected.config.enabled" @click="testChannel"><el-icon><RefreshRight /></el-icon>{{ tx('发送测试消息', 'Send test message') }}</button></form>
    </section>

    <section v-else-if="activeView === 'delivery'" class="operation-workspace"><header><div><h2>{{ tx('投递箱与死信队列', 'Delivery inbox and dead letters') }}</h2><p>{{ tx('外部消息 ID 由数据库唯一约束保证幂等，失败投递按指数退避自动重试。', 'Database uniqueness enforces idempotency; failed deliveries retry with exponential backoff.') }}</p></div><el-select v-model="deliveryStatus" clearable :placeholder="tx('全部状态', 'All statuses')" @change="load"><el-option value="delivered" :label="tx('已送达', 'Delivered')" /><el-option value="retrying" :label="tx('重试中', 'Retrying')" /><el-option value="dead_letter" :label="tx('死信', 'Dead letter')" /></el-select></header><el-table :data="deliveries"><el-table-column prop="channel" :label="tx('渠道', 'Channel')" width="100" /><el-table-column prop="direction" :label="tx('方向', 'Direction')" width="100" /><el-table-column prop="externalMessageId" :label="tx('外部消息 ID', 'External message ID')" show-overflow-tooltip /><el-table-column prop="status" :label="tx('状态', 'Status')" width="110" /><el-table-column prop="attemptCount" :label="tx('尝试', 'Attempts')" width="80" /><el-table-column :label="tx('时间', 'Time')" width="170"><template #default="scope">{{ time(scope.row.createdAt) }}</template></el-table-column><el-table-column :label="tx('操作', 'Action')" width="100"><template #default="scope"><el-button v-if="scope.row.status === 'dead_letter'" link @click="replay(scope.row)">{{ tx('重放', 'Replay') }}</el-button></template></el-table-column></el-table></section>

    <section v-else-if="activeView === 'routing'" class="operation-workspace routing-workspace"><header><div><h2>{{ tx('会话上下文与多 Agent 路由', 'Session context and multi-Agent routing') }}</h2><p>{{ tx('按渠道、会话类型、群聊提及或关键词选择 Agent，并持续复用同一 session。', 'Route by channel, chat type, mentions, or keywords while reusing the same session.') }}</p></div></header><form class="route-form" @submit.prevent="saveRoute"><el-input v-model="routeForm.name" :placeholder="tx('规则名称', 'Rule name')" /><el-select v-model="routeForm.channel"><el-option value="*" :label="tx('全部渠道', 'All channels')" /><el-option v-for="item in ['feishu','dingtalk','wechat']" :key="item" :value="item" :label="item" /></el-select><el-select v-model="routeForm.matchType"><el-option value="default" :label="tx('默认', 'Default')" /><el-option value="mention" :label="tx('群聊提及', 'Mention')" /><el-option value="keyword" :label="tx('关键词', 'Keyword')" /></el-select><el-input v-model="routeForm.matchValue" :disabled="routeForm.matchType === 'default'" :placeholder="tx('匹配内容', 'Match value')" /><el-select v-model="routeForm.agentId"><el-option v-for="agent in agents" :key="agent.id" :value="agent.id" :label="agent.name" /></el-select><el-input-number v-model="routeForm.priority" :min="1" :max="999" /><button class="console-primary" type="submit">{{ tx('保存规则', 'Save rule') }}</button></form><el-table :data="routes"><el-table-column prop="name" :label="tx('规则', 'Rule')" /><el-table-column prop="channel" :label="tx('渠道', 'Channel')" /><el-table-column prop="matchType" :label="tx('匹配', 'Match')" /><el-table-column prop="matchValue" :label="tx('值', 'Value')" /><el-table-column prop="agentId" label="Agent ID" /><el-table-column prop="priority" :label="tx('优先级', 'Priority')" /></el-table><h3 class="table-heading">{{ tx('最近会话映射', 'Recent session mappings') }}</h3><el-table :data="conversations"><el-table-column prop="channel" :label="tx('渠道', 'Channel')" /><el-table-column prop="conversationKey" :label="tx('会话键', 'Conversation key')" show-overflow-tooltip /><el-table-column prop="sessionId" label="Session ID" show-overflow-tooltip /><el-table-column prop="agentId" label="Agent ID" /><el-table-column :label="tx('最后消息', 'Last message')"><template #default="scope">{{ time(scope.row.lastMessageAt) }}</template></el-table-column></el-table></section>

    <section v-else class="operation-workspace"><header><div><h2>{{ tx('三平台消息卡片', 'Three-platform message cards') }}</h2><p>{{ tx('服务端模板可直接用于飞书交互卡片、钉钉 ActionCard 和企业微信 TextCard。', 'Server templates support Feishu interactive cards, DingTalk ActionCard, and WeCom TextCard.') }}</p></div></header><div class="template-grid"><article v-for="(template, channel) in templates" :key="channel"><strong>{{ channel }}</strong><pre>{{ JSON.stringify(template, null, 2) }}</pre></article></div></section>
  </div>
</template>

<style scoped>
.channel-summary{display:grid;grid-template-columns:repeat(5,1fr);border:1px solid var(--console-line);border-radius:8px;background:var(--console-panel);overflow:hidden}.channel-summary div{min-height:82px;padding:14px;display:flex;flex-direction:column;justify-content:space-between;border-right:1px solid var(--console-line)}.channel-summary div:last-child{border:0}.channel-summary span{color:var(--console-muted);font-size: 12px}.channel-summary strong{font:22px ui-monospace,monospace;color:var(--console-ink)}.operation-tabs{margin:14px 0;padding:4px;display:grid;grid-template-columns:repeat(4,1fr);gap:3px;border:1px solid var(--console-line);border-radius:8px;background:var(--console-panel-soft)}.operation-tabs button{min-height:40px;border:0;border-radius:5px;background:transparent;color:var(--console-muted);font:inherit;font-size: 12px;cursor:pointer}.operation-tabs button.active{background:var(--console-panel);color:var(--console-ink);box-shadow:inset 0 0 0 1px var(--console-accent)}
.config-workspace{display:grid;grid-template-columns:340px 1fr;border:1px solid var(--console-line);border-radius:8px;background:var(--console-panel);overflow:hidden}.channel-directory{border-right:1px solid var(--console-line)}.channel-directory header,.channel-form header,.operation-workspace>header{min-height:68px;padding:14px 16px;display:flex;align-items:center;justify-content:space-between;border-bottom:1px solid var(--console-line)}h2{margin:0;font-size:14px}.channel-directory>button{width:100%;min-height:64px;padding:10px 14px;display:grid;grid-template-columns:32px 1fr 8px;gap:10px;align-items:center;border:0;border-bottom:1px solid var(--console-line);background:transparent;color:var(--console-ink);text-align:left;cursor:pointer}.channel-directory>button.active{background:var(--console-primary-soft)}.channel-directory>button>.el-icon{font-size:17px}.channel-directory button span{display:flex;flex-direction:column;gap:4px}.channel-directory small,.channel-form p,.operation-workspace header p{color:var(--console-muted);font-size: 12px}.channel-directory i{width:7px;height:7px;border-radius:50%;background:var(--console-line-strong)}.channel-directory i.on{background:var(--console-green)}
.channel-form{padding-bottom:18px}.channel-form>label{margin:16px 18px 0;display:flex;flex-direction:column;gap:7px;color:var(--console-muted);font-size: 12px}.binding{min-height:40px;padding:0 10px;display:flex;align-items:center;justify-content:space-between;border:1px solid var(--console-line);border-radius:6px;background:var(--console-panel-soft)}.binding button{border:0;background:transparent;color:var(--console-accent);cursor:pointer}.binding code{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.option-row{padding:16px 18px;display:flex;gap:24px}.channel-form>.console-primary,.channel-form>.console-secondary{margin:0 18px 8px;width:calc(100% - 36px)}.operation-workspace{border:1px solid var(--console-line);border-radius:8px;background:var(--console-panel);overflow:hidden}.operation-workspace>header p{margin:5px 0 0}.route-form{padding:14px;display:grid;grid-template-columns:1.3fr repeat(5,1fr) auto;gap:8px;border-bottom:1px solid var(--console-line)}.table-heading{margin:0;padding:14px 16px;border-top:1px solid var(--console-line);font-size: 12px}.template-grid{display:grid;grid-template-columns:repeat(3,1fr)}.template-grid article{min-width:0;padding:16px;border-right:1px solid var(--console-line)}.template-grid article:last-child{border:0}.template-grid strong{font-size: 12px;text-transform:uppercase}.template-grid pre{max-height:380px;padding:12px;overflow:auto;background:#0c1012;color:#8fe1b4;font: 12px/1.55 ui-monospace,monospace;white-space:pre-wrap;word-break:break-word}
@media(max-width:1000px){.channel-summary{grid-template-columns:repeat(3,1fr)}.route-form{grid-template-columns:repeat(2,1fr)}.template-grid{grid-template-columns:1fr}}@media(max-width:720px){.channel-summary{grid-template-columns:repeat(2,1fr)}.operation-tabs,.config-workspace{grid-template-columns:1fr}.channel-directory{border-right:0;border-bottom:1px solid var(--console-line)}.route-form{grid-template-columns:1fr}}
</style>
