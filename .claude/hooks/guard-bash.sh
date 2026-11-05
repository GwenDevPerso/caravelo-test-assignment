#!/usr/bin/env bash
# PreToolUse (Bash): never expose .env secrets, never rewrite remote history.
cmd=$(jq -r '.tool_input.command // empty')

deny() {
  echo "Blocked by project hook: $1" >&2
  exit 2
}

# .env, .env.local… but not .env.example (no secrets by convention).
[[ "$cmd" =~ (^|[^A-Za-z0-9_.])\.env(\.[A-Za-z0-9_]+)?([^A-Za-z0-9_.]|$) && ! "$cmd" =~ \.env\.example ]] \
  && deny ".env holds secrets and must not be read. Ask the user for the value you need."
[[ "$cmd" =~ git[[:space:]].*push && "$cmd" =~ (^|[[:space:]])(-f|--force|--force-with-lease(=[^[:space:]]*)?|-[a-zA-Z]*f[a-zA-Z]*)([[:space:]]|$) ]] \
  && deny "git push --force is forbidden. Push normally, or ask the user."
[[ "$cmd" =~ git[[:space:]].*push.*[[:space:]]\+[^[:space:]]+ ]] \
  && deny "force push via +refspec is forbidden."
exit 0
