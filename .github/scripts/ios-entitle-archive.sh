#!/bin/bash
# Give an UNSIGNED archive's app the entitlements Xcode would have signed it
# with, as an ad-hoc signature, so `xcodebuild -exportArchive` carries them
# into the distribution signature.
#
# Why: ios-release.yml archives with CODE_SIGNING_ALLOWED=NO (see the header
# there). Export reads the entitlements to keep from the archived binary's
# signature; an unsigned binary has none, so App Sandbox and network.client
# would silently drop out of the Mac Catalyst build (ITMS-90296 at upload)
# and keychain-access-groups out of both. An ad-hoc signature ("-") needs no
# certificate and no keychain, so it cannot hit errSecInternalComponent.
#
# Usage: ios-entitle-archive.sh <xcarchive> <ios|maccatalyst> <entitlements> <team-id> <bundle-id>
#   <entitlements> is the file xcodegen generates from ios/project.yml.
set -euo pipefail

ARCHIVE="$1"
PLATFORM="$2"
SOURCE="$3"
TEAM_ID="$4"
BUNDLE_ID="$5"

APP=""
for CANDIDATE in "$ARCHIVE"/Products/Applications/*.app; do
  if [ -d "$CANDIDATE" ]; then
    APP="$CANDIDATE"
    break
  fi
done
if [ -z "$APP" ]; then
  echo "::error::No .app under $ARCHIVE/Products/Applications"
  exit 1
fi

ENT="$(mktemp -d)/archive.entitlements"
python3 - "$SOURCE" "$ENT" "$PLATFORM" "$TEAM_ID" "$BUNDLE_ID" <<'PY'
import plistlib, sys

src, dst, platform, team, bundle = sys.argv[1:6]
with open(src, 'rb') as f:
    ent = plistlib.load(f)

prefix = team + '.'


def resolve(v):
    if isinstance(v, str):
        return (v.replace('$(AppIdentifierPrefix)', prefix)
                 .replace('$(TeamIdentifierPrefix)', prefix))
    if isinstance(v, list):
        return [resolve(x) for x in v]
    if isinstance(v, dict):
        return {k: resolve(x) for k, x in v.items()}
    return v


ent = {k: resolve(v) for k, v in ent.items()}
app_id = f'{team}.{bundle}'
if platform == 'ios':
    # Xcode drops the macOS-only com.apple.security.* keys when it signs the
    # iOS slice (ProcessProductPackaging in any device build log); match it.
    ent = {k: v for k, v in ent.items() if not k.startswith('com.apple.security.')}
    ent['application-identifier'] = app_id
elif platform == 'maccatalyst':
    ent['com.apple.application-identifier'] = app_id
else:
    sys.exit(f'unknown platform {platform!r}; expected ios or maccatalyst')
ent['com.apple.developer.team-identifier'] = team

unresolved = [k for k, v in ent.items() if '$(' in repr(v)]
if unresolved:
    sys.exit(f'unresolved build-setting reference in entitlement(s): {unresolved}')

with open(dst, 'wb') as f:
    plistlib.dump(ent, f)
PY

echo "Entitlements for the $PLATFORM archive ($APP):"
plutil -p "$ENT"

# Nested code first (none today; this keeps a future framework or extension
# from failing the outer signature as "code object is not signed at all").
find "$APP" -depth -mindepth 1 \( -name '*.framework' -o -name '*.dylib' -o -name '*.appex' -o -name '*.xpc' \) -print0 \
  | while IFS= read -r -d '' NESTED; do
      codesign --force --sign - --timestamp=none "$NESTED"
    done

codesign --force --sign - --timestamp=none --entitlements "$ENT" "$APP"
codesign --verify --strict "$APP"
echo "Ad-hoc signed $APP with the entitlements above."
