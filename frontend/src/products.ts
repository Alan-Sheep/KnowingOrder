/** 商品查询独立契约，后续真实适配器保留同样的响应结构。 */
export interface ProductItem {
  id: string;
  title: string;
  merchant: string;
  referencePrice: number;
  currency: 'CNY';
  linkType: 'PLATFORM_HOME';
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

/** 当前只允许固定演示目的地；接入真实渠道时评审专属白名单，不能开放任意URL。 */
export function productJumpUrl(value: unknown): string | undefined {
  return value === 'https://www.taobao.com/' ? value : undefined;
}

export function validateProducts(value: unknown): value is ProductSearchResult {
  return record(value) && value.provider === 'MOCK' && value.simulated === true
    && typeof value.notice === 'string' && value.notice.length > 0
    && Array.isArray(value.items) && value.items.length <= 10
    && value.items.every(item => record(item)
      && ['id', 'title', 'merchant'].every(key => typeof item[key] === 'string' && item[key].length > 0)
      && typeof item.referencePrice === 'number' && Number.isFinite(item.referencePrice)
      && item.referencePrice >= 0 && item.currency === 'CNY'
      && item.linkType === 'PLATFORM_HOME' && productJumpUrl(item.jumpUrl) !== undefined)
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
