<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { createEnterpriseClient, EnterpriseError, positiveId } from '../enterprise';
import type { KnowledgeSpace, VectorJob, VectorPublication } from '../enterprise';
import { workspaceUuid } from '../knowledge-workspace';
import { uuid } from '../uuid';
import type { KnowledgeDocument, DocumentVersion, KnowledgeChunk, KnowledgeGrant, PageData, UploadMetadata } from '../knowledge';
import { ingestActive } from '../knowledge-ingest';
import type { IngestJob } from '../knowledge-ingest';
import { t } from '../i18n';
import AppIcon from './AppIcon.vue';

const props = defineProps<{ enterpriseId: string; space: KnowledgeSpace }>();
const emit = defineEmits<{ updated: [space: KnowledgeSpace]; expired: []; busy: [value: boolean] }>();
const api = createEnterpriseClient();
const catalog = ref<PageData<KnowledgeDocument>>();
const selected = ref<KnowledgeDocument>();
const versions = ref<PageData<DocumentVersion>>();
const fragments = ref<PageData<KnowledgeChunk>>();
const fragmentVersion = ref<DocumentVersion>();
// 原文片段默认收起，避免与阅读层重复占满页面。
const fragmentOpen = ref(false);
// 资料阅读层：与下方管理区分离，先让成员快速看到全文。
const readerOpen = ref(false);
const readerLoading = ref(false);
const readerError = ref('');
const readerVersion = ref<DocumentVersion>();
const readerChunks = ref<KnowledgeChunk[]>([]);
const readerPage = ref(0);
const readerPages = ref(0);
const grants = ref<KnowledgeGrant[]>([]);
// 用可选列表代替手填编号：成员/部门清单来自组织接口，失败时回退到编号输入。
const memberOptions = ref<{ id: string; username: string }[]>([]);
const departmentOptions = ref<{ id: string; name: string }[]>([]);
const canMaintain = ref(false);
const busy = ref(false);
const failed = ref(false);
const message = ref(t('点击刷新资料，核验当前权限和文档状态。'));
const rename = ref(props.space.name);
const targetType = ref('MEMBER');
const targetId = ref('');
const permission = ref('READ');
const ownerId = ref('');
const title = ref('');
const from = ref('');
const until = ref('');
const fileInput = ref<HTMLInputElement>();
const replace = ref(false);
const documentQuery = ref('');
const selectedFileName = ref('');
const visibleDocuments = computed(() => catalog.value?.records.filter(document => document.title.toLocaleLowerCase().includes(documentQuery.value.trim().toLocaleLowerCase())) ?? []);
const publishedCount = computed(() => catalog.value?.records.filter(document => !!document.publishedVersionId).length ?? 0);
// 负责人优先显示姓名，同时保留编号方便与后台核对。
const ownerLabel = computed(() => {
  if (!selected.value) return '';
  return selected.value.ownerMemberName
    ? `${selected.value.ownerMemberName}（#${selected.value.ownerMemberId}）`
    : `${t('成员')} #${selected.value.ownerMemberId}`;
});
function memberLabel(id: string) {
  const found = memberOptions.value.find(member => member.id === id);
  return found ? `${found.username}（#${id}）` : `${t('成员')} #${id}`;
}
function departmentLabel(id: string) {
  const found = departmentOptions.value.find(department => department.id === id);
  return found ? `${found.name}（#${id}）` : `${t('部门')} #${id}`;
}
// 组织接口仅管理员可读；维护者读取失败时保留编号输入作为回退。
async function loadCandidates() {
  try {
    const [memberPage, departmentPage] = await Promise.all([
      api.organizationMembers(props.enterpriseId, 1),
      api.organizationDepartments(props.enterpriseId, 1)
    ]);
    memberOptions.value = memberPage.records.filter(member => member.status === 0).map(member => ({ id: member.id, username: member.displayName || member.username }));
    departmentOptions.value = departmentPage.page.records.map(department => ({ id: department.id, name: department.name }));
  } catch (error) {
    memberOptions.value = []; departmentOptions.value = [];
    if (error instanceof EnterpriseError && error.code === 401) throw error;
  }
}
const unknownWrite = ref(false);
const pendingUpload = ref<{ metadata: UploadMetadata; file: File }>();
const ingestVersion = ref<DocumentVersion>();
const ingestTasks = ref<IngestJob[]>([]);
const pendingIngest = ref<{ id: string; version: DocumentVersion; revision: number; action: string }>();
const vectorVersion = ref<DocumentVersion>();
const vectorTask = ref<VectorJob>();
const vectorPointer = ref<VectorPublication>();
const vectorId = ref('');
const vectorNotice = ref('');
const vectorUncertain = ref<'TASK' | 'POINTER'>();
const pendingVector = ref<{ id: string; revision: string }>();
const pendingPointer = ref<{ action: 'publish' | 'withdraw'; jobId: string | null; revision: string }>();
const vectorActive = computed(() => !!vectorTask.value && ['QUEUED', 'RUNNING'].includes(vectorTask.value.status));
let vectorTimer: ReturnType<typeof setTimeout> | undefined;
let vectorPollUntil = 0;
let vectorReads = 0;
let pollTimer: ReturnType<typeof setTimeout> | undefined;
let pollUntil = 0;
let alive = true;
const labels = computed<Record<string, string>>(() => ({ PENDING: t('待解析'), READY: t('片段就绪'), FAILED: t('解析失败'), DRAFT: t('草稿'), PUBLISHED: t('已发布'), WITHDRAWN: t('已撤回'), DELETED: t('已删除') }));
/** 检索索引的任务与指针状态，界面上不出现英文枚举和内部术语。 */
const indexJobLabels = computed<Record<string, string>>(() => ({ QUEUED: t('排队中'), RUNNING: t('正在建立'), VERIFIED: t('已建立完成'), CANCELLED: t('已取消'), TIMED_OUT: t('超时未完成'), FAILED: t('建立失败') }));
const indexPointerLabels = computed<Record<string, string>>(() => ({ UNPUBLISHED: t('问答还搜不到这份资料'), READY: t('问答已能搜到这份资料'), STALE: t('资料变过，建议重建'), WITHDRAWN: t('已关闭检索') }));

async function run(work: () => Promise<void>, vector = false) {
  if (busy.value) return;
  busy.value = true;
  emit('busy', true);
  failed.value = false;
  message.value = t('正在处理，单次操作最多等待10秒…');
  try { await work(); }
  catch (error) {
    if (!alive) return;
    failed.value = true;
    message.value = error instanceof Error ? error.message : t('操作未完成，请刷新后重试。');
    if (vector) stopVectorPolling();
    if (!vector && error instanceof EnterpriseError && error.uncertain) unknownWrite.value = true;
    if (error instanceof EnterpriseError && [401, 404].includes(error.code)) {
      catalog.value = undefined; selected.value = undefined; versions.value = undefined; fragments.value = undefined;
      readerOpen.value = false;
      grants.value = []; canMaintain.value = false;
      stopPolling(); ingestTasks.value = []; ingestVersion.value = undefined; pendingIngest.value = undefined;
      clearVector();
      if (error.code === 401) { pendingUpload.value = undefined; emit('expired'); }
    }
  } finally { if (alive) { busy.value = false; emit('busy', false); } }
}

async function refreshData(page = 1) {
  catalog.value = undefined;
  fragments.value = undefined;
  readerOpen.value = false;
  const currentSpace = await api.readSpace(props.enterpriseId, props.space.id);
  if (!alive) return;
  emit('updated', currentSpace);
  try { grants.value = await api.grants(props.enterpriseId, props.space.id); canMaintain.value = true; }
  catch (error) {
    grants.value = []; canMaintain.value = false;
    clearVector();
    if (!(error instanceof EnterpriseError && error.code === 404)) throw error;
  }
  if (canMaintain.value) await loadCandidates();
  catalog.value = await api.documents(props.enterpriseId, props.space.id, page);
  if (selected.value) {
    const id = selected.value.id;
    selected.value = undefined; versions.value = undefined;
    selected.value = await api.document(props.enterpriseId, props.space.id, id);
    versions.value = await api.versions(props.enterpriseId, props.space.id, id);
  }
  unknownWrite.value = false;
}
async function refresh(page = 1) { await run(async () => { await refreshData(page); message.value = t('资料状态已刷新。维护者可查看草稿；只读成员仅可访问当前有效的发布版本。'); }); }
async function select(document: KnowledgeDocument) {
  if (busy.value || vectorUncertain.value) return;
  clearVector();
  stopPolling(); ingestTasks.value = []; ingestVersion.value = undefined;
  await run(async () => {
    selected.value = undefined; versions.value = undefined; fragments.value = undefined;
    readerOpen.value = false; readerError.value = ''; readerChunks.value = [];
    readerVersion.value = undefined; readerPage.value = 0; readerPages.value = 0;
    selected.value = await api.document(props.enterpriseId, props.space.id, document.id);
    versions.value = await api.versions(props.enterpriseId, props.space.id, document.id);
    ownerId.value = selected.value.ownerMemberId;
    // 先打开阅读层，让正文加载过程有明确的等待界面。
    readerOpen.value = true;
    await prepareReader();
    message.value = t('已读取文档及当前允许访问的版本。');
  });
}
async function prepareReader() {
  const list = versions.value?.records ?? [];
  const readable = list.find(version => version.publicationStatus === 'PUBLISHED' && version.parseStatus === 'READY')
    ?? (canMaintain.value ? list.find(version => version.parseStatus === 'READY') : undefined);
  readerVersion.value = readable;
  readerChunks.value = []; readerPage.value = 0; readerPages.value = 0;
  if (!readable) return;
  await loadReader(readable, 1, true);
}
async function loadReader(version: DocumentVersion, page: number, replace = false) {
  readerLoading.value = true; readerError.value = '';
  try {
    const data = await api.chunks(props.enterpriseId, props.space.id, version.documentId, version.id, page);
    readerChunks.value = replace ? data.records : [...readerChunks.value, ...data.records];
    readerPage.value = data.pageNum; readerPages.value = data.pages;
    readerVersion.value = version;
  } catch (error) {
    readerError.value = error instanceof Error ? error.message : t('正文读取失败，请稍后重试。');
  } finally { readerLoading.value = false; }
}
function closeReader() { readerOpen.value = false; }
function moreReader() {
  if (readerVersion.value && readerPage.value < readerPages.value) void loadReader(readerVersion.value, readerPage.value + 1);
}
async function downloadReader() {
  if (readerVersion.value) await download(readerVersion.value);
}
async function versionPage(page: number) { await run(async () => { if (selected.value) versions.value = await api.versions(props.enterpriseId, props.space.id, selected.value.id, page); message.value = t('版本列表已更新。'); }); }
async function renameSpace() {
  await run(async () => { emit('updated', await api.renameSpace(props.enterpriseId, props.space.id, props.space.permissionVersion, rename.value)); message.value = t('空间名称已更新。'); });
}
async function changeGrant(revoke = false) {
  await run(async () => {
    emit('updated', await api.changeGrant(props.enterpriseId, props.space.id, props.space.permissionVersion, targetType.value, targetId.value, permission.value, revoke));
    grants.value = await api.grants(props.enterpriseId, props.space.id);
    message.value = revoke ? t('授权已撤销，后续读取会重新校验。') : t('明确授权已保存。');
  });
}
function date(value: string) {
  if (!value) return null;
  const time = Date.parse(value);
  if (!Number.isSafeInteger(time) || time < 0) throw new EnterpriseError(t('有效期日期不合法。'));
  return String(time);
}
async function upload() {
  if (vectorUncertain.value) return;
  await run(async () => {
    if (!pendingUpload.value) {
      const file = fileInput.value?.files?.[0];
      if (!file) throw new EnterpriseError(t('请先选择TXT/Markdown、文字层PDF或DOCX原件。'));
      if (replace.value && !selected.value) throw new EnterpriseError(t('请先选择要新增版本的文档。'));
      pendingUpload.value = { file, metadata: { requestId: uuid(), documentId: replace.value ? selected.value!.id : null,
        expectedRevision: replace.value ? selected.value!.revision : null, title: title.value.trim() || file.name,
        validFromMs: date(from.value), validUntilMs: date(until.value) } };
    }
    try {
      const result = await api.uploadDocument(props.enterpriseId, props.space.id, pendingUpload.value.metadata, pendingUpload.value.file);
      pendingUpload.value = undefined;
      if (fileInput.value) fileInput.value.value = '';
      selectedFileName.value = '';
      selected.value = await api.document(props.enterpriseId, props.space.id, result.documentId);
      clearVector();
      await refreshData();
      message.value = t('原件与草稿版本已保存。请完成解析、检查片段，再明确发布。');
    } catch (error) {
      if (!(error instanceof EnterpriseError && error.uncertain)) pendingUpload.value = undefined;
      throw error;
    }
  });
}
async function parse(version: DocumentVersion) {
  await submitIngest(version, 'PARSE');
}
function stopPolling() { clearTimeout(pollTimer); pollUntil = 0; }
function poll() {
  clearTimeout(pollTimer);
  if (!alive || Date.now() >= pollUntil || !ingestTasks.value.some(ingestActive)) return;
  pollTimer = setTimeout(async () => {
    if (!busy.value && ingestVersion.value) await readIngest(ingestVersion.value, false);
    if (!failed.value) poll();
  }, 1500);
}
async function readIngest(version: DocumentVersion, restart = true) {
  if (restart) { stopPolling(); pollUntil = Date.now() + 40000; }
  await run(async () => {
    const tasks = await api.ingestHistory(props.enterpriseId, props.space.id, version.documentId, version.id);
    if (!alive) return;
    ingestVersion.value = version; ingestTasks.value = tasks;
    if (tasks.length && !tasks.some(ingestActive)) await refreshData();
    message.value = tasks[0]?.message ?? t('本版本暂无本人入库任务。');
  });
  if (restart && !failed.value) poll();
}
async function submitIngest(version: DocumentVersion, action: string) {
  if (!selected.value) return;
  if (action === 'REBUILD' && !window.confirm(t('仅重建此草稿的片段，不发布。确认继续？'))) return;
  await run(async () => {
    if (!pendingIngest.value) pendingIngest.value = { id: uuid(), version, revision: selected.value!.revision, action };
    const pending = pendingIngest.value;
    try {
      // 未知写结果先读取原UUID；确认为不存在才允许同UUID、同参数人工重传。
      let job: IngestJob;
      try { job = await api.readIngest(props.enterpriseId, props.space.id, pending.version.documentId, pending.version.id, pending.id); }
      catch (error) {
        if (!(error instanceof EnterpriseError && error.code === 404)) throw error;
        job = await api.submitIngest(props.enterpriseId, props.space.id, pending.version.documentId, pending.version.id, pending.id, pending.revision, pending.action);
      }
      if (!alive) return;
      ingestVersion.value = pending.version; ingestTasks.value = [job]; pendingIngest.value = undefined;
      unknownWrite.value = false; message.value = job.message;
      pollUntil = Date.now() + 40000; poll();
    } catch (error) {
      if (!(error instanceof EnterpriseError && error.uncertain)) pendingIngest.value = undefined;
      throw error;
    }
  });
}
async function cancelIngest(job: IngestJob) {
  await run(async () => {
    const result = await api.cancelIngest(props.enterpriseId, props.space.id, job.documentId, job.versionId, job.id);
    if (!alive) return;
    ingestTasks.value = ingestTasks.value.map(item => item.id === result.id ? result : item); message.value = result.message;
    if (!ingestTasks.value.some(ingestActive)) stopPolling();
  });
}
function stopVectorPolling() { clearTimeout(vectorTimer); vectorPollUntil = 0; }
function clearVector() {
  stopVectorPolling(); vectorVersion.value = undefined; vectorTask.value = undefined; vectorPointer.value = undefined;
  vectorId.value = ''; vectorNotice.value = ''; vectorUncertain.value = undefined;
  pendingVector.value = undefined; pendingPointer.value = undefined;
}
async function openVector(version: DocumentVersion) {
  if (busy.value || !canMaintain.value) return;
  clearVector(); vectorVersion.value = version;
  await readPointer();
}
async function readPointer() {
  if (!selected.value || !canMaintain.value) return;
  const documentId = selected.value.id;
  await run(async () => {
    const pointer = await api.readVectorPublication(props.enterpriseId, props.space.id, documentId);
    if (!alive || selected.value?.id !== documentId) return;
    vectorPointer.value = pointer;
    const pending = pendingPointer.value;
    if (pending) {
      if (BigInt(pointer.revision) <= BigInt(pending.revision)) {
        vectorNotice.value = t('尚未确认上次索引写入结果；继续查询指针，不自动重发。');
      } else {
        const matched = pointer.revision === (BigInt(pending.revision) + 1n).toString()
          && (pending.action === 'publish' ? pointer.status === 'READY' && pointer.jobId === pending.jobId : pointer.status === 'WITHDRAWN');
        vectorNotice.value = matched ? t('已核对上次索引操作的结果。') : t('指针已被其他操作更新，请按当前状态重新判断。');
        pendingPointer.value = undefined; vectorUncertain.value = undefined;
      }
    }
    message.value = pointer.message;
  }, true);
}
function pollVector() {
  clearTimeout(vectorTimer);
  if (!alive || !vectorActive.value || !vectorPollUntil) return;
  if (Date.now() >= vectorPollUntil || vectorReads >= 80) {
    stopVectorPolling(); vectorNotice.value = t('自动查询已到上限；后台仍按原120秒截止，可用原编号手动查询。'); return;
  }
  vectorTimer = setTimeout(async () => {
    if (!vectorPollUntil || Date.now() >= vectorPollUntil) { pollVector(); return; }
    if (!busy.value) { vectorReads++; await readVector(false); }
    if (!failed.value) pollVector();
  }, 1500);
}
async function readVector(restart = true) {
  if (!vectorVersion.value || !canMaintain.value || !workspaceUuid(vectorId.value)) return;
  if (restart) stopVectorPolling();
  const version = vectorVersion.value; const id = vectorId.value;
  await run(async () => {
    const job = await api.readVectorJob(props.enterpriseId, props.space.id, version.documentId, version.id, id);
    if (!alive || vectorVersion.value?.id !== version.id || vectorId.value !== id) return;
    if (pendingVector.value && job.expectedRevision !== pendingVector.value.revision)
      throw new EnterpriseError(t('原任务修订与提交参数不一致，请核对后端记录。'));
    vectorTask.value = job;
    if (vectorUncertain.value === 'TASK') { vectorUncertain.value = undefined; pendingVector.value = undefined; }
    message.value = job.message;
    if (!vectorActive.value) { stopVectorPolling(); vectorNotice.value = t('任务已结束；核验完成不等于索引已发布。'); }
  }, true);
  if (restart && !failed.value && vectorActive.value) {
    vectorPollUntil = Math.min(Date.now() + 130000, vectorTask.value!.deadlineMs + 10000); vectorReads = 0; pollVector();
  }
}
async function submitVector() {
  const version = vectorVersion.value; const document = selected.value;
  if (!version || !document || !canMaintain.value || vectorUncertain.value || vectorActive.value
    || unknownWrite.value || version.parseStatus !== 'READY' || document.publishedVersionId !== version.id) return;
  await run(async () => {
    if (!Number.isSafeInteger(document.revision) || document.revision <= 0) throw new EnterpriseError(t('文档修订不安全，请刷新文档。'));
    const pending = { id: uuid(), revision: String(document.revision) };
    pendingVector.value = pending; vectorId.value = pending.id; vectorTask.value = undefined;
    try {
      const job = await api.submitVectorJob(props.enterpriseId, props.space.id, document.id, version.id, pending.id, pending.revision);
      if (!alive) return;
      vectorTask.value = job; pendingVector.value = undefined; message.value = job.message;
      vectorNotice.value = t('已开始建立索引。任务编号：{id}（已自动填入下方输入框，建议复制留存）。页面会自动刷新进度，中途离开后凭此编号回来查询。', { id: pending.id });
      vectorPollUntil = Date.now() + 130000; vectorReads = 0; pollVector();
    } catch (error) {
        if (error instanceof EnterpriseError && error.uncertain) {
          vectorUncertain.value = 'TASK'; vectorNotice.value = t('提交结果未知。请点「按编号查进度」，用下方输入框里已填好的编号查询；不要换编号重试。');
        } else pendingVector.value = undefined;
      throw error;
    }
  }, true);
}
async function cancelVector() {
  const job = vectorTask.value;
  if (!job || !vectorActive.value || vectorUncertain.value) return;
  stopVectorPolling();
  await run(async () => {
    try {
      const result = await api.cancelVectorJob(props.enterpriseId, props.space.id, job.documentId, job.versionId, job.id);
      if (!alive) return;
      vectorTask.value = result; message.value = result.message;
      vectorNotice.value = t('以服务端状态为准；取消任务不代表回滚已写向量或撤回索引。');
    } catch (error) {
      if (error instanceof EnterpriseError && error.uncertain) vectorUncertain.value = 'TASK';
      throw error;
    }
  }, true);
}
async function changeVectorPointer(action: 'publish' | 'withdraw') {
  const pointer = vectorPointer.value; const job = vectorTask.value;
  if (!selected.value || !pointer || !canMaintain.value || vectorUncertain.value || unknownWrite.value
    || (action === 'publish' && (job?.status !== 'VERIFIED' || selected.value.publishedVersionId !== job.versionId))) return;
  if (!window.confirm(action === 'publish' ? t('确认显式发布已核验索引？这不会自动启用正式混合问答。') : t('确认撤回索引指针？文档版本不会被撤回。'))) return;
  await run(async () => {
    pendingPointer.value = { action, jobId: action === 'publish' ? job!.id : pointer.jobId, revision: pointer.revision };
    try {
      const result = action === 'publish'
        ? await api.publishVectorIndex(props.enterpriseId, props.space.id, pointer.documentId, job!.id, pointer.revision)
        : await api.withdrawVectorIndex(props.enterpriseId, props.space.id, pointer.documentId, pointer.jobId, pointer.revision);
      if (!alive) return;
      vectorPointer.value = result; pendingPointer.value = undefined; message.value = result.message;
    } catch (error) {
      vectorPointer.value = undefined;
      if (error instanceof EnterpriseError && error.uncertain) {
        vectorUncertain.value = 'POINTER'; vectorNotice.value = t('索引写入结果未知；请查询当前指针核对，不自动重发。');
      } else pendingPointer.value = undefined;
      throw error;
    }
  }, true);
}
async function lifecycle(version: DocumentVersion | undefined, action: string) {
  if (!selected.value || vectorUncertain.value) return;
  if (['DELETE_DOCUMENT', 'DELETE_VERSION', 'WITHDRAW'].includes(action) && !window.confirm(t('确认此操作？撤回或删除会立即阻止普通成员后续访问，不会自动回退到旧版本。'))) return;
  await run(async () => {
    const result = await api.lifecycle(props.enterpriseId, props.space.id, selected.value!.id, version?.id ?? null, selected.value!.revision, action);
    selected.value = action === 'DELETE_DOCUMENT' ? undefined : result;
    versions.value = undefined; fragments.value = undefined;
    await refreshData(); message.value = t('生命周期状态已更新。删除为逻辑删除，原件留存与磁盘清理由管理员按政策处理。');
    clearVector();
  });
}
async function owner() {
  await run(async () => { if (!selected.value) return; selected.value = await api.assignOwner(props.enterpriseId, props.space.id, selected.value.id, selected.value.revision, ownerId.value); message.value = t('负责人已更新；新负责人必须已有该空间维护权。'); });
}
async function showChunks(version: DocumentVersion, page = 1) {
  fragmentOpen.value = true;
  await run(async () => { fragments.value = undefined; fragmentVersion.value = version; fragments.value = await api.chunks(props.enterpriseId, props.space.id, version.documentId, version.id, page); message.value = t('片段已读取；坐标对应提取后的文字。PDF含页码标记；DOCX段落和表格转为文字，不提供Word物理页码。'); });
}
function onFragmentToggle(event: Event) { fragmentOpen.value = (event.target as HTMLDetailsElement).open; }
async function download(version: DocumentVersion) {
  await run(async () => {
    const blob = await api.original(props.enterpriseId, props.space.id, version.documentId, version.id);
    if (!alive) return;
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = version.originalName; anchor.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000); message.value = t('原件已通过当前权限与完整性校验，下载已开始。');
  });
}
function displayDate(value: string | null) {
  if (value === null) return t('不限');
  const time = Number(value);
  return Number.isSafeInteger(time) && time <= 8640000000000000
    ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(time) : t('超出日期显示范围');
}
function onKeydown(event: KeyboardEvent) { if (event.key === 'Escape' && readerOpen.value) closeReader(); }
onMounted(() => { void refresh(); window.addEventListener('keydown', onKeydown); });
onUnmounted(() => { alive = false; window.removeEventListener('keydown', onKeydown); readerOpen.value = false; readerChunks.value = []; stopPolling(); clearVector(); api.dispose(); pendingUpload.value = undefined; pendingIngest.value = undefined; emit('busy', false); });
</script>

<template>
  <section class="document-manager" :aria-label="t('知识文档管理')">
    <div class="toolbar document-heading"><div><h3><AppIcon name="file" :size="18" />{{ t('空间资料') }}<span v-if="catalog" class="total-pill">{{ catalog.total }}</span></h3><p>{{ t('整理原件、核对内容，再发布给团队使用。') }}</p></div><button :disabled="busy" @click="refresh()"><AppIcon name="refresh" :size="14" />{{ t('刷新资料与权限') }}</button></div>
    <ol v-if="canMaintain" class="knowledge-steps" :aria-label="t('资料发布流程')"><li><span><AppIcon name="file" :size="16" /></span><div><strong>{{ t('上传资料') }}</strong><small>{{ t('01 · 保存原件与草稿') }}</small></div></li><li><span><AppIcon name="layers" :size="16" /></span><div><strong>{{ t('解析核对') }}</strong><small>{{ t('02 · 检查提取的原文片段') }}</small></div></li><li><span><AppIcon name="check" :size="16" /></span><div><strong>{{ t('确认发布') }}</strong><small>{{ t('03 · 按授权范围供团队使用') }}</small></div></li></ol>
    <p class="feedback" :class="{ error: failed }" role="status" aria-live="polite">{{ message }}</p>
    <p v-if="unknownWrite" class="feedback error">{{ t('操作结果待核对，先刷新状态。上传原请求仍可使用同一编号人工核对重试。') }}</p>
    <p v-if="pendingIngest" class="feedback">{{ t('待核对入库编号：{id}。', { id: pendingIngest.id }) }}<button :disabled="busy" @click="submitIngest(pendingIngest.version, pendingIngest.action)">{{ t('核对原任务，必要时同编号重传') }}</button></p>
    <section v-if="ingestVersion" aria-label="文档入库任务"><h4>{{ t('版本 {version} 的本人入库任务', { version: ingestVersion.versionNo }) }}</h4><p>{{ t('总时限30秒；刷新后选择版本并查看任务可恢复状态，不会自动重新提交。自动查询最多40秒，网络错误后可手动刷新。') }}</p><button :disabled="busy" @click="readIngest(ingestVersion)">{{ t('刷新入库任务') }}</button><ul><li v-for="job in ingestTasks" :key="job.id">{{ job.status }} · {{ job.message }}<small>{{ job.id }} · {{ t('尝试 {count} 次', { count: job.attempts }) }}<span v-if="job.failureCode"> · {{ job.failureCode }}</span></small><button v-if="ingestActive(job)" :disabled="busy || unknownWrite" @click="cancelIngest(job)">{{ t('取消入库任务') }}</button></li></ul></section>
    <details v-if="canMaintain" class="space-settings"><summary><AppIcon name="settings" :size="16" /><span>{{ t('空间设置与明确授权') }}</span><small>{{ t('名称 · 成员 · 部门权限') }}</small><AppIcon name="chevron" :size="14" /></summary>
      <form @submit.prevent="renameSpace"><fieldset :disabled="busy || unknownWrite"><label>{{ t('空间名称') }}<input v-model="rename" maxlength="128" required /></label><button>{{ t('保存名称') }}</button></fieldset></form>
      <form @submit.prevent="changeGrant()"><fieldset :disabled="busy || unknownWrite"><label>{{ t('目标类型') }}<select v-model="targetType" @change="targetId = ''"><option value="MEMBER">{{ t('成员') }}</option><option value="DEPARTMENT">{{ t('直属部门') }}</option></select></label><label>{{ targetType === 'MEMBER' ? t('选择成员') : t('选择部门') }}<select v-if="targetType === 'MEMBER' && memberOptions.length" v-model="targetId" required><option value="" disabled>{{ t('请选择成员') }}</option><option v-for="member in memberOptions" :key="member.id" :value="member.id">{{ member.username }}（#{{ member.id }}）</option></select><select v-else-if="targetType === 'DEPARTMENT' && departmentOptions.length" v-model="targetId" required><option value="" disabled>{{ t('请选择部门') }}</option><option v-for="department in departmentOptions" :key="department.id" :value="department.id">{{ department.name }}（#{{ department.id }}）</option></select><input v-else v-model="targetId" inputmode="numeric" required :placeholder="targetType === 'MEMBER' ? t('成员编号') : t('部门编号')" /></label><label>{{ t('权限') }}<select v-model="permission"><option value="READ">{{ t('只读') }}</option><option value="MAINTAIN">{{ t('维护（含读取）') }}</option></select></label><button :disabled="!positiveId(targetId)">{{ t('授予或更新') }}</button><button type="button" :disabled="!positiveId(targetId)" @click="changeGrant(true)">{{ t('撤销目标授权') }}</button></fieldset></form>
      <ul><li v-for="grant in grants" :key="grant.id">{{ grant.memberId ? memberLabel(grant.memberId) : departmentLabel(grant.departmentId ?? '') }} · {{ grant.permission }} · {{ grant.status === 0 ? t('有效') : t('已撤销') }}</li></ul>
    </details>
    <details v-if="canMaintain" class="upload-panel" :open="!catalog?.total || !!pendingUpload"><summary><span class="upload-summary-icon"><AppIcon name="plus" :size="19" /></span><span><strong>{{ t('添加知识资料') }}</strong><small>{{ t('TXT / Markdown / PDF / DOCX · 最大 1 MiB') }}</small></span><AppIcon name="chevron" :size="16" /></summary>
    <form :aria-label="t('上传知识文档')" @submit.prevent="upload"><fieldset :disabled="busy || !!vectorUncertain || (unknownWrite && !pendingUpload)"><legend>{{ t('保存文字原件') }}</legend>
      <div v-if="!pendingUpload"><label class="file-drop"><span class="upload-art"><AppIcon name="file" :size="27" /></span><strong>{{ selectedFileName || t('选择一份资料，开始整理团队知识') }}</strong><span>{{ t('点击选择文件 · 支持文字层 PDF，不支持扫描识别') }}</span><input ref="fileInput" type="file" accept=".txt,.md,.pdf,.docx" required @change="selectedFileName = fileInput?.files?.[0]?.name ?? ''" /></label><label>{{ t('文档标题') }}<input v-model="title" maxlength="255" :placeholder="t('可选，留空将使用文件名称')" /></label><details class="upload-options"><summary>{{ t('版本与有效期设置') }}<AppIcon name="chevron" :size="13" /></summary><label class="check-label"><input v-model="replace" type="checkbox" />{{ t('为当前所选文档新增版本') }}</label><div class="date-grid"><label>{{ t('生效时间（当前设备时区）') }}<input v-model="from" type="datetime-local" /></label><label>{{ t('失效时间（不含该时刻）') }}<input v-model="until" type="datetime-local" /></label></div></details></div>
      <p v-else>{{ t('待核对上传编号：{id}。文件与参数已固定，手动重试仍用此编号。', { id: pendingUpload.metadata.requestId }) }}</p>
      <div class="upload-footer"><small><AppIcon name="shield" :size="13" />{{ t('原件私有保存 · 上传后仍需解析和发布') }}<br />{{ t('不进行 OCR，不发送企业原文到外部模型') }}</small><button class="primary-button">{{ pendingUpload ? t('使用原编号核对并重试上传') : t('保存原件与草稿') }}</button></div>
    </fieldset></form></details>
    <template v-if="catalog"><div class="catalog-heading"><div class="catalog-intro"><span class="catalog-kicker">KNOWLEDGE LIBRARY</span><h3>{{ t('需要什么资料，直接找') }}<span class="catalog-count">{{ catalog.total }}</span></h3><p>{{ t('{count} 份已发布 · 点击卡片即可阅读全文，这里只展示你有权访问的有效资料。', { count: publishedCount }) }}</p></div><label class="document-search"><AppIcon name="search" :size="20" /><input v-model="documentQuery" :aria-label="t('搜索资料标题')" :placeholder="t('搜索资料标题，例如：成绩复核、宿舍报修、奖学金…')" /></label></div><ul class="document-grid"><li v-for="(item, index) in visibleDocuments" :key="item.id"><button class="document-card" :class="{ selected: selected?.id === item.id }" :disabled="busy || !!vectorUncertain" @click="select(item)"><span class="document-card-top"><span class="file-symbol" :class="'tone-' + (index % 3)"><AppIcon name="file" :size="28" /></span><span class="document-status" :class="{ published: !!item.publishedVersionId }">{{ item.publishedVersionId ? t('已发布') : t('未发布') }}</span></span><strong class="document-card-title">{{ item.title }}</strong><small class="document-card-meta">{{ t('文档 #{id} · 修订 {revision}', { id: item.id, revision: item.revision }) }}</small><p class="document-card-description">{{ item.publishedVersionId ? t('内容已发布，可阅读全文并核对版本。') : t('尚未发布，普通成员暂时看不到这份资料。') }}</p><span class="document-card-footer"><span>{{ item.publishedVersionId ? t('可阅读全文') : t('等待发布') }}</span><span>{{ t('打开资料') }}<AppIcon name="chevron" :size="15" /></span></span></button></li></ul><div v-if="!visibleDocuments.length" class="documents-empty"><AppIcon :name="documentQuery ? 'search' : 'file'" :size="30" /><h4>{{ documentQuery ? t('本页没有匹配的资料') : t('第一份团队知识，从这里开始') }}</h4><p>{{ documentQuery ? t('换个名称试试，筛选仅作用于当前页。') : canMaintain ? t('点击上方「添加知识资料」，保存原件并完成解析与发布。') : t('当前没有可访问文档，草稿、撤回或过期资料不会向只读成员展示。') }}</p><button v-if="documentQuery" @click="documentQuery = ''">{{ t('清空筛选') }}</button></div><div class="toolbar catalog-pagination"><small>{{ t('共 {count} 份当前可访问文档', { count: catalog.total }) }}</small><div><button :disabled="busy || catalog.pageNum <= 1" @click="refresh(catalog.pageNum - 1)">{{ t('上一页') }}</button><span>{{ t('第 {page} 页', { page: catalog.pageNum }) }}</span><button :disabled="busy || catalog.pageNum >= catalog.pages" @click="refresh(catalog.pageNum + 1)">{{ t('下一页') }}</button></div></div></template>
    <article v-if="selected" class="selected-document"><div class="document-detail-heading"><div><span class="detail-eyebrow">{{ t('文档详情 / 版本记录') }}</span><h4>{{ selected.title }}</h4><p>{{ t('文档 #{id} · 负责人 {owner} · 修订 {revision}', { id: selected.id, owner: ownerLabel, revision: selected.revision }) }}</p></div><span class="document-status" :class="{ published: !!selected.publishedVersionId }">{{ selected.publishedVersionId ? t('有发布版本') : t('未发布') }}</span></div>
      <details v-if="canMaintain" class="owner-settings"><summary>{{ t('负责人及文档管理') }}<AppIcon name="chevron" :size="13" /></summary><p class="owner-current">{{ t('当前负责人：') }}<strong>{{ ownerLabel }}</strong></p><form @submit.prevent="owner"><fieldset :disabled="busy || unknownWrite || !!vectorUncertain"><label>{{ t('负责人') }}<select v-if="memberOptions.length" v-model="ownerId" required><option v-for="member in memberOptions" :key="member.id" :value="member.id">{{ member.username }}（#{{ member.id }}）</option><option v-if="selected.ownerMemberId && !memberOptions.some(member => member.id === selected?.ownerMemberId)" :value="selected.ownerMemberId">{{ t('当前负责人（#{id}）', { id: selected.ownerMemberId }) }}</option></select><input v-else v-model="ownerId" inputmode="numeric" required :placeholder="t('成员编号')" /></label><button :disabled="!positiveId(ownerId)">{{ t('变更负责人') }}</button><button type="button" class="danger-button" @click="lifecycle(undefined, 'DELETE_DOCUMENT')">{{ t('删除整份文档') }}</button></fieldset></form></details>
      <ul class="version-list"><li v-for="version in versions?.records" :key="version.id"><div class="version-heading"><span class="version-number">V{{ version.versionNo }}</span><div><strong>{{ t('版本 {version} · {name}', { version: version.versionNo, name: version.originalName }) }}</strong><p class="version-state" :class="{ 'state-published': version.publicationStatus === 'PUBLISHED', 'state-failed': version.parseStatus === 'FAILED' }">{{ labels[version.parseStatus] }} / {{ labels[version.publicationStatus] }} · {{ t('{count} 个片段', { count: version.chunkCount }) }}<span v-if="version.failureCode"> · {{ version.failureCode }}</span></p></div></div><small>{{ t('生效：') }}{{ displayDate(version.validFromMs) }}<span class="date-divider">→</span>{{ t('失效：') }}{{ displayDate(version.validUntilMs) }}{{ t('（当前设备时区）') }}</small>
        <p class="version-guidance"><AppIcon name="info" :size="13" />{{ version.parseStatus === 'FAILED' ? t('解析未完成。请检查原件格式后，人工重试。') : version.parseStatus === 'PENDING' ? t('下一步：解析原件，检查内容后再发布。') : version.publicationStatus === 'PUBLISHED' ? t('已发布。资料访问仍受权限和有效期限制。') : version.publicationStatus === 'WITHDRAWN' ? t('已撤回。只读成员无法再查看这个版本。') : t('内容已准备好。先查看片段，确认无误后发布。') }}</p>
        <div class="actions version-actions"><button :disabled="busy" @click="download(version)"><AppIcon name="download" :size="13" />{{ t('下载原件') }}</button><button :disabled="busy || version.parseStatus !== 'READY'" @click="showChunks(version)">{{ t('查看片段') }}</button><template v-if="canMaintain"><button v-if="version.parseStatus !== 'READY' && version.publicationStatus === 'DRAFT'" class="primary-button" :disabled="busy || unknownWrite || !!vectorUncertain || !!pendingIngest" @click="parse(version)">{{ version.parseStatus === 'FAILED' ? t('人工重试解析') : t('解析并入库') }}</button><button v-if="version.parseStatus === 'READY' && version.publicationStatus !== 'PUBLISHED'" class="primary-button" :disabled="busy || unknownWrite || !!vectorUncertain" @click="lifecycle(version, 'PUBLISH')">{{ t('发布此版本') }}</button><details class="more-actions"><summary>{{ t('更多操作') }}<AppIcon name="chevron" :size="12" /></summary><div><button :disabled="busy" @click="readIngest(version)">{{ t('查看入库任务') }}</button><button :disabled="busy || unknownWrite || !!vectorUncertain || !!pendingIngest || version.parseStatus !== 'READY' || version.publicationStatus !== 'DRAFT'" @click="submitIngest(version, 'REBUILD')">{{ t('重建草稿片段') }}</button><button class="danger-button" :disabled="busy || unknownWrite || !!vectorUncertain" @click="lifecycle(version, 'WITHDRAW')">{{ t('撤回') }}</button><button class="danger-button" :disabled="busy || unknownWrite || !!vectorUncertain || selected.publishedVersionId === version.id" @click="lifecycle(version, 'DELETE_VERSION')">{{ t('删除此版本') }}</button></div></details></template></div>
      </li></ul>
      <section v-if="canMaintain" :aria-label="t('检索索引维护')">
        <details class="index-settings"><summary><AppIcon name="layers" :size="17" /><span>{{ t('检索索引') }}<small>{{ t('高级维护 · 平时不用管') }}</small></span><AppIcon name="chevron" :size="14" /></summary>
        <p class="index-plain"><strong>{{ t('这是什么：') }}</strong>{{ t('资料发布之后，系统要在后台为它建一份检索索引，问答才能搜到里面的内容。这个过程通常自动完成，你日常不需要进来。') }}</p>
        <p class="index-plain"><strong>{{ t('什么时候需要来：') }}</strong>{{ t('① 资料已发布，但问答里搜不到它；② 这里显示「建立失败」或「超时未完成」，需要重来一次。') }}</p>
        <div class="actions"><button v-for="version in versions?.records" :key="version.id" :disabled="busy || !!vectorUncertain" @click="openVector(version)">{{ t('处理版本 {version} 的检索索引', { version: version.versionNo }) }}</button></div>
        <template v-if="vectorVersion">
          <p class="index-plain">{{ t('正在处理版本 {version}。建立过程最长约 2 分钟，页面会自动刷新结果，最长等待约 2 分钟。', { version: vectorVersion.versionNo }) }}</p>
          <p class="index-plain">{{ t('如果中途刷新或离开页面，任务不会中断，只是这里不再显示进度。任务编号会自动填入下面的输入框（浏览器不保存它，重要的话先复制到别处）。回到同一文档和版本，点「按编号查进度」就能接着看。') }}</p>
          <label>{{ t('索引任务编号（提交后自动填入）') }}<input id="vector-task-id" v-model="vectorId" maxlength="36" :disabled="busy || !!pendingVector || vectorActive || !!vectorUncertain" :placeholder="t('提交建立后自动填入；重新查询时保持不动即可')" @input="stopVectorPolling(); vectorTask = undefined" /></label>
          <div class="actions"><button :disabled="busy || unknownWrite || !!vectorUncertain || vectorActive || vectorVersion.parseStatus !== 'READY' || selected.publishedVersionId !== vectorVersion.id" @click="submitVector">{{ t('为这个版本建立检索索引') }}</button><button :disabled="busy || !workspaceUuid(vectorId)" @click="readVector()">{{ t('按编号查进度') }}</button><button :disabled="busy || !vectorActive || !!vectorUncertain" @click="cancelVector">{{ t('取消建立') }}</button><button @click="stopVectorPolling(); vectorNotice = t('已停止在这里刷新进度；后台任务仍会继续跑完。')">{{ t('停止刷新进度') }}</button></div>
          <p v-if="vectorNotice" class="feedback vector-notice" role="status">{{ vectorNotice }}</p>
          <p v-if="vectorUncertain" class="feedback error">{{ t('上次操作结果还没确认：{action}。刷新页面不会解除这个限制。', { action: vectorUncertain === 'TASK' ? t('请先按编号查进度') : t('请先查看当前索引状态') }) }}</p>
          <div v-if="vectorTask" class="vector-job"><p>{{ indexJobLabels[vectorTask.status] ?? vectorTask.status }} · {{ vectorTask.message }}</p><small>{{ t('任务编号：{id}', { id: vectorTask.id }) }} · {{ t('已完成 {done}/{total} 个片段', { done: vectorTask.verifiedChunks, total: vectorTask.expectedChunks }) }}<span v-if="vectorTask.failureCode"> · {{ t('失败原因 {code}', { code: vectorTask.failureCode }) }}</span></small></div>
          <div class="actions"><button :disabled="busy" @click="readPointer">{{ t('查看当前是否已启用检索') }}</button><button :disabled="busy || unknownWrite || !!vectorUncertain || !vectorPointer || vectorTask?.status !== 'VERIFIED' || selected.publishedVersionId !== vectorTask.versionId" @click="changeVectorPointer('publish')">{{ t('确认启用检索') }}</button><button :disabled="busy || unknownWrite || !!vectorUncertain || !vectorPointer || !['READY', 'STALE'].includes(vectorPointer.status)" @click="changeVectorPointer('withdraw')">{{ t('关闭检索') }}</button></div>
          <p v-if="vectorPointer" class="vector-publication"><strong>{{ indexPointerLabels[vectorPointer.status] ?? vectorPointer.status }}</strong><small>{{ t('第 {revision} 次变更', { revision: vectorPointer.revision }) }}{{ vectorPointer.updatedMs ? ' · ' + new Date(Number(vectorPointer.updatedMs)).toLocaleString('zh-CN') : '' }}</small></p>
        </template></details>
      </section>
      <div v-if="versions" class="toolbar"><button :disabled="busy || versions.pageNum <= 1" @click="versionPage(versions.pageNum - 1)">{{ t('更早页') }}</button><span>{{ t('版本第 {page} 页', { page: versions.pageNum }) }}</span><button :disabled="busy || versions.pageNum >= versions.pages" @click="versionPage(versions.pageNum + 1)">{{ t('更多版本') }}</button></div>
    </article>
    <details v-if="fragments && fragmentVersion" class="fragment-panel" :open="fragmentOpen" @toggle="onFragmentToggle" aria-label="文档片段"><summary><span class="fragment-summary-icon"><AppIcon name="file" :size="18" /></span><span><strong>{{ t('原文片段 · 版本 {version}', { version: fragmentVersion.versionNo }) }}</strong><small>{{ t('用于核对切分、行号与字符范围，阅读全文请点上方资料卡片') }}</small></span><AppIcon name="chevron" :size="15" /></summary><div class="fragment-body"><article v-for="part in fragments.records" :key="part.id"><small>{{ t('片段 {index} · 第{start}—{end}行 · 字符[{from}, {to})', { index: part.chunkIndex, start: part.startLine, end: part.endLine, from: part.startOffset, to: part.endOffset }) }}</small><pre>{{ part.content }}</pre></article><div class="toolbar"><button :disabled="busy || fragments.pageNum <= 1" @click="showChunks(fragmentVersion, fragments.pageNum - 1)">{{ t('上一页片段') }}</button><button :disabled="busy || fragments.pageNum >= fragments.pages" @click="showChunks(fragmentVersion, fragments.pageNum + 1)">{{ t('下一页片段') }}</button></div></div></details>

    <Teleport to="body">
      <div v-if="readerOpen && selected" class="reader-layer">
        <div class="reader-backdrop" @click="closeReader"></div>
        <article class="reader-surface" role="dialog" aria-modal="true" :aria-label="selected.title">
          <header class="reader-head">
            <div>
              <span class="reader-kicker">KNOWLEDGE READER</span>
              <h2>{{ selected.title }}</h2>
              <p v-if="readerVersion">{{ t('版本 V{version} · {name} · {count} 个片段', { version: readerVersion.versionNo, name: readerVersion.originalName, count: readerVersion.chunkCount }) }}</p>
              <p v-else>{{ t('当前没有可阅读的有效版本') }}</p>
            </div>
            <div class="reader-tools">
              <button type="button" :disabled="busy || !readerVersion" @click="downloadReader"><AppIcon name="download" :size="16" />{{ t('下载原件') }}</button>
              <button type="button" class="reader-close" :aria-label="t('关闭阅读')" @click="closeReader"><AppIcon name="close" :size="20" /></button>
            </div>
          </header>
          <div class="reader-body">
            <div v-if="readerLoading && !readerChunks.length" class="reader-placeholder"><span class="reader-spinner"></span><p>{{ t('正在读取正文…') }}</p></div>
            <div v-else-if="readerError && !readerChunks.length" class="reader-placeholder"><AppIcon name="info" :size="38" /><h3>{{ t('正文读取失败') }}</h3><p>{{ readerError }}</p><button type="button" @click="readerVersion && loadReader(readerVersion, 1, true)">{{ t('重新读取') }}</button></div>
            <div v-else-if="!readerChunks.length" class="reader-placeholder"><AppIcon name="file" :size="38" /><h3>{{ t('暂时没有可阅读的正文') }}</h3><p>{{ selected.publishedVersionId ? t('这份资料还没有完成解析的发布版本，请联系资料维护者。') : t('这份资料尚未发布，普通成员看不到内容。') }}</p></div>
            <div v-else class="reader-content">
              <section v-for="part in readerChunks" :key="part.id" class="reader-chunk"><span class="reader-chunk-index">{{ t('片段 {index} · 第 {start}—{end} 行', { index: part.chunkIndex, start: part.startLine, end: part.endLine }) }}</span><p class="reader-text">{{ part.content }}</p></section>
              <p v-if="readerError" class="reader-inline-error">{{ readerError }}</p>
              <button v-if="readerPage < readerPages" type="button" class="reader-more" :disabled="readerLoading" @click="moreReader">{{ readerLoading ? t('正在加载…') : t('继续阅读下一页（{page} / {pages}）', { page: readerPage, pages: readerPages }) }}</button>
              <p v-else class="reader-end">{{ t('已读完当前允许访问的全部 {count} 个片段。', { count: readerChunks.length }) }}</p>
            </div>
          </div>
          <aside class="reader-rail">
            <div class="rail-block"><span class="rail-label">{{ t('文档编号') }}</span><strong>#{{ selected.id }}</strong><small>{{ t('修订 {revision}', { revision: selected.revision }) }}</small></div>
            <div class="rail-block"><span class="rail-label">{{ t('负责人') }}</span><strong>{{ ownerLabel }}</strong><small>{{ t('负责维护这份资料') }}</small></div>
            <div v-if="readerVersion" class="rail-block"><span class="rail-label">{{ t('当前版本') }}</span><strong>V{{ readerVersion.versionNo }}</strong><small>{{ labels[readerVersion.publicationStatus] }} · {{ t('{count} 个片段', { count: readerVersion.chunkCount }) }}</small></div>
            <div v-if="readerVersion" class="rail-block"><span class="rail-label">{{ t('有效期') }}</span><strong>{{ displayDate(readerVersion.validFromMs) }}</strong><small>{{ t('至 {date}', { date: displayDate(readerVersion.validUntilMs) }) }}</small></div>
            <div class="rail-block"><span class="rail-label">{{ t('阅读进度') }}</span><strong>{{ t('{count} 个片段', { count: readerChunks.length }) }}</strong><small v-if="readerPages">{{ t('第 {page} / {pages} 页', { page: readerPage, pages: readerPages }) }}</small><small v-else>{{ t('尚未读取正文') }}</small></div>
            <div class="rail-block"><span class="rail-label">{{ t('访问说明') }}</span><small class="rail-note">{{ t('只展示当前权限允许访问的有效版本。上传、解析与发布等管理操作在资料库下方。') }}</small></div>
            <button v-if="canMaintain" type="button" class="rail-admin" :disabled="busy" @click="closeReader">{{ t('回到资料库管理区') }}<AppIcon name="chevron" :size="15" /></button>
          </aside>
        </article>
      </div>
    </Teleport>
  </section>
</template>

<style scoped>
.document-manager { padding: 24px; border: 1px solid var(--brand-100); border-radius: 0 0 12px 12px; background: var(--n-0); color: var(--brand-700); font-size: var(--fs-xs); }
.toolbar, .actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.toolbar { justify-content: space-between; }
h3 { font-size: var(--fs-lg); font-weight: 600; margin: 0; } h4 { font-size: var(--fs-base); font-weight: 600; overflow-wrap: anywhere; }
p, small { line-height: 1.8; overflow-wrap: anywhere; } small { display: block; color: var(--n-500); font-size: var(--fs-2xs); }
.document-heading h3 { display: flex; align-items: center; gap: 8px; }.document-heading p { margin: 7px 0 0; font-size: var(--fs-2xs); color: var(--n-500); }.document-heading > button { font-size: var(--fs-2xs); padding: 8px 11px; }
.total-pill { font-size: var(--fs-2xs); padding: 2px 6px; border-radius: 4px; background: var(--n-50); color: var(--brand-500); font-weight: 500; }
.knowledge-steps { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); list-style: none; padding: 18px 20px; margin: 24px 0 18px; background: var(--n-25); border: 1px solid var(--n-50); border-radius: 9px; gap: 16px; }
.knowledge-steps li { display: flex; align-items: center; gap: 12px; position: relative; }.knowledge-steps li:not(:last-child)::after { content: '›'; color: var(--n-150); position: absolute; right: 5px; top: 7px; font-size: var(--fs-xl); }
.knowledge-steps li > span { font-size: var(--fs-2xs); color: var(--brand-400); background: var(--n-50); width: 28px; height: 28px; border-radius: 50%; display: grid; place-items: center; flex-shrink: 0; }.knowledge-steps strong { font-weight: 550; font-size: var(--fs-2xs); }.knowledge-steps small { font-size: var(--fs-2xs); margin-top: 3px; }
.feedback { padding: 10px 13px; border: 1px solid var(--brand-100); border-radius: 7px; background: var(--brand-50); color: var(--brand-500); font-size: var(--fs-2xs); }.error { color: var(--amber-500); border-color: var(--warning-border); background: var(--amber-soft); }
details { border: 1px solid var(--brand-100); border-radius: 9px; background: var(--n-0); margin: 14px 0; }
summary { display: flex; align-items: center; gap: 9px; list-style: none; cursor: pointer; padding: 14px 16px; font-size: var(--fs-2xs); color: var(--brand-500); } summary::-webkit-details-marker { display: none; }
summary > svg:last-child { margin-left: auto; transition: transform .15s; } details[open] > summary > svg:last-child { transform: rotate(90deg); }
.space-settings > summary { background: var(--n-25); border-radius: 9px; }.space-settings > summary small { margin-left: auto; font-size: var(--fs-2xs); }.space-settings form { padding: 0 18px 12px; }.space-settings > ul { padding: 0 18px 18px 36px; }.space-settings > form + form { border-top: 1px solid var(--n-50); padding-top: 12px; }
fieldset { border: 0; padding: 0; margin: 0; min-width: 0; } legend { font-size: var(--fs-xs); font-weight: 550; margin-bottom: 12px; }
label { display: block; margin: 12px 0; font-size: var(--fs-2xs); } input:not([type=checkbox]), select { display: block; width: 100%; min-width: 0; box-sizing: border-box; max-width: 100%; margin-top: 7px; border: 1px solid var(--brand-100); padding: 10px 12px; border-radius: 7px; color: inherit; background: white; font: inherit; }
button { display: inline-flex; align-items: center; justify-content: center; gap: 6px; border: 1px solid var(--brand-100); border-radius: 7px; padding: 9px 12px; background: var(--n-0); color: var(--brand-600); cursor: pointer; font-size: var(--fs-2xs); transition: background .15s, border-color .15s; }
button:hover:not(:disabled) { background: var(--brand-50); border-color: var(--brand-300); } button:focus-visible, summary:focus-visible { outline: 2px solid var(--brand-500); outline-offset: 3px; }
button.primary-button { background: var(--brand-700); border-color: var(--brand-700); color: white; } button.primary-button:hover:not(:disabled) { background: var(--brand-700); }
button.danger-button { color: var(--amber-500); border-color: var(--warning-border); } button.danger-button:hover:not(:disabled) { background: var(--amber-soft); border-color: var(--amber-400); }
button:disabled, fieldset:disabled { opacity: .45; cursor: not-allowed; } ul { padding-left: 18px; } .document-list, .version-list { list-style: none; padding: 0; margin: 0; }
.upload-panel { margin: 16px 0 26px; border-color: var(--brand-200); background: var(--n-0); }.upload-panel > summary { gap: 11px; }.upload-panel > summary strong { font-size: var(--fs-xs); font-weight: 550; }.upload-panel > summary small { font-size: var(--fs-2xs); margin-top: 4px; }
.upload-summary-icon { width: 35px; height: 35px; display: grid; place-items: center; background: var(--brand-100); color: var(--brand-500); border-radius: 8px; }
.upload-panel form { padding: 0 20px 20px; }.upload-panel legend { display: none; }
.file-drop { display: flex; position: relative; flex-direction: column; align-items: center; justify-content: center; gap: 8px; min-height: 170px; padding: 24px 18px; border: 1px dashed var(--brand-300); border-radius: 9px; background: var(--n-25); text-align: center; cursor: pointer; overflow-wrap: anywhere; }
.file-drop:hover { border-color: var(--brand-400); background: var(--n-50); }.file-drop:focus-within { outline: 2px solid var(--brand-500); outline-offset: 3px; }.upload-art { color: var(--brand-400); background: var(--n-50); width: 48px; height: 48px; display: grid; place-items: center; border-radius: 12px; margin-bottom: 5px; }
.file-drop strong { font-size: var(--fs-xs); font-weight: 550; max-width: 100%; }.file-drop > span:not(.upload-art) { font-size: var(--fs-2xs); color: var(--n-250); }.file-drop input[type=file] { position: absolute; inset: 0; width: 100%; height: 100%; opacity: 0; cursor: pointer; margin: 0; }
.upload-options { border: 0; background: transparent; margin: 12px 0; }.upload-options > summary { padding: 8px 0; font-size: var(--fs-2xs); }.upload-options > summary svg { margin-left: 0; }.check-label { display: flex; gap: 7px; align-items: center; }
.upload-footer { display: flex; align-items: center; justify-content: space-between; gap: 20px; padding-top: 14px; margin-top: 16px; border-top: 1px solid var(--n-50); }.upload-footer small { font-size: var(--fs-2xs); }.upload-footer small svg { margin-right: 4px; }
.catalog-heading { display: flex; align-items: center; justify-content: space-between; gap: 15px; margin: 24px 0 16px; }.catalog-heading h4 { display: flex; align-items: center; gap: 8px; margin: 0 0 4px; font-size: var(--fs-sm); }
.document-search { display: flex; align-items: center; gap: 8px; border: 1px solid var(--n-100); padding: 0 11px; border-radius: 7px; margin: 0; color: var(--brand-400); width: 210px; }.document-search input { border: 0; background: transparent; margin: 0; padding: 10px 0; font-size: var(--fs-2xs); }.document-search:focus-within { outline: 2px solid var(--brand-500); outline-offset: 2px; }.document-search input:focus-visible { outline: 0; }
.document-list { border: 1px solid var(--n-100); border-radius: 9px; overflow: hidden; }.document-list:empty { display: none; }.document-list li + li { border-top: 1px solid var(--n-50); }
.document-list button { display: flex; gap: 12px; text-align: left; width: 100%; overflow-wrap: anywhere; padding: 16px; border: 0; border-radius: 0; }.document-list button:hover:not(:disabled), .document-list button.selected { background: var(--n-50); }.file-symbol { width: 36px; height: 40px; flex-shrink: 0; display: grid; place-items: center; background: var(--n-50); border-radius: 7px; color: var(--n-500); }.document-name { flex: 1; min-width: 0; }.document-name strong { font-weight: 550; color: var(--brand-600); font-size: var(--fs-xs); }.document-name small { margin-top: 6px; font-size: var(--fs-2xs); color: var(--n-250); }
.document-status { flex-shrink: 0; display: inline-block; background: var(--warning-border); color: var(--amber-500); font-size: var(--fs-2xs); padding: 5px 8px; border-radius: 5px; }.document-status.published { background: var(--brand-100); color: var(--brand-500); }.document-list button > svg:last-child { margin-left: 5px; color: var(--n-250); }
.catalog-pagination { padding: 13px 0; }.catalog-pagination > div { display: flex; align-items: center; gap: 10px; }.catalog-pagination span { font-size: var(--fs-2xs); color: var(--brand-400); }.catalog-pagination button { font-size: var(--fs-2xs); padding: 6px 10px; }
.documents-empty { padding: 38px 18px; border: 1px dashed var(--n-100); border-radius: 9px; color: var(--n-250); text-align: center; background: var(--n-25); }.documents-empty h4 { margin: 14px 0 8px; font-size: var(--fs-sm); color: var(--brand-500); }.documents-empty p { font-size: var(--fs-2xs); color: var(--brand-400); margin: 0; }
.selected-document { margin-top: 24px; border-top: 1px solid var(--n-100); padding-top: 25px; }.document-detail-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; }.document-detail-heading h4 { font-size: var(--fs-xl); letter-spacing: -.3px; margin: 9px 0 6px; }.document-detail-heading p { color: var(--brand-400); font-size: var(--fs-2xs); margin: 0; }.detail-eyebrow { font-size: var(--fs-2xs); color: var(--n-250); letter-spacing: .5px; }
.owner-settings { border: 0; background: var(--n-25); }.owner-settings > summary { font-size: var(--fs-2xs); padding: 11px 14px; }.owner-settings form { padding: 0 14px 14px; }.owner-settings button + button { margin-left: 8px; }
.version-list li { padding: 18px; margin: 14px 0; border: 1px solid var(--n-100); border-radius: 9px; background: var(--n-0); }.version-heading { display: flex; gap: 12px; align-items: flex-start; }.version-heading > div { min-width: 0; }.version-number { width: 32px; height: 34px; flex-shrink: 0; display: grid; place-items: center; background: var(--n-50); border-radius: 7px; color: var(--n-500); font-size: var(--fs-2xs); }.version-heading strong { font-size: var(--fs-xs); font-weight: 550; overflow-wrap: anywhere; }.version-state { color: var(--amber-500); font-size: var(--fs-2xs); margin: 7px 0 12px; }.version-state.state-published { color: var(--brand-500); }.version-state.state-failed { color: var(--amber-500); }.version-list li > small { font-size: var(--fs-2xs); padding-bottom: 14px; }.date-divider { padding: 0 9px; color: var(--n-200); }
.version-actions { border-top: 1px solid var(--n-50); padding-top: 13px; align-items: flex-start; }.version-actions > button { font-size: var(--fs-2xs); padding: 8px 11px; }
.more-actions { border: 0; background: transparent; margin: 0; }.more-actions > summary { padding: 9px 10px; font-size: var(--fs-2xs); }.more-actions > div { display: flex; flex-wrap: wrap; gap: 7px; padding: 8px 0; }.more-actions button { font-size: var(--fs-2xs); }
.index-settings { margin-top: 20px; background: var(--n-25); }.index-settings > summary { font-size: var(--fs-2xs); }.index-settings > summary small { font-size: var(--fs-2xs); margin-top: 3px; }.index-settings > :not(summary) { margin-left: 16px; margin-right: 16px; }.index-settings p { font-size: var(--fs-2xs); line-height: 1.9; color: var(--brand-400); }.index-settings > :last-child { margin-bottom: 16px; }
.vector-job, .vector-publication { padding: 13px; background: var(--n-50); border: 1px solid var(--n-100); border-radius: 7px; overflow-wrap: anywhere; }
.date-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; } pre { white-space: pre-wrap; overflow-wrap: anywhere; padding: 16px; background: var(--n-25); border: 1px solid var(--n-50); border-radius: 8px; font: inherit; line-height: 1.9; color: var(--brand-500); }
section[aria-label="文档片段"] { margin-top: 24px; padding-top: 14px; border-top: 1px solid var(--n-50); } section[aria-label="文档片段"] > h4 { font-size: var(--fs-sm); } section[aria-label="文档入库任务"] { padding: 16px; background: var(--n-25); border: 1px solid var(--n-100); border-radius: 9px; }
.version-guidance { display: flex; align-items: flex-start; gap: 6px; color: var(--brand-500); font-size: var(--fs-xs); margin: 0 0 14px; }.version-guidance > svg { flex-shrink: 0; margin-top: 3px; }
.document-manager { font-size: var(--fs-sm); } small, .feedback, label, .document-heading p, .catalog-pagination small, .document-detail-heading p, .version-state, .version-list li > small, .document-name small { font-size: var(--fs-xs); }
button, .document-heading > button, summary, .version-actions > button, .more-actions > summary, .more-actions button, .owner-settings > summary, .space-settings > summary, .document-search input { font-size: var(--fs-xs); }
.document-name strong, .version-heading strong, .upload-panel > summary strong { font-size: var(--fs-base); }.document-heading h3 { font-size: var(--fs-xl); }.catalog-heading h4 { font-size: var(--fs-lg); }.document-status, .upload-panel > summary small, .file-drop > span:not(.upload-art), .upload-footer small { font-size: var(--fs-2xs); }
@media(max-width: 780px) { .knowledge-steps { padding: 14px; gap: 9px; }.knowledge-steps li { gap: 7px; }.knowledge-steps li::after { display: none; }.knowledge-steps small { display: none; }.knowledge-steps li > span { width: 23px; height: 23px; font-size: var(--fs-2xs); }.knowledge-steps strong { font-size: var(--fs-2xs); } }
@media(max-width: 650px) { .document-manager { padding: 18px 13px; }.date-grid { grid-template-columns: 1fr; gap: 0; }.document-heading { align-items: flex-start; }.document-heading h3 { font-size: var(--fs-base); }.document-heading > button { font-size: var(--fs-2xs); }.space-settings > summary small { display: none; }.upload-panel form { padding: 0 13px 14px; }.file-drop { min-height: 150px; padding: 20px 12px; }.file-drop strong { font-size: var(--fs-2xs); }.file-drop > span:not(.upload-art) { font-size: var(--fs-2xs); }.upload-footer { flex-direction: column; align-items: stretch; gap: 12px; }.catalog-heading { flex-direction: column; align-items: stretch; }.document-search { width: 100%; }.document-list button { padding: 12px 10px; gap: 8px; }.document-status { font-size: var(--fs-2xs); padding: 4px 6px; }.file-symbol { width: 29px; height: 35px; }.document-name strong { font-size: var(--fs-2xs); }.document-list button > svg:last-child { display: none; }.document-detail-heading h4 { font-size: var(--fs-lg); }.version-list li { padding: 14px 12px; }.version-actions > button { font-size: var(--fs-2xs); padding: 8px; }.catalog-pagination { gap: 10px; }.catalog-pagination small { width: 100%; }.document-detail-heading { flex-wrap: wrap; } }
.document-manager { border-radius: 17px; border-color: var(--brand-100); box-shadow: 0 4px 20px #234d3603; }.knowledge-steps { background: linear-gradient(110deg,var(--brand-50),var(--n-25)); border-radius: 12px; }.knowledge-steps li > span { width: 34px; height: 36px; border-radius: 10px; color: var(--brand-500); background: var(--brand-100); }.knowledge-steps li:nth-child(2) > span { background: var(--warning-border); color: var(--amber-500); }.knowledge-steps li:nth-child(3) > span { background: var(--info-border); color: var(--blue-500); }.document-list button { border-radius: 11px; padding: 17px; }.upload-panel { border-radius: 12px; background: var(--n-25); }.version-list li { border-radius: 13px; }.version-state { color: var(--brand-500); }.document-heading > button { font-size: var(--fs-2xs); }
/* 资料浏览优先：卡片资料库排在管理、上传与版本详情之前 */
.document-manager { display: flex; flex-direction: column; padding: clamp(22px, 2.4vw, 38px); background: var(--n-25); }
.document-heading { order: 1; padding-bottom: 18px; }
.document-heading h3 { font-size: var(--fs-sm); font-weight: 600; color: var(--brand-400); letter-spacing: .2px; }
.document-heading p { display: none; }
.document-heading > button { padding: 8px 12px; }
.catalog-heading { order: 2; }
.document-grid { order: 3; }
.documents-empty { order: 4; }
.catalog-pagination { order: 5; }
.feedback { order: 6; }
.knowledge-steps { order: 7; }
section[aria-label="文档入库任务"] { order: 8; }
.space-settings { order: 9; }
.upload-panel { order: 10; }
.selected-document { order: 11; }
section[aria-label="文档片段"], .fragment-panel { order: 12; }

.catalog-heading { display: flex; align-items: flex-end; justify-content: space-between; gap: 26px; margin: 6px 0 24px; }
.catalog-intro { min-width: 0; }
.catalog-kicker { display: block; margin-bottom: 12px; font-size: var(--fs-2xs); font-weight: 600; letter-spacing: 2.6px; color: var(--brand-400); }
.catalog-intro h3 { display: flex; align-items: center; gap: 16px; margin: 0; font-size: clamp(22px, 2.1vw, 30px); line-height: 1.4; font-weight: 600; letter-spacing: -.7px; color: var(--brand-800); }
.catalog-count { display: inline-flex; align-items: center; justify-content: center; min-width: 42px; height: 34px; padding: 0 14px; border-radius: 999px; background: var(--brand-100); color: var(--brand-500); font-size: var(--fs-md); font-weight: 600; letter-spacing: 0; }
.catalog-intro p { margin: 10px 0 0; font-size: var(--fs-sm); color: var(--n-250); }
.document-search { display: flex; align-items: center; gap: 12px; width: min(400px, 100%); height: 54px; padding: 0 20px; border: 1px solid var(--brand-100); border-radius: 16px; background: var(--n-0); color: var(--brand-400); box-shadow: 0 4px 14px rgba(35, 77, 54, .04); transition: border-color .15s, box-shadow .15s; }
.document-search:focus-within { border-color: var(--brand-300); box-shadow: 0 0 0 4px rgba(122, 168, 137, .14); outline: none; }
.document-search input { height: 52px; margin: 0; padding: 0; border: 0; background: transparent; font-size: var(--fs-base); color: var(--brand-800); }
.document-search input:focus-visible { outline: none; }

.document-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(272px, 1fr)); gap: 20px; margin: 0 0 8px; padding: 0; list-style: none; }
.document-grid li { display: flex; min-width: 0; }
.document-card { display: flex; flex-direction: column; align-items: stretch; gap: 12px; width: 100%; height: 100%; min-height: 244px; padding: 26px 24px 20px; border: 1px solid var(--brand-100); border-radius: 18px; background: linear-gradient(158deg, var(--n-0) 0%, var(--n-25) 100%); text-align: left; box-shadow: 0 3px 12px rgba(35, 77, 54, .03); transition: transform .18s ease, box-shadow .18s ease, border-color .18s ease; }
.document-card:hover:not(:disabled) { transform: translateY(-4px); border-color: var(--brand-200); box-shadow: 0 16px 34px rgba(30, 66, 45, .09); background: var(--n-0); }
.document-card.selected { border-color: var(--brand-400); box-shadow: 0 0 0 3px rgba(123, 168, 137, .15), 0 12px 28px rgba(30, 66, 45, .07); }
.document-card-top { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.document-grid .file-symbol { width: 56px; height: 60px; border-radius: 15px; background: var(--brand-50); color: var(--brand-500); }
.document-grid .file-symbol.tone-1 { background: var(--warning-border); color: var(--amber-500); }
.document-grid .file-symbol.tone-2 { background: var(--blue-soft); color: var(--blue-500); }
.document-grid .document-status { padding: 6px 11px; border-radius: 999px; font-size: var(--fs-2xs); }
.document-card-title { font-size: var(--fs-xl); font-weight: 600; line-height: 1.55; letter-spacing: -.35px; color: var(--brand-800); }
.document-card-meta { font-size: var(--fs-xs); color: var(--brand-400); }
.document-card-description { flex: 1; margin: 2px 0 0; font-size: var(--fs-sm); line-height: 1.9; color: var(--n-500); }
.document-card-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding-top: 15px; border-top: 1px solid var(--brand-50); font-size: var(--fs-xs); font-weight: 550; color: var(--brand-500); }
.document-card-footer > span { display: inline-flex; align-items: center; gap: 5px; }
.documents-empty { padding: 64px 24px; border-radius: 18px; }
.documents-empty h4 { margin: 18px 0 10px; font-size: var(--fs-xl); }
.documents-empty p { font-size: var(--fs-sm); }
.catalog-pagination { padding: 18px 0 4px; border-top: 1px solid var(--brand-50); }
.catalog-pagination button { padding: 8px 14px; font-size: var(--fs-xs); }
.catalog-pagination span { font-size: var(--fs-xs); }

/* 全屏阅读层：正文优先，元信息退到右侧栏 */
.reader-layer { position: fixed; inset: 0; z-index: 120; display: flex; align-items: center; justify-content: center; padding: clamp(14px, 3vw, 44px); font-family: "Segoe UI", "Microsoft YaHei", "PingFang SC", sans-serif; }
.reader-backdrop { position: absolute; inset: 0; background: rgba(20, 38, 30, .5); backdrop-filter: blur(6px); }
.reader-surface { position: relative; display: grid; grid-template-columns: minmax(0, 1fr) 306px; grid-template-rows: auto minmax(0, 1fr); width: min(1260px, 100%); height: min(880px, 100%); border-radius: 26px; background: var(--n-25); box-shadow: 0 44px 110px rgba(12, 30, 20, .38); overflow: hidden; animation: reader-rise .22s ease-out; }
@keyframes reader-rise { from { opacity: 0; transform: translateY(14px) scale(.99); } to { opacity: 1; transform: none; } }
.reader-head { grid-column: 1 / -1; display: flex; align-items: flex-start; justify-content: space-between; gap: 26px; padding: 30px clamp(24px, 3vw, 40px) 24px; border-bottom: 1px solid var(--brand-100); background: linear-gradient(135deg, var(--brand-50), var(--n-25)); }
.reader-kicker { display: block; font-size: var(--fs-2xs); font-weight: 600; letter-spacing: 2.6px; color: var(--brand-400); }
.reader-head h2 { max-width: 880px; margin: 11px 0 8px; font-size: clamp(23px, 2.2vw, 32px); line-height: 1.45; font-weight: 600; letter-spacing: -.7px; color: var(--brand-800); overflow-wrap: anywhere; }
.reader-head p { margin: 0; font-size: var(--fs-xs); line-height: 1.8; color: var(--brand-400); overflow-wrap: anywhere; }
.reader-tools { display: flex; align-items: center; gap: 12px; flex-shrink: 0; }
.reader-tools > button { padding: 11px 16px; border-radius: 11px; font-size: var(--fs-xs); background: var(--n-0); }
.reader-tools > .reader-close { width: 44px; height: 44px; padding: 0; border-radius: 50%; background: var(--brand-50); color: var(--brand-500); }
.reader-tools > .reader-close:hover:not(:disabled) { background: var(--brand-100); border-color: var(--brand-200); }
.reader-body { min-height: 0; padding: 36px clamp(24px, 3.4vw, 54px) 56px; overflow-y: auto; }
.reader-content { display: flex; flex-direction: column; gap: 30px; max-width: 760px; margin: 0 auto; }
.reader-chunk + .reader-chunk { padding-top: 28px; border-top: 1px dashed var(--brand-100); }
.reader-chunk-index { display: block; font-size: var(--fs-2xs); letter-spacing: 1.6px; color: var(--brand-400); }
.reader-text { margin: 12px 0 0; font-size: var(--fs-lg); line-height: 2.15; color: var(--brand-700); white-space: pre-wrap; overflow-wrap: anywhere; }
.reader-more { align-self: center; padding: 13px 28px; border-radius: 999px; border-color: var(--brand-700); background: var(--brand-700); color: var(--n-0); font-size: var(--fs-sm); }
.reader-more:hover:not(:disabled) { border-color: var(--brand-700); background: var(--brand-700); }
.reader-end { align-self: center; margin: 0; font-size: var(--fs-xs); color: var(--brand-400); }
.reader-inline-error { margin: 0; padding: 12px 16px; border-radius: 10px; background: var(--amber-soft); color: var(--danger); font-size: var(--fs-xs); }
.reader-placeholder { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 14px; min-height: 320px; text-align: center; color: var(--brand-400); }
.reader-placeholder h3 { margin: 0; font-size: var(--fs-xl); font-weight: 600; color: var(--brand-600); }
.reader-placeholder p { margin: 0; max-width: 420px; font-size: var(--fs-sm); line-height: 1.9; color: var(--n-250); }
.reader-placeholder button { padding: 10px 20px; border-radius: 999px; }
.reader-spinner { width: 34px; height: 34px; border-radius: 50%; border: 3px solid var(--brand-100); border-top-color: var(--brand-500); animation: reader-spin .8s linear infinite; }
@keyframes reader-spin { to { transform: rotate(360deg); } }
.reader-rail { display: flex; flex-direction: column; gap: 20px; padding: 30px 26px 34px; border-left: 1px solid var(--brand-100); background: var(--n-25); overflow-y: auto; }
.rail-block { display: flex; flex-direction: column; gap: 6px; }
.rail-label { font-size: var(--fs-2xs); font-weight: 600; letter-spacing: 1.8px; color: var(--brand-400); }
.rail-block strong { font-size: var(--fs-md); font-weight: 600; color: var(--brand-700); overflow-wrap: anywhere; }
.rail-block small { font-size: var(--fs-xs); line-height: 1.85; color: var(--brand-400); }
.rail-note { color: var(--brand-400); }
.rail-admin { justify-content: space-between; margin-top: auto; padding: 12px 16px; border-radius: 12px; font-size: var(--fs-xs); }

/* 下方管理区：与资料库统一为宽卡片、大字号，避免重新变回密集列表 */
.selected-document { margin-top: 40px; padding: 30px clamp(20px, 2.4vw, 34px); border: 1px solid var(--brand-100); border-radius: 20px; background: var(--n-0); box-shadow: 0 3px 14px rgba(35, 77, 54, .03); }
.document-detail-heading h4 { margin: 12px 0 8px; font-size: clamp(21px, 2vw, 27px); letter-spacing: -.6px; color: var(--brand-800); }
.document-detail-heading p { font-size: var(--fs-sm); color: var(--n-250); }
.detail-eyebrow { font-size: var(--fs-2xs); font-weight: 600; letter-spacing: 2px; color: var(--brand-400); }
.selected-document .document-status { padding: 7px 13px; border-radius: 999px; font-size: var(--fs-xs); }
.owner-settings { margin-top: 20px; border: 1px solid var(--brand-100); border-radius: 14px; background: var(--n-25); }
.owner-settings > summary { padding: 15px 18px; font-size: var(--fs-xs); }
.owner-settings form { padding: 0 18px 18px; }
.owner-settings fieldset > label { max-width: 320px; }
.owner-current { margin: 4px 0 14px; font-size: var(--fs-xs); color: var(--n-500); }
.owner-current strong { color: var(--brand-700); font-weight: 600; }
.version-list { margin: 8px 0 0; }
.version-list li { margin: 18px 0; padding: 24px 26px; border-radius: 16px; }
.version-number { width: 44px; height: 48px; border-radius: 12px; font-size: var(--fs-xs); }
.version-heading strong { font-size: var(--fs-md); }
.version-state { margin: 8px 0 14px; font-size: var(--fs-xs); }
.version-list li > small { padding-bottom: 16px; font-size: var(--fs-xs); }
.version-guidance { font-size: var(--fs-sm); }
.version-actions { padding-top: 16px; gap: 10px; }
.version-actions > button { padding: 10px 15px; font-size: var(--fs-xs); }
.more-actions > summary { padding: 10px 12px; font-size: var(--fs-xs); }
.more-actions button { padding: 9px 12px; font-size: var(--fs-xs); }
.index-settings { margin-top: 24px; border-radius: 14px; }
.index-settings > summary { padding: 16px 18px; font-size: var(--fs-xs); }
.index-settings > summary small { font-size: var(--fs-xs); }
.index-settings p { font-size: var(--fs-xs); }
/* 说明段落：把「什么时候需要来」讲清楚，避免维护区看起来像必填流程 */
.index-settings .index-plain { color: var(--text-secondary); line-height: var(--lh-base); }
.index-settings .index-plain strong { color: var(--text-primary); font-weight: var(--fw-semibold); }
.vector-publication strong { display: block; color: var(--text-primary); }
.vector-publication small { display: block; margin-top: 4px; color: var(--text-tertiary); }
.selected-document > .toolbar { padding-top: 16px; }
.selected-document > .toolbar button { padding: 9px 16px; font-size: var(--fs-xs); }
.selected-document > .toolbar span { font-size: var(--fs-xs); }
.space-settings { border-radius: 14px; }
.space-settings > summary { padding: 17px 20px; font-size: var(--fs-sm); }
.space-settings > summary small { font-size: var(--fs-xs); }
.upload-panel { border-radius: 16px; }
.upload-panel > summary { padding: 18px 20px; }
.upload-panel > summary strong { font-size: var(--fs-base); }
.upload-panel > summary small { font-size: var(--fs-xs); }
.knowledge-steps { padding: 20px 24px; border-radius: 14px; }
.knowledge-steps li > span { width: 32px; height: 32px; font-size: var(--fs-xs); }
.knowledge-steps strong { font-size: var(--fs-xs); }
.knowledge-steps small { font-size: var(--fs-2xs); }
.fragment-panel { margin: 24px 0 0; border-radius: 16px; background: var(--n-0); }
.fragment-panel > summary { padding: 17px 20px; gap: 12px; }
.fragment-panel > summary strong { font-size: var(--fs-base); font-weight: 600; color: var(--brand-700); }
.fragment-panel > summary small { margin-top: 3px; font-size: var(--fs-xs); }
.fragment-summary-icon { display: grid; place-items: center; width: 34px; height: 34px; flex-shrink: 0; border-radius: 10px; background: var(--brand-100); color: var(--brand-500); }
.fragment-body { padding: 0 20px 20px; }
.fragment-body > article { margin: 14px 0; }
.fragment-body > article small { font-size: var(--fs-xs); }
.fragment-body pre { margin: 8px 0 0; padding: 20px 22px; font-size: var(--fs-base); line-height: 2; border-radius: 12px; }
.fragment-body > .toolbar { padding-top: 8px; }
.fragment-body > .toolbar button { padding: 8px 14px; font-size: var(--fs-xs); }

@media (max-width: 980px) {
  .catalog-heading { flex-direction: column; align-items: stretch; gap: 18px; }
  .document-search { width: 100%; }
  .reader-surface { grid-template-columns: minmax(0, 1fr); }
  .reader-rail { display: none; }
}
@media (max-width: 680px) {
  .document-manager { padding: 18px 14px; }
  .document-grid { grid-template-columns: 1fr; gap: 14px; }
  .document-card { min-height: 0; padding: 22px 18px 18px; }
  .catalog-intro h3 { font-size: var(--fs-2xl); }
  .reader-layer { padding: 0; }
  .reader-surface { height: 100%; border-radius: 0; }
  .reader-head { padding: 20px 18px 16px; gap: 14px; }
  .reader-kicker { display: none; }
  .reader-body { padding: 24px 18px 40px; }
  .reader-text { font-size: var(--fs-md); line-height: 2; }
}
@media (prefers-reduced-motion: reduce) { button, summary > svg:last-child { transition: none; } }
</style>
