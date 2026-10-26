<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import { createEnterpriseClient, EnterpriseError, positiveId } from '../enterprise';
import type { Account, KnowledgeSpace } from '../enterprise';
import type { Evidence, KnowledgeAnswer } from '../knowledge-answer';
import type { WorkspaceChat, WorkspaceJob } from '../knowledge-workspace';
import { useSession } from '../session';
import { publicChatEnabled } from '../public-chat';
import KnowledgeFeedbackPanel from './KnowledgeFeedbackPanel.vue';
import AppIcon from './AppIcon.vue';

const emit = defineEmits<{ manage: []; general: []; login: [] }>();
const api = createEnterpriseClient();
// 登录状态与工作台、员工事务、知识空间共用同一份。
const { user, ensure, markAnonymous } = useSession();
const checking = ref(true);
const enterpriseId = ref('');
const spaceId = ref('');
const currentMode = ref('');
const retrievalMode = ref('');
const retrievalLabels: Record<string, string> = { KEYWORD: '关键词检索', HYBRID: '关键词与向量混合检索' };
const spaces = ref<KnowledgeSpace[]>([]);
const question = ref('');
const notice = ref('正在核对登录状态…');
const listing = ref(false);
const now = ref(Date.now());
type Card = { id: string; question: string; started: number; pending: boolean; answer?: KnowledgeAnswer; error?: string;
  jobId?: string; requestChatId?: string; retryable?: boolean; status?: string; message?: string;
  feedback?: string; feedbackId?: string; feedbackSnapshot?: string; feedbackSaved?: boolean };
const cards = ref<Card[]>([]);
const persistent = ref(false);
const chats = ref<WorkspaceChat[]>([]);
const chatId = ref<string>(crypto.randomUUID());
const loadingHistory = ref(false);
const checkingWorkspace = ref(false);
let workspaceGeneration = 0;
const source = ref<Evidence>();
const sourceNotice = ref('');
/** 当前展开核对的片段；空串表示全部收起。同一条再点一次即收起。 */
const openChunkId = ref('');
let mounted = true;
let sourceGeneration = 0;
let scopeGeneration = 0;
const active = computed(() => cards.value.filter(card => card.pending).length);
const currentEnterprise = computed(() => user.value?.enterprises?.find(item => item.enterpriseId === enterpriseId.value));
const currentSpace = computed(() => spaces.value.find(item => item.id === spaceId.value));
const prompts = [
  { icon: 'book', title: '读懂一项制度', text: '请根据资料，梳理这项制度的适用范围和注意事项。', hint: '适用范围 · 关键条件' },
  { icon: 'layers', title: '理清办理流程', text: '请说明资料中有哪些办理步骤，需要准备什么材料？', hint: '办理步骤 · 所需材料' },
  { icon: 'search', title: '找到原文依据', text: '请帮我找到与当前问题相关的原文，并注明出处。', hint: '原文片段 · 版本出处' }
];
const ready = computed(() => user.value && positiveId(enterpriseId.value) && positiveId(spaceId.value) && !!currentMode.value && !checkingWorkspace.value);
const labels: Record<string, string> = { ANSWERED: '已有依据的回答', PARTIAL: '部分可回答', NO_EVIDENCE: '资料不足',
  CONFLICT: '资料有冲突', EVIDENCE_ONLY: '仅原文摘录', MODEL_UNAVAILABLE: '模型未完成',
  OUT_OF_SCOPE: '超出学校业务范围', SCOPE_UNKNOWN: '业务范围待确认', SCOPE_UNAVAILABLE: '范围识别不可用' };
const timer = setInterval(() => { now.value = Date.now(); }, 1000);
watch([enterpriseId, spaceId], async () => {
  const workspace = ++workspaceGeneration;
  persistent.value = false; chats.value = []; chatId.value = crypto.randomUUID(); checkingWorkspace.value = false;
  loadingHistory.value = false;
  const generation = ++scopeGeneration;
  sourceGeneration++;
  cards.value = []; source.value = undefined; sourceNotice.value = ''; openChunkId.value = '';
  currentMode.value = ''; retrievalMode.value = '';
  if (!user.value || !positiveId(enterpriseId.value) || !positiveId(spaceId.value)) return;
  checkingWorkspace.value = true;
  notice.value = '正在核验空间的数据外发边界…';
  try {
    const mode = await api.answerMode(enterpriseId.value, spaceId.value);
    if (mounted && generation === scopeGeneration) {
      currentMode.value = mode;
      notice.value = mode === 'SYNTHETIC_MODEL' ? '此空间已获准使用外部模型，仅允许虚构测试资料和合成问题，请勿输入真实企业信息。'
        : '此空间只做本地检索，不向模型发送问题或原文。';
      try {
        const strategy = await api.retrievalMode(enterpriseId.value, spaceId.value);
        if (mounted && generation === scopeGeneration) retrievalMode.value = strategy;
      } catch (error) {
        if (mounted && generation === scopeGeneration) {
          notice.value += ' 当前检索策略无法确认，不代表索引已就绪。';
          if (error instanceof EnterpriseError && error.code === 401) { failure(error); return; }
        }
      }
      try {
        const history = await api.chats(enterpriseId.value, spaceId.value);
        if (mounted && workspace === workspaceGeneration) {
          chats.value = history; persistent.value = true;
          if (history[0]) { chatId.value = history[0].id; await loadHistory(); }
        }
      } catch (error) {
        if (mounted && workspace === workspaceGeneration) {
          if (error instanceof EnterpriseError && error.code === 401) failure(error);
          else notice.value += ' 会话历史暂不可用；当前仍可单次提问，刷新前请保存结果。';
        }
      }
    }
  } catch (error) { if (mounted && generation === scopeGeneration) notice.value = failure(error); }
  finally { if (mounted && generation === scopeGeneration) checkingWorkspace.value = false; }
});
watch(enterpriseId, () => { spaces.value = []; spaceId.value = ''; });

function failure(error: unknown): string {
  if (error instanceof EnterpriseError && error.code === 401) { markAnonymous(); cards.value = []; source.value = undefined; }
  return error instanceof EnterpriseError ? error.message : '请求未完成，请检查服务后手动重试。';
}
async function list() {
  if (!positiveId(enterpriseId.value) || listing.value || active.value) return;
  listing.value = true;
  notice.value = '正在读取已授权的知识空间…';
  source.value = undefined;
  try {
    const result = await api.listSpaces(enterpriseId.value, 1, 500);
    if (mounted) {
      spaces.value = result.records;
      notice.value = result.records.length ? '已连接团队知识，仅使用获授权的有效资料。' : '没有已授权空间，请联系知识负责人。';
      spaceId.value = result.records[0]?.id ?? '';
    }
  } catch (error) { if (mounted) { spaces.value = []; spaceId.value = ''; notice.value = failure(error); } }
  finally { if (mounted) listing.value = false; }
}
async function connect(current: Account) {
  enterpriseId.value = current.enterprises?.length === 1 ? current.enterprises[0]!.enterpriseId : '';
  if (!current.enterprises) { notice.value = '登录信息尚不包含所属企业，请重启最新版后端后刷新。'; return; }
  if (!current.enterprises.length) { notice.value = '账号尚未分配企业，请联系管理员。'; return; }
  if (!enterpriseId.value) { notice.value = '请选择要进入的企业工作区。'; return; }
  await list();
}
function questionKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing) return;
  event.preventDefault();
  void ask();
}
async function ask() {
  if (!ready.value || active.value >= 2 || !question.value.trim() || question.value.length > 1200 || listing.value) return;
  const enterprise = enterpriseId.value;
  const space = spaceId.value;
  const card = ref<Card>({ id: crypto.randomUUID(), question: question.value, started: Date.now(), pending: true });
  cards.value.push(card.value);
  const limit = persistent.value ? 50 : 10;
  if (cards.value.length > limit) cards.value.splice(cards.value.findIndex(item => !item.pending), 1);
  question.value = '';
  source.value = undefined;
  const generation = workspaceGeneration;
  if (persistent.value) {
    card.value.jobId = card.value.id;
    card.value.requestChatId = chatId.value;
    try {
      const job = await api.submitJob(enterprise, space, card.value.requestChatId, card.value.id, card.value.question);
      if (mounted && generation === workspaceGeneration) {
        if (!chats.value.some(chat => chat.id === chatId.value)) chats.value.unshift({ id: chatId.value, title: card.value.question.slice(0, 80), createdMs: job.createdMs });
        applyJob(card.value, job); void poll(card.value, enterprise, space, generation);
      }
    } catch (error) {
      if (mounted && generation === workspaceGeneration) {
        card.value.error = failure(error); card.value.pending = false;
        card.value.retryable = error instanceof EnterpriseError && error.uncertain;
      }
    }
    return;
  }
  try { const answer = await api.answer(enterprise, space, card.value.question); if (mounted && generation === workspaceGeneration) card.value.answer = answer; }
  catch (error) { if (mounted) card.value.error = failure(error); }
  finally { if (mounted) card.value.pending = false; }
}
function applyJob(card: Card, job: WorkspaceJob) {
  card.retryable = false;
  card.jobId = job.id; card.status = job.status; card.message = job.message;
  card.pending = ['PENDING', 'RUNNING'].includes(job.status); card.answer = job.answer ?? undefined;
  card.started = job.createdMs; card.error = undefined;
}
async function poll(card: Card, enterprise: string, space: string, generation: number) {
  const stopAt = Date.now() + 35000;
  while (mounted && generation === workspaceGeneration && card.pending && Date.now() < stopAt) {
    await new Promise(resolve => setTimeout(resolve, 1000));
    if (!mounted || generation !== workspaceGeneration || !card.pending) return;
    try {
      const job = await api.readJob(enterprise, space, card.jobId!);
      if (!mounted || generation !== workspaceGeneration) return;
      applyJob(card, job);
    } catch (error) {
      if (mounted && generation === workspaceGeneration) { card.pending = false; card.error = failure(error) + ' 请查询原任务，不要新建重复请求。'; }
      return;
    }
  }
  if (mounted && generation === workspaceGeneration && card.pending) {
    card.pending = false; card.error = '进度连接已停止，请查询原任务；后台不会因刷新延长时限。';
  }
}
async function loadHistory() {
  if (!persistent.value || loadingHistory.value) return;
  loadingHistory.value = true;
  sourceGeneration++;
  source.value = undefined; sourceNotice.value = '';
  const generation = ++workspaceGeneration;
  try {
    const list = await api.chatHistory(enterpriseId.value, spaceId.value, chatId.value);
    if (!mounted || generation !== workspaceGeneration) return;
    cards.value = [...list].reverse().map(job => {
      const card: Card = { id: job.id, question: job.question, started: job.createdMs, pending: false };
      applyJob(card, job); return card;
    });
    for (const card of cards.value) if (card.pending) void poll(card, enterpriseId.value, spaceId.value, generation);
  } catch (error) { if (mounted && generation === workspaceGeneration) notice.value = failure(error); }
  finally { if (mounted && generation === workspaceGeneration) loadingHistory.value = false; }
}
/** 当前选中的是已存在的会话（而不是刚生成的新会话编号）。 */
const currentChat = computed(() => chats.value.find(item => item.id === chatId.value));
/** 最近一条带引用的回答：让右侧依据栏在会话进行中就有内容，而不是只留一张插画。 */
const recentEvidence = computed(() => {
  for (let index = cards.value.length - 1; index >= 0; index--) {
    const evidence = cards.value[index]?.answer?.evidence;
    if (evidence?.length) return evidence;
  }
  return [];
});
const streamRef = ref<HTMLElement>();
/**
 * 把最新一轮对话对齐到答案流顶部：提问时先看到自己的问题，
 * 回答生成时停在同一个位置，内容向下生长。
 * 若直接滚到最底部，长回答会把问题顶出视口，读者反而失去上下文。
 */
function scrollToLatest() {
  const node = streamRef.value;
  if (!node) return;
  const cards = node.querySelectorAll<HTMLElement>('.answer-card');
  const last = cards[cards.length - 1];
  if (!last) { node.scrollTo({ top: 0, behavior: 'smooth' }); return; }
  // 用 offsetTop 而不是 getBoundingClientRect：入场动画带 translateY，量到的位置会偏。
  node.scrollTo({ top: Math.max(0, last.offsetTop - 10), behavior: 'smooth' });
}
watch(() => cards.value.map(card => `${card.pending}:${card.answer ? 1 : 0}`).join('|'),
  () => { void nextTick(scrollToLatest); });

const confirmDelete = ref(false);
function newChat() {
  workspaceGeneration++; sourceGeneration++; chatId.value = crypto.randomUUID(); cards.value = [];
  source.value = undefined; sourceNotice.value = ''; openChunkId.value = ''; confirmDelete.value = false;
}
/** 删除当前会话：二次确认后软删除，列表同步移除并切回空白会话。 */
async function removeChat() {
  if (!currentChat.value || active.value || loadingHistory.value) return;
  const target = chatId.value;
  loadingHistory.value = true;
  try {
    await api.deleteChat(enterpriseId.value, spaceId.value, target);
    if (!mounted) return;
    chats.value = chats.value.filter(item => item.id !== target);
    if (chatId.value === target) newChat();
    notice.value = '会话已从列表移除。问答任务与反馈仍保留在企业审计记录里。';
  } catch (error) { if (mounted) notice.value = failure(error); }
  finally { if (mounted) { loadingHistory.value = false; confirmDelete.value = false; } }
}
async function recover(card: Card) {
  const generation = workspaceGeneration;
  try {
    const job = await api.readJob(enterpriseId.value, spaceId.value, card.jobId!);
    if (mounted && generation === workspaceGeneration) { applyJob(card, job); if (card.pending) void poll(card, enterpriseId.value, spaceId.value, generation); }
  } catch (error) { if (mounted && generation === workspaceGeneration) card.error = failure(error); }
}
async function retryOriginal(card: Card) {
  if (!ready.value || !card.retryable || card.pending || active.value >= 2 || !card.requestChatId) return;
  const generation = workspaceGeneration, enterprise = enterpriseId.value, space = spaceId.value;
  card.pending = true; card.error = undefined;
  try {
    let job: WorkspaceJob;
    try { job = await api.readJob(enterprise, space, card.jobId!); }
    catch (error) {
      // 只有明确查无原任务才人工重发；断网/鉴权/服务异常不能证明原提交未成功。
      if (!(error instanceof EnterpriseError) || error.code !== 404) throw error;
      if (!mounted || generation !== workspaceGeneration) return;
      job = await api.submitJob(enterprise, space, card.requestChatId, card.jobId!, card.question);
    }
    if (mounted && generation === workspaceGeneration) {
      if (!chats.value.some(chat => chat.id === job.chatId)) chats.value.unshift({ id: job.chatId, title: card.question.slice(0, 80), createdMs: job.createdMs });
      applyJob(card, job); if (card.pending) void poll(card, enterprise, space, generation);
    }
  } catch (error) {
    if (mounted && generation === workspaceGeneration) { card.pending = false; card.error = failure(error); }
  }
}
async function cancel(card: Card) {
  const generation = workspaceGeneration;
  try {
    const job = await api.cancelJob(enterpriseId.value, spaceId.value, card.jobId!);
    if (mounted && generation === workspaceGeneration) applyJob(card, job);
  } catch (error) { if (mounted && generation === workspaceGeneration) card.error = failure(error); }
}
async function sendFeedback(card: Card) {
  if (!card.feedback?.trim() || !card.jobId || card.feedbackSaved) return;
  card.feedbackId ??= crypto.randomUUID();
  card.feedbackSnapshot ??= card.feedback;
  const generation = workspaceGeneration;
  try {
    await api.sendFeedback(enterpriseId.value, spaceId.value,
      { id: card.feedbackId, jobId: card.jobId, chunkId: null, category: 'OTHER', comment: card.feedbackSnapshot });
    if (mounted && generation === workspaceGeneration) { card.feedbackSaved = true; card.message = '反馈已保存，知识负责人可以查看并处理。'; }
  } catch (error) { if (mounted && generation === workspaceGeneration) card.error = failure(error); }
}
async function inspect(evidence: Evidence) {
  // 点已展开的那一条＝收起：不必再到面板顶部找关闭按钮。
  if (openChunkId.value === evidence.chunkId) { collapse(); return; }
  const generation = ++sourceGeneration;
  openChunkId.value = evidence.chunkId;
  source.value = undefined;
  sourceNotice.value = '正在重新核验引用权限与发布状态…';
  try {
    const current = await api.evidence(enterpriseId.value, spaceId.value, evidence.chunkId);
    if (mounted && generation === sourceGeneration) { source.value = current; sourceNotice.value = '已重新核验当前可读的原文片段。'; }
  } catch (error) { if (mounted && generation === sourceGeneration) sourceNotice.value = failure(error); }
}
/** 收起当前原文核对，回到一览引用的状态。 */
function collapse() {
  sourceGeneration++;
  openChunkId.value = '';
  source.value = undefined;
  sourceNotice.value = '';
}
/**
 * 答不上来时给出明确去向，而不是让用户对着“仅原文摘录”猜下一步。
 * 只有服务端真的判定超范围才说“不属于学校业务”；本地检索模式下没命中资料属于
 * “校内资料没有覆盖”，不能替用户下范围结论，两种措辞必须分开。
 */
function redirect(answer: KnowledgeAnswer): string {
  // 生产部署里公开联网问答是关闭的，措辞必须指向用户当下真能用的地方，
  // 否则会把人从“答不上来”再送到一个“此功能未开启”的说明页。
  const outward = publicChatEnabled ? '公开联网问答' : '公开搜索引擎（如必应、百度）';
  const statuses = answer.parts.map(part => part.status);
  if (statuses.includes('OUT_OF_SCOPE'))
    return `这个问题不属于当前学校的业务范围，校内资料里不会有答案。可自行使用${outward}查询；那里不携带当前问题、学校原文或会话历史，请勿输入内部信息。`;
  if (statuses.includes('SCOPE_UNKNOWN'))
    return `暂时判断不了这属于哪一项校内业务，所以没有给出制度结论。可以补上具体的校内对象再问一次；如果本来就是校外的公开信息，请到${outward}。`;
  if (statuses.includes('EVIDENCE_ONLY') || statuses.includes('NO_EVIDENCE'))
    return `现有校内资料里没有能直接回答这个问题的内容。如果是校外的公开信息（活动、新闻、通用常识），请到${outward}；如果确实是校内事务，可以换个说法再问，或反馈让知识负责人补充资料。`;
  return '';
}
/** 公开联网问答未开启时，直接把用户送到公开搜索引擎，而不是站内的“未开启”说明页。 */
function openPublicSearch() {
  window.open('https://www.bing.com', '_blank', 'noopener,noreferrer');
}
onMounted(async () => {
  try {
    const current = await ensure();
    if (!mounted) return;
    if (current) await connect(current);
    else notice.value = '请先前往知识空间，使用管理员开通的企业账号登录。';
  }
  catch (error) { if (mounted) notice.value = failure(error); }
  finally { if (mounted) checking.value = false; }
});
onUnmounted(() => { mounted = false; workspaceGeneration++; clearInterval(timer); api.dispose(); });
</script>

<template>
  <section class="knowledge-answer" aria-label="企业知识问答">
    <header class="answer-toolbar">
      <div class="answer-brand">
        <span class="assistant-avatar"><AppIcon name="sparkle" :size="22" /></span>
        <div>
          <h2>知识助手<span>知序</span></h2>
          <p>{{ currentEnterprise?.enterpriseName ?? '你的团队知识，随问随用' }}</p>
        </div>
      </div>
      <div class="answer-account">
        <span v-if="user" class="connection-badge"><i></i>企业身份已连接</span>
        <button type="button" @click="user ? emit('manage') : emit('login')">
          <AppIcon :name="user ? 'book' : 'shield'" :size="15" />{{ user ? '管理知识' : '登录企业账号' }}
        </button>
      </div>
    </header>
    <p class="answer-notice" role="status" aria-live="polite"><AppIcon name="shield" :size="14" /><span>{{ notice }}</span></p>

    <div v-if="!user && !checking" class="answer-login-state">
      <div class="login-hero">
        <div class="assistant-orb"><AppIcon name="sparkle" :size="38" /></div>
        <h2>连接团队，打开知识。</h2>
        <p>登录企业账号后，自动连接所属企业与可访问的资料。</p>
        <button class="solid" @click="emit('login')">登录并开始<AppIcon name="arrow" :size="16" /></button>
      </div>
      <div class="login-preview">
        <article><span class="preview-icon sage"><AppIcon name="book" :size="20" /></span><h3>按主题归档</h3><p>制度、流程、经验各自成册，版本与有效期随资料一起留存。</p></article>
        <article><span class="preview-icon blue"><AppIcon name="search" :size="20" /></span><h3>只回答有依据的部分</h3><p>回答附上原文出处；资料不足时明确说明，而不是编一个给你。</p></article>
        <article><span class="preview-icon amber"><AppIcon name="shield" :size="20" /></span><h3>权限由企业管理</h3><p>你能看到的空间都是管理员已授权的，不需要记任何编号。</p></article>
      </div>
    </div>

    <template v-if="user && !checking">
      <div class="answer-controls">
        <div class="answer-scope">
          <label v-if="(user.enterprises?.length ?? 0) > 1" for="answer-workspace">
            <AppIcon name="briefcase" :size="15" />
            <select id="answer-workspace" v-model="enterpriseId" :disabled="!!active || listing" @change="list">
              <option value="" disabled>选择企业工作区</option>
              <option v-for="item in user.enterprises" :key="item.enterpriseId" :value="item.enterpriseId">{{ item.enterpriseName }}</option>
            </select>
          </label>
          <label for="answer-space">
            <AppIcon name="book" :size="15" />
            <select id="answer-space" v-model="spaceId" :disabled="!!active || listing">
              <option value="">选择知识空间</option>
              <option v-for="space in spaces" :key="space.id" :value="space.id">{{ space.name }}</option>
            </select>
          </label>
          <button class="scope-refresh" type="button" :disabled="!!active || listing || !positiveId(enterpriseId)" @click="list" aria-label="刷新授权空间"><AppIcon name="refresh" :size="15" /></button>
        </div>
        <div v-if="persistent" class="session-tools">
          <label>私有会话
            <select v-model="chatId" :disabled="!!active || loadingHistory" @change="workspaceGeneration++; confirmDelete = false; loadHistory()">
              <option v-if="!chats.some(item => item.id === chatId)" :value="chatId">新会话</option>
              <option v-for="chat in chats" :key="chat.id" :value="chat.id">{{ chat.title }}</option>
            </select>
          </label>
          <button :disabled="!!active || loadingHistory" @click="newChat"><AppIcon name="plus" :size="14" />新建会话</button>
          <button :disabled="loadingHistory" @click="loadHistory"><AppIcon name="refresh" :size="14" />查询原会话任务</button>
          <button v-if="currentChat" class="session-delete" :disabled="!!active || loadingHistory" @click="confirmDelete = true"><AppIcon name="close" :size="14" />删除会话</button>
        </div>
        <span class="scope-privacy"><AppIcon name="shield" :size="13" />企业私有资料</span>
      </div>
      <div v-if="confirmDelete && currentChat" class="session-confirm" role="alertdialog" aria-label="确认删除会话">
        <span>将从列表移除「{{ currentChat.title }}」。问答任务与反馈仍保留在企业审计里，不能恢复显示。</span>
        <button class="danger" :disabled="loadingHistory" @click="removeChat">{{ loadingHistory ? '正在删除…' : '确认删除' }}</button>
        <button :disabled="loadingHistory" @click="confirmDelete = false">取消</button>
      </div>
      <div class="answer-workbench"><div class="answer-stage">
        <div ref="streamRef" class="answer-stream">
          <section v-if="!cards.length" class="answer-welcome">
            <div class="knowledge-constellation" aria-hidden="true">
              <span class="orbit-node node-book"><AppIcon name="book" :size="20" /></span>
              <span class="orbit-node node-file"><AppIcon name="file" :size="18" /></span>
              <span class="orbit-node node-check"><AppIcon name="check" :size="17" /></span>
              <div class="assistant-orb"><AppIcon name="sparkle" :size="32" /></div>
              <i></i><i></i>
            </div>
            <span class="welcome-kicker">YOUR TEAM, CONNECTED</span>
            <h2>把工作里的问号，<br />交给有依据的回答<span>。</span></h2>
            <p>{{ currentSpace ? `从「${currentSpace.name}」开始，找制度、理流程、查依据。` : '选择知识空间，让散落的资料成为清晰的下一步。' }}</p>
            <div class="question-starters">
              <button v-for="item in prompts" :key="item.title" :disabled="!ready" @click="question = item.text">
                <AppIcon :name="item.icon" :size="19" />
                <strong>{{ item.title }}</strong>
                <small>{{ item.hint }}</small>
                <AppIcon class="starter-arrow" name="chevron" :size="13" />
              </button>
            </div>
          </section>

          <div class="answer-timeline" aria-live="polite">
            <article v-for="card in cards" :key="card.id" class="answer-card">
              <div class="user-question">
                <h3>{{ card.question }}</h3>
                <span class="user-avatar">我</span>
              </div>

              <div class="answer-reply">
                <span class="assistant-mini"><AppIcon name="sparkle" :size="16" /></span>
                <div class="answer-reply-body">
                  <div class="answer-head">
                    <div class="assistant-label">
                      <strong>知序</strong>
                      <small>{{ card.pending ? '正在处理中' : '本次回答' }}</small>
                    </div>
                    <div v-if="card.answer" class="answer-state" :data-status="card.answer.status">
                      <strong>{{ labels[card.answer.status] }}</strong>
                      <small>{{ card.answer.mode === 'SYNTHETIC_MODEL' ? '虚构资料 · 模型验收' : '本地授权检索' }} · {{ card.answer.elapsedMs }} ms</small>
                    </div>
                  </div>

                  <div v-if="card.pending" class="answer-progress" role="status">
                    <span class="thinking-dots"><i></i><i></i><i></i></span>
                    <div>
                      <strong>{{ card.status === 'PENDING' ? '问题已受理，等待处理' : '正在检索和核对依据' }}</strong>
                      <p>{{ Math.max(0, Math.floor((now - card.started) / 1000)) }} 秒 · 最长处理 30 秒，你仍可补充新问题</p>
                    </div>
                  </div>

                  <p v-if="card.error" class="answer-error" role="alert"><AppIcon name="info" :size="16" />{{ card.error }}</p>

                  <template v-if="card.answer">
                    <p class="answer-message">{{ card.answer.message }}</p>

                    <section v-for="part in card.answer.parts" :key="part.id" class="answer-part" :data-status="part.status">
                      <h4>
                        <span class="part-number">{{ part.id.toString().padStart(2, '0') }}</span>
                        <span class="part-title">{{ part.question }}</span>
                        <small>{{ labels[part.status] }}</small>
                      </h4>
                      <div v-for="(paragraph, index) in part.paragraphs" :key="index" class="answer-paragraph">
                        <p class="answer-text">{{ paragraph.text }}</p>
                        <div class="citation-row">
                          <button v-for="citation in paragraph.citations" :key="citation.sourceId" type="button" class="citation-button" @click="inspect(citation.evidence)">
                            <span class="citation-index">{{ citation.sourceId }}</span>
                            <span class="citation-body"><strong>{{ citation.evidence.title }}</strong><small>v{{ citation.evidence.versionNo }} · 第{{ citation.evidence.startLine }}—{{ citation.evidence.endLine }}行</small></span>
                            <AppIcon name="external" :size="12" />
                          </button>
                        </div>
                      </div>
                      <ul v-if="part.gaps.length" class="evidence-gaps">
                        <li v-for="gap in part.gaps" :key="gap"><AppIcon name="info" :size="13" /><span>{{ gap }}</span></li>
                      </ul>
                    </section>

                    <div v-if="redirect(card.answer)" class="answer-scope-switch">
                      <p>{{ redirect(card.answer) }}</p>
                      <button v-if="publicChatEnabled" type="button" @click="emit('general')">前往公开联网问答</button>
                      <button v-else type="button" @click="openPublicSearch">用必应搜一下</button>
                    </div>

                    <details v-if="card.answer.evidence.length" class="answer-evidence">
                      <summary>查看 {{ card.answer.evidence.length }} 条命中原文（命中不等于答案充分）</summary>
                      <div v-for="item in card.answer.evidence" :key="item.chunkId">
                        <button type="button" @click="inspect(item)">{{ item.title }} · v{{ item.versionNo }} · 第{{ item.startLine }}—{{ item.endLine }}行 · 重新核验引用</button>
                        <pre>{{ item.content }}</pre>
                      </div>
                    </details>
                  </template>

                  <p v-if="card.message && (!card.answer || card.feedbackSaved)" class="answer-caption" role="status">{{ card.message }}</p>

                  <div class="answer-tools">
                    <details v-if="card.jobId" class="task-details">
                      <summary><AppIcon name="clock" :size="13" />任务与恢复</summary>
                      <small>任务编号 {{ card.jobId }}</small>
                      <div>
                        <button :disabled="card.pending && card.retryable" @click="recover(card)">查询原任务</button>
                        <button v-if="card.retryable" :disabled="card.pending || active >= 2" @click="retryOriginal(card)">核对并按原编号重发</button>
                        <button v-if="card.pending && !card.retryable" @click="cancel(card)">取消此任务</button>
                      </div>
                    </details>
                    <details v-if="card.jobId" class="feedback-inline">
                      <summary><AppIcon name="info" :size="13" />反馈答案或资料问题</summary>
                      <label>请说明哪里不准确、缺失或过期<textarea v-model="card.feedback" :disabled="!!card.feedbackId" maxlength="1000" rows="2" /></label>
                      <small v-if="card.feedbackId && !card.feedbackSaved">正在核对原反馈；再次点击保留原编号与内容，不创建重复记录。</small>
                      <button :disabled="!card.feedback?.trim() || card.feedbackSaved" @click="sendFeedback(card)">保存反馈</button>
                    </details>
                  </div>
                </div>
              </div>
            </article>
          </div>
        </div>

        <form class="enterprise-composer" aria-label="提交企业问题" @submit.prevent="ask">
          <label class="composer-label" for="knowledge-question">向团队知识提问</label>
          <textarea id="knowledge-question" v-model="question" maxlength="1200" rows="2" placeholder="工作里有什么不清楚？试着一次问完整。" :disabled="!ready || listing || loadingHistory" @keydown="questionKeydown"></textarea>
          <div class="answer-compose-footer">
            <span class="composer-space"><AppIcon name="book" :size="14" />{{ currentSpace?.name ?? '请先选择空间' }}</span>
            <small>{{ question.length }}/1200</small>
            <button class="solid" type="submit" :disabled="!ready || active >= 2 || !question.trim() || listing || loadingHistory" aria-label="发送问题"><AppIcon name="arrow" :size="18" /><span>发送问题</span></button>
          </div>
        </form>
        <p class="composer-help">
          <span>{{ active ? `正在处理 ${active} 条问题，最多两条同时处理` : 'Enter 发送 · Shift + Enter 换行' }}</span>
          <span>{{ persistent ? '历史已接通 · 可查询原会话任务' : '临时对话 · 刷新前请保存结果' }}</span>
        </p>
      </div>
      <aside class="answer-sidecar">
        <div class="source-heading"><AppIcon name="layers" :size="17" /><strong>答案背后的依据</strong><span>可追溯</span></div>

        <!-- 展开的原文独立在列表上方并可收起：不挡住其它引用，随时能继续核对下一条。 -->
        <aside v-if="openChunkId" class="source-view" aria-label="当前引用原文">
          <header class="source-view-head">
            <span>原文核对</span>
            <button type="button" class="source-collapse" @click="collapse">收起</button>
          </header>
          <p role="status">{{ sourceNotice }}</p>
          <template v-if="source">
            <h3>{{ source.title }} · v{{ source.versionNo }}</h3>
            <small>第{{ source.startLine }}—{{ source.endLine }}行</small>
            <pre>{{ source.content }}</pre>
            <details>
              <summary>来源定位信息</summary>
              <small>文档 {{ source.documentId }} / 版本 {{ source.versionId }} / 片段 {{ source.chunkId }} · UTF-16位置 {{ source.startOffset }}—{{ source.endOffset }}</small>
            </details>
          </template>
        </aside>

        <div v-if="recentEvidence.length" class="source-list">
          <p class="source-list-hint">本次回答引用的原文；点一条展开核对，再点一次收起。</p>
          <button v-for="item in recentEvidence" :key="item.chunkId" type="button" class="source-item"
            :class="{ open: openChunkId === item.chunkId }" :aria-expanded="openChunkId === item.chunkId" @click="inspect(item)">
            <span class="source-item-head">
              <strong>{{ item.title }}</strong>
              <small>v{{ item.versionNo }}</small>
            </span>
            <span class="source-item-line">第{{ item.startLine }}—{{ item.endLine }}行</span>
            <span class="source-item-snippet">{{ item.content }}</span>
          </button>
        </div>

        <div v-else-if="!openChunkId" class="source-placeholder">
          <div class="source-art"><AppIcon name="file" :size="34" /><span><AppIcon name="check" :size="13" /></span></div>
          <h3>每一个结论，<br />都有迹可循。</h3>
          <p>点击回答中的引用，在这里核对原文、版本和位置。</p>
          <div class="source-principle"><span>01</span><div><strong>资料有出处</strong><small>保留原文与版本，方便核对</small></div></div>
          <div class="source-principle"><span>02</span><div><strong>不确定，会告诉你</strong><small>依据不足时，明确保留空白</small></div></div>
        </div>

        <details class="answer-boundaries">
          <summary><AppIcon name="shield" :size="14" />隐私与回答边界</summary>
          <p class="answer-retrieval-note">当前服务端策略：{{ retrievalLabels[retrievalMode] ?? '尚未确认' }}。策略配置不等于依赖健康或索引就绪；历史回答不据此追溯标记。</p>
          <p v-if="persistent" class="answer-context-note">继续追问请保留同一会话；是否关联历史以每条处理提示为准。无法可靠确定话题时，不会强行继承旧问题。</p>
        </details>

        <KnowledgeFeedbackPanel v-if="persistent && ready" :key="`${enterpriseId}:${spaceId}`" :enterprise-id="enterpriseId" :space-id="spaceId" />
      </aside>
    </div>
    </template>
  </section>
</template>

<style scoped>
/* ============================== 基础 ============================== */
.knowledge-answer {
  flex: 1;
  min-height: 0;
  width: 100%;
  max-width: var(--content-max);
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  color: var(--text-primary);
}
.knowledge-answer h2,
.knowledge-answer h3,
.knowledge-answer h4 { margin: 0; color: var(--text-primary); }
p, small, li { font-size: var(--fs-xs); line-height: var(--lh-base); color: var(--text-tertiary); margin: 0; }

button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  border: 1px solid var(--border-default);
  border-radius: var(--r-sm);
  padding: 9px var(--sp-3);
  color: var(--text-secondary);
  background: var(--n-0);
  font: inherit;
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
  cursor: pointer;
  transition: background var(--dur-1) var(--ease-out),
              border-color var(--dur-1) var(--ease-out),
              color var(--dur-1) var(--ease-out),
              transform var(--dur-2) var(--ease-out),
              box-shadow var(--dur-2) var(--ease-out);
}
button:hover:not(:disabled) { background: var(--n-50); border-color: var(--brand-200); color: var(--brand-700); }
button:active:not(:disabled) { transform: scale(.98); }
button.solid { background: var(--brand-600); border-color: var(--brand-600); color: #fff; box-shadow: var(--sh-sm); }
button.solid:hover:not(:disabled) { background: var(--brand-700); border-color: var(--brand-700); color: #fff; }
button:disabled { opacity: .45; cursor: not-allowed; }
button:focus-visible, select:focus-visible,
textarea:focus-visible, summary:focus-visible {
  outline: 2px solid var(--brand-400);
  outline-offset: 2px;
}

/* ============================== 顶部工具栏 ============================== */
.answer-toolbar {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-4);
  padding-bottom: var(--sp-3);
}
.answer-brand { display: flex; align-items: center; gap: var(--sp-3); min-width: 0; }
.answer-account { display: flex; align-items: center; gap: var(--sp-3); flex-shrink: 0; }
.assistant-avatar {
  display: grid;
  place-items: center;
  background: var(--focus-block);
  color: #fff;
  width: 38px;
  height: 38px;
  border-radius: var(--r-md);
  box-shadow: var(--sh-sm);
  flex-shrink: 0;
}
.answer-brand h2 {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: var(--fs-lg);
  letter-spacing: -.2px;
  font-weight: var(--fw-semibold);
  line-height: 1.3;
}
.answer-brand h2 span {
  font-size: var(--fs-2xs);
  font-weight: var(--fw-medium);
  color: var(--brand-700);
  padding: 2px 7px;
  background: var(--brand-50);
  border-radius: var(--r-full);
  letter-spacing: 0;
}
.answer-brand p { font-size: var(--fs-2xs); color: var(--text-tertiary); margin-top: 2px; }
.connection-badge { color: var(--brand-700); font-size: var(--fs-2xs); display: inline-flex; align-items: center; }
.connection-badge i {
  display: inline-block;
  background: var(--brand-500);
  width: 6px;
  height: 6px;
  border-radius: var(--r-full);
  margin-right: 6px;
  box-shadow: 0 0 0 3px var(--brand-50);
}

/* ============================== 提示条 ============================== */
.answer-notice {
  flex: 0 0 auto;
  display: flex;
  gap: 8px;
  align-items: flex-start;
  font-size: var(--fs-2xs);
  line-height: var(--lh-base);
  color: var(--brand-700);
  background: var(--brand-50);
  border-radius: var(--r-sm);
  padding: 8px 12px;
  margin-bottom: var(--sp-1);
}
.answer-notice > svg { flex-shrink: 0; margin-top: 2px; }
.answer-notice > span { color: inherit; font-size: inherit; }

/* ============================== 控制行 ============================== */
.answer-controls {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
  padding: var(--sp-3) 0;
}
.answer-scope { display: flex; align-items: center; gap: var(--sp-2); min-width: 0; flex-wrap: wrap; }
.answer-scope label {
  display: flex;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--border-default);
  padding: 0 8px 0 10px;
  border-radius: var(--r-full);
  background: var(--n-0);
  color: var(--text-placeholder);
  min-width: 0;
  max-width: 300px;
  transition: border-color var(--dur-1), box-shadow var(--dur-2);
}
.answer-scope label:hover { border-color: var(--border-strong); }
.answer-scope label:focus-within { border-color: var(--brand-300); box-shadow: var(--ring); }
.answer-scope select { max-width: 250px; }
select {
  min-width: 0;
  max-width: 100%;
  background: transparent;
  color: var(--text-primary);
  border: 0;
  border-radius: var(--r-full);
  padding: 7px 2px;
  font: inherit;
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
  cursor: pointer;
}
.scope-refresh { padding: 0; width: 32px; height: 32px; border-radius: var(--r-full); flex-shrink: 0; }
.scope-privacy {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: var(--fs-2xs);
  color: var(--text-placeholder);
  flex-shrink: 0;
}

.session-tools { display: flex; align-items: center; gap: 4px; min-width: 0; flex-wrap: wrap; }
.session-tools label { display: flex; align-items: center; gap: 6px; min-width: 0; font-size: var(--fs-2xs); color: var(--text-placeholder); }
.session-tools select {
  max-width: 230px;
  background: var(--n-0);
  border: 1px solid var(--border-default);
  padding: 6px 10px;
  border-radius: var(--r-full);
}
.session-tools select:focus-visible { border-color: var(--brand-300); }
.session-tools button {
  font-size: var(--fs-2xs);
  border-color: transparent;
  background: transparent;
  padding: 6px 9px;
  border-radius: var(--r-full);
  color: var(--text-tertiary);
}
.session-tools button:hover:not(:disabled) { color: var(--brand-700); background: var(--brand-50); border-color: transparent; }
.session-tools .session-delete:hover:not(:disabled) { color: var(--danger); background: var(--danger-soft); }

.session-confirm {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  flex-wrap: wrap;
  margin-bottom: var(--sp-2);
  padding: 10px 12px;
  border: 1px solid var(--danger-border);
  background: var(--danger-soft);
  border-radius: var(--r-sm);
  font-size: var(--fs-2xs);
  line-height: var(--lh-base);
  color: var(--text-secondary);
}
.session-confirm span { flex: 1 1 240px; font-size: inherit; color: inherit; }
.session-confirm button { padding: 6px 12px; font-size: var(--fs-2xs); border-radius: var(--r-xs); }
.session-confirm button.danger { border-color: transparent; background: var(--danger); color: #fff; font-weight: var(--fw-medium); }

/* ============================== 工作台骨架 ============================== */
.answer-workbench {
  flex: 1 1 auto;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 304px;
  border: 1px solid var(--border-subtle);
  background: var(--n-0);
  border-radius: var(--r-lg);
  overflow: clip;
  box-shadow: var(--sh-sm);
}
.answer-stage { display: flex; flex-direction: column; min-width: 0; min-height: 0; }
.answer-stream {
  position: relative;
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: var(--sp-6) 36px var(--sp-4);
  scroll-behavior: smooth;
  /* 上下渐隐：滚动中被容器边缘切断的行不会显得像是残缺 */
  -webkit-mask-image: linear-gradient(to bottom, transparent 0, #000 14px, #000 calc(100% - 18px), transparent 100%);
  mask-image: linear-gradient(to bottom, transparent 0, #000 14px, #000 calc(100% - 18px), transparent 100%);
}

/* ============================== 空态 ============================== */
.answer-welcome {
  max-width: 660px;
  width: 100%;
  flex: 0 0 auto;
  /* auto 边距让空态在空白区域里居中；内容超高时自动退化为正常流，不会截断 */
  margin: auto auto;
  text-align: center;
  padding: var(--sp-2) 0 var(--sp-6);
  animation: rise-in var(--dur-4) var(--ease-out) both;
}
.answer-timeline { flex: 0 0 auto; }
.knowledge-constellation {
  width: 210px;
  height: 118px;
  position: relative;
  margin: 0 auto var(--sp-1);
  background: radial-gradient(ellipse at center, var(--brand-100) 0, rgba(217, 236, 226, .28) 45%, transparent 72%);
}
.knowledge-constellation::before,
.knowledge-constellation::after {
  content: '';
  position: absolute;
  inset: 10px 14px;
  border: 1px dashed var(--brand-200);
  border-radius: 50%;
  transform: rotate(-18deg);
}
.knowledge-constellation::after { inset: 6px 40px; transform: rotate(25deg); opacity: .7; }
.assistant-orb {
  display: grid;
  place-items: center;
  width: 62px;
  height: 62px;
  color: #fff;
  border: 4px solid var(--n-0);
  border-radius: var(--r-lg);
  background: var(--focus-block);
  box-shadow: var(--sh-md);
  position: relative;
  z-index: 1;
}
.knowledge-constellation .assistant-orb { position: absolute; top: 24px; left: 74px; transform: rotate(-8deg); }
.orbit-node {
  position: absolute;
  z-index: 2;
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  background: var(--n-0);
  border: 1px solid var(--border-subtle);
  box-shadow: var(--sh-xs);
  color: var(--brand-500);
  border-radius: var(--r-sm);
}
.node-book { left: 8px; top: 38px; transform: rotate(-12deg); }
.node-file { right: 14px; top: 10px; transform: rotate(10deg); color: var(--blue-500); }
.node-check { right: 24px; bottom: 6px; color: var(--amber-500); }
.knowledge-constellation > i {
  position: absolute;
  width: 5px;
  height: 5px;
  background: var(--brand-300);
  border-radius: var(--r-full);
  top: 10px;
  left: 60px;
}
.knowledge-constellation > i:last-child { top: auto; bottom: 4px; left: 55px; width: 4px; height: 4px; background: var(--n-300); }

.welcome-kicker { font-size: var(--fs-2xs); font-weight: var(--fw-semibold); letter-spacing: 2px; color: var(--brand-600); }
.answer-welcome h2 {
  font-size: clamp(22px, 2.2vw, 27px);
  line-height: 1.45;
  letter-spacing: -.5px;
  font-weight: var(--fw-semibold);
  margin: var(--sp-2) 0 10px;
}
.answer-welcome h2 > span { color: var(--brand-500); }
.answer-welcome > p { font-size: var(--fs-sm); color: var(--text-tertiary); margin-bottom: var(--sp-5); line-height: var(--lh-base); }

.question-starters { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; text-align: left; }
.question-starters button {
  position: relative;
  align-items: flex-start;
  flex-direction: column;
  padding: 14px;
  background: var(--n-0);
  min-width: 0;
  border-color: var(--border-subtle);
  box-shadow: var(--sh-xs);
  gap: 3px;
}
.question-starters button:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: var(--sh-md);
  border-color: var(--brand-200);
}
.question-starters button > svg:first-child { color: var(--brand-500); margin-bottom: 6px; }
.question-starters button:nth-child(2) > svg:first-child { color: var(--amber-500); }
.question-starters button:nth-child(3) > svg:first-child { color: var(--blue-500); }
.question-starters strong { font-size: var(--fs-xs); font-weight: var(--fw-semibold); color: var(--text-primary); }
.question-starters small { font-size: var(--fs-2xs); color: var(--text-placeholder); line-height: var(--lh-snug); }
.question-starters .starter-arrow {
  position: absolute;
  top: 14px;
  right: 12px;
  color: var(--n-300);
  transition: transform var(--dur-2) var(--ease-out);
}
.question-starters button:hover .starter-arrow { transform: translateX(3px); color: var(--brand-500); }

/* ============================== 对话流 ============================== */
.answer-timeline:empty { display: none; }
.answer-card {
  padding: 0 0 var(--sp-5);
  margin: 0 0 var(--sp-5);
  border-bottom: 1px solid var(--border-subtle);
  animation: rise-in var(--dur-3) var(--ease-out) both;
}
.answer-card:last-child { border-bottom: 0; margin-bottom: 0; padding-bottom: var(--sp-2); }

/* 用户提问：右侧实心气泡，一眼分清「我说的」和「它答的」 */
.user-question {
  display: flex;
  justify-content: flex-end;
  align-items: flex-start;
  gap: 10px;
  margin-bottom: var(--sp-4);
}
.user-question h3 {
  max-width: 78%;
  padding: 11px 15px;
  border-radius: var(--r-lg) var(--r-lg) var(--r-xs) var(--r-lg);
  background: var(--brand-600);
  color: #fff;
  font-size: var(--fs-base);
  font-weight: var(--fw-regular);
  line-height: 1.65;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  box-shadow: var(--sh-xs);
}
.user-avatar {
  flex-shrink: 0;
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  margin-top: 3px;
  border-radius: var(--r-full);
  background: var(--n-100);
  border: 1px solid var(--n-200);
  color: var(--text-tertiary);
  font-size: var(--fs-2xs);
  font-weight: var(--fw-medium);
}

/* 助手回答：无气泡，靠排版承托长文本 */
.answer-reply { display: grid; grid-template-columns: 26px minmax(0, 1fr); gap: 12px; }
.assistant-mini {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border-radius: var(--r-sm);
  background: var(--focus-block);
  color: #fff;
  flex-shrink: 0;
  margin-top: 1px;
}
.answer-reply-body { min-width: 0; }
.answer-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 10px; }
.assistant-label { display: flex; align-items: baseline; gap: 7px; }
.assistant-label strong { font-size: var(--fs-xs); font-weight: var(--fw-semibold); color: var(--text-primary); }
.assistant-label small { font-size: var(--fs-2xs); color: var(--text-placeholder); }

.answer-state { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.answer-state > strong {
  font-size: var(--fs-2xs);
  font-weight: var(--fw-medium);
  border-radius: var(--r-full);
  padding: 3px 9px;
  background: var(--brand-50);
  color: var(--brand-700);
}
.answer-state > small { font-size: var(--fs-2xs); color: var(--text-placeholder); font-variant-numeric: tabular-nums; }
.answer-state[data-status=PARTIAL] > strong,
.answer-state[data-status=EVIDENCE_ONLY] > strong,
.answer-state[data-status=SCOPE_UNKNOWN] > strong { background: var(--amber-soft); color: var(--amber-600); }
.answer-state[data-status=NO_EVIDENCE] > strong,
.answer-state[data-status=CONFLICT] > strong,
.answer-state[data-status=MODEL_UNAVAILABLE] > strong,
.answer-state[data-status=SCOPE_UNAVAILABLE] > strong { background: var(--danger-soft); color: var(--danger-fg); }
.answer-state[data-status=OUT_OF_SCOPE] > strong { background: var(--blue-soft); color: var(--blue-600); }

.answer-message {
  font-size: var(--fs-md);
  line-height: 1.85;
  color: var(--n-800);
  margin: 0 0 18px;
  overflow-wrap: anywhere;
}

.answer-part { margin-bottom: var(--sp-5); }
.answer-part:last-of-type { margin-bottom: 0; }
.answer-part h4 {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: var(--fs-md);
  line-height: var(--lh-snug);
  margin-bottom: 10px;
  font-weight: var(--fw-semibold);
}
.part-number {
  flex-shrink: 0;
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  border-radius: var(--r-full);
  background: var(--brand-50);
  color: var(--brand-700);
  font-size: 10px;
  font-weight: var(--fw-semibold);
  font-variant-numeric: tabular-nums;
}
.part-title { flex: 1 1 auto; min-width: 0; overflow-wrap: anywhere; font-size: inherit; }
.answer-part h4 small {
  flex-shrink: 0;
  font-size: var(--fs-2xs);
  font-weight: var(--fw-regular);
  color: var(--text-placeholder);
  background: var(--n-50);
  border-radius: var(--r-full);
  padding: 2px 8px;
}
.answer-part[data-status=PARTIAL] > h4 small,
.answer-part[data-status=EVIDENCE_ONLY] > h4 small { background: var(--amber-soft); color: var(--amber-600); }
.answer-part[data-status=NO_EVIDENCE] > h4 small,
.answer-part[data-status=CONFLICT] > h4 small { background: var(--danger-soft); color: var(--danger-fg); }

.answer-paragraph + .answer-paragraph { margin-top: 14px; }
.answer-text,
pre { white-space: pre-wrap; overflow-wrap: anywhere; }
.answer-text {
  font-size: var(--fs-md);
  line-height: 1.9;
  color: var(--n-700);
  text-wrap: pretty;
}

/* 引用：单行胶囊，不打断阅读节奏 */
.citation-row { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 9px; }
.citation-button {
  justify-content: flex-start;
  gap: 6px;
  padding: 3px 10px 3px 3px;
  border-radius: var(--r-full);
  border-color: var(--n-150);
  background: var(--n-25);
  max-width: 100%;
  min-width: 0;
}
.citation-button:hover:not(:disabled) { background: var(--brand-50); border-color: var(--brand-200); }
.citation-index {
  flex-shrink: 0;
  display: grid;
  place-items: center;
  width: 17px;
  height: 17px;
  border-radius: var(--r-full);
  background: var(--brand-100);
  color: var(--brand-700);
  font-size: 10px;
  font-weight: var(--fw-semibold);
  font-variant-numeric: tabular-nums;
}
.citation-body { display: inline-flex; align-items: baseline; gap: 6px; min-width: 0; }
.citation-button strong {
  font-size: var(--fs-2xs);
  font-weight: var(--fw-medium);
  color: var(--text-secondary);
  max-width: 190px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.citation-button small { font-size: 10px; color: var(--text-placeholder); white-space: nowrap; }
.citation-button > svg { flex-shrink: 0; color: var(--n-300); }
.citation-button:hover > svg { color: var(--brand-500); }

.evidence-gaps {
  list-style: none;
  padding: 0 0 0 11px;
  margin: 12px 0 0;
  border-left: 2px solid var(--amber-400);
}
.evidence-gaps li { display: flex; align-items: flex-start; gap: 6px; font-size: var(--fs-xs); line-height: var(--lh-base); color: var(--amber-600); }
.evidence-gaps li + li { margin-top: 4px; }
.evidence-gaps svg { margin-top: 4px; flex-shrink: 0; }
.evidence-gaps span { font-size: inherit; color: inherit; }

pre {
  font-family: var(--font-mono);
  font-size: var(--fs-2xs);
  line-height: 1.75;
  background: var(--n-50);
  border: 1px solid var(--border-subtle);
  padding: 10px 12px;
  border-radius: var(--r-sm);
  color: var(--text-secondary);
}
.answer-evidence { margin-top: var(--sp-4); }
.answer-evidence summary { font-size: var(--fs-2xs); }
.answer-evidence > div { margin-top: 10px; }
.answer-evidence > div > button { font-size: var(--fs-2xs); padding: 5px 10px; }
.answer-evidence pre { margin-top: 6px; max-height: 210px; overflow: auto; }

summary {
  cursor: pointer;
  color: var(--text-tertiary);
  font-size: var(--fs-xs);
  line-height: var(--lh-base);
  font-weight: var(--fw-medium);
  display: inline-flex;
  align-items: center;
  gap: 4px;
  transition: color var(--dur-1) var(--ease-out);
}
summary::-webkit-details-marker { display: none; }
summary::marker { content: ''; }
/* 改成 flex 后原生三角不再绘制，用自绘箭头保留「可以展开」的暗示 */
summary::before {
  content: '';
  flex-shrink: 0;
  width: 5px;
  height: 5px;
  margin-right: 3px;
  border-right: 1.5px solid currentColor;
  border-bottom: 1.5px solid currentColor;
  transform: rotate(-45deg);
  transition: transform var(--dur-2) var(--ease-out);
}
details[open] > summary::before { transform: rotate(45deg); }
summary:hover { color: var(--brand-700); }

/* 开发者/治理向的操作收成一行幽灵按钮，不抢正文注意力 */
.answer-tools { display: flex; flex-wrap: wrap; align-items: flex-start; gap: 2px; margin-top: 14px; }
.answer-tools details { margin: 0; }
.answer-tools summary {
  padding: 5px 10px;
  border-radius: var(--r-full);
  font-size: var(--fs-2xs);
  color: var(--text-placeholder);
  list-style: none;
}
.answer-tools details[open] { flex: 1 1 100%; }
.answer-tools details[open] > summary { background: var(--n-50); color: var(--brand-700); }
.answer-tools details > small { display: block; margin: 8px 0 6px; overflow-wrap: anywhere; padding-left: 10px; }
.answer-tools details > div { display: flex; gap: 6px; flex-wrap: wrap; padding-left: 10px; }
.answer-tools details > button { margin-left: 10px; }
.answer-tools details textarea { margin: 8px 0; }
.answer-tools details label { display: block; padding-left: 10px; }
.answer-tools details > button,
.answer-tools details > label { font-size: var(--fs-2xs); }

.answer-caption { font-size: var(--fs-2xs); color: var(--brand-700); margin-top: 10px; }

.answer-error {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  color: var(--danger-fg);
  background: var(--danger-soft);
  border-radius: var(--r-sm);
  padding: 10px 12px;
  font-size: var(--fs-xs);
  line-height: var(--lh-base);
  margin: 10px 0 0;
}
.answer-error svg { flex-shrink: 0; margin-top: 2px; }

.answer-progress {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: 12px 14px;
  border-radius: var(--r-md);
  background: var(--n-50);
  margin-bottom: 12px;
}
.answer-progress strong { font-size: var(--fs-xs); font-weight: var(--fw-medium); color: var(--brand-700); }
.answer-progress p { margin: 3px 0 0; font-size: var(--fs-2xs); color: var(--text-placeholder); }
.thinking-dots { display: flex; gap: 4px; flex-shrink: 0; }
.thinking-dots i {
  width: 6px;
  height: 6px;
  border-radius: var(--r-full);
  background: var(--brand-400);
  animation: thinking 1s var(--ease-in-out) infinite alternate;
}
.thinking-dots i:nth-child(2) { animation-delay: .2s; }
.thinking-dots i:nth-child(3) { animation-delay: .4s; }

.answer-scope-switch {
  margin-top: var(--sp-4);
  padding: 12px 14px;
  border-radius: var(--r-sm);
  background: var(--blue-soft);
}
.answer-scope-switch p { font-size: var(--fs-2xs); color: var(--blue-600); margin-bottom: 8px; }
.answer-scope-switch button { font-size: var(--fs-2xs); padding: 6px 12px; }

/* ============================== 输入框 ============================== */
.enterprise-composer {
  flex: 0 0 auto;
  margin: 0 36px var(--sp-2);
  padding: 12px 14px;
  border: 1px solid var(--border-default);
  border-radius: var(--r-md);
  background: var(--n-25);
  transition: border-color var(--dur-2), box-shadow var(--dur-2), background var(--dur-2);
}
.enterprise-composer:focus-within {
  border-color: var(--brand-300);
  background: var(--n-0);
  box-shadow: var(--ring);
}
.composer-label {
  display: block;
  font-size: 10px;
  letter-spacing: .4px;
  color: var(--text-placeholder);
  font-weight: var(--fw-medium);
  margin-bottom: 2px;
}
textarea {
  display: block;
  box-sizing: border-box;
  width: 100%;
  font-family: inherit;
  font-size: var(--fs-base);
  line-height: var(--lh-base);
  resize: none;
  border: 1px solid var(--border-default);
  border-radius: var(--r-sm);
  background: var(--n-0);
  color: var(--text-primary);
  padding: 10px 12px;
}
.enterprise-composer textarea {
  border: 0;
  padding: 4px 0;
  background: transparent;
  outline: none;
  min-height: 46px;
  font-size: var(--fs-md);
  line-height: 1.7;
}
.enterprise-composer textarea:disabled { background: transparent; }
.enterprise-composer textarea::placeholder { color: var(--text-placeholder); }
.answer-compose-footer { display: flex; align-items: center; gap: 10px; justify-content: flex-end; }
.composer-space {
  margin-right: auto;
  display: flex;
  gap: 5px;
  align-items: center;
  color: var(--text-tertiary);
  font-size: var(--fs-2xs);
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.answer-compose-footer small { font-size: var(--fs-2xs); color: var(--text-placeholder); font-variant-numeric: tabular-nums; }
.answer-compose-footer .solid { padding: 8px 16px; border-radius: var(--r-full); }
.answer-compose-footer .solid > span { font-size: var(--fs-xs); }
.composer-help {
  flex: 0 0 auto;
  display: flex;
  justify-content: space-between;
  gap: var(--sp-3);
  margin: 0 36px 10px;
  font-size: 10px;
  color: var(--text-placeholder);
}
.composer-help > span { font-size: inherit; color: inherit; }

/* ============================== 右侧依据栏 ============================== */
.answer-sidecar {
  min-width: 0;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  border-left: 1px solid var(--border-subtle);
  background: var(--n-25);
  padding: var(--sp-5) 18px var(--sp-4);
  display: flex;
  flex-direction: column;
}
.source-heading { display: flex; align-items: center; gap: 7px; font-size: var(--fs-xs); color: var(--text-secondary); font-weight: var(--fw-semibold); flex-shrink: 0; }
.source-heading > span {
  margin-left: auto;
  font-size: 10px;
  font-weight: var(--fw-medium);
  color: var(--brand-700);
  background: var(--brand-50);
  border-radius: var(--r-full);
  padding: 2px 8px;
}

/* 有引用时优先显示真实内容，而不是插画 */
.source-list { margin-top: var(--sp-4); display: flex; flex-direction: column; gap: 8px; }
.source-list-hint { font-size: var(--fs-2xs); color: var(--text-placeholder); line-height: var(--lh-base); margin-bottom: 2px; }
.source-item {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 3px;
  width: 100%;
  text-align: left;
  padding: 10px 11px;
  border-radius: var(--r-sm);
  border-color: var(--border-subtle);
  background: var(--n-0);
  box-shadow: var(--sh-xs);
}
.source-item:hover:not(:disabled) { border-color: var(--brand-200); background: var(--n-0); box-shadow: var(--sh-sm); }
/* 正在核对的那一条保持高亮，方便对着上方的原文看是哪一段。 */
.source-item.open { border-color: var(--brand-200); background: var(--brand-50); }
.source-item-head { display: flex; align-items: baseline; gap: 6px; }
.source-item-head strong {
  font-size: var(--fs-2xs);
  font-weight: var(--fw-semibold);
  color: var(--text-primary);
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.source-item-head small { flex-shrink: 0; font-size: 10px; color: var(--brand-700); background: var(--brand-50); border-radius: var(--r-full); padding: 1px 6px; }
.source-item-line { font-size: 10px; color: var(--text-placeholder); font-variant-numeric: tabular-nums; }
.source-item-snippet {
  font-size: var(--fs-2xs);
  line-height: 1.65;
  color: var(--text-tertiary);
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
  margin-top: 3px;
}

.source-placeholder { padding: var(--sp-7) 0 var(--sp-4); }
.source-art {
  position: relative;
  width: 64px;
  height: 70px;
  display: grid;
  place-items: center;
  margin: 0 0 var(--sp-5) 6px;
  background: var(--n-0);
  color: var(--n-300);
  border: 1px solid var(--border-subtle);
  border-radius: var(--r-sm);
  transform: rotate(-9deg);
  box-shadow: var(--sh-xs);
}
.source-art > span {
  position: absolute;
  right: -11px;
  bottom: 6px;
  color: #fff;
  background: var(--brand-500);
  border: 3px solid var(--n-25);
  border-radius: var(--r-full);
  padding: 4px;
}
.source-placeholder h3 { font-size: var(--fs-lg); line-height: 1.55; font-weight: var(--fw-semibold); margin: 0 0 10px; }
.source-placeholder > p { color: var(--text-tertiary); font-size: var(--fs-2xs); margin-bottom: var(--sp-5); line-height: var(--lh-base); }
.source-principle { display: flex; align-items: flex-start; gap: 10px; margin-top: 12px; }
.source-principle > span { color: var(--n-300); font-size: 10px; margin-top: 3px; font-weight: var(--fw-semibold); font-variant-numeric: tabular-nums; }
.source-principle strong { display: block; font-size: var(--fs-xs); font-weight: var(--fw-semibold); color: var(--text-secondary); }
.source-principle small { display: block; font-size: var(--fs-2xs); color: var(--text-placeholder); margin-top: 2px; line-height: var(--lh-snug); }

.source-view { margin-top: var(--sp-4); }
/* 展开的原文要有一个显眼的退出入口，否则长原文占满整栏就回不到列表。 */
.source-view-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
  font-size: var(--fs-2xs);
  font-weight: var(--fw-semibold);
  color: var(--text-secondary);
}
.source-collapse {
  margin-left: auto;
  padding: 3px 10px;
  font-size: 10px;
  font-weight: var(--fw-medium);
  color: var(--text-secondary);
  background: transparent;
  border-color: var(--border-subtle);
  border-radius: var(--r-full);
}
.source-collapse:hover:not(:disabled) { color: var(--brand-700); border-color: var(--brand-200); }
.source-view h3 { font-size: var(--fs-sm); overflow-wrap: anywhere; font-weight: var(--fw-semibold); }
.source-view > small { display: block; margin: 4px 0 8px; font-size: 10px; color: var(--text-placeholder); }
.source-view pre { max-height: 380px; overflow: auto; }
.source-view details { margin-top: 10px; }
.source-view details small { display: block; margin-top: 6px; overflow-wrap: anywhere; }

.answer-boundaries { border-top: 1px solid var(--border-subtle); padding-top: var(--sp-3); margin-top: auto; flex-shrink: 0; }
.answer-boundaries summary { font-size: var(--fs-2xs); color: var(--text-placeholder); }
.answer-boundaries p { font-size: var(--fs-2xs); color: var(--text-tertiary); margin-top: 8px; line-height: var(--lh-base); }

/* ============================== 未登录态 ============================== */
.answer-login-state {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  padding: var(--sp-6) var(--sp-4);
  text-align: center;
}
.login-hero { max-width: 480px; width: 100%; margin: auto auto 0; animation: rise-in var(--dur-4) var(--ease-out) both; }
.login-hero .assistant-orb { margin: 0 auto var(--sp-5); width: 72px; height: 72px; }
.login-hero h2 {
  font-size: var(--fs-2xl);
  font-weight: var(--fw-semibold);
  letter-spacing: -.5px;
  margin-bottom: 10px;
  line-height: var(--lh-snug);
}
.login-hero p { font-size: var(--fs-sm); margin-bottom: var(--sp-5); line-height: var(--lh-base); color: var(--text-tertiary); }
.login-hero .solid { padding: 11px var(--sp-6); font-size: var(--fs-sm); border-radius: var(--r-full); }

.login-preview {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--sp-3);
  max-width: 900px;
  width: 100%;
  margin: var(--sp-6) auto auto;
  text-align: left;
  animation: rise-in var(--dur-4) var(--ease-out) .1s both;
}
.login-preview article {
  padding: 18px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--r-md);
  background: var(--n-0);
  box-shadow: var(--sh-xs);
}
.preview-icon { display: grid; place-items: center; width: 34px; height: 34px; border-radius: var(--r-sm); margin-bottom: 10px; }
.preview-icon.sage { background: var(--brand-50); color: var(--brand-600); }
.preview-icon.blue { background: var(--blue-soft); color: var(--blue-600); }
.preview-icon.amber { background: var(--amber-soft); color: var(--amber-600); }
.login-preview h3 { font-size: var(--fs-sm); font-weight: var(--fw-semibold); margin: 0 0 5px; }
.login-preview p { font-size: var(--fs-2xs); line-height: var(--lh-base); color: var(--text-tertiary); }

@keyframes thinking { to { opacity: .25; transform: translateY(-3px); } }

/* ============================== 响应式 ============================== */
@media (max-width: 1280px) {
  .answer-workbench { grid-template-columns: minmax(0, 1fr) 268px; }
  .answer-stream { padding-left: 26px; padding-right: 26px; }
  .enterprise-composer { margin-left: 26px; margin-right: 26px; }
  .composer-help { margin-left: 26px; margin-right: 26px; }
  .answer-sidecar { padding: var(--sp-4) 14px; }
}

/* 窄屏不再固定高度：整页滚动 + 依据栏落到内容下方 */
@media (max-width: 980px) {
  .knowledge-answer { display: block; }
  .answer-workbench { display: block; }
  .answer-stream { overflow: visible; padding: var(--sp-4) var(--sp-4) 0; -webkit-mask-image: none; mask-image: none; }
  .answer-sidecar { border-left: 0; border-top: 1px solid var(--border-subtle); overflow: visible; padding: var(--sp-4); }
  .source-placeholder { display: none; }
  .answer-boundaries { margin-top: var(--sp-3); }
  .enterprise-composer { margin: 0 var(--sp-4) var(--sp-2); }
  .composer-help { margin: 0 var(--sp-4) var(--sp-3); }
  .login-preview { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 640px) {
  .answer-toolbar { gap: var(--sp-2); }
  .answer-brand h2 { font-size: var(--fs-base); }
  .answer-account button { font-size: var(--fs-2xs); padding: 7px 10px; }
  .connection-badge { display: none; }
  .answer-scope label { max-width: calc(100% - 40px); }
  .answer-scope select { max-width: 100%; }
  .scope-privacy { display: none; }
  .answer-welcome h2 { font-size: var(--fs-xl); }
  .knowledge-constellation { transform: scale(.86); margin-bottom: -8px; }
  .question-starters { grid-template-columns: 1fr; gap: 6px; }
  .question-starters button { flex-direction: row; align-items: center; padding: 11px 12px; }
  .question-starters button > svg:first-child { margin-bottom: 0; }
  .question-starters small, .starter-arrow { display: none; }
  .answer-stream { padding: var(--sp-3) var(--sp-3) 0; }
  .user-question h3 { max-width: 86%; font-size: var(--fs-sm); }
  .answer-reply { grid-template-columns: 22px minmax(0, 1fr); gap: 9px; }
  .assistant-mini { width: 22px; height: 22px; }
  .answer-message, .answer-text { font-size: var(--fs-base); line-height: 1.8; }
  .citation-button strong { max-width: 120px; }
  .citation-button small { display: none; }
  .enterprise-composer { margin: 0 var(--sp-3) var(--sp-2); padding: 10px; }
  .composer-help { margin: 0 var(--sp-3) var(--sp-2); flex-wrap: wrap; }
  .answer-compose-footer .solid > span { display: none; }
  .answer-compose-footer .solid { padding: 8px 12px; }
  .answer-sidecar { padding: var(--sp-3); }
  .login-hero h2 { font-size: var(--fs-xl); }
  .login-preview { grid-template-columns: 1fr; margin-top: var(--sp-8); }
  .session-tools label { width: 100%; }
  .session-tools select { flex: 1; max-width: 100%; }
}

@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after { animation: none !important; transition: none !important; scroll-behavior: auto !important; }
}
</style>
