import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

export default defineConfig({
  plugins: [vue()],
  // 前端不读取密钥文件，所有第三方调用均由后端处理。
  envDir: false,
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    proxy: {
      '/api/auth': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
        xfwd: false,
        timeout: 12000,
        proxyTimeout: 11000
      },
      // 问答后台总时限30秒；代理多留传输余量，浏览器仍在35秒内结束等待。
      '^/api/enterprises/[^/]+/knowledge-spaces/[^/]+/answers(?:/|$)': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
        xfwd: false,
        timeout: 36000,
        proxyTimeout: 34000
      },
      '/api/enterprises': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
        xfwd: false,
        timeout: 12000,
        proxyTimeout: 11000
      },
      '/api/products': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
        xfwd: false,
        timeout: 12000,
        proxyTimeout: 11000
      },
      '/api/chat': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
        // 保持回环访问，不添加后端开发保护器明确拒绝的转发头。
        xfwd: false,
        timeout: 105000,
        proxyTimeout: 100000
      }
    }
  }
});
