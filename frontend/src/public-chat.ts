/**
 * 公开联网问答体验的可用性判定。
 *
 * 这个入口调用外部联网搜索（Tavily）与外部模型（`/api/chat`），是独立于企业问答的公开体验业务，
 * 不检索企业知识。后端接口已放开（无鉴权，由 Nginx 对 /api/chat 按 IP 限流防滥用）。
 *
 * 前端构建时必须设置 `VITE_PUBLIC_CHAT=true`，页面才展示问答界面，
 * 否则切换成"未开启"说明态（避免用户点进一个不可用的页面）。
 */
export const publicChatEnabled =
  import.meta.env.DEV || import.meta.env.VITE_PUBLIC_CHAT === 'true';
