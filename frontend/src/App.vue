<script setup lang="ts">
import { useRoute } from 'vue-router'
import AppHeader from '@/components/AppHeader.vue'

// Keyed on the path: a new tenant or section remounts the view, which loads its data once.
const route = useRoute()
</script>

<template>
  <AppHeader />
  <main class="app-main">
    <RouterView :key="route.path" />
  </main>
</template>

<style>
:root {
  color-scheme: dark;
  --bg: #0e0e0c;          --surface: #141410;      --surface-hover: #1a1915;  --chip: #1e1d18;
  --fg: #edeae2;          --fg-soft: #b4aea1;      --muted: #9a9384;          --faint: #8e887b;
  --line: #26251f;        --line-soft: #1e1d18;    --line-strong: #33322a;
  --accent: #7be0b0;      /* mint: prompts, numbers, primary action, focus */
  --warning: #d9a07e;     /* muted warm tone for REMOVED / FAILED, derived from the palette */
  --sans: 'Archivo', Helvetica, Arial, sans-serif;
  --mono: 'IBM Plex Mono', ui-monospace, monospace;
  --max-width: 1400px;    --gutter: 28px;          --header-height: 50px;
  --space: 4px;           /* base unit: use calc(var(--space) * n) */
  --text-xs: 11px;        --text-sm: 12px;         --text-md: 13px;
  --text-heading: 22px;   --text-title: clamp(40px, 9vw, 96px);
  --tracking: 0.08em;     --tracking-tight: -0.04em;
  --leading: 1.5;         --leading-tight: 0.95;
  --border: 1px;
}
@media (max-width: 768px) { :root { --gutter: 18px; } }

*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }

body {
  min-height: 100vh;
  background-color: var(--bg);
  /* faint vertical grid lines */
  background-image: linear-gradient(to right, var(--line-soft) var(--border), transparent var(--border));
  background-size: 25% 100%;
  color: var(--fg);
  font-family: var(--mono);
  font-size: var(--text-md);
  line-height: var(--leading);
  -webkit-font-smoothing: antialiased;
}

a { color: inherit; }
button, input, select { font: inherit; color: inherit; }
table { border-collapse: collapse; width: 100%; }

:focus-visible { outline: var(--border) solid var(--accent); outline-offset: calc(var(--space) / 2); }

.app-main {
  max-width: var(--max-width);
  margin: 0 auto;
  padding: calc(var(--space) * 10) var(--gutter) calc(var(--space) * 16);
}
</style>
