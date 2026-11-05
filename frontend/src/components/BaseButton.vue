<script setup lang="ts">
withDefaults(defineProps<{
  variant?: 'primary' | 'secondary'
  type?: 'button' | 'submit' | 'reset'
  disabled?: boolean
}>(), {
  variant: 'secondary',
  type: 'button',
  disabled: false,
})
</script>

<template>
  <button
    class="base-button"
    :class="`base-button-${variant}`"
    :type="type"
    :disabled="disabled"
  >
    <slot />
  </button>
</template>

<style scoped>
.base-button {
  padding: calc(var(--space) * 2.5) calc(var(--space) * 4);
  font-family: var(--mono);
  font-size: var(--text-sm);
  border: var(--border) solid transparent;
  border-radius: 0;
  cursor: pointer;
}

.base-button-primary {
  background: var(--accent);
  color: var(--bg);
}

.base-button-secondary {
  background: transparent;
  border-color: var(--line-strong);
  color: var(--fg-soft);
}

.base-button-secondary:hover {
  color: var(--fg);
  background: var(--surface-hover);
}

.base-button:disabled {
  cursor: progress;
  opacity: 0.6;
}
</style>
