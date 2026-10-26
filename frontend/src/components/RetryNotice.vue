<script setup lang="ts">
import AppIcon from './AppIcon.vue';

// 区块级失败提示：一块没读出来不该拖垮整页，所以只在这一块里提示，并给一个局部重试入口。
// 文案由调用方传入（已包含是哪个区块），这里不再重复标题，避免出现两遍区块名。
defineProps<{ message: string; busy?: boolean; label?: string }>();
const emit = defineEmits<{ retry: [] }>();
</script>

<template>
  <p class="retry-notice" role="alert">
    <AppIcon name="info" :size="14" />
    <span>{{ message }}</span>
    <button type="button" :disabled="busy" @click="emit('retry')">{{ label ? `重新加载${label}` : '重试' }}</button>
  </p>
</template>

<style scoped>
.retry-notice {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  padding: 10px 12px;
  border: 1px solid var(--warning-border);
  border-radius: 10px;
  background: var(--amber-soft);
  color: var(--amber-600);
  font-size: var(--fs-xs);
  line-height: 1.7;
}
.retry-notice span { flex: 1 1 auto; min-width: 0; overflow-wrap: anywhere; }
.retry-notice button {
  flex: none;
  padding: 4px 10px;
  border: 1px solid var(--warning-border);
  border-radius: 8px;
  background: var(--n-0);
  color: var(--amber-600);
  font-size: var(--fs-2xs);
  font-weight: var(--fw-medium);
  cursor: pointer;
  transition: background var(--dur-1) var(--ease-out);
}
.retry-notice button:hover:not(:disabled) { background: var(--amber-soft); }
.retry-notice button:disabled { opacity: .5; cursor: not-allowed; }
</style>
