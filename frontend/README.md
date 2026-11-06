# route-sync frontend

Vue 3 + TypeScript + Vite UI for the Route Network Sync Service (contract: `../SPEC.md` §6).

| Path | View |
| --- | --- |
| `/` | tenant list (`GET /api/tenants`) |
| `/tenants/:tenantId/routes` | routes grouped by origin, filters `origin` / `destination` / `type` in the query string |
| `/tenants/:tenantId/changes` | change log grouped by sync run |
| `/tenants/:tenantId/health` | sync health and manual `./sync →` (`POST …/sync`) |

## Commands

```sh
npm install          # npm 10.9 crashes on this tree (arborist peer bug); use `npx npm@11 install` if it does
npm run dev          # http://localhost:5173, proxies /api to the backend on http://localhost:8080
npm run build        # type-check + production build into dist/
npm run test:unit -- --run
```

Start the backend first (`docker compose up --build` from the repository root).

## End-to-end tests

`e2e/*.spec.ts` (Playwright) drive a real browser against the Vite dev server, which proxies `/api` to the running
stack (Spring app, PostgreSQL, `mock-sources` WireMock with the deterministic sample payloads). Nothing is mocked.

```sh
docker compose up -d --build     # from the repository root; global setup fails fast if :8080 does not answer
npx playwright install chromium  # once
npm run test:e2e                 # projects: desktop Chromium and mobile 360×740; reuses a running `npm run dev`
```

Assertions rely on the sample data (tenant-a origin ACA: 58 destinations, DIRECT only GDL, MEX, TIJ). The health
spec triggers a real `POST …/sync` on tenant-a. Locators are roles, labels and text only.

## Layout

- `src/api/client.ts`: typed `fetch` wrapper and one function per endpoint; non-2xx throws `ApiError(status, message, parameter?)`.
- `src/composables/useAsyncData.ts`: `{ data, error, isLoading, reload }`, used by every view.
- `src/components/Base*.vue`: presentational components (props, slots, events only). Design tokens live in `App.vue`.
