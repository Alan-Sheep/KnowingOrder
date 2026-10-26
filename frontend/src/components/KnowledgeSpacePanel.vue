<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { createEnterpriseClient, EnterpriseError, positiveId } from '../enterprise';
import type { Account, KnowledgeSpace, SpacePage } from '../enterprise';
import { useSession } from '../session';
import KnowledgeDocumentsPanel from './KnowledgeDocumentsPanel.vue';
import OrganizationPanel from './OrganizationPanel.vue';
import AccountPasswordPanel from './AccountPasswordPanel.vue';
import AppIcon from './AppIcon.vue';
import { t } from '../i18n';

const props = defineProps<{ returnLabel?: string }>();
const emit = defineEmits<{ authenticated: [] }>();
const api = createEnterpriseClient();
// 登录状态与工作台、企业问答、员工事务共用同一份；这里通常是登录的入口页。
const { user, ensure, login: signIn, logout: signOut, markAnonymous } = useSession();
const username = ref('');
const password = ref('');
const enterpriseId = ref('');
const spaceId = ref('');
const name = ref('');
const result = ref<KnowledgeSpace>();
const resultEnterprise = ref('');
const busy = ref('checking');
const message = ref(t('正在核对登录状态…'));
const failed = ref(false);
const unknownCreation = ref(false);
const listing = ref<SpacePage>();
const documentBusy = ref(false);
const spaceQuery = ref('');
const managerRef = ref<HTMLElement>();
const visibleSpaces = computed(() => listing.value?.records.filter(space => space.name.toLocaleLowerCase().includes(spaceQuery.value.trim().toLocaleLowerCase())) ?? []);
let mounted = true;
const validEnterprise = computed(() => positiveId(enterpriseId.value));
const currentEnterprise = computed(() => user.value?.enterprises?.find(item => item.enterpriseId === enterpriseId.value));
const validName = computed(() => name.value.trim().length > 0 && name.value.trim().length <= 128);

watch([enterpriseId, spaceId], () => { result.value = undefined; }, { flush: 'sync' });
watch(enterpriseId, () => { listing.value = undefined; }, { flush: 'sync' });
// 列表与详情共用父级滚动容器；等 DOM 切换后归零，避免沿用目录底部的位置。
watch(() => result.value?.id, () => {
  managerRef.value?.closest<HTMLElement>('.enterprise-workspace')?.scrollTo({ top: 0, behavior: 'instant' });
}, { flush: 'post' });
async function run(kind: string, work: () => Promise<void>): Promise<boolean> {
  if (busy.value || documentBusy.value) return false;
  busy.value = kind;
  message.value = kind === 'login' ? t('正在登录…') : kind === 'create' ? t('正在创建空间…') : t('正在处理…');
  failed.value = false;
  try { await work(); return mounted; }
  catch (error) {
    if (!mounted) return false;
    failed.value = true;
    message.value = error instanceof EnterpriseError ? error.message : t('操作未完成，请稍后手动重试。');
    if (error instanceof EnterpriseError && error.code === 401) { markAnonymous(); result.value = undefined; listing.value = undefined; }
    if (error instanceof EnterpriseError && error.code === 404) listing.value = undefined;
    if (error instanceof EnterpriseError && error.uncertain) unknownCreation.value = true;
    return false;
  } finally {
    if (mounted) busy.value = '';
    password.value = '';
  }
}
async function login() {
  const ok = await run('login', async () => {
    await connect(await signIn(username.value, password.value));
    result.value = undefined;
  });
  // 只有真的登录成功才回跳；失败了就留在本页看提示，不然用户会被弹回去又进不来。
  if (ok && user.value) emit('authenticated');
}
async function connect(current: Account) {
  enterpriseId.value = current.enterprises?.length === 1 ? current.enterprises[0]!.enterpriseId : '';
  if (!current.enterprises) { failed.value = true; message.value = t('登录信息尚不包含所属企业，请重启最新版后端后刷新。'); return; }
  if (!current.enterprises.length) { message.value = t('账号尚未分配企业，请联系管理员开通成员身份。'); return; }
  if (!enterpriseId.value) { message.value = t('请选择你要进入的企业工作区。'); return; }
  listing.value = await api.listSpaces(enterpriseId.value);
  message.value = listing.value.total ? t('已为你加载可访问的知识空间。') : t('暂未获得空间授权，请联系知识负责人。');
}
async function logout() {
  await run('logout', async () => {
    await signOut();
    result.value = undefined;
    listing.value = undefined;
    username.value = '';
    enterpriseId.value = '';
    spaceId.value = '';
    name.value = '';
    message.value = t('已退出登录。');
  });
}
async function create() {
  if (!user.value || !validEnterprise.value || !validName.value || unknownCreation.value) return;
  await run('create', async () => {
    result.value = undefined;
    const enterprise = enterpriseId.value;
    const created = await api.createSpace(enterprise, name.value);
    spaceId.value = created.id;
    resultEnterprise.value = enterprise;
    result.value = created;
    message.value = t('空间已创建，可以开始上传资料。');
  });
}
async function read() {
  if (!user.value || !validEnterprise.value || !positiveId(spaceId.value)) return;
  await run('read', async () => {
    result.value = undefined;
    const enterprise = enterpriseId.value;
    const found = await api.readSpace(enterprise, spaceId.value);
    resultEnterprise.value = enterprise;
    result.value = found;
    message.value = t('已通过服务端归属与授权校验，读取成功。');
  });
}
async function list(page = 1) {
  if (!user.value || !validEnterprise.value) return;
  await run('list', async () => {
    listing.value = undefined;
    listing.value = await api.listSpaces(enterpriseId.value, page);
    message.value = listing.value.total ? t('已读取当前明确授权范围内的空间。') : t('当前没有可访问的知识空间，请联系知识负责人授权。');
  });
}
function selectSpace(space: KnowledgeSpace) {
  spaceId.value = space.id;
  void read();
}
async function browseSpaces() {
  if (busy.value || documentBusy.value) return;
  result.value = undefined;
  await list();
}
onMounted(async () => {
  try {
    const current = await ensure();
    if (!mounted) return;
    if (current) await connect(current);
    else message.value = t('请使用管理员开通的企业账号登录，不开放公开注册。');
  } catch (error) {
    if (mounted) {
      failed.value = true;
      message.value = error instanceof Error ? error.message : t('登录状态核对失败。');
    }
  } finally { if (mounted) busy.value = ''; }
});
onUnmounted(() => { mounted = false; password.value = ''; api.dispose(); });
</script>

<template>
  <section ref="managerRef" class="knowledge-manager" :aria-label="t('企业账号与知识空间')">
    <header v-show="!result" class="library-heading"><div><span class="eyebrow">YOUR TEAM’S KNOWLEDGE</span><h2>{{ t('把团队的知识，') }}<br />{{ t('变成触手可及的答案。') }}</h2><p>{{ t('整理资料、沉淀经验，让好方法被更多人找到。') }}</p><span class="private-badge"><AppIcon name="shield" :size="13" />{{ t('企业私有 · 按权限可见') }}</span></div><div class="library-art" aria-hidden="true"><span class="library-orbit"></span><span class="folder-back"></span><span class="folder-paper"><i></i><i></i><i></i></span><span class="folder-front"><AppIcon name="book" :size="34" /></span><span class="folder-label"><AppIcon name="check" :size="14" />{{ t('知识，有序生长') }}</span><i class="folder-spark">+</i></div></header>
    <div class="account-line" v-if="user"><div class="account-identity"><span class="account-avatar"><AppIcon name="briefcase" :size="19" /></span><div><strong>{{ currentEnterprise?.enterpriseName ?? t('企业工作区') }}</strong><small>{{ currentEnterprise?.role === 'ADMIN' ? t('企业管理员') : t('企业成员') }} · {{ t('已登录') }}</small></div><span class="connected-dot">{{ t('身份已连接') }}</span></div><button type="button" class="quiet-button" :disabled="!!busy || documentBusy" @click="logout">{{ busy === 'logout' ? t('正在退出…') : t('退出登录') }}</button></div>
    <form v-else class="account-form" :aria-label="t('企业账号登录')" @submit.prevent="login">
      <div class="login-intro"><span class="login-symbol"><AppIcon name="book" :size="29" /></span><h3>{{ t('连接你的团队知识') }}</h3><p>{{ t('登录后查看获授权的空间，管理资料与版本。') }}</p><div class="login-benefits"><span><AppIcon name="check" :size="15" />{{ t('资料按空间有序归档') }}</span><span><AppIcon name="check" :size="15" />{{ t('回答与原文相互对应') }}</span><span><AppIcon name="check" :size="15" />{{ t('访问权限由企业管理') }}</span></div></div>
      <fieldset :disabled="!!busy"><legend>{{ t('企业账号登录') }}</legend><p class="login-caption">{{ t('使用管理员为你开通的账号') }}</p><p v-if="props.returnLabel" class="login-return"><AppIcon name="arrow" :size="14" /><span>{{ t('登录成功后会回到「{label}」，接着刚才的事继续。', { label: props.returnLabel ?? '' }) }}</span></p><label for="enterprise-username">{{ t('账号') }}<input id="enterprise-username" v-model="username" autocomplete="username" :placeholder="t('输入企业账号')" required maxlength="64" /></label><label for="enterprise-password">{{ t('密码') }}<input id="enterprise-password" v-model="password" type="password" autocomplete="current-password" :placeholder="t('输入登录密码')" required maxlength="256" /></label><button class="solid" type="submit">{{ busy === 'login' ? t('正在登录…') : t('登录企业账号') }}<AppIcon name="chevron" :size="15" /></button>
        <!-- 未登录时的反馈必须贴在按钮下方：页面底部的提示在常见视口高度下会落到折叠线外，失败原因看不见。 -->
        <p class="manager-feedback form-feedback" :class="{ failed }" role="status" aria-live="polite"><AppIcon :name="failed ? 'info' : 'check'" :size="15" />{{ message }}</p></fieldset>
      <small>{{ t('密码只随本次登录提交，不写入本机存储；提交完成后清空输入框。') }}</small>
    </form>
    <p v-if="user" class="manager-feedback" :class="{ failed }" role="status" aria-live="polite"><AppIcon :name="failed ? 'info' : 'check'" :size="15" />{{ message }}</p>
    <template v-if="user">
      <div class="workspace-connection"><label v-if="(user.enterprises?.length ?? 0) > 1" class="enterprise-selector" for="enterprise-workspace"><span><AppIcon name="briefcase" :size="15" />{{ t('企业工作区') }}</span><select id="enterprise-workspace" v-model="enterpriseId" :disabled="!!busy || documentBusy" @change="list()"><option value="" disabled>{{ t('选择所属企业') }}</option><option v-for="item in user.enterprises" :key="item.enterpriseId" :value="item.enterpriseId">{{ item.enterpriseName }}</option></select></label><span v-else class="enterprise-name"><AppIcon name="shield" :size="15" />{{ currentEnterprise ? t('已自动连接所属企业') : t('等待企业身份') }}</span><button type="button" class="quiet-button" :disabled="!!busy || documentBusy || !validEnterprise" @click="list()"><AppIcon name="refresh" :size="15" />{{ busy === 'list' ? t('正在刷新…') : t('刷新空间列表') }}</button><small>{{ t('按你的权限展示资料') }}</small></div>
      <section v-show="!result" class="space-list" :aria-label="t('授权空间列表')">
        <div class="directory-heading"><div><h3>{{ t('我的知识空间') }}<span v-if="listing" class="count-badge">{{ listing.total }}</span></h3><p>{{ t('选择一个主题空间，开始整理或查阅资料。') }}</p></div><label class="space-search"><AppIcon name="search" :size="16" /><input v-model="spaceQuery" :aria-label="t('筛选本页知识空间')" :placeholder="t('筛选本页空间…')" :disabled="!listing" /></label></div>
        <template v-if="listing">
          <ul class="space-cards"><li v-for="(item, index) in visibleSpaces" :key="item.id"><button type="button" :class="{ chosen: result?.id === item.id }" :disabled="!!busy || documentBusy" @click="selectSpace(item)"><span class="card-top"><span class="space-symbol" :class="'tone-' + index % 3"><AppIcon :name="['book', 'layers', 'briefcase'][index % 3]!" :size="23" /></span><span class="card-tag">{{ result?.id === item.id ? t('当前空间') : t('知识空间') }}</span></span><strong>{{ item.name }}</strong><span class="card-description">{{ t('集中管理这一主题的资料、版本与访问权限') }}</span><span class="card-bottom"><small>{{ t('点开查看资料与版本') }}</small><span>{{ t('打开空间') }}<AppIcon name="chevron" :size="14" /></span></span></button></li></ul>
          <div v-if="!visibleSpaces.length" class="directory-empty"><AppIcon :name="spaceQuery ? 'search' : 'book'" :size="32" /><h4>{{ spaceQuery ? t('本页没有匹配的空间') : t('这里还没有知识空间') }}</h4><p>{{ spaceQuery ? t('换一个名称试试，或清空筛选查看本页全部空间。') : t('管理员可以在下方创建空间；员工可联系知识负责人获取授权。') }}</p><button v-if="spaceQuery" type="button" @click="spaceQuery = ''">{{ t('清空筛选') }}</button></div>
          <div class="directory-pagination"><span>{{ t('共 {total} 个可访问空间 · 第 {page} 页', { total: listing.total, page: listing.pageNum }) }}</span><div><button type="button" :disabled="!!busy || documentBusy || listing.pageNum <= 1" @click="list(listing.pageNum - 1)">{{ t('上一页') }}</button><button type="button" :disabled="!!busy || documentBusy || listing.pageNum >= listing.pages" @click="list(listing.pageNum + 1)">{{ t('下一页') }}</button></div></div>
        </template>
        <div v-else-if="!result" class="directory-empty"><span class="empty-orbit"><AppIcon name="layers" :size="34" /></span><h4>{{ busy ? t('正在连接团队知识') : t('你的团队知识，从这里汇聚') }}</h4><p>{{ user.enterprises?.length ? t('选择企业工作区后，自动展示你有权限访问的空间。') : t('登录后会自动带出你有权限访问的空间，不需要记任何编号。') }}</p></div>
      </section>
      <div v-show="!result" class="manager-grid">
        <details><summary><span class="utility-icon"><AppIcon name="plus" :size="19" /></span><span><strong>{{ t('新建知识空间') }}</strong><small>{{ t('为一个主题建立专属资料库 · 管理员') }}</small></span><AppIcon name="chevron" :size="16" /></summary><form :aria-label="t('创建知识空间')" @submit.prevent="create"><fieldset :disabled="!!busy || documentBusy"><legend>{{ t('创建知识空间') }}</legend><label for="space-name">{{ t('空间名称') }}<input id="space-name" v-model="name" required maxlength="128" :placeholder="t('例如：研发工作指南')" /></label><button class="solid" type="submit" :disabled="!validEnterprise || !validName || unknownCreation">{{ busy === 'create' ? t('正在创建…') : t('创建空间') }}</button><small>{{ t('仅有效管理员可创建，创建后获得该空间维护权限。') }}</small></fieldset></form></details>
        <details class="guide-card"><summary><span class="utility-icon neutral"><AppIcon name="info" :size="19" /></span><span><strong>{{ t('第一次用？从这里开始') }}</strong><small>{{ t('三步找到你要的资料') }}</small></span><AppIcon name="chevron" :size="16" /></summary><div class="guide-body"><ol><li><strong>{{ t('点开上面任意一个空间') }}</strong><p>{{ t('你能看到的空间，都是管理员已经为你授权的，不需要记任何编号。') }}</p></li><li><strong>{{ t('在空间里浏览资料') }}</strong><p>{{ t('每份资料都保留版本和有效期；已过期或已撤下的资料不会出现在回答里。') }}</p></li><li><strong>{{ t('回到「企业问答」提问') }}</strong><p>{{ t('用日常说法问就可以。回答会附上原文出处，方便你自己核对。') }}</p></li></ol></div></details>
      </div>
      <p v-if="unknownCreation" class="manager-feedback failed">{{ t('创建结果尚未核对，当前页面已暂停继续创建。请联系管理员核对开发库记录后，再刷新页面操作。') }}</p>
      <button v-if="result" type="button" class="back-to-spaces" :disabled="!!busy || documentBusy" @click="browseSpaces"><AppIcon name="chevron" :size="14" />{{ t('返回空间目录') }}</button>
      <article v-if="result" class="space-result" :aria-label="t('知识空间结果')"><span class="selected-space-icon"><AppIcon name="book" :size="26" /></span><div><span class="eyebrow">{{ t('当前知识空间') }}</span><h3>{{ result.name }}</h3><small>{{ t('资料按版本留存，回答可以回溯到原文') }}</small></div><span class="private-badge"><AppIcon name="shield" :size="14" />{{ t('已通过授权校验') }}</span></article>
      <KnowledgeDocumentsPanel v-if="result" :key="resultEnterprise + ':' + result.id" :enterprise-id="resultEnterprise" :space="result"
        @updated="result = $event; listing = undefined" @expired="markAnonymous(); result = undefined; listing = undefined" @busy="documentBusy = $event" />
      <details class="workspace-administration"><summary><AppIcon name="settings" :size="17" /><span>{{ t('工作区管理') }}<small>{{ t('账号安全、部门与成员') }}</small></span><AppIcon name="chevron" :size="16" /></summary><AccountPasswordPanel :disabled="!!busy || documentBusy" @busy="documentBusy = $event" @changed="markAnonymous(); result = undefined; listing = undefined; message = t('口令已修改，请使用新口令重新登录。')" /><OrganizationPanel v-if="validEnterprise" :key="enterpriseId" :enterprise-id="enterpriseId" :disabled="!!busy || documentBusy" @busy="documentBusy = $event" @expired="markAnonymous(); result = undefined; listing = undefined" /></details>
    </template>
  </section>
</template>

<style scoped>
.knowledge-manager {
  --library-ink: var(--text-primary);
  --library-muted: var(--text-tertiary);
  max-width: var(--content-max);
  margin: 0 auto;
  color: var(--library-ink);
}

/* ---------------- 深色焦点横幅 ---------------- */
.library-heading {
  position: relative;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-6);
  margin: 0 0 var(--sp-5);
  padding: clamp(26px, 3vw, 40px) clamp(24px, 3vw, 43px);
  min-height: 220px;
  border-radius: var(--r-2xl);
  background: var(--focus-block);
  color: #fff;
  box-shadow: var(--sh-xl);
  isolation: isolate;
}
.library-heading::before {
  content: '';
  position: absolute;
  inset: 0;
  background: var(--focus-block-glow);
  z-index: -1;
}
.library-heading::after {
  content: '';
  position: absolute;
  inset: 0;
  background: radial-gradient(ellipse 55% 85% at 10% 105%, rgba(47, 127, 98, .45), transparent 60%);
  z-index: -1;
}
.library-heading > div:first-child { position: relative; z-index: 1; }
.library-heading h2 {
  color: #fff;
  font-size: clamp(25px, 2.7vw, 36px);
  line-height: 1.36;
  letter-spacing: -.6px;
  margin: var(--sp-3) 0 var(--sp-2);
  font-weight: var(--fw-semibold);
}
.library-heading .eyebrow {
  color: rgba(167, 198, 179, .95);
  font-size: var(--fs-2xs);
  font-weight: var(--fw-semibold);
  letter-spacing: 1.8px;
}
.library-heading p { margin: 0; color: var(--focus-block-muted); font-size: var(--fs-sm); line-height: var(--lh-base); }
.library-heading .private-badge {
  margin-top: var(--sp-4);
  color: rgba(226, 240, 232, .92);
  background: rgba(255, 255, 255, .08);
  border: 1px solid rgba(255, 255, 255, .16);
  font-size: var(--fs-2xs);
  padding: 6px 11px;
  backdrop-filter: blur(8px);
}

/* 横幅插画：深色底上的浅色折叠与卡片 */
.library-art { position: relative; width: 265px; height: 178px; margin-right: var(--sp-4); flex-shrink: 0; }
.library-orbit {
  position: absolute;
  inset: -6px;
  border: 1px dashed rgba(167, 206, 180, .3);
  border-radius: 50%;
  transform: rotate(-20deg);
}
.folder-back {
  position: absolute;
  width: 150px;
  height: 110px;
  left: 55px;
  top: 29px;
  background: rgba(92, 146, 116, .5);
  border: 1px solid rgba(132, 180, 152, .45);
  border-radius: var(--r-xs) var(--r-lg) var(--r-lg) var(--r-lg);
  transform: rotate(8deg);
}
.folder-back::before {
  content: '';
  position: absolute;
  top: -13px;
  left: -1px;
  width: 65px;
  height: 16px;
  border-radius: var(--r-xs) var(--r-sm) 0 0;
  background: rgba(92, 146, 116, .5);
  border: 1px solid rgba(132, 180, 152, .45);
  border-bottom: 0;
}
.folder-paper {
  position: absolute;
  left: 76px;
  top: 21px;
  width: 108px;
  height: 108px;
  border-radius: var(--r-xs);
  background: #eef6f1;
  padding: var(--sp-4);
  transform: rotate(-5deg);
  box-shadow: 0 10px 24px rgba(6, 20, 14, .35);
}
.folder-paper i { display: block; height: 4px; width: 62px; margin-bottom: 10px; background: var(--brand-200); border-radius: var(--r-full); }
.folder-paper i:last-child { width: 35px; }
.folder-front {
  position: absolute;
  left: 48px;
  bottom: 24px;
  display: grid;
  place-items: center;
  width: 164px;
  height: 94px;
  border-radius: var(--r-md) var(--r-md) var(--r-lg) var(--r-lg);
  color: #dff0e5;
  background: linear-gradient(130deg, #6fa77c, #3f7355);
  border: 1px solid rgba(160, 195, 145, .55);
  transform: rotate(-7deg) skewX(-5deg);
  box-shadow: 0 18px 34px rgba(4, 18, 12, .4);
}
.folder-label {
  position: absolute;
  right: -6px;
  bottom: 8px;
  padding: 9px 12px;
  display: flex;
  gap: 6px;
  align-items: center;
  color: var(--brand-700);
  background: #fff;
  font-size: var(--fs-2xs);
  font-weight: var(--fw-medium);
  border-radius: var(--r-sm);
  transform: rotate(4deg);
  box-shadow: 0 10px 22px rgba(4, 18, 12, .3);
}
.folder-spark {
  position: absolute;
  top: 10px;
  left: 10px;
  font-size: 24px;
  color: rgba(167, 198, 149, .7);
  font-style: normal;
  line-height: 1;
}

/* ---------------- 基础排版 ---------------- */
.eyebrow {
  color: var(--text-placeholder);
  font-size: var(--fs-2xs);
  letter-spacing: 1.4px;
  font-weight: var(--fw-semibold);
}
h2 { font-size: var(--fs-2xl); font-weight: var(--fw-semibold); letter-spacing: -.5px; margin: var(--sp-2) 0; line-height: var(--lh-snug); }
p, small { font-size: var(--fs-xs); line-height: var(--lh-base); color: var(--library-muted); }
small { display: block; }

.private-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
  padding: 7px 11px;
  border-radius: var(--r-xs);
  background: var(--brand-50);
  color: var(--brand-700);
  font-size: var(--fs-2xs);
  font-weight: var(--fw-medium);
}

.account-form, .account-line, .space-result {
  border: 1px solid var(--border-subtle);
  border-radius: var(--r-lg);
  background: var(--n-0);
  box-shadow: var(--sh-xs);
}

fieldset { border: 0; padding: 0; margin: 0; min-width: 0; }
legend { font-size: var(--fs-lg); font-weight: var(--fw-semibold); padding: 0; margin-bottom: var(--sp-3); color: var(--text-primary); }
label { display: block; font-size: var(--fs-xs); margin: var(--sp-3) 0; color: var(--text-secondary); font-weight: var(--fw-medium); }
input {
  display: block;
  box-sizing: border-box;
  width: 100%;
  min-width: 0;
  margin-top: var(--sp-2);
  border: 1px solid var(--border-default);
  border-radius: var(--r-sm);
  padding: 11px var(--sp-3);
  background: var(--n-0);
  color: var(--text-primary);
  font: inherit;
  font-size: var(--fs-base);
  font-weight: var(--fw-regular);
  transition: border-color var(--dur-1), box-shadow var(--dur-2);
}
input::placeholder { color: var(--text-placeholder); }
input:focus-visible { outline: none; border-color: var(--brand-300); box-shadow: var(--ring); }

button {
  display: inline-flex;
  justify-content: center;
  align-items: center;
  gap: 7px;
  border: 1px solid var(--border-default);
  border-radius: var(--r-sm);
  padding: 10px var(--sp-4);
  color: var(--text-secondary);
  background: var(--n-0);
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
  cursor: pointer;
  transition: background var(--dur-1) var(--ease-out),
              border-color var(--dur-1) var(--ease-out),
              transform var(--dur-2) var(--ease-out),
              box-shadow var(--dur-2) var(--ease-out);
}
button:hover:not(:disabled) { background: var(--n-50); border-color: var(--brand-200); color: var(--brand-700); }
button:active:not(:disabled) { transform: scale(.98); }
button.solid {
  background: var(--brand-600);
  border-color: var(--brand-600);
  color: #fff;
  box-shadow: var(--sh-sm);
}
button.solid:hover:not(:disabled) { background: var(--brand-700); border-color: var(--brand-700); color: #fff; }
button:disabled, fieldset:disabled { opacity: .5; cursor: not-allowed; }

/* ---------------- 登录卡片 ---------------- */
.account-form {
  display: grid;
  grid-template-columns: 1fr 1fr;
  max-width: 880px;
  margin: var(--sp-8) auto var(--sp-5);
  overflow: hidden;
  box-shadow: var(--sh-lg);
  border-radius: var(--r-xl);
}
.login-intro { background: var(--n-50); padding: var(--sp-10) var(--sp-8); border-right: 1px solid var(--border-subtle); }
.login-symbol {
  width: 58px;
  height: 58px;
  display: grid;
  place-items: center;
  color: var(--brand-600);
  background: var(--brand-50);
  border: 1px solid var(--brand-100);
  border-radius: var(--r-lg);
}
.login-intro h3 { font-size: var(--fs-xl); font-weight: var(--fw-semibold); letter-spacing: -.3px; margin: var(--sp-5) 0 var(--sp-2); color: var(--text-primary); }
.login-benefits { display: grid; gap: var(--sp-3); margin-top: var(--sp-7); color: var(--text-secondary); font-size: var(--fs-xs); }
.login-benefits span { display: flex; align-items: center; gap: var(--sp-2); }
.login-benefits svg { color: var(--brand-500); flex-shrink: 0; }
.account-form fieldset { padding: var(--sp-8); }
.account-form legend { float: left; width: 100%; margin: 0 0 var(--sp-2); }
.login-caption { margin: 0 0 var(--sp-5); }
/* 从工作台／企业问答跳来登录时的回流提示：说明登录后会自动回去 */
.login-return {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  margin: 0 0 var(--sp-4);
  padding: 9px 11px;
  border: 1px solid var(--brand-200);
  border-radius: var(--r-sm);
  background: var(--brand-50);
  color: var(--brand-700);
  font-size: var(--fs-xs);
  line-height: var(--lh-base);
}
.login-return svg { flex: 0 0 auto; margin-top: 2px; }
.account-form fieldset > button { width: 100%; margin-top: var(--sp-3); justify-content: space-between; padding: 12px var(--sp-4); font-size: var(--fs-sm); }
.account-form > small {
  grid-column: 1 / -1;
  padding: var(--sp-3) var(--sp-5);
  border-top: 1px solid var(--border-subtle);
  background: var(--n-50);
  font-size: var(--fs-2xs);
  text-align: center;
}

/* ---------------- 账号条 ---------------- */
.account-line {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-3) var(--sp-5);
  border-radius: var(--r-md);
}
.account-identity { display: flex; align-items: center; gap: var(--sp-3); min-width: 0; font-size: var(--fs-sm); }
.account-identity small { font-size: var(--fs-2xs); margin-top: 2px; }
.account-avatar {
  display: grid;
  place-items: center;
  background: var(--brand-50);
  color: var(--brand-600);
  width: 38px;
  height: 38px;
  border-radius: var(--r-md);
  border: 1px solid var(--brand-100);
  flex-shrink: 0;
}
.connected-dot { color: var(--brand-600); font-size: var(--fs-2xs); margin-left: var(--sp-3); display: inline-flex; align-items: center; }
.connected-dot::before {
  content: '';
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: var(--r-full);
  background: var(--brand-400);
  margin-right: 6px;
  box-shadow: 0 0 0 3px var(--brand-50);
}
.quiet-button { border: 0; font-size: var(--fs-xs); background: transparent; }
.quiet-button:hover:not(:disabled) { background: var(--n-100); }

/* ---------------- 工作区连接 ---------------- */
.workspace-connection { display: flex; flex-wrap: wrap; align-items: center; gap: var(--sp-3); padding: var(--sp-4) 0 var(--sp-1); }
.enterprise-selector { display: flex; align-items: center; gap: var(--sp-3); margin: 0; }
.enterprise-selector > span { display: inline-flex; align-items: center; gap: 6px; flex-shrink: 0; color: var(--text-tertiary); font-size: var(--fs-xs); font-weight: var(--fw-medium); }
.enterprise-selector select {
  max-width: 280px;
  min-width: 0;
  padding: 10px var(--sp-3);
  border: 1px solid var(--border-default);
  border-radius: var(--r-sm);
  background: var(--n-0);
  color: var(--text-primary);
  font: inherit;
  font-size: var(--fs-sm);
}
.enterprise-name { display: flex; align-items: center; gap: 6px; font-size: var(--fs-sm); color: var(--brand-700); }
.workspace-connection > small { margin-left: auto; font-size: var(--fs-2xs); }

.manager-feedback {
  display: flex;
  align-items: flex-start;
  gap: var(--sp-2);
  padding: 11px var(--sp-4);
  background: var(--brand-50);
  border: 1px solid var(--brand-100);
  border-radius: var(--r-sm);
  overflow-wrap: anywhere;
  color: var(--brand-700);
  font-size: var(--fs-xs);
  margin: 0;
}
.manager-feedback svg { flex-shrink: 0; margin-top: 2px; }
.manager-feedback.failed { background: var(--danger-soft); border-color: var(--danger-border); color: var(--danger-fg); }
/* 登录表单内的反馈：跟按钮同列，不用滚动就能看到失败原因 */
.account-form .form-feedback { margin-top: var(--sp-3); }

/* ---------------- 空间目录 ---------------- */
.space-list { margin-top: var(--sp-6); }
.directory-heading { display: flex; justify-content: space-between; align-items: center; gap: var(--sp-4); margin-bottom: var(--sp-4); }
.directory-heading h3 { margin: 0; display: flex; gap: var(--sp-2); align-items: center; font-size: var(--fs-xl); font-weight: var(--fw-semibold); color: var(--text-primary); }
.directory-heading p { margin: 6px 0 0; font-size: var(--fs-xs); }
.count-badge {
  font-size: var(--fs-2xs);
  background: var(--brand-50);
  border-radius: var(--r-full);
  padding: 2px 8px;
  color: var(--brand-600);
  font-weight: var(--fw-medium);
  font-variant-numeric: tabular-nums;
}
.space-search {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin: 0;
  width: 235px;
  border: 1px solid var(--border-default);
  background: var(--n-0);
  padding: 0 var(--sp-3);
  border-radius: var(--r-sm);
  color: var(--text-placeholder);
  transition: border-color var(--dur-1), box-shadow var(--dur-2);
}
.space-search input { padding: 10px 0; margin: 0; border: 0; background: transparent; font-size: var(--fs-xs); }
.space-search:focus-within { border-color: var(--brand-300); box-shadow: var(--ring); }
.space-search input:focus-visible { outline: none; }

.space-cards { list-style: none; margin: 0; padding: 0; display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: var(--sp-4); }
.space-cards li { min-width: 0; }
.space-cards li button {
  width: 100%;
  height: 100%;
  padding: var(--sp-5) var(--sp-5) 0;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  text-align: left;
  border-radius: var(--r-lg);
  background: var(--n-0);
  border: 1px solid var(--border-subtle);
  box-shadow: var(--sh-xs);
  overflow-wrap: anywhere;
}
.space-cards li button:hover:not(:disabled),
.space-cards li button.chosen {
  border-color: var(--brand-300);
  box-shadow: var(--sh-lg);
  background: var(--n-0);
  transform: translateY(-3px);
}
.space-cards li button { transition: transform var(--dur-2) var(--ease-out), box-shadow var(--dur-2) var(--ease-out), border-color var(--dur-2) var(--ease-out); }

.card-top { display: flex; align-items: center; justify-content: space-between; gap: var(--sp-2); margin-bottom: var(--sp-4); }
.space-symbol {
  width: 44px;
  height: 44px;
  display: grid;
  place-items: center;
  border-radius: var(--r-md);
  background: var(--brand-50);
  color: var(--brand-600);
}
.space-symbol.tone-1 { background: var(--amber-soft); color: var(--amber-600); }
.space-symbol.tone-2 { background: var(--blue-soft); color: var(--blue-600); }
.card-tag {
  color: var(--text-tertiary);
  font-size: var(--fs-2xs);
  background: var(--n-50);
  border: 1px solid var(--border-subtle);
  padding: 4px 8px;
  border-radius: var(--r-full);
}
.space-cards strong { font-size: var(--fs-md); line-height: var(--lh-snug); font-weight: var(--fw-semibold); color: var(--text-primary); }
.card-description { font-size: var(--fs-xs); line-height: var(--lh-base); color: var(--text-tertiary); margin: 6px 0 var(--sp-5); }
.card-bottom {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--sp-2);
  margin-top: auto;
  border-top: 1px solid var(--border-subtle);
  padding: var(--sp-3) 0;
}
.card-bottom small { font-size: var(--fs-2xs); color: var(--text-placeholder); }
.card-bottom > span { display: inline-flex; align-items: center; gap: 5px; font-size: var(--fs-xs); color: var(--brand-600); font-weight: var(--fw-medium); }

.directory-empty {
  border: 1px dashed var(--border-strong);
  border-radius: var(--r-lg);
  text-align: center;
  padding: var(--sp-10) var(--sp-5);
  background: var(--n-25);
  color: var(--text-placeholder);
}
.directory-empty h4 { margin: var(--sp-4) 0 6px; font-size: var(--fs-lg); font-weight: var(--fw-semibold); color: var(--text-secondary); }
.directory-empty p { margin: 0; font-size: var(--fs-xs); line-height: var(--lh-base); }
.directory-empty button { margin-top: var(--sp-4); }
.empty-orbit {
  margin: auto;
  display: grid;
  place-items: center;
  width: 66px;
  height: 66px;
  background: var(--brand-50);
  border-radius: var(--r-lg);
  border: 1px solid var(--brand-100);
  color: var(--brand-500);
  transform: rotate(-6deg);
}
.directory-pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  margin: var(--sp-4) 0;
  font-size: var(--fs-xs);
  color: var(--text-tertiary);
}
.directory-pagination div { display: flex; gap: 6px; }
.directory-pagination button { padding: 6px 11px; font-size: var(--fs-xs); }

/* ---------------- 管理折叠区 ---------------- */
.manager-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--sp-4); margin: var(--sp-5) 0; }
.manager-grid details, .workspace-administration {
  background: var(--n-0);
  border: 1px solid var(--border-subtle);
  border-radius: var(--r-md);
  min-width: 0;
  box-shadow: var(--sh-xs);
}
summary { list-style: none; cursor: pointer; display: flex; align-items: center; gap: var(--sp-3); padding: var(--sp-4) var(--sp-5); }
summary::-webkit-details-marker { display: none; }
summary > svg:last-child { margin-left: auto; color: var(--n-400); transition: transform var(--dur-2) var(--ease-out); }
details[open] > summary > svg:last-child { transform: rotate(90deg); }
summary strong { font-size: var(--fs-sm); font-weight: var(--fw-semibold); color: var(--text-primary); }
summary small { font-size: var(--fs-2xs); margin-top: 3px; }
.utility-icon {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  border-radius: var(--r-sm);
  color: var(--brand-600);
  background: var(--brand-50);
  flex-shrink: 0;
}
.utility-icon.neutral { color: var(--blue-600); background: var(--blue-soft); }
.manager-grid form { padding: 0 var(--sp-5) var(--sp-5); }
.manager-grid form small { font-size: var(--fs-2xs); margin-top: var(--sp-3); }

.guide-body { padding: 0 var(--sp-5) var(--sp-5); }
.guide-body ol { list-style: none; margin: 0; padding: 0; display: grid; gap: var(--sp-4); counter-reset: step; }
.guide-body li { position: relative; padding-left: 36px; }
.guide-body li::before {
  counter-increment: step;
  content: counter(step, decimal-leading-zero);
  position: absolute;
  left: 0;
  top: 1px;
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border-radius: var(--r-sm);
  background: var(--brand-50);
  color: var(--brand-600);
  font-size: var(--fs-2xs);
  font-weight: var(--fw-semibold);
}
.guide-body strong { display: block; font-size: var(--fs-sm); font-weight: var(--fw-semibold); color: var(--text-primary); }
.guide-body p { margin: 5px 0 0; font-size: var(--fs-xs); line-height: var(--lh-base); }

/* ---------------- 当前空间头部 ---------------- */
.space-result {
  display: flex;
  align-items: center;
  gap: var(--sp-4);
  margin-top: var(--sp-6);
  padding: var(--sp-5) var(--sp-6);
  border-radius: var(--r-lg) var(--r-lg) 0 0;
  border-bottom: 0;
  background: var(--n-0);
}
.selected-space-icon {
  width: 50px;
  height: 50px;
  flex-shrink: 0;
  display: grid;
  place-items: center;
  background: var(--brand-50);
  border: 1px solid var(--brand-100);
  border-radius: var(--r-md);
  color: var(--brand-600);
}
.space-result > div { min-width: 0; flex: 1; }
.space-result h3 { overflow-wrap: anywhere; font-size: var(--fs-2xl); font-weight: var(--fw-semibold); letter-spacing: -.4px; margin: 6px 0; color: var(--text-primary); }
.space-result small { font-size: var(--fs-2xs); }
.space-result .eyebrow { letter-spacing: 1px; font-size: var(--fs-2xs); }
.meta-dot { padding: 0 var(--sp-2); color: var(--border-strong); }

.workspace-administration { margin-top: var(--sp-6); background: transparent; box-shadow: none; }
.workspace-administration > summary { color: var(--text-tertiary); font-size: var(--fs-xs); }
.workspace-administration > summary small { display: inline; margin-left: var(--sp-3); }
.workspace-administration :deep(.organization-panel),
.workspace-administration :deep(.password-panel) { margin: 0 var(--sp-4) var(--sp-4); }

.back-to-spaces {
  margin-top: var(--sp-4);
  padding: 8px 0;
  border: 0;
  background: transparent;
  font-size: var(--fs-xs);
  color: var(--brand-600);
}
.back-to-spaces:hover:not(:disabled) { background: transparent; color: var(--brand-700); }
.back-to-spaces > svg { transform: rotate(180deg); }
.back-to-spaces + .space-result { margin-top: var(--sp-3); }

/* ---------------- 响应式 ---------------- */
@media (max-width: 950px) {
  .space-cards { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .workspace-connection > small { width: 100%; margin: 0; }
  .library-heading .private-badge { display: none; }
}

@media (max-width: 760px) {
  .library-heading { padding: var(--sp-6); min-height: 200px; }
  .library-art { transform: scale(.8); transform-origin: right center; margin-right: -25px; width: 205px; }
  .library-heading h2 { font-size: 26px; }
}

@media (max-width: 650px) {
  .manager-grid { grid-template-columns: 1fr; gap: var(--sp-3); }
  .library-heading { margin-bottom: var(--sp-5); }
  h2 { font-size: var(--fs-xl); }
  .account-form { grid-template-columns: 1fr; margin-top: var(--sp-5); }
  .login-intro { padding: var(--sp-6); border-right: 0; border-bottom: 1px solid var(--border-subtle); }
  .login-intro h3 { margin-top: var(--sp-4); font-size: var(--fs-lg); }
  .login-benefits { display: none; }
  .account-form fieldset { padding: var(--sp-6); }
  .account-line { padding: var(--sp-3); }
  .connected-dot { display: none; }
  .enterprise-selector { width: 100%; }
  .enterprise-selector select { flex: 1; width: 0; }
  .directory-heading { flex-direction: column; align-items: stretch; gap: var(--sp-3); }
  .space-search { width: 100%; }
  .space-cards { grid-template-columns: 1fr; }
  .directory-pagination { align-items: flex-start; }
  .directory-pagination > span { max-width: 52%; }
  .space-result { padding: var(--sp-4); flex-wrap: wrap; gap: var(--sp-3); }
  .space-result h3 { font-size: var(--fs-xl); }
  .space-result > .private-badge { margin-left: 0; font-size: var(--fs-2xs); }
  .space-result > div { flex-basis: calc(100% - 64px); }
  .workspace-administration > summary small { display: block; margin-left: 0; }
}

@media (max-width: 600px) {
  .library-art { display: none; }
  .library-heading { padding: var(--sp-6) var(--sp-5); min-height: 0; }
  .library-heading h2 { font-size: 25px; }
  .library-heading .private-badge { display: inline-flex; }
  .enterprise-selector select { width: 100%; max-width: 100%; }
  .enterprise-selector { flex-wrap: wrap; }
  .workspace-connection > small { width: auto; margin-left: auto; font-size: var(--fs-2xs); }
}

@media (prefers-reduced-motion: reduce) {
  button, summary > svg:last-child { transition: none; }
}
</style>
