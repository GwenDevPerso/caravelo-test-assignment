import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

export const routes: RouteRecordRaw[] = [
  { path: '/', name: 'tenants', component: () => import('@/views/TenantPickerView.vue') },
  { path: '/tenants/:tenantId/routes', name: 'routes', component: () => import('@/views/TenantRoutesView.vue') },
  { path: '/tenants/:tenantId/changes', name: 'changes', component: () => import('@/views/TenantChangesView.vue') },
  { path: '/tenants/:tenantId/health', name: 'health', component: () => import('@/views/TenantHealthView.vue') },
  { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('@/views/NotFoundView.vue') },
]

export default createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
})
