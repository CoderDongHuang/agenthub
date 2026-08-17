<script setup lang="ts">
import { computed, watchEffect } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import en from 'element-plus/es/locale/lang/en'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import UiLanguageBridge from './components/UiLanguageBridge.vue'

const { locale } = useI18n()
const route = useRoute()
const elementLocale = computed(() => locale.value === 'en-US' ? en : zhCn)
const englishTitles: Record<string, string> = {
  Home: 'Enterprise AI Agent Platform', Scenarios: 'Use Cases', Models: 'Model Ecosystem', Features: 'Core Features',
  Docs: 'Documentation', About: 'About', Login: 'Sign In', Dashboard: 'Dashboard', Agents: 'Agent Management',
  AgentChat: 'Agent Chat', AgentDetail: 'Agent Details', Users: 'User Management', Approvals: 'Approval Center',
  Audit: 'Audit Log', Tools: 'Tool Market', Knowledge: 'Knowledge Base', Workflows: 'Workflow Studio',
  Guardrails: 'Guardrails', Analytics: 'Usage Analytics', Channels: 'Channel Integrations', NotFound: 'Page Not Found',
  Operations: 'Release & Runtime Center',
  EnterpriseGovernance: 'Enterprise Governance',
}

watchEffect(() => {
  const page = locale.value === 'en-US' ? englishTitles[String(route.name)] : String(route.meta.title || '')
  document.title = page && page !== 'AI Agent Hub' ? `${page} | AgentHub` : 'AgentHub | Enterprise AI Agent Platform'
})
</script>

<template>
  <el-config-provider :locale="elementLocale">
    <router-view />
    <UiLanguageBridge />
  </el-config-provider>
</template>
