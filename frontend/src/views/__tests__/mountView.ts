import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { Component } from 'vue'
import { routes } from '@/router'

/** Mounts a view under a memory router positioned at `path`. */
export async function mountView(view: Component, path: string) {
  const router = createRouter({ history: createMemoryHistory(), routes })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(view, { global: { plugins: [router] } })
  await flushPromises()
  return { wrapper, router }
}

/** A promise that never settles, to observe the loading state. */
export const pending = () => new Promise<never>(() => {})
