#!/usr/bin/env bash
# Enforces Meteor archive rules R1 and R3, before any Gradle work in CI:
#   R1  dependencies must be one-directional and may only follow the declared
#       edges below — checked both on build.gradle.kts and on source packages;
#   R3  a module must stay removable — :app's conditional source sets may only
#       name the module they are paired with.
# Catching a stray import here costs seconds; catching it in the pluggability
# build costs a full assembleDebug.
set -euo pipefail

cd "$(dirname "$0")/.."

# Declared, acyclic dependency edges ("from to"). Adding an edge requires an
# ADR and a change to the Meteor archive §3 graph.
ALLOWED_EDGES="$(cat <<'EOF'
app feature-settings
app feature-calendar
app feature-editor
app feature-people
app feature-gallery
app feature-ai
app core-backup
app core-storage
app core-crypto
app core-time
app core-notify
app core-log
app core-index
feature-settings core-crypto
feature-settings core-backup
feature-calendar core-storage
feature-editor core-storage
feature-people core-storage
feature-ai core-storage
feature-people feature-editor
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

# ---------------------------------------------------------------------------
# R1 on source code, not only on build files.
#
# The edge check above only reads `project(":x")` declarations. A stray
# `import com.meteor.memory.core.index.*` compiles perfectly in the default
# build — the dependency is on the classpath — and only fails once the module
# is excluded. That makes the pluggability build the sole detector, at the
# price of an assemble. Checking the packages directly is equivalent for every
# compile-time reference and costs milliseconds.
# ---------------------------------------------------------------------------

# Package prefix for a module, with dots escaped for grep -E.
module_package() {
  case "$1" in
    core-*)    echo "com\.meteor\.memory\.core\.${1#core-}" ;;
    feature-*) echo "com\.meteor\.memory\.feature\.${1#feature-}" ;;
    *)         echo "" ;;
  esac
}

# Gradle module name -> its directory, mirroring settings.gradle.kts.
module_dir() {
  case "$1" in
    app)       echo "app" ;;
    core-*)    echo "core/${1#core-}" ;;
    feature-*) echo "feature/${1#feature-}" ;;
    *)         echo "" ;;
  esac
}

# "$1" source dir, "$2" modules it must not name.
check_forbidden_packages() {
  local src="$1" forbidden="$2" pattern="" m pkg
  [ -d "$src" ] || return 0
  for m in $forbidden; do
    pkg="$(module_package "$m")"
    [ -n "$pkg" ] && pattern+="${pkg}|"
  done
  [ -z "$pattern" ] && return 0
  # A trailing package boundary keeps :core-index from matching :core-indexer.
  pattern="(${pattern%|})(\$|[^A-Za-z0-9_])"
  while IFS= read -r hit; do
    [ -z "$hit" ] && continue
    error "$src references a module it must not depend on (archive §3): ${hit:0:160}"
  done < <(grep -rnE --include='*.kt' --include='*.java' "$pattern" "$src" 2>/dev/null || true)
}

# Every library module: no package of a module it has no declared edge to.
for m in "${on_disk[@]}"; do
  dir="$(module_dir "$m")"
  [ -z "$dir" ] && continue
  forbidden=""
  for n in "${on_disk[@]}"; do
    [ "$n" = "$m" ] && continue
    grep -qxF "$m $n" <<< "$ALLOWED_EDGES" || forbidden+="$n "
  done
  check_forbidden_packages "$dir" "$forbidden"
done

# :app owns the R3 seams. Each conditional source set may name the module it is
# paired with — that is the whole point of the seam — but nothing else, so the
# two features stay independent of each other. app/src/main must stay neutral:
# that is what lets a module be dropped without touching the app shell.
# Adding a seam means adding both its source sets here and its exclusions to the
# Pluggability job in .github/workflows/ci.yml.
check_forbidden_packages app/src/main                     "core-index core-notify"
check_forbidden_packages app/src/indexEnabled             "core-notify"
check_forbidden_packages app/src/indexDisabled            "core-index"
check_forbidden_packages app/src/notificationsEnabled     "core-index"
check_forbidden_packages app/src/notificationsDisabled    "core-notify"

if [ "$fail" -ne 0 ]; then
  echo "Module graph check FAILED."
  exit 1
fi

echo "Module graph OK (${#on_disk[@]} modules, acyclic, all edges declared, packages within bounds)."
