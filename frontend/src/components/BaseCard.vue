<script setup lang="ts">
withDefaults(defineProps<{ title?: string }>(), { title: '' })
</script>

<template>
  <section class="base-card">
    <h2
      v-if="title"
      class="base-card-title"
    >
      {{ title }}
    </h2>
    <slot />
  </section>
</template>

<style scoped>
.base-card {
  display: flex;
  flex-direction: column;
  gap: calc(var(--space) * 3);
  min-width: 0;
  padding: calc(var(--space) * 5);
  background: var(--surface);
  border: var(--border) solid var(--line);
}

.base-card-title {
  font-family: var(--mono);
  font-size: var(--text-xs);
  font-weight: 400;
  text-transform: uppercase;
  letter-spacing: var(--tracking);
  color: var(--muted);
}
</style>
