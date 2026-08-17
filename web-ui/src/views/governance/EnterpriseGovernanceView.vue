<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  Connection, DataBoard, DocumentChecked, Download, Key, Lock, Plus, Refresh,
  RefreshRight, Search, Upload, UserFilled, Warning,
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import api from '../../api'

const { locale } = useI18n()
const router = useRouter()
const en = computed(() => locale.value === 'en-US')
const tx = (zh: string, english: string) => en.value ? english : zh
const loading = ref(false)
const activeTab = ref('identity')
const overview = ref<any>({ counts: {} })
const providers = ref<any[]>([])
const scimTokens = ref<any[]>([])
const accessPolicies = ref<any[]>([])
const secrets = ref<any[]>([])
const retentionPolicies = ref<any[]>([])
const approvalPolicies = ref<any[]>([])
const onCall = ref<any[]>([])
const jobs = ref<any[]>([])
const compliance = ref<any>(null)
const result = ref<any>(null)
const oneTimeSecret = ref('')

const providerForm = ref({ providerType: 'oidc', name: '', issuer: '', clientId: '', secretRef: '' })
const scimForm = ref({ name: '', expiryDays: 90 })
const scimUserForm = ref({ userName: '', displayName: '', email: '', department: '', active: true })
const accessForm = ref({ name: '', effect: 'allow', resourceType: 'dataset', actionPattern: 'read*', department: '' })
const accessTest = ref({ resourceType: 'dataset', action: 'read.list', department: '' })
const secretForm = ref({ secretKey: '', value: '', description: '' })
const retentionForm = ref({ dataType: 'audit_log', retentionDays: 365, action: 'anonymize', legalHold: false })
const guardrailForm = ref({ text: '', fileName: '', fileBase64: '', toolUrl: '', toolCommand: '' })
const approvalForm = ref({ name: '', decision: 'dual', priority: 100, tool: '', amountMin: 5000, dataClassification: 'confidential', callerType: 'external', slaMinutes: 30 })
const approvalTest = ref({ tool: '', amount: 8000, dataClassification: 'confidential', callerType: 'external' })
const onCallForm = ref({ name: '', primaryUserId: '', backupUserId: '', timezone: 'Asia/Shanghai', activeFrom: '00:00', activeTo: '23:59' })
const migrationTarget = ref('tenant-target-reference')

const tabs = computed(() => [
  { name: 'identity', label: tx('身份与访问', 'Identity & Access'), icon: UserFilled },
  { name: 'kms', label: tx('密钥托管', 'Key Management'), icon: Key },
  { name: 'data', label: tx('数据与合规', 'Data & Compliance'), icon: DocumentChecked },
  { name: 'guardrails', label: tx('多层护栏', 'Layered Guardrails'), icon: Lock },
  { name: 'policy', label: tx('审批策略', 'Approval Policy'), icon: DataBoard },
  { name: 'operations', label: tx('值班与 SLA', 'On-call & SLA'), icon: Warning },
  { name: 'recovery', label: tx('恢复与迁移', 'Recovery & Migration'), icon: RefreshRight },
])

async function loadAll() {
  loading.value = true
  const requests = await Promise.allSettled([
    api.get('/governance/overview'), api.get('/governance/identity/providers'), api.get('/governance/scim/tokens'),
    api.get('/governance/access-policies'), api.get('/governance/secrets'), api.get('/governance/retention-policies'),
    api.get('/governance/approval-policies'), api.get('/governance/on-call'), api.get('/governance/jobs'),
  ])
  const data = requests.map(item => item.status === 'fulfilled' ? (item.value as any).data : null)
  overview.value = data[0] || { counts: {} }
  providers.value = data[1] || []
  scimTokens.value = data[2] || []
  accessPolicies.value = data[3] || []
  secrets.value = data[4] || []
  retentionPolicies.value = data[5] || []
  approvalPolicies.value = data[6] || []
  onCall.value = data[7] || []
  jobs.value = data[8] || []
  loading.value = false
}

async function run(label: string, action: () => Promise<any>, reload = true) {
  try {
    const response = await action()
    result.value = response?.data ?? response
    ElMessage.success(label)
    if (reload) await loadAll()
    return response?.data ?? response
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message || error?.message || tx('操作失败', 'Operation failed'))
    return null
  }
}

async function saveProvider() {
  const form = providerForm.value
  await run(tx('身份提供商配置已验证', 'Identity provider configuration validated'), () => api.post('/governance/identity/providers', {
    providerType: form.providerType, name: form.name, enabled: true,
    config: form.providerType === 'oidc'
      ? { issuer: form.issuer, clientId: form.clientId, secretRef: form.secretRef }
      : { entityId: form.clientId, ssoUrl: form.issuer, certificate: form.secretRef },
  }))
}

async function issueToken() {
  const data = await run(tx('SCIM Token 已签发', 'SCIM token issued'), () => api.post('/governance/scim/tokens', scimForm.value))
  if (data?.token) {
    oneTimeSecret.value = data.token
    await ElMessageBox.alert(data.token, tx('SCIM Token 仅显示一次', 'SCIM token shown once'), { confirmButtonText: tx('我已记录', 'Recorded') })
    oneTimeSecret.value = ''
  }
}

async function syncScimUser() {
  const form = scimUserForm.value
  await run(tx('SCIM 用户与组织已同步', 'SCIM user and organization synced'), () => api.post('/governance/scim/v2/Users', {
    userName: form.userName, displayName: form.displayName, active: form.active, department: form.department,
    emails: form.email ? [{ value: form.email, primary: true }] : [],
  }))
}

async function saveAccessPolicy() {
  const form = accessForm.value
  await run(tx('ABAC 策略已保存', 'ABAC policy saved'), () => api.post('/governance/access-policies', {
    name: form.name, effect: form.effect, priority: 100, resourceType: form.resourceType,
    actionPattern: form.actionPattern, conditions: form.department ? { department: form.department } : {}, enabled: true,
  }))
}

async function testAccess() {
  await run(tx('访问决策已完成', 'Access decision completed'), () => api.post('/governance/access-policies/evaluate', {
    resourceType: accessTest.value.resourceType, action: accessTest.value.action,
    attributes: { department: accessTest.value.department },
  }), false)
}

async function saveSecret() {
  await run(tx('字段已使用租户密钥加密', 'Field encrypted with tenant key'), () => api.post('/governance/secrets', secretForm.value))
  secretForm.value.value = ''
}

async function revealSecret(item: any) {
  const data = await run(tx('密钥读取已审计', 'Secret access audited'), () => api.post(`/governance/secrets/${item.id}/reveal`), false)
  if (data?.value) await ElMessageBox.alert(data.value, tx('敏感值临时查看', 'Temporary secret view'))
}

async function rotateKey() {
  await ElMessageBox.confirm(tx('将解密并重新加密当前租户的全部托管字段，继续？', 'All managed fields will be re-encrypted. Continue?'), tx('轮换租户密钥', 'Rotate tenant key'), { type: 'warning' })
  await run(tx('密钥轮换完成', 'Key rotation completed'), () => api.post('/governance/secrets/rotate-key'))
}

async function saveRetention() {
  await run(tx('保留策略已保存', 'Retention policy saved'), () => api.post('/governance/retention-policies', retentionForm.value))
}

async function runRetention(item: any, execute: boolean) {
  if (execute) await ElMessageBox.confirm(tx('这会执行真实删除或匿名化，确认继续？', 'This executes real deletion or anonymization. Continue?'), tx('执行保留策略', 'Execute retention policy'), { type: 'warning' })
  await run(execute ? tx('保留策略已执行', 'Retention policy executed') : tx('保留策略预演完成', 'Retention preview completed'),
    () => api.post(`/governance/retention-policies/${item.id}/run`, null, { params: { execute } }))
}

async function reportCompliance() {
  const data = await run(tx('合规证据已生成', 'Compliance evidence generated'), () => api.get('/governance/compliance-report'), false)
  compliance.value = data
}

async function scanGuardrails() {
  const form = guardrailForm.value
  await run(tx('四层护栏扫描完成', 'Four-layer guardrail scan completed'), () => api.post('/governance/guardrails/scan', {
    text: form.text, fileName: form.fileName, fileBase64: form.fileBase64,
    toolParameters: { url: form.toolUrl, command: form.toolCommand },
  }), false)
}

async function saveApprovalPolicy() {
  const form = approvalForm.value
  await run(tx('审批策略已保存', 'Approval policy saved'), () => api.post('/governance/approval-policies', {
    name: form.name, decision: form.decision, priority: form.priority, slaMinutes: form.slaMinutes,
    escalationRole: 'approver', enabled: true,
    conditions: { tool: form.tool ? [form.tool] : [], amountMin: form.amountMin,
      dataClassification: form.dataClassification, callerType: form.callerType },
  }))
}

async function testApproval() {
  await run(tx('动态审批决策已完成', 'Dynamic approval decision completed'),
    () => api.post('/governance/approval-policies/evaluate', approvalTest.value), false)
}

async function saveOnCall() {
  const form = onCallForm.value
  await run(tx('值班表已保存', 'On-call schedule saved'), () => api.post('/governance/on-call', {
    ...form, primaryUserId: form.primaryUserId || null, backupUserId: form.backupUserId || null, enabled: true,
  }))
}

async function createJob(type: 'exports' | 'backups') {
  await run(type === 'exports' ? tx('租户导出已生成', 'Tenant export created') : tx('租户备份已生成', 'Tenant backup created'),
    () => api.post(`/governance/${type}`))
}

async function verifyJob(item: any) {
  await run(tx('校验和验证完成', 'Checksum verification completed'), () => api.post(`/governance/jobs/${item.id}/verify`), false)
}

async function restoreDrill(item: any) {
  await run(tx('恢复演练通过', 'Restore drill passed'), () => api.post(`/governance/backups/${item.id}/restore-drill`))
}

async function createMigration() {
  await run(tx('迁移计划已校验', 'Migration plan validated'), () => api.post('/governance/migrations', { targetTenantRef: migrationTarget.value }))
}

onMounted(loadAll)
</script>

<template>
  <div class="console-page governance-page" data-no-ui-translate v-loading="loading">
    <div class="console-page-head">
      <div class="console-page-head__copy">
        <span>ENTERPRISE GOVERNANCE</span>
        <h1>{{ tx('企业治理控制台', 'Enterprise Governance') }}</h1>
        <p>{{ tx('身份、密钥、数据、护栏、审批与恢复统一在租户边界内执行并留痕。', 'Identity, keys, data, guardrails, approvals and recovery are enforced and evidenced inside each tenant boundary.') }}</p>
      </div>
      <div class="console-page-actions">
        <button class="console-secondary" @click="loadAll"><el-icon><Refresh /></el-icon>{{ tx('刷新', 'Refresh') }}</button>
        <button class="console-primary" @click="reportCompliance"><el-icon><DocumentChecked /></el-icon>{{ tx('生成合规报告', 'Generate report') }}</button>
      </div>
    </div>

    <section class="governance-summary">
      <div><span>{{ tx('身份源', 'Identity providers') }}</span><strong>{{ overview.counts?.identityProviders || 0 }}</strong></div>
      <div><span>{{ tx('托管密钥', 'Managed secrets') }}</span><strong>{{ overview.counts?.managedSecrets || 0 }}</strong></div>
      <div><span>{{ tx('访问策略', 'Access policies') }}</span><strong>{{ overview.counts?.accessPolicies || 0 }}</strong></div>
      <div><span>{{ tx('逾期审批', 'Overdue approvals') }}</span><strong :class="{ alert: overview.counts?.overdueApprovals }">{{ overview.counts?.overdueApprovals || 0 }}</strong></div>
      <div><span>{{ tx('恢复资产', 'Recovery artifacts') }}</span><strong>{{ overview.counts?.recoveryArtifacts || 0 }}</strong></div>
    </section>

    <nav class="governance-tabs" :aria-label="tx('治理能力', 'Governance capabilities')">
      <button v-for="tab in tabs" :key="tab.name" :class="{ active: activeTab === tab.name }" @click="activeTab = tab.name">
        <el-icon><component :is="tab.icon" /></el-icon><span>{{ tab.label }}</span>
      </button>
    </nav>

    <section v-if="activeTab === 'identity'" class="governance-workspace">
      <header><div><h2>{{ tx('企业身份与细粒度访问', 'Enterprise identity and fine-grained access') }}</h2><p>{{ tx('OIDC/SAML 配置验证、SCIM 组织同步以及默认拒绝的 RBAC/ABAC 决策。', 'OIDC/SAML configuration validation, SCIM organization sync, and default-deny RBAC/ABAC decisions.') }}</p></div><el-icon><Connection /></el-icon></header>
      <div class="governance-grid three">
        <form @submit.prevent="saveProvider">
          <h3>{{ tx('身份提供商', 'Identity provider') }}</h3>
          <div class="console-segmented"><button type="button" :class="{ active: providerForm.providerType === 'oidc' }" @click="providerForm.providerType = 'oidc'">OIDC</button><button type="button" :class="{ active: providerForm.providerType === 'saml' }" @click="providerForm.providerType = 'saml'">SAML</button></div>
          <el-input v-model="providerForm.name" :placeholder="tx('配置名称', 'Configuration name')" required />
          <el-input v-model="providerForm.issuer" :placeholder="providerForm.providerType === 'oidc' ? 'Issuer URL' : 'SSO URL'" required />
          <el-input v-model="providerForm.clientId" :placeholder="providerForm.providerType === 'oidc' ? 'Client ID' : 'Entity ID'" required />
          <el-input v-model="providerForm.secretRef" :placeholder="providerForm.providerType === 'oidc' ? 'Vault secretRef' : 'X.509 certificate'" required />
          <button class="console-primary" type="submit"><el-icon><Plus /></el-icon>{{ tx('验证并保存', 'Validate & save') }}</button>
        </form>
        <form @submit.prevent="syncScimUser">
          <h3>{{ tx('SCIM 用户同步', 'SCIM user sync') }}</h3>
          <el-input v-model="scimUserForm.userName" placeholder="userName" required />
          <el-input v-model="scimUserForm.displayName" :placeholder="tx('显示名称', 'Display name')" required />
          <el-input v-model="scimUserForm.email" placeholder="Email" />
          <el-input v-model="scimUserForm.department" :placeholder="tx('部门', 'Department')" />
          <el-switch v-model="scimUserForm.active" :active-text="tx('启用账号', 'Active account')" />
          <button class="console-primary" type="submit"><el-icon><Upload /></el-icon>{{ tx('同步组织成员', 'Sync member') }}</button>
        </form>
        <form @submit.prevent="issueToken">
          <h3>{{ tx('SCIM 凭证', 'SCIM credential') }}</h3>
          <el-input v-model="scimForm.name" :placeholder="tx('令牌名称', 'Token name')" required />
          <el-input-number v-model="scimForm.expiryDays" :min="1" :max="3650" />
          <p class="form-note">{{ tx('令牌仅显示一次，数据库只保存 SHA-256 哈希。', 'The token is shown once; only its SHA-256 hash is stored.') }}</p>
          <button class="console-secondary" type="submit"><el-icon><Key /></el-icon>{{ tx('签发令牌', 'Issue token') }}</button>
        </form>
      </div>
      <div class="governance-grid two">
        <form @submit.prevent="saveAccessPolicy">
          <h3>{{ tx('ABAC 策略', 'ABAC policy') }}</h3>
          <el-input v-model="accessForm.name" :placeholder="tx('策略名称', 'Policy name')" required />
          <div class="field-row"><el-select v-model="accessForm.effect"><el-option value="allow" label="Allow" /><el-option value="deny" label="Deny" /></el-select><el-input v-model="accessForm.resourceType" placeholder="resourceType" /></div>
          <el-input v-model="accessForm.actionPattern" placeholder="actionPattern: read*" />
          <el-input v-model="accessForm.department" :placeholder="tx('部门条件（可选）', 'Department condition (optional)')" />
          <button class="console-primary" type="submit">{{ tx('保存策略', 'Save policy') }}</button>
        </form>
        <form @submit.prevent="testAccess">
          <h3>{{ tx('实时访问决策', 'Live access decision') }}</h3>
          <div class="field-row"><el-input v-model="accessTest.resourceType" placeholder="resourceType" /><el-input v-model="accessTest.action" placeholder="action" /></div>
          <el-input v-model="accessTest.department" :placeholder="tx('请求方部门', 'Requester department')" />
          <button class="console-secondary" type="submit"><el-icon><Search /></el-icon>{{ tx('评估', 'Evaluate') }}</button>
        </form>
      </div>
      <el-table :data="accessPolicies"><el-table-column prop="name" :label="tx('策略', 'Policy')" /><el-table-column prop="effect" label="Effect" width="100" /><el-table-column prop="resourceType" label="Resource" /><el-table-column prop="actionPattern" label="Action" /></el-table>
    </section>

    <section v-else-if="activeTab === 'kms'" class="governance-workspace">
      <header><div><h2>{{ tx('租户级密钥托管', 'Tenant key management') }}</h2><p>{{ tx('AES-256-GCM 字段加密、随机 Nonce、租户派生密钥和可验证轮换。', 'AES-256-GCM field encryption, random nonces, tenant-derived keys, and verifiable rotation.') }}</p></div><button class="console-secondary" @click="rotateKey"><el-icon><RefreshRight /></el-icon>{{ tx('轮换密钥', 'Rotate key') }}</button></header>
      <form class="horizontal-form" @submit.prevent="saveSecret">
        <el-input v-model="secretForm.secretKey" :placeholder="tx('字段键名', 'Secret key')" required />
        <el-input v-model="secretForm.value" type="password" show-password :placeholder="tx('敏感值', 'Sensitive value')" required />
        <el-input v-model="secretForm.description" :placeholder="tx('用途说明', 'Description')" />
        <button class="console-primary" type="submit"><el-icon><Lock /></el-icon>{{ tx('加密保存', 'Encrypt & store') }}</button>
      </form>
      <el-table :data="secrets"><el-table-column prop="secretKey" :label="tx('键名', 'Key')" /><el-table-column prop="description" :label="tx('说明', 'Description')" /><el-table-column prop="keyVersion" :label="tx('密钥版本', 'Key version')" width="110" /><el-table-column width="110"><template #default="scope"><el-button link @click="revealSecret(scope.row)">{{ tx('审计查看', 'Audit view') }}</el-button></template></el-table-column></el-table>
    </section>

    <section v-else-if="activeTab === 'data'" class="governance-workspace">
      <header><div><h2>{{ tx('数据保留与合规证据', 'Data retention and compliance evidence') }}</h2><p>{{ tx('先预演候选记录，再执行删除或匿名化；Legal Hold 会阻止执行。', 'Preview candidate records before deletion or anonymization; legal hold blocks execution.') }}</p></div><button class="console-secondary" @click="reportCompliance"><el-icon><DocumentChecked /></el-icon>{{ tx('刷新报告', 'Refresh report') }}</button></header>
      <form class="horizontal-form" @submit.prevent="saveRetention">
        <el-select v-model="retentionForm.dataType"><el-option value="audit_log" label="Audit logs" /><el-option value="token_usage" label="Token usage" /><el-option value="execution_trace" label="Execution traces" /></el-select>
        <el-input-number v-model="retentionForm.retentionDays" :min="1" :max="36500" />
        <el-select v-model="retentionForm.action"><el-option value="anonymize" label="Anonymize" /><el-option value="delete" label="Delete" /></el-select>
        <el-switch v-model="retentionForm.legalHold" active-text="Legal Hold" />
        <button class="console-primary" type="submit">{{ tx('保存策略', 'Save policy') }}</button>
      </form>
      <el-table :data="retentionPolicies"><el-table-column prop="dataType" :label="tx('数据类型', 'Data type')" /><el-table-column prop="retentionDays" :label="tx('保留天数', 'Days')" /><el-table-column prop="action" :label="tx('动作', 'Action')" /><el-table-column width="190"><template #default="scope"><el-button link @click="runRetention(scope.row, false)">{{ tx('预演', 'Preview') }}</el-button><el-button link type="danger" @click="runRetention(scope.row, true)">{{ tx('执行', 'Execute') }}</el-button></template></el-table-column></el-table>
      <pre v-if="compliance" class="result-console">{{ JSON.stringify(compliance, null, 2) }}</pre>
    </section>

    <section v-else-if="activeTab === 'guardrails'" class="governance-workspace">
      <header><div><h2>{{ tx('四层安全护栏', 'Four-layer security guardrails') }}</h2><p>{{ tx('同时检查提示词注入、敏感数据、恶意文件签名和工具参数注入/SSRF。', 'Checks prompt injection, sensitive data, malicious file signatures, and tool-parameter injection/SSRF together.') }}</p></div><el-icon><Lock /></el-icon></header>
      <form class="guardrail-form" @submit.prevent="scanGuardrails">
        <el-input v-model="guardrailForm.text" type="textarea" :rows="5" :placeholder="tx('输入待扫描文本', 'Text to scan')" />
        <div class="field-row"><el-input v-model="guardrailForm.fileName" :placeholder="tx('文件名（可选）', 'File name (optional)')" /><el-input v-model="guardrailForm.fileBase64" placeholder="Base64 payload (optional)" /></div>
        <div class="field-row"><el-input v-model="guardrailForm.toolUrl" placeholder="Tool callback URL" /><el-input v-model="guardrailForm.toolCommand" placeholder="Tool command / query" /></div>
        <button class="console-primary" type="submit"><el-icon><Search /></el-icon>{{ tx('执行完整扫描', 'Run full scan') }}</button>
      </form>
    </section>

    <section v-else-if="activeTab === 'policy'" class="governance-workspace">
      <header><div><h2>{{ tx('动态审批策略引擎', 'Dynamic approval policy engine') }}</h2><p>{{ tx('按工具、金额、数据级别、时间段与调用方匹配优先级策略。', 'Matches prioritized policies by tool, amount, data classification, time window, and caller.') }}</p></div><el-icon><DataBoard /></el-icon></header>
      <div class="governance-grid two">
        <form @submit.prevent="saveApprovalPolicy"><h3>{{ tx('新增策略', 'New policy') }}</h3><el-input v-model="approvalForm.name" :placeholder="tx('策略名称', 'Policy name')" required /><div class="field-row"><el-select v-model="approvalForm.decision"><el-option value="auto_approve" label="Auto approve" /><el-option value="single" label="Single" /><el-option value="dual" label="Dual" /><el-option value="reject" label="Reject" /></el-select><el-input v-model="approvalForm.tool" placeholder="tool" /></div><div class="field-row"><el-input-number v-model="approvalForm.amountMin" :min="0" /><el-select v-model="approvalForm.dataClassification"><el-option value="public" label="Public" /><el-option value="internal" label="Internal" /><el-option value="confidential" label="Confidential" /></el-select></div><div class="field-row"><el-input v-model="approvalForm.callerType" placeholder="callerType" /><el-input-number v-model="approvalForm.slaMinutes" :min="1" :max="43200" /></div><button class="console-primary" type="submit">{{ tx('保存策略', 'Save policy') }}</button></form>
        <form @submit.prevent="testApproval"><h3>{{ tx('决策试算', 'Decision simulation') }}</h3><el-input v-model="approvalTest.tool" placeholder="tool" /><el-input-number v-model="approvalTest.amount" :min="0" /><el-select v-model="approvalTest.dataClassification"><el-option value="public" label="Public" /><el-option value="internal" label="Internal" /><el-option value="confidential" label="Confidential" /></el-select><el-input v-model="approvalTest.callerType" placeholder="callerType" /><button class="console-secondary" type="submit">{{ tx('评估审批要求', 'Evaluate approval') }}</button></form>
      </div>
      <el-table :data="approvalPolicies"><el-table-column prop="name" :label="tx('策略', 'Policy')" /><el-table-column prop="decision" :label="tx('决策', 'Decision')" /><el-table-column prop="slaMinutes" label="SLA (min)" /><el-table-column prop="escalationRole" :label="tx('升级角色', 'Escalation role')" /></el-table>
    </section>

    <section v-else-if="activeTab === 'operations'" class="governance-workspace">
      <header><div><h2>{{ tx('值班升级与移动审批', 'On-call escalation and mobile approval') }}</h2><p>{{ tx('逾期审批自动标记 SLA 并转派备班；现有审批中心响应式支持移动端处理。', 'Overdue approvals are marked and reassigned to backup on-call users; the responsive approval center supports mobile decisions.') }}</p></div><button class="console-primary" @click="run(tx('SLA 扫描完成', 'SLA sweep completed'), () => api.post('/governance/approval-sla/sweep'))">{{ tx('扫描逾期审批', 'Sweep overdue') }}</button></header>
      <form class="horizontal-form" @submit.prevent="saveOnCall"><el-input v-model="onCallForm.name" :placeholder="tx('值班表名称', 'Schedule name')" required /><el-input v-model="onCallForm.primaryUserId" :placeholder="tx('主值班用户 ID', 'Primary user ID')" /><el-input v-model="onCallForm.backupUserId" :placeholder="tx('备班用户 ID', 'Backup user ID')" /><el-input v-model="onCallForm.activeFrom" type="time" /><el-input v-model="onCallForm.activeTo" type="time" /><button class="console-primary" type="submit">{{ tx('保存值班表', 'Save schedule') }}</button></form>
      <el-table :data="onCall"><el-table-column prop="name" :label="tx('值班表', 'Schedule')" /><el-table-column prop="primaryUserId" :label="tx('主值班', 'Primary')" /><el-table-column prop="backupUserId" :label="tx('备班', 'Backup')" /><el-table-column prop="timezone" :label="tx('时区', 'Timezone')" /></el-table>
      <button class="console-secondary mobile-approval" @click="router.push('/console/approvals')"><el-icon><UserFilled /></el-icon>{{ tx('打开移动审批界面', 'Open mobile approval view') }}</button>
    </section>

    <section v-else class="governance-workspace">
      <header><div><h2>{{ tx('导出、备份、恢复与迁移', 'Export, backup, recovery and migration') }}</h2><p>{{ tx('生成租户范围清单和 SHA-256 校验和；恢复仅执行安全预演，迁移先生成可验证计划。', 'Generates tenant-scoped manifests and SHA-256 checksums; recovery is a safe dry run and migration first creates a verifiable plan.') }}</p></div><el-icon><RefreshRight /></el-icon></header>
      <div class="recovery-actions"><button class="console-primary" @click="createJob('exports')"><el-icon><Download /></el-icon>{{ tx('创建数据导出', 'Create export') }}</button><button class="console-secondary" @click="createJob('backups')"><el-icon><DocumentChecked /></el-icon>{{ tx('创建备份', 'Create backup') }}</button><el-input v-model="migrationTarget" /><button class="console-secondary" @click="createMigration"><el-icon><Upload /></el-icon>{{ tx('验证迁移计划', 'Validate migration') }}</button></div>
      <el-table :data="jobs"><el-table-column prop="jobType" :label="tx('类型', 'Type')" /><el-table-column prop="status" :label="tx('状态', 'Status')" /><el-table-column prop="checksum" label="SHA-256" show-overflow-tooltip /><el-table-column width="210"><template #default="scope"><el-button link @click="verifyJob(scope.row)">{{ tx('验证', 'Verify') }}</el-button><el-button v-if="scope.row.jobType === 'backup'" link @click="restoreDrill(scope.row)">{{ tx('恢复演练', 'Restore drill') }}</el-button></template></el-table-column></el-table>
    </section>

    <aside v-if="result" class="governance-result"><header><span>{{ tx('最近执行结果', 'Latest execution result') }}</span><button aria-label="Close" @click="result = null">×</button></header><pre>{{ JSON.stringify(result, null, 2) }}</pre></aside>
  </div>
</template>

<style scoped>
.governance-page { position: relative; }
.governance-summary { margin-bottom: 14px; display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); border: 1px solid var(--console-line); border-radius: 8px; background: var(--console-panel); overflow: hidden; }
.governance-summary div { min-height: 76px; padding: 14px 16px; display: flex; flex-direction: column; justify-content: space-between; border-right: 1px solid var(--console-line); }.governance-summary div:last-child { border-right: 0; }
.governance-summary span { color: var(--console-muted); font-size: 9px; }.governance-summary strong { color: var(--console-ink); font: 22px ui-monospace, monospace; }.governance-summary strong.alert { color: var(--console-danger); }
.governance-tabs { margin-bottom: 14px; padding: 4px; display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); gap: 3px; border: 1px solid var(--console-line); border-radius: 8px; background: #101417; }
.governance-tabs button { min-height: 48px; padding: 6px; display: flex; align-items: center; justify-content: center; gap: 7px; border: 0; border-radius: 5px; background: transparent; color: var(--console-muted); font: inherit; font-size: 9px; cursor: pointer; }.governance-tabs button.active { background: var(--console-panel-soft); color: var(--console-accent); box-shadow: inset 0 0 0 1px var(--console-line-strong); }
.governance-workspace { border: 1px solid var(--console-line); border-radius: 8px; background: var(--console-panel); overflow: hidden; }.governance-workspace > header { min-height: 82px; padding: 16px 18px; display: flex; align-items: center; justify-content: space-between; gap: 20px; border-bottom: 1px solid var(--console-line); }.governance-workspace > header h2 { margin: 0; font-size: 15px; }.governance-workspace > header p { max-width: 760px; margin: 6px 0 0; color: var(--console-muted); font-size: 9px; line-height: 1.6; }.governance-workspace > header > .el-icon { color: var(--console-accent); font-size: 24px; }
.governance-grid { display: grid; border-bottom: 1px solid var(--console-line); }.governance-grid.two { grid-template-columns: repeat(2, minmax(0, 1fr)); }.governance-grid.three { grid-template-columns: repeat(3, minmax(0, 1fr)); }.governance-grid form { min-width: 0; padding: 16px; display: flex; flex-direction: column; gap: 10px; border-right: 1px solid var(--console-line); }.governance-grid form:last-child { border-right: 0; }
form h3 { margin: 0 0 3px; color: var(--console-ink); font-size: 11px; }.form-note { margin: 0; color: var(--console-muted); font-size: 8px; line-height: 1.6; }.field-row { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.horizontal-form { padding: 16px; display: grid; grid-template-columns: repeat(3, minmax(130px, 1fr)) auto; gap: 9px; align-items: center; border-bottom: 1px solid var(--console-line); }.guardrail-form { padding: 18px; display: flex; flex-direction: column; gap: 10px; }.guardrail-form .console-primary { align-self: flex-start; }
.recovery-actions { padding: 16px; display: grid; grid-template-columns: auto auto minmax(180px, 1fr) auto; gap: 9px; border-bottom: 1px solid var(--console-line); }
.mobile-approval { margin: 16px; }.result-console { margin: 0; padding: 16px; max-height: 260px; overflow: auto; color: var(--console-accent); font: 9px/1.6 ui-monospace, monospace; }
.governance-result { position: fixed; z-index: 70; right: 20px; bottom: 18px; width: min(460px, calc(100vw - 32px)); max-height: 42vh; border: 1px solid var(--console-line-strong); border-radius: 8px; background: #0c1012; box-shadow: var(--console-shadow); overflow: hidden; }.governance-result header { min-height: 38px; padding: 0 10px 0 13px; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid var(--console-line); color: var(--console-ink); font-size: 9px; }.governance-result header button { width: 28px; height: 28px; border: 0; background: transparent; color: var(--console-muted); font-size: 18px; cursor: pointer; }.governance-result pre { max-height: calc(42vh - 39px); margin: 0; padding: 13px; overflow: auto; color: #8fe1b4; font: 8px/1.55 ui-monospace, monospace; white-space: pre-wrap; word-break: break-word; }
@media (max-width: 1100px) { .governance-tabs { grid-template-columns: repeat(4, 1fr); }.governance-summary { grid-template-columns: repeat(3, 1fr); }.governance-grid.three { grid-template-columns: 1fr; }.governance-grid.three form { border-right: 0; border-bottom: 1px solid var(--console-line); }.horizontal-form { grid-template-columns: repeat(2, minmax(0, 1fr)); }.recovery-actions { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 720px) { .governance-summary { grid-template-columns: repeat(2, 1fr); }.governance-tabs { grid-template-columns: repeat(2, 1fr); }.governance-tabs button { justify-content: flex-start; padding-inline: 10px; }.governance-grid.two { grid-template-columns: 1fr; }.governance-grid form { border-right: 0; border-bottom: 1px solid var(--console-line); }.horizontal-form, .recovery-actions { grid-template-columns: 1fr; }.field-row { grid-template-columns: 1fr; }.governance-workspace > header { align-items: flex-start; flex-direction: column; } }
</style>
