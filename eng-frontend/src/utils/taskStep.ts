import type { TaskStatus } from '@/api/types'

/** 向导步序文案：与列表 / 工作台顶栏共用（①抽取 → ⑤生成） */
export function taskStepLabel(
  step: number | string | undefined | null,
  status?: TaskStatus | string | null
): string {
  if (status === 'COMPLETED') return '已完成'
  const n = Number(step)
  if (n === 1) return '① 上传抽取'
  if (n === 2) return '② 三维层次'
  if (n === 3) return '③ 材料核对'
  if (n === 4) return '④ 翻译审校'
  if (n === 5) return '⑤ 生成'
  return step == null || step === '' ? '—' : String(step)
}
