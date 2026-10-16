<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-header">
        <img class="logo-img" src="/logo.png" alt="工程图纸生成系统 Logo" />
        <div class="title-wrap">
          <h1>工程图纸生成系统</h1>
        </div>
      </div>

      <a-form layout="vertical" :model="form" @finish="onSubmit">
        <a-form-item name="username" :rules="[{ required: true, message: '请输入钉钉工号' }]">
          <a-input v-model:value="form.username" size="large" placeholder="请输入钉钉工号" allow-clear>
            <template #prefix><UserOutlined /></template>
          </a-input>
        </a-form-item>
        <a-form-item name="password" :rules="[{ required: true, message: '请输入密码' }]">
          <a-input-password v-model:value="form.password" size="large" placeholder="请输入密码">
            <template #prefix><LockOutlined /></template>
          </a-input-password>
        </a-form-item>
        <div class="login-options">
          <a-checkbox v-model:checked="remember">记住账号</a-checkbox>
        </div>
        <a-button type="primary" html-type="submit" size="large" block :loading="loading">
          登录
        </a-button>
      </a-form>

      <div class="login-footer">
        <a @click="showPwd = true">修改密码</a>
      </div>
    </div>

    <ChangePasswordModal
      v-model:open="showPwd"
      :require-login="false"
      tip="请先使用当前账号密码登录后再改密；未登录改密入口仅作引导。"
    />

    <!-- 强制补姓名：不可关闭 -->
    <a-modal
      v-model:open="profileOpen"
      title="完善用户名称"
      :closable="false"
      :mask-closable="false"
      :keyboard="false"
      :footer="null"
      destroy-on-close
    >
      <p class="profile-tip">首次登录请填写显示名称，便于审计与协作识别。</p>
      <a-form layout="vertical" :model="profileForm" @finish="onSaveProfile">
        <a-form-item
          name="displayName"
          label="用户名称"
          :rules="[{ required: true, message: '请输入用户名称' }, { min: 2, max: 32, message: '2–32 个字符' }]"
        >
          <a-input
            v-model:value="profileForm.displayName"
            size="large"
            placeholder="请输入用户名称"
            allow-clear
            :maxlength="32"
          />
        </a-form-item>
        <a-button type="primary" html-type="submit" size="large" block :loading="profileSaving">
          确认并进入系统
        </a-button>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { message } from 'ant-design-vue'
import { UserOutlined, LockOutlined } from '@ant-design/icons-vue'
import { authApi } from '@/api'
import { useUserStore } from '@/stores/user'
import ChangePasswordModal from '@/components/ChangePasswordModal.vue'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const loading = ref(false)
const remember = ref(true)
const showPwd = ref(false)
const profileOpen = ref(false)
const profileSaving = ref(false)
const pendingRedirect = ref('/tasks')
const form = reactive({
  username: '',
  password: ''
})
const profileForm = reactive({
  displayName: ''
})

onMounted(() => {
  form.username = userStore.rememberedUsername()
})

async function enterSystem() {
  message.success('登录成功')
  const redirect = pendingRedirect.value || '/tasks'
  await router.replace(redirect.startsWith('/') ? redirect : '/tasks')
}

async function onSubmit() {
  loading.value = true
  try {
    const res = await authApi.login(form.username.trim(), form.password)
    userStore.setAuth(res, remember.value)
    pendingRedirect.value = (route.query.redirect as string) || '/tasks'
    if (res.needProfile) {
      profileForm.displayName = (res.displayName || '').trim()
      profileOpen.value = true
      return
    }
    await enterSystem()
  } catch {
    // 业务错误已由 http 拦截器提示
  } finally {
    loading.value = false
  }
}

async function onSaveProfile() {
  const name = profileForm.displayName.trim()
  if (!name) {
    message.warning('请输入用户名称')
    return
  }
  profileSaving.value = true
  try {
    const res = await authApi.updateDisplayName(name)
    userStore.updateDisplayName(res.displayName || name)
    profileOpen.value = false
    await enterSystem()
  } catch {
    // toast 已处理
  } finally {
    profileSaving.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background:
    linear-gradient(135deg, rgba(8, 28, 68, 0.78), rgba(12, 48, 96, 0.72)),
    url('/login-bg.jpg') center / cover no-repeat;
}

.login-card {
  width: 420px;
  max-width: 100%;
  background: #fff;
  border-radius: 10px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.28);
  padding: 32px 36px 24px;
}

.login-header {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 28px;
}

.logo-img {
  width: 46px;
  height: 46px;
  border-radius: 50%;
  object-fit: cover;
  flex-shrink: 0;
}

.title-wrap h1 {
  margin: 0;
  font-size: 19px;
  font-weight: 700;
  color: #0f172a;
  line-height: 1.35;
  letter-spacing: 0.02em;
}

.login-options {
  margin: -4px 0 16px;
}

.login-footer {
  margin-top: 16px;
  text-align: right;
}

.login-footer a {
  color: #1677ff;
  font-size: 13px;
  cursor: pointer;
}

.profile-tip {
  margin: 0 0 12px;
  color: #64748b;
  font-size: 13px;
}
</style>
