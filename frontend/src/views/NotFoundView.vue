<script setup lang="ts">
import BaseEmptyState from '@/components/BaseEmptyState.vue'
import BaseSectionTitle from '@/components/BaseSectionTitle.vue'
</script>

<template>
  <BaseSectionTitle
    prefix="404 — Not found"
    title="Nothing here"
  />
  <BaseEmptyState message="This page does not exist.">
    <RouterLink
      class="not-found-link"
      to="/"
    >
      ← back to tenants
    </RouterLink>
  </BaseEmptyState>
</template>

<style scoped>
.not-found-link {
  color: var(--accent);
}
</style>
