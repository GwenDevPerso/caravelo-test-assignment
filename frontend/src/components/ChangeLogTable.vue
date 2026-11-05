<script setup lang="ts">
import { computed } from 'vue'
import type { RouteChange } from '@/api/client'
import BaseTag from '@/components/BaseTag.vue'
import { formatRouteType } from '@/utils/format'

const props = defineProps<{
  caption: string
  changes: RouteChange[]
}>()

const rows = computed(() =>
  props.changes.map((change) => {
    const before = formatRouteType(change.oldType, change.oldVia)
    const after = formatRouteType(change.newType, change.newVia)
    const details: Record<RouteChange['changeType'], string> = {
      ADDED: after,
      REMOVED: before,
      MODIFIED: `${before} → ${after}`,
    }
    return {
      key: `${change.origin}-${change.destination}-${change.changeType}`,
      changeType: change.changeType,
      pair: `${change.origin} → ${change.destination}`,
      detail: details[change.changeType],
    }
  }),
)
</script>

<template>
  <table class="change-log-table">
    <caption class="change-log-table-caption">
      {{ caption }}
    </caption>
    <thead>
      <tr>
        <th
          class="change-log-table-heading"
          scope="col"
        >
          Change
        </th>
        <th
          class="change-log-table-heading"
          scope="col"
        >
          Route
        </th>
        <th
          class="change-log-table-heading"
          scope="col"
        >
          Type
        </th>
      </tr>
    </thead>
    <tbody>
      <tr
        v-for="row in rows"
        :key="row.key"
        class="change-log-table-row"
      >
        <td class="change-log-table-cell">
          <BaseTag :value="row.changeType" />
        </td>
        <td class="change-log-table-cell change-log-table-pair">
          {{ row.pair }}
        </td>
        <td class="change-log-table-cell change-log-table-detail">
          {{ row.detail }}
        </td>
      </tr>
    </tbody>
  </table>
</template>

<style scoped>
.change-log-table-caption {
  padding-bottom: calc(var(--space) * 2);
  font-size: var(--text-xs);
  text-align: left;
  text-transform: uppercase;
  letter-spacing: var(--tracking);
  color: var(--muted);
}

.change-log-table-heading {
  padding: calc(var(--space) * 2) calc(var(--space) * 2) calc(var(--space) * 2) 0;
  font-size: var(--text-xs);
  font-weight: 400;
  text-align: left;
  text-transform: uppercase;
  letter-spacing: var(--tracking);
  color: var(--muted);
  border-bottom: var(--border) solid var(--line-strong);
}

.change-log-table-row {
  border-top: var(--border) solid var(--line);
}

.change-log-table-cell {
  padding: calc(var(--space) * 2) calc(var(--space) * 2) calc(var(--space) * 2) 0;
  font-size: var(--text-sm);
  vertical-align: top;
  overflow-wrap: anywhere;
}

.change-log-table-pair {
  white-space: nowrap;
  color: var(--fg);
}

.change-log-table-detail {
  color: var(--fg-soft);
}
</style>
