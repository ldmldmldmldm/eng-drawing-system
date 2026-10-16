<template>
  <div class="page-list">
    <a-card size="small" title="操作日志">
      <template #extra>
        <a-space wrap :size="6">
          <PageHelpTip
            title="操作日志说明"
            content="记录系统内新增 / 编辑 / 删除等写操作（含登录登出、备注与目标变更）。详情过长可点击查看全文；支持批量删除。"
          />
          <a-tag color="orange">仅系统管理员可见</a-tag>
          <a-popconfirm
            :title="`确认删除选中的 ${selectedRowKeys.length} 条日志？删除后不可恢复。`"
            ok-text="删除"
            ok-type="danger"
            cancel-text="取消"
            :disabled="!selectedRowKeys.length"
            @confirm="onBatchDelete"
          >
            <a-button size="small" danger :disabled="!selectedRowKeys.length" :loading="batchDeleting">
              批量删除
            </a-button>
          </a-popconfirm>
          <FullscreenExitFilterBtn />
        </a-space>
      </template>

      <a-form layout="inline" class="list-filter-form" size="small" :model="filter" style="margin-bottom: 12px">
        <a-form-item label="操作人">
          <a-input v-model:value="filter.operator" size="small" allow-clear placeholder="精确匹配" style="width: 140px" />
        </a-form-item>
        <a-form-item label="模块">
          <a-select v-model:value="filter.module" size="small" allow-clear placeholder="全部" style="width: 140px">
            <a-select-option value="AUTH">登录认证</a-select-option>
            <a-select-option value="USER">用户管理</a-select-option>
            <a-select-option value="PROJECT_CONFIG">项目配置</a-select-option>
            <a-select-option value="DINGTALK">钉钉推送</a-select-option>
            <a-select-option value="DAILY_NOTE">日产出备注</a-select-option>
            <a-select-option value="DAILY_TARGET">日产出目标</a-select-option>
            <a-select-option value="SYSTEM">系统</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="动作">
          <a-select v-model:value="filter.action" size="small" allow-clear placeholder="全部" style="width: 140px">
            <a-select-option value="CREATE">新增</a-select-option>
            <a-select-option value="UPDATE">编辑</a-select-option>
            <a-select-option value="DELETE">删除</a-select-option>
            <a-select-option value="BATCH_DELETE">批量删除</a-select-option>
            <a-select-option value="IMPORT">导入</a-select-option>
            <a-select-option value="CLEAR">清除</a-select-option>
            <a-select-option value="LOGIN">登录</a-select-option>
            <a-select-option value="LOGOUT">登出</a-select-option>
            <a-select-option value="CHANGE_PASSWORD">改密</a-select-option>
            <a-select-option value="RESET_PASSWORD">重置密码</a-select-option>
            <a-select-option value="TEST">测试</a-select-option>
            <a-select-option value="ENQUEUE">入队</a-select-option>
            <a-select-option value="RETRY">重试</a-select-option>
            <a-select-option value="UPDATE_SCHEDULE">定时配置</a-select-option>
            <a-select-option value="VIEW">查看</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="结果">
          <a-select v-model:value="filter.status" size="small" allow-clear placeholder="全部" style="width: 110px">
            <a-select-option value="SUCCESS">成功</a-select-option>
            <a-select-option value="FAIL">失败</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="日期">
          <a-range-picker v-model:value="dateRange" size="small" value-format="YYYY-MM-DD" style="width: 240px" />
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
          :row-selection="rowSelection"
          :pagination="pagination"
          @change="onTableChange"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'module'">
              {{ moduleLabel(record.module) }}
            </template>
            <template v-else-if="column.key === 'action'">
              <a-tag>{{ actionLabel(record.action) }}</a-tag>
            </template>
            <template v-else-if="column.key === 'status'">
              <a-tag :color="record.status === 'SUCCESS' ? 'green' : 'red'">
                {{ record.status === 'SUCCESS' ? '成功' : '失败' }}
              </a-tag>
            </template>
            <template v-else-if="column.key === 'targetKey'">
              <a-tooltip :title="record.targetKey || '-'">
                <span class="cell-ellipsis cell-clickable" @click="openDetail(record)">
                  {{ record.targetKey || '-' }}
                </span>
              </a-tooltip>
            </template>
            <template v-else-if="column.key === 'detail'">
              <div class="detail-cell cell-clickable" @click="openDetail(record)">
                <a-tooltip :title="record.detail || '-'">
                  <div class="cell-ellipsis">{{ record.detail || '-' }}</div>
                </a-tooltip>
                <div v-if="record.requestId" class="muted cell-ellipsis">rid={{ record.requestId }}</div>
              </div>
            </template>
            <template v-else-if="column.key === 'clientIp'">
              <a-tooltip :title="record.clientIp || '-'">
                <span class="cell-ellipsis">{{ record.clientIp || '-' }}</span>
              </a-tooltip>
            </template>
            <template v-else-if="column.key === 'view'">
              <a-button type="link" size="small" @click="openDetail(record)">查看</a-button>
            </template>
          </template>
        </a-table>
        <a-empty v-if="!loading && !loadError && !items.length" description="暂无操作日志" style="margin-top: 24px" />
        <a-empty v-if="!loading && loadError" description="加载失败，请稍后重试" style="margin-top: 24px" />
      </div>
    </a-card>

    <a-modal
      v-model:open="detailOpen"
      title="操作详情"
      :footer="null"
      width="640px"
      destroy-on-close
    >
      <a-descriptions v-if="detailRow" size="small" bordered :column="1">
        <a-descriptions-item label="操作时间">{{ detailRow.createdTime || '-' }}</a-descriptions-item>
        <a-descriptions-item label="操作人">{{ detailRow.operator || '-' }}</a-descriptions-item>
        <a-descriptions-item label="模块">{{ moduleLabel(detailRow.module) }}</a-descriptions-item>
        <a-descriptions-item label="动作">{{ actionLabel(detailRow.action) }}</a-descriptions-item>
        <a-descriptions-item label="结果">
          <a-tag :color="detailRow.status === 'SUCCESS' ? 'green' : 'red'">
            {{ detailRow.status === 'SUCCESS' ? '成功' : '失败' }}
          </a-tag>
        </a-descriptions-item>
        <a-descriptions-item label="业务键">
          <div class="detail-full">{{ detailRow.targetKey || '-' }}</div>
        </a-descriptions-item>
        <a-descriptions-item label="详情">
          <div class="detail-full">{{ detailRow.detail || '-' }}</div>
        </a-descriptions-item>
        <a-descriptions-item label="IP">{{ detailRow.clientIp || '-' }}</a-descriptions-item>
        <a-descriptions-item label="requestId">
          <div class="detail-full">{{ detailRow.requestId || '-' }}</div>
        </a-descriptions-item>
      </a-descriptions>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import type { TablePaginationConfig } from 'ant-design-vue'
import { message } from 'ant-design-vue'
import { operationLogApi } from '@/api'
import type { OperationLogItem } from '@/api/types'
import FullscreenExitFilterBtn from '@/components/FullscreenExitFilterBtn.vue'
import PageHelpTip from '@/components/PageHelpTip.vue'

const loading = ref(false)
const loadError = ref(false)
const batchDeleting = ref(false)
const items = ref<OperationLogItem[]>([])
const selectedRowKeys = ref<number[]>([])
const dateRange = ref<[string, string] | undefined>()
const detailOpen = ref(false)
const detailRow = ref<OperationLogItem | null>(null)

const filter = reactive({
  operator: undefined as string | undefined,
  module: undefined as string | undefined,
  action: undefined as string | undefined,
  status: undefined as string | undefined
})

const pagination = reactive({
  current: 1,
  pageSize: 50,
  total: 0,
  showSizeChanger: true,
  showTotal: (t: number) => `共 ${t} 条`,
  pageSizeOptions: ['20', '50', '100']
})

const columns = [
  { title: '操作时间', dataIndex: 'createdTime', key: 'createdTime', width: 170 },
  { title: '操作人', dataIndex: 'operator', key: 'operator', width: 100 },
  { title: '模块', dataIndex: 'module', key: 'module', width: 110 },
  { title: '动作', dataIndex: 'action', key: 'action', width: 90 },
  { title: '业务键', dataIndex: 'targetKey', key: 'targetKey', width: 180 },
  { title: '详情', dataIndex: 'detail', key: 'detail' },
  { title: 'IP', dataIndex: 'clientIp', key: 'clientIp', width: 120 },
  { title: '结果', dataIndex: 'status', key: 'status', width: 80 },
  { title: '操作', key: 'view', width: 72, fixed: 'right' as const }
]

const rowSelection = computed(() => ({
  selectedRowKeys: selectedRowKeys.value,
  onChange: (keys: (string | number)[]) => {
    selectedRowKeys.value = keys.map((k) => Number(k))
  }
}))

const MODULE_LABELS: Record<string, string> = {
  AUTH: '登录认证',
  USER: '用户管理',
  PROJECT_CONFIG: '项目配置',
  DINGTALK: '钉钉推送',
  DAILY_NOTE: '日产出备注',
  DAILY_TARGET: '日产出目标',
  SYSTEM: '系统'
}

const ACTION_LABELS: Record<string, string> = {
  CREATE: '新增',
  UPDATE: '编辑',
  DELETE: '删除',
  BATCH_DELETE: '批量删除',
  IMPORT: '导入',
  CLEAR: '清除',
  LOGIN: '登录',
  LOGOUT: '登出',
  CHANGE_PASSWORD: '改密',
  RESET_PASSWORD: '重置密码',
  TEST: '测试',
  ENQUEUE: '入队',
  RETRY: '重试',
  UPDATE_SCHEDULE: '定时配置',
  VIEW: '查看'
}

function moduleLabel(m?: string) {
  return (m && MODULE_LABELS[m]) || m || '-'
}

function actionLabel(a?: string) {
  return (a && ACTION_LABELS[a]) || a || '-'
}

function openDetail(row: OperationLogItem) {
  detailRow.value = row
  detailOpen.value = true
}

async function load() {
  loading.value = true
  loadError.value = false
  selectedRowKeys.value = []
  try {
    const res = await operationLogApi.list({
      page: pagination.current,
      size: pagination.pageSize,
      operator: filter.operator || undefined,
      module: filter.module || undefined,
      action: filter.action || undefined,
      status: filter.status || undefined,
      dateFrom: dateRange.value?.[0],
      dateTo: dateRange.value?.[1]
    })
    items.value = res?.items || []
    pagination.total = res?.total || 0
  } catch {
    loadError.value = true
    items.value = []
    pagination.total = 0
  } finally {
    loading.value = false
  }
}

function onSearch() {
  pagination.current = 1
  load()
}

function onTableChange(pag: TablePaginationConfig) {
  pagination.current = pag.current || 1
  pagination.pageSize = pag.pageSize || 50
  load()
}

async function onBatchDelete() {
  const ids = [...selectedRowKeys.value]
  if (!ids.length) {
    message.warning('请先选择要删除的日志')
    return
  }
  batchDeleting.value = true
  try {
    await operationLogApi.batchDelete(ids)
    message.success('已删除')
    await load()
  } catch {
    // 拦截器已 toast
  } finally {
    batchDeleting.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.muted {
  color: rgba(0, 0, 0, 0.45);
  font-size: 12px;
  margin-top: 2px;
}

.cell-ellipsis {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 100%;
}

.cell-clickable {
  cursor: pointer;
  color: inherit;
}

.cell-clickable:hover {
  color: var(--jy-primary, #1677ff);
}

.detail-cell {
  min-width: 0;
}

.detail-full {
  white-space: pre-wrap;
  word-break: break-all;
  line-height: 1.5;
}
</style>
