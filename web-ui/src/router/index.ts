import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    ...['dashboard', 'agents', 'users', 'approvals', 'audit', 'tools', 'knowledge', 'workflows', 'guardrails', 'analytics', 'channels']
      .map(path => ({ path: `/${path}`, redirect: `/console/${path}` })),
    // 首页（公开）
    {
      path: '/',
      name: 'Home',
      component: () => import('../views/LandingView.vue'),
      meta: { title: 'AI Agent Hub', public: true },
    },
    // 文档（公开）
    {
      path: '/docs',
      name: 'Docs',
      component: () => import('../views/DocsView.vue'),
      meta: { title: '文档', public: true },
    },
    // 场景（公开）
    {
      path: '/scenarios',
      name: 'Scenarios',
      component: () => import('../views/ScenariosView.vue'),
      meta: { title: '适用场景', public: true },
    },
    // 模型（公开）
    {
      path: '/models',
      name: 'Models',
      component: () => import('../views/ModelsView.vue'),
      meta: { title: '已接入模型', public: true },
    },
    // 特色（公开）
    {
      path: '/features',
      name: 'Features',
      component: () => import('../views/FeaturesView.vue'),
      meta: { title: '核心特色', public: true },
    },
    // 关于（公开）
    {
      path: '/about',
      name: 'About',
      component: () => import('../views/AboutView.vue'),
      meta: { title: '关于', public: true },
    },
    // 登录（公开）
    {
      path: '/login',
      name: 'Login',
      component: () => import('../views/LoginView.vue'),
      meta: { title: '登录', public: true },
    },
    // 控制台（需登录）
    {
      path: '/console',
      component: () => import('../layout/MainLayout.vue'),
      redirect: '/console/dashboard',
      children: [
        {
          path: 'dashboard',
          name: 'Dashboard',
          component: () => import('../views/dashboard/DashboardView.vue'),
          meta: { title: '工作台' },
        },
        {
          path: 'agents',
          name: 'Agents',
          component: () => import('../views/agents/AgentListView.vue'),
          meta: { title: 'Agent 管理' },
        },
        {
          path: 'agents/:id/chat',
          name: 'AgentChat',
          component: () => import('../views/agents/AgentChatView.vue'),
          meta: { title: 'Agent 对话' },
        },
        {
          path: 'agents/:id',
          name: 'AgentDetail',
          component: () => import('../views/agents/AgentDetailView.vue'),
          meta: { title: 'Agent 详情' },
        },
        {
          path: 'users',
          name: 'Users',
          component: () => import('../views/users/UserListView.vue'),
          meta: { title: '用户管理' },
        },
        {
          path: 'approvals',
          name: 'Approvals',
          component: () => import('../views/approvals/ApprovalListView.vue'),
          meta: { title: '审批中心' },
        },
        {
          path: 'audit',
          name: 'Audit',
          component: () => import('../views/audit/AuditLogView.vue'),
          meta: { title: '审计日志' },
        },
        {
          path: 'tools',
          name: 'Tools',
          component: () => import('../views/tools/ToolMarketView.vue'),
          meta: { title: '工具市场' },
        },
        {
          path: 'knowledge',
          name: 'Knowledge',
          component: () => import('../views/KnowledgeView.vue'),
          meta: { title: '知识库' },
        },
        {
          path: 'workflows',
          name: 'Workflows',
          component: () => import('../views/workflows/WorkflowStudioView.vue'),
          meta: { title: '流程编排' },
        },
        {
          path: 'guardrails',
          name: 'Guardrails',
          component: () => import('../views/governance/GuardrailCenterView.vue'),
          meta: { title: '安全护栏' },
        },
        {
          path: 'analytics',
          name: 'Analytics',
          component: () => import('../views/analytics/UsageAnalyticsView.vue'),
          meta: { title: '用量分析' },
        },
        {
          path: 'channels',
          name: 'Channels',
          component: () => import('../views/channels/ChannelCenterView.vue'),
          meta: { title: '渠道接入' },
        },
      ],
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'NotFound',
      component: () => import('../views/NotFoundView.vue'),
      meta: { title: '页面不存在', public: true },
    },
  ],
})

router.beforeEach(async (to) => {
  const authStore = useAuthStore()
  if (to.meta.public) return true
  if (!authStore.isLoggedIn) await authStore.restoreSession()
  if (!authStore.isLoggedIn) return '/login'
  return true
})

export default router
