<template>
  <div class="page-list" data-tour="page-tasks">
    <a-card size="small" title="任务">
      <template #extra>
        <a-space wrap :size="6">
          <PageHelpTip
            title="任务说明"
            content="新建任务后点「编辑」进入①～⑤向导。已完成任务点「编辑」会先确认再重开为进行中（成品图暂时无法预览下载，再次生成完成才更新）；点「预览下载」看结果。"
          />
          <a-button
            type="primary"
            size="small"
            data-tour="btn-create-task"
            @click="openCreate"
          >
            新建任务
          </a-button>
          <FullscreenExitFilterBtn />
        </a-space>
      </template>

      <a-form
        layout="inline"
        class="list-filter-form"
        size="small"
        :model="filter"
        style="margin-bottom: 12px"
        data-tour="filter-tasks"
      >
        <a-form-item label="关键词">
          <a-input
            v-model:value="filter.keyword"
            size="small"
            allow-clear
            placeholder="标题"
            style="width: 160px"
            @press-enter="onSearch"
          />
        </a-form-item>
        <a-form-item label="状态">
          <a-select v-model:value="filter.status" size="small" allow-clear placeholder="全部" style="width: 120px">
            <a-select-option value="DRAFT">草稿</a-select-option>
            <a-select-option value="IN_PROGRESS">进行中</a-select-option>
            <a-select-option value="COMPLETED">已完成</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item>
          <a-button type="primary" size="small" :loading="loading" @click="onSearch">查询</a-button>
        </a-form-item>
      </a-form>

      <a-alert v-if="loadError" type="error" show-icon :message="loadError" style="margin-bottom: 12px" />

      <div class="table-scroll">
        <a-table
          :columns="columns"
          :data-source="rows"
          :loading="loading"
          row-key="id"
          :pagination="pagination"
          size="middle"
          bordered
          :row-class-name="rowClassName"
          :locale="{ emptyText: loadError ? '加载失败，请点击查询重试' : '暂无任务，点击右上角「新建任务」开始' }"
          @change="onTableChange"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'owner'">
              {{ ownerLabel(record) }}
            </template>
            <template v-else-if="column.key === 'status'">
              <a-tag :color="statusColor(record.status)">{{ statusLabel(record.status) }}</a-tag>
            </template>
            <template v-else-if="column.key === 'currentStep'">
              {{ stepLabel(record.currentStep, record.status) }}
            </template>
            <template v-else-if="column.key === 'updateTime'">
              {{ formatTime(record.updateTime) }}
            </template>
            <template v-else-if="column.key === 'action'">
              <a-space :size="4" wrap>
                <a-button
                  type="link"
                  size="small"
                  data-tour="btn-enter-task"
                  :loading="editingId === record.id"
                  @click="onEdit(record)"
                >
                  编辑
                </a-button>
                <a-tooltip
                  :title="
                    record.status === 'COMPLETED'
                      ? '查看预览并下载 DXF'
                      : '任务完成后可用（请先在工作台生成并完成）'
                  "
                >
                  <span style="display: inline-block">
                    <a-button
                      type="link"
                      size="small"
                      :disabled="record.status !== 'COMPLETED'"
                      @click="goResult(record)"
                    >
                      预览下载
                    </a-button>
                  </span>
                </a-tooltip>
                <a-button type="link" size="small" danger @click="onDelete(record)">删除</a-button>
              </a-space>
            </template>
          </template>
        </a-table>
      </div>
    </a-card>

    <a-modal v-model:open="createOpen" title="新建任务" :confirm-loading="creating" @ok="onCreate">
      <a-form layout="vertical">
        <a-form-item label="任务标题" required>
          <a-input v-model:value="createTitle" placeholder="例如：XX项目-工程图" :maxlength="80" allow-clear />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import { ExclamationCircleFilled } from '@ant-design/icons-vue'
import type { TablePaginationConfig } from 'ant-design-vue'
import { taskApi } from '@/api'
import type { TaskItem, TaskStatus } from '@/api/types'
import PageHelpTip from '@/components/PageHelpTip.vue'
import FullscreenExitFilterBtn from '@/components/FullscreenExitFilterBtn.vue'
import { formatTime } from '@/utils/format'
import { taskStepLabel } from '@/utils/taskStep'
import { useUserStore } from '@/stores/user'
import { maybeAutoStartListTour, readTourProgress, writeTourProgress } from '@/onboarding'

const router = useRouter()
const userStore = useUserStore()
const isAdmin = computed(() => (userStore.role || '').toUpperCase() === 'ADMIN')
const loading = ref(false)
const loadError = ref('')
const rows = ref<TaskItem[]>([])
const createOpen = ref(false)
const createTitle = ref('')
const creating = ref(false)
/** 正在「编辑」重开的任务 id */
const editingId = ref<string | number | null>(null)
/** 刚创建的任务 ID，用于列表行高亮提示「可点编辑」 */
const highlightId = ref<string | number | null>(null)

const filter = reactive({
  keyword: '',
  status: undefined as TaskStatus | undefined
})

const pagination = reactive({
  current: 1,
  pageSize: 20,
  total: 0,
  showSizeChanger: true,
  showTotal: (t: number) => `共 ${t} 条`
})

const columns = computed(() => {
  const cols = [
    { title: '标题', dataIndex: 'title', key: 'title', ellipsis: true },
    { title: '状态', key: 'status', width: 100 },
    { title: '当前步骤', dataIndex: 'currentStep', key: 'currentStep', width: 130 },
    { title: '更新时间', key: 'updateTime', width: 170 },
    { title: '操作', key: 'action', width: 200 }
  ]
  if (isAdmin.value) {
    cols.splice(1, 0, { title: '所属用户', key: 'owner', width: 160, ellipsis: true })
  }
  return cols
})

function ownerLabel(row: TaskItem) {
  const name = (row.ownerDisplayName || '').trim()
  const account = (row.ownerUsername || '').trim()
  if (name && account && name !== account) {
    return `${name}（${account}）`
  }
  return name || account || '—'
}

function statusLabel(s: TaskStatus | string) {
  if (s === 'DRAFT') return '草稿'
  if (s === 'IN_PROGRESS') return '进行中'
  if (s === 'COMPLETED') return '已完成'
  return s || '—'
}

function statusColor(s: TaskStatus | string) {
  if (s === 'DRAFT') return 'default'
  if (s === 'IN_PROGRESS') return 'processing'
  if (s === 'COMPLETED') return 'success'
  return 'default'
}

function stepLabel(step: number | string | undefined, status?: TaskStatus | string) {
  return taskStepLabel(step, status)
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const res = await taskApi.list({
      page: pagination.current,
      size: pagination.pageSize,
      status: filter.status,
      keyword: filter.keyword.trim() || undefined
    })
    rows.value = res.items || []
    pagination.total = res.total || 0
  } catch (e) {
    rows.value = []
    pagination.total = 0
    loadError.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

function onSearch() {
  pagination.current = 1
  void load()
}

function onTableChange(pag: TablePaginationConfig) {
  pagination.current = pag.current || 1
  pagination.pageSize = pag.pageSize || 20
  void load()
}

function openCreate() {
  createTitle.value = ''
  createOpen.value = true
}

function rowClassName(record: TaskItem) {
  return highlightId.value != null && String(record.id) === String(highlightId.value)
    ? 'task-row-highlight'
    : ''
}

async function onCreate() {
  const title = createTitle.value.trim()
  if (!title) {
    message.warning('请输入任务标题')
    return
  }
  creating.value = true
  try {
    const item = await taskApi.create({ title })
    createOpen.value = false
    highlightId.value = item.id
    message.success('已创建，请在列表中点「编辑」开始操作')
    pagination.current = 1
    await load()
  } catch {
    // toast
  } finally {
    creating.value = false
  }
}

/** 编辑：进工作台；已完成先确认再重开为进行中 */
function onEdit(row: TaskItem) {
  highlightId.value = null
  if (row.status !== 'COMPLETED') {
    markPendingWizardIfNeeded()
    router.push(`/tasks/${row.id}`)
    return
  }
  Modal.confirm({
    title: '重新编辑已完成任务？',
    content:
      '重新编辑后，任务会变为「进行中」，当前成品图将暂时无法预览/下载；在①～⑤修改并再次生成完成后才会更新产物。是否继续？',
    okText: '继续编辑',
    cancelText: '取消',
    centered: true,
    onOk: async () => {
      editingId.value = row.id
      try {
        await taskApi.update(row.id, { status: 'IN_PROGRESS' })
        message.success('已重新打开，可进入①～⑤修改后再次生成')
        markPendingWizardIfNeeded()
        await router.push(`/tasks/${row.id}`)
      } finally {
        editingId.value = null
      }
    }
  })
}

function markPendingWizardIfNeeded() {
  const u = userStore.username || ''
  const p = readTourProgress(u)
  if (p.listDone && !p.wizardDone && !p.skipped) {
    writeTourProgress(u, { pendingWizard: true })
  }
}

function goResult(row: TaskItem) {
  highlightId.value = null
  router.push(`/tasks/${row.id}/result`)
}

function onDelete(row: TaskItem) {
  const title = (row.title || '').trim() || '未命名任务'
  const statusText = statusLabel(row.status)
  Modal.confirm({
    title: '删除任务后无法恢复',
    icon: () => h(ExclamationCircleFilled, { style: { color: '#faad14' } }),
    centered: true,
    okText: '确认删除',
    okType: 'danger',
    cancelText: '取消',
    width: 460,
    content: () =>
      h('div', { class: 'task-del-modal' }, [
        h('p', { class: 'task-del-lead' }, [
          '即将删除 ',
          h('strong', title),
          h('span', `，当前状态：${statusText}。`),
        ]),
        h('p', { class: 'task-del-sub' }, '删除后将同时清除：'),
        h('ul', { class: 'task-del-list' }, [
          h('li', '任务记录（列表中不再显示，无法恢复）'),
          h('li', '客户 DWG、三维 STP、审校快照与过程文件'),
          h('li', '已生成的工程图 DXF 与预览图'),
          h('li', '仅被本任务使用的三维层次资产（其它任务仍在用则保留）'),
        ]),
      ]),
    onOk: async () => {
      await taskApi.remove(row.id)
      if (highlightId.value != null && String(highlightId.value) === String(row.id)) {
        highlightId.value = null
      }
      message.success('任务已删除')
      await load()
    },
  })
}

onMounted(async () => {
  await load()
  maybeAutoStartListTour(userStore.username || '', isAdmin.value)
})
</script>

<style scoped>
:deep(.task-row-highlight > td) {
  background: #e6f4ff !important;
}
</style>

<style>
/* Modal.confirm 挂到 body，需非 scoped */
.task-del-modal {
  color: #334155;
  font-size: 13px;
  line-height: 1.55;
}
.task-del-lead {
  margin: 0 0 10px;
}
.task-del-lead strong {
  color: #0f172a;
}
.task-del-sub {
  margin: 0 0 6px;
  color: #64748b;
}
.task-del-list {
  margin: 0;
  padding: 8px 12px 8px 28px;
  background: #fff7ed;
  border: 1px solid #fed7aa;
  border-radius: 8px;
  color: #9a3412;
}
.task-del-list li + li {
  margin-top: 4px;
}
</style>
