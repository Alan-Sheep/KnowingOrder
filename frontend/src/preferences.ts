/** 用户偏好仅保存展示选项，不保存问答正文、身份凭据或服务端时间。 */
export const PREFERENCES_KEY = 'ragqa.preferences.v1';

export const LANGUAGES = [
  { value: 'zh-CN', label: '简体中文' },
  { value: 'en', label: 'English' },
  { value: 'fr', label: 'Français' },
  { value: 'ja', label: '日本語' },
  { value: 'de', label: 'Deutsch' },
  { value: 'es', label: 'Español' }
] as const;

export const TIME_ZONES = [
  { value: 'Asia/Shanghai', label: '北京' },
  { value: 'Asia/Tokyo', label: '东京' },
  { value: 'Europe/Paris', label: '巴黎' },
  { value: 'Europe/London', label: '伦敦' },
  { value: 'America/New_York', label: '纽约' },
  { value: 'America/Los_Angeles', label: '洛杉矶' }
] as const;

export type Language = typeof LANGUAGES[number]['value'];
export type TimeZone = typeof TIME_ZONES[number]['value'];
export interface UserPreferences {
  version: 1;
  uiLanguage: Language;
  answerLanguage: Language | 'follow-ui';
  timeZone: TimeZone;
}

export const DEFAULT_PREFERENCES: Readonly<UserPreferences> = Object.freeze({
  version: 1, uiLanguage: 'zh-CN', answerLanguage: 'follow-ui', timeZone: 'Asia/Shanghai'
});

type PreferenceStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function language(value: unknown): value is Language {
  return LANGUAGES.some(option => option.value === value);
}

function timeZone(value: unknown): value is TimeZone {
  return TIME_ZONES.some(option => option.value === value);
}

/** 旧版本整体回退；当前版本的损坏字段分别回退，不信任本地存储内容。 */
function normalize(value: unknown): UserPreferences {
  const defaults = { ...DEFAULT_PREFERENCES };
  if (!record(value) || value.version !== 1) return defaults;
  return {
    version: 1,
    uiLanguage: language(value.uiLanguage) ? value.uiLanguage : defaults.uiLanguage,
    answerLanguage: value.answerLanguage === 'follow-ui' || language(value.answerLanguage)
      ? value.answerLanguage : defaults.answerLanguage,
    timeZone: timeZone(value.timeZone) ? value.timeZone : defaults.timeZone
  };
}

function browserStorage(): Storage | undefined {
  return typeof window === 'undefined' ? undefined : window.localStorage;
}

export function loadPreferences(storage?: PreferenceStorage): UserPreferences {
  try {
    const raw = (storage ?? browserStorage())?.getItem(PREFERENCES_KEY);
    // 自己写入的数据很小，拒绝解析异常膨胀的本地内容。
    if (!raw || raw.length > 2048) return { ...DEFAULT_PREFERENCES };
    return normalize(JSON.parse(raw));
  } catch {
    return { ...DEFAULT_PREFERENCES };
  }
}

/** 返回保存结果，调用方不得在存储被禁用或配额耗尽时宣称刷新后仍能保留。 */
export function savePreferences(value: UserPreferences, storage?: PreferenceStorage): boolean {
  if (!record(value) || value.version !== 1 || !language(value.uiLanguage)
      || !(value.answerLanguage === 'follow-ui' || language(value.answerLanguage)) || !timeZone(value.timeZone)) {
    return false;
  }
  try {
    const target = storage ?? browserStorage();
    if (!target) return false;
    target.setItem(PREFERENCES_KEY, JSON.stringify(normalize(value)));
    return true;
  } catch {
    return false;
  }
}

/** 只移除自己的偏好键，不清空同站点的其他数据。 */
export function resetPreferences(storage?: PreferenceStorage): boolean {
  try {
    const target = storage ?? browserStorage();
    if (!target) return false;
    target.removeItem(PREFERENCES_KEY);
    return true;
  } catch {
    return false;
  }
}

export function effectiveAnswerLanguage(value: UserPreferences): Language {
  const preferences = normalize(value);
  return preferences.answerLanguage === 'follow-ui' ? preferences.uiLanguage : preferences.answerLanguage;
}

/** 仅负责展示已有时间戳；不能充当后端判断“今天”的可信时钟。 */
export function formatMessageTime(timestamp: number, value: UserPreferences): string {
  if (!Number.isFinite(timestamp) || !Number.isFinite(new Date(timestamp).getTime())) return '—';
  const preferences = normalize(value);
  return new Intl.DateTimeFormat(preferences.uiLanguage, {
    hour: '2-digit', minute: '2-digit', hourCycle: 'h23', timeZone: preferences.timeZone
  }).format(timestamp);
}
