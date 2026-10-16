<template>
  <a-button
    v-if="active"
    type="primary"
    size="small"
    class="jy-fs-exit-inline"
    @click="onExit"
  >
    <FullscreenExitOutlined />
    退出全屏
  </a-button>
</template>

<script setup lang="ts">
import { computed, inject, type Ref } from 'vue'
import { FullscreenExitOutlined } from '@ant-design/icons-vue'

type FsApi = {
  active: Ref<boolean>
  exit: () => void
}

const fs = inject<FsApi | null>('jyContentFullscreen', null)
const active = computed(() => !!fs?.active.value)

function onExit() {
  fs?.exit()
}
</script>
