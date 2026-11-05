<script setup lang="ts">
import { computed, useId } from 'vue'

const props = withDefaults(defineProps<{
  label: string
  error?: string
  /** Renders a select when given; a text input otherwise. */
  options?: { value: string, label: string }[]
  placeholder?: string
  maxlength?: number
}>(), {
  error: '',
  options: undefined,
  placeholder: '',
  maxlength: undefined,
})
const model = defineModel<string>({ default: '' })

const id = useId()
const errorId = `${id}-error`
const describedBy = computed(() => (props.error ? errorId : undefined))
</script>

<template>
  <div class="base-field">
    <label
      class="base-field-label"
      :for="id"
    >{{ label }}</label>
    <select
      v-if="options"
      :id="id"
      v-model="model"
      class="base-field-control"
      :aria-invalid="Boolean(error)"
      :aria-describedby="describedBy"
    >
      <option
        v-for="option in options"
        :key="option.value"
        :value="option.value"
      >
        {{ option.label }}
      </option>
    </select>
    <input
      v-else
      :id="id"
      v-model="model"
      class="base-field-control"
      type="text"
      autocomplete="off"
      :placeholder="placeholder"
      :maxlength="maxlength"
      :aria-invalid="Boolean(error)"
      :aria-describedby="describedBy"
    >
    <p
      :id="errorId"
      class="base-field-error"
      aria-live="polite"
    >
      {{ error }}
    </p>
  </div>
</template>

<style scoped>
.base-field {
  display: flex;
  flex-direction: column;
  gap: calc(var(--space) * 1.5);
  min-width: 0;
}

.base-field-label {
  font-size: var(--text-xs);
  text-transform: uppercase;
  letter-spacing: var(--tracking);
  color: var(--muted);
}

.base-field-control {
  width: 100%;
  padding: calc(var(--space) * 2) calc(var(--space) * 3);
  font-family: var(--mono);
  font-size: var(--text-md);
  text-transform: uppercase;
  background: var(--surface);
  color: var(--fg);
  border: var(--border) solid var(--line-strong);
  border-radius: 0;
}

.base-field-control[aria-invalid='true'] {
  border-color: var(--warning);
}

.base-field-error {
  min-height: calc(var(--space) * 4);
  font-size: var(--text-xs);
  color: var(--warning);
}
</style>
