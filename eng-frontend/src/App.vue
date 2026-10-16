<template>
  <a-config-provider :locale="zhCN" :theme="theme">
    <router-view />
  </a-config-provider>
</template>

<script setup lang="ts">
import zhCN from 'ant-design-vue/es/locale/zh_CN'
import type { ThemeConfig } from 'ant-design-vue/es/config-provider/context'

const theme: ThemeConfig = {
  token: {
    fontFamily:
      '"Noto Sans SC", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", Inter, system-ui, sans-serif',
    colorPrimary: '#1677ff',
    borderRadius: 8
  }
}
</script>
