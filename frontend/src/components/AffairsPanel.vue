<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import { createEnterpriseClient, EnterpriseError, positiveId } from '../enterprise';
import type { Account } from '../enterprise';
import { useSession } from '../session';
import type { AffairApplication, AffairEligibility, AffairFinanceApplication, AffairMember,
  AffairPending, AffairReceipt, AffairsRule, AffairSettlement, AffairSummary, AffairsPage } from '../affairs';
import AppIcon from './AppIcon.vue';
import RetryNotice from './RetryNotice.vue';

// 员工事务：申请、参与确认、凭证与财务协同。金额一律以“分”的字符串提交。
const api = createEnterpriseClient();
// 登录状态与工作台、企业问答、知识空间共用同一份，不再各自保存副本。
const { user, ensure, login: signIn, logout: signOut, markAnonymous } = useSession();
const username = ref('');
const password = ref('');
const enterpriseId = ref('');
const tab = ref<'mine' | 'confirm' | 'finance'>('mine');
const busy = ref('checking');
const message = ref('正在核对登录状态…');
const failed = ref(false);
let mounted = true;

/**
 * 每个区块独立加载：某个接口失败只让那一块显示「没读出来」，其余区块照常渲染。
 * 之前这里是 6 个裸 await 串起来，规则表一变动或超时，员工的申请列表就整片空白。
 */
type BlockKey = 'rules' | 'members' | 'mine' | 'pending' | 'finance' | 'settlements';
const blockLabels: Record<BlockKey, string> = {
  rules: '补贴规则', members: '成员名单', mine: '我的申请',
  pending: '待我确认', finance: '财务清单', settlements: '结算单'
};
const blockErrors = reactive<Record<BlockKey, string>>({
  rules: '', members: '', mine: '', pending: '', finance: '', settlements: ''
});

const role = computed(() => user.value?.enterprises?.find(item => item.enterpriseId === enterpriseId.value)?.role);
const isAdmin = computed(() => role.value === 'ADMIN');
const isStaff = computed(() => role.value === 'ADMIN' || role.value === 'FINANCE');

const rules = ref<AffairsPage<AffairsRule>>();
const members = ref<AffairMember[]>([]);
const mine = ref<AffairsPage<AffairSummary>>();
const pending = ref<AffairsPage<AffairPending>>();
const detail = ref<AffairApplication>();
const receipts = ref<AffairsPage<AffairReceipt>>();
const finance = ref<AffairsPage<AffairFinanceApplication>>();
const settlements = ref<AffairsPage<AffairSettlement>>();
const eligibility = ref<AffairEligibility>();
const minePage = ref(1);
const pendingPage = ref(1);
const financePage = ref(1);
const settlementPage = ref(1);

// 界面统一按“元”填写，提交前在这里转成分；后端只认分，转换失败一律给出人话提示。
const newApp = reactive({ id: crypto.randomUUID(), ruleId: '', platform: '', customType: '', orderNo: '', occurredAt: '', amountYuan: '', shares: [share()] });
const reviseForm = reactive({ reason: '', amountYuan: '', shares: [share()] });
const ruleForm = reactive({ name: '', period: 'MONTH', memberLimitYuan: '', applicationLimitYuan: '', requiresManager: false });
const settleForm = reactive({ applicationId: '', payeeMemberId: '', note: '' });
const refundForm = reactive({ applicationId: '', kind: 'FULL' as 'FULL' | 'PARTIAL', amountYuan: '', reason: '' });
const confirmNote = reactive<Record<string, string>>({});

/** 页面上分摊行按“元”填写，提交时才转换成后端要求的分。 */
type ShareRow = { memberId: string; shareYuan: string };
function share(): ShareRow { return { memberId: '', shareYuan: '' }; }
function yuan(value: string) {
  const amount = BigInt(value);
  return `${amount / 100n}.${(amount % 100n).toString().padStart(2, '0')}`;
}
/**
 * 费用类型：后端 platform 字段只作为分类标识，不参与额度计算。
 * 预置项覆盖常见报销场景；选「其他」时允许员工自己写，不必被迫归类。
 */
const expenseTypes = [
  { value: 'travel-hotel', label: '出差住宿', hint: '酒店、宾馆、民宿，按实际住宿天数报销' },
  { value: 'travel-transport', label: '交通出行', hint: '机票、高铁、打车、油费与过路费' },
  { value: 'meal', label: '餐饮招待', hint: '工作餐、加班餐、客户来访招待' },
  { value: 'office', label: '办公采购', hint: '办公用品、耗材、书籍资料' },
  { value: 'activity', label: '活动经费', hint: '团建、会议、培训、学生活动的场地与物料' },
  { value: 'reward', label: '举报奖励', hint: '实名举报经核实后按制度发放' },
  { value: 'other', label: '其他（自己填写）', hint: '以上都不合适时，自己写一个名称' }
];
/** 历史数据里存的是商城标识，展示时仍翻回中文，避免旧记录变成一串英文。 */
const legacyPlatforms: Record<string, string> = {
  taobao: '淘宝 / 天猫', meituan: '美团', eleme: '饿了么', jd: '京东', pdd: '拼多多', offline: '线下门店'
};
function platformLabel(value: string) {
  return expenseTypes.find(item => item.value === value)?.label ?? legacyPlatforms[value] ?? value;
}
/** 选中「其他」时用员工自己写的名称提交，其余情况直接用预置 key。 */
const isCustomExpense = computed(() => newApp.platform === 'other');
const expenseHint = computed(() => expenseTypes.find(item => item.value === newApp.platform)?.hint ?? '');
/** 元 → 分。接受 30、30.5、30.50，拒绝三位小数和超范围。 */
function toCents(value: string): string {
  const text = value.trim();
  if (!/^\d{1,8}(\.\d{1,2})?$/.test(text)) throw new EnterpriseError('金额请填写数字，最多两位小数，例如 30 或 30.50。');
  const [whole, frac = ''] = text.split('.') as [string, string?];
  const cents = BigInt(whole) * 100n + BigInt((frac + '00').slice(0, 2));
  if (cents < 1n || cents > 1000000000n) throw new EnterpriseError('金额需在 0.01 元至 10000000 元之间。');
  return cents.toString();
}
/** 分摊金额可以为 0（本人以外由他人承担），但不能为负。 */
function toShareCents(value: string): string {
  const text = value.trim() || '0';
  if (!/^\d{1,8}(\.\d{1,2})?$/.test(text)) throw new EnterpriseError('分摊金额请填写数字，最多两位小数，例如 15 或 15.00。');
  const [whole, frac = ''] = text.split('.') as [string, string?];
  const cents = BigInt(whole) * 100n + BigInt((frac + '00').slice(0, 2));
  if (cents > 1000000000n) throw new EnterpriseError('分摊金额不能超过 10000000 元。');
  return cents.toString();
}
/** 浏览器 datetime-local 给的是 2026-10-25T14:30，后端要 2026-10-25 14:30:00。 */
function toServerTime(value: string): string | null {
  if (!value.trim()) return null;
  const matched = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(value.trim());
  if (!matched) throw new EnterpriseError('请选择有效的发生时间。');
  return `${matched[1]}-${matched[2]}-${matched[3]} ${matched[4]}:${matched[5]}:00`;
}
/** 分摊合计是否等于总金额，用来即时提示，而不是等后端报错。 */
const shareTotalCents = computed(() => {
  let total = 0n;
  for (const row of newApp.shares) {
    try { total += BigInt(toShareCents(row.shareYuan)); } catch { return null; }
  }
  return total;
});
const amountCentsForCheck = computed(() => {
  try { return BigInt(toCents(newApp.amountYuan)); } catch { return null; }
});
const shareMatches = computed(() => shareTotalCents.value !== null && amountCentsForCheck.value !== null
  && shareTotalCents.value === amountCentsForCheck.value);
/** 差额用来即时提示“还差多少钱”，避免员工自己心算出错。 */
const shareGapYuan = computed(() => {
  const amount = amountCentsForCheck.value;
  const total = shareTotalCents.value;
  if (amount === null || total === null) return null;
  const gap = amount > total ? amount - total : total - amount;
  return yuan(gap.toString());
});
const statusText: Record<string, string> = { SUBMITTED: '待确认', CONFIRMING: '确认中', VERIFIED: '待主管批准',
  PENDING_SETTLEMENT: '待结算', SETTLED: '已结算', REJECTED: '已退回', CANCELLED: '已撤回',
  PENDING: '待批准', APPROVED: '已批准', RETURNED: '已退回', VOID: '已作废' };
const periodText: Record<string, string> = { MONTH: '每月', QUARTER: '每季', YEAR: '每年', NONE: '不限周期' };

async function run(label: string, work: () => Promise<void>) {
  if (busy.value) return;
  busy.value = label;
  failed.value = false;
  try { await work(); }
  catch (error) {
    if (!mounted) return;
    failed.value = true;
    message.value = error instanceof Error ? error.message : '操作未完成，请稍后手动重试。';
    if (error instanceof EnterpriseError && error.code === 401) markAnonymous();
  } finally {
    if (mounted) busy.value = '';
    password.value = '';
  }
}
function ready() { return !!user.value && positiveId(enterpriseId.value); }

async function login() {
  await run('login', async () => { await connect(await signIn(username.value, password.value)); });
}
async function connect(current: Account) {
  enterpriseId.value = current.enterprises?.length === 1 ? current.enterprises[0]!.enterpriseId : '';
  if (!current.enterprises) { failed.value = true; message.value = '登录信息尚不包含所属企业，请重启最新版后端后刷新。'; return; }
  if (!current.enterprises.length) { message.value = '账号尚未分配企业，请联系管理员开通成员身份。'; return; }
  if (!enterpriseId.value) { message.value = '请选择你要进入的企业工作区。'; return; }
  message.value = '已连接企业工作区。';
  await loadAll();
}
async function logout() {
  await run('logout', async () => {
    await signOut();
    detail.value = undefined;
    rules.value = mine.value = pending.value = finance.value = settlements.value = receipts.value = undefined;
    username.value = enterpriseId.value = '';
    message.value = '已退出登录。';
  });
}
/** 区块级加载：失败只记在自己这块，不中断其它区块。 */
async function guard(key: BlockKey, work: () => Promise<void>) {
  try {
    await work();
    if (mounted) blockErrors[key] = '';
    return true;
  } catch (error) {
    if (!mounted) return false;
    if (error instanceof EnterpriseError && error.code === 401) markAnonymous();
    blockErrors[key] = error instanceof Error ? error.message : '加载失败，请稍后重试。';
    return false;
  }
}
/** 顶部汇总：说清哪几块没读出来，并强调其余内容仍然可用。 */
function syncSummary() {
  const broken = (Object.keys(blockErrors) as BlockKey[]).filter(key => blockErrors[key]);
  failed.value = broken.length > 0;
  message.value = broken.length
    ? `${broken.map(key => blockLabels[key]).join('、')}暂时没读出来；其它内容照常可用，可在对应区块点「重试」。`
    : '已读取最新的申请、确认与结算数据。';
}
/** 区块内单独重试，不必整页刷新。 */
async function retry(key: BlockKey) {
  if (busy.value) return;
  busy.value = `block-${key}`;
  try { await guard(key, blockReload[key]); }
  finally {
    if (mounted) { busy.value = ''; syncSummary(); }
  }
}
async function reload() {
  if (!ready()) return;
  // 四个区块互不依赖，并行发起；任何一块失败都不影响其余三块的渲染。
  const jobs: Promise<unknown>[] = [
    guard('rules', loadRules),
    guard('members', loadMembers),
    guard('mine', () => loadMine(minePage.value)),
    guard('pending', () => loadPending(pendingPage.value))
  ];
  if (isStaff.value) {
    jobs.push(guard('finance', () => loadFinance(financePage.value)));
    jobs.push(guard('settlements', () => loadSettlements(settlementPage.value)));
  } else {
    // 无财务权限的人本来就不看这两块，不应该留下失败痕迹。
    blockErrors.finance = blockErrors.settlements = '';
  }
  await Promise.all(jobs);
  if (mounted) syncSummary();
}
async function loadAll() {
  await reload();
}
async function loadRules() {
  if (!ready()) return;
  rules.value = await api.affairsRules(enterpriseId.value);
  blockErrors.rules = '';
}
async function loadMembers() {
  if (!ready()) return;
  members.value = await api.affairMemberOptions(enterpriseId.value);
  blockErrors.members = '';
}
async function loadMine(page: number) {
  if (!ready()) return;
  minePage.value = page;
  mine.value = await api.affairApplications(enterpriseId.value, null, page);
  blockErrors.mine = '';
}
async function loadPending(page: number) {
  if (!ready()) return;
  pendingPage.value = page;
  pending.value = await api.affairConfirmations(enterpriseId.value, page);
  blockErrors.pending = '';
}
async function loadFinance(page: number) {
  if (!ready() || !isStaff.value) return;
  financePage.value = page;
  finance.value = await api.affairFinanceApplications(enterpriseId.value, null, page);
  blockErrors.finance = '';
}
async function loadSettlements(page: number) {
  if (!ready() || !isStaff.value) return;
  settlementPage.value = page;
  settlements.value = await api.affairSettlements(enterpriseId.value, null, page);
  blockErrors.settlements = '';
}
const blockReload: Record<BlockKey, () => Promise<void>> = {
  rules: () => loadRules(),
  members: () => loadMembers(),
  mine: () => loadMine(minePage.value),
  pending: () => loadPending(pendingPage.value),
  finance: () => loadFinance(financePage.value),
  settlements: () => loadSettlements(settlementPage.value)
};
async function loadReceipts(applicationId: string) {
  receipts.value = await api.affairReceipts(enterpriseId.value, applicationId);
}

async function preview() {
  if (!ready() || !positiveId(newApp.ruleId) || !newApp.amountYuan.trim()) return;
  await run('preview', async () => {
    eligibility.value = await api.affairEligibility(enterpriseId.value, newApp.ruleId, toCents(newApp.amountYuan));
    message.value = `本期还可以申请 ${yuan(eligibility.value.applicableCents)} 元。`;
  });
}
async function submitApplication() {
  if (!ready()) return;
  // 「其他」写的是员工自己的话，直接作为分类名提交；预置项仍用固定 key，便于统计。
  const platform = isCustomExpense.value ? newApp.customType.trim() : newApp.platform;
  if (!platform) { failed.value = true; message.value = '请先选择费用类型，或写明具体是什么费用。'; return; }
  await run('create', async () => {
    const created = await api.createAffairApplication(enterpriseId.value, {
      id: newApp.id, ruleId: newApp.ruleId, platform,
      externalOrderNo: newApp.orderNo.trim() ? newApp.orderNo.trim() : null,
      occurredAt: toServerTime(newApp.occurredAt),
      amountCents: toCents(newApp.amountYuan),
      participants: newApp.shares.map(row => ({ memberId: row.memberId, shareCents: toShareCents(row.shareYuan) }))
    });
    detail.value = created;
    newApp.id = crypto.randomUUID();
    newApp.ruleId = newApp.platform = newApp.customType = newApp.orderNo = newApp.occurredAt = newApp.amountYuan = '';
    newApp.shares = [share()];
    eligibility.value = undefined;
    message.value = '申请已提交；等参与人确认后，进入主管批准或结算。';
    await loadMine(1);
    await loadPending(1);
    if (isStaff.value) await loadFinance(1);
  });
}
async function openDetail(applicationId: string) {
  await run('detail', async () => {
    detail.value = await api.affairApplication(enterpriseId.value, applicationId);
    reviseForm.reason = '';
    reviseForm.amountYuan = yuan(detail.value.amountCents);
    reviseForm.shares = detail.value.participants.map(item => ({ memberId: item.memberId, shareYuan: yuan(item.shareCents) }));
    await loadReceipts(applicationId);
  });
}
async function revise() {
  if (!detail.value) return;
  await run('revise', async () => {
    const changed = await api.reviseAffairApplication(enterpriseId.value, detail.value!.id, detail.value!.currentVersion,
      toCents(reviseForm.amountYuan), reviseForm.shares.map(row => ({ memberId: row.memberId, shareCents: toShareCents(row.shareYuan) })), reviseForm.reason);
    detail.value = changed;
    reviseForm.amountYuan = yuan(changed.amountCents);
    reviseForm.shares = changed.participants.map(item => ({ memberId: item.memberId, shareYuan: yuan(item.shareCents) }));
    message.value = '已生成新版本，旧版本的确认失效，参与人需要重新确认。';
    await loadMine(minePage.value);
    await loadPending(pendingPage.value);
  });
}
async function cancelApplication() {
  if (!detail.value) return;
  await run('cancel', async () => {
    detail.value = await api.cancelAffairApplication(enterpriseId.value, detail.value!.id);
    message.value = '申请已撤回，占用额度已释放。';
    await loadMine(minePage.value);
  });
}
async function confirm(item: AffairPending, decision: 'CONFIRM' | 'REJECT') {
  await run('confirm', async () => {
    await api.confirmAffairApplication(enterpriseId.value, item.applicationId, item.version, decision,
      confirmNote[item.participantId]?.trim() ? confirmNote[item.participantId]!.trim() : null);
    message.value = decision === 'CONFIRM' ? '已确认参与；全部确认后进入下一环节。' : '已拒绝参与；该申请退回并释放额度。';
    await loadPending(pendingPage.value);
    await loadMine(minePage.value);
  });
}
async function uploadReceipt(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file || !detail.value) return;
  await run('receipt', async () => {
    await api.uploadAffairReceipt(enterpriseId.value, detail.value!.id, file);
    await loadReceipts(detail.value!.id);
    message.value = '凭证原件已保存；仅供人工核对，不做OCR。';
  });
}
async function download(receipt: AffairReceipt) {
  if (!detail.value) return;
  await run('download', async () => {
    const blob = await api.downloadAffairReceipt(enterpriseId.value, detail.value!.id, receipt.id);
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = receipt.originalName;
    link.click();
    URL.revokeObjectURL(url);
  });
}
async function approveApplication(item: AffairFinanceApplication) {
  await run('approve', async () => {
    await api.approveAffairApplication(enterpriseId.value, item.id);
    message.value = '已批准，申请进入待结算。';
    await loadFinance(financePage.value);
  });
}
async function createRule() {
  if (!ready()) return;
  await run('rule', async () => {
    await api.createAffairRule(enterpriseId.value, {
      name: ruleForm.name, period: ruleForm.period as AffairsRule['period'],
      memberLimitCents: ruleForm.memberLimitYuan.trim() ? toCents(ruleForm.memberLimitYuan) : null,
      applicationLimitCents: toCents(ruleForm.applicationLimitYuan), requiresManager: ruleForm.requiresManager
    });
    ruleForm.name = ruleForm.memberLimitYuan = ruleForm.applicationLimitYuan = '';
    ruleForm.requiresManager = false;
    message.value = '规则草稿已创建；确认无误后激活。';
    await loadRules();
  });
}
async function activateRule(rule: AffairsRule) {
  await run('activate', async () => {
    await api.activateAffairRule(enterpriseId.value, rule.id);
    message.value = '规则已激活；同名旧规则自动停用。';
    await loadRules();
  });
}
async function createSettlement() {
  if (!ready()) return;
  await run('settlement', async () => {
    await api.createAffairSettlement(enterpriseId.value, settleForm.applicationId, settleForm.payeeMemberId,
      settleForm.note.trim() ? settleForm.note.trim() : null);
    settleForm.applicationId = settleForm.payeeMemberId = settleForm.note = '';
    message.value = '结算单已开出，需经批准后才能标记结算。';
    await loadFinance(financePage.value);
    await loadSettlements(1);
  });
}
async function approveSettlement(item: AffairSettlement) {
  await run('approve-settlement', async () => {
    await api.approveAffairSettlement(enterpriseId.value, item.id);
    message.value = '结算单已批准，可标记结算。';
    await loadSettlements(settlementPage.value);
  });
}
async function settle(item: AffairSettlement) {
  await run('settle', async () => {
    await api.settleAffairSettlement(enterpriseId.value, item.id);
    message.value = '已标记结算：预占额度转为已用。';
    await loadSettlements(settlementPage.value);
    await loadFinance(financePage.value);
    await loadMine(minePage.value);
  });
}
async function refundApplication() {
  if (!ready()) return;
  await run('refund', async () => {
    await api.refundAffairApplication(enterpriseId.value, refundForm.applicationId, refundForm.kind,
      toCents(refundForm.amountYuan), refundForm.reason);
    refundForm.applicationId = refundForm.amountYuan = refundForm.reason = '';
    refundForm.kind = 'FULL';
    message.value = '退款已登记；额度按退款金额回退，原结算记录保留审计。';
    await loadFinance(financePage.value);
    await loadSettlements(settlementPage.value);
  });
}
function addShare(list: ShareRow[]) { if (list.length < 20) list.push(share()); }
function removeShare(list: ShareRow[], index: number) { if (list.length > 1) list.splice(index, 1); }
function selectTab(next: 'mine' | 'confirm' | 'finance') {
  tab.value = next;
  eligibility.value = undefined;
  if (next === 'confirm') void run('pending', () => loadPending(pendingPage.value));
  if (next === 'finance' && isStaff.value) void run('finance', async () => { await loadFinance(financePage.value); await loadSettlements(settlementPage.value); });
}

watch(enterpriseId, () => {
  detail.value = undefined;
  receipts.value = undefined;
  eligibility.value = undefined;
  if (ready()) void run('enterprise', loadAll);
});

onMounted(async () => {
  try {
    const current = await ensure();
    if (!mounted) return;
    if (current) await connect(current);
    else message.value = '请使用企业账号登录，不开放公开注册。';
  } catch (error) {
    if (mounted) {
      failed.value = true;
      message.value = error instanceof Error ? error.message : '登录状态核对失败。';
    }
  } finally { if (mounted) busy.value = ''; }
});
onUnmounted(() => { mounted = false; password.value = ''; api.dispose(); });
</script>

<template>
  <section class="affairs-panel" aria-label="员工事务">
    <div class="account-line" v-if="user"><div class="account-identity"><span class="account-avatar"><AppIcon name="briefcase" :size="19" /></span><div><strong>{{ user.enterprises?.find(item => item.enterpriseId === enterpriseId)?.enterpriseName ?? '企业工作区' }}</strong><small>{{ isAdmin ? '企业管理员' : role === 'FINANCE' ? '财务人员' : '企业成员' }} · 已登录</small></div><span class="connected-dot">身份已连接</span></div><button type="button" class="quiet-button" :disabled="!!busy" @click="logout">{{ busy === 'logout' ? '正在退出…' : '退出登录' }}</button></div>
    <form v-else class="account-form" aria-label="企业账号登录" @submit.prevent="login">
      <fieldset :disabled="!!busy"><legend>企业账号登录</legend><p class="login-caption">使用管理员为你开通的账号</p><label for="affairs-username">账号<input id="affairs-username" v-model="username" autocomplete="username" placeholder="输入企业账号" required maxlength="64" /></label><label for="affairs-password">密码<input id="affairs-password" v-model="password" type="password" autocomplete="current-password" placeholder="输入登录密码" required maxlength="256" /></label><button class="solid" type="submit">{{ busy === 'login' ? '正在登录…' : '登录企业账号' }}<AppIcon name="chevron" :size="15" /></button></fieldset>
      <small>密码只随本次登录提交，不写入本机存储。</small>
    </form>

    <template v-if="user">
      <div class="workspace-connection">
        <label v-if="(user.enterprises?.length ?? 0) > 1" class="enterprise-selector" for="affairs-enterprise"><span><AppIcon name="briefcase" :size="15" />企业工作区</span><select id="affairs-enterprise" v-model="enterpriseId" :disabled="!!busy"><option value="" disabled>选择所属企业</option><option v-for="item in user.enterprises" :key="item.enterpriseId" :value="item.enterpriseId">{{ item.enterpriseName }}</option></select></label>
        <span v-else class="enterprise-name"><AppIcon name="shield" :size="15" />已自动连接所属企业</span>
        <nav class="affairs-tabs" aria-label="员工事务视图"><button type="button" :class="{ active: tab === 'mine' }" @click="selectTab('mine')">我的申请</button><button type="button" :class="{ active: tab === 'confirm' }" @click="selectTab('confirm')">待我确认<span v-if="pending?.total" class="count-badge">{{ pending.total }}</span></button><button v-if="isStaff" type="button" :class="{ active: tab === 'finance' }" @click="selectTab('finance')">财务协同</button></nav>
        <button type="button" class="quiet-button" :disabled="!!busy || !ready()" @click="run('refresh', loadAll)"><AppIcon name="refresh" :size="15" />{{ busy === 'refresh' ? '正在刷新…' : '刷新' }}</button>
      </div>
      <p class="panel-feedback" :class="{ failed }" role="status" aria-live="polite"><AppIcon :name="failed ? 'info' : 'check'" :size="15" />{{ message }}</p>

      <section v-if="tab === 'mine'" class="affairs-view">
        <article class="affair-block affair-compose">
          <header class="block-head">
            <span class="block-icon"><AppIcon name="briefcase" :size="20" /></span>
            <div>
              <h3>提交一笔补贴申请</h3>
              <p class="block-hint">按顺序填四步就可以。带 <em>必填</em> 标记的是必须填的，其余留空也没关系。</p>
            </div>
          </header>
          <form class="affair-form" @submit.prevent="submitApplication">
            <fieldset class="form-step">
              <legend><span class="step-no">1</span>适用哪条补贴规则</legend>
              <label class="field"><span class="field-name">补贴规则<em>必填</em></span>
                <select v-model="newApp.ruleId" required>
                  <option value="" disabled>请选择一条生效中的规则</option>
                  <option v-for="item in rules?.records.filter(row => row.status === 'ACTIVE') ?? []" :key="item.id" :value="item.id">{{ item.name }}（第 {{ item.version }} 版）</option>
                </select>
              </label>
              <p v-if="rules && !rules.records.some(row => row.status === 'ACTIVE')" class="field-warn">
                <AppIcon name="info" :size="14" />现在还没有生效中的规则。请联系管理员到「财务协同」里创建并激活一条，之后这里就能选了。
              </p>
              <RetryNotice v-if="blockErrors.rules" class="form-wide" :message="blockErrors.rules" :busy="!!busy" label="补贴规则" @retry="retry('rules')" />
            </fieldset>

            <fieldset class="form-step">
              <legend><span class="step-no">2</span>这笔钱属于哪一类</legend>
              <label class="field"><span class="field-name">费用类型<em>必填</em></span>
                <select v-model="newApp.platform" required>
                  <option value="" disabled>请选择费用类型</option>
                  <option v-for="item in expenseTypes" :key="item.value" :value="item.value">{{ item.label }}</option>
                </select>
                <small v-if="expenseHint">{{ expenseHint }}</small>
              </label>
              <label v-if="isCustomExpense" class="field"><span class="field-name">具体是什么费用<em>必填</em></span>
                <input v-model="newApp.customType" required maxlength="32" placeholder="例如 实验耗材、竞赛报名费" />
                <small>用自己的话说清楚就行，审核的人能看懂。</small>
              </label>
              <label class="field">订单号<small>选填，没有就留空</small>
                <input v-model="newApp.orderNo" maxlength="64" placeholder="例如订单号、发票号" />
              </label>
              <label class="field">消费时间<small>选填，没有就留空</small>
                <input v-model="newApp.occurredAt" type="datetime-local" />
              </label>
            </fieldset>

            <fieldset class="form-step">
              <legend><span class="step-no">3</span>申请金额</legend>
              <label class="field field-amount"><span class="field-name">金额（元）<em>必填</em></span>
                <span class="amount-input"><span class="unit">¥</span><input v-model="newApp.amountYuan" inputmode="decimal" required maxlength="12" placeholder="例如 30 或 30.50" /></span>
                <small>直接按元填写，最多两位小数，不用自己换算成分。</small>
              </label>
            </fieldset>

            <fieldset class="form-step">
              <legend><span class="step-no">4</span>和谁一起分摊</legend>
              <p class="step-note">必须包含你自己。所有人的分摊加起来，要正好等于第 3 步的金额。</p>
              <div v-for="(row, index) in newApp.shares" :key="index" class="share-row">
                <label class="field">参与人<select v-model="row.memberId" required><option value="" disabled>请选择同事</option><option v-for="member in members" :key="member.memberId" :value="member.memberId">{{ member.username }}</option></select></label>
                <label class="field">分摊（元）<span class="amount-input"><span class="unit">¥</span><input v-model="row.shareYuan" inputmode="decimal" required maxlength="12" placeholder="0.00" /></span></label>
                <button type="button" class="quiet-button icon-only" :disabled="newApp.shares.length <= 1" aria-label="移除这一行" title="移除这一行" @click="removeShare(newApp.shares, index)"><AppIcon name="close" :size="14" /></button>
              </div>
              <button type="button" class="quiet-button add-share" :disabled="newApp.shares.length >= 20" @click="addShare(newApp.shares)"><AppIcon name="plus" :size="14" />再加一位同事</button>
              <RetryNotice v-if="blockErrors.members" class="form-wide" :message="blockErrors.members" :busy="!!busy" label="成员名单" @retry="retry('members')" />
              <div class="share-summary" :class="{ matched: shareMatches, mismatch: !shareMatches && shareTotalCents !== null && amountCentsForCheck !== null }" role="status">
                <AppIcon :name="shareMatches ? 'check' : 'info'" :size="15" />
                <span v-if="shareMatches">分摊合计与金额一致，可以提交了</span>
                <span v-else-if="shareGapYuan !== null">分摊合计和金额还差 {{ shareGapYuan }} 元，请调整后再提交</span>
                <span v-else>填好金额和分摊后，这里会告诉你能不能对得上</span>
              </div>
            </fieldset>

            <div class="form-actions">
              <button type="button" class="quiet-button" :disabled="!!busy || !newApp.ruleId || !newApp.amountYuan" @click="preview"><AppIcon name="search" :size="15" />先算一下额度</button>
              <button class="solid" type="submit" :disabled="!!busy">提交申请<AppIcon name="chevron" :size="15" /></button>
            </div>
            <p v-if="eligibility" class="eligibility"><AppIcon name="info" :size="14" />本期还可以申请 {{ yuan(eligibility.applicableCents) }} 元 · 已用 {{ yuan(eligibility.usedCents) }} 元 · 已预占 {{ yuan(eligibility.reservedCents) }} 元</p>
          </form>
        </article>

        <article class="affair-block">
          <h3>我的申请<span v-if="mine" class="count-badge">{{ mine.total }}</span></h3>
          <RetryNotice v-if="blockErrors.mine" :message="blockErrors.mine" :busy="!!busy" label="我的申请" @retry="retry('mine')" />
          <ul v-if="mine?.records.length" class="affair-list">
            <li v-for="item in mine.records" :key="item.id"><button type="button" class="affair-row" @click="openDetail(item.id)"><span class="row-main"><strong>{{ item.ruleName }}</strong><small>{{ platformLabel(item.platform) }} · {{ item.occurredAt }} · 第{{ item.currentVersion }}版</small></span><span class="row-side"><strong>{{ yuan(item.amountCents) }} 元</strong><span class="status-pill">{{ statusText[item.status] ?? item.status }}</span></span></button></li>
          </ul>
          <p v-else-if="mine" class="empty">还没有申请记录。</p>
          <div v-if="mine && mine.pages > 1" class="pager"><button type="button" class="quiet-button" :disabled="!!busy || minePage <= 1" @click="run('mine', () => loadMine(minePage - 1))">上一页</button><span>{{ minePage }} / {{ mine.pages }}</span><button type="button" class="quiet-button" :disabled="!!busy || minePage >= mine.pages" @click="run('mine', () => loadMine(minePage + 1))">下一页</button></div>
        </article>

        <article v-if="detail" class="affair-block">
          <h3>申请详情<span class="status-pill">{{ statusText[detail.status] ?? detail.status }}</span></h3>
          <dl class="affair-facts"><div><dt>规则</dt><dd>{{ detail.ruleName }} · v{{ detail.ruleVersion }}</dd></div><div><dt>费用类型 / 订单号</dt><dd>{{ platformLabel(detail.platform) }} / {{ detail.externalOrderNo ?? '未填写' }}</dd></div><div><dt>发生时间</dt><dd>{{ detail.occurredAt }}</dd></div><div><dt>金额</dt><dd>{{ yuan(detail.amountCents) }} 元</dd></div><div><dt>额度周期</dt><dd>{{ detail.quotaPeriod }}</dd></div><div><dt>版本</dt><dd>第 {{ detail.currentVersion }} 版</dd></div></dl>
          <ul class="participant-list"><li v-for="item in detail.participants" :key="item.id"><span>{{ item.username }}</span><span>{{ yuan(item.shareCents) }} 元</span><span class="status-pill">{{ statusText[item.status] ?? item.status }}</span></li></ul>
          <div class="inline-actions"><label class="file-label">上传凭证<input type="file" accept=".jpg,.jpeg,.png,.pdf" :disabled="!!busy" @change="uploadReceipt" /></label><button type="button" class="quiet-button" :disabled="!!busy || !['SUBMITTED', 'CONFIRMING', 'VERIFIED'].includes(detail.status)" @click="cancelApplication">撤回申请</button></div>
          <ul v-if="receipts?.records.length" class="receipt-list"><li v-for="item in receipts!.records" :key="item.id"><span>{{ item.originalName }} · {{ (item.byteSize / 1024).toFixed(0) }}KB</span><button type="button" class="quiet-button" :disabled="!!busy" @click="download(item)">下载原件</button></li></ul>
          <p v-else-if="receipts" class="empty">尚未上传凭证；不做OCR，原件仅供人工核对。</p>
          <div v-if="!['SETTLED', 'REJECTED', 'CANCELLED'].includes(detail.status)" class="revise-box">
            <h4>修改申请（生成新版本）</h4>
            <label>新金额（元）<input v-model="reviseForm.amountYuan" inputmode="decimal" maxlength="12" /></label>
            <div class="share-editor"><span>参与人与分摊（元）</span><div v-for="(row, index) in reviseForm.shares" :key="index" class="share-row"><select v-model="row.memberId"><option value="" disabled>选择成员</option><option v-for="member in members" :key="member.memberId" :value="member.memberId">{{ member.username }}</option></select><input v-model="row.shareYuan" inputmode="decimal" maxlength="12" placeholder="分摊金额（元）" /><button type="button" class="quiet-button" @click="removeShare(reviseForm.shares, index)">移除</button></div><button type="button" class="quiet-button" @click="addShare(reviseForm.shares)">添加参与人</button></div>
            <label>变更原因<input v-model="reviseForm.reason" maxlength="200" required placeholder="1至200个字符" /></label>
            <button class="solid" type="button" :disabled="!!busy || !reviseForm.reason.trim()" @click="revise">提交修改</button>
          </div>
        </article>
      </section>

      <section v-if="tab === 'confirm'" class="affairs-view">
        <article class="affair-block">
          <h3>待我确认<span v-if="pending" class="count-badge">{{ pending.total }}</span></h3>
          <p class="block-hint">只确认当前版本的参与与分摊；拒绝会让整单退回并释放额度。</p>
          <RetryNotice v-if="blockErrors.pending" :message="blockErrors.pending" :busy="!!busy" label="待确认记录" @retry="retry('pending')" />
          <ul v-if="pending?.records.length" class="affair-list">
            <li v-for="item in pending.records" :key="item.participantId" class="confirm-item"><div class="row-main"><strong>{{ item.ruleName }}</strong><small>{{ platformLabel(item.platform) }} · {{ item.occurredAt }} · 第{{ item.version }}版 · 我的分摊 {{ yuan(item.shareCents) }} 元</small></div><input v-model="confirmNote[item.participantId]" maxlength="200" placeholder="确认说明（可选）" /><div class="inline-actions"><button type="button" class="solid" :disabled="!!busy" @click="confirm(item, 'CONFIRM')">确认参与</button><button type="button" class="quiet-button" :disabled="!!busy" @click="confirm(item, 'REJECT')">拒绝</button></div></li>
          </ul>
          <p v-else-if="pending" class="empty">没有待确认的参与记录。</p>
          <div v-if="pending && pending.pages > 1" class="pager"><button type="button" class="quiet-button" :disabled="!!busy || pendingPage <= 1" @click="run('pending', () => loadPending(pendingPage - 1))">上一页</button><span>{{ pendingPage }} / {{ pending.pages }}</span><button type="button" class="quiet-button" :disabled="!!busy || pendingPage >= pending.pages" @click="run('pending', () => loadPending(pendingPage + 1))">下一页</button></div>
        </article>
      </section>

      <section v-if="tab === 'finance' && isStaff" class="affairs-view">
        <article v-if="isAdmin" class="affair-block">
          <h3>补贴规则</h3>
          <RetryNotice v-if="blockErrors.rules" :message="blockErrors.rules" :busy="!!busy" label="补贴规则" @retry="retry('rules')" />
          <ul v-if="rules?.records.length" class="affair-list"><li v-for="item in rules.records" :key="item.id" class="confirm-item"><div class="row-main"><strong>{{ item.name }} · v{{ item.version }}</strong><small>{{ periodText[item.period] ?? item.period }} · 单笔上限 {{ yuan(item.applicationLimitCents) }} 元 · 成员上限 {{ item.memberLimitCents === null ? '不限' : yuan(item.memberLimitCents) + ' 元' }} · {{ item.requiresManager ? '需主管批准' : '无需主管批准' }}</small></div><span class="status-pill">{{ item.status }}</span><button type="button" class="quiet-button" :disabled="!!busy || item.status === 'ACTIVE'" @click="activateRule(item)">激活</button></li></ul>
          <form class="affair-form" @submit.prevent="createRule"><label>规则名称<input v-model="ruleForm.name" required maxlength="128" /></label><label>额度周期<select v-model="ruleForm.period"><option value="MONTH">每月</option><option value="QUARTER">每季</option><option value="YEAR">每年</option><option value="NONE">不限周期</option></select></label><label>成员周期上限（元，可空）<input v-model="ruleForm.memberLimitYuan" inputmode="decimal" maxlength="12" placeholder="留空表示不限" /></label><label>单笔上限（元）<input v-model="ruleForm.applicationLimitYuan" inputmode="decimal" required maxlength="12" /></label><label class="inline-check"><input v-model="ruleForm.requiresManager" type="checkbox" />需主管批准</label><button class="solid" type="submit" :disabled="!!busy">创建规则草稿</button></form>
        </article>

        <article class="affair-block">
          <h3>财务清单<span v-if="finance" class="count-badge">{{ finance.total }}</span></h3>
          <RetryNotice v-if="blockErrors.finance" :message="blockErrors.finance" :busy="!!busy" label="财务清单" @retry="retry('finance')" />
          <ul v-if="finance?.records.length" class="affair-list"><li v-for="item in finance.records" :key="item.id" class="confirm-item"><div class="row-main"><strong>{{ item.ruleName }} · {{ item.applicantName }}</strong><small>{{ item.occurredAt }} · {{ yuan(item.amountCents) }} 元 · 结算单 {{ item.settlementId ?? '未开' }}{{ item.settlementStatus ? ' / ' + (statusText[item.settlementStatus] ?? item.settlementStatus) : '' }}</small></div><span class="status-pill">{{ statusText[item.status] ?? item.status }}</span><button v-if="isAdmin && item.status === 'VERIFIED'" type="button" class="quiet-button" :disabled="!!busy" @click="approveApplication(item)">主管批准</button></li></ul>
          <p v-else-if="finance" class="empty">财务清单为空。</p>
          <div v-if="finance && finance.pages > 1" class="pager"><button type="button" class="quiet-button" :disabled="!!busy || financePage <= 1" @click="run('finance', () => loadFinance(financePage - 1))">上一页</button><span>{{ financePage }} / {{ finance.pages }}</span><button type="button" class="quiet-button" :disabled="!!busy || financePage >= finance.pages" @click="run('finance', () => loadFinance(financePage + 1))">下一页</button></div>
        </article>

        <article class="affair-block">
          <h3>开结算单</h3>
          <p class="block-hint">只有待结算申请可开单；收款人必须显式选择，金额取受理时的事实。</p>
          <form class="affair-form" @submit.prevent="createSettlement"><label>待结算申请<select v-model="settleForm.applicationId" required><option value="" disabled>选择申请</option><option v-for="item in finance?.records.filter(row => row.status === 'PENDING_SETTLEMENT' && !row.settlementId) ?? []" :key="item.id" :value="item.id">{{ item.ruleName }} · {{ item.applicantName }} · {{ yuan(item.amountCents) }} 元</option></select></label><label>收款人<select v-model="settleForm.payeeMemberId" required><option value="" disabled>选择成员</option><option v-for="member in members" :key="member.memberId" :value="member.memberId">{{ member.username }}</option></select></label><label>核算说明（可选）<input v-model="settleForm.note" maxlength="500" /></label><button class="solid" type="submit" :disabled="!!busy">开出结算单</button></form>
          <RetryNotice v-if="blockErrors.members" :message="blockErrors.members" :busy="!!busy" label="成员名单" @retry="retry('members')" />
        </article>

        <article class="affair-block">
          <h3>结算单<span v-if="settlements" class="count-badge">{{ settlements.total }}</span></h3>
          <RetryNotice v-if="blockErrors.settlements" :message="blockErrors.settlements" :busy="!!busy" label="结算单" @retry="retry('settlements')" />
          <ul v-if="settlements?.records.length" class="affair-list"><li v-for="item in settlements.records" :key="item.id" class="confirm-item"><div class="row-main"><strong>{{ item.ruleName }} · {{ item.applicantName }}</strong><small>收款人 {{ item.payeeName }} · {{ yuan(item.amountCents) }} 元</small></div><span class="status-pill">{{ statusText[item.status] ?? item.status }}</span><button v-if="item.status === 'PENDING'" type="button" class="quiet-button" :disabled="!!busy" @click="approveSettlement(item)">批准</button><button v-if="item.status === 'APPROVED'" type="button" class="quiet-button" :disabled="!!busy" @click="settle(item)">标记结算</button></li></ul>
          <p v-else-if="settlements" class="empty">还没有结算单。</p>
          <div v-if="settlements && settlements.pages > 1" class="pager"><button type="button" class="quiet-button" :disabled="!!busy || settlementPage <= 1" @click="run('settle-page', () => loadSettlements(settlementPage - 1))">上一页</button><span>{{ settlementPage }} / {{ settlements.pages }}</span><button type="button" class="quiet-button" :disabled="!!busy || settlementPage >= settlements.pages" @click="run('settle-page', () => loadSettlements(settlementPage + 1))">下一页</button></div>
        </article>

        <article class="affair-block">
          <h3>退款登记</h3>
          <p class="block-hint">退款只追加调整记录；未结算整单取消并释放预占，已结算按退款金额回退已用额度。</p>
          <form class="affair-form" @submit.prevent="refundApplication"><label>申请<select v-model="refundForm.applicationId" required><option value="" disabled>选择申请</option><option v-for="item in finance?.records.filter(row => row.status !== 'CANCELLED' && row.status !== 'REJECTED') ?? []" :key="item.id" :value="item.id">{{ item.ruleName }} · {{ item.applicantName }} · {{ yuan(item.amountCents) }} 元</option></select></label><label>退款类型<select v-model="refundForm.kind"><option value="FULL">全额退款</option><option value="PARTIAL">部分退款</option></select></label><label>退款金额（元）<input v-model="refundForm.amountYuan" inputmode="decimal" required maxlength="12" /></label><label>退款原因<input v-model="refundForm.reason" required maxlength="200" /></label><button class="solid" type="submit" :disabled="!!busy">登记退款</button></form>
        </article>
      </section>
    </template>
  </section>
</template>

<style scoped>
.affairs-panel { display: flex; flex-direction: column; gap: 16px; color: var(--brand-800); font-size: var(--fs-sm); }
.account-line { display: flex; align-items: center; justify-content: space-between; gap: 14px; flex-wrap: wrap; padding: 16px 20px; border: 1px solid var(--brand-100); border-radius: 14px; background: linear-gradient(120deg, var(--n-25), var(--brand-50)); }
.workspace-connection { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.account-identity { display: flex; align-items: center; gap: 12px; }
.account-identity strong { display: block; font-size: var(--fs-base); font-weight: 600; color: var(--brand-800); }
.account-identity small { display: block; margin-top: 3px; font-size: var(--fs-xs); color: var(--n-500); }
.account-avatar { display: grid; place-items: center; width: 42px; height: 42px; border-radius: 12px; background: var(--n-0); border: 1px solid var(--brand-100); color: var(--brand-600); box-shadow: 0 3px 10px rgba(36, 104, 83, .06); }
.connected-dot { display: inline-flex; align-items: center; gap: 5px; font-size: var(--fs-2xs); color: var(--brand-600); background: var(--brand-100); border-radius: 20px; padding: 3px 9px; }
.connected-dot::before { content: ''; width: 5px; height: 5px; border-radius: 50%; background: var(--brand-500); }
.account-form { display: grid; gap: 12px; max-width: 460px; padding: 24px; border: 1px solid var(--brand-100); border-radius: 18px; background: var(--n-0); box-shadow: 0 6px 24px rgba(36, 104, 83, .045); }
.account-form fieldset { display: grid; gap: 12px; border: 0; padding: 0; margin: 0; }
.account-form legend { font-size: var(--fs-lg); font-weight: 600; color: var(--brand-800); }
.account-form label { display: grid; gap: 7px; font-size: var(--fs-xs); color: var(--brand-700); }
.account-form input { padding: 11px 12px; border: 1px solid var(--brand-100); border-radius: 10px; font: inherit; font-size: var(--fs-sm); }
.account-form small, .login-caption { color: var(--n-250); font-size: var(--fs-xs); }
.affairs-tabs { display: inline-flex; gap: 4px; padding: 4px; border-radius: 12px; background: var(--brand-50); }
.affairs-tabs button { padding: 8px 16px; border: 0; border-radius: 9px; background: transparent; color: var(--n-500); font-size: var(--fs-sm); font-weight: 500; transition: background .16s, color .16s; }
.affairs-tabs button:hover { color: var(--brand-700); }
.affairs-tabs button.active { background: var(--n-0); color: var(--brand-700); font-weight: 600; box-shadow: 0 2px 8px rgba(36, 104, 83, .1); }
.count-badge { display: inline-flex; align-items: center; margin-left: 8px; font-size: var(--fs-2xs); font-weight: 500; color: var(--brand-600); background: var(--brand-50); border-radius: 20px; padding: 2px 8px; }
.enterprise-selector { display: flex; align-items: center; gap: 9px; font-size: var(--fs-xs); color: var(--brand-700); }
.enterprise-selector select { padding: 9px 11px; border: 1px solid var(--brand-100); border-radius: 10px; font: inherit; font-size: var(--fs-xs); background: var(--n-0); }
.enterprise-name { display: inline-flex; align-items: center; gap: 7px; font-size: var(--fs-xs); color: var(--brand-600); }
.quiet-button { display: inline-flex; align-items: center; justify-content: center; gap: 7px; padding: 10px 15px; border: 1px solid var(--brand-100); border-radius: 10px; background: var(--n-0); color: var(--brand-600); font-size: var(--fs-xs); font-weight: 500; transition: border-color .16s, background .16s; }
.quiet-button:hover:not(:disabled) { background: var(--n-25); border-color: var(--brand-300); }
.quiet-button:disabled { opacity: .45; cursor: not-allowed; }
.solid { display: inline-flex; align-items: center; justify-content: center; gap: 8px; padding: 11px 20px; border: 0; border-radius: 10px; background: linear-gradient(135deg, var(--brand-600), var(--brand-700)); color: var(--n-0); font-size: var(--fs-sm); font-weight: 550; box-shadow: 0 4px 14px rgba(31, 101, 80, .22); transition: transform .16s, box-shadow .16s; }
.solid:hover:not(:disabled) { transform: translateY(-1px); box-shadow: 0 6px 18px rgba(31, 101, 80, .26); }
.solid:disabled { opacity: .45; box-shadow: none; cursor: not-allowed; }
.panel-feedback { display: flex; align-items: flex-start; gap: 8px; margin: 0; padding: 11px 14px; border-radius: 10px; background: var(--brand-50); border: 1px solid var(--brand-100); font-size: var(--fs-xs); color: var(--brand-600); overflow-wrap: anywhere; }
.panel-feedback.failed { background: var(--amber-soft); border-color: var(--warning-border); color: var(--danger); }
.affairs-view { display: grid; gap: 16px; }
.affair-block { background: var(--n-0); border: 1px solid var(--brand-100); border-radius: 18px; padding: 24px; box-shadow: 0 6px 24px rgba(36, 104, 83, .045); }
.affair-block h3 { display: flex; align-items: center; gap: 8px; margin: 0 0 6px; font-size: var(--fs-lg); font-weight: 600; color: var(--brand-800); letter-spacing: -.2px; }
.affair-block h4 { margin: 0 0 12px; font-size: var(--fs-sm); font-weight: 600; color: var(--brand-700); }
.block-hint { margin: 0; font-size: var(--fs-xs); line-height: 1.85; color: var(--n-500); }
.block-hint em { font-style: normal; color: var(--amber-500); background: var(--amber-soft); padding: 1px 5px; border-radius: 4px; font-size: var(--fs-2xs); }
.block-head { display: flex; align-items: flex-start; gap: 14px; margin-bottom: 22px; }
.block-icon { display: grid; place-items: center; flex-shrink: 0; width: 44px; height: 44px; border-radius: 13px; background: linear-gradient(140deg, var(--brand-50), var(--brand-100)); color: var(--brand-600); border: 1px solid var(--brand-100); }
.affair-form { display: grid; gap: 12px; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); align-items: end; }
.affair-form > label { display: grid; gap: 7px; font-size: var(--fs-xs); color: var(--brand-700); }
.affair-form input, .affair-form select { padding: 11px 12px; border: 1px solid var(--brand-100); border-radius: 10px; font: inherit; font-size: var(--fs-sm); background: var(--n-0); color: var(--brand-800); }
.affair-compose .affair-form { grid-template-columns: 1fr; gap: 20px; }
.form-step { border: 0; margin: 0; padding: 0; min-width: 0; grid-column: 1 / -1; display: grid; gap: 14px; grid-template-columns: repeat(auto-fit, minmax(215px, 1fr)); }
.form-step legend { grid-column: 1 / -1; display: flex; align-items: center; gap: 9px; padding: 0; font-size: var(--fs-sm); font-weight: 600; color: var(--brand-800); }
.step-no { display: grid; place-items: center; width: 22px; height: 22px; border-radius: 7px; background: var(--brand-50); color: var(--brand-600); font-size: var(--fs-xs); font-weight: 600; }
.step-note { grid-column: 1 / -1; margin: 0; font-size: var(--fs-xs); line-height: 1.8; color: var(--n-500); }
.form-wide { grid-column: 1 / -1; }
.field-warn { grid-column: 1 / -1; display: flex; align-items: flex-start; gap: 7px; margin: 0; padding: 11px 13px; border-radius: 10px; background: var(--amber-soft); border: 1px solid var(--warning-border); color: var(--amber-600); font-size: var(--fs-xs); line-height: 1.8; }
.field { position: relative; display: flex; flex-direction: column; gap: 7px; min-width: 0; font-size: var(--fs-xs); font-weight: 500; color: var(--brand-700); }
.field-name { display: inline-flex; align-items: center; gap: 6px; }
.field em { font-style: normal; font-size: var(--fs-2xs); font-weight: 400; color: var(--amber-500); background: var(--amber-soft); border-radius: 4px; padding: 1px 5px; }
.field small { order: 3; font-size: var(--fs-2xs); font-weight: 400; color: var(--n-250); }
.field select, .field input { order: 2; width: 100%; padding: 11px 12px; border: 1px solid var(--brand-100); border-radius: 10px; background: var(--n-0); color: var(--brand-800); font: inherit; font-size: var(--fs-sm); transition: border-color .16s, box-shadow .16s; }
.field .amount-input { order: 2; }
.field select:focus, .field input:focus { outline: none; border-color: var(--brand-400); box-shadow: 0 0 0 3px rgba(80, 160, 125, .13); }
.field-amount { max-width: 340px; }
.amount-input { display: flex; align-items: center; border: 1px solid var(--brand-100); border-radius: 10px; background: var(--n-0); overflow: hidden; transition: border-color .16s, box-shadow .16s; }
.amount-input .unit { padding: 0 2px 0 13px; color: var(--brand-400); font-size: var(--fs-base); }
.amount-input input { border: 0; border-radius: 0; padding-left: 6px; font-size: var(--fs-md); font-variant-numeric: tabular-nums; }
.amount-input input:focus { box-shadow: none; }
.amount-input:focus-within { border-color: var(--brand-400); box-shadow: 0 0 0 3px rgba(80, 160, 125, .13); }
.share-row { grid-column: 1 / -1; display: grid; grid-template-columns: minmax(160px, 1fr) minmax(140px, .8fr) auto; gap: 12px; align-items: end; padding: 14px; border: 1px solid var(--brand-50); border-radius: 12px; background: var(--n-25); }
.share-row .icon-only { padding: 11px; }
.icon-only { display: inline-grid; place-items: center; }
.add-share { grid-column: 1 / -1; justify-self: start; }
.share-summary { grid-column: 1 / -1; display: flex; align-items: center; gap: 8px; padding: 11px 13px; border-radius: 10px; background: var(--n-25); border: 1px solid var(--brand-100); color: var(--n-500); font-size: var(--fs-xs); }
.share-summary.matched { background: var(--brand-50); border-color: var(--brand-100); color: var(--brand-600); }
.share-summary.mismatch { background: var(--amber-soft); border-color: var(--warning-border); color: var(--amber-600); }
.share-editor { grid-column: 1 / -1; display: grid; gap: 9px; font-size: var(--fs-xs); color: var(--brand-700); }
.eligibility { grid-column: 1 / -1; display: flex; align-items: center; gap: 8px; margin: 0; padding: 12px 14px; border-radius: 10px; background: var(--brand-50); border: 1px solid var(--brand-100); color: var(--brand-600); font-size: var(--fs-xs); }
.form-actions { grid-column: 1 / -1; display: flex; align-items: center; gap: 10px; flex-wrap: wrap; padding-top: 4px; }
.inline-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.affair-list { list-style: none; margin: 0; padding: 0; display: grid; gap: 9px; }
.affair-row { display: flex; justify-content: space-between; gap: 14px; width: 100%; text-align: left; padding: 14px 16px; border: 1px solid var(--brand-100); border-radius: 12px; background: var(--n-25); transition: border-color .16s, box-shadow .16s, transform .16s; }
.affair-row:hover { border-color: var(--brand-200); box-shadow: 0 4px 14px rgba(36, 104, 83, .06); transform: translateY(-1px); }
.row-main { display: grid; gap: 4px; color: var(--brand-800); }
.row-main strong { font-size: var(--fs-sm); font-weight: 600; }
.row-main small { font-size: var(--fs-xs); color: var(--n-250); }
.row-side { display: grid; justify-items: end; gap: 6px; }
.row-side strong { font-size: var(--fs-base); font-weight: 650; color: var(--brand-700); font-variant-numeric: tabular-nums; }
.status-pill { display: inline-flex; align-items: center; font-size: var(--fs-2xs); color: var(--brand-600); background: var(--brand-50); border-radius: 20px; padding: 3px 9px; }
.confirm-item { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; padding: 14px 16px; border: 1px solid var(--brand-100); border-radius: 12px; background: var(--n-25); }
.confirm-item > input { flex: 1 1 180px; padding: 11px 12px; border: 1px solid var(--brand-100); border-radius: 10px; font: inherit; font-size: var(--fs-sm); }
.pager { display: flex; align-items: center; gap: 12px; margin-top: 14px; font-size: var(--fs-xs); color: var(--n-250); }
.empty { margin: 0; padding: 26px; text-align: center; font-size: var(--fs-xs); color: var(--n-250); border: 1px dashed var(--brand-100); border-radius: 12px; background: var(--n-25); }
.affair-facts { display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 14px 18px; margin: 0 0 16px; padding: 16px; border-radius: 12px; background: var(--n-25); border: 1px solid var(--brand-50); }
.affair-facts div { display: grid; gap: 4px; }
.affair-facts dt { color: var(--n-250); font-size: var(--fs-xs); }
.affair-facts dd { margin: 0; color: var(--brand-800); font-size: var(--fs-sm); font-weight: 500; }
.participant-list, .receipt-list { list-style: none; margin: 0 0 14px; padding: 0; display: grid; gap: 7px; }
.participant-list li, .receipt-list li { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 11px 14px; border: 1px solid var(--brand-50); border-radius: 10px; font-size: var(--fs-xs); }
.file-label { position: relative; overflow: hidden; display: inline-flex; align-items: center; gap: 7px; padding: 10px 15px; border: 1px dashed var(--brand-200); border-radius: 10px; background: var(--n-25); color: var(--brand-600); font-size: var(--fs-xs); cursor: pointer; }
.file-label input { position: absolute; inset: 0; opacity: 0; cursor: pointer; }
.revise-box { margin-top: 18px; padding: 18px; border: 1px dashed var(--brand-100); border-radius: 12px; background: var(--n-25); display: grid; gap: 12px; }
.revise-box label { display: grid; gap: 7px; font-size: var(--fs-xs); color: var(--brand-700); }
.revise-box input, .revise-box select { padding: 11px 12px; border: 1px solid var(--brand-100); border-radius: 10px; font: inherit; font-size: var(--fs-sm); background: var(--n-0); }
.inline-check { display: flex !important; align-items: center; gap: 8px; }
@media (max-width: 620px) {
  .affair-block { padding: 18px 16px; }
  .account-line { padding: 13px 14px; }
  .share-row { grid-template-columns: 1fr; }
  .affairs-tabs { width: 100%; }
  .affairs-tabs button { flex: 1; padding: 8px 10px; font-size: var(--fs-xs); }
}

</style>
