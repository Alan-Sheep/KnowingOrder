/**
 * 全局唯一的界面语言状态。
 *
 * 和 session.ts 同样的问题：偏好设置原本只有 App.vue 自己持有一个 ref，
 * 企业侧组件要在模板里翻译，就各自去 loadPreferences() 的话会拿到互不相干的副本——
 * 用户在设置里切了语言，只有 App.vue 那份会变，企业工作区不会重渲染。
 *
 * 这里把 preferences 提到模块顶层，全应用共用一份；t() 在渲染期间读取
 * preferences.value.uiLanguage，Vue 会把这次读取记进依赖，语言一变，
 * 所有调用过 t() 的组件自动重渲染，不需要额外的通知机制。
 */
import { ref } from 'vue';
import { loadPreferences, PREFERENCES_KEY } from './preferences';
import type { UserPreferences } from './preferences';
import { translate } from './messages';

/** 已加载并可能被修改的偏好；App.vue 与设置面板都读写这一份。 */
export const preferences = ref<UserPreferences>(loadPreferences());

/** 替换整份偏好（设置面板确认后调用），调用方负责持久化。 */
export function applyPreferences(value: UserPreferences): void {
  preferences.value = value;
}

/** 翻译界面文案。词典里没有的条目原样返回中文，见 messages.ts 的说明。 */
export function t(text: string, values?: Record<string, string | number>): string {
  return translate(preferences.value.uiLanguage, text, values);
}

// 同一个浏览器开多个标签页时，一边改了语言，另一边跟着走。
if (typeof window !== 'undefined') {
  window.addEventListener('storage', event => {
    if (event.key === null || event.key === PREFERENCES_KEY) preferences.value = loadPreferences();
  });
}
