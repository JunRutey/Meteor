#!/usr/bin/env bash
# Enforces Meteor archive rule R1: dependencies must be one-directional and
# may only follow the edges declared below. Runs before any Gradle work in CI.
set -euo pipefail

cd "$(dirname "$0")/.."

# Declared, acyclic dependency edges ("from to"). Adding an edge requires an
# ADR and a change to the Meteor archive §3 graph.
ALLOWED_EDGES="$(cat <<'EOF'
app feature-settings
app feature-calendar
app feature-editor
app core-backup
app core-storage
app core-crypto
app core-time
app core-log
app core-index
feature-settings core-crypto
feature-settings core-backup
feature-calendar core-storage
feature-editor core-storage
core-backup core-storage
core-backup core-crypto
core-index core-storage
core-storage core-crypto
core-storage core-time
EOF
)"

fail=0
error() { echo "::error::$1"; fail=1; }

module_name_for_dir() {
  local dir="$1"
  case "$dir" in
    app)              echo "app" ;;
    core/*)           echo "core-${dir#core/}" ;;
    feature/*)        echo "feature-${dir#feature/}" ;;
    *)                echo "" ;;
  esac
}

mapfile -t build_files < <(find . -name build.gradle.kts -not -path './.git/*' \
  -not -path './build.gradle.kts' | sort)

found_modules=()
for f in "${build_files[@]}"; do
  dir="${f#./}"
  dir="$(dirname "$dir")"
  name="$(module_name_for_dir "$dir")"
  [ -z "$name" ] && continue
  found_modules+=("$name")

  deps="$(grep -oE 'project\(":[A-Za-z0-9_.-]+"\)' "$f" \
    | sed -E 's/project\("://; s/"\)//' || true)"

  for d in $deps; do
    if ! grep -qxF "$name $d" <<< "$ALLOWED_EDGES"; then
      error "$name depends on :$d, which is not a declared edge (archive §3)."
    fi
  done
done

# settings.gradle.kts must register exactly the modules that exist on disk.
mapfile -t declared < <(grep -oE 'meteorModule\("[A-Za-z0-9_.-]+"\)' settings.gradle.kts \
  | sed -E 's/meteorModule\("//; s/"\)//' | sort)

mapfile -t on_disk < <(printf '%s\n' "${found_modules[@]}" | sort)

if ! diff -u <(printf '%s\n' "${declared[@]}") <(printf '%s\n' "${on_disk[@]}") > /tmp/modules.diff; then
  echo "Mismatch between settings.gradle.kts and module directories:"
  cat /tmp/modules.diff
  fail=1
fi

# The allowed-edge table must stay acyclic. Topological sort, Kahn's algorithm.
declare -A indeg=() adj=()
for m in "${on_disk[@]}"; do indeg["$m"]=0; adj["$m"]=""; done
while read -r from to; do
  [ -z "${from:-}" ] && continue
  [ -z "${indeg[$to]+x}" ] && continue
  adj["$from"]+="$to "
  indeg["$to"]=$(( ${indeg[$to]} + 1 ))
done <<< "$ALLOWED_EDGES"

queue=()
for m in "${!indeg[@]}"; do [ "${indeg[$m]}" -eq 0 ] && queue+=("$m"); done
visited=0
while [ ${#queue[@]} -gt 0 ]; do
  m="${queue[0]}"; queue=("${queue[@]:1}"); visited=$((visited + 1))
  for n in ${adj[$m]}; do
    indeg["$n"]=$(( ${indeg[$n]} - 1 ))
    [ "${indeg[$n]}" -eq 0 ] && queue+=("$n")
  done
done
if [ "$visited" -ne "${#on_disk[@]}" ]; then
  error "Declared dependency graph contains a cycle."
fi

if [ "$fail" -ne 0 ]; then
  echo "Module graph check FAILED."
  exit 1
fi

echo "Module graph OK (${#on_disk[@]} modules, acyclic, all edges declared)."
