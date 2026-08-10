<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowRight, Close, Menu } from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()
const mobileOpen = ref(false)

const links = [
  { label: '首页', path: '/' },
  { label: '场景', path: '/scenarios' },
  { label: '模型', path: '/models' },
  { label: '特色', path: '/features' },
  { label: '文档', path: '/docs' },
  { label: '关于', path: '/about' },
]

const currentPath = computed(() => route.path)

function navigate(path: string) {
  mobileOpen.value = false
  router.push(path)
}
</script>

<template>
  <header class="site-header">
    <div class="site-header__inner">
      <button class="site-brand" aria-label="返回首页" @click="navigate('/')">
        <img class="site-brand__logo" src="/bg.svg" alt="AgentHub" />
        <span class="site-brand__name">AgentHub</span>
        <span class="site-brand__edition">企业智能工作平台</span>
      </button>

      <nav class="site-nav" aria-label="主导航">
        <router-link
          v-for="link in links"
          :key="link.path"
          :to="link.path"
          :class="{ active: currentPath === link.path }"
        >
          {{ link.label }}
        </router-link>
      </nav>

      <div class="site-header__actions">
        <button class="site-console-link" @click="navigate('/login')">
          打开工作台
          <el-icon><ArrowRight /></el-icon>
        </button>
        <button
          class="site-menu-button"
          :aria-label="mobileOpen ? '关闭菜单' : '打开菜单'"
          @click="mobileOpen = !mobileOpen"
        >
          <el-icon><component :is="mobileOpen ? Close : Menu" /></el-icon>
        </button>
      </div>
    </div>

    <div v-if="mobileOpen" class="site-mobile-menu">
      <button
        v-for="link in links"
        :key="link.path"
        :class="{ active: currentPath === link.path }"
        @click="navigate(link.path)"
      >
        <span>{{ link.label }}</span><span>0{{ links.indexOf(link) + 1 }}</span>
      </button>
      <button class="mobile-console" @click="navigate('/login')">打开工作台</button>
    </div>
  </header>
</template>
