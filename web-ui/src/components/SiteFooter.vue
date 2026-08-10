<script setup lang="ts">
import { TopRight } from '@element-plus/icons-vue'
</script>

<template>
  <footer class="site-footer">
    <div class="site-footer__top">
      <div>
        <div class="site-footer__brand"><img src="/bg.svg" alt="AgentHub" /><span>AgentHub</span></div>
        <p>把模型、知识、工具与团队协作放进同一套可靠工作流，让每一次自动化都清楚、可控、可复用。</p>
      </div>
      <div class="site-footer__links">
        <router-link to="/scenarios">场景</router-link>
        <router-link to="/features">特色</router-link>
        <router-link to="/docs">文档</router-link>
        <router-link to="/models">模型接入</router-link>
        <router-link to="/about">项目路线</router-link>
        <a href="https://github.com/CoderDongHuang/agenthub" target="_blank" rel="noreferrer">
          GitHub <el-icon><TopRight /></el-icon>
        </a>
      </div>
    </div>
    <div class="site-footer__bottom">
      <span>© 2026 AgentHub</span>
      <span>Java 管理面 · Python 执行面 · Vue 工作台</span>
      <span>MIT License</span>
    </div>
  </footer>
</template>
