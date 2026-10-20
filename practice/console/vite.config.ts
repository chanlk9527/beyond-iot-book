import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const proxy = {
    '/mqtt': {
      target: env.MQTT_WS_PROXY_TARGET || 'http://127.0.0.1:8084',
      ws: true,
      changeOrigin: false,
    },
    '/api/management': {
      target: env.API_PROXY_TARGET || 'http://127.0.0.1:8080',
      changeOrigin: false,
      ws: true,
    },
  }
  return {
    plugins: [vue()],
    server: {
      port: 5173,
      strictPort: true,
      proxy,
      // Developer pages import the shared guides outside the console root.
      fs: { allow: [fileURLToPath(new URL('.', import.meta.url)), fileURLToPath(new URL('../docs', import.meta.url))] },
    },
    preview: { port: 4173, strictPort: true, proxy },
  }
})
