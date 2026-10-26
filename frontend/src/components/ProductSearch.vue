<script setup lang="ts">
import { onUnmounted, ref } from 'vue';
import { productJumpUrl, searchProducts } from '../products';
import { t } from '../i18n';
import type { ProductSearchResult } from '../products';

const keyword = ref('咖啡');
const pending = ref(false);
const result = ref<ProductSearchResult>();
const error = ref('');
let controller: AbortController | undefined;

async function search() {
  if (pending.value) return;
  result.value = undefined;
  error.value = '';
  pending.value = true;
  const current = new AbortController();
  controller = current;
  try {
    const response = await searchProducts(keyword.value, current.signal);
    if (!current.signal.aborted) result.value = response;
  } catch (cause) {
    if (!current.signal.aborted) error.value = cause instanceof Error ? cause.message : '商品查询未完成。';
  } finally {
    if (controller === current) pending.value = false;
  }
}

onUnmounted(() => controller?.abort());
</script>

<template>
  <section class="product-search" :aria-label="t('商品查询演示')">
    <span class="demo-badge">{{ t('MOCK · 模拟商品') }}</span>
    <h2>{{ t('找点想吃的、想喝的') }}</h2>
    <p>{{ t('开发演示：商品、门店与价格均为虚构。跳转会打开淘宝搜索页并自动填入商品名，但示例商品本身是虚构的，搜索结果与报价由平台决定，不对应这里的示例商品。') }}</p>
    <form class="product-form" @submit.prevent="search">
      <label for="product-keyword">{{ t('商品关键词') }}</label>
      <div><input id="product-keyword" v-model="keyword" maxlength="80" :placeholder="t('试试：咖啡、奶茶、晚餐')" :disabled="pending" />
        <button type="submit" :disabled="pending || !keyword.trim()">{{ pending ? t('查询中…') : t('查询示例商品') }}</button></div>
    </form>
    <p v-if="pending" role="status">{{ t('正在查询，请稍候；最多等待10秒。') }}</p>
    <p v-if="error" class="product-error" role="alert">{{ error }}</p>
    <template v-if="result">
      <p class="product-notice">{{ result.notice }}</p>
      <p v-if="!result.items.length" role="status">{{ t('没有匹配的模拟商品。可以试试“咖啡”“奶茶”或“晚餐”。') }}</p>
      <ul v-else class="product-list">
        <li v-for="item in result.items" :key="item.id">
          <div><strong>{{ item.title }}</strong><span>{{ item.merchant }}</span></div>
          <p>{{ t('示例价 ¥{price} · 非实际报价', { price: item.referencePrice.toFixed(2) }) }}</p>
          <a :href="productJumpUrl(item.jumpUrl)" target="_blank" rel="noopener noreferrer" referrerpolicy="no-referrer">{{ item.linkType === 'PLATFORM_SEARCH' ? t('去淘宝搜索这件商品 ↗') : t('前往平台首页 ↗') }}</a>
        </li>
      </ul>
    </template>
    <p class="product-footnote">{{ t('本演示不创建订单、不确认支付、不占用补贴额度，不调用模型或淘宝API。') }}</p>
  </section>
</template>

<style scoped>
.product-search { text-align: left; }
.product-search p { line-height: 1.7; }
.product-form { margin: 20px 0; }
.product-form label { display: block; margin-bottom: 8px; font-weight: 600; }
.product-form > div { display: flex; gap: 8px; flex-wrap: wrap; }
.product-form input { min-width: 0; flex: 1; border: 1px solid var(--brand-200); border-radius: 10px; padding: 10px; background: white; }
.product-form button { border: 0; border-radius: 10px; padding: 10px 14px; color: white; background: var(--brand-700); }
.product-form button:disabled { opacity: .5; cursor: not-allowed; }
.product-list { list-style: none; padding: 0; display: grid; gap: 12px; }
.product-list li { border: 1px solid var(--brand-100); border-radius: 12px; padding: 14px; }
.product-list strong, .product-list span { display: block; overflow-wrap: anywhere; }
.product-list span, .product-footnote { font-size: var(--fs-xs); color: var(--n-600); }
.product-list a { color: var(--brand-700); font-weight: 600; }
.product-error { color: var(--danger); }
.product-notice { background: var(--n-50); border-radius: 10px; padding: 10px; font-size: var(--fs-xs); }
</style>
