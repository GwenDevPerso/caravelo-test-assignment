#!/usr/bin/env bash
# PostToolUse (Write|Edit): fast compile after a .java change, tests are not run.
# Compile errors go back to Claude (exit 2) so they are fixed right away.
file=$(jq -r '.tool_input.file_path // empty')
[[ "$file" == *.java ]] || exit 0

goal=compile
[[ "$file" == */src/test/* ]] && goal=test-compile

cd "$CLAUDE_PROJECT_DIR" || exit 0
if ! out=$(./mvnw -q -o "$goal" 2>&1); then
  echo "./mvnw $goal failed after editing $file:" >&2
  errors=$(grep -E '\.java:\[' <<<"$out" | sort -u | head -20)
  echo "${errors:-$(grep -E '^\[ERROR\]' <<<"$out" | head -10)}" >&2
  exit 2
fi
exit 0
