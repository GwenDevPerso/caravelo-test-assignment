<script setup lang="ts">
defineProps<{
  /** Mono prefix, e.g. `01 — Routes`. */
  prefix: string
  title: string
}>()
</script>

<template>
  <div class="base-section-title">
    <p class="base-section-title-prefix">
      {{ prefix }}
    </p>
    <h1 class="base-section-title-heading">
      {{ title }}
    </h1>
  </div>
</template>

<style scoped>
.base-section-title {
  display: flex;
  flex-direction: column;
  gap: calc(var(--space) * 3);
  margin-bottom: calc(var(--space) * 8);
}

.base-section-title-prefix {
  font-family: var(--mono);
  font-size: var(--text-xs);
  text-transform: uppercase;
  letter-spacing: var(--tracking);
  color: var(--muted);
}

.base-section-title-heading {
  font-family: var(--sans);
  font-size: var(--text-title);
  font-weight: 800;
  line-height: var(--leading-tight);
  letter-spacing: var(--tracking-tight);
  overflow-wrap: anywhere;
}
</style>
