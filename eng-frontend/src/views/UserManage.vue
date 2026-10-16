<template>
  <div class="page-list">
    <a-card size="small" title="用户管理">
      <template #extra>
        <a-space wrap :size="6">
          <PageHelpTip
            title="用户管理说明"
            content="普通用户角色为 USER，默认可登录并使用任务工作台；系统管理员账号（admin）不可删除、停用或降权。普通用户状态可在列表开关或编辑弹窗中修改。新建/批量导入默认密码 123456，请尽快通过「修改密码」更换。导入仅新增，账号已存在则跳过。"
          />
          <a-tag color="orange">仅系统管理员可见</a-tag>
          <a-button size="small" @click="onDownloadTemplate">下载模板</a-button>
          <a-button size="small" @click="onExport">导出当前</a-button>
          <a-upload :show-upload-list="false" :before-upload="onImport" accept=".xlsx,.xls">
            <a-button size="small" :loading="importing">导入配置</a-button>
          </a-upload>
          <a-button type="primary" size="small" @click="openCreate">新建用户</a-button>
          <FullscreenExitFilterBtn />
        </a-space>
      </template>

      <a-form layout="inline" class="list-filter-form" size="small" :model="filter" style="margin-bottom: 12px">
        <a-form-item label="账号">
          <a-input v-model:value="filter.username" size="small" allow-clear placeholder="精确匹配" style="width: 140px" />
        </a-form-item>
        <a-form-item label="显示名">
          <a-input v-model:value="filter.displayName" size="small" allow-clear placeholder="精确匹配" style="width: 140px" />
        </a-form-item>
        <a-form-item label="角色">
          <a-select v-model:value="filter.role" size="small" allow-clear placeholder="全部" style="width: 140px">
            <a-select-option value="USER">普通用户</a-select-option>
            <a-select-option value="ADMIN">系统管理员</a-select-option>
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
          :data-source="users"
          :loading="loading"
          row-key="id"
          :pagination="pagination"
          size="middle"
          bordered
          @change="onTableChange"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'role'">
              <a-tag :color="isAdmin(record) ? 'blue' : 'default'">{{ roleLabel(record.role) }}</a-tag>
            </template>
            <template v-else-if="column.key === 'status'">
              <!-- 系统管理员不可停用；普通用户可直接切换状态 -->
              <a-switch
                v-if="!isAdmin(record)"
                size="small"
                :checked="record.status === 'ACTIVE'"
                checked-children="启用"
                un-checked-children="停用"
                :loading="statusSavingId === record.id"
                @change="(checked) => onStatusChange(record, !!checked)"
              />
              <a-tag v-else color="green">启用</a-tag>
            </template>
            <template v-else-if="column.key === 'createTime' || column.key === 'updateTime'">
              {{ formatTime(record[column.key]) }}
            </template>
            <template v-else-if="column.key === 'action'">
              <a-space :size="4" wrap>
                <a-button type="link" size="small" @click="openEdit(record)">编辑</a-button>
                <a-popconfirm
                  title="确认将密码重置为 123456？"
                  ok-text="重置"
                  cancel-text="取消"
                  @confirm="onResetPwd(record)"
                >
                  <a-button type="link" size="small">重置密码</a-button>
                </a-popconfirm>
                <a-popconfirm
                  v-if="!isAdmin(record)"
                  title="确认删除该用户？删除后不可恢复。"
                  ok-text="删除"
                  ok-type="danger"
                  cancel-text="取消"
                  @confirm="onDelete(record)"
                >
                  <a-button type="link" size="small" danger>删除</a-button>
                </a-popconfirm>
              </a-space>
            </template>
          </template>
        </a-table>
        <a-empty v-if="!loading && !users.length" description="暂无用户" style="margin-top: 24px" />
      </div>
    </a-card>

    <a-modal
      v-model:open="createOpen"
      title="新建用户"
      :confirm-loading="saving"
      ok-text="创建"
      cancel-text="取消"
      destroy-on-close
      @ok="onCreate"
    >
      <a-alert
        type="info"
        show-icon
        style="margin-bottom: 16px"
        message="角色固定为普通用户（USER），初始密码 123456。"
      />
      <a-form layout="vertical" :model="createForm">
        <a-form-item label="账号" required>
          <a-input v-model:value="createForm.username" placeholder="登录账号，2～64 字符" allow-clear />
        </a-form-item>
        <a-form-item label="显示名">
          <a-input v-model:value="createForm.displayName" placeholder="不填则与账号相同" allow-clear />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal
      v-model:open="editOpen"
      title="编辑用户"
      :confirm-loading="saving"
      ok-text="保存"
      cancel-text="取消"
      destroy-on-close
      @ok="onEdit"
    >
      <a-form layout="vertical" :model="editForm">
        <a-form-item label="账号">
          <a-input :value="editForm.username" disabled />
        </a-form-item>
        <a-form-item label="显示名" required>
          <a-input v-model:value="editForm.displayName" placeholder="显示名" allow-clear />
        </a-form-item>
        <a-form-item label="状态">
          <a-radio-group v-if="!editForm.systemAdmin" v-model:value="editForm.status">
            <a-radio value="ACTIVE">启用</a-radio>
            <a-radio value="DISABLED">停用</a-radio>
          </a-radio-group>
          <span v-else class="status-locked">启用（系统管理员不可停用）</span>
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import type { TablePaginationConfig } from 'ant-design-vue'
import { message } from 'ant-design-vue'
import { authApi } from '@/api'
import type { SysUserItem } from '@/api/types'
import FullscreenExitFilterBtn from '@/components/FullscreenExitFilterBtn.vue'
import PageHelpTip from '@/components/PageHelpTip.vue'

const loading = ref(false)
const saving = ref(false)
const importing = ref(false)
/** 状态开关请求中的用户 id，防连点 */
const statusSavingId = ref<number | null>(null)
const users = ref<SysUserItem[]>([])

const filter = reactive({
  username: '',
  displayName: '',
  role: undefined as string | undefined,
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

const createOpen = ref(false)
const editOpen = ref(false)
const createForm = reactive({ username: '', displayName: '' })
const editForm = reactive({
  id: 0,
  username: '',
  displayName: '',
  status: 'ACTIVE',
  systemAdmin: false
})

const columns = [
  { title: '账号', dataIndex: 'username', key: 'username', width: 120 },
  { title: '显示名', dataIndex: 'displayName', key: 'displayName', width: 140 },
  { title: '角色', key: 'role', width: 120 },
  { title: '状态', key: 'status', width: 110 },
  { title: '创建时间', dataIndex: 'createTime', key: 'createTime', width: 170 },
  { title: '更新时间', dataIndex: 'updateTime', key: 'updateTime', width: 170 },
  { title: '操作', key: 'action', width: 220 }
]

function isAdmin(record: SysUserItem) {
  return !!record.systemAdmin || (record.role || '').toUpperCase() === 'ADMIN'
}

function roleLabel(role: string) {
  const r = (role || '').toUpperCase()
  if (r === 'ADMIN') return '系统管理员'
  if (r === 'USER') return '普通用户'
  return role
}

/** 去掉 ISO 中的 T，统一为 yyyy-MM-dd HH:mm:ss */
function formatTime(value?: string) {
  if (!value) return '-'
  return value.replace('T', ' ').replace(/\.\d+.*$/, '').slice(0, 19)
}

function queryParams() {
  return {
    username: filter.username.trim() || undefined,
    displayName: filter.displayName.trim() || undefined,
    role: filter.role || undefined,
    status: filter.status || undefined
  }
}

async function load() {
  loading.value = true
  try {
    const res = await authApi.listUsers({
      page: pagination.current,
      size: pagination.pageSize,
      ...queryParams()
    })
    users.value = res?.items || []
    pagination.total = res?.total || 0
  } catch {
    users.value = []
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

function openCreate() {
  createForm.username = ''
  createForm.displayName = ''
  createOpen.value = true
}

function openEdit(record: SysUserItem) {
  editForm.id = record.id
  editForm.username = record.username
  editForm.displayName = record.displayName || ''
  editForm.status = record.status || 'ACTIVE'
  editForm.systemAdmin = isAdmin(record)
  editOpen.value = true
}

async function onCreate() {
  const username = createForm.username.trim()
  if (!username) {
    message.warning('请输入账号')
    return Promise.reject()
  }
  saving.value = true
  try {
    await authApi.createUser({
      username,
      displayName: createForm.displayName.trim() || undefined
    })
    message.success('创建成功，默认密码 123456')
    createOpen.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function onEdit() {
  const displayName = editForm.displayName.trim()
  if (!displayName) {
    message.warning('请输入显示名')
    return Promise.reject()
  }
  saving.value = true
  try {
    const body: { displayName: string; status?: string } = { displayName }
    if (!editForm.systemAdmin) {
      body.status = editForm.status
    }
    await authApi.updateUser(editForm.id, body)
    message.success('已更新')
    editOpen.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function onStatusChange(record: SysUserItem, checked: boolean) {
  if (isAdmin(record)) {
    message.warning('系统管理员不可停用')
    return
  }
  const next = checked ? 'ACTIVE' : 'DISABLED'
  if (record.status === next) return
  statusSavingId.value = record.id
  try {
    await authApi.updateUser(record.id, { status: next })
    message.success(next === 'ACTIVE' ? '已启用' : '已停用')
    await load()
  } finally {
    statusSavingId.value = null
  }
}

async function onResetPwd(record: SysUserItem) {
  await authApi.resetPassword(record.id)
  message.success(`已重置 ${record.username} 的密码为 123456`)
  await load()
}

async function onDelete(record: SysUserItem) {
  await authApi.deleteUser(record.id)
  message.success('已删除')
  await load()
}

async function onDownloadTemplate() {
  try {
    await authApi.downloadTemplate()
  } catch {
    // message already shown
  }
}

async function onExport() {
  try {
    await authApi.exportCurrent(queryParams())
  } catch {
    // message already shown
  }
}

function onImport(file: File) {
  importing.value = true
  authApi
    .importExcel(file)
    .then((res) => {
      const failHint = res.failCount > 0 ? `，失败 ${res.failCount} 行` : ''
      message.success(`导入成功 ${res.successCount} 行${failHint}`)
      if (res.errors?.length) {
        message.warning(res.errors.slice(0, 3).join('；'))
      }
      return load()
    })
    .catch(() => {
      // toast by interceptor
    })
    .finally(() => {
      importing.value = false
    })
  return false
}

onMounted(load)
</script>

<style scoped>
.status-locked {
  color: var(--ui-text-strong, #000);
  font-size: 12px;
}
</style>
