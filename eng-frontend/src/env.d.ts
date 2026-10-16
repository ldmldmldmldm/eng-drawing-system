/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>
  export default component
}

declare module 'occt-import-js' {
  interface OcctMesh {
    color?: number[]
    attributes: {
      position: { array: ArrayLike<number> }
      normal?: { array: ArrayLike<number> }
    }
    index: { array: ArrayLike<number> }
  }
  interface OcctModule {
    ReadStepFile(
      content: Uint8Array,
      params: Record<string, unknown> | null
    ): { success: boolean; meshes: OcctMesh[] }
  }
  export default function occtimportjs(args?: {
    locateFile?: (file: string) => string
  }): Promise<OcctModule>
}

declare module 'occt-import-js/dist/occt-import-js.wasm?url' {
  const url: string
  export default url
}
