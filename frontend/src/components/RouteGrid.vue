<script setup lang="ts">
import { computed } from 'vue'
import type { RoutesByOrigin } from '@/api/client'
import RouteGridItem from '@/components/RouteGridItem.vue'

const props = defineProps<{ routes: RoutesByOrigin }>()

const origins = computed(() => Object.entries(props.routes))
</script>

<template>
  <table class="route-grid">
    <caption class="route-grid-caption">
      Destinations by origin
    </caption>
    <thead>
      <tr>
        <th
          class="route-grid-heading"
          scope="col"
        >
          Origin
        </th>
        <th
          class="route-grid-heading"
          scope="col"
        >
          Destinations
        </th>
      </tr>
    </thead>
    <tbody>
      <RouteGridItem
        v-for="[origin, destinations] in origins"
        :key="origin"
        :origin="origin"
        :destinations="destinations"
      />
    </tbody>
  </table>
</template>

<style scoped>
.route-grid {
  border: var(--border) solid var(--line);
  background: var(--surface);
}

.route-grid-caption {
  padding-bottom: calc(var(--space) * 2);
  font-size: var(--text-xs);
  text-align: left;
  text-transform: uppercase;
  letter-spacing: var(--tracking);
  color: var(--muted);
}

.route-grid-heading {
  padding: calc(var(--space) * 2) calc(var(--space) * 4);
  font-size: var(--text-xs);
  font-weight: 400;
  text-align: left;
  text-transform: uppercase;
  letter-spacing: var(--tracking);
  color: var(--muted);
  border-bottom: var(--border) solid var(--line-strong);
}
</style>
