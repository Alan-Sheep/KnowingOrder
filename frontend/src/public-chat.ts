/**
 * 公开联网问答体验的可用性判定。
 *
 * 这个入口会调用外部联网搜索与外部模型（`/api/chat`），属于开发期验证功能，
 * 后端控制器带 `@Profile("dev")`，生产环境不会注册该接口。
 *
 * 因此生产构建默认把页面切换成"未开启"说明态，而不是让用户点进一个会返回 404 的页面。
 * 需要临时演示时，两件事要同时做：
 *   1) 前端构建时设置 `VITE_PUBLIC_CHAT=true`
 *   2) 后端放开 `/api/chat`（去掉控制器上的 `@Profile("dev")`，并给 Nginx 加限流）
 */
export const publicChatEnabled =
  import.meta.env.DEV || import.meta.env.VITE_PUBLIC_CHAT === 'true';
