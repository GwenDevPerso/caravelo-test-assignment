<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError, getRoutes, type RouteFilters, type RouteType } from '@/api/client'
import BaseButton from '@/components/BaseButton.vue'
import BaseEmptyState from '@/components/BaseEmptyState.vue'
import BaseField from '@/components/BaseField.vue'
import BaseSectionTitle from '@/components/BaseSectionTitle.vue'
import BaseStatRow from '@/components/BaseStatRow.vue'
import BaseStatusMessage from '@/components/BaseStatusMessage.vue'
import RouteGrid from '@/components/RouteGrid.vue'
import { useAsyncData } from '@/composables/useAsyncData'

type FilterName = 'origin' | 'destination' | 'type'

const typeOptions = [
  { value: '', label: 'All' },
  { value: 'DIRECT', label: 'Direct' },
  { value: 'CONNECTING', label: 'Connecting' },
]

const route = useRoute()
const router = useRouter()

const tenantId = computed(() => String(route.params.tenantId))
const queryFilters = computed<RouteFilters>(() => ({
  origin: queryValue('origin'),
  destination: queryValue('destination'),
  type: queryValue('type') as RouteType | undefined,
}))

const form = reactive({ origin: '', destination: '', type: '' })
const { data: routes, error, isLoading, reload } = useAsyncData(() =>
  getRoutes(tenantId.value, queryFilters.value),
)

const badRequest = computed(() =>
  error.value instanceof ApiError && error.value.status === 400 ? error.value : null,
)
const fieldErrors = computed(() => {
  const errors: Record<FilterName, string> = { origin: '', destination: '', type: '' }
  const problem = badRequest.value
  if (problem?.parameter && problem.parameter in errors) errors[problem.parameter as FilterName] = problem.message
  return errors
})
const formError = computed(() => {
  const problem = badRequest.value
  const onField = problem?.parameter ? problem.parameter in fieldErrors.value : false
  return problem && !onField ? problem.message : ''
})
const loadError = computed(() => (error.value && !badRequest.value ? error.value.message : ''))
const origins = computed(() => Object.keys(routes.value ?? {}))
const routeCount = computed(() =>
  Object.values(routes.value ?? {}).reduce((total, destinations) => total + destinations.length, 0),
)
const isEmpty = computed(() => routes.value !== null && origins.value.length === 0)

watch(queryFilters, (filters) => {
  form.origin = filters.origin ?? ''
  form.destination = filters.destination ?? ''
  form.type = filters.type ?? ''
}, { immediate: true })
// The query watcher also fires while leaving the page: only reload on this route.
watch(() => route.query, () => {
  if (route.name === 'routes') void reload()
})

function queryValue(name: FilterName): string | undefined {
  const value = route.query[name]
  return typeof value === 'string' && value ? value : undefined
}

function applyFilters() {
  const query: Record<string, string> = {}
  for (const name of ['origin', 'destination', 'type'] as const) {
    const value = form[name].trim().toUpperCase()
    if (value) query[name] = value
  }
  void router.replace({ query })
}

function resetFilters() {
  void router.replace({ query: {} })
}
</script>

<template>
  <BaseSectionTitle
    prefix="01 — Routes"
    :title="tenantId"
  />
  <form
    class="tenant-routes-filters"
    aria-label="Route filters"
    @submit.prevent="applyFilters"
  >
    <BaseField
      v-model="form.origin"
      label="Origin"
      placeholder="ACA"
      :maxlength="3"
      :error="fieldErrors.origin"
    />
    <BaseField
      v-model="form.destination"
      label="Destination"
      placeholder="MEX"
      :maxlength="3"
      :error="fieldErrors.destination"
    />
    <BaseField
      v-model="form.type"
      label="Type"
      :options="typeOptions"
      :error="fieldErrors.type"
    />
    <div class="tenant-routes-actions">
      <BaseButton
        variant="primary"
        type="submit"
      >
        ./filter →
      </BaseButton>
      <BaseButton @click="resetFilters">
        reset
      </BaseButton>
    </div>
  </form>
  <BaseStatusMessage
    v-if="formError"
    tone="error"
    :message="formError"
  />
  <BaseStatusMessage
    v-if="isLoading"
    message="Loading routes…"
  />
  <BaseStatusMessage
    v-else-if="loadError"
    tone="error"
    :message="loadError"
  />
  <BaseEmptyState
    v-else-if="isEmpty"
    message="No route matches these filters."
  />
  <template v-else-if="routes">
    <div class="tenant-routes-stats">
      <BaseStatRow
        label="Origins"
        tone="accent"
        :value="origins.length"
      />
      <BaseStatRow
        label="Routes"
        tone="accent"
        :value="routeCount"
      />
    </div>
    <RouteGrid :routes="routes" />
  </template>
</template>

<style scoped>
.tenant-routes-filters {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, calc(var(--space) * 40)), 1fr));
  align-items: start;
  gap: calc(var(--space) * 4);
  margin-bottom: calc(var(--space) * 4);
}

.tenant-routes-actions {
  display: flex;
  gap: calc(var(--space) * 2);
  padding-top: calc(var(--space) * 5);
}

.tenant-routes-stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, calc(var(--space) * 40)), 1fr));
  gap: calc(var(--space) * 4);
  margin-bottom: calc(var(--space) * 6);
}
</style>
