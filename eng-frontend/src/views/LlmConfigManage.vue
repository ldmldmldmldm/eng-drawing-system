<template>
  <div class="page-list">
    <a-card size="small" title="模型配置">
      <template #extra>
        <a-space wrap :size="6">
          <PageHelpTip
            title="模型配置说明"
            content="配置 OpenAI 兼容的 LLM（如 Deepseek）。全局仅可启用一个，同时用于材料试抽与 NOTE 补译。可用「测试」验证 Base URL / Key / 模型名是否通。API Key 仅管理员可见（列表脱敏）。"
          />
          <a-tag color="orange">仅系统管理员可见</a-tag>
          <a-button type="primary" size="small" @click="openCreate">新增</a-button>
          <FullscreenExitFilterBtn />
        </a-space>
      </template>

      <div class="table-scroll">
        <a-table
          :columns="columns"
          :data-source="items"
          :loading="loading"
          row-key="id"
          size="middle"
          bordered
          :pagination="false"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'enabled'">
              <a-tag :color="record.enabled ? 'green' : 'default'">
                {{ record.enabled ? '启用中' : '未启用' }}
              </a-tag>
            </template>
            <template v-else-if="column.key === 'apiKey'">
              {{ record.apiKeyMasked || record.apiKey || '—' }}
            </template>
            <template v-else-if="column.key === 'action'">
              <a-space :size="4" wrap>
                <a-button type="link" size="small" @click="openEdit(record)">编辑</a-button>
                <a-button
                  type="link"
                  size="small"
                  :loading="testingId === record.id"
                  @click="onTest(record.id)"
                >
                  测试
                </a-button>
                <a-button
                  type="link"
                  size="small"
                  :disabled="record.enabled"
                  :loading="enablingId === record.id"
                  @click="onEnable(record.id)"
                >
                  启用
                </a-button>
                <a-popconfirm
                  title="确认删除该配置？"
                  ok-text="删除"
                  ok-type="danger"
                  :disabled="record.enabled"
                  @confirm="onDelete(record.id)"
                >
                  <a-button type="link" size="small" danger :disabled="record.enabled">删除</a-button>
                </a-popconfirm>
              </a-space>
            </template>
          </template>
        </a-table>
      </div>
    </a-card>

    <a-modal
      v-model:open="modalOpen"
      :title="editingId ? '编辑模型' : '新增模型'"
      ok-text="保存"
      cancel-text="取消"
      :confirm-loading="saving"
      destroy-on-close
      width="560px"
      @ok="onSave"
    >
      <a-form layout="vertical" size="small">
        <a-form-item label="名称" required>
          <a-input v-model:value="form.name" placeholder="如 生产 Deepseek" />
        </a-form-item>
        <a-form-item label="厂商">
          <a-input v-model:value="form.provider" placeholder="deepseek / openai / 其它" />
        </a-form-item>
        <a-form-item label="Base URL" required>
          <a-input v-model:value="form.baseUrl" placeholder="https://api.deepseek.com" />
        </a-form-item>
        <a-form-item label="模型名" required>
          <a-input v-model:value="form.modelName" placeholder="deepseek-flash" />
        </a-form-item>
        <a-form-item :label="editingId ? 'API Key（留空则不改）' : 'API Key'" :required="!editingId">
          <a-input-password v-model:value="form.apiKey" placeholder="sk-…" autocomplete="new-password" />
        </a-form-item>
        <a-form-item label="备注">
          <a-input v-model:value="form.remark" />
        </a-form-item>
        <a-form-item label="保存后启用">
          <a-switch v-model:checked="form.enabled" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { llmConfigApi, type LlmConfigRow } from '@/api'
import PageHelpTip from '@/components/PageHelpTip.vue'
import FullscreenExitFilterBtn from '@/components/FullscreenExitFilterBtn.vue'

const loading = ref(false)
const saving = ref(false)
const enablingId = ref<number | null>(null)
const testingId = ref<number | null>(null)
const items = ref<LlmConfigRow[]>([])
const modalOpen = ref(false)
const editingId = ref<number | null>(null)
const form = reactive({
  name: '',
  provider: 'deepseek',
  baseUrl: 'https://api.deepseek.com',
  modelName: 'deepseek-flash',
  apiKey: '',
  remark: '',
  enabled: false
})

const columns = [
  { title: '名称', dataIndex: 'name', key: 'name', width: 140 },
  { title: '厂商', dataIndex: 'provider', key: 'provider', width: 100 },
  { title: '模型', dataIndex: 'modelName', key: 'modelName', width: 140 },
  { title: 'Base URL', dataIndex: 'baseUrl', key: 'baseUrl', ellipsis: true },
  { title: 'API Key', key: 'apiKey', width: 140 },
  { title: '状态', key: 'enabled', width: 90 },
  { title: '操作', key: 'action', width: 220 }
]

async function load() {
  loading.value = true
  try {
    items.value = (await llmConfigApi.list()) || []
  } catch (e) {
    items.value = []
    message.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  form.name = ''
  form.provider = 'deepseek'
  form.baseUrl = 'https://api.deepseek.com'
  form.modelName = 'deepseek-flash'
  form.apiKey = ''
  form.remark = ''
  form.enabled = true
  modalOpen.value = true
}

function openEdit(row: LlmConfigRow) {
  editingId.value = row.id
  form.name = row.name
  form.provider = row.provider
  form.baseUrl = row.baseUrl
  form.modelName = row.modelName
  form.apiKey = ''
  form.remark = row.remark || ''
  form.enabled = !!row.enabled
  modalOpen.value = true
}

async function onSave() {
  if (!form.name.trim() || !form.baseUrl.trim() || !form.modelName.trim()) {
    message.warning('名称、Base URL、模型名不能为空')
    return
  }
  if (!editingId.value && !form.apiKey.trim()) {
    message.warning('请填写 API Key')
    return
  }
  saving.value = true
  try {
    const body = {
      name: form.name.trim(),
      provider: form.provider.trim() || 'deepseek',
      baseUrl: form.baseUrl.trim(),
      modelName: form.modelName.trim(),
      apiKey: form.apiKey.trim() || undefined,
      remark: form.remark.trim(),
      enabled: form.enabled
    }
    if (editingId.value) {
      await llmConfigApi.update(editingId.value, body)
      message.success('已更新')
    } else {
      await llmConfigApi.create(body)
      message.success('已新增')
    }
    modalOpen.value = false
    await load()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function onEnable(id: number) {
  enablingId.value = id
  try {
    await llmConfigApi.enable(id)
    message.success('已启用')
    await load()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '启用失败')
  } finally {
    enablingId.value = null
  }
}

async function onTest(id: number) {
  testingId.value = id
  try {
    const res = await llmConfigApi.test(id)
    const ms = res?.latencyMs != null ? `${res.latencyMs}ms` : ''
    if (res?.ok) {
      const preview = res.preview ? `：${res.preview}` : ''
      message.success(`连通正常（${ms}）${preview}`)
    } else {
      message.error(res?.message ? `不通（${ms}）：${res.message}` : `不通（${ms}）`)
    }
  } catch (e) {
    message.error(e instanceof Error ? e.message : '测试失败')
  } finally {
    testingId.value = null
  }
}

async function onDelete(id: number) {
  try {
    await llmConfigApi.remove(id)
    message.success('已删除')
    await load()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '删除失败')
  }
}

onMounted(() => void load())
</script>
