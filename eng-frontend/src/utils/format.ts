/** 整数千分位展示（如 2100 → 2,100）；null/undefined 返回空串。
 * 报表产量类数字（计划/产出/投入/不良/目标等）一律用本函数，禁止裸整数。 */
export function formatQty(v?: number | null): string {
  if (v == null || Number.isNaN(v)) {
    return ''
  }
  return Math.trunc(v).toLocaleString('en-US')
}

/** 去掉 ISO 中的 T，统一为 yyyy-MM-dd HH:mm:ss */
export function formatTime(value?: string) {
  if (!value) return '-'
  return value.replace('T', ' ').replace(/\.\d+.*$/, '').slice(0, 19)
}
