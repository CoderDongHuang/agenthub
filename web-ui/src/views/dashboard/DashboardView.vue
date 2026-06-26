<script setup lang="ts">
import { ref } from 'vue'

const stats = ref([
  { label: '活跃 Agent', value: 0, icon: 'Robot', color: '#409EFF' },
  { label: '今日调用', value: 0, icon: 'TrendCharts', color: '#67C23A' },
  { label: '活跃用户', value: 0, icon: 'User', color: '#E6A23C' },
  { label: '待审批', value: 0, icon: 'Check', color: '#F56C6C' },
])
</script>

<template>
  <div class="dashboard">
    <!-- 统计卡片 -->
    <el-row :gutter="20">
      <el-col :span="6" v-for="item in stats" :key="item.label">
        <el-card shadow="hover">
          <div class="stat-card">
            <el-icon :size="32" :color="item.color">
              <component :is="item.icon" />
            </el-icon>
            <div class="stat-info">
              <div class="stat-value">{{ item.value }}</div>
              <div class="stat-label">{{ item.label }}</div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 欢迎信息 -->
    <el-card style="margin-top: 20px">
      <template #header>
        <span>👋 欢迎使用 AI Agent Hub</span>
      </template>
      <div class="welcome-content">
        <p>这是 Phase 0 开发阶段的骨架页面。后续会加入：</p>
        <ul>
          <li>📊 Agent 调用统计图表</li>
          <li>💰 Token 消费趋势</li>
          <li>🔔 实时审批通知</li>
          <li>📋 最近操作审计日志</li>
        </ul>
        <p style="margin-top: 16px; color: #909399">
          当前状态：三端通信骨架已搭建，gRPC 验证通过后进入 Phase 1 开发
        </p>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.stat-card {
  display: flex;
  align-items: center;
  gap: 16px;
}

.stat-info {
  flex: 1;
}

.stat-value {
  font-size: 28px;
  font-weight: bold;
  color: #303133;
}

.stat-label {
  font-size: 14px;
  color: #909399;
  margin-top: 4px;
}

.welcome-content p {
  margin: 8px 0;
  color: #606266;
}

.welcome-content ul {
  margin: 12px 0;
  padding-left: 24px;
  color: #606266;
}

.welcome-content li {
  margin: 6px 0;
}
</style>
