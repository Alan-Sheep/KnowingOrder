<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import AppIcon from './components/AppIcon.vue';
import AnswerText from './components/AnswerText.vue';
import PreferenceSettings from './components/PreferenceSettings.vue';
import ProductSearch from './components/ProductSearch.vue';
import EnterpriseWorkspace from './components/EnterpriseWorkspace.vue';
import { effectiveAnswerLanguage, formatMessageTime, loadPreferences } from './preferences';
import { translate } from './messages';
import { safeUrl, sendQuestion } from './api';
import { createDemo } from './demo';
import { publicChatEnabled } from './public-chat';
import type { ChatTurn, Conversation, PartStatus } from './types';

const conversations = ref<Conversation[]>([]);
const preferences = ref(loadPreferences());
const t = (text: string, values?: Record<string, string | number>) => translate(preferences.value.uiLanguage, text, values);
watch(() => preferences.value.uiLanguage, value => { document.documentElement.lang = value; }, { immediate: true });
const activeId = ref('');
const draft = ref('');
const historyQuery = ref('');
const sidebarOpen = ref(false);
type WorkspacePage = 'home' | 'enterprise' | 'knowledge' | 'affairs' | 'chat';
const page = ref<WorkspacePage>('home');
/** 从其它页面跳来知识空间登录时的来源页；为空表示用户本来就是来管理知识的。 */
const loginReturn = ref<WorkspacePage | null>(null);
const pageTitles: Record<WorkspacePage, string> = { home: '工作台', enterprise: '企业问答', knowledge: '知识空间', affairs: '员工事务', chat: '联网问答体验' };
const contextOpen = ref(false);
const selectedTurnId = ref('');
const selectedSourceId = ref('');
const toast = ref('');
const now = ref(Date.now());
const online = ref(navigator.onLine);
const feed = ref<HTMLElement>();
const input = ref<HTMLTextAreaElement>();
const dialog = ref<HTMLDialogElement>();
const dialogKind = ref<'settings' | 'knowledge' | 'help' | 'products'>('settings');
const controllers = new Map<string, AbortController>();
let tick: ReturnType<typeof setInterval>;
let toastTimer: ReturnType<typeof setTimeout>;

const active = computed(() => conversations.value.find(item => item.id === activeId.value));
const turns = computed(() => active.value?.turns ?? []);
const hasTurns = computed(() => turns.value.length > 0);
const pendingCount = computed(() => conversations.value.reduce((sum, item) => sum + item.turns.filter(turn => turn.state === 'pending').length, 0));
const questionLength = computed(() => Array.from(draft.value).length);
const canSend = computed(() => publicChatEnabled && page.value === 'chat' && draft.value.trim().length > 0 && questionLength.value <= 500 && pendingCount.value < 2 && online.value);
const history = computed(() => conversations.value.filter(item => item.turns.length && item.title.toLocaleLowerCase().includes(historyQuery.value.toLocaleLowerCase())).slice().reverse());
const selectedTurn = computed(() => turns.value.find(turn => turn.id === selectedTurnId.value && turn.answer)
  ?? turns.value.filter(turn => turn.answer).at(-1));
const sources = computed(() => selectedTurn.value?.answer?.answers.flatMap(part => part.sources.map(source => ({ ...source, question: part.question }))) ?? []);
const answeredCount = computed(() => selectedTurn.value?.answer?.answers.filter(part => part.status === 'ANSWERED').length ?? 0);

const suggestions = [
  { icon: 'layers', type: '理解一个概念', title: 'RAG 和直接问大模型，有什么不同？', question: '请用简单的例子解释 RAG 和直接问大模型有什么区别。', color: 'green' },
  { icon: 'briefcase', type: '把事情安排好', title: 'Java 后端面试，我该如何准备？', question: '我准备参加 Java 后端面试，请帮我安排一周的复习计划。', color: 'sand' },
  { icon: 'globe', type: '查找可靠信息', title: '联网核对 Java 17 的发布信息', question: '请联网核对 Java 17 的发布日期和长期支持性质，并给出资料来源。', color: 'blue' },
  { icon: 'sparkle', type: '一次问几个问题', title: '晚饭、面试和职业选择，一起聊聊', question: '晚上放了学回家吃什么要去面试的话需要准备啥agent和java两个哪个就业好？', color: 'rose' }
];
const labels: Record<PartStatus, string> = {
  ANSWERED: '已回答', PARTIAL: '部分回答', NO_EVIDENCE: '未找到资料',
  INSUFFICIENT_EVIDENCE: '资料不足', CLARIFICATION_NEEDED: '信息待补充',
  DEPENDENCY_FAILED: '前置问题未完成', FAILED: '暂未完成', TIMED_OUT: '处理超时'
};

function notify(message: string) {
  toast.value = message;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { toast.value = ''; }, 5500);
}

function newConversation() {
  navigate('chat');
  activeId.value = '';
  draft.value = '';
  selectedTurnId.value = '';
  selectedSourceId.value = '';
  sidebarOpen.value = false;
  nextTick(() => input.value?.focus());
}

function chooseConversation(id: string) {
  navigate('chat');
  activeId.value = id;
  selectedTurnId.value = '';
  selectedSourceId.value = '';
  sidebarOpen.value = false;
  scrollBottom();
}

function fillQuestion(question: string) {
  draft.value = question;
  nextTick(() => { input.value?.focus(); resizeInput(); });
}

function scrollBottom() {
  nextTick(() => feed.value?.scrollTo({ top: feed.value.scrollHeight, behavior: 'smooth' }));
}

function resizeInput() {
  if (!input.value) return;
  input.value.style.height = 'auto';
  input.value.style.height = `${Math.min(input.value.scrollHeight, 160)}px`;
}

async function submit() {
  if (!canSend.value) return;
  if (!active.value && conversations.value.length >= 30) {
    notify('当前页面已有 30 个会话，请先移除一个会话再创建。');
    return;
  }
  const question = draft.value.trim();
  // 提交时复制偏好；切换设置不会改变已发出的请求或续期其等待上限。
  const requestPreferences = { ...preferences.value };
  if (!active.value) {
    const conversation: Conversation = { id: crypto.randomUUID(), title: question, createdAt: Date.now(), turns: [] };
    conversations.value.push(conversation);
    activeId.value = conversation.id;
  }
  const conversationId = activeId.value;
  const turn: ChatTurn = { id: crypto.randomUUID(), question, createdAt: Date.now(), state: 'pending' };
  active.value!.turns.push(turn);
  // 用响应式数组中的对象更新，确保异步结果能够触发页面渲染。
  const savedTurn = active.value!.turns.at(-1)!;
  const controller = new AbortController();
  controllers.set(turn.id, controller);
  draft.value = '';
  nextTick(resizeInput);
  scrollBottom();
  try {
    savedTurn.answer = await sendQuestion(question, controller.signal, 95000,
      requestPreferences.timeZone, effectiveAnswerLanguage(requestPreferences));
    savedTurn.state = 'done';
  } catch (error) {
    savedTurn.state = 'error';
    savedTurn.error = error instanceof Error ? error.message : '请求未完成，请稍后手动重试。';
  } finally {
    controllers.delete(turn.id);
    if (activeId.value === conversationId) {
      selectedTurnId.value = turn.id;
      if (feed.value && feed.value.scrollHeight - feed.value.scrollTop - feed.value.clientHeight < 300) scrollBottom();
      else notify('有一条新的回答结果，可以向下查看。');
    } else {
      notify('另一个会话的请求已结束，可在左侧查看。');
    }
  }
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229) {
    event.preventDefault();
    void submit();
  }
}

function showSources(turn: ChatTurn, sourceId = '') {
  selectedTurnId.value = turn.id;
  selectedSourceId.value = sourceId;
  contextOpen.value = true;
  if (sourceId) nextTick(() => document.getElementById(`source-${sourceId}`)?.scrollIntoView({ block: 'nearest', behavior: 'smooth' }));
}

function showRequestDetails() {
  document.getElementById('request-details')?.scrollIntoView({ behavior: 'smooth' });
}

function showDemo() {
  if (conversations.value.length >= 30) { notify('请先移除一个会话再体验示例。'); return; }
  navigate('chat');
  const conversation = createDemo();
  conversations.value.push(conversation);
  activeId.value = conversation.id;
  selectedTurnId.value = conversation.turns[0]!.id;
  selectedSourceId.value = '';
  contextOpen.value = true;
  scrollBottom();
}

function navigate(destination: WorkspacePage) {
  // 主动切走页面时，取消尚未完成的「登录后回到原页面」意图，避免下次登录被莫名弹走。
  if (destination !== 'knowledge') loginReturn.value = null;
  page.value = destination;
  sidebarOpen.value = false;
  contextOpen.value = false;
}

/** 工作台／企业问答发起登录：记住来源页，登录成功后自动回去。 */
function startLogin() {
  if (page.value !== 'knowledge') loginReturn.value = page.value;
  navigate('knowledge');
}

/** 知识空间登录成功：只有从别的页面带着意图过来时才回跳，本来就站在这儿就不动。 */
function finishLogin() {
  const back = loginReturn.value;
  loginReturn.value = null;
  if (back) navigate(back);
}

function openDialog(kind: typeof dialogKind.value) {
  sidebarOpen.value = false;
  dialogKind.value = kind;
  dialog.value?.showModal();
}

function removeConversation(id: string) {
  const item = conversations.value.find(conversation => conversation.id === id);
  if (item?.turns.some(turn => turn.state === 'pending')) { notify('此会话仍有请求正在处理，请等待结束后再移除。'); return; }
  conversations.value = conversations.value.filter(conversation => conversation.id !== id);
  if (activeId.value === id) newConversation();
  notify('已从当前页面移除此会话。');
}

async function copyAnswer(turn: ChatTurn) {
  const text = turn.answer?.answers.map(part => `${part.question}\n${part.answer ?? part.message ?? ''}`).join('\n\n');
  if (!text) return;
  try { await navigator.clipboard.writeText(text); notify('回答已复制。'); }
  catch { notify('浏览器未允许复制，请选中文字手动复制。'); }
}

function exportConversation() {
  if (!active.value?.turns.length) { notify('发送问题后，可以将对话导出为文本。'); return; }
  const text = active.value.turns.map(turn => `问：${turn.question}\n\n${turn.demo ? '【界面演示，非真实回答】\n' : ''}${turn.answer?.answers.map(part => `${part.question}\n${part.answer ?? part.message ?? ''}\n${part.sources.map(source => `${source.title} ${safeUrl(source.url) ?? '链接不可用'}`).join('\n')}`).join('\n\n') ?? turn.error ?? '本次请求仍在等待中，导出内容不包含后续结果。'}`).join('\n\n---\n\n');
  const url = URL.createObjectURL(new Blob([text], { type: 'text/plain;charset=utf-8' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = '知序-对话记录.txt';
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function hostname(value: string) {
  const url = safeUrl(value);
  return url ? new URL(url).hostname : '链接不可用';
}

function time(value: number) {
  return formatMessageTime(value, preferences.value);
}

function networkChanged() {
  online.value = navigator.onLine;
  if (!online.value) notify('网络已断开。恢复后请检查原请求结果，页面不会自动重复提交。');
}

onMounted(() => {
  tick = setInterval(() => { now.value = Date.now(); }, 1000);
  window.addEventListener('online', networkChanged);
  window.addEventListener('offline', networkChanged);
});
onUnmounted(() => {
  clearInterval(tick);
  clearTimeout(toastTimer);
  controllers.forEach(controller => controller.abort());
  window.removeEventListener('online', networkChanged);
  window.removeEventListener('offline', networkChanged);
});
</script>

<template>
  <div class="workspace enterprise-shell" :class="{ 'enterprise-mode': page !== 'chat', 'context-visible': contextOpen && page === 'chat', 'sidebar-visible': sidebarOpen }">
    <button v-if="sidebarOpen" class="mobile-scrim" :aria-label="t('关闭导航')" @click="sidebarOpen = false"></button>
    <aside class="sidebar" aria-label="主导航">
      <a class="brand" href="#" @click.prevent="navigate('home')">
        <span class="brand-mark"><svg viewBox="0 0 32 32" fill="none" aria-hidden="true"><path d="M8 9h16L8 23h16M10 16h12" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" /></svg></span>
        <span><strong>知序<span class="brand-dot">.</span></strong><small>KNOWINGORDER</small></span>
      </a>
      <div class="enterprise-space-label"><span class="enterprise-space-avatar"><AppIcon name="briefcase" :size="17" /></span><span><strong>知序工作空间</strong><small>企业知识与工作助手</small></span></div>
      <nav class="primary-nav">
        <span class="nav-group-label">企业工作</span>
        <button class="nav-item" :class="{ active: page === 'home' }" :aria-current="page === 'home' ? 'page' : undefined" @click="navigate('home')"><AppIcon name="panel" :size="18" />工作台<span v-if="page === 'home'" class="nav-indicator"></span></button>
        <button class="nav-item" :class="{ active: page === 'enterprise' }" :aria-current="page === 'enterprise' ? 'page' : undefined" @click="navigate('enterprise')"><AppIcon name="chat" :size="18" />企业问答</button>
        <button class="nav-item" :class="{ active: page === 'knowledge' }" :aria-current="page === 'knowledge' ? 'page' : undefined" @click="navigate('knowledge')"><AppIcon name="book" :size="18" />知识空间</button>
        <button class="nav-item" :class="{ active: page === 'affairs' }" :aria-current="page === 'affairs' ? 'page' : undefined" @click="navigate('affairs')"><AppIcon name="briefcase" :size="18" />员工事务</button>
        <span class="nav-group-label">公开体验</span>
        <button class="nav-item" :class="{ active: page === 'chat' }" :aria-current="page === 'chat' ? 'page' : undefined" @click="navigate('chat')"><AppIcon name="globe" :size="18" />联网问答体验<AppIcon class="nav-end-icon" name="external" :size="13" /></button>
        <button v-if="page === 'chat'" class="public-new-chat" @click="newConversation"><AppIcon name="plus" :size="14" />{{ t('开启新对话') }}</button>
      </nav>
      <template v-if="page === 'chat'">
      <div class="history-heading"><span>{{ t('当前页面会话') }}</span><span>{{ history.length.toString().padStart(2, '0') }}</span></div>
      <label class="history-search"><AppIcon name="search" :size="15" /><input v-model="historyQuery" :aria-label="t('搜索对话…')" :placeholder="t('搜索对话…')" /></label>
      <div class="history-list">
        <div v-for="conversation in history" :key="conversation.id" class="history-row" :class="{ selected: conversation.id === activeId }">
          <button class="history-select" @click="chooseConversation(conversation.id)"><AppIcon name="chat" :size="15" /><span>{{ conversation.title }}</span><i v-if="conversation.turns.some(turn => turn.state === 'pending')" class="busy-dot"></i></button>
          <button class="history-delete" :aria-label="t('移除此会话')" @click="removeConversation(conversation.id)"><AppIcon name="close" :size="13" /></button>
        </div>
        <div v-if="!history.length" class="history-empty"><span class="history-empty-line"></span><p>{{ t(historyQuery ? '没有找到相关会话' : '留一点空间，给新的想法') }}</p><small>{{ t(historyQuery ? '试试其他关键词' : '你的对话将出现在这里') }}</small></div>
      </div>
      </template>
      <div v-if="page !== 'chat'" class="nav-context"><div class="nav-context-art" aria-hidden="true"><span><AppIcon :name="page === 'knowledge' ? 'book' : page === 'enterprise' ? 'sparkle' : page === 'affairs' ? 'briefcase' : 'layers'" :size="24" /></span><i></i><i></i></div><strong>{{ page === 'knowledge' ? '让知识，持续生长' : page === 'enterprise' ? '让问题，有据可循' : page === 'affairs' ? '让琐事，少点来回' : '让工作，从容开始' }}</strong><p>{{ page === 'enterprise' ? '对话与历史保存在企业问答的私有会话中。' : '整理团队的经验，也为下一位同事留一条清晰的路。' }}</p><span class="nav-context-tag"><AppIcon name="shield" :size="12" />知识有边界 · 协作有秩序</span></div>
      <div class="sidebar-bottom">
        <div class="workspace-privacy-note"><AppIcon name="shield" :size="16" /><span>企业知识有边界<br /><small>请勿在联网体验中输入内部资料</small></span></div>
        <button class="help-link" @click="openDialog('help')"><AppIcon name="info" :size="16" />{{ t('使用指南') }}<span>↗</span></button>
        <div class="profile"><span class="avatar"><AppIcon name="layers" :size="16" /></span><span><a href="#knowledge" @click.prevent="navigate('knowledge')">企业账号与空间</a><small>前往知识空间登录与管理</small></span><button class="icon-button" :aria-label="t('工作空间设置')" @click="openDialog('settings')"><AppIcon name="settings" :size="18" /></button></div>
      </div>
    </aside>

    <main class="main-panel">
      <header class="topbar">
        <div class="breadcrumb"><button class="icon-button mobile-menu" :aria-label="t('打开导航')" @click="sidebarOpen = true"><AppIcon name="menu" /></button><span class="breadcrumb-root">{{ t('工作空间') }}</span><span class="breadcrumb-slash">/</span><strong>{{ t(pageTitles[page]) }}</strong></div>
        <div class="header-actions"><span class="environment"><i :class="{ offline: !online }"></i>{{ t(online ? '本地开发' : '网络离线') }}</span><span class="header-divider"></span><template v-if="page === 'chat' && publicChatEnabled"><button class="icon-button" :title="t('导出当前对话')" :aria-label="t('导出当前对话')" @click="exportConversation"><AppIcon name="download" :size="18" /></button><button class="context-toggle" :class="{ selected: contextOpen }" :aria-expanded="contextOpen" aria-controls="context-panel" @click="contextOpen = !contextOpen"><AppIcon name="panel" :size="17" /><span>{{ t('参考资料') }}</span></button></template><button v-else class="icon-button" :aria-label="t('使用指南')" @click="openDialog('help')"><AppIcon name="info" :size="18" /></button></div>
      </header>
      <Transition name="page" mode="out-in">
        <EnterpriseWorkspace v-if="page !== 'chat'" :key="page" :page="page" :login-return-label="loginReturn ? pageTitles[loginReturn] : ''" @navigate="navigate" @products="openDialog('products')" @login="startLogin" @authenticated="finishLogin" />
      </Transition>
      <!-- 保留问答组件实例，切换工作区不丢弃在途请求；提交入口同时检查当前页面。 -->
      <div v-show="page === 'chat'" class="chat-layout">
        <section class="chat-column" aria-label="问答工作区">
          <div v-if="!publicChatEnabled" class="chat-parked">
            <span class="chat-parked-icon"><AppIcon name="globe" :size="26" /></span>
            <h1>{{ t('公开联网问答体验未开启') }}</h1>
            <p class="chat-parked-lead">{{ t('这个入口会调用外部联网搜索与外部模型，属于开发期验证功能。当前部署没有开放它，是为了避免一个无需登录的公开接口被滥用、消耗外部额度。') }}</p>
            <p class="chat-parked-note">{{ t('需要查企业内部资料，请使用「企业问答」——回答带引用、带版本、带权限校验。') }}</p>
            <div class="chat-parked-actions">
              <button type="button" class="parked-primary" @click="navigate('enterprise')">{{ t('前往企业问答') }}</button>
              <a class="parked-ghost" href="https://www.bing.com" target="_blank" rel="noopener noreferrer">{{ t('打开公开搜索引擎') }}<AppIcon name="external" :size="13" /></a>
            </div>
          </div>
          <template v-else>
          <div v-if="!online" class="offline-banner" role="status"><AppIcon name="info" :size="16" />{{ t('网络已断开，恢复后可继续提问。请求不会自动重发。') }}</div>
          <div ref="feed" class="conversation-scroll" :class="{ 'is-welcome': !hasTurns }">
            <section v-if="!hasTurns" class="welcome">
              <p class="public-scope-notice"><AppIcon name="globe" :size="14" />公开联网问答体验 · 不检索企业知识，请勿输入内部资料</p>
              <div class="welcome-eyebrow"><span></span>YOUR KNOWLEDGE, CONNECTED</div>
              <div class="hero-art" aria-hidden="true">
                <div class="orbit orbit-one"></div><div class="orbit orbit-two"></div>
                <span class="art-satellite satellite-book"><AppIcon name="book" :size="22" /></span>
                <span class="art-satellite satellite-globe"><AppIcon name="globe" :size="19" /></span>
                <span class="art-satellite satellite-check"><AppIcon name="check" :size="16" /></span>
                <div class="art-sheet sheet-back"></div><div class="art-sheet sheet-front"><span class="sheet-mark"><AppIcon name="sparkle" :size="23" /></span><span class="sheet-line long"></span><span class="sheet-line"></span><span class="sheet-line short"></span><span class="sheet-answer"><i></i> 有据可循</span></div>
                <span class="art-dot dot-one"></span><span class="art-dot dot-two"></span><span class="art-cross">+</span>
              </div>
              <h1>{{ t('好问题，值得一个') }}<span>{{ t('有依据的回答。') }}</span></h1>
              <p class="welcome-description">{{ t('从一个想法开始，连接知识与信息。') }}<br class="small-break" />{{ t('把复杂的问题，变成清晰的下一步。') }}</p>
              <div class="welcome-capabilities"><span><AppIcon name="globe" :size="14" />{{ t('联网查证') }}</span><i></i><span><AppIcon name="layers" :size="14" />{{ t('多问题拆解') }}</span><i></i><span><AppIcon name="shield" :size="14" />{{ t('来源可追溯') }}</span></div>
              <div class="suggestions-heading"><span>{{ t('不妨从这里开始') }}</span><button @click="showDemo">{{ t('体验回答示例') }} <AppIcon name="chevron" :size="13" /></button></div>
              <div class="suggestion-grid"><button v-for="suggestion in suggestions" :key="suggestion.type" class="suggestion-card" @click="fillQuestion(suggestion.question)"><span class="suggestion-icon" :class="suggestion.color"><AppIcon :name="suggestion.icon" :size="18" /></span><span class="suggestion-type">{{ t(suggestion.type) }}</span><strong>{{ t(suggestion.title) }}</strong><AppIcon class="suggestion-arrow" name="chevron" :size="15" /></button></div>
              <div class="welcome-footnote"><AppIcon name="info" :size="13" />{{ t('清楚呈现已知，也坦诚说明未知。') }}</div>
            </section>

            <div v-else class="turn-list">
              <div class="conversation-heading"><span>{{ t('一问一答，逐步清晰') }}</span><span>{{ t('{count} 次提问 · 当前页面记录', { count: turns.length }) }}</span></div>
              <article v-for="turn in turns" :key="turn.id" class="turn">
                <div class="user-message"><div class="message-byline"><span class="user-dot">{{ t('我') }}</span><strong>{{ t('你') }}</strong><time>{{ time(turn.createdAt) }}</time></div><div class="user-bubble">{{ turn.question }}</div></div>
                <div class="assistant-message">
                  <div class="message-byline"><span class="assistant-mark"><AppIcon name="sparkle" :size="16" /></span><strong>知序</strong><span class="assistant-tag">{{ t('知识助手') }}</span><span v-if="turn.demo" class="demo-badge">{{ t('界面演示') }}</span><span v-else-if="turn.state === 'done'" class="answer-duration"><AppIcon name="clock" :size="12" />{{ ((turn.answer?.elapsedMillis ?? 0) / 1000).toFixed(1) }}s</span></div>
                  <div v-if="turn.state === 'pending'" class="pending-answer" role="status"><span class="thinking-indicator"><i></i><i></i><i></i></span><strong>{{ t('正在为你整理答案') }}</strong><span>{{ t('已等待 {seconds} 秒', { seconds: Math.max(0, Math.floor((now - turn.createdAt) / 1000)) }) }}</span><p>{{ t('你可以继续补充新的问题。当前每条提问独立处理。') }}</p><button @click="controllers.get(turn.id)?.abort()"><AppIcon name="stop" :size="13" />{{ t('停止等待') }}</button></div>
                  <div v-else-if="turn.state === 'error'" class="error-answer" role="alert"><span class="error-symbol"><AppIcon name="info" :size="20" /></span><div><strong>{{ t('这次暂时没能完成') }}</strong><p>{{ t(turn.error ?? '') }}</p><button class="text-button" @click="fillQuestion(turn.question)">{{ t('把问题放回输入框') }} <AppIcon name="refresh" :size="13" /></button></div></div>
                  <template v-else-if="turn.answer">
                    <div v-if="turn.demo" class="demo-notice"><AppIcon name="info" :size="14" />{{ t('这是预置的界面示例，未调用模型或搜索。新提问会请求真实后端。') }}</div>
                    <div v-if="turn.answer.status !== 'COMPLETED'" class="result-summary" :class="{ 'no-answer': turn.answer.status === 'NO_ANSWER' }"><AppIcon name="info" :size="16" /><span>{{ t(turn.answer.status === 'PARTIAL' ? '已有部分回答，仍有内容需要进一步确认。' : '本次未能形成可靠答案，请查看下方说明。') }}</span></div>
                    <section v-for="(part, index) in turn.answer.answers" :key="part.id" class="answer-part">
                      <div class="part-heading"><span v-if="turn.answer.answers.length > 1" class="part-number">{{ String(index + 1).padStart(2, '0') }}</span><h2>{{ part.question }}</h2><span class="status-label" :class="part.status === 'ANSWERED' ? 'success' : 'warning'">{{ t(labels[part.status]) }}</span></div>
                      <div class="basis-label"><AppIcon :name="part.basis === 'WEB' ? 'globe' : 'sparkle'" :size="12" />{{ t(part.basis === 'WEB' ? '联网资料' : '一般性建议 · 非联网核验') }}</div>
                      <AnswerText v-if="part.answer" :text="part.answer" :sources="part.sources" @citation="id => showSources(turn, id)" />
                      <div v-if="part.message" class="part-note" :class="{ 'evidence-gap': part.status !== 'ANSWERED' }"><AppIcon name="info" :size="15" /><p>{{ part.message }}</p></div>
                      <div v-if="part.sources.length" class="inline-sources"><button v-for="(source, sourceIndex) in part.sources.slice(0, 3)" :key="source.id" @click="showSources(turn, source.id)"><span>{{ sourceIndex + 1 }}</span>{{ hostname(source.url) }}<AppIcon name="external" :size="11" /></button><button v-if="part.sources.length > 3" @click="showSources(turn)">+{{ part.sources.length - 3 }}</button></div>
                    </section>
                    <div class="answer-footer"><span><AppIcon name="shield" :size="13" />{{ t(turn.demo ? '示例内容，仅用于体验界面' : '请结合来源核对重要信息') }}</span><div><button :aria-label="t('复制回答')" :title="t('复制回答')" @click="copyAnswer(turn)"><AppIcon name="copy" :size="15" /></button><button @click="showSources(turn)"><AppIcon name="panel" :size="14" />{{ t('来源与详情') }}</button></div></div>
                  </template>
                </div>
              </article>
            </div>
          </div>

          <div class="composer-area">
            <div v-if="pendingCount" class="concurrency-note"><i class="busy-dot"></i>{{ t(pendingCount === 1 ? '有 1 个请求处理中，你仍可发送新的问题。' : '已有 2 个请求处理中，请稍候；你可以先编辑问题。') }}</div>
            <form class="composer" :class="{ 'is-invalid': questionLength > 500 }" @submit.prevent="submit">
              <label class="sr-only" for="question">{{ t('发送问题') }}</label><textarea id="question" ref="input" v-model="draft" rows="2" :placeholder="t('有什么想了解的？复杂的问题，也可以一次问。')" @input="resizeInput" @keydown="onKeydown"></textarea>
              <div class="composer-toolbar"><div class="composer-modes"><span class="active-mode"><AppIcon name="globe" :size="14" />公开联网体验</span><button type="button" class="knowledge-mode" title="需要企业账号登录，回答只使用你获授权且已发布的资料" @click="navigate('enterprise')"><AppIcon name="book" :size="14" /><span>{{ t('企业知识问答') }}</span><span class="mode-soon">{{ t('需登录') }}</span></button></div><div class="send-area"><span class="char-count" :class="{ invalid: questionLength > 500 }">{{ questionLength }}<span> / 500</span></span><button type="submit" class="send-button" :disabled="!canSend" :aria-label="t('发送问题')"><AppIcon name="arrow" :size="19" /></button></div></div>
            </form>
            <div class="composer-hint"><span v-if="questionLength > 500" class="invalid" role="alert">{{ t('问题最多 500 个字符，请精简后发送。') }}</span><span v-else>Enter ↗<span class="hint-separator">·</span>Shift + Enter ↵</span><span>请勿输入内部资料 · 刷新后会话清空</span></div>
          </div>
          </template>
        </section>

        <button v-if="publicChatEnabled && contextOpen" class="context-scrim" :aria-label="t('关闭参考资料')" @click="contextOpen = false"></button>
        <aside v-if="publicChatEnabled && contextOpen" id="context-panel" class="context-panel" aria-label="参考资料与回答详情">
          <div class="context-heading"><div><AppIcon name="book" :size="17" /><strong>{{ t('参考资料') }}</strong><span>{{ sources.length }}</span></div><button class="icon-button" :aria-label="t('关闭参考资料')" @click="contextOpen = false"><AppIcon name="close" :size="16" /></button></div>
          <div class="context-content">
            <template v-if="selectedTurn?.answer">
              <p v-if="selectedTurn.demo" class="context-demo-label">{{ t('界面演示 · 非实际检索') }}</p>
              <p class="context-question">{{ selectedTurn.question }}</p>
              <div class="source-tabs"><span class="active">{{ t('来源') }} <b>{{ sources.length }}</b></span><a href="#request-details" @click.prevent="showRequestDetails">{{ t('回答详情') }}</a></div>
              <p v-if="sources.length" class="context-demo-label">{{ t('检索资料不一定适用于本题，引用事实已另行校验。') }}</p>
              <div v-if="sources.length" class="source-list"><article v-for="(source, index) in sources" :id="`source-${source.id}`" :key="`${source.question}-${source.id}`" class="source-card" :class="{ highlighted: selectedSourceId === source.id }"><div class="source-meta"><span class="source-number">{{ index + 1 }}</span><span>{{ hostname(source.url) }}</span><AppIcon name="globe" :size="12" /></div><a v-if="safeUrl(source.url)" :href="safeUrl(source.url)" target="_blank" rel="noopener noreferrer" referrerpolicy="no-referrer">{{ source.title }}<AppIcon name="external" :size="13" /></a><strong v-else>{{ source.title }}</strong><p>{{ source.excerpt || t('此来源未提供摘要。') }}</p><small>{{ t('关联问题') }} · {{ source.question }}</small></article></div>
              <div v-else class="sources-empty"><AppIcon name="search" :size="28" /><strong>{{ t('这条回答暂无参考来源') }}</strong><p>{{ t('一般性建议不等于联网查证；未找到或不足的资料也会在回答中说明。') }}</p></div>
              <section id="request-details" class="request-details"><h3>{{ t('回答详情') }}</h3><dl><div><dt>{{ t('回答情况') }}</dt><dd>{{ t(selectedTurn.answer.status === 'COMPLETED' ? '全部完成' : selectedTurn.answer.status === 'PARTIAL' ? '部分完成' : '暂无可靠答案') }}</dd></div><div><dt>{{ t('子问题') }}</dt><dd>{{ selectedTurn.answer.answers.length }}</dd></div><div><dt>{{ t('完整回答') }}</dt><dd>{{ answeredCount }}</dd></div><div><dt>{{ t('处理耗时') }}</dt><dd>{{ selectedTurn.demo ? t('演示不计时') : `${(selectedTurn.answer.elapsedMillis / 1000).toFixed(1)}s` }}</dd></div></dl><span class="request-id-label">REQUEST ID</span><code>{{ selectedTurn.answer.requestId || t('未提供') }}</code><p>{{ selectedTurn.answer.message }}</p></section>
            </template>
            <template v-else><div class="context-empty-art"><AppIcon name="file" :size="34" /><span><AppIcon name="check" :size="12" /></span></div><h3 class="context-empty-title">{{ t('答案背后的依据，也值得被看见。') }}</h3><p class="context-empty-description">{{ t('提问后，在这里查看参考来源、资料摘要和本次回答的完成情况。') }}</p><div class="context-feature"><span>01</span><div><strong>{{ t('信息有出处') }}</strong><p>{{ t('打开原文，核对关键结论。') }}</p></div></div><div class="context-feature"><span>02</span><div><strong>{{ t('不确定，会告诉你') }}</strong><p>{{ t('资料不足时，明确说明边界。') }}</p></div></div><div class="context-tip"><AppIcon name="shield" :size="17" /><p>{{ t('不会用看似完整的回答，掩盖缺少依据的部分。') }}</p></div></template>
          </div>
          <div class="context-bottom"><AppIcon name="shield" :size="13" />{{ t('少一点猜测，多一点依据') }}</div>
        </aside>
      </div>
    </main>

    <Transition name="toast"><div v-if="toast" class="toast-notification" role="status"><AppIcon name="info" :size="17" />{{ t(toast) }}<button :aria-label="t('关闭通知')" @click="toast = ''"><AppIcon name="close" :size="14" /></button></div></Transition>
    <dialog ref="dialog" class="workspace-dialog" @click="event => { if (event.target === dialog) dialog?.close(); }">
      <div class="dialog-heading"><span class="dialog-icon"><AppIcon :name="dialogKind === 'knowledge' ? 'book' : dialogKind === 'help' ? 'info' : dialogKind === 'products' ? 'briefcase' : 'settings'" :size="23" /></span><button class="icon-button" :aria-label="t('关闭窗口')" @click="dialog?.close()"><AppIcon name="close" /></button></div>
      <template v-if="dialogKind === 'knowledge'"><span class="dialog-eyebrow">ENTERPRISE KNOWLEDGE</span><h2>企业知识，需要先核验授权。</h2><p>请从企业问答进入授权空间检索。公开联网体验独立运行，不读取企业资料；请勿在此提交内部信息。</p><div class="dialog-note"><AppIcon name="info" :size="18" />企业模型外发默认关闭，仅对获准的虚构测试空间开放。</div></template>
      <PreferenceSettings v-else-if="dialogKind === 'settings'" :preferences="preferences" @change="value => preferences = value" />
      <ProductSearch v-else-if="dialogKind === 'products'" />
      <template v-else-if="page !== 'chat'">
        <span class="dialog-eyebrow">KNOWINGORDER GUIDE</span><h2>读懂公司知识，帮你把工作做好。</h2>
        <ol class="help-list">
          <li><strong>企业知识是主线</strong><p>企业账号、文字入库与授权关键词检索已接入。默认仅展示本地原文；模型只对获准的虚构测试空间开放，真实企业资料外发仍关闭。</p></li>
          <li><strong>员工事务是辅助</strong><p>可体验模拟商品查询，跳转只打开淘宝首页。补贴、参与确认与财务结算仍待开发，没有实际订单或金额记录。</p></li>
          <li><strong>公开体验单独使用</strong><p>联网问答仍可用于公开问题，请勿输入企业内部资料。页面会话不持久保存，刷新后清空；重要结果请导出。</p></li>
        </ol>
      </template>
      <template v-else><span class="dialog-eyebrow">A LITTLE GUIDE</span><h2>{{ t('让每一次提问，更有收获。') }}</h2><ol class="help-list"><li><strong>{{ t('直接提问，不必先选优先级') }}</strong><p>{{ t('一句话可以包含多个问题，结果会分项呈现。当前最多拆分三个子问题。') }}</p></li><li><strong>{{ t('有补充，可以继续发送') }}</strong><p>{{ t('输入框不会因一个请求锁住。每条提问独立处理，目前不会自动合并或携带历史上下文。') }}</p></li><li><strong>{{ t('查看依据，也留意边界') }}</strong><p>{{ t('点击引用可查看来源。资料不足、超时和服务异常会明确提示；页面不自动重试。') }}</p></li><li><strong>{{ t('重要内容，记得及时导出') }}</strong><p>{{ t('会话仅保留在当前页面。刷新、关闭页面会丢失记录；目前没有后台任务恢复接口。') }}</p></li></ol></template>
      <button class="dialog-primary" @click="dialog?.close()">{{ t('知道了') }}<AppIcon name="check" :size="16" /></button>
    </dialog>
  </div>
</template>

<style scoped>
/* ---------- 侧边栏：业务外壳下的局部调整，其余继承全局令牌 ---------- */
.enterprise-shell > .sidebar { padding-top: var(--sp-6); background: var(--n-25); }
.enterprise-mode > .sidebar { background: var(--n-25); border-right-color: var(--border-subtle); }
.enterprise-shell .brand { margin-bottom: var(--sp-5); }
.enterprise-shell .brand small { font-size: var(--fs-2xs); letter-spacing: 1.6px; }

.enterprise-space-label {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin-bottom: var(--sp-4);
  padding: var(--sp-3) var(--sp-2);
  border-top: 1px solid var(--border-subtle);
  border-bottom: 1px solid var(--border-subtle);
}
.enterprise-space-avatar {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  background: var(--brand-50);
  color: var(--brand-600);
  border: 1px solid var(--brand-100);
  border-radius: var(--r-sm);
  flex-shrink: 0;
}
.enterprise-space-label strong {
  display: block;
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  color: var(--text-secondary);
}
.enterprise-space-label small {
  display: block;
  font-size: var(--fs-2xs);
  margin-top: 2px;
  color: var(--text-placeholder);
}
.nav-end-icon { margin-left: auto; opacity: .6; }

.enterprise-mode .primary-nav { margin-top: var(--sp-3); gap: 2px; padding-bottom: var(--sp-4); }
.enterprise-mode .nav-item {
  padding: 11px var(--sp-3);
  margin: 1px 0;
  font-size: var(--fs-sm);
  border-radius: var(--r-sm);
}
.enterprise-mode .nav-item.active {
  color: var(--brand-700);
  background: var(--brand-50);
  box-shadow: none;
  font-weight: var(--fw-semibold);
}
.enterprise-mode .nav-group-label { margin-top: var(--sp-1); }

.nav-group-label {
  font-size: var(--fs-2xs);
  font-weight: var(--fw-medium);
  color: var(--text-placeholder);
  padding: var(--sp-3) var(--sp-3) 6px;
  letter-spacing: .8px;
}

.public-new-chat {
  font-size: var(--fs-xs);
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 8px var(--sp-3) 8px 34px;
  color: var(--brand-600);
  border-radius: var(--r-sm);
  transition: background var(--dur-1), color var(--dur-1);
}
.public-new-chat:hover { color: var(--brand-700); background: var(--brand-50); }

/* 公开联网问答未开启时的说明态：入口保留，但不让用户点进一个会报 404 的页面。 */
.chat-parked {
  max-width: 560px;
  margin: auto;
  padding: var(--sp-8) var(--sp-6);
}
.chat-parked-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 52px;
  height: 52px;
  border-radius: var(--r-md);
  background: var(--brand-50);
  color: var(--brand-600);
}
.chat-parked h1 {
  margin: var(--sp-5) 0 var(--sp-3);
  font-size: var(--fs-xl);
  font-weight: var(--fw-semibold);
  color: var(--text-primary);
  letter-spacing: -.2px;
}
.chat-parked-lead,
.chat-parked-note {
  margin: 0 0 var(--sp-3);
  font-size: var(--fs-sm);
  line-height: var(--lh-relaxed);
  color: var(--text-secondary);
}
.chat-parked-note { margin-bottom: var(--sp-6); color: var(--text-tertiary); }
.chat-parked-actions { display: flex; flex-wrap: wrap; align-items: center; gap: var(--sp-3); }
.parked-primary {
  height: 40px;
  padding: 0 var(--sp-5);
  border-radius: var(--r-sm);
  background: var(--brand-600);
  color: var(--text-inverse);
  font-size: var(--fs-sm);
  font-weight: var(--fw-medium);
  transition: background var(--dur-1);
}
.parked-primary:hover { background: var(--brand-700); }
.parked-ghost {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 40px;
  padding: 0 var(--sp-4);
  border: 1px solid var(--border-default);
  border-radius: var(--r-sm);
  font-size: var(--fs-sm);
  color: var(--text-secondary);
  transition: border-color var(--dur-1), color var(--dur-1);
}
.parked-ghost:hover { border-color: var(--brand-200); color: var(--brand-700); }

.enterprise-shell .history-heading { margin-top: var(--sp-4); }
.enterprise-shell .history-empty { padding: var(--sp-6) 0; }
.enterprise-shell .history-empty p { font-size: var(--fs-xs); }

/* ---------- 导航下方说明区：收敛装饰，突出可读性 ---------- */
.nav-context {
  flex: 1;
  min-height: 72px;
  padding: var(--sp-7) var(--sp-2) var(--sp-4);
  overflow: auto;
  display: flex;
  flex-direction: column;
}
.nav-context-art {
  position: relative;
  width: 76px;
  height: 68px;
  margin-bottom: var(--sp-4);
  flex-shrink: 0;
}
.nav-context-art > span {
  display: grid;
  place-items: center;
  position: absolute;
  left: 12px;
  top: 8px;
  width: 46px;
  height: 48px;
  color: var(--brand-500);
  border: 1px solid var(--brand-100);
  border-radius: var(--r-md);
  background: var(--brand-50);
  transform: rotate(-8deg);
  box-shadow: var(--sh-xs);
}
.nav-context-art::before {
  content: '';
  position: absolute;
  inset: 0;
  border: 1px dashed var(--brand-200);
  border-radius: 50%;
  transform: rotate(20deg);
  opacity: .7;
}
.nav-context-art i {
  position: absolute;
  top: 2px;
  right: 6px;
  width: 6px;
  height: 6px;
  background: var(--brand-300);
  border-radius: var(--r-full);
}
.nav-context-art i:last-child {
  top: auto;
  bottom: 4px;
  left: 2px;
  width: 4px;
  height: 4px;
  background: var(--n-300);
}
.nav-context > strong {
  font-weight: var(--fw-semibold);
  font-size: var(--fs-sm);
  color: var(--text-secondary);
  line-height: var(--lh-snug);
}
.nav-context > p {
  max-width: 190px;
  font-size: var(--fs-xs);
  line-height: var(--lh-base);
  color: var(--text-tertiary);
  margin: var(--sp-2) 0 var(--sp-4);
}
.nav-context-tag {
  display: inline-flex;
  gap: 5px;
  align-items: center;
  color: var(--text-placeholder);
  font-size: var(--fs-2xs);
  margin-top: auto;
}

.workspace-privacy-note {
  display: flex;
  align-items: flex-start;
  gap: var(--sp-2);
  color: var(--text-tertiary);
  padding: var(--sp-3) var(--sp-2);
  line-height: var(--lh-base);
  font-size: var(--fs-2xs);
}
.workspace-privacy-note small { font-size: var(--fs-2xs); color: var(--text-placeholder); }
.enterprise-shell .help-link { padding: var(--sp-3) var(--sp-2); }
.enterprise-shell .profile { padding-top: var(--sp-3); padding-bottom: var(--sp-4); }

.public-scope-notice {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: 1px solid var(--warning-border);
  background: var(--warning-soft);
  padding: 9px var(--sp-3);
  border-radius: var(--r-sm);
  font-size: var(--fs-xs);
  line-height: var(--lh-base);
  color: var(--amber-600);
}

/* ---------- 低矮视口：优先保证导航与输入区可见 ---------- */
@media (max-height: 860px) {
  .enterprise-shell > .sidebar { padding-top: var(--sp-5); }
  .enterprise-shell .brand { margin-bottom: var(--sp-3); }
  .enterprise-space-label { margin-bottom: var(--sp-2); padding: 9px var(--sp-1); }
  .enterprise-mode .primary-nav { margin-top: var(--sp-2); gap: 0; padding-bottom: var(--sp-2); }
  .enterprise-mode .nav-item { padding: 9px var(--sp-3); }
  .enterprise-shell .history-heading { margin-top: var(--sp-3); margin-bottom: var(--sp-2); }
  .enterprise-shell .history-empty { padding: 5px 0; }
  .enterprise-shell .history-empty small { display: none; }
  .workspace-privacy-note { display: none; }
  .nav-context { padding-top: var(--sp-5); }
  .nav-context-art { display: none; }
  .nav-context > p { font-size: var(--fs-2xs); }
}

@media (max-height: 680px) {
  .enterprise-shell > .sidebar { overflow-y: auto; }
  .enterprise-shell .history-list { min-height: 35px; }
  .nav-context { display: none; }
}
</style>
