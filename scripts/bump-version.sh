#!/usr/bin/env bash
# Set the project version everywhere it is committed, from one argument.
#
# build.gradle.kts is the source of truth; the npm and PyPI shims must agree with
# it (their downloads are version-pinned to match their own package). Two things
# are deliberately NOT touched: the docs carry no version (they use the constant
# releases/latest/download asset name and build/libs globs), and the server reads
# its own version from the JAR manifest at runtime.
#
# After running this, commit the result and tag v<version>. CI enforces the rest:
# scripts/check-version.sh fails a PR on any drift, and the release workflow refuses
# to build if the tag does not match build.gradle.kts.
#
# Uses perl for in-place edits so it works the same on GNU and BSD (macOS) userlands.
set -euo pipefail

cd "$(dirname "$0")/.."

new="${1:-}"
if ! printf '%s' "$new" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+([-.][0-9A-Za-z.-]+)?$'; then
  echo "usage: scripts/bump-version.sh <version>   (e.g. 3.3.0)" >&2
  exit 1
fi

edit() {  # edit <file> <perl-expression>
  perl -i -pe "$2" "$1"
  echo "  updated $1"
}

edit build.gradle.kts                                 "s/^version = \".*\"/version = \"$new\"/"
edit server.json                                      "s/\"version\": \"[^\"]*\"/\"version\": \"$new\"/g"
edit npm-package/package.json                         "s/\"version\": \"[^\"]*\"/\"version\": \"$new\"/"
edit py-package/pyproject.toml                        "s/^version = \".*\"/version = \"$new\"/"
edit py-package/src/icloud_calendar_mcp/__init__.py   "s/^__version__ = \".*\"/__version__ = \"$new\"/"
edit py-package/src/icloud_calendar_mcp/downloader.py "s/^VERSION = \".*\"/VERSION = \"$new\"/"
edit py-package/src/icloud_calendar_mcp/cli.py        "s/^JAR_NAME = \"icloud-calendar-mcp-.*-all\.jar\"/JAR_NAME = \"icloud-calendar-mcp-$new-all.jar\"/"

echo ""
echo "Bumped to $new. Verifying..."
bash scripts/check-version.sh
