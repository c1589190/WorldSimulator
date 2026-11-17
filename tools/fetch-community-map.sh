#!/usr/bin/env bash
# Download only the map/states/localisation files needed by the HOI4 community-map importer.
# Raw assets stay under runtime/ (gitignored); no map content is committed.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="${1:-$ROOT/runtime/imports/MappaMundi}"
REPO="${MAPPAMUNDI_REPO:-https://github.com/N0zza/MappaMundi.git}"
COMMIT="${MAPPAMUNDI_COMMIT:-7a7afbf04ef64b715ff1ebf95380f73a7d02b21a}"

mkdir -p "$(dirname "$DEST")"
if [[ -d "$DEST/.git" ]]; then
  git -C "$DEST" fetch --depth 1 origin "$COMMIT"
else
  if [[ -e "$DEST" ]]; then
    echo "目标已存在但不是 git 仓库: $DEST" >&2
    echo "请移走该目录或传入新的目标路径。" >&2
    exit 1
  fi
  git clone --depth 1 --filter=blob:none --no-checkout "$REPO" "$DEST"
fi

git -C "$DEST" sparse-checkout init --no-cone
git -C "$DEST" sparse-checkout set \
  README.md \
  mappamundi.mod \
  map/provinces.bmp \
  map/definition.csv \
  map/heightmap.bmp \
  map/supply_nodes.txt \
  map/strategicregions \
  history/states \
  localisation
git -C "$DEST" checkout --detach "$COMMIT"

cat > "$DEST/.worldsim-source.txt" <<SOURCE
repo=$REPO
commit=$COMMIT
fetched_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
note=Raw community map assets are local-only; see project docs for license uncertainty.
SOURCE
printf 'downloaded %s @ %s\n' "$DEST" "$COMMIT"
