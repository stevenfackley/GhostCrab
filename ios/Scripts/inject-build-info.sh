#!/bin/bash
#
# Build-phase script that overwrites the `gitSHA` constant in BuildInfo.swift
# with the short SHA of the current HEAD commit.
#
# Wire this into the Xcode project as a "Run Script" build phase BEFORE
# "Compile Sources" so the rewritten file is what gets compiled. The xcodegen
# project.yml under `targets.GhostCrab.preBuildScripts` can declare it:
#
#   preBuildScripts:
#     - script: ${SRCROOT}/Scripts/inject-build-info.sh
#       name: "Inject build info"
#       inputFiles:
#         - $(SRCROOT)/GhostCrab/App/BuildInfo.swift
#       outputFiles:
#         - $(SRCROOT)/GhostCrab/App/BuildInfo.swift
#
# Safe to run from outside Xcode too:
#   cd ios && ./Scripts/inject-build-info.sh
#
# If git isn't available (e.g. archived snapshot), the script leaves
# BuildInfo.swift untouched so the "dev" placeholder remains.

set -euo pipefail

# Find the build info file relative to this script.
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_INFO="${SCRIPT_DIR}/../GhostCrab/App/BuildInfo.swift"

if [[ ! -f "$BUILD_INFO" ]]; then
    echo "warning: BuildInfo.swift not found at $BUILD_INFO — skipping SHA injection"
    exit 0
fi

# Resolve git SHA. Fall back to leaving file alone if git is unhappy.
if ! GIT_SHA="$(git -C "$SCRIPT_DIR" rev-parse --short=7 HEAD 2>/dev/null)"; then
    echo "warning: not a git repository — leaving BuildInfo.swift unchanged"
    exit 0
fi

# Use sed in-place. macOS sed needs the '' after -i; Linux sed accepts -i alone.
if sed --version >/dev/null 2>&1; then
    SED_INPLACE=(sed -i)
else
    SED_INPLACE=(sed -i '')
fi

"${SED_INPLACE[@]}" "s/public static let gitSHA: String = \"[^\"]*\"/public static let gitSHA: String = \"${GIT_SHA}\"/" "$BUILD_INFO"

echo "Injected git SHA ${GIT_SHA} into BuildInfo.swift"
