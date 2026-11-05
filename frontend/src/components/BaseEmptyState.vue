<script setup lang="ts">
defineProps<{ message: string }>()
</script>

<template>
  <div class="base-empty-state">
    <p class="base-empty-state-message">
      {{ message }}
    </p>
    <slot />
  </div>
</template>

<style scoped>
.base-empty-state {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: calc(var(--space) * 3);
  padding: calc(var(--space) * 8) calc(var(--space) * 5);
  border: var(--border) dashed var(--line-strong);
}

.base-empty-state-message {
  font-size: var(--text-md);
  color: var(--fg-soft);
}
</style>
