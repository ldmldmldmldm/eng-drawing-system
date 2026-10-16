<template>
  <div class="page-list">
    <a-card size="small" title="运行日志">
      <template #extra>
        <a-space wrap :size="6">
          <a-tooltip title="说明">
            <a-button type="text" size="small" class="jy-page-help-btn" aria-label="说明" @click="helpOpen = true">
              <QuestionCircleOutlined />
            </a-button>
          </a-tooltip>
          <a-tag color="orange">仅系统管理员可见</a-tag>
          <a-tag :color="streaming ? 'green' : 'default'">
            {{ streaming ? '实时跟随中' : '未跟随' }}
          </a-tag>
          <FullscreenExitFilterBtn />
        </a-space>
      </template>

      <a-form layout="inline" class="list-filter-form" size="small" :model="filter" style="margin-bottom: 12px">
        <a-form-item label="日志源">
          <a-select v-model:value="filter.source" size="small" style="width: 140px">
            <a-select-option value="all">全量</a-select-option>
            <a-select-option value="error">ERROR</a-select-option>
            <a-select-option value="biz">业务摘要</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="级别">
          <a-select v-model:value="filter.level" size="small" style="width: 120px">
            <a-select-option value="ALL">全部</a-select-option>
            <a-select-option value="ERROR">ERROR</a-select-option>
            <a-select-option value="WARN">WARN</a-select-option>
            <a-select-option value="INFO">INFO</a-select-option>
            <a-select-option value="DEBUG">DEBUG</a-select-option>
            <a-select-option value="TRACE">TRACE</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="关键词">
          <a-input
            v-model:value="filter.keyword"
            size="small"
            allow-clear
            placeholder="rid / job / 钉钉…"
            style="width: 180px"
            @pressEnter="onSearch"
          />
        </a-form-item>
        <a-form-item label="最近行数">
          <a-select v-model:value="filter.lines" size="small" style="width: 100px">
            <a-select-option :value="100">100</a-select-option>
            <a-select-option :value="200">200</a-select-option>
            <a-select-option :value="500">500</a-select-option>
            <a-select-option :value="1000">1000</a-select-option>
            <a-select-option :value="2000">2000</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item>
          <a-space wrap :size="6">
            <a-button type="primary" size="small" :loading="loading" :disabled="streaming" @click="onSearch">
              查询
            </a-button>
            <a-button v-if="!streaming" size="small" @click="startStream">实时跟随</a-button>
            <a-button v-else size="small" danger @click="stopStream">停止跟随</a-button>
            <a-button size="small" @click="clearLines">清空显示</a-button>
          </a-space>
        </a-form-item>
      </a-form>

      <div class="log-toolbar">
        <span class="meta">{{ metaText }}</span>
        <a-checkbox v-model:checked="autoScroll">自动滚底</a-checkbox>
      </div>

      <div ref="panelRef" class="log-panel" @scroll="onPanelScroll">
        <div v-if="!displayLines.length && !loading" class="log-empty">暂无日志行</div>
        <div
          v-for="(line, idx) in displayLines"
          :key="idx"
          class="log-line"
          :class="levelClass(line)"
          v-html="renderLineHtml(line)"
        />
      </div>
    </a-card>

    <a-modal
      v-model:open="helpOpen"
      title="运行日志检索说明"
      :footer="null"
      width="720px"
      destroy-on-close
    >
      <a-alert
        type="info"
        show-icon
        style="margin-bottom: 12px"
        message="应用运行日志（文件）用于排查异常，与「操作日志」业务审计不同。点击下方关键词可填入并查询。排障建议：先选 ERROR 日志源看报错，再用 rid / job 串一次请求或推送任务。"
      />
      <a-table
        size="middle"
        bordered
        :pagination="false"
        :columns="helpColumns"
        :data-source="helpRows"
        row-key="scene"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'keywords'">
            <a-space wrap>
              <a-tag
                v-for="kw in record.keywords"
                :key="kw"
                color="blue"
                class="kw-chip"
                @click="applyKeyword(kw)"
              >
                {{ kw }}
              </a-tag>
            </a-space>
          </template>
          <template v-else-if="column.key === 'tip'">
            <span class="help-tip">{{ record.tip }}</span>
          </template>
        </template>
      </a-table>
      <div class="help-footer">
        日志源说明：<b>全量</b>含各级 INFO/WARN/ERROR；<b>ERROR</b>只盯异常；
        <b>业务摘要</b>仅含带标签的关键行（如 [审计]/[任务]/[钉钉]）。
      </div>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { QuestionCircleOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import { runtimeLogApi, type RuntimeLogLevel, type RuntimeLogSource } from '@/api'
import { useUserStore } from '@/stores/user'
import FullscreenExitFilterBtn from '@/components/FullscreenExitFilterBtn.vue'

const MAX_BUFFER = 5000

const userStore = useUserStore()
const loading = ref(false)
const streaming = ref(false)
const autoScroll = ref(true)
const stickToBottom = ref(true)
const lines = ref<string[]>([])
const metaText = ref('')
const panelRef = ref<HTMLElement | null>(null)
const helpOpen = ref(false)

const filter = reactive({
  source: 'all' as RuntimeLogSource,
  level: 'ALL' as RuntimeLogLevel,
  keyword: '',
  lines: 200
})

let stopStreamFn: (() => void) | null = null

const displayLines = computed(() => lines.value)
const activeKeyword = computed(() => filter.keyword?.trim() || '')

const helpColumns = [
  { title: '排查场景', dataIndex: 'scene', key: 'scene', width: 150 },
  { title: '推荐关键词（点击填入）', key: 'keywords' },
  { title: '说明', dataIndex: 'tip', key: 'tip' }
]

const helpRows = [
  {
    scene: '串一次页面请求',
    keywords: ['rid=', 'X-Request-Id'],
    tip: '从前端 Network 响应头复制 X-Request-Id，或搜 rid= 后半段 UUID'
  },
  {
    scene: '钉钉推送 / 队列',
    keywords: ['[任务]', '[钉钉]', 'job=', 'fireKey=', 'jobId='],
    tip: '定时扫描、入队、Worker 执行、渲染图失败多在此'
  },
  {
    scene: '登录 / 权限',
    keywords: ['[鉴权]', '[审计]', '登录', 'Token'],
    tip: '未登录、非管理员访问、登录成功/失败'
  },
  {
    scene: '产量 / SQL Server',
    keywords: ['[SQL Server]', 'Mock', 'sqlserver'],
    tip: '连不上产量库、回退 Mock、只读查询异常'
  },
  {
    scene: '日产出备注 / 目标',
    keywords: ['[审计]', '备注', '目标'],
    tip: '备注增改、目标增改清除；也可到「操作日志」按业务键查'
  },
  {
    scene: '用户 / 项目配置',
    keywords: ['[审计]', '用户', '项目配置'],
    tip: '用户 CRUD、导入导出、项目配置变更'
  },
  {
    scene: '系统启动 / 通用异常',
    keywords: ['[系统]', 'ERROR', 'Exception'],
    tip: '启动摘要、读日志失败等；级别可先筛 ERROR'
  }
]

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

function escapeRegExp(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

function renderLineHtml(line: string): string {
  const safe = escapeHtml(line)
  const kw = activeKeyword.value
  if (!kw) return safe
  const re = new RegExp(`(${escapeRegExp(kw)})`, 'gi')
  return safe.replace(re, '<mark class="kw-hit">$1</mark>')
}

function levelClass(line: string): string {
  if (/\bERROR\b/.test(line)) return 'is-error'
  if (/\bWARN\b/.test(line)) return 'is-warn'
  if (/\bINFO\b/.test(line)) return 'is-info'
  if (/\bDEBUG\b/.test(line) || /\bTRACE\b/.test(line)) return 'is-debug'
  return ''
}

function appendLines(newLines: string[]) {
  if (!newLines.length) return
  const merged = lines.value.concat(newLines)
  lines.value = merged.length > MAX_BUFFER ? merged.slice(merged.length - MAX_BUFFER) : merged
  if (autoScroll.value && stickToBottom.value) {
    nextTick(scrollToBottom)
  }
}

function scrollToBottom() {
  const el = panelRef.value
  if (!el) return
  el.scrollTop = el.scrollHeight
}

function onPanelScroll() {
  const el = panelRef.value
  if (!el) return
  stickToBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight < 48
}

function clearLines() {
  lines.value = []
}

function queryParams() {
  return {
    source: filter.source,
    lines: filter.lines,
    level: filter.level,
    keyword: filter.keyword?.trim() || undefined
  }
}

function applyKeyword(kw: string) {
  filter.keyword = kw
  helpOpen.value = false
  if (!streaming.value) {
    onSearch()
  } else {
    message.info('已填入关键词；请停止跟随后再查询，或重新开启实时跟随以应用筛选')
  }
}

async function onSearch() {
  if ((userStore.role || '').toUpperCase() !== 'ADMIN') {
    message.warning('无权限访问该功能')
    return
  }
  stopStream()
  loading.value = true
  try {
    const res = await runtimeLogApi.tail(queryParams())
    lines.value = res.lines || []
    const sizeKb = res.fileSize ? `${(res.fileSize / 1024).toFixed(1)} KB` : '0 KB'
    metaText.value = res.exists
      ? `${res.fileName} · ${sizeKb} · 返回 ${res.lineCount} 行`
      : res.message || `${res.fileName} 不存在`
    if (res.message && res.exists) {
      message.info(res.message)
    } else if (!res.exists) {
      message.warning(res.message || '日志文件不存在')
    }
    await nextTick()
    scrollToBottom()
  } catch {
    // toast by interceptor
  } finally {
    loading.value = false
  }
}

function startStream() {
  if ((userStore.role || '').toUpperCase() !== 'ADMIN') {
    message.warning('无权限访问该功能')
    return
  }
  stopStream()
  lines.value = []
  streaming.value = true
  metaText.value = '正在连接实时日志…'
  stopStreamFn = runtimeLogApi.openStream(queryParams(), {
    onMeta: (text) => {
      metaText.value = text
    },
    onStatus: (text) => {
      appendLines([`── ${text} ──`])
    },
    onLog: (line) => {
      appendLines([line])
    },
    onError: (err) => {
      message.error(err.message || '实时日志中断')
      streaming.value = false
      stopStreamFn = null
    },
    onDone: () => {
      streaming.value = false
      stopStreamFn = null
      if (!metaText.value.includes('已停止')) {
        metaText.value = (metaText.value ? metaText.value + ' · ' : '') + '已停止'
      }
    }
  })
}

function stopStream() {
  if (stopStreamFn) {
    stopStreamFn()
    stopStreamFn = null
  }
  streaming.value = false
}

onMounted(() => {
  onSearch()
})

onBeforeUnmount(() => {
  stopStream()
})
</script>

<style scoped>
.log-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
  font-size: 12px;
  color: #667085;
}

.log-toolbar .meta {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.log-panel {
  height: calc(100vh - 280px);
  min-height: 360px;
  overflow: auto;
  border: 1px solid var(--jy-border);
  border-radius: 8px;
  background: #0b1220;
  color: #d0d5dd;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', monospace;
  font-size: 12px;
  line-height: 1.55;
  padding: 10px 12px;
}

.log-empty {
  color: #98a2b3;
  padding: 24px 0;
  text-align: center;
}

.log-line {
  white-space: pre-wrap;
  word-break: break-all;
}

.log-line.is-error {
  color: #f97066;
}

.log-line.is-warn {
  color: #f5a623;
}

.log-line.is-info {
  color: #d0d5dd;
}

.log-line.is-debug {
  color: #98a2b3;
}

.log-line :deep(.kw-hit) {
  background: #f5a623;
  color: #0b1220;
  border-radius: 2px;
  padding: 0 2px;
  font-weight: 600;
}

.kw-chip {
  cursor: pointer;
}

.help-tip {
  color: #667085;
  font-size: 13px;
}

.help-footer {
  margin-top: 12px;
  color: #667085;
  font-size: 13px;
  line-height: 1.6;
}
</style>
