<script setup lang="ts">
import { onUnmounted, ref } from 'vue';
import { createEnterpriseClient, EnterpriseError } from '../enterprise';
import { t } from '../i18n';

defineProps<{ disabled: boolean }>();
const emit = defineEmits<{ changed: []; busy: [value: boolean] }>();
const api = createEnterpriseClient();
const previous = ref('');
const replacement = ref('');
const repeated = ref('');
const busy = ref(false);
const message = ref(t('改密后所有已有登录将失效，请使用新口令重新登录。'));
let mounted = true;
async function submit() {
  if (busy.value) return;
  if (replacement.value !== repeated.value) { message.value = t('两次新口令不一致。'); return; }
  busy.value = true; emit('busy', true);
  try {
    await api.changePassword(previous.value, replacement.value);
    if (mounted) emit('changed');
  } catch (error) {
    if (mounted) message.value = error instanceof EnterpriseError ? error.message : t('改密未完成，请重新登录核对。');
  } finally {
    previous.value = ''; replacement.value = ''; repeated.value = '';
    if (mounted) { busy.value = false; emit('busy', false); }
  }
}
onUnmounted(() => { mounted = false; api.dispose(); previous.value = ''; replacement.value = ''; repeated.value = ''; });
</script>

<template>
  <details class="password-panel">
    <summary>{{ t('修改本人登录口令') }}</summary>
    <form :aria-label="t('修改登录口令')" @submit.prevent="submit">
      <fieldset :disabled="disabled || busy"><legend>{{ t('账号安全') }}</legend>
        <label>{{ t('原口令') }}<input v-model="previous" type="password" autocomplete="current-password" required maxlength="256" /></label>
        <label>{{ t('新口令') }}<input v-model="replacement" type="password" autocomplete="new-password" required minlength="12" maxlength="72" /></label>
        <label>{{ t('再次输入新口令') }}<input v-model="repeated" type="password" autocomplete="new-password" required minlength="12" maxlength="72" /></label>
        <button type="submit">{{ busy ? t('正在修改…') : t('确认修改并退出登录') }}</button>
      </fieldset>
    </form>
    <p role="status">{{ message }}</p>
    <small>{{ t('不保存或回显口令；请求结果未知时先用新口令重新登录核对，不重复提交。') }}</small>
  </details>
</template>

<style scoped>
.password-panel { margin: 20px 0; padding: 18px; border: 1px solid var(--brand-100); border-radius: 10px; background: var(--amber-soft); }
summary { cursor: pointer; font-size: var(--fs-sm); }
form { max-width: 420px; margin-top: 15px; }
fieldset { border: 0; padding: 0; }
legend, label, p, small { font-size: var(--fs-xs); line-height: 1.8; }
label { display: block; margin: 10px 0; }
input { display: block; width: 100%; box-sizing: border-box; padding: 9px; border: 1px solid var(--brand-200); border-radius: 6px; }
button { border: 0; border-radius: 6px; background: var(--brand-700); color: white; padding: 10px 15px; cursor: pointer; }
small, p { color: var(--n-500); }
</style>
