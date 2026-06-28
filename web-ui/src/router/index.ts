import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'Login',
      component: () => import('../views/LoginView.vue'),
      meta: { title: '登录' },
    },
    {
      path: '/',
      component: () => import('../layout/MainLayout.vue'),
      redirect: '/dashboard',
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
      ],
    },
  ],
})

export default router
