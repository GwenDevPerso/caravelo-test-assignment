<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ApiError, getHealth, triggerSync, type SyncRun } from '@/api/client'
import BaseButton from '@/components/BaseButton.vue'
import BaseEmptyState from '@/components/BaseEmptyState.vue'
import BaseSectionTitle from '@/components/BaseSectionTitle.vue'
import BaseStatRow from '@/components/BaseStatRow.vue'
import BaseStatusMessage from '@/components/BaseStatusMessage.vue'
import BaseTag from '@/components/BaseTag.vue'
import BaseTerminal from '@/components/BaseTerminal.vue'
import { useAsyncData } from '@/composables/useAsyncData'
import { formatTimestamp } from '@/utils/format'

const route = useRoute()

const tenantId = computed(() => String(route.params.tenantId))
const { data: health, error, isLoading, reload } = useAsyncData(() => getHealth(tenantId.value))
const isSyncing = ref(false)
const syncRun = ref<SyncRun | null>(null)
const syncError = ref('')

const isFirstLoad = computed(() => isLoading.value && !health.value)
const neverSynced = computed(() => health.value?.lastStatus === null)
const lastSuccess = computed(() => formatTimestamp(health.value?.lastSuccessAt ?? null, 'never'))
const lastStatus = computed(() => health.value?.lastStatus ?? 'NONE')
const staleness = computed(() => (health.value?.stale ? 'STALE' : 'FRESH'))
const errorTone = computed(() => (health.value?.errorCount ? 'warning' : 'accent'))
const schedule = computed(() => (health.value?.enabled ? 'enabled' : 'disabled'))
const syncLabel = computed(() => (isSyncing.value ? 'syncing…' : './sync →'))
const healthLines = computed(() => [
  { command: 'source --last', output: health.value?.lastSourceUsed ?? 'none' },
  { command: 'error --last', output: health.value?.lastError ?? 'none' },
])
const syncLines = computed(() => {
  const run = syncRun.value
  if (!run) return []
  return [
    { command: `sync ${run.tenantId}`, output: `${run.status} via ${run.sourceUsed ?? 'no source'} · run ${run.runId}` },
    {
      command: 'diff --stat',
      output: `+${run.added} −${run.removed} ~${run.modified} · ${run.routeCount} routes · ${run.issueCount} issue(s)`,
    },
    { command: 'error', output: run.error ?? 'none' },
  ]
})

async function sync() {
  isSyncing.value = true
  syncError.value = ''
  try {
    syncRun.value = await triggerSync(tenantId.value)
    await reload()
  } catch (caught) {
    syncError.value = caught instanceof ApiError && caught.status === 409
      ? 'A sync is already running.'
      : (caught as Error).message
  } finally {
    isSyncing.value = false
  }
}
</script>

<template>
  <BaseSectionTitle
    prefix="03 — Health"
    :title="tenantId"
  />
  <div class="tenant-health-actions">
    <BaseButton
      variant="primary"
      :disabled="isSyncing"
      @click="sync"
    >
      {{ syncLabel }}
    </BaseButton>
  </div>
  <BaseStatusMessage
    v-if="syncError"
    tone="error"
    :message="syncError"
  />
  <BaseTerminal
    v-if="syncRun"
    class="tenant-health-sync"
    label="Last manual sync"
    :lines="syncLines"
  />
  <BaseStatusMessage
    v-if="isFirstLoad"
    message="Loading health…"
  />
  <BaseStatusMessage
    v-else-if="error"
    tone="error"
    :message="error.message"
  />
  <BaseEmptyState
    v-else-if="neverSynced"
    message="No sync has run for this tenant yet."
  />
  <div
    v-else-if="health"
    class="tenant-health-layout"
  >
    <div class="tenant-health-stats">
      <BaseStatRow
        label="Last successful sync"
        :value="lastSuccess"
      />
      <BaseStatRow label="Last status">
        <BaseTag :value="lastStatus" />
      </BaseStatRow>
      <BaseStatRow label="Staleness">
        <BaseTag :value="staleness" />
      </BaseStatRow>
      <BaseStatRow
        label="Error count"
        :tone="errorTone"
        :value="health.errorCount"
      />
      <BaseStatRow
        label="Routes stored"
        tone="accent"
        :value="health.routeCount"
      />
      <BaseStatRow
        label="Scheduled sync"
        :value="schedule"
      />
    </div>
    <BaseTerminal
      label="Last run details"
      :lines="healthLines"
    />
  </div>
</template>

<style scoped>
.tenant-health-actions {
  margin-bottom: calc(var(--space) * 4);
}

.tenant-health-sync {
  margin-bottom: calc(var(--space) * 6);
}

.tenant-health-layout {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, calc(var(--space) * 80)), 1fr));
  gap: calc(var(--space) * 6);
  align-items: start;
}

.tenant-health-stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, calc(var(--space) * 45)), 1fr));
  column-gap: calc(var(--space) * 4);
}
</style>
