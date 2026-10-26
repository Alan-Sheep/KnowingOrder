/** 商品查询独立契约，后续真实适配器保留同样的响应结构。 */
export interface ProductItem {
  id: string;
  title: string;
  merchant: string;
  referencePrice: number;
  currency: 'CNY';
  linkType: 'PLATFORM_HOME' | 'PLATFORM_SEARCH';
  jumpUrl: string;
}

export interface ProductSearchResult {
  provider: 'MOCK';
  simulated: true;
  notice: string;
  items: ProductItem[];
}

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

const HOME_URL = 'https://www.taobao.com/';

/**
 * 只允许两种固定演示目的地：淘宝首页，或带单个 q 参数的淘宝搜索页。
 *
 * 白名单按「协议 + 主机 + 路径 + 查询参数」逐段校验，而不是包含判断或前缀匹配——
 * 前者能被 `https://s.taobao.com.evil.example/` 绕过，后者能被 `?q=1&redirect=...` 绕过。
 * 校验不过一律返回 undefined，调用方不渲染成可点链接，也不做兜底跳转。
 */
export function productJumpUrl(value: unknown): string | undefined {
  if (typeof value !== 'string' || value.length > 320) return undefined;
  let url: URL;
  try { url = new URL(value); } catch { return undefined; }
  // 端口、用户名口令、锚点都不该出现在演示链接里，出现即视为被构造过。
  if (url.protocol !== 'https:' || url.port || url.username || url.password || url.hash) return undefined;
  if (url.hostname === 'www.taobao.com') {
    return url.pathname === '/' && !url.search ? HOME_URL : undefined;
  }
  if (url.hostname !== 's.taobao.com' || url.pathname !== '/search') return undefined;
  const keys = [...url.searchParams.keys()];
  if (keys.length !== 1 || keys[0] !== 'q') return undefined;
  const query = url.searchParams.get('q') ?? '';
  if (!query.trim() || query.length > 80) return undefined;
  return url.toString();
}

/** 校验通过后，linkType 必须与实际地址一致，避免文案和目的地各说各话。 */
function linkMatchesType(item: Record<string, unknown>): boolean {
  const url = productJumpUrl(item.jumpUrl);
  if (url === undefined) return false;
  return item.linkType === (url === HOME_URL ? 'PLATFORM_HOME' : 'PLATFORM_SEARCH');
}

export function validateProducts(value: unknown): value is ProductSearchResult {
  return record(value) && value.provider === 'MOCK' && value.simulated === true
    && typeof value.notice === 'string' && value.notice.length > 0
    && Array.isArray(value.items) && value.items.length <= 10
    && value.items.every(item => record(item)
      && ['id', 'title', 'merchant'].every(key => typeof item[key] === 'string' && item[key].length > 0)
      && typeof item.referencePrice === 'number' && Number.isFinite(item.referencePrice)
      && item.referencePrice >= 0 && item.currency === 'CNY' && linkMatchesType(item))
    && new Set(value.items.map(item => item.id)).size === value.items.length;
}

/** Mock请求不自动重试，十秒内停止等待；未接通后端时不能在前端伪造查询成功。 */
export async function searchProducts(keyword: string, signal?: AbortSignal, timeoutMs = 10000): Promise<ProductSearchResult> {
  const query = keyword.trim();
  if (!query || query.length > 80 || /[\u0000-\u001f\u007f-\u009f]/u.test(query)) {
    throw new Error('请输入1至80个字符的商品关键词，不能包含控制字符。');
  }
  const controller = new AbortController();
  let timedOut = false;
  const abort = () => controller.abort();
  signal?.addEventListener('abort', abort, { once: true });
  if (signal?.aborted) abort();
  const timer = setTimeout(() => { timedOut = true; controller.abort(); }, timeoutMs);
  try {
    const response = await fetch('/api/products/search', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ keyword: query }), signal: controller.signal,
      cache: 'no-store', credentials: 'omit'
    });
    if (!response.ok) {
      if (response.status === 404) throw new Error('商品接口尚未启用，请重启最新版dev后端。');
      if ([401, 403].includes(response.status)) throw new Error('商品入口仅供本机开发访问，当前请求未获允许。');
      throw new Error('商品查询暂不可用，请检查后端后手动重试。');
    }
    const payload: unknown = await response.json().catch(() => null);
    if (!record(payload) || payload.code !== 0 || !validateProducts(payload.data)) {
      throw new Error('商品响应不符合当前契约，已停止展示和跳转，请检查接口版本。');
    }
    return payload.data;
  } catch (error) {
    if (timedOut) throw new Error('商品查询超过10秒，已停止等待；不会自动重复提交。');
    if (signal?.aborted) throw new Error('已停止等待商品查询。');
    if (error instanceof TypeError) throw new Error('无法连接商品服务，请确认dev后端已启动。');
    throw error;
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', abort);
  }
}
