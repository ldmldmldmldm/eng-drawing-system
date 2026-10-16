/**
 * 3D 层次旁注 vs 2D 抽出材料：按序号对齐，字段不一致标红。
 * 比对用结构化字段，不用译文句子。
 * ③ 核对工作行：对照只读 + 采用结果可编辑 + 处置态。
 */
import type { ConvertMaterialRow } from '@/api/engDrawing'
import type { StackExplodeExportBody } from '@/api/engDrawing'

export type MatchStatus = 'match' | 'missing_3d' | 'missing_2d' | 'field_diff'

/** pending=字段差待确认；其余为已处置 */
export type MaterialDisposition = 'pending' | 'adopt_3d' | 'adopt_2d' | 'manual'

export interface StackCalloutSide {
  noteNo: string
  material: string
  color: string
  thickness: string
  specialReq: string
}

export interface CompareRow {
  noteNo: string
  status: MatchStatus
  diffs: string[]
  stack?: StackCalloutSide
  dwg?: ConvertMaterialRow
}

export interface MaterialAdopted {
  material: string
  color: string
  thickness: string
}

export interface MaterialWorkRow extends CompareRow {
  disposition: MaterialDisposition
  adopted: MaterialAdopted
}

const UNLABELED = '未标注'

export function normalizeNoteNo(raw?: string | number | null): string {
  if (raw == null) return ''
  return String(raw)
    .trim()
    .replace(/^\[|\]$/g, '')
    .replace(/\s+/g, '')
}

function normText(raw?: string | null): string {
  const t = (raw || '').trim()
  if (!t || t === UNLABELED || t.toLowerCase() === 'n/a') return ''
  return t.replace(/\s+/g, ' ').toLowerCase()
}

function normThickness(raw?: string | number | null): string {
  if (raw == null || raw === '') return ''
  const s = String(raw).trim().replace(/mm$/i, '').replace(/\s+/g, '')
  const n = Number(s)
  if (Number.isFinite(n)) return String(Number(n.toFixed(4)))
  return normText(s)
}

function displaySide(raw?: string | null): string {
  const t = (raw || '').trim()
  if (!t || t === UNLABELED) return ''
  return t
}

export function calloutsFromStack(
  callouts?: StackExplodeExportBody['callouts'] | null
): StackCalloutSide[] {
  if (!callouts?.length) return []
  return callouts.map((c) => ({
    noteNo: normalizeNoteNo(c.calloutNo),
    material: (c.material || '').trim() || UNLABELED,
    color: (c.color || '').trim() || UNLABELED,
    thickness:
      c.thickness != null && Number.isFinite(Number(c.thickness))
        ? String(c.thickness)
        : UNLABELED,
    specialReq: (c.specialReq || '').trim()
  }))
}

/** 按序号合并两侧，产出核对行（含标红状态） */
export function buildMaterialCompare(
  stackCallouts: StackCalloutSide[],
  dwgMaterials: ConvertMaterialRow[]
): CompareRow[] {
  const stackMap = new Map<string, StackCalloutSide>()
  for (const s of stackCallouts) {
    if (!s.noteNo) continue
    stackMap.set(s.noteNo, s)
  }
  const dwgMap = new Map<string, ConvertMaterialRow>()
  for (const m of dwgMaterials || []) {
    const no = normalizeNoteNo(m.noteNo || m.seq)
    if (!no) continue
    dwgMap.set(no, m)
  }
  const keys = new Set([...stackMap.keys(), ...dwgMap.keys()])
  const sorted = [...keys].sort((a, b) => {
    const na = Number(a)
    const nb = Number(b)
    if (Number.isFinite(na) && Number.isFinite(nb)) return na - nb
    return a.localeCompare(b)
  })
  return sorted.map((noteNo) => {
    const stack = stackMap.get(noteNo)
    const dwg = dwgMap.get(noteNo)
    if (stack && !dwg) {
      return { noteNo, status: 'missing_2d' as const, diffs: ['2D 材料表缺少该序号'], stack }
    }
    if (!stack && dwg) {
      return { noteNo, status: 'missing_3d' as const, diffs: ['3D 层次缺少该序号'], dwg }
    }
    const diffs: string[] = []
    if (stack && dwg) {
      const sm = normText(stack.material)
      const dm = normText(dwg.materialEn) || normText(dwg.materialZh)
      if (sm && dm && sm !== dm) diffs.push('材料')
      const sc = normText(stack.color)
      const dc = normText(dwg.color)
      if (sc && dc && sc !== dc) diffs.push('颜色')
      const st = normThickness(stack.thickness)
      const dt = normThickness(dwg.thickness)
      if (st && dt && st !== dt) diffs.push('厚度')
    }
    return {
      noteNo,
      status: diffs.length ? ('field_diff' as const) : ('match' as const),
      diffs,
      stack,
      dwg
    }
  })
}

export function hasCompareConflict(rows: CompareRow[]): boolean {
  return rows.some((r) => r.status !== 'match')
}

function adoptedFromDwg(dwg: ConvertMaterialRow): MaterialAdopted {
  return {
    material: displaySide(dwg.materialEn) || displaySide(dwg.materialZh),
    color: displaySide(dwg.color),
    thickness: displaySide(dwg.thickness)
  }
}

function adoptedFromStack(stack: StackCalloutSide): MaterialAdopted {
  return {
    material: displaySide(stack.material),
    color: displaySide(stack.color),
    thickness: displaySide(stack.thickness)
  }
}

/** 默认采用：有 2D 用 2D；仅 3D 用 3D。字段差默认填 2D 但 disposition=pending */
export function defaultDisposition(status: MatchStatus): MaterialDisposition {
  if (status === 'field_diff') return 'pending'
  if (status === 'missing_2d') return 'adopt_3d'
  if (status === 'missing_3d') return 'adopt_2d'
  return 'adopt_2d'
}

export function defaultAdopted(row: CompareRow): MaterialAdopted {
  if (row.dwg) return adoptedFromDwg(row.dwg)
  if (row.stack) return adoptedFromStack(row.stack)
  return { material: '', color: '', thickness: '' }
}

export function createWorkRow(base: CompareRow): MaterialWorkRow {
  return {
    ...base,
    disposition: defaultDisposition(base.status),
    adopted: defaultAdopted(base)
  }
}

export function buildMaterialWorkRows(
  stackCallouts: StackCalloutSide[],
  dwgMaterials: ConvertMaterialRow[]
): MaterialWorkRow[] {
  return buildMaterialCompare(stackCallouts, dwgMaterials).map(createWorkRow)
}

/** 两侧快照是否同键同内容（用于保留手工处置） */
export function sameCompareSides(a: CompareRow, b: CompareRow): boolean {
  if (a.noteNo !== b.noteNo || a.status !== b.status) return false
  const sm = a.stack
  const sn = b.stack
  if (!!sm !== !!sn) return false
  if (sm && sn) {
    if (sm.material !== sn.material || sm.color !== sn.color || sm.thickness !== sn.thickness) {
      return false
    }
  }
  const dm = a.dwg
  const dn = b.dwg
  if (!!dm !== !!dn) return false
  if (dm && dn) {
    if (
      (dm.materialEn || '') !== (dn.materialEn || '') ||
      (dm.materialZh || '') !== (dn.materialZh || '') ||
      (dm.color || '') !== (dn.color || '') ||
      (dm.thickness || '') !== (dn.thickness || '')
    ) {
      return false
    }
  }
  return true
}

/** 合并新旧工作行：两侧未变则保留处置与采用结果 */
export function mergeMaterialWorkRows(
  nextBase: CompareRow[],
  prev: MaterialWorkRow[]
): MaterialWorkRow[] {
  const prevMap = new Map(prev.map((r) => [r.noteNo, r]))
  return nextBase.map((base) => {
    const old = prevMap.get(base.noteNo)
    if (old && sameCompareSides(old, base)) {
      return {
        ...base,
        disposition: old.disposition,
        adopted: { ...old.adopted }
      }
    }
    return createWorkRow(base)
  })
}

export function applyAdopt3d(row: MaterialWorkRow): boolean {
  if (!row.stack) return false
  row.adopted = adoptedFromStack(row.stack)
  row.disposition = 'adopt_3d'
  return true
}

export function applyAdopt2d(row: MaterialWorkRow): boolean {
  if (!row.dwg) return false
  row.adopted = adoptedFromDwg(row.dwg)
  row.disposition = 'adopt_2d'
  return true
}

export function markManual(row: MaterialWorkRow) {
  row.disposition = 'manual'
}

/** 字段差未点选处置则未解决；缺侧已有默认处置视为已解决 */
export function isWorkRowResolved(row: MaterialWorkRow): boolean {
  if (row.status === 'match') return true
  if (row.status === 'field_diff') return row.disposition !== 'pending'
  return row.disposition === 'adopt_2d' || row.disposition === 'adopt_3d' || row.disposition === 'manual'
}

export function unresolvedWorkRows(rows: MaterialWorkRow[]): MaterialWorkRow[] {
  return rows.filter((r) => !isWorkRowResolved(r))
}

export function workRowsToMaterials(rows: MaterialWorkRow[]): ConvertMaterialRow[] {
  return rows.map((r, idx) => {
    const en = (r.adopted.material || r.dwg?.materialEn || '').trim()
    // 中文只取抽取侧真译文；禁止用 adopted.material（英文）回填，否则④会误判「已译」
    const zhRaw = (r.dwg?.materialZh || '').trim()
    const zh = zhRaw && zhRaw !== en ? zhRaw : ''
    return {
      seq: idx + 1,
      noteNo: r.noteNo,
      materialEn: en || r.dwg?.materialEn || '',
      materialZh: zh,
      color: r.adopted.color,
      thickness: r.adopted.thickness,
      source: r.disposition === 'adopt_3d' ? 'stack' : r.dwg?.source || 'dwg',
      role: r.dwg?.role
    }
  })
}

export function formatSideTriple(
  material?: string | null,
  color?: string | null,
  thickness?: string | null
): string {
  const m = displaySide(material) || '—'
  const c = displaySide(color) || '—'
  const t = displaySide(thickness) || '—'
  return `${m} / ${c} / ${t}`
}
