<script setup lang="ts">
defineProps<{
  label: string
  lines: { command: string, output: string }[]
}>()
</script>

<template>
  <div
    class="base-terminal"
    role="group"
    :aria-label="label"
  >
    <template
      v-for="(line, index) in lines"
      :key="index"
    >
      <p class="base-terminal-command">
        <span
          class="base-terminal-prompt"
          aria-hidden="true"
        >$</span>
        {{ line.command }}
      </p>
      <p class="base-terminal-output">
        {{ line.output }}
      </p>
    </template>
  </div>
</template>

<style scoped>
.base-terminal {
  display: flex;
  flex-direction: column;
  gap: var(--space);
  padding: calc(var(--space) * 4);
  font-family: var(--mono);
  font-size: var(--text-sm);
  background: var(--surface);
  border: var(--border) solid var(--line);
  overflow-wrap: anywhere;
}

.base-terminal-command {
  color: var(--fg-soft);
}

.base-terminal-prompt {
  color: var(--accent);
  margin-right: calc(var(--space) * 2);
}

.base-terminal-output {
  color: var(--fg);
  margin-bottom: calc(var(--space) * 2);
}
</style>
