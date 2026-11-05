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

## Layout

- `src/api/client.ts`: typed `fetch` wrapper and one function per endpoint; non-2xx throws `ApiError(status, message, parameter?)`.
- `src/composables/useAsyncData.ts`: `{ data, error, isLoading, reload }`, used by every view.
- `src/components/Base*.vue`: presentational components (props, slots, events only). Design tokens live in `App.vue`.
