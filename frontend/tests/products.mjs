import test from 'node:test';
import assert from 'node:assert/strict';
import { productJumpUrl, searchProducts, validateProducts } from '../src/products.ts';

const fixture = () => ({ provider: 'MOCK', simulated: true, notice: '模拟数据，跳转首页。', items: [{
  id: 'mock-coffee', title: '示例咖啡', merchant: '虚构门店', referencePrice: 12,
  currency: 'CNY', linkType: 'PLATFORM_HOME', jumpUrl: 'https://www.taobao.com/'
}] });

test('契约明确模拟来源，空结果合法，损坏和未知来源拒绝展示', () => {
  assert.equal(validateProducts(fixture()), true);
  assert.equal(validateProducts({ ...fixture(), items: [] }), true);
  for (const value of [null, {}, { ...fixture(), simulated: false }, { ...fixture(), provider: 'TAOBAO' },
    { ...fixture(), items: [...fixture().items, ...fixture().items] },
    { ...fixture(), items: [{ ...fixture().items[0], referencePrice: NaN }] },
    { ...fixture(), items: [{ ...fixture().items[0], jumpUrl: 'https://evil.example/' }] }]) {
    assert.equal(validateProducts(value), false);
  }
});

test('跳转只允许淘宝首页或带单个q参数的搜索页，不接受脚本、凭据、相似域名或夹带参数', () => {
  assert.equal(productJumpUrl('https://www.taobao.com/'), 'https://www.taobao.com/');
  // 搜索页：关键词允许百分号编码，结果与输入等价即可。
  assert.equal(productJumpUrl('https://s.taobao.com/search?q=%E7%BE%8E%E5%BC%8F%E5%92%96%E5%95%A1'),
    'https://s.taobao.com/search?q=%E7%BE%8E%E5%BC%8F%E5%92%96%E5%95%A1');
  for (const value of [null, 'javascript:alert(1)', '//www.taobao.com', 'http://www.taobao.com/',
    'https://www.taobao.com.evil.example/', 'https://secret@www.taobao.com/',
    'https://www.taobao.com/redirect?url=https://evil.example',
    'https://s.taobao.com.evil.example/search?q=%E5%92%96%E5%95%A1',
    'http://s.taobao.com/search?q=%E5%92%96%E5%95%A1',
    'https://s.taobao.com:8443/search?q=%E5%92%96%E5%95%A1',
    'https://s.taobao.com/search?q=%E5%92%96%E5%95%A1&redirect=https://evil.example',
    'https://s.taobao.com/search?q=%E5%92%96%E5%95%A1&q=%E5%A5%B6%E8%8C%B6',
    'https://s.taobao.com/search?q=',
    'https://s.taobao.com/search?q=%20%20',
    'https://s.taobao.com/search?q=%E5%92%96%E5%95%A1#frag',
    'https://s.taobao.com/item?q=%E5%92%96%E5%95%A1']) assert.equal(productJumpUrl(value), undefined);
});

test('linkType 必须与实际地址一致，不能文案说搜索、链接却回首页', () => {
  const home = fixture().items[0];
  assert.equal(validateProducts({ ...fixture(), items: [{ ...home, linkType: 'PLATFORM_SEARCH' }] }), false);
  assert.equal(validateProducts({ ...fixture(), items: [{ ...home, linkType: 'PLATFORM_HOME',
    jumpUrl: 'https://s.taobao.com/search?q=%E5%92%96%E5%95%A1' }] }), false);
  assert.equal(validateProducts({ ...fixture(), items: [{ ...home, linkType: 'PLATFORM_SEARCH',
    jumpUrl: 'https://s.taobao.com/search?q=%E5%92%96%E5%95%A1' }] }), true);
});

test('传递关键词、校验后端数据并且不自动重试', async () => {
  const original = globalThis.fetch;
  let count = 0;
  try {
    globalThis.fetch = async (url, options) => {
      count++;
      assert.equal(url, '/api/products/search');
      assert.deepEqual(JSON.parse(options.body), { keyword: '咖啡' });
      return Response.json({ code: 0, data: fixture() });
    };
    assert.deepEqual(await searchProducts(' 咖啡 '), fixture());
    assert.equal(count, 1);
    globalThis.fetch = async () => { count++; return Response.json({ code: 0, data: {} }); };
    await assert.rejects(searchProducts('咖啡'), /契约/);
    assert.equal(count, 2);
  } finally { globalThis.fetch = original; }
});

test('错误、超时和取消有终态；输入校验先于调用', async () => {
  const original = globalThis.fetch;
  try {
    globalThis.fetch = async () => { throw new Error('非法输入不应发送请求'); };
    for (const keyword of ['', ' ', '字'.repeat(81), '咖啡\u0000']) await assert.rejects(searchProducts(keyword), /关键词/);
    globalThis.fetch = async () => new Response(null, { status: 404 });
    await assert.rejects(searchProducts('咖啡'), /重启/);
    globalThis.fetch = async () => new Response(null, { status: 403 });
    await assert.rejects(searchProducts('咖啡'), /未获允许/);
    globalThis.fetch = (_url, { signal }) => new Promise((_resolve, reject) => {
      if (signal.aborted) reject(new DOMException('取消', 'AbortError'));
      else signal.addEventListener('abort', () => reject(new DOMException('取消', 'AbortError')), { once: true });
    });
    await assert.rejects(searchProducts('咖啡', undefined, 10), /停止等待/);
    const aborted = new AbortController();
    aborted.abort();
    await assert.rejects(searchProducts('咖啡', aborted.signal), /停止等待/);
  } finally { globalThis.fetch = original; }
});
