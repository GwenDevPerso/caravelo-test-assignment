<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()

const tenantId = computed(() => {
  const value = route.params.tenantId
  return typeof value === 'string' ? value : null
})
const links = computed(() =>
  ['routes', 'changes', 'health'].map((section) => ({
    section,
    to: `/tenants/${encodeURIComponent(tenantId.value ?? '')}/${section}`,
  })),
)
</script>

<template>
  <header class="app-header">
    <RouterLink
      class="app-header-brand"
      to="/"
    >
      <span
        class="app-header-mark"
        aria-hidden="true"
      >◆</span>
      route-sync
      <span
        v-if="tenantId"
        class="app-header-tenant"
      >/ {{ tenantId }}</span>
    </RouterLink>
    <nav
      v-if="tenantId"
      class="app-header-nav"
      aria-label="Tenant sections"
    >
      <RouterLink
        v-for="link in links"
        :key="link.section"
        class="app-header-link"
        active-class="app-header-link-active"
        :to="link.to"
      >
        {{ link.section }}
      </RouterLink>
    </nav>
  </header>
</template>

<style scoped>
.app-header {
  position: sticky;
  top: 0;
  z-index: 1;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: calc(var(--space) * 4);
  min-height: var(--header-height);
  padding: 0 var(--gutter);
  background: var(--bg);
  border-bottom: var(--border) solid var(--line);
}

.app-header-brand {
  font-family: var(--mono);
  font-size: var(--text-md);
  text-decoration: none;
  color: var(--fg);
}

.app-header-mark {
  color: var(--accent);
  margin-right: calc(var(--space) * 2);
}

.app-header-tenant {
  color: var(--muted);
}

.app-header-nav {
  display: flex;
  gap: calc(var(--space) * 5);
}

.app-header-link {
  font-size: var(--text-sm);
  text-decoration: none;
  color: var(--fg-soft);
}

.app-header-link:hover,
.app-header-link-active {
  color: var(--fg);
}
</style>
