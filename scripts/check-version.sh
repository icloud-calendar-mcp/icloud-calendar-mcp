#!/usr/bin/env bash
# Verify every committed version string agrees with build.gradle.kts (the single
# source of truth), and that the docs carry no version-pinned JAR name (which would
# 404 after the next release). Run in CI on every push and PR, and locally after
# scripts/bump-version.sh. Exits non-zero and lists every mismatch.
set -euo pipefail

cd "$(dirname "$0")/.."

version="$(sed -n 's/^version = "\(.*\)"/\1/p' build.gradle.kts)"
if [ -z "$version" ]; then
  echo "error: could not read version from build.gradle.kts" >&2
  exit 1
fi
echo "Canonical version (build.gradle.kts): $version"

fail=0
report() { echo "  MISMATCH: $1" >&2; fail=1; }

# Each file must carry the exact expected literal.
grep -q "\"version\": \"$version\"" npm-package/package.json \
  || report "npm-package/package.json version != $version"
grep -q "^version = \"$version\"" py-package/pyproject.toml \
  || report "py-package/pyproject.toml version != $version"
grep -q "^__version__ = \"$version\"" py-package/src/icloud_calendar_mcp/__init__.py \
  || report "py-package __init__.py __version__ != $version"
grep -q "^VERSION = \"$version\"" py-package/src/icloud_calendar_mcp/downloader.py \
  || report "py-package downloader.py VERSION != $version"
grep -q "^JAR_NAME = \"icloud-calendar-mcp-$version-all.jar\"" py-package/src/icloud_calendar_mcp/cli.py \
  || report "py-package cli.py JAR_NAME != $version"

# server.json carries the version twice (top level + npm package entry).
count="$(grep -c "\"version\": \"$version\"" server.json || true)"
[ "$count" -eq 2 ] || report "server.json has $count of 2 expected \"version\": \"$version\" entries"

# Docs must not pin a version into a JAR name: releases/latest/download needs a
# constant asset name, and build-from-source examples should use a glob. A pinned
# name 404s the moment the next release renames the asset.
if grep -Ern 'icloud-calendar-mcp-[0-9][0-9A-Za-z.-]*-all\.jar' README.md CONTRIBUTING.md; then
  report "docs contain a version-pinned JAR name (see matches above)"
fi

if [ "$fail" -ne 0 ]; then
  echo "" >&2
  echo "Version drift detected. Run: scripts/bump-version.sh $version" >&2
  exit 1
fi
echo "All version strings agree with $version."
