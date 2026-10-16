<script setup lang="ts">
/**
 * ⑤ 生成：左栏产物总览（2×2）+ 分步全量详情。
 * 表：区域内纵向滚动；图：滚轮缩放 + 拖动平移（与①图纸视口一致）。
 */
import { computed, nextTick, ref, watch } from 'vue'
import {
  AimOutlined,
  CheckOutlined,
  DragOutlined,
  LeftOutlined,
  WarningOutlined,
  ZoomInOutlined,
  ZoomOutOutlined
} from '@ant-design/icons-vue'
import {
  getEngStackPreview,
  type ConvertMaterialRow,
  type ConvertNoteLine,
  type EngComposeResult,
  type EngStackAssetSummary
} from '@/api'
import type { MaterialWorkRow } from '@/utils/materialCompare'

export type ComposeBoardPane =
  | 'overview'
  | 'extract'
  | 'stack'
  | 'material'
  | 'translate'
  | 'result'

export interface ComposeBoardSummary {
  total: number
  match: number
  conflict: number
  missing3d: number
  missing2d: number
  fieldDiff: number
  unresolved: number
}

const props = defineProps<{
  dwgFileName?: string
  drawingNo?: string
  previewPngBase64?: string
  previewSvg?: string
  previewSvgs?: string[]
  materialCrops?: string[]
  notes: ConvertNoteLine[]
  materials: ConvertMaterialRow[]
  stack: EngStackAssetSummary | null
  /** 任务已跳过②三维层次（无 STP） */
  stackSkipped?: boolean
  /** 向导预拉结果；配合 stackPreviewPrefetched 跳过再请求 */
  stackPreviewPngBase64?: string
  stackPreviewPrefetched?: boolean
  materialSummary: ComposeBoardSummary
  materialWorkRows: MaterialWorkRow[]
  materialsConfirmed: boolean
  notesConfirmed: boolean
  result: EngComposeResult | null
}>()

const emit = defineEmits<{
  goStep: [step: 1 | 2 | 3 | 4]
}>()

const pane = ref<ComposeBoardPane>(props.result ? 'result' : 'overview')
const extractSub = ref<'sheet' | 'notes' | 'materials' | 'crops'>('sheet')
const translateSub = ref<'notes' | 'materials'>('notes')
const stackPreviewSrc = ref('')
const stackPreviewLoading = ref(false)

const composePartial = computed(() => {
  const r = props.result
  if (!r) return false
  const msg = `${r.message || ''} ${(r.warnings || []).join(' ')}`
  return /未能嵌入|没有放进|无法打开|无图元|导入失败|量界失败/.test(msg)
})

/** 图纸/层次/终稿：缩放平移（默认适应视口居中，非原图像素 100%） */
const ZOOM_MIN = 0.05
const ZOOM_MAX = 12
const viewHand = ref(true)
const viewScale = ref(1)
const viewOffsetX = ref(0)
const viewOffsetY = ref(0)
const viewPanning = ref(false)
const viewPortRef = ref<HTMLDivElement | null>(null)
/** 适应视口时的 scale，用于显示「100%」= 适应而非原图像素 */
const fitScale = ref(1)
const sheetPageIndex = ref(0)
let panLastX = 0
let panLastY = 0
let fitRetryTimer = 0

const panes = computed(() => {
  const list: { key: ComposeBoardPane; lab: string }[] = [
    { key: 'overview', lab: '总览' },
    { key: 'extract', lab: '① 抽取' },
    { key: 'stack', lab: '② 层次' },
    { key: 'material', lab: '③ 材料' },
    { key: 'translate', lab: '④ 翻译' }
  ]
  if (props.result) list.push({ key: 'result', lab: '终稿' })
  return list
})

const extractThumb = computed(() => {
  const b64 = props.previewPngBase64
  if (b64) return b64.startsWith('data:') ? b64 : `data:image/png;base64,${b64}`
  return ''
})

const extractPages = computed(() => {
  const svgs = (props.previewSvgs?.length
    ? props.previewSvgs
    : props.previewSvg
      ? [props.previewSvg]
      : []
  ).filter((s) => !!s && s.trim())
  if (svgs.length > 1) return svgs.map((s) => ({ kind: 'svg' as const, content: s }))
  if (extractThumb.value) return [{ kind: 'png' as const, content: extractThumb.value }]
  if (svgs.length === 1) return [{ kind: 'svg' as const, content: svgs[0] }]
  return []
})

const sheetCurrent = computed(() => extractPages.value[sheetPageIndex.value] || null)

const cropSrcs = computed(() =>
  (props.materialCrops || [])
    .filter((c) => !!c && String(c).trim())
    .map((c) => (c.startsWith('data:') ? c : `data:image/png;base64,${c}`))
)

const resultThumb = computed(() => {
  const b64 = props.result?.previewPngBase64
  if (b64) return b64.startsWith('data:') ? b64 : `data:image/png;base64,${b64}`
  return ''
})

/** 当前媒体视口内容：图纸页 / 层次图 / 终稿 */
const mediaKind = computed<'png' | 'svg' | null>(() => {
  if (pane.value === 'extract' && extractSub.value === 'sheet') {
    return sheetCurrent.value?.kind || null
  }
  if (pane.value === 'stack' && stackPreviewSrc.value) return 'png'
  if (pane.value === 'result') {
    if (resultThumb.value) return 'png'
    if (props.result?.previewSvg) return 'svg'
  }
  return null
})

const mediaSrc = computed(() => {
  if (pane.value === 'extract' && extractSub.value === 'sheet') {
    return sheetCurrent.value?.content || ''
  }
  if (pane.value === 'stack') return stackPreviewSrc.value
  if (pane.value === 'result') {
    if (resultThumb.value) return resultThumb.value
    return props.result?.previewSvg || ''
  }
  return ''
})

const showMediaView = computed(() => {
  if (pane.value === 'extract' && extractSub.value === 'sheet') return extractPages.value.length > 0
  if (pane.value === 'stack') return !!stackPreviewSrc.value
  if (pane.value === 'result') return !!(resultThumb.value || props.result?.previewSvg)
  return false
})

const viewTransformStyle = computed(() => ({
  transform: `translate(${viewOffsetX.value}px, ${viewOffsetY.value}px) scale(${viewScale.value})`,
  transformOrigin: '0 0'
}))

/** 相对「适应」的百分比，适应时为 100% */
const viewZoomLabel = computed(() => {
  const base = fitScale.value > 1e-6 ? fitScale.value : 1
  return `${Math.round((viewScale.value / base) * 100)}%`
})

const paneHint = computed(() => {
  if (pane.value === 'overview') return '点击卡片查看该步全部产物；顶栏可切换'
  if (pane.value === 'extract') {
    if (extractSub.value === 'sheet') return '默认适应居中 · 滚轮缩放 · 拖动平移'
    return `${props.dwgFileName || '客户 DWG'} · NOTE ${props.notes.length} · 材料 ${props.materials.length}`
  }
  if (pane.value === 'stack') {
    return props.stack
      ? `${props.stack.title} · 默认适应居中 · 可缩放拖动`
      : props.stackSkipped
        ? '已跳过三维层次（无爆炸图）'
        : '尚未保存层次'
  }
  if (pane.value === 'material') {
    const s = props.materialSummary
    return `共 ${s.total} · 一致 ${s.match} · 不一致 ${s.conflict} · 表内可滚动`
  }
  if (pane.value === 'translate') {
    return `NOTE ${props.notes.length} · 材料 ${props.materials.length} · 表内可滚动`
  }
  return '默认适应居中 · 滚轮缩放 · 拖动平移'
})

function measureMediaSize(): { w: number; h: number } | null {
  const vp = viewPortRef.value
  if (!vp) return null
  const img = vp.querySelector('img.cab-viewport-img') as HTMLImageElement | null
  if (img && img.naturalWidth > 0 && img.naturalHeight > 0) {
    return { w: img.naturalWidth, h: img.naturalHeight }
  }
  const svg = vp.querySelector('.cab-viewport-svg svg') as SVGSVGElement | null
  if (svg) {
    try {
      const box = svg.getBBox()
      if (box.width > 0 && box.height > 0) return { w: box.width, h: box.height }
    } catch {
      /* ignore */
    }
    const aw = svg.width?.baseVal?.value || svg.clientWidth || svg.scrollWidth
    const ah = svg.height?.baseVal?.value || svg.clientHeight || svg.scrollHeight
    if (aw > 0 && ah > 0) return { w: aw, h: ah }
  }
  return null
}

/** 整图适应视口并居中（打开详情 / 点复位） */
function fitToView() {
  const vp = viewPortRef.value
  if (!vp) return
  const vw = vp.clientWidth
  const vh = vp.clientHeight
  if (vw < 8 || vh < 8) {
    scheduleFitToView(80)
    return
  }
  const size = measureMediaSize()
  if (!size) {
    scheduleFitToView(80)
    return
  }
  const pad = 0.92
  const s = Math.min(vw / size.w, vh / size.h) * pad
  const scale = Number.isFinite(s) && s > 0 ? s : 1
  fitScale.value = scale
  viewScale.value = scale
  viewOffsetX.value = (vw - size.w * scale) / 2
  viewOffsetY.value = (vh - size.h * scale) / 2
}

function scheduleFitToView(delayMs = 0) {
  if (fitRetryTimer) window.clearTimeout(fitRetryTimer)
  fitRetryTimer = window.setTimeout(() => {
    fitRetryTimer = 0
    void nextTick(() => fitToView())
  }, delayMs)
}

function resetView() {
  fitToView()
}

function clampScale(v: number) {
  return Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, v))
}

function zoomAt(factor: number, clientX?: number, clientY?: number) {
  const el = viewPortRef.value
  const prev = viewScale.value
  const next = clampScale(prev * factor)
  if (next === prev) return
  if (el && clientX != null && clientY != null) {
    const rect = el.getBoundingClientRect()
    const vx = clientX - rect.left
    const vy = clientY - rect.top
    const contentX = (vx - viewOffsetX.value) / prev
    const contentY = (vy - viewOffsetY.value) / prev
    viewScale.value = next
    viewOffsetX.value = vx - contentX * next
    viewOffsetY.value = vy - contentY * next
  } else {
    viewScale.value = next
  }
}

function zoomIn() {
  zoomAt(1.25)
}

function zoomOut() {
  zoomAt(1 / 1.25)
}

function onViewWheel(e: WheelEvent) {
  if (!showMediaView.value) return
  e.preventDefault()
  zoomAt(e.deltaY < 0 ? 1.12 : 1 / 1.12, e.clientX, e.clientY)
}

function onViewPointerDown(e: PointerEvent) {
  if (!viewHand.value || !showMediaView.value) return
  if (e.button !== 0) return
  viewPanning.value = true
  panLastX = e.clientX
  panLastY = e.clientY
  ;(e.currentTarget as HTMLElement).setPointerCapture?.(e.pointerId)
}

function onViewPointerMove(e: PointerEvent) {
  if (!viewPanning.value) return
  viewOffsetX.value += e.clientX - panLastX
  viewOffsetY.value += e.clientY - panLastY
  panLastX = e.clientX
  panLastY = e.clientY
}

function onViewPointerUp(e: PointerEvent) {
  if (!viewPanning.value) return
  viewPanning.value = false
  try {
    ;(e.currentTarget as HTMLElement).releasePointerCapture?.(e.pointerId)
  } catch {
    /* ignore */
  }
}

function selectSheetPage(idx: number) {
  if (idx < 0 || idx >= extractPages.value.length) return
  sheetPageIndex.value = idx
  scheduleFitToView(0)
}

function onMediaLoad() {
  scheduleFitToView(0)
}

function applyStackPreviewRaw(raw: string | undefined | null) {
  const v = (raw || '').trim()
  if (v) {
    stackPreviewSrc.value = v.startsWith('data:') ? v : `data:image/png;base64,${v}`
  } else {
    stackPreviewSrc.value = ''
  }
}

let stackPreviewFetchSeq = 0

watch(
  () =>
    [props.stack?.id, props.stackPreviewPrefetched, props.stackPreviewPngBase64] as const,
  async ([id, prefetched, prefetchedPng]) => {
    const seq = ++stackPreviewFetchSeq
    stackPreviewSrc.value = ''
    if (!id) {
      stackPreviewLoading.value = false
      return
    }
    // 向导已预拉：直接用缓存，避免总览闪「无预览图」
    if (prefetched) {
      applyStackPreviewRaw(prefetchedPng)
      stackPreviewLoading.value = false
      return
    }
    stackPreviewLoading.value = true
    try {
      const r = await getEngStackPreview(id)
      if (seq !== stackPreviewFetchSeq) return
      applyStackPreviewRaw(r?.previewPngBase64)
    } catch {
      if (seq !== stackPreviewFetchSeq) return
      stackPreviewSrc.value = ''
    } finally {
      if (seq === stackPreviewFetchSeq) stackPreviewLoading.value = false
    }
  },
  { immediate: true }
)

watch(
  () => props.result,
  (r) => {
    if (r && pane.value === 'overview') pane.value = 'result'
    // result 被清空后勿停在空终稿页
    if (!r && pane.value === 'result') pane.value = 'overview'
  },
  { immediate: true }
)

watch([pane, extractSub, () => stackPreviewSrc.value, () => props.result?.previewPngBase64], () => {
  scheduleFitToView(30)
})

function openPane(p: ComposeBoardPane) {
  pane.value = p
  if (p === 'extract') extractSub.value = 'sheet'
  if (p === 'translate') translateSub.value = 'notes'
  sheetPageIndex.value = 0
  scheduleFitToView(30)
}

function statusLab(status: string) {
  if (status === 'match') return '一致'
  if (status === 'missing_3d') return '缺3D'
  if (status === 'missing_2d') return '缺2D'
  if (status === 'field_diff') return '字段差'
  return status || '—'
}

function dispositionLab(d: string) {
  if (d === 'adopt_3d') return '采用3D'
  if (d === 'adopt_2d') return '采用2D'
  if (d === 'manual') return '手工'
  if (d === 'pending') return '待确认'
  return d || '—'
}
</script>

<template>
  <!-- 绝对铺满 stage-main：保证内部滚动/视口有确定高度 -->
  <div class="cab">
    <div class="cab-bar">
      <span class="cab-lab">产物总览</span>
      <span class="cab-hint">{{ paneHint }}</span>
      <nav class="cab-tabs" aria-label="产物切换">
        <button
          v-for="t in panes"
          :key="t.key"
          type="button"
          class="cab-tab"
          :class="{ on: pane === t.key }"
          @click="openPane(t.key)"
        >
          {{ t.lab }}
        </button>
      </nav>
    </div>

    <!-- 总览 2×2 -->
    <div v-if="pane === 'overview'" class="cab-fill cab-grid">
      <button type="button" class="cab-card" @click="openPane('extract')">
        <header class="cab-card-h">
          <span>① 抽取</span>
          <CheckOutlined class="cab-ok" />
        </header>
        <div class="cab-card-media cab-card-media--dark">
          <img v-if="extractThumb" :src="extractThumb" alt="客户图纸" />
          <div v-else-if="previewSvg" class="cab-svg" v-html="previewSvg" />
          <div v-else class="cab-ph cab-ph--dark">无图纸预览</div>
        </div>
        <p class="cab-card-meta">
          {{ dwgFileName || 'customer.dwg' }} · NOTE {{ notes.length }} · 材料 {{ materials.length }}
        </p>
      </button>

      <button type="button" class="cab-card" @click="openPane('stack')">
        <header class="cab-card-h">
          <span>② 层次</span>
          <CheckOutlined v-if="stack || stackSkipped" class="cab-ok" />
        </header>
        <div class="cab-card-media cab-card-media--dark">
          <div v-if="stackPreviewLoading" class="cab-ph cab-ph--dark">加载层次预览…</div>
          <img v-else-if="stackPreviewSrc" :src="stackPreviewSrc" alt="层次图" />
          <div v-else class="cab-ph cab-ph--dark">
            {{
              stack
                ? '层次已保存（无预览图）'
                : stackSkipped
                  ? '已跳过'
                  : '未保存'
            }}
          </div>
        </div>
        <p class="cab-card-meta">
          <template v-if="stack">{{ stack.plateCount }} 板 · {{ stack.calloutCount }} 旁注</template>
          <template v-else-if="stackSkipped">已跳过（无三维层次）</template>
          <template v-else>—</template>
        </p>
      </button>

      <button type="button" class="cab-card" @click="openPane('material')">
        <header class="cab-card-h">
          <span>③ 材料</span>
          <CheckOutlined v-if="materialsConfirmed" class="cab-ok" />
        </header>
        <div class="cab-card-body">
          <p class="cab-stat">
            <span class="ok">一致 {{ materialSummary.match }}</span>
            <span :class="{ bad: materialSummary.conflict }">不一致 {{ materialSummary.conflict }}</span>
          </p>
          <ul class="cab-mini-list">
            <li v-for="row in materialWorkRows.slice(0, 6)" :key="'m-' + row.noteNo">
              <b>{{ row.noteNo || '—' }}</b>
              {{ row.adopted?.material || '未标注' }}
            </li>
            <li v-if="materialWorkRows.length > 6" class="more">
              还有 {{ materialWorkRows.length - 6 }} 行…
            </li>
          </ul>
        </div>
        <p class="cab-card-meta">共 {{ materialSummary.total }} 行 · 点击查看全部</p>
      </button>

      <button type="button" class="cab-card" @click="openPane('translate')">
        <header class="cab-card-h">
          <span>④ 翻译</span>
          <CheckOutlined v-if="notesConfirmed" class="cab-ok" />
        </header>
        <div class="cab-card-body">
          <ul class="cab-mini-list">
            <li v-for="(n, i) in notes.slice(0, 5)" :key="'n-' + i">
              <b>{{ n.noteNo || i + 1 }}</b>
              {{ n.translated || n.original || '—' }}
            </li>
            <li v-if="notes.length > 5" class="more">还有 {{ notes.length - 5 }} 条 NOTE…</li>
          </ul>
        </div>
        <p class="cab-card-meta">NOTE {{ notes.length }} · 材料 {{ materials.length }} · 点击查看全部</p>
      </button>

      <button
        v-if="result"
        type="button"
        class="cab-card cab-card--result"
        @click="openPane('result')"
      >
        <header class="cab-card-h">
          <span>终稿预览</span>
          <WarningOutlined v-if="composePartial" class="cab-warn-ico" />
          <CheckOutlined v-else class="cab-ok" />
        </header>
        <div class="cab-card-media cab-card-media--dark">
          <img v-if="resultThumb" :src="resultThumb" alt="工程图" />
          <div v-else-if="result.previewSvg" class="cab-svg" v-html="result.previewSvg" />
          <div v-else class="cab-ph cab-ph--dark">已生成，暂无预览图</div>
        </div>
        <p class="cab-card-meta" :class="{ warn: composePartial }">
          {{ result.message || '工程图已生成' }}
        </p>
      </button>
    </div>

    <!-- ① 抽取 -->
    <div v-else-if="pane === 'extract'" class="cab-fill cab-detail">
      <div class="cab-detail-tools">
        <button type="button" class="cab-link" @click="openPane('overview')">
          <LeftOutlined /> 总览
        </button>
        <div class="cab-subtabs">
          <button
            type="button"
            class="cab-sub"
            :class="{ on: extractSub === 'sheet' }"
            @click="extractSub = 'sheet'"
          >
            图纸
          </button>
          <button
            type="button"
            class="cab-sub"
            :class="{ on: extractSub === 'notes' }"
            @click="extractSub = 'notes'"
          >
            NOTE ({{ notes.length }})
          </button>
          <button
            type="button"
            class="cab-sub"
            :class="{ on: extractSub === 'materials' }"
            @click="extractSub = 'materials'"
          >
            材料 ({{ materials.length }})
          </button>
          <button
            type="button"
            class="cab-sub"
            :class="{ on: extractSub === 'crops' }"
            @click="extractSub = 'crops'"
          >
            裁剪 ({{ cropSrcs.length }})
          </button>
        </div>
        <div v-if="extractSub === 'sheet' && showMediaView" class="cab-view-tools">
          <label class="cab-hand" title="手形拖动画布">
            <input v-model="viewHand" type="checkbox" />
            <DragOutlined />
            手形
          </label>
          <button type="button" class="cab-tool-btn" title="放大" @click="zoomIn">
            <ZoomInOutlined />
          </button>
          <button type="button" class="cab-tool-btn" title="缩小" @click="zoomOut">
            <ZoomOutOutlined />
          </button>
          <button type="button" class="cab-tool-btn" title="适应居中" @click="resetView">
            <AimOutlined />
          </button>
          <span class="cab-zoom-lab" title="相对适应视口的比例">{{ viewZoomLabel }}</span>
        </div>
        <button type="button" class="cab-link muted" @click="emit('goStep', 1)">回①修改</button>
      </div>

      <!-- 图纸：缩放+拖动 -->
      <div v-if="extractSub === 'sheet'" class="cab-media-wrap">
        <aside v-if="extractPages.length > 1" class="cab-page-rail" aria-label="图纸分页">
          <button
            v-for="(pg, idx) in extractPages"
            :key="'pg-' + idx"
            type="button"
            class="cab-page-thumb"
            :class="{ on: idx === sheetPageIndex }"
            :title="`第 ${idx + 1} 页`"
            @click="selectSheetPage(idx)"
          >
            {{ idx + 1 }}
          </button>
        </aside>
        <div
          v-if="showMediaView"
          ref="viewPortRef"
          class="cab-viewport cab-viewport--dark"
          :class="{ 'is-hand': viewHand, 'is-panning': viewPanning }"
          @wheel.prevent="onViewWheel"
          @pointerdown="onViewPointerDown"
          @pointermove="onViewPointerMove"
          @pointerup="onViewPointerUp"
          @pointercancel="onViewPointerUp"
        >
          <div class="cab-viewport-canvas" :style="viewTransformStyle">
            <img
              v-if="mediaKind === 'png'"
              class="cab-viewport-img"
              :src="mediaSrc"
              alt="客户图纸"
              draggable="false"
              @load="onMediaLoad"
            />
            <div v-else-if="mediaKind === 'svg'" class="cab-viewport-svg" v-html="mediaSrc" />
          </div>
        </div>
        <p v-else class="cab-empty">无图纸预览图，可回①重新解析</p>
      </div>

      <div v-else-if="extractSub === 'notes'" class="cab-scroll">
        <table class="cab-table">
          <thead>
            <tr>
              <th class="col-no">序号</th>
              <th>原文</th>
              <th>译文</th>
              <th class="col-st">状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(n, i) in notes" :key="'en-' + i">
              <td class="col-no">{{ n.noteNo || i + 1 }}</td>
              <td>{{ n.original || '—' }}</td>
              <td>{{ n.translated || '—' }}</td>
              <td class="col-st">{{ n.status || '—' }}</td>
            </tr>
            <tr v-if="!notes.length">
              <td colspan="4" class="cab-empty-cell">暂无 NOTE</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-else-if="extractSub === 'materials'" class="cab-scroll">
        <table class="cab-table">
          <thead>
            <tr>
              <th class="col-no">序号</th>
              <th>材料(EN)</th>
              <th>材料(ZH)</th>
              <th>颜色</th>
              <th>厚度</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(m, i) in materials" :key="'em-' + i">
              <td class="col-no">{{ m.noteNo || m.seq || i + 1 }}</td>
              <td>{{ m.materialEn || '未标注' }}</td>
              <td>{{ m.materialZh || '—' }}</td>
              <td>{{ m.color || '未标注' }}</td>
              <td>{{ m.thickness || '未标注' }}</td>
            </tr>
            <tr v-if="!materials.length">
              <td colspan="5" class="cab-empty-cell">暂无材料</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-else class="cab-scroll cab-crops">
        <img v-for="(src, i) in cropSrcs" :key="'crop-' + i" :src="src" :alt="`裁剪 ${i + 1}`" />
        <p v-if="!cropSrcs.length" class="cab-empty">无材料裁剪图</p>
      </div>
    </div>

    <!-- ② 层次 -->
    <div v-else-if="pane === 'stack'" class="cab-fill cab-detail">
      <div class="cab-detail-tools">
        <button type="button" class="cab-link" @click="openPane('overview')">
          <LeftOutlined /> 总览
        </button>
        <span class="cab-detail-title">{{
          stack?.title || (stackSkipped ? '已跳过层次' : '层次资产')
        }}</span>
        <div v-if="showMediaView" class="cab-view-tools">
          <label class="cab-hand" title="手形拖动画布">
            <input v-model="viewHand" type="checkbox" />
            <DragOutlined />
            手形
          </label>
          <button type="button" class="cab-tool-btn" title="放大" @click="zoomIn">
            <ZoomInOutlined />
          </button>
          <button type="button" class="cab-tool-btn" title="缩小" @click="zoomOut">
            <ZoomOutOutlined />
          </button>
          <button type="button" class="cab-tool-btn" title="适应居中" @click="resetView">
            <AimOutlined />
          </button>
          <span class="cab-zoom-lab" title="相对适应视口的比例">{{ viewZoomLabel }}</span>
        </div>
        <button type="button" class="cab-link muted" @click="emit('goStep', 2)">回②修改</button>
      </div>
      <div class="cab-media-wrap">
        <div v-if="stackPreviewLoading" class="cab-empty">加载层次预览…</div>
        <div
          v-else-if="showMediaView"
          ref="viewPortRef"
          class="cab-viewport cab-viewport--dark"
          :class="{ 'is-hand': viewHand, 'is-panning': viewPanning }"
          @wheel.prevent="onViewWheel"
          @pointerdown="onViewPointerDown"
          @pointermove="onViewPointerMove"
          @pointerup="onViewPointerUp"
          @pointercancel="onViewPointerUp"
        >
          <div class="cab-viewport-canvas" :style="viewTransformStyle">
            <img
              class="cab-viewport-img"
              :src="mediaSrc"
              alt="层次爆炸图"
              draggable="false"
              @load="onMediaLoad"
            />
          </div>
        </div>
        <div v-else class="cab-ph cab-ph--lg">
          <p v-if="stack">
            {{ stack.title }}<br />
            图号 {{ stack.drawingNo || drawingNo || '—' }} · {{ stack.plateCount }} 板 ·
            {{ stack.calloutCount }} 旁注
          </p>
          <p v-else-if="stackSkipped">已跳过三维层次（无 STP，工程图不含爆炸图）</p>
          <p v-else>尚未保存层次，请回②标注并保存</p>
          <p v-if="!stackSkipped || stack" class="cab-ph-tip">
            无预览 PNG 时可回②打开层次编辑器查看三维爆炸
          </p>
        </div>
      </div>
    </div>

    <!-- ③ 材料 -->
    <div v-else-if="pane === 'material'" class="cab-fill cab-detail">
      <div class="cab-detail-tools">
        <button type="button" class="cab-link" @click="openPane('overview')">
          <LeftOutlined /> 总览
        </button>
        <span class="cab-detail-title">
          共 {{ materialSummary.total }} ·
          <span class="ok">一致 {{ materialSummary.match }}</span> ·
          <span :class="{ bad: materialSummary.conflict }"
            >不一致 {{ materialSummary.conflict }}</span
          >
        </span>
        <button type="button" class="cab-link muted" @click="emit('goStep', 3)">回③修改</button>
      </div>
      <div class="cab-scroll">
        <table class="cab-table">
          <thead>
            <tr>
              <th class="col-no">序号</th>
              <th>对照</th>
              <th>处置</th>
              <th>采用材料</th>
              <th>颜色</th>
              <th>厚度</th>
              <th>3D</th>
              <th>2D</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="row in materialWorkRows"
              :key="'mw-' + row.noteNo"
              :class="{ warn: row.status !== 'match' }"
            >
              <td class="col-no">{{ row.noteNo || '—' }}</td>
              <td>{{ statusLab(row.status) }}</td>
              <td>{{ dispositionLab(row.disposition) }}</td>
              <td>{{ row.adopted?.material || '未标注' }}</td>
              <td>{{ row.adopted?.color || '未标注' }}</td>
              <td>{{ row.adopted?.thickness || '未标注' }}</td>
              <td class="muted">
                {{ row.stack?.material || '—' }} / {{ row.stack?.color || '—' }} /
                {{ row.stack?.thickness || '—' }}
              </td>
              <td class="muted">
                {{ row.dwg?.materialEn || row.dwg?.materialZh || '—' }} /
                {{ row.dwg?.color || '—' }} / {{ row.dwg?.thickness || '—' }}
              </td>
            </tr>
            <tr v-if="!materialWorkRows.length">
              <td colspan="8" class="cab-empty-cell">暂无材料对照行</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- ④ 翻译 -->
    <div v-else-if="pane === 'translate'" class="cab-fill cab-detail">
      <div class="cab-detail-tools">
        <button type="button" class="cab-link" @click="openPane('overview')">
          <LeftOutlined /> 总览
        </button>
        <div class="cab-subtabs">
          <button
            type="button"
            class="cab-sub"
            :class="{ on: translateSub === 'notes' }"
            @click="translateSub = 'notes'"
          >
            NOTE ({{ notes.length }})
          </button>
          <button
            type="button"
            class="cab-sub"
            :class="{ on: translateSub === 'materials' }"
            @click="translateSub = 'materials'"
          >
            材料 ({{ materials.length }})
          </button>
        </div>
        <button type="button" class="cab-link muted" @click="emit('goStep', 4)">回④修改</button>
      </div>

      <div v-if="translateSub === 'notes'" class="cab-scroll">
        <table class="cab-table">
          <thead>
            <tr>
              <th class="col-no">序号</th>
              <th>原文</th>
              <th>译文</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(n, i) in notes" :key="'tn-' + i">
              <td class="col-no">{{ n.noteNo || i + 1 }}</td>
              <td>{{ n.original || '—' }}</td>
              <td>{{ n.translated || '—' }}</td>
            </tr>
            <tr v-if="!notes.length">
              <td colspan="3" class="cab-empty-cell">暂无 NOTE</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="cab-scroll">
        <table class="cab-table">
          <thead>
            <tr>
              <th class="col-no">序号</th>
              <th>材料(EN)</th>
              <th>材料(ZH)</th>
              <th>颜色</th>
              <th>厚度</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(m, i) in materials" :key="'tm-' + i">
              <td class="col-no">{{ m.noteNo || m.seq || i + 1 }}</td>
              <td>{{ m.materialEn || '未标注' }}</td>
              <td>{{ m.materialZh || '—' }}</td>
              <td>{{ m.color || '未标注' }}</td>
              <td>{{ m.thickness || '未标注' }}</td>
            </tr>
            <tr v-if="!materials.length">
              <td colspan="5" class="cab-empty-cell">暂无材料</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 终稿 -->
    <div v-else class="cab-fill cab-detail">
      <div class="cab-detail-tools">
        <button type="button" class="cab-link" @click="openPane('overview')">
          <LeftOutlined /> 总览
        </button>
        <span class="cab-detail-title">{{ result?.drawingNo || drawingNo || '工程图' }}</span>
        <div v-if="showMediaView" class="cab-view-tools">
          <label class="cab-hand" title="手形拖动画布">
            <input v-model="viewHand" type="checkbox" />
            <DragOutlined />
            手形
          </label>
          <button type="button" class="cab-tool-btn" title="放大" @click="zoomIn">
            <ZoomInOutlined />
          </button>
          <button type="button" class="cab-tool-btn" title="缩小" @click="zoomOut">
            <ZoomOutOutlined />
          </button>
          <button type="button" class="cab-tool-btn" title="适应居中" @click="resetView">
            <AimOutlined />
          </button>
          <span class="cab-zoom-lab" title="相对适应视口的比例">{{ viewZoomLabel }}</span>
        </div>
      </div>
      <div class="cab-media-wrap">
        <div
          v-if="showMediaView"
          ref="viewPortRef"
          class="cab-viewport cab-viewport--dark"
          :class="{ 'is-hand': viewHand, 'is-panning': viewPanning }"
          @wheel.prevent="onViewWheel"
          @pointerdown="onViewPointerDown"
          @pointermove="onViewPointerMove"
          @pointerup="onViewPointerUp"
          @pointercancel="onViewPointerUp"
        >
          <div class="cab-viewport-canvas" :style="viewTransformStyle">
            <img
              v-if="mediaKind === 'png'"
              class="cab-viewport-img"
              :src="mediaSrc"
              alt="工程图终稿"
              draggable="false"
              @load="onMediaLoad"
            />
            <div v-else-if="mediaKind === 'svg'" class="cab-viewport-svg" v-html="mediaSrc" />
          </div>
        </div>
        <p v-else class="cab-empty">{{ result?.message || '暂无终稿预览' }}</p>
      </div>
      <ul v-if="result?.warnings?.length" class="cab-warns">
        <li v-for="(w, i) in result.warnings" :key="'rw-' + i">{{ w }}</li>
      </ul>
    </div>
  </div>
</template>

<style scoped>
/* 绝对铺满父级 stage-main，切断「被内容撑高 → 外层裁切无滚动」 */
.cab {
  position: absolute;
  inset: 0;
  z-index: 2;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #f3f5f9;
}
.cab-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px;
  border-bottom: 1px solid var(--jy-border, #d0d5dd);
  background: #fff;
  flex-shrink: 0;
  flex-wrap: wrap;
}
.cab-lab {
  font-size: 13px;
  font-weight: 600;
  color: var(--jy-text-strong, #0f172a);
  line-height: 22px;
}
.cab-hint {
  flex: 1;
  min-width: 100px;
  font-size: 12px;
  color: #64748b;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cab-tabs {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}
.cab-tab {
  height: 24px;
  padding: 0 10px;
  border: 1px solid var(--jy-border, #d0d5dd);
  border-radius: 4px;
  background: #fff;
  font-size: 12px;
  font-weight: 600;
  color: #475569;
  cursor: pointer;
}
.cab-tab.on {
  border-color: #1677ff;
  color: #1677ff;
  background: #e6f4ff;
}
.cab-fill {
  flex: 1 1 0%;
  min-height: 0;
  overflow: hidden;
}
.cab-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  grid-auto-rows: minmax(160px, 1fr);
  gap: 10px;
  padding: 10px;
  overflow: auto;
}
.cab-card {
  display: flex;
  flex-direction: column;
  text-align: left;
  border: 1px solid var(--jy-border, #d0d5dd);
  border-radius: 10px;
  background: #fff;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.06);
  padding: 0;
  cursor: pointer;
  overflow: hidden;
  min-height: 0;
}
.cab-card:hover {
  border-color: #1677ff;
}
.cab-card--result {
  grid-column: 1 / -1;
  min-height: 180px;
}
.cab-card-h {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 10px;
  font-size: 13px;
  font-weight: 700;
  border-bottom: 1px solid #eef2f7;
}
.cab-ok {
  color: #1677ff;
  font-size: 14px;
}
.cab-warn-ico {
  color: #d48806;
  font-size: 14px;
}
.cab-card-media {
  flex: 1 1 0%;
  min-height: 80px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f8fafc;
  overflow: hidden;
}
.cab-card-media--dark {
  background: #0b1220;
}
.cab-card-media img {
  max-width: 100%;
  max-height: 140px;
  object-fit: contain;
}
.cab-ph--dark {
  color: #94a3b8;
}
.cab-card-meta.warn {
  color: #b45309;
  font-weight: 600;
}
.cab-card-body {
  flex: 1 1 0%;
  min-height: 0;
  padding: 8px 10px;
  overflow: hidden;
}
.cab-card-meta {
  margin: 0;
  padding: 6px 10px;
  font-size: 12px;
  color: #64748b;
  border-top: 1px solid #eef2f7;
  background: #fafbfd;
}
.cab-stat {
  display: flex;
  gap: 12px;
  margin: 0 0 6px;
  font-size: 12px;
  font-weight: 700;
}
.ok {
  color: #166534;
}
.bad {
  color: #9f1239;
}
.cab-mini-list {
  margin: 0;
  padding: 0;
  list-style: none;
  font-size: 12px;
  color: #334155;
}
.cab-mini-list li {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  line-height: 20px;
}
.cab-mini-list b {
  margin-right: 6px;
  color: #0f172a;
}
.cab-mini-list .more {
  color: #94a3b8;
}
.cab-ph {
  padding: 16px;
  font-size: 12px;
  color: #94a3b8;
  text-align: center;
}
.cab-ph--lg {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #64748b;
  font-size: 13px;
  line-height: 1.5;
  padding: 24px;
}
.cab-ph-tip {
  font-size: 12px;
  color: #94a3b8;
}
.cab-svg {
  width: 100%;
  max-height: 140px;
  overflow: hidden;
}
.cab-svg :deep(svg) {
  max-width: 100%;
  height: auto;
}
.cab-detail {
  display: flex;
  flex-direction: column;
}
.cab-detail-tools {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-bottom: 1px solid var(--jy-border, #d0d5dd);
  background: #fff;
  flex-shrink: 0;
  flex-wrap: wrap;
}
.cab-detail-title {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  font-weight: 600;
  color: #334155;
}
.cab-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 24px;
  padding: 0 8px;
  border: 1px solid var(--jy-border, #d0d5dd);
  border-radius: 4px;
  background: #fff;
  font-size: 12px;
  font-weight: 600;
  color: #1677ff;
  cursor: pointer;
}
.cab-link.muted {
  color: #64748b;
}
.cab-subtabs {
  display: flex;
  gap: 4px;
  flex: 1;
  min-width: 0;
  flex-wrap: wrap;
}
.cab-sub {
  height: 24px;
  padding: 0 10px;
  border: 1px solid transparent;
  border-radius: 4px;
  background: transparent;
  font-size: 12px;
  font-weight: 600;
  color: #64748b;
  cursor: pointer;
}
.cab-sub.on {
  background: #e6f4ff;
  color: #1677ff;
  border-color: #91caff;
}
.cab-view-tools {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.cab-hand {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: #334155;
  cursor: pointer;
  user-select: none;
}
.cab-tool-btn {
  width: 24px;
  height: 24px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--jy-border, #d0d5dd);
  border-radius: 4px;
  background: #fff;
  color: #334155;
  cursor: pointer;
}
.cab-tool-btn:hover {
  border-color: #1677ff;
  color: #1677ff;
}
.cab-zoom-lab {
  font-size: 12px;
  font-weight: 700;
  color: #64748b;
  min-width: 40px;
}
/* 表格滚动区：禁止 padding，否则 sticky 表头上方会透出表体字 */
.cab-scroll {
  flex: 1 1 0%;
  min-height: 0;
  overflow: auto;
  padding: 0;
  background: #fff;
  -webkit-overflow-scrolling: touch;
}
.cab-crops {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-content: flex-start;
  padding: 8px;
}
.cab-crops img {
  max-width: 240px;
  max-height: 180px;
  object-fit: contain;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
}
.cab-table {
  width: 100%;
  border-collapse: separate;
  border-spacing: 0;
  table-layout: fixed;
  font-size: 12.5px;
  font-weight: 700;
  color: var(--jy-text-strong, #0f172a);
}
.cab-table th,
.cab-table td {
  border-right: 1px solid #e2e8f0;
  border-bottom: 1px solid #e2e8f0;
  padding: 6px 8px;
  text-align: left;
  vertical-align: top;
  word-break: break-word;
  background-clip: padding-box;
}
.cab-table th:first-child,
.cab-table td:first-child {
  border-left: 1px solid #e2e8f0;
}
.cab-table tbody tr:first-child td {
  border-top: none;
}
.cab-table thead th {
  position: sticky;
  top: 0;
  z-index: 5;
  /* 与④翻译表一致：实底 + 底边，杜绝滚动透字 */
  background-color: var(--jy-header-bg, #eef1f6);
  background-image: linear-gradient(
    var(--jy-header-bg, #eef1f6),
    var(--jy-header-bg, #eef1f6)
  );
  font-weight: 700;
  color: var(--jy-text-strong, #0f172a);
  border-top: none;
  box-shadow: inset 0 -1px 0 #d0d5dd;
}
.cab-table tbody td {
  position: relative;
  z-index: 0;
  background-color: #fff;
}
.cab-table tbody tr:nth-child(even) td {
  background-color: #fafbfd;
}
.cab-table tbody tr.warn td {
  background-color: #fff1f2;
}
.cab-table .col-no {
  width: 56px;
}
.cab-table .col-st {
  width: 72px;
}
.cab-table .muted {
  font-weight: 400;
  color: #64748b;
  font-size: 12px;
}
.cab-empty,
.cab-empty-cell {
  text-align: center;
  color: #94a3b8;
  font-size: 13px;
  font-weight: 400;
  padding: 24px;
}
.cab-warns {
  flex-shrink: 0;
  margin: 0;
  padding: 6px 12px 8px 28px;
  color: #9f1239;
  font-size: 12px;
  background: #fff;
  border-top: 1px solid #fee2e2;
  max-height: 72px;
  overflow: auto;
}
/* 媒体视口：缩放+拖动 */
.cab-media-wrap {
  flex: 1 1 0%;
  min-height: 0;
  display: flex;
  flex-direction: row;
  overflow: hidden;
  background: #fff;
}
.cab-page-rail {
  flex: 0 0 40px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 6px 4px;
  border-right: 1px solid #e2e8f0;
  background: #f8fafc;
  overflow-y: auto;
}
.cab-page-thumb {
  height: 28px;
  border: 1px solid #d0d5dd;
  border-radius: 4px;
  background: #fff;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
}
.cab-page-thumb.on {
  border-color: #1677ff;
  color: #1677ff;
  background: #e6f4ff;
}
.cab-viewport {
  flex: 1 1 0%;
  min-width: 0;
  min-height: 0;
  position: relative;
  overflow: hidden;
  background: #e8eef6;
  cursor: default;
  touch-action: none;
}
.cab-viewport--dark {
  background: #0b1220;
}
.cab-viewport.is-hand {
  cursor: grab;
}
.cab-viewport.is-panning {
  cursor: grabbing;
}
.cab-viewport-canvas {
  position: absolute;
  left: 0;
  top: 0;
  will-change: transform;
}
.cab-viewport-img {
  display: block;
  max-width: none;
  user-select: none;
  pointer-events: none;
}
.cab-viewport-svg {
  display: block;
  background: #fff;
}
.cab-viewport-svg :deep(svg) {
  display: block;
  max-width: none;
  height: auto;
}
</style>
