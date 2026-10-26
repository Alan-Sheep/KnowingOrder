/**
 * 生成 v4 UUID。
 *
 * 为什么不直接写 crypto.randomUUID()：这个接口只在**安全上下文**里存在，
 * 也就是 HTTPS、localhost、127.0.0.1。一旦用 IP 走明文 HTTP 部署
 * （演示环境的 http://8.155.137.88:8090 就是这种），crypto.randomUUID
 * 是 undefined，调用即抛 TypeError。
 *
 * 而下面三个组件都在 setup 阶段就调它：
 *   KnowledgeAnswerPanel（第 33 行）、AffairsPanel（第 56 行里的 reactive 初值）、
 *   App.vue 的联网问答。
 * setup 抛异常 → 该组件整棵子树不渲染 → 表现为「企业问答整页空白」
 * 「员工事务只剩静态介绍、登录框和申请区都不见了」。本地 localhost 调试
 * 属于安全上下文，永远复现不出来，所以这个问题只会在大屏演示时炸。
 *
 * 回退用 crypto.getRandomValues——它**不受安全上下文限制**，随机质量与
 * randomUUID 一致；只有它也不存在时才退到 Math.random（老浏览器兜底，
 * 这里生成的只是前端请求标识，不承担加密职责）。
 */
export function uuid(): string {
  const crypto = globalThis.crypto;
  if (crypto && typeof crypto.randomUUID === 'function') {
    try {
      return crypto.randomUUID();
    } catch {
      // 某些浏览器在非安全上下文中保留了函数但调用即报错，继续走回退。
    }
  }
  const bytes = new Uint8Array(16);
  if (crypto && typeof crypto.getRandomValues === 'function') {
    crypto.getRandomValues(bytes);
  } else {
    for (let i = 0; i < bytes.length; i += 1) bytes[i] = Math.floor(Math.random() * 256);
  }
  // 版本位 4 与变体位 10xx（RFC 4122）
  bytes[6] = (bytes[6]! & 0x0f) | 0x40;
  bytes[8] = (bytes[8]! & 0x3f) | 0x80;
  const hex: string[] = [];
  for (let i = 0; i < bytes.length; i += 1) hex.push(bytes[i]!.toString(16).padStart(2, '0'));
  return `${hex.slice(0, 4).join('')}-${hex.slice(4, 6).join('')}-${hex.slice(6, 8).join('')}-${hex.slice(8, 10).join('')}-${hex.slice(10, 16).join('')}`;
}
