<script setup lang="ts">
import { onUnmounted, ref } from 'vue';
import { createEnterpriseClient } from '../enterprise';
import type { KnowledgeFeedback } from '../knowledge-workspace';
const props = defineProps<{ enterpriseId: string; spaceId: string }>();
const api = createEnterpriseClient();
const entries = ref<KnowledgeFeedback[]>([]);
const notice = ref('仅当前空间维护者可以读取和处理反馈；处理不会自动修改已发布制度。');
const resolution = ref<Record<string, string>>({});
const busy = ref(false);
let mounted = true;
async function load() {
  if (busy.value) return;
  busy.value = true;
  try { const result = await api.feedbacks(props.enterpriseId, props.spaceId); if (mounted) { entries.value = result; notice.value = result.length ? '请核对资料后填写处理说明。' : '当前没有反馈。'; } }
  catch { if (mounted) { entries.value = []; notice.value = '反馈不可读取，请核对维护授权、登录状态和服务。'; } }
  finally { if (mounted) busy.value = false; }
}
async function resolve(entry: KnowledgeFeedback, status: string) {
  if (busy.value || !resolution.value[entry.id]?.trim()) return;
  busy.value = true;
  try {
    await api.resolveFeedback(props.enterpriseId, props.spaceId, entry.id, entry.revision, status, resolution.value[entry.id]!);
    if (mounted) { busy.value = false; await load(); }
  } catch { if (mounted) notice.value = '处理结果未确认，请重新读取核对；不会自动重复提交。'; }
  finally { if (mounted) busy.value = false; }
}
onUnmounted(() => { mounted = false; api.dispose(); });
</script>
<template>
  <details class="feedback-governance"><summary>知识反馈处理 · 维护者</summary><p role="status">{{ notice }}</p><button :disabled="busy" @click="load">读取待办与处理记录</button>
    <article v-for="entry in entries" :key="entry.id"><strong>{{ entry.category }} · {{ entry.status }}</strong><p>关联问题：{{ entry.question }}</p><p>{{ entry.commentText }}</p><small>关联任务 {{ entry.jobId }}<span v-if="entry.chunkId"> · 片段 {{ entry.chunkId }}</span></small>
      <p v-if="entry.resolution">处理说明：{{ entry.resolution }}</p><template v-if="entry.status === 'OPEN'"><label>核验与处理说明<textarea v-model="resolution[entry.id]" maxlength="1000" rows="2" /></label><button :disabled="busy || !resolution[entry.id]?.trim()" @click="resolve(entry, 'RESOLVED')">标记已处理</button><button :disabled="busy || !resolution[entry.id]?.trim()" @click="resolve(entry, 'DISMISSED')">说明后关闭</button></template>
    </article>
  </details>
</template>
<style scoped>
/* 维护者工具：放在依据栏底部，默认收起，保持中性色不抢正文 */
.feedback-governance {
  margin-top: 16px;
  padding: 0;
  border: 0;
  border-top: 1px solid var(--border-subtle);
  padding-top: 14px;
  font-size: var(--fs-2xs);
}
summary { cursor: pointer; color: var(--text-placeholder); font-size: var(--fs-2xs); font-weight: var(--fw-medium); }
summary:hover { color: var(--brand-700); }
p, small { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.75; font-size: var(--fs-2xs); color: var(--text-tertiary); }
article { border-top: 1px solid var(--border-subtle); padding: 10px 0; margin-top: 10px; }
article strong { display: block; font-size: var(--fs-2xs); color: var(--text-secondary); margin-bottom: 4px; }
label { display: block; margin-top: 8px; font-size: var(--fs-2xs); color: var(--text-tertiary); }
textarea { display: block; width: 100%; box-sizing: border-box; margin: 6px 0 0; padding: 8px; border: 1px solid var(--border-default); border-radius: var(--r-sm); font: inherit; font-size: var(--fs-2xs); resize: vertical; }
button { margin: 6px 6px 0 0; border: 1px solid var(--border-default); border-radius: var(--r-full); background: var(--n-0); padding: 5px 12px; color: var(--text-secondary); font-size: var(--fs-2xs); cursor: pointer; }
button:hover:not(:disabled) { border-color: var(--brand-200); color: var(--brand-700); background: var(--brand-50); }
button:disabled { opacity: .45; cursor: not-allowed; }
</style>
