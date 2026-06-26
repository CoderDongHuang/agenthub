<script setup lang="ts">
// Phase 1 实现: Agent 列表、创建、编辑、发布
</script>

<template>
  <el-card>
    <template #header>
      <div style="display: flex; justify-content: space-between; align-items: center">
        <span>Agent 管理</span>
        <el-button type="primary" :icon="'Plus'">新建 Agent</el-button>
      </div>
    </template>
    <el-empty description="Phase 1 实现 Agent 列表" />
  </el-card>
</template>
