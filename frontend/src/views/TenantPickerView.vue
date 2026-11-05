<script setup lang="ts">
import { computed } from 'vue'
import { getTenants } from '@/api/client'
import BaseCard from '@/components/BaseCard.vue'
import BaseEmptyState from '@/components/BaseEmptyState.vue'
import BaseSectionTitle from '@/components/BaseSectionTitle.vue'
import BaseStatRow from '@/components/BaseStatRow.vue'
import BaseStatusMessage from '@/components/BaseStatusMessage.vue'
import BaseTag from '@/components/BaseTag.vue'
import { useAsyncData } from '@/composables/useAsyncData'

const { data: tenants, error, isLoading } = useAsyncData(getTenants)

const isEmpty = computed(() => tenants.value?.length === 0)
const cards = computed(() =>
  (tenants.value ?? []).map((tenant) => ({
    ...tenant,
    to: `/tenants/${encodeURIComponent(tenant.tenantId)}/routes`,
    state: tenant.enabled ? 'ENABLED' : 'DISABLED',
    sourceList: tenant.sources.join(' → '),
  })),
)
</script>

<template>
  <BaseSectionTitle
    prefix="00 — Tenants"
    title="Pick a tenant"
  />
  <BaseStatusMessage
    v-if="isLoading"
    message="Loading tenants…"
  />
  <BaseStatusMessage
    v-else-if="error"
    tone="error"
    :message="error.message"
  />
  <BaseEmptyState
    v-else-if="isEmpty"
    message="No tenant is configured."
  />
  <ul
    v-else
    class="tenant-picker-list"
  >
    <li
      v-for="tenant in cards"
      :key="tenant.tenantId"
    >
      <BaseCard>
        <RouterLink
          class="tenant-picker-link"
          :to="tenant.to"
        >
          {{ tenant.tenantId }} →
        </RouterLink>
        <BaseStatRow
          label="Airline"
          tone="accent"
          :value="tenant.airlineIata"
        />
        <BaseStatRow label="State">
          <BaseTag :value="tenant.state" />
        </BaseStatRow>
        <BaseStatRow
          label="Sources"
          :value="tenant.sourceList"
        />
      </BaseCard>
    </li>
  </ul>
</template>

<style scoped>
.tenant-picker-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, calc(var(--space) * 70)), 1fr));
  gap: calc(var(--space) * 4);
  list-style: none;
}

.tenant-picker-link {
  font-family: var(--sans);
  font-size: var(--text-heading);
  font-weight: 600;
  text-decoration: none;
}

.tenant-picker-link:hover {
  color: var(--accent);
}
</style>
