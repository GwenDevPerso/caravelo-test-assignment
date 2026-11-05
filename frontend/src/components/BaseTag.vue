<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ value: string }>()

const tones: Record<string, string> = {
  ADDED: 'accent',
  SUCCESS: 'accent',
  REMOVED: 'warning',
  FAILED: 'warning',
  REJECTED: 'warning',
  STALE: 'warning',
  MODIFIED: 'soft',
  DEGRADED: 'soft',
}

const toneClass = computed(() => `base-tag-${tones[props.value] ?? 'muted'}`)
</script>

<template>
  <span
    class="base-tag"
    :class="toneClass"
  >{{ value }}</span>
</template>

<style scoped>
.base-tag {
  display: inline-block;
  padding: 0 calc(var(--space) * 1.5);
  font-family: var(--mono);
  font-size: var(--text-xs);
  text-transform: uppercase;
  letter-spacing: var(--tracking);
  border: var(--border) solid currentColor;
}

.base-tag-accent { color: var(--accent); }
.base-tag-warning { color: var(--warning); }
.base-tag-soft { color: var(--fg-soft); }
.base-tag-muted { color: var(--muted); }
</style>
