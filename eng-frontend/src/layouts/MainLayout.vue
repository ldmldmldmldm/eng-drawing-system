<template>
  <a-layout class="main-layout" :class="{ 'is-content-fullscreen': contentFullscreen }">
    <a-layout-sider
      v-if="!contentFullscreen"
      v-model:collapsed="collapsed"
      :trigger="null"
      collapsible
      theme="dark"
      :width="168"
      :collapsed-width="64"
    >
      <div class="brand" :class="{ collapsed }">
        <img class="brand-logo" src="/logo.png" alt="Logo" />
        <div v-if="!collapsed" class="brand-text-wrap">
          <span class="brand-name">工程图纸</span>
          <span class="brand-title">工程图纸生成系统</span>
        </div>
      </div>
      <a-menu
        theme="dark"
        mode="inline"
        :selected-keys="[selectedKey]"
        v-model:open-keys="openKeys"
        @click="onMenuClick"
      >
        <a-sub-menu key="tasks-group">
          <template #icon><ProjectOutlined /></template>
          <template #title>
            <span data-tour="menu-tasks">任务管理</span>
          </template>
          <a-menu-item key="tasks">
            <template #icon><UnorderedListOutlined /></template>
            我的任务
          </a-menu-item>
        </a-sub-menu>
        <a-sub-menu key="system">
          <template #icon><SettingOutlined /></template>
          <template #title>系统管理</template>
          <a-menu-item key="glossary-manage">
            <template #icon><BookOutlined /></template>
            语料管理
          </a-menu-item>
          <a-menu-item v-if="isAdmin" key="llm-config">
            <template #icon><ApiOutlined /></template>
            模型配置
          </a-menu-item>
          <a-menu-item v-if="isAdmin" key="user-manage">
            <template #icon><IdcardOutlined /></template>
            用户管理
          </a-menu-item>
          <a-menu-item v-if="isAdmin" key="operation-log">
            <template #icon><FileSearchOutlined /></template>
            操作日志
          </a-menu-item>
          <a-menu-item v-if="isAdmin" key="runtime-log">
            <template #icon><CodeOutlined /></template>
            运行日志
          </a-menu-item>
        </a-sub-menu>
      </a-menu>
    </a-layout-sider>

    <a-layout>
      <a-layout-header v-if="!contentFullscreen" class="top-header">
        <div class="header-left">
          <a-button type="text" class="header-toggle" @click="collapsed = !collapsed">
            <MenuFoldOutlined v-if="!collapsed" />
            <MenuUnfoldOutlined v-else />
          </a-button>
          <div class="crumbs">
            <span>首页</span>
            <span class="sep">/</span>
            <span>{{ crumbParent }}</span>
            <span class="sep">/</span>
            <span class="current">{{ pageTitle }}</span>
          </div>
        </div>
        <div class="header-right">
          <a-dropdown>
            <a-button type="text" class="help-btn" data-tour="btn-help-tour">
              <QuestionCircleOutlined />
              <span>帮助</span>
            </a-button>
            <template #overlay>
              <a-menu>
                <a-menu-item key="tour" @click="onStartTour">
                  <template #icon><CompassOutlined /></template>
                  新手引导
                </a-menu-item>
              </a-menu>
            </template>
          </a-dropdown>
          <a-tooltip title="全屏展示数据区">
            <a-button type="text" class="fs-btn" @click="enterFullscreen">
              <FullscreenOutlined />
            </a-button>
          </a-tooltip>
          <a-dropdown>
            <a class="user-entry" @click.prevent>
              <UserOutlined />
              <span>{{ userStore.displayName || userStore.username }}</span>
            </a>
            <template #overlay>
              <a-menu>
                <a-menu-item key="pwd" @click="showPwd = true">
                  <template #icon><LockOutlined /></template>
                  修改密码
                </a-menu-item>
                <a-menu-item key="logout" @click="onLogout">
                  <template #icon><LogoutOutlined /></template>
                  退出登录
                </a-menu-item>
              </a-menu>
            </template>
          </a-dropdown>
        </div>
      </a-layout-header>

      <a-layout-content class="content">
        <router-view />
      </a-layout-content>
    </a-layout>

    <ChangePasswordModal v-model:open="showPwd" />
  </a-layout>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, provide, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ProjectOutlined,
  IdcardOutlined,
  FileSearchOutlined,
  CodeOutlined,
  SettingOutlined,
  UserOutlined,
  LockOutlined,
  LogoutOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  FullscreenOutlined,
  BookOutlined,
  ApiOutlined,
  UnorderedListOutlined,
  QuestionCircleOutlined,
  CompassOutlined
} from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import { authApi } from '@/api'
import { useUserStore } from '@/stores/user'
import ChangePasswordModal from '@/components/ChangePasswordModal.vue'
import { startFullTour } from '@/onboarding'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const collapsed = ref(false)
const openKeys = ref<string[]>([])
const showPwd = ref(false)
const contentFullscreen = ref(false)

provide('jyContentFullscreen', {
  active: contentFullscreen,
  enter: () => {
    contentFullscreen.value = true
  },
  exit: () => {
    contentFullscreen.value = false
  }
})

const isAdmin = computed(() => (userStore.role || '').toUpperCase() === 'ADMIN')

function onStartTour() {
  void startFullTour(userStore.username || '')
}

const selectedKey = computed(() => (route.meta.menu as string) || 'tasks')
const pageTitle = computed(() => (route.meta.title as string) || '任务管理')
const crumbParent = computed(() =>
  String(route.meta.menu || '').startsWith('glossary') ||
  route.meta.menu === 'llm-config' ||
  route.meta.menu === 'user-manage' ||
  route.meta.menu === 'operation-log' ||
  route.meta.menu === 'runtime-log' ||
  route.meta.menu === 'glossary-manage'
    ? '系统管理'
    : '任务管理'
)

watch(
  () => route.path,
  () => {
    const systemMenus = [
      'user-manage',
      'operation-log',
      'runtime-log',
      'glossary-manage',
      'llm-config'
    ]
    const nextOpen = [...openKeys.value]
    if (systemMenus.includes(String(route.meta.menu)) && !nextOpen.includes('system')) {
      nextOpen.push('system')
    }
    if (String(route.meta.menu) === 'tasks' && !nextOpen.includes('tasks-group')) {
      nextOpen.push('tasks-group')
    }
    openKeys.value = nextOpen
    contentFullscreen.value = false
  },
  { immediate: true }
)

function enterFullscreen() {
  contentFullscreen.value = true
}

function exitFullscreen() {
  contentFullscreen.value = false
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape' && contentFullscreen.value) {
    e.preventDefault()
    exitFullscreen()
  }
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onUnmounted(() => window.removeEventListener('keydown', onKeydown))

function onMenuClick(info: { key: string | number }) {
  const key = String(info.key)
  if (key === 'tasks') {
    router.push('/tasks')
  } else if (key === 'glossary-manage') {
    router.push('/system/glossary')
  } else if (key === 'llm-config') {
    router.push('/system/llm-configs')
  } else if (key === 'user-manage') {
    router.push('/system/users')
  } else if (key === 'operation-log') {
    router.push('/system/operation-logs')
  } else if (key === 'runtime-log') {
    router.push('/system/runtime-logs')
  }
}

async function onLogout() {
  try {
    await authApi.logout()
  } catch {
    // ignore
  }
  userStore.clearAuth()
  message.success('已退出')
  await router.replace('/login')
}
</script>

<style scoped>
.main-layout {
  height: 100vh;
  min-height: 100vh;
}

.main-layout > :deep(.ant-layout) {
  min-height: 0;
  overflow: hidden;
}

.main-layout.is-content-fullscreen > :deep(.ant-layout) {
  width: 100%;
}

.brand {
  min-height: 56px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  color: #fff;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.brand.collapsed {
  justify-content: center;
  padding: 10px 6px;
}

.brand-logo {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  object-fit: cover;
  flex-shrink: 0;
  box-shadow: 0 0 0 2px rgba(245, 166, 35, 0.35);
}

.brand-text-wrap {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
  line-height: 1.2;
}

.brand-name {
  display: block;
  font-size: 15px;
  font-weight: 800;
  letter-spacing: 0.08em;
  background: linear-gradient(135deg, #f7b733 0%, #f5a623 45%, #e85d4c 100%);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  color: #f5a623;
}

.brand-title {
  display: block;
  font-size: 10px;
  font-weight: 500;
  color: rgba(255, 255, 255, 0.72);
  letter-spacing: 0;
  line-height: 1.25;
  white-space: normal;
}

.top-header {
  background: #fff;
  padding: 0 16px 0 8px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #e8e8e8;
  height: 56px;
  line-height: 56px;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
}

.header-toggle {
  width: 36px;
  height: 36px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  color: #f5a623;
  border: 1px solid #f5a623;
  border-radius: 6px;
  margin-right: 8px;
}

.header-toggle:hover {
  color: #e67e22 !important;
  border-color: #e67e22;
  background: rgba(245, 166, 35, 0.08) !important;
}

.help-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 32px;
  padding: 0 8px;
  color: #334155;
  font-size: 13px;
}

.help-btn:hover {
  color: #1677ff !important;
  background: #e6f4ff !important;
}

.crumbs {
  color: #64748b;
  font-size: 13px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.crumbs .sep {
  margin: 0 6px;
  color: #cbd5e1;
}

.crumbs .current {
  color: #0f172a;
  font-weight: 600;
}

.header-right {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.fs-btn {
  width: 36px;
  height: 36px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  color: #334155;
}

.fs-btn:hover {
  color: #1677ff !important;
  background: rgba(22, 119, 255, 0.08) !important;
}

.header-right .user-entry {
  color: #334155;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-weight: 500;
}

.content {
  margin: 6px 8px;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.is-content-fullscreen .content {
  margin: 8px;
  height: 100vh;
}

:deep(.ant-menu) {
  font-size: 12px;
}

:deep(.ant-menu-dark .ant-menu-item-selected) {
  border-radius: 6px;
}

:deep(.ant-menu-dark.ant-menu-inline .ant-menu-item),
:deep(.ant-menu-dark.ant-menu-inline .ant-menu-submenu-title) {
  margin-inline: 4px;
  width: calc(100% - 8px);
  border-radius: 6px;
  height: 34px;
  line-height: 34px;
  padding-inline: 8px !important;
}

:deep(.ant-menu-dark.ant-menu-inline .ant-menu-submenu-title) {
  font-size: 12px;
  font-weight: 600;
}

:deep(.ant-menu-dark.ant-menu-inline .ant-menu-item) {
  font-size: 11px;
  font-weight: 400;
  padding-inline-start: 28px !important;
}

:deep(.ant-menu-inline .ant-menu-item .anticon),
:deep(.ant-menu-inline .ant-menu-submenu-title .anticon) {
  font-size: 13px;
  min-width: 14px;
  margin-inline-end: 6px;
}

/* 一级任务菜单：与二级缩进对齐取消 */
:deep(.ant-menu-dark.ant-menu-inline > .ant-menu-item) {
  padding-inline-start: 8px !important;
  font-size: 12px;
  font-weight: 600;
}
</style>
