/**
 * 层次图边线：与三维预览同一相机、同一套描边，投影即所见。
 * 优先用 mesh 上已挂的 LineSegments（视口正在画的边），再 project(viewCamera)。
 */
import * as THREE from 'three'

export type DraftSeg = { x1: number; y1: number; x2: number; y2: number }

export type DraftPlate = {
  bodyId: string
  thickness: number
  segments: DraftSeg[]
}

export type DraftingOptions = {
  /** 投影平面坐标放大（便于引线尺度） */
  scale?: number
}

const DEFAULT_SCALE = 100

/**
 * 按当前预览相机投影实体边线（含 Z 展开位移），不做消隐、合并、过滤。
 */
export function extractDraftingPlates(
  meshes: THREE.Mesh[],
  viewCamera: THREE.PerspectiveCamera | THREE.OrthographicCamera,
  options?: DraftingOptions,
): DraftPlate[] {
  const scale = options?.scale ?? DEFAULT_SCALE
  if (!meshes.length) return []

  viewCamera.updateMatrixWorld(true)
  viewCamera.updateProjectionMatrix()

  const out: DraftPlate[] = []
  for (const mesh of meshes) {
    const bodyId = String(mesh.userData.bodyId || '')
    if (!bodyId) continue
    mesh.updateWorldMatrix(true, false)
    const segments = projectMeshEdgesAsPreview(mesh, viewCamera, scale)
    if (!segments.length) continue
    out.push({
      bodyId,
      thickness: Number(mesh.userData.thickness) || 0,
      segments,
    })
  }
  out.sort((a, b) => {
    const ma = meshes.find((m) => String(m.userData.bodyId) === a.bodyId)
    const mb = meshes.find((m) => String(m.userData.bodyId) === b.bodyId)
    const za = Number(ma?.userData.zMin ?? 0)
    const zb = Number(mb?.userData.zMin ?? 0)
    if (za !== zb) return za - zb
    return a.bodyId.localeCompare(b.bodyId)
  })
  return out
}

/**
 * 优先取视口已显示的 LineSegments；否则临时 EdgesGeometry(18)（与建实体时一致）。
 * 坐标：mesh 局部 → 世界（含 position 展开）→ 预览相机 NDC × scale。
 */
function projectMeshEdgesAsPreview(
  mesh: THREE.Mesh,
  camera: THREE.Camera,
  scale: number,
): DraftSeg[] {
  let pos: THREE.BufferAttribute | null = null
  let tempEdge: THREE.BufferGeometry | null = null

  const lineChild = mesh.children.find((c) => c instanceof THREE.LineSegments) as
    | THREE.LineSegments
    | undefined
  if (lineChild?.geometry) {
    pos = lineChild.geometry.getAttribute('position') as THREE.BufferAttribute | null
  }
  if (!pos || pos.count < 2) {
    tempEdge = new THREE.EdgesGeometry(mesh.geometry as THREE.BufferGeometry, 18)
    pos = tempEdge.getAttribute('position') as THREE.BufferAttribute | null
  }
  if (!pos || pos.count < 2) {
    tempEdge?.dispose()
    return []
  }

  const segs: DraftSeg[] = []
  const a = new THREE.Vector3()
  const b = new THREE.Vector3()
  for (let i = 0; i + 1 < pos.count; i += 2) {
    a.fromBufferAttribute(pos, i)
    b.fromBufferAttribute(pos, i + 1)
    mesh.localToWorld(a)
    mesh.localToWorld(b)
    a.project(camera)
    b.project(camera)
    // 仅丢掉完全在远/近裁剪外的段；不裁左右，避免孔/外框被切掉
    if ((a.z < -1 && b.z < -1) || (a.z > 1 && b.z > 1)) continue
    segs.push({
      x1: a.x * scale,
      y1: a.y * scale,
      x2: b.x * scale,
      y2: b.y * scale,
    })
  }
  tempEdge?.dispose()
  return segs
}
