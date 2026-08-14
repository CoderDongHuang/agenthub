import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/runtime-api': {
        target: 'http://localhost:8000',
        changeOrigin: true,
        rewrite: path => path.replace(/^\/runtime-api/, ''),
      },
    },
  },
  build: {
    chunkSizeWarningLimit: 500,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) return
          if (id.includes('element-plus')) return 'element-plus'
          if (id.includes('@element-plus/icons-vue')) return 'element-icons'
          if (id.includes('markdown-it') || id.includes('highlight.js')) return 'markdown'
          if (id.includes('/ogl/') || id.includes('/three/')) return 'graphics'
          if (id.includes('/vue/') || id.includes('vue-router') || id.includes('pinia') || id.includes('vue-i18n')) return 'vue-core'
        },
      },
    },
  },
})
