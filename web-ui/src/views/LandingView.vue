<script setup lang="ts">
import { computed, ref } from 'vue'
import { ArrowRight, Check, CircleCheck, Connection, Lock, Promotion, TrendCharts } from '@element-plus/icons-vue'
import SiteHeader from '../components/SiteHeader.vue'
import SiteFooter from '../components/SiteFooter.vue'

const modes = [
  { id: 'service', label: '客户服务', model: 'Qwen Plus', route: '知识检索 → 回复', cost: '¥0.018', time: '0.9s' },
  { id: 'review', label: '合同审阅', model: 'Claude Sonnet', route: '条款识别 → 风险复核', cost: '¥0.086', time: '3.1s' },
  { id: 'claim', label: '理赔协同', model: 'DeepSeek V3', route: '材料核验 → 人工审批', cost: '¥0.042', time: '1.8s' },
]
const activeMode = ref('claim')
const currentMode = computed(() => modes.find(item => item.id === activeMode.value) || modes[0])

const expansion = [
  { icon: Connection, title: '多 Agent 编排', desc: '把多个角色、工具和人工节点组成完整流程。', path: '/scenarios' },
  { icon: Lock, title: '安全护栏', desc: '隐私脱敏、越狱检测和质量检查进入运行链路。', path: '/features' },
  { icon: TrendCharts, title: '用量分析', desc: '按模型和 Agent 追踪成本，主动发现异常消耗。', path: '/models' },
]
</script>

<template>
  <div class="public-page home-page">
    <SiteHeader />
    <main>
      <section class="home-hero">
        <div class="hero-copy">
          <span class="site-kicker">企业智能工作平台</span>
          <h1>让 Agent 真正<br>进入日常工作。</h1>
          <p>在同一个空间里创建 Agent、连接知识与工具、安排人工审批，并清楚掌握每一次执行和成本。</p>
          <div class="hero-actions"><router-link to="/login">打开工作台 <el-icon><ArrowRight /></el-icon></router-link><router-link to="/scenarios">看看如何工作</router-link></div>
          <div class="hero-proof"><span><el-icon><Check /></el-icon> 三端真实运行</span><span><el-icon><Check /></el-icon> 关键动作可审批</span><span><el-icon><Check /></el-icon> 全过程可追溯</span></div>
        </div>
        <div class="hero-caption"><span>真实产品界面</span><strong>今日工作台</strong><small>Agent、审批、工具与成本一处查看</small></div>
      </section>

      <section class="trust-strip"><span>适用于需要可靠协作的团队</span><b>金融保险</b><b>客户服务</b><b>人力资源</b><b>IT 运维</b><b>经营分析</b></section>

      <section class="routing-section">
        <div class="routing-copy"><span class="site-kicker">按任务选择，而不是押注单一模型</span><h2>同一个入口，走不同的可靠路径。</h2><p>平台根据任务类型、敏感程度、响应速度和预算，组合模型、知识、工具与审批方式。</p><div class="mode-tabs"><button v-for="mode in modes" :key="mode.id" :class="{ active: activeMode === mode.id }" @click="activeMode = mode.id"><span>{{ mode.label }}</span><small>{{ mode.model }}</small></button></div></div>
        <div class="route-board">
          <div class="route-head"><span>当前执行方案</span><b><i /> 已就绪</b></div>
          <div class="route-request"><span>01</span><p><strong>业务请求</strong><small>{{ currentMode.label }}任务进入工作流</small></p></div>
          <div class="route-line"><span>身份验证</span><i /><span>安全策略</span><i /><span>预算检查</span></div>
          <div class="route-main"><div><span>主模型</span><strong>{{ currentMode.model }}</strong><small>{{ currentMode.route }}</small></div><div class="route-metrics"><p><span>预计成本</span><strong>{{ currentMode.cost }}</strong></p><p><span>预计耗时</span><strong>{{ currentMode.time }}</strong></p></div></div>
          <div class="route-result"><el-icon><CircleCheck /></el-icon><p><strong>结果返回并留痕</strong><small>输入、工具调用、审批和答案统一记录</small></p></div>
        </div>
      </section>

      <section class="workflow-story">
        <div class="story-heading"><span class="site-kicker">不只是聊天</span><h2>把一项真实业务，交给一支数字团队。</h2><p>每个节点只做自己擅长的事，复杂任务因此更清楚，也更容易被人接管。</p></div>
        <div class="story-steps"><article><span>1</span><div><small>入口 Agent</small><h3>理解请求</h3><p>识别意图、补全上下文，决定任务该交给谁。</p></div></article><article><span>2</span><div><small>专业 Agent</small><h3>完成分析</h3><p>检索知识、比对材料，并调用经过授权的工具。</p></div></article><article><span>3</span><div><small>人工节点</small><h3>确认关键动作</h3><p>高风险操作暂停执行，负责人看到完整上下文再决定。</p></div></article><article><span>4</span><div><small>交付 Agent</small><h3>解释并归档</h3><p>生成清晰结果，向用户返回并写入审计记录。</p></div></article></div>
        <router-link class="story-link" to="/login">在编排画布中试一试 <el-icon><ArrowRight /></el-icon></router-link>
      </section>

      <section class="tool-section">
        <div class="tool-code"><div class="code-top"><span>refund_tool.py</span><b>Python</b></div><pre><code><span class="code-muted"># 能力和边界写在一起</span>
<span class="code-blue">@tool</span>(
  name=<span class="code-green">"refund.execute"</span>,
  risk=<span class="code-coral">"high"</span>,
  approvers=<span class="code-amber">2</span>
)
<span class="code-blue">async def</span> execute(order_id: str):
  <span class="code-blue">return await</span> refund(order_id)</code></pre><div class="code-status"><span><i /> 已注册到工具目录</span><strong>双人审批</strong></div></div>
        <div class="tool-copy"><span class="site-kicker">开发一次，组织复用</span><h2>一段业务代码，变成可管理的团队能力。</h2><p>开发者专注函数实现；参数、权限、限流、审批和审计由平台统一处理。业务人员不需要理解底层代码，也能安全地把工具装进 Agent。</p><ul><li><el-icon><Check /></el-icon> 自动发现参数定义</li><li><el-icon><Check /></el-icon> 按角色授权使用</li><li><el-icon><Check /></el-icon> 高风险操作自动进入审批</li></ul><router-link to="/docs">阅读工具接入指南 <el-icon><ArrowRight /></el-icon></router-link></div>
      </section>

      <section class="expansion-section"><div class="site-section-head"><div><span class="site-kicker">平台正在生长</span><h2>从创建 Agent，走向完整的企业协作系统。</h2></div><p>扩展方向来自项目文档，并优先实现能形成业务闭环的能力。</p></div><div class="expansion-list"><router-link v-for="item in expansion" :key="item.title" :to="item.path"><el-icon><component :is="item.icon" /></el-icon><span><strong>{{ item.title }}</strong><small>{{ item.desc }}</small></span><el-icon><ArrowRight /></el-icon></router-link></div></section>

      <section class="home-final"><div><span>从一个流程开始</span><h2>今天就让第一项工作跑起来。</h2><p>内置演示账号和真实三端服务，可以直接创建、发布并测试 Agent。</p></div><router-link to="/login">进入 AgentHub <el-icon><Promotion /></el-icon></router-link></section>
    </main>
    <SiteFooter />
  </div>
</template>

<style scoped>
.home-hero { position: relative; min-height: min(760px, calc(100vh - 72px)); padding: 95px max(30px, calc((100vw - 1240px) / 2)); display: flex; align-items: center; overflow: hidden; background: #edf2ed; }
.home-hero::after { content: ''; position: absolute; inset: 54px -8vw 54px 45%; background: #fff url('/product-console.png') left top / cover no-repeat; border: 1px solid var(--site-line-strong); border-radius: 8px; box-shadow: var(--site-shadow); transform: rotate(-1.5deg); }
.home-hero::before { content: ''; position: absolute; z-index: 1; inset: 0 54% 0 0; background: #edf2ed; }
.hero-copy { position: relative; z-index: 2; max-width: 610px; }
.hero-copy h1 { margin-top: 22px; font-size: 66px; line-height: 1.04; font-weight: 800; letter-spacing: 0; }
.hero-copy > p { max-width: 570px; margin-top: 24px; color: var(--site-muted); font-size: 17px; line-height: 1.8; }
.hero-actions { margin-top: 34px; display: flex; gap: 10px; }
.hero-actions a { min-height: 48px; padding: 0 18px; display: inline-flex; align-items: center; justify-content: center; gap: 8px; border: 1px solid var(--site-line-strong); border-radius: 7px; background: rgba(255,255,255,.7); color: var(--site-ink); text-decoration: none; font-size: 13px; font-weight: 700; }
.hero-actions a:first-child { border-color: var(--site-primary-dark); background: var(--site-primary-dark); color: white; }
.hero-proof { margin-top: 30px; display: flex; flex-wrap: wrap; gap: 17px; color: #66736c; font-size: 11px; }
.hero-proof span { display: inline-flex; align-items: center; gap: 5px; }.hero-proof .el-icon { color: var(--site-primary); }
.hero-caption { position: absolute; z-index: 3; right: 5vw; bottom: 76px; width: 240px; padding: 15px; display: flex; flex-direction: column; border-radius: 7px; background: rgba(255,255,255,.94); box-shadow: 0 10px 30px rgba(45,62,53,.12); }.hero-caption span { color: var(--site-primary); font-size: 9px; font-weight: 700; }.hero-caption strong { margin-top: 5px; font-size: 13px; }.hero-caption small { margin-top: 5px; color: var(--site-muted); font-size: 9px; }
.trust-strip { min-height: 92px; padding: 0 max(30px, calc((100vw - 1240px) / 2)); display: grid; grid-template-columns: 1.5fr repeat(5, .7fr); align-items: center; border-top: 1px solid var(--site-line); border-bottom: 1px solid var(--site-line); background: white; }.trust-strip span { color: var(--site-muted); font-size: 11px; }.trust-strip b { color: #718078; font-size: 12px; text-align: center; }
.routing-section { padding: 100px max(30px, calc((100vw - 1240px) / 2)); display: grid; grid-template-columns: .78fr 1.22fr; gap: 90px; align-items: center; background: var(--site-paper); }.routing-copy h2 { margin-top: 20px; font-size: 43px; line-height: 1.16; }.routing-copy > p { margin-top: 18px; color: var(--site-muted); font-size: 14px; line-height: 1.8; }.mode-tabs { margin-top: 28px; display: flex; flex-direction: column; border-top: 1px solid var(--site-line); }.mode-tabs button { min-height: 65px; padding: 0 12px; display: flex; align-items: center; justify-content: space-between; border: 0; border-bottom: 1px solid var(--site-line); background: transparent; color: var(--site-muted); font: inherit; cursor: pointer; }.mode-tabs button.active { padding-left: 18px; border-radius: 6px; background: var(--site-primary-soft); color: var(--site-primary-dark); }.mode-tabs span { font-size: 13px; font-weight: 700; }.mode-tabs small { font-size: 10px; }
.route-board { padding: 22px; border: 1px solid var(--site-line-strong); border-radius: 8px; background: white; box-shadow: var(--site-shadow); }.route-head { display: flex; align-items: center; justify-content: space-between; color: var(--site-muted); font-size: 10px; }.route-head b { display: flex; align-items: center; gap: 6px; color: var(--site-primary); font-size: 9px; }.route-head i { width: 7px; height: 7px; border-radius: 50%; background: var(--site-primary); }.route-request, .route-result { min-height: 74px; margin-top: 20px; padding: 14px; display: flex; align-items: center; gap: 13px; border-radius: 7px; background: #f5f7f4; }.route-request > span { width: 32px; height: 32px; display: grid; place-items: center; border-radius: 7px; background: var(--site-blue-soft); color: var(--site-blue); font-size: 10px; font-weight: 800; }.route-request p, .route-result p { display: flex; flex-direction: column; gap: 5px; }.route-request strong, .route-result strong { font-size: 12px; }.route-request small, .route-result small { color: var(--site-muted); font-size: 9px; }.route-line { min-height: 62px; display: flex; align-items: center; justify-content: center; gap: 10px; color: #87928c; font-size: 9px; }.route-line i { width: 28px; height: 1px; background: var(--site-line-strong); }.route-main { min-height: 150px; padding: 22px; display: grid; grid-template-columns: 1fr 1fr; align-items: center; border: 1px solid #cbd9cf; border-radius: 8px; background: var(--site-primary-soft); }.route-main > div:first-child { display: flex; flex-direction: column; }.route-main span { color: var(--site-muted); font-size: 9px; }.route-main > div > strong { margin-top: 10px; font-size: 20px; }.route-main small { margin-top: 8px; color: var(--site-primary-dark); font-size: 10px; }.route-metrics { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }.route-metrics p { min-height: 78px; padding: 13px; display: flex; flex-direction: column; border-radius: 7px; background: white; }.route-metrics strong { margin-top: auto; font-size: 15px; }.route-result { margin-top: 16px; background: var(--site-blue-soft); }.route-result > .el-icon { width: 32px; height: 32px; color: var(--site-blue); font-size: 22px; }
.workflow-story { padding: 104px max(30px, calc((100vw - 1240px) / 2)); background: #e8eef2; }.story-heading { max-width: 720px; }.story-heading h2 { margin-top: 18px; font-size: 45px; line-height: 1.15; }.story-heading p { margin-top: 17px; color: #647580; font-size: 14px; line-height: 1.75; }.story-steps { margin-top: 52px; display: grid; grid-template-columns: repeat(4, 1fr); border-top: 1px solid #c5d3dc; }.story-steps article { min-height: 260px; padding: 24px; display: grid; grid-template-columns: 34px 1fr; align-content: start; border-right: 1px solid #c5d3dc; }.story-steps article:first-child { border-left: 1px solid #c5d3dc; }.story-steps article > span { width: 28px; height: 28px; display: grid; place-items: center; border-radius: 50%; background: white; color: var(--site-blue); font-size: 10px; font-weight: 800; }.story-steps small { color: #668096; font-size: 9px; font-weight: 700; }.story-steps h3 { margin-top: 54px; font-size: 18px; }.story-steps p { margin-top: 10px; color: #687b87; font-size: 11px; line-height: 1.7; }.story-link { margin-top: 28px; display: inline-flex; align-items: center; gap: 8px; color: #4e6d82; text-decoration: none; font-size: 12px; font-weight: 700; }
.tool-section { padding: 108px max(30px, calc((100vw - 1240px) / 2)); display: grid; grid-template-columns: 1.08fr .92fr; gap: 90px; align-items: center; background: white; }.tool-code { border: 1px solid #d8ded9; border-radius: 8px; background: #f4f6f3; box-shadow: 16px 16px 0 #e2e9e3; overflow: hidden; }.code-top, .code-status { min-height: 44px; padding: 0 16px; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid #d8ded9; color: #7f8a84; font-size: 9px; }.code-top b { color: var(--site-blue); }.tool-code pre { min-height: 330px; margin: 0; padding: 30px; overflow: auto; color: #4d5a53; font: 12px/1.9 ui-monospace, SFMono-Regular, Menlo, monospace; }.code-muted { color: #9ba49f; }.code-blue { color: #517791; }.code-green { color: #4e8066; }.code-coral { color: #b86250; }.code-amber { color: #a7782f; }.code-status { border-top: 1px solid #d8ded9; border-bottom: 0; background: white; }.code-status span { display: flex; align-items: center; gap: 6px; color: var(--site-primary); }.code-status i { width: 7px; height: 7px; border-radius: 50%; background: var(--site-primary); }.code-status strong { font-size: 9px; }.tool-copy h2 { margin-top: 20px; font-size: 41px; line-height: 1.16; }.tool-copy > p { margin-top: 18px; color: var(--site-muted); font-size: 14px; line-height: 1.8; }.tool-copy ul { margin-top: 24px; list-style: none; }.tool-copy li { margin-top: 11px; display: flex; align-items: center; gap: 8px; color: var(--site-ink-soft); font-size: 12px; }.tool-copy li .el-icon { color: var(--site-primary); }.tool-copy > a { margin-top: 30px; display: inline-flex; align-items: center; gap: 8px; color: var(--site-primary-dark); text-decoration: none; font-size: 12px; font-weight: 800; }
.expansion-section { padding: 96px max(30px, calc((100vw - 1240px) / 2)); background: #f4f0e8; }.expansion-list { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; }.expansion-list a { min-height: 190px; padding: 24px; display: grid; grid-template-columns: 38px 1fr 24px; gap: 12px; align-items: start; border: 1px solid #ddd8ce; border-radius: 8px; background: rgba(255,255,255,.65); color: var(--site-ink); text-decoration: none; }.expansion-list a:hover { background: white; transform: translateY(-3px); }.expansion-list a > .el-icon:first-child { width: 38px; height: 38px; display: grid; place-items: center; border-radius: 8px; background: var(--site-primary-soft); color: var(--site-primary-dark); font-size: 19px; }.expansion-list a > span { display: flex; flex-direction: column; }.expansion-list strong { margin-top: 7px; font-size: 16px; }.expansion-list small { margin-top: 45px; color: var(--site-muted); font-size: 11px; line-height: 1.6; }.expansion-list a > .el-icon:last-child { margin-top: 8px; color: #919b95; }
.home-final { min-height: 340px; padding: 70px max(30px, calc((100vw - 1240px) / 2)); display: flex; align-items: center; justify-content: space-between; gap: 50px; background: var(--site-primary-dark); color: white; }.home-final span { color: #bcd0c3; font-size: 11px; font-weight: 700; }.home-final h2 { margin-top: 14px; font-size: 44px; }.home-final p { margin-top: 14px; color: #cedbd3; font-size: 13px; }.home-final > a { min-height: 52px; padding: 0 20px; display: flex; align-items: center; gap: 9px; border-radius: 7px; background: white; color: var(--site-primary-dark); text-decoration: none; font-size: 12px; font-weight: 800; }
@media (max-width: 1050px) { .home-hero::after { left: 50%; opacity: .55; }.home-hero::before { right: 45%; }.routing-section, .tool-section { grid-template-columns: 1fr; }.story-steps { grid-template-columns: 1fr 1fr; }.expansion-list { grid-template-columns: 1fr; }.expansion-list a { min-height: 130px; }.expansion-list small { margin-top: 18px; } }
@media (max-width: 720px) { .home-hero { min-height: 730px; padding: 62px 16px 260px; align-items: flex-start; }.home-hero::before { inset: 0; background: rgba(237,242,237,.93); }.home-hero::after { inset: auto 16px 24px 16px; height: 230px; opacity: 1; transform: none; }.hero-copy h1 { font-size: 45px; }.hero-copy > p { font-size: 14px; }.hero-actions { flex-direction: column; }.hero-caption { display: none; }.trust-strip { padding: 20px 16px; grid-template-columns: 1fr 1fr; gap: 18px; }.trust-strip span { grid-column: 1 / -1; }.routing-section, .workflow-story, .tool-section, .expansion-section { padding: 72px 16px; }.routing-copy h2, .story-heading h2, .tool-copy h2 { font-size: 34px; }.route-main { grid-template-columns: 1fr; gap: 18px; }.story-steps { grid-template-columns: 1fr; }.story-steps article { min-height: 180px; border-left: 1px solid #c5d3dc; border-bottom: 1px solid #c5d3dc; }.tool-code pre { padding: 18px; font-size: 9px; }.home-final { padding: 65px 16px; align-items: flex-start; flex-direction: column; }.home-final h2 { font-size: 35px; } }
</style>
