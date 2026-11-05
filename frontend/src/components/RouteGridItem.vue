<script setup lang="ts">
defineProps<{
  origin: string
  destinations: string[]
}>()
</script>

<template>
  <tr class="route-grid-item">
    <th
      class="route-grid-item-origin"
      scope="row"
    >
      {{ origin }}
      <span class="route-grid-item-count">{{ destinations.length }}</span>
    </th>
    <td class="route-grid-item-destinations">
      <ul class="route-grid-item-list">
        <li
          v-for="destination in destinations"
          :key="destination"
          class="route-grid-item-chip"
        >
          {{ destination }}
        </li>
      </ul>
    </td>
  </tr>
</template>

<style scoped>
.route-grid-item {
  border-top: var(--border) solid var(--line);
}

.route-grid-item-origin {
  padding: calc(var(--space) * 3) calc(var(--space) * 4);
  font-size: var(--text-md);
  font-weight: 500;
  text-align: left;
  vertical-align: top;
  white-space: nowrap;
  color: var(--fg);
}

.route-grid-item-count {
  margin-left: calc(var(--space) * 2);
  font-size: var(--text-xs);
  font-weight: 400;
  color: var(--accent);
}

.route-grid-item-destinations {
  padding: calc(var(--space) * 3) calc(var(--space) * 4);
}

.route-grid-item-list {
  display: flex;
  flex-wrap: wrap;
  gap: calc(var(--space) * 1.5);
  list-style: none;
}

.route-grid-item-chip {
  padding: 0 calc(var(--space) * 1.5);
  font-size: var(--text-sm);
  background: var(--chip);
  color: var(--fg-soft);
}
</style>
