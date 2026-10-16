<template>
  <div class="page-list">
    <a-card size="small" title="语料管理">
      <template #extra>
        <a-space wrap :size="6">
          <PageHelpTip
            title="语料说明"
            content="维护 NOTE 英译中词条。层级：词 TERM（短词，词边界匹配）、短语 PHRASE（多词固定译法）。料号/标准号/数字不译。翻译时语料优先，未命中再走启用中的大模型。"
          />
          <a-button size="small" @click="onTemplate">下载模板</a-button>
          <a-upload :before-upload="beforeImport" :show-upload-list="false" accept=".xlsx,.xls">
            <a-button size="small" :loading="importing">导入</a-button>
          </a-upload>
          <a-button size="small" :loading="exporting" @click="onExport">导出</a-button>
          <a-button size="small" :loading="reclassifying" @click="onReclassify">按规则洗库</a-button>
          <a-button type="primary" size="small" @click="openCreate">新增</a-button>
          <FullscreenExitFilterBtn />
        </a-space>
      </template>

      <a-form layout="inline" class="list-filter-form filter-card" size="small" :model="filter" style="margin-bottom: 12px">
        <a-form-item label="关键词">
          <a-input
            v-model:value="filter.keyword"
            size="small"
            allow-clear
            placeholder="英文/中文"
            style="width: 180px"
            @pressEnter="onSearch"
          />
        </a-form-item>
        <a-form-item label="层级">
          <a-select v-model:value="filter.entryKind" size="small" allow-clear placeholder="全部" style="width: 120px">
            <a-select-option value="TERM">词 TERM</a-select-option>
            <a-select-option value="PHRASE">短语 PHRASE</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="状态">
          <a-select v-model:value="filter.status" size="small" allow-clear placeholder="全部" style="width: 120px">
            <a-select-option value="ACTIVE">启用</a-select-option>
            <a-select-option value="DISABLED">停用</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item>
          <a-button type="primary" size="small" :loading="loading" @click="onSearch">查询</a-button>
        </a-form-item>
      </a-form>

      <div class="table-scroll">
        <a-table
          :columns="columns"
          :data-source="items"
          :loading="loading"
          row-key="id"
          size="middle"
          bordered
          :pagination="pagination"
          @change="onTableChange"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'entryKind'">
              <a-tag :color="kindColor(record.entryKind)">{{ kindLabel(record.entryKind) }}</a-tag>
            </template>
            <template v-else-if="column.key === 'status'">
              <a-tag :color="record.status === 'ACTIVE' ? 'green' : 'default'">
                {{ record.status === 'ACTIVE' ? '启用' : '停用' }}
              </a-tag>
            </template>
            <template v-else-if="column.key === 'action'">
              <a-space :size="4">
                <a-button type="link" size="small" @click="openEdit(record)">编辑</a-button>
                <a-popconfirm title="确认删除该语料？" ok-text="删除" ok-type="danger" @confirm="onDelete(record.id)">
                  <a-button type="link" size="small" danger>删除</a-button>
                </a-popconfirm>
              </a-space>
            </template>
          </template>
        </a-table>
      </div>
    </a-card>

    <a-modal
      v-model:open="modalOpen"
      :title="editingId ? '编辑语料' : '新增语料'"
      ok-text="保存"
      cancel-text="取消"
      :confirm-loading="saving"
      destroy-on-close
      @ok="onSave"
    >
      <a-form layout="vertical" size="small">
        <a-form-item label="英文短语" required>
          <a-input v-model:value="form.enPhrase" placeholder="如 RELEASE LINER" />
        </a-form-item>
        <a-form-item label="中文译文" required>
          <a-input v-model:value="form.zhPhrase" placeholder="如 离型膜" />
        </a-form-item>
        <a-form-item label="层级">
          <a-select v-model:value="form.entryKind" style="width: 100%">
            <a-select-option value="TERM">词 TERM（短词，词边界匹配）</a-select-option>
            <a-select-option value="PHRASE">短语 PHRASE（多词固定译法）</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="状态">
          <a-select v-model:value="form.status" style="width: 100%">
            <a-select-option value="ACTIVE">启用</a-select-option>
            <a-select-option value="DISABLED">停用</a-select-option>
          </a-select>
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { glossaryApi, type GlossaryRow } from '@/api'
import PageHelpTip from '@/components/PageHelpTip.vue'
import FullscreenExitFilterBtn from '@/components/FullscreenExitFilterBtn.vue'

const loading = ref(false)
const saving = ref(false)
const importing = ref(false)
const exporting = ref(false)
const reclassifying = ref(false)
const items = ref<GlossaryRow[]>([])
const modalOpen = ref(false)
const editingId = ref<number | null>(null)
const filter = reactive({
  keyword: '',
  status: undefined as string | undefined,
  entryKind: undefined as string | undefined
})
const form = reactive({ enPhrase: '', zhPhrase: '', entryKind: 'PHRASE', status: 'ACTIVE' })
const pagination = reactive({ current: 1, pageSize: 20, total: 0, showSizeChanger: true })

const columns = [
  { title: '英文短语', dataIndex: 'enPhrase', key: 'enPhrase', ellipsis: true },
  { title: '中文译文', dataIndex: 'zhPhrase', key: 'zhPhrase', ellipsis: true },
  { title: '层级', key: 'entryKind', width: 110 },
  { title: '状态', key: 'status', width: 90 },
  { title: '更新时间', dataIndex: 'updateTime', key: 'updateTime', width: 170 },
  { title: '操作', key: 'action', width: 120 }
]

function kindLabel(k?: string) {
  if (k === 'TERM') return '词'
  return '短语'
}

function kindColor(k?: string) {
  if (k === 'TERM') return 'blue'
  return 'cyan'
}

async function load() {
  loading.value = true
  try {
    const res = await glossaryApi.page({
      page: pagination.current,
      size: pagination.pageSize,
      keyword: filter.keyword.trim() || undefined,
      status: filter.status,
      entryKind: filter.entryKind
    })
    items.value = res.items || []
    pagination.total = res.total || 0
  } catch (e) {
    items.value = []
    message.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function onSearch() {
  pagination.current = 1
  void load()
}

function onTableChange(pag: { current?: number; pageSize?: number }) {
  pagination.current = pag.current || 1
  pagination.pageSize = pag.pageSize || 20
  void load()
}

function openCreate() {
  editingId.value = null
  form.enPhrase = ''
  form.zhPhrase = ''
  form.entryKind = 'PHRASE'
  form.status = 'ACTIVE'
  modalOpen.value = true
}

function openEdit(row: GlossaryRow) {
  editingId.value = row.id
  form.enPhrase = row.enPhrase
  form.zhPhrase = row.zhPhrase
  form.entryKind = row.entryKind || 'PHRASE'
  form.status = row.status || 'ACTIVE'
  modalOpen.value = true
}

async function onSave() {
  if (!form.enPhrase.trim() || !form.zhPhrase.trim()) {
    message.warning('英文与中文不能为空')
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await glossaryApi.update(editingId.value, { ...form })
      message.success('已更新')
    } else {
      await glossaryApi.create({ ...form })
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

async function onDelete(id: number) {
  try {
    await glossaryApi.remove(id)
    message.success('已删除')
    await load()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '删除失败')
  }
}

async function onExport() {
  exporting.value = true
  try {
    await glossaryApi.exportExcel({
      keyword: filter.keyword.trim() || undefined,
      status: filter.status,
      entryKind: filter.entryKind
    })
  } catch {
    // toast in helper
  } finally {
    exporting.value = false
  }
}

async function onTemplate() {
  try {
    await glossaryApi.downloadTemplate()
  } catch {
    // ignore
  }
}

function onReclassify() {
  Modal.confirm({
    title: '按规则洗库？',
    content: '将按词数/长度规则重算全部条目的层级（短词→TERM，其余→PHRASE）。',
    okText: '洗库',
    cancelText: '取消',
    onOk: async () => {
      reclassifying.value = true
      try {
        const res = await glossaryApi.reclassify()
        message.success(`洗库完成：词 ${res.termCount}，短语 ${res.phraseCount}`)
        await load()
      } catch (e) {
        message.error(e instanceof Error ? e.message : '洗库失败')
      } finally {
        reclassifying.value = false
      }
    }
  })
}

function beforeImport(file: File) {
  void (async () => {
    importing.value = true
    try {
      const res = await glossaryApi.importExcel(file)
      message.success(`导入完成：成功 ${res.successCount}，失败 ${res.failCount}`)
      if (res.errors?.length) {
        message.warning(res.errors.slice(0, 3).join('；'))
      }
      await load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '导入失败')
    } finally {
      importing.value = false
    }
  })()
  return false
}

onMounted(() => void load())
</script>
