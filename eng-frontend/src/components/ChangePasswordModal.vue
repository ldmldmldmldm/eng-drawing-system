<template>
  <a-modal
    :open="open"
    title="修改密码"
    :confirm-loading="loading"
    ok-text="确认修改"
    cancel-text="取消"
    destroy-on-close
    @update:open="emit('update:open', $event)"
    @ok="onOk"
  >
    <a-alert v-if="tip" type="info" show-icon :message="tip" style="margin-bottom: 16px" />
    <a-form layout="vertical" :model="form">
      <a-form-item v-if="!requireLogin" label="账号" required>
        <a-input v-model:value="form.username" placeholder="请输入账号" />
      </a-form-item>
      <a-form-item label="旧密码" required>
        <a-input-password v-model:value="form.oldPassword" placeholder="请输入旧密码" />
      </a-form-item>
      <a-form-item label="新密码" required>
        <a-input-password v-model:value="form.newPassword" placeholder="至少 6 位" />
      </a-form-item>
      <a-form-item label="确认新密码" required>
        <a-input-password v-model:value="form.confirmPassword" placeholder="再次输入新密码" />
      </a-form-item>
    </a-form>
  </a-modal>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { authApi } from '@/api'
import { useUserStore } from '@/stores/user'
import { useRouter } from 'vue-router'

const props = withDefaults(defineProps<{
  open: boolean
  requireLogin?: boolean
  tip?: string
}>(), {
  requireLogin: true,
  tip: ''
})

const emit = defineEmits<{
  'update:open': [boolean]
}>()

const userStore = useUserStore()
const router = useRouter()
const loading = ref(false)
const form = reactive({
  username: '',
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

watch(() => props.open, (v) => {
  if (v) {
    form.username = userStore.username || userStore.rememberedUsername()
    form.oldPassword = ''
    form.newPassword = ''
    form.confirmPassword = ''
  }
})

async function onOk() {
  if (!form.oldPassword || !form.newPassword) {
    message.warning('请填写完整')
    return
  }
  if (form.newPassword.length < 6) {
    message.warning('新密码至少 6 位')
    return
  }
  if (form.newPassword !== form.confirmPassword) {
    message.warning('两次新密码不一致')
    return
  }

  if (!props.requireLogin && !userStore.isLoggedIn) {
    // 登录页入口：先登录再改密
    loading.value = true
    try {
      const res = await authApi.login(form.username.trim(), form.oldPassword)
      userStore.setAuth(res, true)
      await authApi.changePassword(form.oldPassword, form.newPassword)
      message.success('密码已修改，请使用新密码登录')
      userStore.clearAuth()
      emit('update:open', false)
    } catch {
      // 业务错误已由 http 拦截器提示，避免二次弹窗
    } finally {
      loading.value = false
    }
    return
  }

  loading.value = true
  try {
    await authApi.changePassword(form.oldPassword, form.newPassword)
    message.success('密码已修改，请重新登录')
    userStore.clearAuth()
    emit('update:open', false)
    await router.replace('/login')
  } catch {
    // 业务错误已由 http 拦截器提示，避免二次弹窗
  } finally {
    loading.value = false
  }
}
</script>
