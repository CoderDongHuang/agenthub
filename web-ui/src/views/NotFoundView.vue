<script setup lang="ts">
import { ArrowLeft, House } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import PersonalizationPanel from '../components/PersonalizationPanel.vue'

const router = useRouter()
</script>

<template>
  <main class="not-found">
    <header><img src="/bg.svg" alt="AgentHub" /><strong>AgentHub</strong><PersonalizationPanel compact /></header>
    <section>
      <span>404</span>
      <h1>这条路径还没有<br>被编排进来。</h1>
      <p>页面可能已经移动，或者链接仍在开发中。</p>
      <div>
        <button @click="router.back()"><el-icon><ArrowLeft /></el-icon> 返回上一页</button>
        <button @click="router.push('/')"><el-icon><House /></el-icon> 回到首页</button>
      </div>
    </section>
    <aside><span>你可以继续</span><router-link to="/scenarios">浏览场景</router-link><router-link to="/docs">阅读文档</router-link><router-link to="/login">打开工作台</router-link></aside>
  </main>
</template>

<style scoped>
.not-found { min-height: 100vh; padding: 38px max(24px, calc((100vw - 1180px) / 2)); display: grid; grid-template-columns: 1.3fr .7fr; grid-template-rows: auto 1fr; gap: 50px; background: var(--site-bg); color: var(--site-ink); font-family: Inter, "PingFang SC", system-ui, sans-serif; }
.not-found header { grid-column: 1 / -1; display: flex; align-items: center; gap: 9px; }
.not-found header img { width: 34px; height: 34px; }
.not-found header .personalization-root { margin-left: auto; }
.not-found section { align-self: center; }
.not-found section > span { color: var(--site-primary); font-size: 12px; font-weight: 800; }
.not-found h1 { margin-top: 18px; font-size: 59px; line-height: 1.1; }
.not-found p { margin-top: 18px; color: var(--site-muted); font-size: 13px; }
.not-found section > div { margin-top: 30px; display: flex; gap: 9px; }
.not-found button { min-height: 44px; padding: 0 14px; display: flex; align-items: center; gap: 7px; border: 1px solid var(--site-line-strong); border-radius: 7px; background: var(--site-surface); color: var(--site-ink); font: inherit; font-size: 10px; font-weight: 700; cursor: pointer; }
.not-found button:last-child { border-color: var(--site-primary); background: var(--site-primary); color: white; }
.not-found aside { align-self: center; padding: 24px; display: flex; flex-direction: column; border: 1px solid var(--site-line); border-radius: 8px; background: var(--site-surface); }
.not-found aside > span { margin-bottom: 10px; color: var(--site-muted); font-size: 9px; }
.not-found aside a { padding: 16px 0; border-bottom: 1px solid var(--site-line); color: var(--site-ink); text-decoration: none; font-size: 12px; font-weight: 700; }
.not-found aside a:last-child { border-bottom: 0; }
@media(max-width:700px){.not-found{grid-template-columns:1fr}.not-found h1{font-size:41px}.not-found aside{align-self:start}}
</style>
