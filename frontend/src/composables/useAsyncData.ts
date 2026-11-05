import { ref, shallowRef, type Ref } from 'vue'

/** Runs `load` immediately and on `reload()`; only the latest call updates the state. */
export function useAsyncData<T>(load: () => Promise<T>) {
  const data = shallowRef<T | null>(null) as Ref<T | null>
  const error = shallowRef<Error | null>(null)
  const isLoading = ref(false)
  let call = 0

  async function reload() {
    const current = ++call
    isLoading.value = true
    error.value = null
    try {
      const result = await load()
      if (current === call) data.value = result
    } catch (caught) {
      if (current === call) {
        data.value = null
        error.value = caught instanceof Error ? caught : new Error(String(caught))
      }
    } finally {
      if (current === call) isLoading.value = false
    }
  }

  void reload()
  return { data, error, isLoading, reload }
}
