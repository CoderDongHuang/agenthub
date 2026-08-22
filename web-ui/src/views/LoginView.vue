<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, ArrowRight, Check, Connection, Hide, Lock, Message, OfficeBuilding, User, View } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'
import api from '../api'
import PersonalizationPanel from '../components/PersonalizationPanel.vue'

const router = useRouter()
const authStore = useAuthStore()
const mode = ref<'login' | 'register'>('login')
const loading = ref(false)
const formError = ref('')
const showPassword = ref(false)
const terminalLine = ref(0)
const loginForm = ref({ username: '', password: '' })
const registerForm = ref({ username: '', password: '', displayName: '', email: '', teamName: '', scenario: 'internal-rag' })
const terminalLogs = [
  { time: '09:41:00', service: 'JAVA', message: 'Security Gateway initialized', tone: 'green' },
  { time: '09:41:01', service: 'PYTHON', message: 'Agent Runtime synchronized', tone: 'cyan' },
  { time: '09:41:02', service: 'POLICY', message: 'Risk interceptor ready', tone: 'amber' },
  { time: '09:41:03', service: 'AUDIT', message: 'Trace store connected', tone: 'blue' },
]
let terminalTimer: number | undefined

const passwordScore = computed(() => {
  const password = mode.value === 'register' ? registerForm.value.password : loginForm.value.password
  return [password.length >= 6, /[A-Z]/.test(password), /\d/.test(password), /[^A-Za-z0-9]/.test(password)].filter(Boolean).length
})
const passwordLabel = computed(() => ['未设置', '较弱', '可用', '良好', '较强'][passwordScore.value])
function switchMode(next: 'login' | 'register') { mode.value = next; formError.value = ''; showPassword.value = false }
function notifySso(provider: string) { ElMessage.info(`${provider} 登录需要先在服务端配置 OAuth / SSO，本地开源版当前未启用。`) }
async function handleLogin() {
  formError.value = ''
  if (!loginForm.value.username || !loginForm.value.password) { formError.value = '请输入用户名和密码。'; return }
  loading.value = true
  try { await authStore.login(loginForm.value.username, loginForm.value.password); router.push('/console/dashboard') }
  catch (error: any) { formError.value = error?.response?.data?.message || '登录失败，请检查账号信息。' }
  finally { loading.value = false }
}
async function handleRegister() {
  formError.value = ''
  const form = registerForm.value
  if (!form.username || !form.password || !form.displayName || !form.teamName) { formError.value = '用户名、显示名称、团队名称和密码为必填项。'; return }
  if (form.username.length < 3 || form.password.length < 6) { formError.value = '用户名至少 3 位，密码至少 6 位。'; return }
  loading.value = true
  try {
    await api.post('/auth/register', { username: form.username, password: form.password, displayName: form.displayName, email: form.email })
    loginForm.value = { username: form.username, password: '' }
    registerForm.value = { username: '', password: '', displayName: '', email: '', teamName: '', scenario: 'internal-rag' }
    switchMode('login')
    ElMessage.success('账号已创建；团队与场景信息将在租户能力启用后生效。')
  } catch (error: any) { formError.value = error?.response?.data?.message || '注册失败，请稍后重试。' }
  finally { loading.value = false }
}
onMounted(() => { terminalTimer = window.setInterval(() => { terminalLine.value = (terminalLine.value + 1) % terminalLogs.length }, 1500) })
onBeforeUnmount(() => { if (terminalTimer) window.clearInterval(terminalTimer) })
</script>

<template>
  <div class="auth-page">
    <section class="auth-visual">
      <span class="auth-node node-1" /><span class="auth-node node-2" /><span class="auth-node node-3" /><i class="auth-link link-1" /><i class="auth-link link-2" />
      <header><button aria-label="返回官网" @click="router.push('/')"><el-icon><ArrowLeft /></el-icon></button><img src="/bg.svg" alt="AgentMesh" /><strong>AgentMesh</strong><PersonalizationPanel class="auth-personalization" /></header>
      <div class="auth-message"><span class="site-kicker">ENTERPRISE AGENT PLATFORM</span><h1>构建受控、合规的<br>企业级 AI 智能体。</h1><p>双引擎协同架构，将 Java 企业治理与 Python Agent 运行时放进一条可观察、可审批的执行链路。</p>
        <div class="auth-terminal"><header><div><i /><i /><i /></div><span>system.log</span><b>LIVE</b></header><div><p v-for="(log, index) in terminalLogs" :key="log.service" :class="[log.tone, { active: terminalLine === index }]"><span>[{{ log.time }}]</span><b>{{ log.service }}</b><strong>{{ log.message }}</strong><i v-if="terminalLine === index" /></p></div><footer><span>0 violations</span><b><i /> all systems operational</b></footer></div>
      </div>
      <footer><span><el-icon><Lock /></el-icon>多租户权限隔离</span><span><el-icon><Check /></el-icon>动态卡点拦截</span><span><el-icon><Connection /></el-icon>ReAct Trace 审计</span></footer>
    </section>

    <main class="auth-panel">
      <div class="auth-panel__inner">
        <header class="mobile-brand"><img src="/bg.svg" alt="AgentMesh" /><strong>AgentMesh</strong><PersonalizationPanel compact /><button @click="router.push('/')"><el-icon><ArrowLeft /></el-icon></button></header>
        <div class="auth-mode"><button :class="{ active: mode === 'login' }" @click="switchMode('login')">登录账号</button><button :class="{ active: mode === 'register' }" @click="switchMode('register')">注册 / 申请试用</button></div>
        <div class="entry-heading"><span>{{ mode === 'login' ? 'WELCOME BACK' : 'CREATE WORKSPACE ACCOUNT' }}</span><h2>{{ mode === 'login' ? '进入 AgentMesh' : '创建企业账号' }}</h2><p>{{ mode === 'login' ? '使用组织账号访问 Agent 控制台与工作台。' : '创建本地账号，并记录团队与应用场景偏好。' }}</p></div>

        <div class="sso-actions"><button type="button" @click="notifySso('企业 SSO')"><el-icon><OfficeBuilding /></el-icon><span>企业 SSO</span><small>未配置</small></button><button type="button" @click="notifySso('GitHub OAuth')"><b>GH</b><span>GitHub</span><small>未配置</small></button></div>
        <div class="auth-divider"><i /><span>或使用本地账号密码</span><i /></div>

        <form v-if="mode === 'login'" class="auth-form" @submit.prevent="handleLogin">
          <label><span>用户名</span><div><el-icon><User /></el-icon><input v-model.trim="loginForm.username" autocomplete="username" placeholder="例如 admin" /></div></label>
          <label><div class="label-row"><span>密码</span><small>本地开源版本</small></div><div><el-icon><Lock /></el-icon><input v-model="loginForm.password" :type="showPassword ? 'text' : 'password'" autocomplete="current-password" placeholder="输入密码" /><button type="button" :aria-label="showPassword ? '隐藏密码' : '显示密码'" @click="showPassword = !showPassword"><el-icon><component :is="showPassword ? Hide : View" /></el-icon></button></div></label>
          <div v-if="formError" class="form-error">{{ formError }}</div>
          <button class="auth-submit" type="submit" :disabled="loading"><span>{{ loading ? '正在验证…' : '进入工作台' }}</span><el-icon><ArrowRight /></el-icon></button>
          <div class="demo-account"><span>本地演示账号</span><button type="button" @click="loginForm = { username: 'admin', password: 'admin123' }">填入 admin / admin123</button></div>
        </form>

        <form v-else class="auth-form register-form" @submit.prevent="handleRegister">
          <div class="field-row"><label><span>用户名 *</span><div><el-icon><User /></el-icon><input v-model.trim="registerForm.username" autocomplete="username" placeholder="至少 3 位" /></div></label><label><span>显示名称 *</span><div><el-icon><Message /></el-icon><input v-model.trim="registerForm.displayName" placeholder="姓名或称呼" /></div></label></div>
          <label><span>企业 / 团队名称 *</span><div><el-icon><OfficeBuilding /></el-icon><input v-model.trim="registerForm.teamName" placeholder="例如：AgentMesh 演示团队" /></div></label>
          <div class="field-row"><label><span>工作邮箱</span><div><el-icon><Message /></el-icon><input v-model.trim="registerForm.email" type="email" autocomplete="email" placeholder="name@company.com" /></div></label><label><span>主要应用场景</span><div><el-icon><Connection /></el-icon><select v-model="registerForm.scenario"><option value="risk">金融风控</option><option value="service">智能客服</option><option value="internal-rag">内部 RAG</option><option value="ops">自动化运维</option></select></div></label></div>
          <label><span>密码 *</span><div><el-icon><Lock /></el-icon><input v-model="registerForm.password" :type="showPassword ? 'text' : 'password'" autocomplete="new-password" placeholder="至少 6 位" /><button type="button" :aria-label="showPassword ? '隐藏密码' : '显示密码'" @click="showPassword = !showPassword"><el-icon><component :is="showPassword ? Hide : View" /></el-icon></button></div></label>
          <div class="password-strength"><span><i v-for="index in 4" :key="index" :class="{ active: index <= passwordScore }" /></span><b>{{ passwordLabel }}</b></div>
          <div v-if="formError" class="form-error">{{ formError }}</div>
          <button class="auth-submit" type="submit" :disabled="loading"><span>{{ loading ? '正在创建…' : '创建账号并开启体验' }}</span><el-icon><ArrowRight /></el-icon></button>
        </form>

        <div class="auth-security"><el-icon><Lock /></el-icon><p><strong>安全登录</strong><small>JWT 会话 · CSRF 防护 · RBAC 权限控制</small></p></div>
      </div>
    </main>
  </div>
</template>

<style scoped>
.auth-page { min-height: 100vh; display: grid; grid-template-columns: 3fr 2fr; overflow: hidden; background: #090c0f; color: #edf3ef; font-family: Inter, "PingFang SC", "Microsoft YaHei", system-ui, sans-serif; }.auth-visual { position: relative; min-width: 0; min-height: 100vh; padding: 34px clamp(30px,5vw,70px); display: flex; flex-direction: column; overflow: hidden; border-right: 1px solid #293137; background: #070a0d; }.auth-visual > header { position: relative; z-index: 2; display: flex; align-items: center; gap: 9px; }.auth-visual > header button { width: 32px; height: 32px; margin-right: 5px; display: grid; place-items: center; border: 1px solid #30383e; border-radius: 6px; background: #111518; color: #7c878e; cursor: pointer; }.auth-visual > header img { width: 31px; height: 31px; }.auth-visual > header strong { font-size: 14px; }.auth-visual > header > span { margin-left: 4px; padding: 4px 7px; display: flex; align-items: center; gap: 5px; border: 1px solid #31513f; border-radius: 10px; color: #6fe0a5; font: 6px ui-monospace, monospace; }.auth-visual > header > span i { width: 5px; height: 5px; border-radius: 50%; background: #5ee19f; box-shadow: 0 0 8px rgba(94,225,159,.6); }.auth-node { position: absolute; width: 8px; height: 8px; border: 1px solid #3f6652; border-radius: 50%; background: #111a15; box-shadow: 0 0 13px rgba(81,220,151,.28); }.node-1 { left: 10%; top: 24%; }.node-2 { right: 13%; top: 16%; }.node-3 { right: 22%; bottom: 12%; }.auth-link { position: absolute; height: 1px; background: #1e3027; transform-origin: left center; }.link-1 { left: 10%; top: 24%; width: 78%; transform: rotate(-6deg); }.link-2 { right: 21%; top: 16%; width: 30%; transform: rotate(84deg); }.auth-message { position: relative; z-index: 2; width: min(640px,100%); margin: auto 0; }.auth-message h1 { margin-top: 17px; font-size: clamp(38px,4.2vw,56px); line-height: 1.08; }.auth-message > p { max-width: 600px; margin-top: 18px; color: #7f8b92; font-size: 12px; line-height: 1.75; }.auth-terminal { margin-top: 30px; border: 1px solid #2e373c; border-radius: 8px; background: rgba(10,14,16,.93); box-shadow: 0 25px 60px rgba(0,0,0,.42); overflow: hidden; }.auth-terminal > header { min-height: 40px; padding: 0 12px; display: grid; grid-template-columns: 1fr auto 1fr; align-items: center; border-bottom: 1px solid #283035; }.auth-terminal > header div { display: flex; gap: 5px; }.auth-terminal > header div i { width: 7px; height: 7px; border-radius: 50%; background: #4a5359; }.auth-terminal > header div i:first-child { background: #e57167; }.auth-terminal > header div i:nth-child(2) { background: #dcae56; }.auth-terminal > header div i:last-child { background: #52d894; }.auth-terminal > header span { color: #59646b; font: 12px ui-monospace, monospace; }.auth-terminal > header b { justify-self: end; color: #5cd99b; font: 6px ui-monospace, monospace; }.auth-terminal > div { padding: 13px 15px; }.auth-terminal p { min-height: 28px; display: grid; grid-template-columns: 70px 58px 1fr 8px; align-items: center; color: #69747b; font: 12px ui-monospace, monospace; transition: color .2s; }.auth-terminal p.active { color: #aab6af; }.auth-terminal p > b { color: #68dca1; }.auth-terminal p.cyan > b { color: #63c6cc; }.auth-terminal p.amber > b { color: #deb45a; }.auth-terminal p.blue > b { color: #6eb7e1; }.auth-terminal p strong { font-weight: 500; }.auth-terminal p > i { width: 5px; height: 11px; background: #5ddf9e; animation: caretBlink .8s infinite; }.auth-terminal > footer { min-height: 38px; padding: 0 14px; display: flex; align-items: center; justify-content: space-between; border-top: 1px solid #283035; color: #59646b; font: 6px ui-monospace, monospace; }.auth-terminal > footer b { display: flex; align-items: center; gap: 5px; color: #619577; }.auth-terminal > footer b i { width: 5px; height: 5px; border-radius: 50%; background: #57d998; }.auth-visual > footer { position: relative; z-index: 2; display: flex; flex-wrap: wrap; gap: 22px; color: #647078; font-size: 12px; }.auth-visual > footer span { display: flex; align-items: center; gap: 6px; }.auth-visual > footer .el-icon { color: #65d99f; }
.auth-panel { min-width: 0; min-height: 100vh; padding: 36px clamp(26px,4vw,54px); display: flex; align-items: center; justify-content: center; background: #0c1013; overflow-y: auto; }.auth-panel__inner { width: min(440px,100%); }.mobile-brand { display: none; }.auth-mode { padding: 4px; display: grid; grid-template-columns: 1fr 1fr; border: 1px solid #2d353b; border-radius: 7px; background: #0a0e10; }.auth-mode button { min-height: 38px; border: 0; border-radius: 5px; background: transparent; color: #707b82; font: inherit; font-size: 12px; font-weight: 700; cursor: pointer; }.auth-mode button.active { background: #1b2420; color: #7ce4ad; box-shadow: inset 0 0 0 1px #344a3e; }.entry-heading { margin: 29px 0 21px; }.entry-heading > span { color: #5fdda0; font: 12px ui-monospace, monospace; }.entry-heading h2 { margin-top: 8px; font-size: 27px; }.entry-heading p { margin-top: 7px; color: #747f86; font-size: 12px; }.sso-actions { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }.sso-actions button { min-height: 43px; padding: 0 10px; display: grid; grid-template-columns: 22px 1fr auto; gap: 7px; align-items: center; border: 1px solid #30383e; border-radius: 6px; background: #121619; color: #b7c1bb; font: inherit; text-align: left; cursor: pointer; }.sso-actions button:hover { border-color: #435049; background: #151b18; }.sso-actions button .el-icon { color: #70b9e2; }.sso-actions button > b { color: #bdc7c1; font: 12px ui-monospace, monospace; }.sso-actions button span { font-size: 12px; }.sso-actions button small { color: #5f6970; font-size: 12px; }.auth-divider { min-height: 48px; display: grid; grid-template-columns: 1fr auto 1fr; gap: 9px; align-items: center; color: #576168; font-size: 12px; }.auth-divider i { height: 1px; background: #293137; }.auth-form { display: flex; flex-direction: column; gap: 13px; }.auth-form label > span, .label-row > span { display: block; margin-bottom: 6px; color: #879198; font-size: 12px; font-weight: 700; }.label-row { display: flex; align-items: center; justify-content: space-between; }.label-row small { margin-bottom: 6px; color: #59646b; font-size: 12px; }.auth-form label > div:not(.label-row) { min-height: 43px; padding: 0 11px; display: flex; align-items: center; gap: 8px; border: 1px solid #30383e; border-radius: 6px; background: #090d0f; }.auth-form label > div:focus-within { border-color: #4b805f; box-shadow: 0 0 0 3px rgba(81,221,151,.06); }.auth-form label .el-icon { color: #69747b; }.auth-form input, .auth-form select { min-width: 0; flex: 1; border: 0; outline: 0; background: transparent; color: #dbe3de; font: inherit; font-size: 12px; }.auth-form select { width: 100%; }.auth-form label button { width: 28px; height: 28px; display: grid; place-items: center; border: 0; background: transparent; color: #667178; cursor: pointer; }.field-row { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }.password-strength { display: flex; align-items: center; justify-content: space-between; }.password-strength > span { width: 80%; display: grid; grid-template-columns: repeat(4,1fr); gap: 4px; }.password-strength i { height: 3px; border-radius: 2px; background: #283035; }.password-strength i.active { background: #59dc9b; }.password-strength b { color: #69747b; font-size: 12px; }.form-error { padding: 9px 10px; border: 1px solid #55302c; border-radius: 5px; background: #241413; color: #e1877e; font-size: 12px; line-height: 1.5; }.auth-submit { min-height: 45px; padding: 0 14px; display: flex; align-items: center; justify-content: space-between; border: 1px solid #55df9c; border-radius: 6px; background: #53de99; color: #06120a; font: inherit; font-size: 12px; font-weight: 800; cursor: pointer; box-shadow: 0 0 24px rgba(75,222,148,.12); }.auth-submit:disabled { opacity: .5; }.demo-account { min-height: 38px; display: flex; align-items: center; justify-content: space-between; border-top: 1px solid #293137; }.demo-account span { color: #626d74; font-size: 12px; }.demo-account button { padding: 0; border: 0; background: transparent; color: #67daa0; font: inherit; font-size: 12px; cursor: pointer; }.auth-security { margin-top: 21px; padding-top: 16px; display: flex; align-items: center; gap: 9px; border-top: 1px solid #293137; color: #61d99d; }.auth-security p { display: flex; flex-direction: column; gap: 4px; }.auth-security strong { color: #b9c3bd; font-size: 12px; }.auth-security small { color: #5f6970; font-size: 12px; }
@keyframes caretBlink { 0%,100% { opacity: 1; } 50% { opacity: 0; } }
@media (max-width: 980px) { .auth-page { grid-template-columns: 1fr; overflow: auto; }.auth-visual { display: none; }.auth-panel { padding: 28px 20px 50px; }.mobile-brand { min-height: 50px; margin-bottom: 22px; display: flex; align-items: center; gap: 8px; }.mobile-brand img { width: 30px; }.mobile-brand strong { font-size: 13px; }.mobile-brand button { width: 32px; height: 32px; margin-left: auto; display: grid; place-items: center; border: 1px solid #30383e; border-radius: 6px; background: #121619; color: #7d878e; } }
@media (max-width: 520px) { .auth-panel { align-items: flex-start; }.field-row, .sso-actions { grid-template-columns: 1fr; }.entry-heading h2 { font-size: 24px; } }
.auth-personalization { margin-left: auto; }
@media (max-width: 980px) { .mobile-brand .personalization-root { margin-left: auto; }.mobile-brand > button { margin-left: 0; } }
</style>
