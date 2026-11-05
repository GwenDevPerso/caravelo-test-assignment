<script setup lang="ts">
withDefaults(defineProps<{
  label: string
  value?: string | number
  tone?: 'default' | 'accent' | 'warning'
}>(), {
  value: '',
  tone: 'default',
})
</script>

<template>
  <div class="base-stat-row">
    <p class="base-stat-row-label">
      {{ label }}
    </p>
    <p
      class="base-stat-row-value"
      :class="`base-stat-row-value-${tone}`"
    >
      <slot>{{ value }}</slot>
    </p>
  </div>
</template>

<style scoped>
.base-stat-row {
  display: flex;
  flex-direction: column;
  gap: var(--space);
  padding: calc(var(--space) * 3) 0;
  border-top: var(--border) solid var(--line);
}

.base-stat-row-label {
  font-size: var(--text-xs);
  text-transform: uppercase;
  letter-spacing: var(--tracking);
  color: var(--muted);
}

.base-stat-row-value {
  font-size: var(--text-md);
  overflow-wrap: anywhere;
  color: var(--fg);
}

.base-stat-row-value-accent {
  color: var(--accent);
}

.base-stat-row-value-warning {
  color: var(--warning);
}
</style>
