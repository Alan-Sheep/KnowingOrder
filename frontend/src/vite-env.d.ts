/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 设为 "true" 时前端才展示公开联网问答页；后端 /api/chat 已常驻，Nginx 侧按 IP 限流。 */
  readonly VITE_PUBLIC_CHAT?: string;
}
