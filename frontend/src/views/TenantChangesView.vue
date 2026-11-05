<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { getChanges, type RouteChange } from '@/api/client'
import BaseCard from '@/components/BaseCard.vue'
import BaseEmptyState from '@/components/BaseEmptyState.vue'
import BaseSectionTitle from '@/components/BaseSectionTitle.vue'
import BaseStatusMessage from '@/components/BaseStatusMessage.vue'
import ChangeLogTable from '@/components/ChangeLogTable.vue'
import { useAsyncData } from '@/composables/useAsyncData'
import { formatTimestamp } from '@/utils/format'

const route = useRoute()

const tenantId = computed(() => String(route.params.tenantId))
const { data: changes, error, isLoading } = useAsyncData(() => getChanges(tenantId.value))

const isEmpty = computed(() => changes.value?.length === 0)
/** Changes come newest first; grouping keeps that order. */
const runs = computed(() => {
  const groups = new Map<string, RouteChange[]>()
  for (const change of changes.value ?? []) {
    groups.set(change.runId, [...(groups.get(change.runId) ?? []), change])
  }
  return [...groups].map(([runId, runChanges]) => ({
    runId,
    title: `run ${runId.slice(0, 8)} · ${formatTimestamp(runChanges[0]?.detectedAt ?? null)}`,
    caption: `${runChanges.length} change(s) in sync run ${runId}`,
    changes: runChanges,
  }))
})
</script>

<template>
  <BaseSectionTitle
    prefix="02 — Changes"
    :title="tenantId"
  />
  <BaseStatusMessage
    v-if="isLoading"
    message="Loading changes…"
  />
  <BaseStatusMessage
    v-else-if="error"
    tone="error"
    :message="error.message"
  />
  <BaseEmptyState
    v-else-if="isEmpty"
    message="No change recorded yet."
  />
  <div
    v-else
    class="tenant-changes-runs"
  >
    <BaseCard
      v-for="run in runs"
      :key="run.runId"
      :title="run.title"
    >
      <ChangeLogTable
        :caption="run.caption"
        :changes="run.changes"
      />
    </BaseCard>
  </div>
</template>

<style scoped>
.tenant-changes-runs {
  display: flex;
  flex-direction: column;
  gap: calc(var(--space) * 6);
}
</style>
