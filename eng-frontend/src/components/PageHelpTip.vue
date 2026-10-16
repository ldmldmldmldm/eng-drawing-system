<template>
  <a-popover
    v-model:open="open"
    trigger="click"
    placement="bottomRight"
    :overlay-inner-style="{ maxWidth: '420px' }"
  >
    <template #title v-if="title">{{ title }}</template>
    <template #content>
      <div class="jy-page-help-body">
        <slot>{{ content }}</slot>
      </div>
    </template>
    <a-tooltip :title="tooltip" :mouse-enter-delay="0.4">
      <a-button type="text" size="small" class="jy-page-help-btn" aria-label="说明">
        <QuestionCircleOutlined />
      </a-button>
    </a-tooltip>
  </a-popover>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { QuestionCircleOutlined } from '@ant-design/icons-vue'

withDefaults(
  defineProps<{
    /** 气泡标题（可选） */
    title?: string
    /** 纯文案说明；复杂内容用默认插槽 */
    content?: string
    /** 悬停提示 */
    tooltip?: string
  }>(),
  {
    tooltip: '说明'
  }
)

const open = ref(false)
</script>

<style scoped>
.jy-page-help-body {
  font-size: 12px;
  line-height: 1.6;
  color: #1f2937;
  white-space: pre-wrap;
}
</style>
