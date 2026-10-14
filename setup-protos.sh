#!/usr/bin/env bash
# Lädt die offiziellen DMXControl-3-Protobuf-Dateien (Client + Common) in app/src/main/proto.
# Version MUSS zur installierten DMXControl-Version passen (Tags: v3.3.0, v3.3.1, v3.3.2, ...).
set -euo pipefail
VERSION="${1:-v3.3.2}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
DST="$ROOT/app/src/main/proto"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
curl -fsSL "https://github.com/DMXControl/DMXControl3-Network-Interface/archive/refs/tags/${VERSION}.tar.gz" | tar -xz -C "$TMP"
SRC="$(find "$TMP" -mindepth 1 -maxdepth 1 -type d | head -n1)/proto"
find "$DST" -mindepth 1 ! -name .gitkeep -exec rm -rf {} +
cp -r "$SRC/Common" "$SRC/Client" "$DST/"
echo "Protos $VERSION -> $DST ($(find "$DST" -name '*.proto' | wc -l) Dateien)"
