import { createRouter, createWebHistory } from 'vue-router'
import { message } from 'ant-design-vue'
import { useUserStore } from '@/stores/user'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/Login.vue'),
      meta: { public: true }
    },
    {
      path: '/',
      component: () => import('@/layouts/MainLayout.vue'),
      redirect: '/tasks',
      children: [
        {
          path: 'tasks',
          name: 'tasks',
          component: () => import('@/views/TaskList.vue'),
          meta: { title: '我的任务', menu: 'tasks' }
        },
        {
          path: 'tasks/:id',
          name: 'task-workspace',
          component: () => import('@/views/TaskWorkspace.vue'),
          meta: { title: '任务工作台', menu: 'tasks' }
        },
        {
          path: 'tasks/:id/result',
          name: 'task-result',
          component: () => import('@/views/TaskResult.vue'),
          meta: { title: '任务结果', menu: 'tasks' }
        },
        {
          path: 'system/users',
          name: 'user-manage',
          component: () => import('@/views/UserManage.vue'),
          meta: { title: '用户管理', menu: 'user-manage', adminOnly: true }
        },
        {
          path: 'system/glossary',
          name: 'glossary-manage',
          component: () => import('@/views/GlossaryManage.vue'),
          meta: { title: '语料管理', menu: 'glossary-manage' }
        },
        {
          path: 'system/llm-configs',
          name: 'llm-config',
          component: () => import('@/views/LlmConfigManage.vue'),
          meta: { title: '模型配置', menu: 'llm-config', adminOnly: true }
        },
        {
          path: 'system/operation-logs',
          name: 'operation-log',
          component: () => import('@/views/OperationLog.vue'),
          meta: { title: '操作日志', menu: 'operation-log', adminOnly: true }
        },
        {
          path: 'system/runtime-logs',
          name: 'runtime-log',
          component: () => import('@/views/RuntimeLog.vue'),
          meta: { title: '运行日志', menu: 'runtime-log', adminOnly: true }
        }
      ]
    }
  ]
})

router.beforeEach((to) => {
  const userStore = useUserStore()
  if (!to.meta.public && !userStore.isLoggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.path === '/login' && userStore.isLoggedIn) {
    return { path: '/tasks' }
  }
  if (to.meta.adminOnly && (userStore.role || '').toUpperCase() !== 'ADMIN') {
    message.warning('无权限访问该功能')
    return { path: '/tasks' }
  }
  return true
})

export default router
