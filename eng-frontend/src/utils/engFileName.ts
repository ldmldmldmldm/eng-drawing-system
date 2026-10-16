/** 工程图 DXF 下载文件名：图号-工程图-yyyyMMdd-HHmmss.dxf */

function pad2(n: number): string {
  return String(n).padStart(2, '0')
}

function sanitizeFileToken(raw?: string | null): string {
  const s = String(raw || '')
    .trim()
    .replace(/[\\/:*?"<>|\s]+/g, '_')
    .replace(/_+/g, '_')
    .replace(/^_|_$/g, '')
  return s || 'eng'
}

function formatStamp(when?: Date | string | number | null): string {
  const d = when != null && when !== '' ? new Date(when) : new Date()
  if (Number.isNaN(d.getTime())) {
    return formatStamp(new Date())
  }
  return `${d.getFullYear()}${pad2(d.getMonth() + 1)}${pad2(d.getDate())}-${pad2(d.getHours())}${pad2(d.getMinutes())}${pad2(d.getSeconds())}`
}

export function engineeringDxfFileName(
  drawingNo?: string | null,
  when?: Date | string | number | null
): string {
  return `${sanitizeFileToken(drawingNo)}-工程图-${formatStamp(when)}.dxf`
}
