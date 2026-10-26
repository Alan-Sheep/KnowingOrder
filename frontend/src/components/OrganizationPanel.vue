<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue';
import { createEnterpriseClient, EnterpriseError, positiveId } from '../enterprise';
import type { Departments, Department, OrganizationMember } from '../organization';
import type { PageData } from '../knowledge';

const props = defineProps<{ enterpriseId: string; disabled: boolean }>();
const emit = defineEmits<{ busy: [value: boolean]; expired: [] }>();
const api = createEnterpriseClient();
const departments = ref<Departments>();
const members = ref<PageData<OrganizationMember>>();
const busy = ref(false);
const unknown = ref(false);
const message = ref('仅企业管理员可读取和管理组织；管理身份不代表可以查看员工私有知识。');
const failed = ref(false);
const name = ref('');
const editing = ref<Department>();
const editName = ref('');
const editStatus = ref(0);
const employee = ref<OrganizationMember>();
const departmentId = ref('');
// 部门选择用当前页记录填充；成员原部门不在本页时保留为附加选项，避免误清空。
const departmentChoices = computed(() => departments.value?.page.records ?? []);
const employeeStatus = ref(0);
const username = ref('');
const password = ref('');
// 表单级校验：错误信息贴在对应输入框下方，不再统一堆到页面顶部。
const accountError = ref('');
const passwordError = ref('');
const createError = ref('');
const nameError = ref('');
const editNameError = ref('');
const employeeError = ref('');
let alive = true;

async function run(work: () => Promise<void>, inline?: (text: string) => void) {
  if (busy.value || props.disabled) return;
  busy.value = true; failed.value = false; emit('busy', true); message.value = '正在核验并处理；各接口操作最多等待10秒，刷新会依次读取部门和成员…';
  try { await work(); }
  catch (error) {
    if (!alive) return;
    const text = error instanceof Error ? error.message : '操作未完成，请刷新核对。';
    if (inline) {
      inline(text);
      message.value = '';
    } else {
      failed.value = true; message.value = text;
      if (error instanceof EnterpriseError && error.code === 409) message.value = '组织信息已变化，或部门名称重复、仍有关联成员/有效授权。请刷新组织，核对关联后重新确认。';
    }
    if (error instanceof EnterpriseError) {
      unknown.value ||= error.uncertain;
      if ([401, 404].includes(error.code)) {
        departments.value = undefined; members.value = undefined; editing.value = undefined; employee.value = undefined;
        if (error.code === 401) emit('expired');
      }
    }
  } finally { if (alive) { busy.value = false; emit('busy', false); } }
}
async function load(departmentPage = 1, memberPage = 1) {
  // 两部分完整读取后再替换，失败不把新旧权限版本混成一次成功刷新。
  const d = await api.organizationDepartments(props.enterpriseId, departmentPage);
  const m = await api.organizationMembers(props.enterpriseId, memberPage);
  if (!alive) return;
  departments.value = d; members.value = m; editing.value = undefined; employee.value = undefined; unknown.value = false;
}
async function refresh(departmentPage = 1, memberPage = 1) {
  await run(async () => { await load(departmentPage, memberPage); message.value = '组织与成员已重新核权读取。停用部门前须移出所有成员并撤销部门授权。'; });
}
async function createDepartment() {
  if (!departments.value || unknown.value) return;
  nameError.value = '';
  if (!name.value.trim() || name.value.trim().length > 128) { nameError.value = '部门名称须为 1—128 个字符，不能只填空格'; return; }
  await run(async () => {
    await api.createDepartment(props.enterpriseId, departments.value!.enterprisePermissionVersion, name.value.trim());
    name.value = ''; await load(); message.value = '部门已创建；没有自动给部门或成员授予知识权限。';
  }, text => { nameError.value = text; });
}
function editDepartment(row: Department) { editing.value = row; editName.value = row.name; editStatus.value = row.status; }
async function saveDepartment() {
  if (!departments.value || !editing.value || unknown.value) return;
  editNameError.value = '';
  if (!editName.value.trim() || editName.value.trim().length > 128) { editNameError.value = '部门名称须为 1—128 个字符，不能只填空格'; return; }
  if (editStatus.value === 1 && !window.confirm('停用部门要求没有关联成员和有效部门授权。确认保存？')) return;
  await run(async () => {
    await api.changeDepartment(props.enterpriseId, editing.value!.id, departments.value!.enterprisePermissionVersion, editName.value.trim(), editStatus.value);
    await load(); message.value = '部门已保存，请重新读取受影响的知识任务或空间。';
  }, text => { editNameError.value = text; });
}
function editEmployee(row: OrganizationMember) { employee.value = row; departmentId.value = row.departmentId ?? ''; employeeStatus.value = row.status; }
async function saveEmployee() {
  if (!employee.value || unknown.value) return;
  employeeError.value = '';
  if (departmentId.value && !positiveId(departmentId.value)) { employeeError.value = '请选择有效的直属部门，或留空表示未分配'; return; }
  if (!window.confirm('直属部门或成员状态变化会影响知识访问；重新启用成员可能恢复其原有明确授权。确认保存？')) return;
  await run(async () => {
    await api.changeOrganizationMember(props.enterpriseId, employee.value!.id, employee.value!.permissionVersion, departmentId.value || null, employeeStatus.value);
    await load(); message.value = '成员已保存；旧权限快照将被拒绝，账号与成员状态分别校验。';
  }, text => { employeeError.value = text; });
}
async function createEmployee() {
  if (unknown.value) return;
  accountError.value = ''; passwordError.value = ''; createError.value = '';
  const account = username.value.trim();
  if (!/^[A-Za-z0-9_.@-]{3,64}$/.test(account)) {
    accountError.value = '账号须为 3–64 位字母、数字或 _ . @ -，不能包含空格';
    return;
  }
  if (password.value.length < 12 || new TextEncoder().encode(password.value).length > 72 || !password.value.trim()) {
    passwordError.value = '口令须至少 12 个字符且不超过 72 字节';
    return;
  }
  await run(async () => {
    try {
      const result = await api.createEmployee(props.enterpriseId, account, password.value);
      username.value = ''; await load(); message.value = `账号已开通，成员编号 ${result.memberId}；请另外设置部门和明确知识授权。`;
    } finally { password.value = ''; }
  }, text => { createError.value = text; });
}
onUnmounted(() => { alive = false; password.value = ''; api.dispose(); emit('busy', false); });
</script>

<template>
  <details class="organization-panel">
    <summary>企业组织与成员管理（管理员）</summary>
    <p role="status" aria-live="polite" :class="{ error: failed }">{{ message }}</p>
    <p v-if="unknown" class="error">写入结果未知，已暂停再次写入。先刷新核对部门名称或成员账号；不要换名字反复开户。</p>
    <button type="button" :disabled="busy || disabled" @click="refresh()">刷新组织与成员</button>
    <fieldset :disabled="busy || disabled || unknown" v-if="departments && members">
      <legend>平级部门与企业成员</legend>
      <p>企业组织版本 {{ departments.enterprisePermissionVersion }}；部门与成员分别分页，不代表全部记录。部门授权在知识空间中另行配置。</p>
      <section aria-label="部门列表"><h3>部门 · 共 {{ departments.page.total }} 项</h3>
        <ul><li v-for="row in departments.page.records" :key="row.id">{{ row.name }} · 编号 {{ row.id }} · {{ row.status === 0 ? '启用' : '停用' }} <button type="button" @click="editDepartment(row)">编辑部门 {{ row.id }}</button></li></ul>
        <button type="button" :disabled="departments.page.pageNum <= 1" @click="refresh(departments.page.pageNum - 1, members.pageNum)">部门上一页</button>
        <span>第 {{ departments.page.pageNum }} 页</span>
        <button type="button" :disabled="departments.page.pageNum >= departments.page.pages" @click="refresh(departments.page.pageNum + 1, members.pageNum)">部门下一页</button>
      </section>
      <form aria-label="创建部门" @submit.prevent="createDepartment"><label>新部门名称<input v-model="name" maxlength="128" required :class="{ invalid: !!nameError }" :aria-invalid="!!nameError" @input="nameError = ''" /><small v-if="nameError" class="field-error" role="alert">{{ nameError }}</small></label><button>创建部门</button></form>
      <form v-if="editing" aria-label="保存部门" @submit.prevent="saveDepartment"><p>正在编辑部门 {{ editing.id }}</p><label>部门名称<input v-model="editName" maxlength="128" required :class="{ invalid: !!editNameError }" :aria-invalid="!!editNameError" @input="editNameError = ''" /><small v-if="editNameError" class="field-error" role="alert">{{ editNameError }}</small></label><label>状态<select v-model="editStatus"><option :value="0">启用</option><option :value="1">停用</option></select></label><button>保存部门</button></form>
      <section aria-label="成员列表"><h3>成员 · 共 {{ members.total }} 项</h3>
        <ul><li v-for="row in members.records" :key="row.id">{{ row.username }} · 成员 {{ row.id }} / 账号 {{ row.userId }} · {{ row.role === 'ADMIN' ? '管理员' : '员工' }} · 部门 {{ row.departmentId || '未分配' }} · {{ ['有效', '停用', '离职'][row.status] }}<span v-if="row.accountStatus !== 0">（账号本身已停用）</span><button type="button" v-if="row.role === 'EMPLOYEE'" @click="editEmployee(row)">编辑成员 {{ row.id }}</button></li></ul>
        <button type="button" :disabled="members.pageNum <= 1" @click="refresh(departments.page.pageNum, members.pageNum - 1)">成员上一页</button><span>第 {{ members.pageNum }} 页</span><button type="button" :disabled="members.pageNum >= members.pages" @click="refresh(departments.page.pageNum, members.pageNum + 1)">成员下一页</button>
      </section>
      <form v-if="employee" aria-label="保存成员" @submit.prevent="saveEmployee"><p>正在编辑 {{ employee.username }} · 成员 {{ employee.id }} · 版本 {{ employee.permissionVersion }}</p><label>直属部门<select v-model="departmentId"><option value="">未分配</option><option v-for="row in departmentChoices" :key="row.id" :value="String(row.id)">{{ row.name }}（#{{ row.id }}）</option><option v-if="departmentId && !departmentChoices.some(row => String(row.id) === departmentId)" :value="departmentId">部门 #{{ departmentId }}（不在当前页）</option></select></label><label>成员状态<select v-model="employeeStatus" @change="employeeError = ''"><option :value="0">有效</option><option :value="1">停用</option><option :value="2">离职</option></select></label><small v-if="employeeError" class="field-error" role="alert">{{ employeeError }}</small><button :disabled="!!departmentId && !positiveId(departmentId)">保存成员</button></form>
      <form aria-label="开通员工账号" @submit.prevent="createEmployee"><h3>管理员开通员工账号</h3><label>账号<input v-model="username" required maxlength="64" autocomplete="off" :class="{ invalid: !!accountError }" :aria-invalid="!!accountError" @input="accountError = ''; createError = ''" /><small v-if="accountError" class="field-error" role="alert">{{ accountError }}</small></label><label>初始口令<input v-model="password" type="password" required autocomplete="new-password" :class="{ invalid: !!passwordError }" :aria-invalid="!!passwordError" @input="passwordError = ''; createError = ''" /><small v-if="passwordError" class="field-error" role="alert">{{ passwordError }}</small></label><small>至少12字符且不超过72字节；仅在本次请求中发送，提交后清空，不显示到日志或列表。请通过公司批准的安全渠道交付员工。</small><small v-if="createError" class="field-error" role="alert">{{ createError }}</small><button>开通员工账号</button></form>
    </fieldset>
  </details>
</template>

<style scoped>
.organization-panel { margin: 24px 0; border: 1px solid var(--brand-100); border-radius: 10px; padding: 18px; background: var(--amber-soft); color: var(--brand-700); font-size: var(--fs-sm); }
summary { cursor: pointer; font-weight: 600; } p, li, small { line-height: 1.8; overflow-wrap: anywhere; } .error { color: var(--danger); background: var(--danger-soft); padding: 10px; }
fieldset { min-width: 0; border: 0; padding: 12px 0; } form, section { margin: 20px 0; padding: 12px; background: var(--n-25); border-radius: 8px; } label, small { display: block; margin: 10px 0; }
input, select { display: block; width: 100%; box-sizing: border-box; padding: 10px; border: 1px solid var(--brand-200); border-radius: 6px; background: white; color: inherit; font: inherit; }
input.invalid { border-color: var(--rose-400); background: var(--danger-soft); }
.field-error { margin: 6px 0 0; color: var(--danger); font-weight: 500; }
button { padding: 9px 12px; margin: 5px; border: 1px solid var(--brand-300); border-radius: 6px; background: var(--n-50); color: var(--brand-700); cursor: pointer; } button:disabled, fieldset:disabled { opacity: .55; } li { margin: 10px 0; } ul { padding-left: 18px; }
</style>
