#!/usr/bin/env bash
# PreToolUse hook for the test-designer subagent: tests are written from SPEC.md only,
# so any access to the implementation (src/main, build output, git history) is blocked.
input=$(cat)
tool=$(jq -r '.tool_name' <<<"$input")
args=$(jq -r '.tool_input | [.file_path, .path, .pattern, .command] | map(select(. != null)) | join(" ")' <<<"$input")
path=$(jq -r '.tool_input.path // empty' <<<"$input")

deny() {
  echo "test-designer works from SPEC.md only: $1" >&2
  exit 2
}

[[ "$args" =~ src/main|target/ ]] && deny "src/main and target/ are off limits."
# A search without an explicit path would scan src/main too.
[[ "$tool" =~ ^(Grep|Glob)$ && -z "$path" ]] && deny "give an explicit path (e.g. src/test, SPEC.md)."
if [[ "$tool" == "Bash" ]]; then
  [[ "$args" =~ git[[:space:]]+(show|diff|log[[:space:]].*-p|blame) ]] && deny "git history and diffs are off limits."
  [[ "$args" =~ (^|[[:space:]|;&])(rg|grep[[:space:]]+-[a-zA-Z]*[rR][a-zA-Z]*|find)[[:space:]] && ! "$args" =~ src/test ]] \
    && deny "recursive search must target src/test."
fi
exit 0
