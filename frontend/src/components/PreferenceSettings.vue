<script setup lang="ts">
import { ref } from 'vue';
import { DEFAULT_PREFERENCES, LANGUAGES, TIME_ZONES, resetPreferences, savePreferences } from '../preferences';
import type { UserPreferences } from '../preferences';
import { translate } from '../messages';

const props = defineProps<{ preferences: UserPreferences }>();
const emit = defineEmits<{ change: [value: UserPreferences] }>();
const status = ref('');
const t = (text: string) => translate(props.preferences.uiLanguage, text);

function update(event: Event, key: 'uiLanguage' | 'answerLanguage' | 'timeZone') {
  const value = (event.target as HTMLSelectElement).value;
  if (key === 'timeZone' ? !TIME_ZONES.some(item => item.value === value)
    : value !== 'follow-ui' || key === 'uiLanguage' ? !LANGUAGES.some(item => item.value === value) : false) return;
  const next = { ...props.preferences, [key]: value } as UserPreferences;
  // 选项来自白名单；持久化失败仍应用于当前页面，并明确告知刷新后的风险。
  const saved = savePreferences(next);
  emit('change', next);
  status.value = saved ? '已保存，下次提问生效。' : '浏览器无法保存设置；当前页面已生效，刷新后可能丢失。';
}

function reset() {
  const saved = resetPreferences();
  emit('change', { ...DEFAULT_PREFERENCES });
  status.value = saved ? '已恢复默认设置。' : '浏览器无法保存设置；当前页面已生效，刷新后可能丢失。';
}
</script>

<template>
  <section class="preference-settings">
    <h2>{{ t('工作空间设置') }}</h2>
    <label for="ui-language">{{ t('界面语言') }}</label>
    <select id="ui-language" :value="preferences.uiLanguage" @change="update($event, 'uiLanguage')">
      <option v-for="item in LANGUAGES" :key="item.value" :value="item.value">{{ item.label }}</option>
    </select>
    <label for="answer-language">{{ t('回答语言') }}</label>
    <select id="answer-language" :value="preferences.answerLanguage" @change="update($event, 'answerLanguage')">
      <option value="follow-ui">{{ t('跟随界面语言') }}</option>
      <option v-for="item in LANGUAGES" :key="item.value" :value="item.value">{{ item.label }}</option>
    </select>
    <label for="time-zone">{{ t('地区与时区') }}</label>
    <select id="time-zone" :value="preferences.timeZone" @change="update($event, 'timeZone')">
      <option v-for="item in TIME_ZONES" :key="item.value" :value="item.value">{{ t(item.label) }} · {{ item.value }}</option>
    </select>
    <p>{{ t('设置对下次提问生效，不修改正在处理的请求。') }}</p>
    <p>{{ t('仅在此浏览器保存语言与时区，不保存密钥或对话。') }}</p>
    <button class="text-button" type="button" @click="reset">{{ t('恢复默认') }}</button>
    <p v-if="status" class="preference-status" role="status">{{ t(status) }}</p>
  </section>
</template>
