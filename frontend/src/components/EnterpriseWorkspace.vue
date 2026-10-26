<script setup lang="ts">
import { computed, onActivated, ref, watch } from 'vue';
import AppIcon from './AppIcon.vue';
import KnowledgeSpacePanel from './KnowledgeSpacePanel.vue';
import KnowledgeAnswerPanel from './KnowledgeAnswerPanel.vue';
import AffairsPanel from './AffairsPanel.vue';
import WorkbenchOverview from './WorkbenchOverview.vue';
import { t } from '../i18n';
import { useSession } from '../session';

const props = defineProps<{ page: 'home' | 'enterprise' | 'knowledge' | 'affairs'; loginReturnLabel?: string }>();
const { user } = useSession();
const viewport = ref<HTMLElement>();
// 普通页面从顶部进入；问答由自己的对话流管理阅读位置。
function resetPageScroll() {
  if (props.page !== 'enterprise') viewport.value?.scrollTo({ top: 0, behavior: 'instant' });
}
watch(() => props.page, resetPageScroll, { flush: 'post' });
onActivated(resetPageScroll);
const emit = defineEmits<{
  navigate: [page: 'home' | 'enterprise' | 'knowledge' | 'affairs' | 'chat'];
  products: [];
  /** 请求登录：由父级记住来源页，再跳到知识空间。 */
  login: [];
  /** 知识空间登录成功：父级据此把用户送回来源页。 */
  authenticated: [];
}>();
// 这里只展示已确定的信息架构，不生成企业文档、员工身份或业务记录。
const spaces = computed(() => [
  { icon: 'chat', name: t('向团队知识提问'), description: t('查制度、理流程，让复杂的问题有据可循'), tag: t('开始对话'), tone: 'sage', page: 'enterprise' as const },
  { icon: 'book', name: t('整理与更新资料'), description: t('上传原件、核对版本，维护团队的知识资产'), tag: t('管理资料'), tone: 'sand', page: 'knowledge' as const },
  { icon: 'layers', name: t('循着引用找依据'), description: t('从回答回到原文，核对结论、版本和适用条件'), tag: t('查看出处'), tone: 'blue', page: 'enterprise' as const }
]);
</script>

<template>
  <div ref="viewport" class="enterprise-workspace" :data-page="page">
    <div v-if="page === 'home' || page === 'affairs'" class="workspace-page-heading">
      <div><span class="section-kicker">KNOWINGORDER / WORKSPACE</span><h1>{{ page === 'home' ? t('把知识，接到工作里。') : t('员工事务') }}</h1></div>
      <span class="workspace-stage"><AppIcon name="shield" :size="14" />{{ t('企业知识 · 按权限可见') }}</span>
    </div>

    <template v-if="page === 'home'">
      <section class="work-hero">
        <div class="work-hero-copy"><span class="hero-label"><AppIcon name="sparkle" :size="15" />{{ t('你的企业知识与工作助手') }}</span><h2>{{ t('读懂公司知识，') }}<br />{{ t('帮你把工作做好') }}<span>{{ t('。') }}</span></h2><p>{{ t('从了解一项制度，到理清一个业务流程。') }}<br />{{ t('让散落的知识，成为手边有依据的下一步。') }}</p><div class="work-hero-actions"><button class="work-primary" @click="emit('navigate', 'enterprise')">{{ t('进入企业问答') }}<AppIcon name="chevron" :size="16" /></button><button class="work-secondary" @click="emit('navigate', 'knowledge')">{{ t('浏览知识空间') }}<AppIcon name="external" :size="14" /></button></div><small>{{ t('仅检索账号获授权的已发布资料；企业原文默认不发送外部模型。') }}</small></div>
        <div class="knowledge-illustration" aria-hidden="true"><div class="knowledge-ring"></div><span class="illustration-label">FROM KNOWLEDGE TO ACTION</span><div class="knowledge-stack stack-back"></div><div class="knowledge-stack stack-front"><span class="document-symbol"><AppIcon name="book" :size="28" /></span><strong>{{ t('一份知识，有序可循') }}</strong><span class="document-line"></span><span class="document-line short"></span><div class="document-tags"><span>{{ t('来源') }}</span><span>{{ t('版本') }}</span><span>{{ t('适用范围') }}</span></div></div><div class="illustration-answer"><span><AppIcon name="sparkle" :size="17" />{{ t('知序') }}</span><strong>{{ t('找到依据，再给出下一步。') }}</strong><i></i><i class="short"></i></div><span class="illustration-dot"></span></div>
      </section>

      <WorkbenchOverview @navigate="emit('navigate', $event)" @login="emit('login')" />

      <div class="work-dashboard-grid">
        <section class="work-section"><div class="work-section-heading"><div><span class="section-kicker">MAKE WORK FLOW</span><h2>{{ t('你的工作捷径') }}</h2></div><button @click="emit('navigate', 'knowledge')">{{ t('打开知识空间') }}<AppIcon name="chevron" :size="14" /></button></div><div class="knowledge-space-grid"><button v-for="space in spaces" :key="space.name" class="knowledge-space-card" @click="emit('navigate', space.page)"><span class="space-icon" :class="space.tone"><AppIcon :name="space.icon" :size="21" /></span><AppIcon class="space-template" name="chevron" :size="14" /><h3>{{ space.name }}</h3><p>{{ space.description }}</p><div><span>{{ space.tag }}</span><AppIcon name="chevron" :size="14" /></div></button></div><p class="work-caption">{{ t('资料范围与可用操作，以当前账号的实际权限为准。') }}</p></section>
        <aside class="work-status-card"><span class="section-kicker">A LITTLE GUIDE</span><h2>{{ t('一份资料，如何成为答案？') }}</h2><ol class="work-guide"><li><span>01</span><div><strong>{{ t('放进知识空间') }}</strong><p>{{ t('把制度、指南和经验有序归档。') }}</p></div></li><li><span>02</span><div><strong>{{ t('核对，再发布') }}</strong><p>{{ t('检查内容与权限，让有效版本可用。') }}</p></div></li><li><span>03</span><div><strong>{{ t('提问，也看出处') }}</strong><p>{{ t('带着问题来，循着引用找依据。') }}</p></div></li></ol><div class="status-explanation"><AppIcon name="shield" :size="17" /><p>{{ t('只使用你获授权的有效资料，资料不足时明确告诉你。') }}</p></div></aside>
      </div>
      <section class="work-shortcuts"><button @click="emit('navigate', 'affairs')"><span class="shortcut-icon"><AppIcon name="briefcase" :size="22" /></span><span><strong>{{ t('工作之外的小事，也有序安排') }}</strong><small>{{ t('商品查询演示 · 平台跳转 · 补贴协同规划') }}</small></span><AppIcon name="chevron" :size="17" /></button><button @click="emit('navigate', 'chat')"><span class="shortcut-icon"><AppIcon name="globe" :size="22" /></span><span><strong>{{ t('先体验一次有依据的回答') }}</strong><small>{{ t('独立的公开联网问答，不检索企业知识') }}</small></span><AppIcon name="chevron" :size="17" /></button></section>
    </template>

    <!-- 只缓存问答；身份变化立即销毁旧缓存与在途请求，不保留其他账号的正文。 -->
    <KeepAlive :key="user?.userId ?? 'anonymous'">
      <KnowledgeAnswerPanel v-if="page === 'enterprise'" @manage="emit('navigate', 'knowledge')" @login="emit('login')" @general="emit('navigate', 'chat')" />
    </KeepAlive>

    <template v-if="page === 'knowledge'">
      <KnowledgeSpacePanel :return-label="loginReturnLabel ?? ''" @authenticated="emit('authenticated')" />
    </template>

    <template v-else-if="page === 'affairs'">
      <section class="affairs-banner"><div><span class="section-kicker">LESS FRICTION, MORE FOCUS</span><h2>{{ t('少一点来回，多一点便利。') }}</h2><p>{{ t('把商品查询、凭证留存与补贴协同放在一起，让工作事务有迹可循。') }}</p></div><AppIcon name="briefcase" :size="65" /></section><div class="affairs-grid affairs-grid-two"><article class="affair-card"><span class="space-icon sand"><AppIcon name="briefcase" :size="22" /></span><span class="planned-pill demo-pill">{{ t('Mock · 可体验') }}</span><h2>{{ t('找商品，前往平台') }}</h2><p>{{ t('查询咖啡、奶茶和晚餐的模拟商品。跳转会打开淘宝搜索页并填入商品名，由你在平台查询、下单与支付。') }}</p><button class="work-primary" @click="emit('products')">{{ t('商品查询') }}<AppIcon name="external" :size="15" /></button></article><article class="affair-card"><span class="space-icon sage"><AppIcon name="file" :size="22" /></span><span class="planned-pill demo-pill">{{ t('已接入') }}</span><h2>{{ t('凭证与补贴，参与与结算') }}</h2><p>{{ t('保存原始票据、多人确认分摊、主管批准与财务结算均已接入；不做 OCR，不把付款当作完成。') }}</p><small class="affair-note">{{ t('在下方直接办理；权限、版本与额度由服务端校验。') }}</small></article></div><div class="affairs-panel-slot"><AffairsPanel /></div><div class="affairs-boundary"><AppIcon name="info" :size="19" /><p>{{ t('补贴申请、参与确认与财务结算已接入；模拟商品查询仍仅演示，不创建订单、不代支付、不确认实际成交。凭证无法识别票据内容，需人工核对。') }}</p></div>
    </template>
    <footer class="work-page-footer"><span>{{ t('知序 · 企业知识与工作助手') }}</span><span>{{ t('有依据地回答，有边界地行动。') }}</span></footer>
  </div>
</template>

<style scoped>
.enterprise-workspace {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: var(--sp-7) clamp(20px, 2.8vw, 56px) 0;
  background: var(--bg-app);
  font-family: var(--font-sans);
}

/*
 * 企业问答是对话式界面：整页不滚动，改由答案流内部滚动，
 * 输入框固定在底部——否则每问一句都要先把页面滚到最底下。
 */
.enterprise-workspace[data-page="enterprise"] {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  padding: var(--sp-4) clamp(20px, 2.8vw, 48px) var(--sp-3);
}
@media (max-width: 980px) {
  .enterprise-workspace[data-page="enterprise"] { display: block; overflow-y: auto; padding-bottom: var(--sp-4); }
}

/* ---------------- 页面标题 ---------------- */
.workspace-page-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-4);
  max-width: 1280px;
  margin: 0 auto var(--sp-6);
}
.section-kicker {
  color: var(--text-placeholder);
  font-size: var(--fs-2xs);
  font-weight: var(--fw-semibold);
  letter-spacing: 1.7px;
}
.workspace-page-heading h1 {
  margin: var(--sp-2) 0 0;
  color: var(--text-primary);
  font-size: var(--fs-3xl);
  letter-spacing: -.8px;
  font-weight: var(--fw-semibold);
  line-height: 1.3;
}
.workspace-stage {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-xs);
  color: var(--text-tertiary);
}

/* ---------------- 深色焦点块：全站统一的强调语言 ---------------- */
.work-hero {
  position: relative;
  overflow: hidden;
  display: grid;
  grid-template-columns: 1.06fr 1fr;
  align-items: center;
  max-width: 1280px;
  margin: auto;
  padding: clamp(34px, 3.4vw, 54px) clamp(26px, 3vw, 48px);
  border-radius: var(--r-2xl);
  background: var(--focus-block);
  box-shadow: var(--sh-xl);
  isolation: isolate;
}
.work-hero::before {
  content: '';
  position: absolute;
  inset: 0;
  background: var(--focus-block-glow);
  z-index: -1;
}
.work-hero::after {
  content: '';
  position: absolute;
  inset: 0;
  background: radial-gradient(ellipse 60% 90% at 12% 108%, rgba(47, 127, 98, .42), transparent 62%);
  z-index: -1;
}
.work-hero-copy { position: relative; z-index: 1; }

.hero-label {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: rgba(255, 255, 255, .92);
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
  padding: 6px 13px;
  border: 1px solid rgba(255, 255, 255, .16);
  border-radius: var(--r-full);
  background: rgba(255, 255, 255, .08);
  backdrop-filter: blur(8px);
}
.work-hero h2 {
  font-size: clamp(28px, 2.9vw, 42px);
  font-weight: var(--fw-semibold);
  letter-spacing: -.6px;
  line-height: 1.36;
  margin: var(--sp-5) 0 var(--sp-3);
  color: #fff;
}
.work-hero h2 span { color: #8ad3b1; }
.work-hero p {
  font-size: var(--fs-md);
  color: var(--focus-block-muted);
  line-height: 1.95;
  margin: 0;
}
.work-hero-actions {
  display: flex;
  align-items: center;
  gap: var(--sp-4);
  margin-top: var(--sp-6);
  flex-wrap: wrap;
}
.work-primary,
.work-secondary {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--sp-2);
  padding: 12px 20px;
  font-size: var(--fs-sm);
  font-weight: var(--fw-medium);
  border-radius: var(--r-sm);
  line-height: 1.5;
  transition: background var(--dur-2) var(--ease-out),
              color var(--dur-2) var(--ease-out),
              transform var(--dur-2) var(--ease-out),
              box-shadow var(--dur-2) var(--ease-out);
}
.work-hero .work-primary {
  background: #fff;
  color: var(--brand-800);
  box-shadow: 0 4px 16px rgba(0, 0, 0, .18);
}
.work-hero .work-primary:hover { background: #f2faf5; transform: translateY(-2px); box-shadow: 0 8px 24px rgba(0, 0, 0, .24); }
.work-hero .work-primary:active { transform: translateY(0); }
.work-hero .work-secondary { color: rgba(255, 255, 255, .88); padding-left: 4px; padding-right: 4px; }
.work-hero .work-secondary:hover { color: #fff; gap: 11px; }
.work-hero-copy > small {
  display: block;
  color: rgba(226, 240, 232, .58);
  font-size: var(--fs-xs);
  margin-top: var(--sp-5);
  line-height: var(--lh-base);
}

/* 深色块上的插画：白色卡片形成强对比 */
.knowledge-illustration {
  position: relative;
  align-self: center;
  justify-self: center;
  width: 336px;
  height: 300px;
}
.knowledge-ring {
  position: absolute;
  inset: 0 10px 0 24px;
  border: 1px solid rgba(255, 255, 255, .13);
  border-radius: 50%;
}
.knowledge-ring::after {
  content: '';
  position: absolute;
  inset: 24px -32px;
  border-radius: 50%;
  border: 1px dashed rgba(255, 255, 255, .11);
  transform: rotate(-28deg);
}
.illustration-label {
  position: absolute;
  top: 4px;
  right: 4px;
  font-size: var(--fs-2xs);
  color: rgba(255, 255, 255, .34);
  letter-spacing: 1.5px;
  transform: rotate(9deg);
}
.knowledge-stack { position: absolute; width: 206px; height: 220px; border-radius: var(--r-md); }
.stack-back {
  left: 68px;
  top: 28px;
  background: rgba(255, 255, 255, .07);
  border: 1px solid rgba(255, 255, 255, .12);
  transform: rotate(9deg);
}
.stack-front {
  padding: var(--sp-5);
  left: 48px;
  top: 32px;
  background: #fff;
  border: 1px solid rgba(255, 255, 255, .8);
  transform: rotate(-6deg);
  box-shadow: 0 26px 50px rgba(0, 0, 0, .32);
}
.document-symbol {
  display: grid;
  place-items: center;
  width: 46px;
  height: 46px;
  background: var(--brand-50);
  color: var(--brand-600);
  border-radius: var(--r-md);
}
.stack-front > strong {
  display: block;
  color: var(--brand-800);
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  margin: var(--sp-4) 0;
}
.document-line { display: block; width: 100%; height: 4px; border-radius: var(--r-full); background: var(--brand-100); margin: 10px 0; }
.document-line.short { width: 65%; }
.document-tags { display: flex; gap: 5px; margin-top: var(--sp-4); }
.document-tags span {
  padding: 4px 7px;
  background: var(--n-100);
  color: var(--text-tertiary);
  font-size: var(--fs-2xs);
  border-radius: var(--r-xs);
}
.illustration-answer {
  position: absolute;
  right: 0;
  bottom: 6px;
  width: 226px;
  padding: var(--sp-4);
  background: #fff;
  border: 1px solid rgba(255, 255, 255, .5);
  border-radius: var(--r-md);
  transform: rotate(3deg);
  box-shadow: 0 20px 40px rgba(0, 0, 0, .28);
}
.illustration-answer > span {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: var(--fs-2xs);
  color: var(--brand-600);
  font-weight: var(--fw-medium);
}
.illustration-answer strong {
  display: block;
  color: var(--text-primary);
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  margin: var(--sp-2) 0;
  line-height: var(--lh-snug);
}
.illustration-answer i { display: block; height: 3px; background: var(--n-150); border-radius: var(--r-full); margin-top: 7px; }
.illustration-answer i.short { width: 60%; }
.illustration-dot {
  position: absolute;
  width: 13px;
  height: 13px;
  background: #8ad3b1;
  border: 3px solid rgba(11, 39, 29, .9);
  border-radius: var(--r-full);
  top: 50px;
  left: 8px;
  box-shadow: 0 0 20px rgba(138, 211, 177, .6);
}

/* ---------------- 工作捷径 ---------------- */
.work-dashboard-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: var(--sp-6);
  max-width: 1280px;
  margin: var(--sp-8) auto 0;
}
.work-section { min-width: 0; }
.work-section-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--sp-4);
  margin-bottom: var(--sp-4);
}
.work-section-heading h2,
.work-status-card h2 {
  font-size: var(--fs-xl);
  font-weight: var(--fw-semibold);
  color: var(--text-primary);
  margin: 6px 0 0;
  line-height: var(--lh-snug);
  letter-spacing: -.3px;
}
.work-section-heading > button {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: var(--fs-xs);
  color: var(--brand-600);
  flex-shrink: 0;
  padding: 5px 0 5px 8px;
  transition: gap var(--dur-2) var(--ease-out);
}
.work-section-heading > button:hover { gap: 9px; color: var(--brand-700); }

.knowledge-space-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--sp-4);
  max-width: 1280px;
  margin: auto;
}
.knowledge-space-card {
  position: relative;
  display: flex;
  flex-direction: column;
  min-width: 0;
  border: 1px solid var(--border-subtle);
  border-radius: var(--r-lg);
  background: var(--n-0);
  padding: var(--sp-5);
  text-align: left;
  box-shadow: var(--sh-xs);
}
button.knowledge-space-card {
  transition: transform var(--dur-2) var(--ease-out),
              box-shadow var(--dur-2) var(--ease-out),
              border-color var(--dur-2) var(--ease-out);
}
button.knowledge-space-card:hover {
  border-color: var(--brand-200);
  transform: translateY(-3px);
  box-shadow: var(--sh-lg);
}
button.knowledge-space-card:hover .space-template { color: var(--brand-500); transform: translateX(3px); }
.space-icon {
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  border-radius: var(--r-md);
}
.space-icon.sage { color: var(--brand-600); background: var(--brand-50); }
.space-icon.sand { color: var(--amber-600); background: var(--amber-soft); }
.space-icon.blue { color: var(--blue-600); background: var(--blue-soft); }
.space-template {
  position: absolute;
  right: var(--sp-4);
  top: var(--sp-5);
  color: var(--n-300);
  transition: transform var(--dur-2) var(--ease-out), color var(--dur-2);
}
.knowledge-space-card h3 {
  font-size: var(--fs-md);
  font-weight: var(--fw-semibold);
  color: var(--text-primary);
  margin: var(--sp-4) 0 var(--sp-2);
  line-height: var(--lh-snug);
}
.knowledge-space-card p {
  font-size: var(--fs-xs);
  color: var(--text-tertiary);
  line-height: var(--lh-base);
  margin: 0 0 var(--sp-4);
}
.knowledge-space-card > div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: auto;
  border-top: 1px solid var(--border-subtle);
  padding-top: var(--sp-3);
  font-size: var(--fs-xs);
  color: var(--brand-600);
  font-weight: var(--fw-medium);
}
.work-caption { color: var(--text-placeholder); font-size: var(--fs-2xs); margin: var(--sp-3) 0 0; line-height: var(--lh-base); }

/* ---------------- 流程说明卡 ---------------- */
.work-status-card {
  position: relative;
  overflow: hidden;
  padding: var(--sp-5);
  background: var(--n-0);
  border: 1px solid var(--border-subtle);
  border-radius: var(--r-lg);
  box-shadow: var(--sh-xs);
}
.work-status-card::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 3px;
  background: linear-gradient(90deg, var(--brand-500), var(--brand-300) 45%, transparent);
}
.work-status-card h2 { font-size: var(--fs-md); margin: 6px 0 var(--sp-4); }
.work-status-card .section-kicker { font-size: var(--fs-2xs); letter-spacing: 1.5px; color: var(--brand-500); }

.work-guide { list-style: none; padding: 0; margin: 0; }
.work-guide li {
  display: grid;
  grid-template-columns: 26px minmax(0, 1fr);
  column-gap: var(--sp-3);
  margin-top: var(--sp-4);
}
.work-guide li > span {
  color: var(--brand-600);
  background: var(--brand-50);
  border: 1px solid var(--brand-100);
  font-size: var(--fs-2xs);
  font-weight: var(--fw-semibold);
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border-radius: var(--r-sm);
  font-variant-numeric: tabular-nums;
}
.work-guide strong {
  color: var(--text-primary);
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  line-height: 26px;
}
.work-guide p { color: var(--text-tertiary); font-size: var(--fs-2xs); line-height: var(--lh-base); margin: 2px 0 0; }

.status-explanation {
  display: flex;
  align-items: flex-start;
  gap: var(--sp-2);
  color: var(--text-tertiary);
  margin-top: var(--sp-4);
  padding-top: var(--sp-4);
  border-top: 1px solid var(--border-subtle);
}
.status-explanation svg { color: var(--brand-500); flex-shrink: 0; margin-top: 1px; }
.status-explanation p { font-size: var(--fs-2xs); line-height: var(--lh-base); margin: 0; }

/* ---------------- 快捷入口 ---------------- */
.work-shortcuts {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--sp-4);
  max-width: 1280px;
  margin: var(--sp-5) auto 0;
}
.work-shortcuts > button {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-5);
  border: 1px solid var(--border-subtle);
  background: var(--n-0);
  border-radius: var(--r-md);
  text-align: left;
  box-shadow: var(--sh-xs);
  transition: background var(--dur-2) var(--ease-out),
              border-color var(--dur-2) var(--ease-out),
              transform var(--dur-2) var(--ease-out);
}
.work-shortcuts > button:hover { background: var(--n-25); border-color: var(--brand-200); transform: translateY(-2px); }
.shortcut-icon {
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  border-radius: var(--r-md);
  background: var(--brand-50);
  color: var(--brand-600);
  flex-shrink: 0;
}
.work-shortcuts strong {
  display: block;
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  color: var(--text-primary);
  line-height: var(--lh-snug);
}
.work-shortcuts small { display: block; color: var(--text-tertiary); font-size: var(--fs-2xs); margin-top: 4px; line-height: var(--lh-base); }
.work-shortcuts > button > svg { margin-left: auto; color: var(--n-300); flex-shrink: 0; }

/* 企业问答占满整屏，底部不再保留营销式页脚 */
.enterprise-workspace[data-page="enterprise"] .work-page-footer { display: none; }

.work-page-footer {
  display: flex;
  justify-content: space-between;
  gap: var(--sp-4);
  max-width: 1280px;
  margin: var(--sp-8) auto 0;
  border-top: 1px solid var(--border-subtle);
  padding: var(--sp-5) 0 var(--sp-6);
  color: var(--text-placeholder);
  font-size: var(--fs-2xs);
}

/* ---------------- 空状态 / 提示 ---------------- */
.work-empty-state {
  max-width: 860px;
  margin: var(--sp-8) auto;
  padding: var(--sp-10) var(--sp-7);
  text-align: center;
  border: 1px solid var(--border-subtle);
  border-radius: var(--r-xl);
  background: var(--n-0);
  box-shadow: var(--sh-sm);
}
.empty-state-icon {
  display: grid;
  place-items: center;
  width: 72px;
  height: 72px;
  background: var(--brand-50);
  color: var(--brand-500);
  border: 1px solid var(--brand-100);
  border-radius: var(--r-lg);
  margin: 0 auto var(--sp-5);
}
.work-empty-state h2 {
  color: var(--text-primary);
  font-size: var(--fs-2xl);
  font-weight: var(--fw-semibold);
  line-height: var(--lh-snug);
  margin: var(--sp-3) 0;
}
.work-empty-state > p { font-size: var(--fs-sm); line-height: var(--lh-base); color: var(--text-tertiary); }
.enterprise-example-list { margin: var(--sp-5) auto; max-width: 520px; text-align: left; }
.enterprise-example-list > span { font-size: var(--fs-2xs); color: var(--text-placeholder); }
.enterprise-example-list button {
  display: flex;
  align-items: center;
  width: 100%;
  gap: var(--sp-2);
  text-align: left;
  padding: var(--sp-3) var(--sp-4);
  margin-top: var(--sp-2);
  background: var(--n-50);
  border: 1px solid var(--border-subtle);
  border-radius: var(--r-sm);
  color: var(--text-secondary);
  font-size: var(--fs-sm);
  line-height: var(--lh-base);
  transition: border-color var(--dur-1), background var(--dur-1);
}
.enterprise-example-list button:hover { border-color: var(--brand-200); background: var(--brand-50); }
.enterprise-example-list button > svg:last-child { margin-left: auto; color: var(--n-300); }

.work-inline-feedback {
  padding: var(--sp-3) var(--sp-4);
  border: 1px solid var(--brand-100);
  border-radius: var(--r-sm);
  background: var(--brand-50);
  font-size: var(--fs-sm);
  line-height: var(--lh-base);
  color: var(--brand-700);
  max-width: 1280px;
  margin: var(--sp-5) auto;
}
.work-text-link { display: block; margin: var(--sp-4) auto 0; color: var(--brand-600); font-size: var(--fs-xs); line-height: var(--lh-base); }
.knowledge-page-intro { max-width: 1280px; margin: 0 auto var(--sp-6); }
.knowledge-page-intro p { font-size: var(--fs-sm); line-height: var(--lh-base); color: var(--text-tertiary); }

.planned-pill {
  display: inline-block;
  white-space: nowrap;
  padding: 4px 9px;
  border: 1px solid var(--border-subtle);
  background: var(--n-50);
  color: var(--text-tertiary);
  border-radius: var(--r-full);
  font-size: var(--fs-2xs);
  font-weight: var(--fw-medium);
}
.demo-pill { color: var(--amber-600); background: var(--amber-soft); border-color: var(--warning-border); }
.knowledge-empty { max-width: 1280px; margin-top: var(--sp-5); border-style: dashed; background: transparent; box-shadow: none; }
.knowledge-principles {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: var(--sp-5);
  margin-top: var(--sp-5);
}
.knowledge-principles span { display: flex; align-items: center; gap: 5px; font-size: var(--fs-xs); color: var(--text-tertiary); }

/* ---------------- 员工事务页 ---------------- */
.affairs-banner {
  position: relative;
  overflow: hidden;
  max-width: 1280px;
  margin: auto;
  border-radius: var(--r-xl);
  padding: var(--sp-8);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-5);
  background: var(--focus-block);
  box-shadow: var(--sh-lg);
  isolation: isolate;
}
.affairs-banner::before {
  content: '';
  position: absolute;
  inset: 0;
  background: var(--focus-block-glow);
  z-index: -1;
}
.affairs-banner h2 {
  font-size: var(--fs-2xl);
  color: #fff;
  font-weight: var(--fw-semibold);
  letter-spacing: -.3px;
  margin: var(--sp-3) 0;
  line-height: 1.4;
}
.affairs-banner p { font-size: var(--fs-sm); line-height: var(--lh-base); color: var(--focus-block-muted); margin: 0; }
.affairs-banner .section-kicker { color: rgba(138, 211, 177, .9); }
.affairs-banner > svg { color: rgba(255, 255, 255, .18); margin-right: var(--sp-4); flex-shrink: 0; }

.affairs-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--sp-5);
  max-width: 1280px;
  margin: var(--sp-5) auto;
}
.affairs-grid-two { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.affairs-panel-slot { max-width: 1280px; margin: 0 auto var(--sp-5); }
.affair-note { margin-top: auto; font-size: var(--fs-2xs); line-height: var(--lh-base); color: var(--text-tertiary); }

.affair-card {
  position: relative;
  display: flex;
  align-items: flex-start;
  flex-direction: column;
  padding: var(--sp-5);
  background: var(--n-0);
  border: 1px solid var(--border-subtle);
  border-radius: var(--r-lg);
  box-shadow: var(--sh-xs);
}
.affair-card .planned-pill { position: absolute; right: var(--sp-4); top: var(--sp-5); }
.affair-card h2 {
  color: var(--text-primary);
  font-size: var(--fs-md);
  font-weight: var(--fw-semibold);
  margin: var(--sp-5) 0 0;
  line-height: var(--lh-snug);
}
.affair-card p { font-size: var(--fs-xs); color: var(--text-tertiary); line-height: var(--lh-base); margin: var(--sp-2) 0 var(--sp-5); }
.affair-card > button { margin-top: auto; }
.affair-card .work-primary { background: var(--brand-600); color: #fff; padding: 10px 16px; box-shadow: none; }
.affair-card .work-primary:hover { background: var(--brand-700); transform: translateY(-1px); }

.affairs-boundary {
  max-width: 1280px;
  margin: auto;
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-4);
  background: var(--n-100);
  border: 1px solid var(--border-subtle);
  border-radius: var(--r-md);
  color: var(--text-tertiary);
}
.affairs-boundary svg { color: var(--brand-500); flex-shrink: 0; }
.affairs-boundary p { font-size: var(--fs-2xs); line-height: var(--lh-base); margin: 0; }

/* ---------------- 响应式 ---------------- */
@media (max-width: 1280px) {
  .work-dashboard-grid { grid-template-columns: minmax(0, 1fr); gap: var(--sp-5); }
  .work-status-card { max-width: 100%; }
  .knowledge-illustration { transform: scale(.86); width: 316px; }
  .affairs-grid { gap: var(--sp-3); }
  .affair-card { padding: var(--sp-4); }
}

@media (max-width: 1000px) {
  .workspace-stage { display: none; }
  .work-hero { grid-template-columns: 1fr; }
  .knowledge-illustration { display: none; }
  .work-shortcuts, .affairs-grid { grid-template-columns: 1fr; }
  .knowledge-space-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .work-hero h2 { font-size: 32px; }
}

@media (max-width: 600px) {
  .enterprise-workspace { padding: var(--sp-5) var(--sp-4) 0; }
  .workspace-page-heading { margin-bottom: var(--sp-4); }
  .workspace-page-heading h1 { font-size: var(--fs-2xl); }
  .section-kicker { font-size: var(--fs-2xs); }
  .work-hero { padding: var(--sp-6) var(--sp-5); border-radius: var(--r-xl); }
  .work-hero h2 { font-size: 26px; }
  .work-hero p { font-size: var(--fs-sm); }
  .work-hero-actions { gap: var(--sp-2); margin-top: var(--sp-5); }
  .work-hero-actions .work-primary { padding: 11px 16px; }
  .work-dashboard-grid { margin-top: var(--sp-6); }
  .work-section-heading { align-items: flex-start; flex-wrap: wrap; gap: var(--sp-2); }
  .work-section-heading h2 { font-size: var(--fs-lg); }
  .knowledge-space-grid { grid-template-columns: 1fr; gap: var(--sp-3); }
  .knowledge-space-card { padding: var(--sp-4); }
  .knowledge-space-card h3 { margin-top: var(--sp-3); }
  .knowledge-space-card p { margin-bottom: var(--sp-3); }
  .work-shortcuts { gap: var(--sp-3); margin-top: var(--sp-4); }
  .work-shortcuts > button { padding: var(--sp-4); gap: var(--sp-3); }
  .work-page-footer { flex-direction: column; gap: 6px; font-size: var(--fs-2xs); }
  .work-empty-state { margin-top: var(--sp-4); padding: var(--sp-7) var(--sp-5); }
  .work-empty-state h2 { font-size: var(--fs-xl); }
  .knowledge-principles { gap: var(--sp-3); }
  .affairs-banner { padding: var(--sp-6) var(--sp-5); }
  .affairs-banner > svg { display: none; }
  .affairs-banner h2 { font-size: var(--fs-xl); }
}
</style>
