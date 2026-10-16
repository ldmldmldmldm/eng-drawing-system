import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'
import type { ProxyOptions } from 'vite'

const apiProxy: ProxyOptions = {
  target: 'http://localhost:6053',
  changeOrigin: true,
  timeout: 0,
  proxyTimeout: 0,
  rewrite: (path) => path.replace(/^\/api/, '')
}

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src')
    }
  },
  server: {
    port: 8893,
    strictPort: false,
    host: true,
    proxy: {
      '/api': apiProxy
    }
  },
  optimizeDeps: {
    // 必须预构建：exclude 后原生 ESM 动态导入拿不到 UMD 入口函数
    include: ['occt-import-js']
  },
  assetsInclude: ['**/*.wasm']
})
