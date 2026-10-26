<script setup lang="ts">
import { computed } from 'vue';
import type { Source } from '../types';

const props = defineProps<{ text: string; sources: Source[] }>();
const emit = defineEmits<{ citation: [id: string] }>();

// 后端交付纯文本。这里只识别引用标记，任何 HTML 都按文字显示。
const paragraphs = computed(() => props.text.split(/\n\s*\n/).filter(Boolean).map(paragraph => {
  return paragraph.split(/(\[q\d+-s\d+\])/g).filter(Boolean).map(text => {
    const id = text.match(/^\[(q\d+-s\d+)\]$/)?.[1];
    const index = props.sources.findIndex(source => source.id === id);
    return { text, id: index >= 0 ? id : undefined, number: index + 1 };
  });
}));
</script>

<template>
  <div class="answer-text">
    <p v-for="(paragraph, index) in paragraphs" :key="index">
      <template v-for="(token, tokenIndex) in paragraph" :key="tokenIndex">
        <button v-if="token.id" class="citation" :aria-label="`查看参考来源 ${token.number}`"
          @click="emit('citation', token.id)">{{ token.number }}</button>
        <span v-else>{{ token.text }}</span>
      </template>
    </p>
  </div>
</template>
