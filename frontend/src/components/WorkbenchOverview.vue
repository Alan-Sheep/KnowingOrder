<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { createEnterpriseClient, EnterpriseError } from '../enterprise';
import type { AffairSummary } from '../affairs';
import AppIcon from './AppIcon.vue';

// 工作台概览：登录后最想知道的三件事——有几个空间、有没有要我确认的、我的申请到哪一步。
// 之前这一页只有导航按钮，员工要自己点进四个页面才知道自己有什么要做。
const emit = defineEmits<{ navigate: [page: 'enterprise' | 'knowledge' | 'affairs']; login: [] }>();
const api = createEnterpriseClient();
const state = ref<'checking' | 'anonymous' | 'ready' | 'error'>('checking');
const message = ref('');
const enterpriseName = ref('');
const spaceTotal = ref<number | null>(null);
const pendingTotal = ref<number | null>(null);
const mineTotal = ref<number | null>(null);
const latest = ref<AffairSummary>();
let mounted = true;

const statusText: Record<string, string> = {
  SUBMITTED: '已提交', CONFIRMING: '待参与人确认', VERIFIED: '待主管批准',
  PENDING_SETTLEMENT: '待结算', SETTLED: '已结算', REJECTED: '已退回', CANCELLED: '已撤回'
};

async function load() {
  state.value = 'checking';
  message.value = '';
  try {
    const current = await api.me();
    if (!mounted) return;
    const list = current.enterprises ?? [];
    if (!current.enterprises) {
      state.value = 'anonymous';
      message.value = '登录信息里还没有企业归属，请用企业账号重新登录。';
      return;
    }
    if (!list.length) {
      state.value = 'anonymous';
      message.value = '账号已登录，但还没有分配企业。请联系管理员开通成员身份。';
      return;
    }
    const target = list[0]!;
    enterpriseName.value = target.enterpriseName;
    state.value = 'ready';
    // 三个数字各取第一页即可拿到总数，不拉全量列表。
    const [spacePage, pendingPage, minePage] = await Promise.allSettled([
      api.listSpaces(target.enterpriseId, 1, 1),
      api.affairConfirmations(target.enterpriseId, 1),
      api.affairApplications(target.enterpriseId, null, 1)
    ]);
    if (!mounted) return;
    spaceTotal.value = spacePage.status === 'fulfilled' ? spacePage.value.total : null;
    pendingTotal.value = pendingPage.status === 'fulfilled' ? pendingPage.value.total : null;
    if (minePage.status === 'fulfilled') {
      mineTotal.value = minePage.value.total;
      latest.value = minePage.value.records[0];
    } else {
      mineTotal.value = null;
    }
    if ([spacePage, pendingPage, minePage].every(item => item.status === 'rejected')) {
      state.value = 'error';
      message.value = '概览暂时没读出来，可能是网络或服务端还没就绪。';
    }
  } catch (error) {
    if (!mounted) return;
    if (error instanceof EnterpriseError && error.code === 401) {
      state.value = 'anonymous';
      message.value = '登录企业账号后，这里会显示你能访问的空间与待办。';
      return;
    }
    state.value = 'error';
    message.value = error instanceof Error ? error.message : '概览暂时没读出来。';
  }
}

const cards = computed(() => [
  { key: 'spaces', icon: 'book', label: '可访问知识空间', value: spaceTotal.value, unit: '个',
    hint: '已按你的授权范围筛选', page: 'knowledge' as const },
  { key: 'pending', icon: 'check', label: '待我确认', value: pendingTotal.value, unit: '条',
    hint: pendingTotal.value ? '确认或拒绝后，整单才会继续' : '暂时没有要你确认的', page: 'affairs' as const },
  { key: 'mine', icon: 'briefcase', label: '我的申请', value: mineTotal.value, unit: '条',
    hint: latest.value ? `最近一笔：${statusText[latest.value.status] ?? latest.value.status}` : '还没有提交过补贴申请', page: 'affairs' as const }
]);

onMounted(() => { void load(); });
onUnmounted(() => { mounted = false; api.dispose(); });
</script>

<template>
  <section class="work-overview" aria-label="我的工作概览">
    <header class="overview-head">
      <div>
        <span class="overview-kicker">AT A GLANCE</span>
        <h2>今天要处理的事</h2>
      </div>
      <span v-if="state === 'ready'" class="overview-scope"><AppIcon name="shield" :size="13" />{{ enterpriseName }} · 按你的权限统计</span>
      <button v-else-if="state === 'error'" type="button" class="overview-text-button" @click="load">重新读取</button>
    </header>

    <p v-if="state === 'checking'" class="overview-loading" role="status">
      <span class="loading-dots" aria-hidden="true"><i></i><i></i><i></i></span>正在读取你的空间与待办…
    </p>

    <div v-else-if="state === 'anonymous'" class="overview-empty">
      <AppIcon name="shield" :size="17" />
      <p>{{ message }}</p>
      <button type="button" @click="emit('login')">用企业账号登录</button>
    </div>

    <p v-else-if="state === 'error'" class="overview-error" role="alert">
      <AppIcon name="info" :size="15" /><span>{{ message }}</span>
      <button type="button" @click="load">重试</button>
    </p>

    <div v-else class="overview-grid">
      <button v-for="card in cards" :key="card.key" type="button" class="overview-card" @click="emit('navigate', card.page)">
        <span class="overview-icon"><AppIcon :name="card.icon" :size="18" /></span>
        <span class="overview-body">
          <small>{{ card.label }}</small>
          <strong v-if="card.value !== null">{{ card.value }}<em>{{ card.unit }}</em></strong>
          <strong v-else class="unavailable">—</strong>
          <span>{{ card.value === null ? '这一项没读出来，可稍后刷新' : card.hint }}</span>
        </span>
        <AppIcon class="overview-arrow" name="chevron" :size="14" />
      </button>
    </div>
  </section>
</template>

<style scoped>
.work-overview {
  max-width: 1280px;
  margin: var(--sp-8) auto 0;
  padding: var(--sp-5);
  background: var(--n-0);
  border: 1px solid var(--border-subtle);
  border-radius: var(--r-lg);
  box-shadow: var(--sh-xs);
}
.overview-head { display: flex; align-items: flex-end; justify-content: space-between; gap: var(--sp-4); flex-wrap: wrap; }
.overview-kicker { color: var(--text-placeholder); font-size: var(--fs-2xs); font-weight: var(--fw-semibold); letter-spacing: 1.7px; }
.overview-head h2 { margin: var(--sp-2) 0 0; font-size: var(--fs-lg); font-weight: var(--fw-semibold); color: var(--text-primary); letter-spacing: -.3px; }
.overview-scope { display: inline-flex; align-items: center; gap: 6px; font-size: var(--fs-xs); color: var(--text-tertiary); }
.overview-text-button { border: 0; background: none; padding: 0; color: var(--brand-600); font-size: var(--fs-xs); font-weight: var(--fw-medium); cursor: pointer; }
.overview-text-button:hover { text-decoration: underline; }

.overview-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: var(--sp-3); margin-top: var(--sp-4); }
.overview-card {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-4);
  border: 1px solid var(--border-subtle);
  border-radius: var(--r-md);
  background: var(--n-25);
  text-align: left;
  cursor: pointer;
  transition: background var(--dur-2) var(--ease-out), border-color var(--dur-2) var(--ease-out), transform var(--dur-2) var(--ease-out);
}
.overview-card:hover { background: var(--n-0); border-color: var(--brand-200); transform: translateY(-2px); }
.overview-icon {
  display: grid; place-items: center; flex: none;
  width: 38px; height: 38px; border-radius: var(--r-sm);
  background: var(--brand-50); color: var(--brand-600); border: 1px solid var(--brand-100);
}
.overview-body { display: grid; gap: 2px; min-width: 0; flex: 1 1 auto; }
.overview-body small { font-size: var(--fs-xs); color: var(--text-tertiary); }
.overview-body strong { font-size: var(--fs-2xl); font-weight: var(--fw-semibold); color: var(--text-primary); line-height: 1.2; letter-spacing: -.5px; }
.overview-body strong em { margin-left: 3px; font-style: normal; font-size: var(--fs-xs); font-weight: var(--fw-regular); color: var(--text-tertiary); }
.overview-body strong.unavailable { color: var(--text-placeholder); }
.overview-body > span { font-size: var(--fs-2xs); color: var(--text-tertiary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.overview-arrow { flex: none; color: var(--text-placeholder); }

.overview-loading { display: flex; align-items: center; gap: 8px; margin: var(--sp-4) 0 0; font-size: var(--fs-xs); color: var(--text-tertiary); }
.loading-dots { display: inline-flex; gap: 4px; }
.loading-dots i { width: 5px; height: 5px; border-radius: 50%; background: var(--brand-400); animation: overview-pulse 1.1s var(--ease-in-out) infinite; }
.loading-dots i:nth-child(2) { animation-delay: .16s; }
.loading-dots i:nth-child(3) { animation-delay: .32s; }
@keyframes overview-pulse { 0%, 100% { opacity: .25; } 50% { opacity: 1; } }

.overview-empty {
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
  margin-top: var(--sp-4); padding: var(--sp-4);
  border: 1px dashed var(--border-strong); border-radius: var(--r-md);
  background: var(--n-25); color: var(--text-secondary);
}
.overview-empty p { flex: 1 1 240px; margin: 0; font-size: var(--fs-xs); line-height: 1.7; }
.overview-empty button, .overview-error button {
  padding: 7px 13px; border: 1px solid var(--brand-200); border-radius: var(--r-sm);
  background: var(--n-0); color: var(--brand-700); font-size: var(--fs-2xs); font-weight: var(--fw-medium); cursor: pointer;
}
.overview-empty button:hover, .overview-error button:hover { background: var(--brand-50); }

.overview-error {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  margin: var(--sp-4) 0 0; padding: 12px 14px;
  border: 1px solid var(--warning-border); border-radius: var(--r-md);
  background: var(--amber-soft); color: var(--amber-600); font-size: var(--fs-xs);
}
.overview-error span { flex: 1 1 200px; }
.overview-error button { border-color: var(--warning-border); color: var(--amber-600); background: var(--n-0); }

@media (max-width: 940px) {
  .overview-grid { grid-template-columns: 1fr; }
}
</style>
