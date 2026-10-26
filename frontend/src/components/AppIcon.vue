<script setup lang="ts">
defineProps<{ name: string; size?: number }>();

// 图标路径为本地固定常量，不使用外部图标字体或远程资源。
const paths: Record<string, string> = {
  plus: 'M12 5v14M5 12h14',
  chat: 'M21 11.5a8.5 8.5 0 0 1-8.5 8.5H4l-2 2V11.5A8.5 8.5 0 0 1 10.5 3h2a8.5 8.5 0 0 1 8.5 8.5Z M7 9h10M7 13h7',
  search: 'm21 21-4.6-4.6M19 10.5a8.5 8.5 0 1 1-17 0 8.5 8.5 0 0 1 17 0',
  book: 'M3 4h6a4 4 0 0 1 3 1.5A4 4 0 0 1 15 4h6v16h-6a4 4 0 0 0-3 1.5A4 4 0 0 0 9 20H3ZM12 6v15',
  globe: 'M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0M3 12h18M12 3a18 18 0 0 1 0 18 18 18 0 0 1 0-18',
  arrow: 'M12 19V5m-6 6 6-6 6 6',
  chevron: 'm9 5 7 7-7 7',
  external: 'M14 3h7v7m0-7L10 14M10 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-5',
  sparkle: 'm12 3 2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5ZM20 2v4M18 4h4',
  layers: 'm12 3 10 5-10 5L2 8Zm-10 9 10 5 10-5M2 16l10 5 10-5',
  panel: 'M9 3v18M3 3h18v18H3Z',
  clock: 'M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0M12 7v5l3 2',
  check: 'm5 12 4 4L19 6',
  info: 'M12 11v6M12 7h.01M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0',
  close: 'm6 6 12 12M6 18 18 6',
  settings: 'M4 6h16M4 12h16M4 18h16M8 3v6M16 9v6M10 15v6',
  copy: 'M8 8h12v13H8ZM16 8V3H3v13h5',
  shield: 'm12 2 9 4v6c0 5-9 10-9 10S3 17 3 12V6Zm-5 10 3 3 6-6',
  file: 'M14 2H4v20h16V8Zm0 0v6h6M8 13h8M8 17h5',
  stop: 'M6 6h12v12H6Z',
  menu: 'M4 6h16M4 12h16M4 18h16',
  trash: 'M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7M14 10v7',
  refresh: 'M20 7A9 9 0 1 0 21 14M20 3v5h-5',
  bolt: 'm13 2-9 12h7l-1 8 10-13h-8Z',
  briefcase: 'M3 7h18v14H3ZM8 7V3h8v4M3 12l9 3 9-3M12 12v5',
  code: 'm8 6-6 6 6 6m8-12 6 6-6 6m-3-15-2 18',
  leaf: 'M20 3C8 1 1 8 5 16c8 5 15-1 15-13ZM3 21 15 9',
  download: 'M12 3v12m-5-5 5 5 5-5M4 17v4h16v-4'
};
</script>

<template>
  <svg :width="size ?? 20" :height="size ?? 20" viewBox="0 0 24 24" fill="none"
    stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round"
    aria-hidden="true" focusable="false">
    <path :d="paths[name] ?? paths.info" />
  </svg>
</template>
