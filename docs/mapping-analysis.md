# Mapping analysis: Source A vs Source B (sample data)

Referenced by `SPEC.md` §3 and rule R7. Both samples describe Volaris (`Y4`); this file lists how they differ
and how each difference is resolved. Figures computed with `jq` on `tenant1.json` (Source A, Tenant A's
`GetStation` API) and `tenant2.json` (Source B, `generic-backup-provider`) after the mapping rules of §3.

## Overview

| | Source A | Source B |
| --- | --- | --- |
| Records | 146 departure stations | 866 scheduled flights |
| Airline filter | — (tenant's own API) | all 866 are `Y4`; no codeshare; every flight has `days` |
| Departure stations | 146 | 39 |
| Direct routes | 474 | 205 (after R1 de-duplication of 866 flights) |
| Connecting routes | 2,867 (pairs not also direct) | — (not covered) |
| Total canonical routes | 3,341 | 205 |

## Differences found

| # | Difference | Count | Resolution |
| --- | --- | --- | --- |
| D1 | Direct routes present in both sources | 201 | Agree. |
| D2 | Direct routes only in A (e.g. ACA→GDL, ACA→MEX, ACA→TIJ, BJX→LAX, CUN→ORD…) | 273 | B only lists flights for 39 departure airports: it is a partial schedule, not a full network. No merge (R7): A is Tenant A's source of truth; when Tenant A falls back to B, the guard and the diff only compare `DIRECT` routes (R12), and a fallback with < 50 % of the stored direct routes is `REJECTED` rather than deleting 273 routes. |
| D3 | Direct routes only in B: CJS→NLU, COS→DEN, CUL→NLU, MID→BJX | 4 | Not added to Tenant A's network while A answers (R7). 2 of them (CJS→NLU, CUL→NLU) exist in A as connecting routes: on a fallback to B they become `DIRECT` (`MODIFIED`) until A is back, which is visible in the change log. |
| D4 | Routes connecting in A only | 2,867 | B cannot see connecting routes (`coveredTypes = {DIRECT}`): never removed on a fallback (R12, AC12). |
| D5 | B lists the same pair many times (several flights a day) | 866 flights → 205 pairs | R1: de-duplicated, no issue (normal for a schedule). |
| D6 | B pairs operated in one direction only | 103 of 205 | R6: kept as is, the return is never inferred. Consistent with a partial schedule. |
| D7 | A lists direct routes twice (`direct[]` and `connection[routeDirect=1]`), and the lists disagree in 25 stations (e.g. AGU: `direct[]` has CUN, LAX, MDW, TIJ; `connection[routeDirect=1]` only CUN, TIJ) | 25 stations | R9: union, `DIRECT`, `MappingIssue R9` logged. 18 more stations have no `routeDirect=1` entry at all: they only use `direct[]`, nothing to compare, no issue. |
| D8 | A's `connection[routeDirect=1]` entries carry a `connectionStation` | 372 | Ignored for `DIRECT` routes (§3.1: `via` is empty for a non-stop route). |
| D9 | A's `connectionStation` lists several stations (`"GDL,MEX,TIJ"`) | — | Split, trimmed, validated, de-duplicated into `via` (R11). |
| D10 | Field naming and shape (nested per station vs flat per flight, `stationCode` / `arrivalStation` vs `dep_iata` / `arr_iata`) | — | One adapter per format, one canonical model (§3.1). |

## What a real sync of the samples gives

`docker compose up` (mock serving both samples): Tenant A `SUCCESS` from `tenant-a-stations`, 3,341 routes,
25 mapping issues (all R9, see D7); Tenant B `SUCCESS` from `generic-backup-provider`, 205 routes, 0 issues.
