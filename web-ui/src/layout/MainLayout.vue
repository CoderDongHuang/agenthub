<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()

const isCollapse = ref(false)

const menuItems = [
  { path: '/dashboard', title: '工作台', icon: 'HomeFilled' },
  { path: '/agents', title: 'Agent 管理', icon: 'Robot' },
  { path: '/tools', title: '工具市场', icon: 'Shop' },
  { path: '/users', title: '用户管理', icon: 'User' },
  { path: '/approvals', title: '审批中心', icon: 'Check' },
  { path: '/audit', title: '审计日志', icon: 'Document' },
]

const currentTitle = computed(() => {
  return route.meta?.title || 'AI Agent Hub'
})

const displayName = computed(() => authStore.user?.displayName || '用户')

const handleLogout = () => {
  authStore.logout()
  router.push('/login')
}
</script>

<template>
  <el-container class="layout-container">
    <!-- 侧边栏 -->
    <el-aside :width="isCollapse ? '64px' : '220px'" class="layout-aside">
      <div class="logo">
        <span v-if="!isCollapse">🤖 AI Agent Hub</span>
        <span v-else>🤖</span>
      </div>
      <el-menu
        :default-active="route.path"
        :collapse="isCollapse"
        :collapse-transition="false"
        router
        background-color="#304156"
        text-color="#bfcbd9"
        active-text-color="#409EFF"
      >
        <el-menu-item v-for="item in menuItems" :key="item.path" :index="item.path">
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.title }}</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <!-- 主内容 -->
    <el-container>
      <el-header class="layout-header">
        <el-button
          text
          @click="isCollapse = !isCollapse"
          style="font-size: 20px"
        >
          <el-icon><Fold /></el-icon>
        </el-button>
        <h2>{{ currentTitle }}</h2>
        <div class="header-right">
          <span>{{ displayName }}</span>
          <el-button type="danger" link size="small" @click="handleLogout">退出</el-button>
        </div>
      </el-header>
      <el-main class="layout-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout-container {
  height: 100vh;
}

.layout-aside {
  background-color: #304156;
  transition: width 0.3s;
  overflow: hidden;
}

.logo {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 18px;
  font-weight: bold;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.layout-header {
  display: flex;
  align-items: center;
  gap: 16px;
  background: #fff;
  border-bottom: 1px solid #e6e6e6;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.05);
}

.layout-header h2 {
  flex: 1;
  font-size: 18px;
  color: #303133;
}

.header-right {
  color: #606266;
}

.layout-main {
  background: #f0f2f5;
  padding: 20px;
}
</style>
