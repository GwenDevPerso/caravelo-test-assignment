<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  message: string
  tone?: 'info' | 'error'
}>(), { tone: 'info' })

const prompt = computed(() => (props.tone === 'error' ? '!' : '…'))
</script>

<template>
  <p
    class="base-status-message"
    :class="`base-status-message-${tone}`"
    role="status"
    aria-live="polite"
  >
    <span
      class="base-status-message-prompt"
      aria-hidden="true"
    >{{ prompt }}</span>
    {{ message }}
  </p>
</template>

<style scoped>
.base-status-message {
  padding: calc(var(--space) * 3) 0;
  font-size: var(--text-sm);
}

.base-status-message-prompt {
  margin-right: calc(var(--space) * 2);
}

.base-status-message-info { color: var(--fg-soft); }
.base-status-message-info .base-status-message-prompt { color: var(--accent); }
.base-status-message-error { color: var(--warning); }
</style>
