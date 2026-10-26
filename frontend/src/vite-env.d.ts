/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 设为 "true" 时前端才展示公开联网问答页；需后端同时放开 /api/chat。 */
  readonly VITE_PUBLIC_CHAT?: string;
}
