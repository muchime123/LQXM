/// <reference types="vitest" />
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [
    vue(),
    {
      // 开发期禁用模块资源的 304 协商缓存：浏览器磁盘缓存损坏时会报
      // ERR_CACHE_READ_FAILURE。剥掉条件请求头后 dev server 始终返回 200。
      name: 'no-conditional-dev-cache',
      configureServer(server) {
        server.middlewares.use((req, _res, next) => {
          const url = req.url || ''
          if (!url.startsWith('/api')) {
            delete req.headers['if-none-match']
            delete req.headers['if-modified-since']
          }
          next()
        })
      }
    }
  ],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src')
    }
  },
  optimizeDeps: {
    include: ['vue', 'vue-router', 'axios', 'element-plus', '@element-plus/icons-vue', 'dayjs'],
    exclude: ['chart.js', 'xlsx']
  },
  test: {
    environment: 'happy-dom',
    include: ['src/**/*.{test,spec}.{js,ts}'],
    globals: true
  },
  build: {
    target: 'es2020',
    minify: 'esbuild',
    sourcemap: false,
    chunkSizeWarningLimit: 1000,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('node_modules/vue') || id.includes('node_modules/@vue')) {
            return 'vue-vendor'
          }
          if (id.includes('node_modules/vue-router')) {
            return 'vue-router'
          }
          if (id.includes('node_modules/element-plus')) {
            return 'element-plus'
          }
          if (id.includes('node_modules/@element-plus/icons-vue')) {
            return 'element-icons'
          }
          if (id.includes('node_modules/axios')) {
            return 'axios'
          }
          if (id.includes('node_modules/chart.js')) {
            return 'charts'
          }
          if (id.includes('node_modules/xlsx')) {
            return 'xlsx'
          }
          if (id.includes('node_modules')) {
            return 'vendor'
          }
        }
      }
    }
  },
  server: {
    host: '0.0.0.0',
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': {
        target: process.env.VITE_API_TARGET || 'http://127.0.0.1:8081',
        changeOrigin: true,
        timeout: 600000,
        proxyTimeout: 600000
      }
    },
    allowedHosts: true
  }
})
