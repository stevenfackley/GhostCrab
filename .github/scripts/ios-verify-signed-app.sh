#!/bin/bash
# Check an exported, distribution-signed app before it goes to App Store
# Connect. The archive is unsigned by design (see ios-release.yml), so the
# export is the first point where the shipped signing can be checked at all.
#
# Usage: ios-verify-signed-app.sh <path to .app> <ios|maccatalyst> <team-id> <bundle-id> <build-number>
set -euo pipefail

APP="$1"
PLATFORM="$2"
TEAM_ID="$3"
BUNDLE_ID="$4"
BUILD_NUMBER="$5"

case "$PLATFORM" in
  ios)         INFO="$APP/Info.plist";          PROFILE="$APP/embedded.mobileprovision" ;;
  maccatalyst) INFO="$APP/Contents/Info.plist"; PROFILE="$APP/Contents/embedded.provisionprofile" ;;
  *) echo "::error::unknown platform '$PLATFORM'"; exit 1 ;;
esac

codesign --verify --strict --verbose=2 "$APP"

WORK="$(mktemp -d)"
codesign -dvv "$APP" > "$WORK/signature.txt" 2>&1
echo "--- signature ---"
grep -E '^(Identifier|Authority|TeamIdentifier)=' "$WORK/signature.txt" || true
AUTHORITY="$(awk '/^Authority=/ { print substr($0, 11); exit }' "$WORK/signature.txt")"
codesign -d --entitlements - --xml "$APP" > "$WORK/entitlements.plist" 2>/dev/null \
  || codesign -d --entitlements :- "$APP" > "$WORK/entitlements.plist" 2>/dev/null
if [ ! -f "$PROFILE" ]; then
  echo "::error::No embedded provisioning profile at $PROFILE"
  exit 1
fi
security cms -D -i "$PROFILE" > "$WORK/profile.plist"

python3 - "$WORK/entitlements.plist" "$INFO" "$WORK/profile.plist" \
          "$PLATFORM" "$TEAM_ID" "$BUNDLE_ID" "$BUILD_NUMBER" "$AUTHORITY" <<'PY'
import plistlib, sys

ent_path, info_path, prof_path, platform, team, bundle, build, authority = sys.argv[1:9]
with open(ent_path, 'rb') as f:
    raw = f.read()
ent = plistlib.loads(raw) if raw.strip() else {}
with open(info_path, 'rb') as f:
    info = plistlib.load(f)
with open(prof_path, 'rb') as f:
    prof = plistlib.load(f)

errors, warnings = [], []
print(f"CFBundleIdentifier          {info.get('CFBundleIdentifier')}")
print(f"CFBundleShortVersionString  {info.get('CFBundleShortVersionString')}")
print(f"CFBundleVersion             {info.get('CFBundleVersion')}")
print(f"Signed by                   {authority}")
print(f"Profile                     {prof.get('Name')} (expires {prof.get('ExpirationDate')})")
print('Entitlements:')
for k in sorted(ent):
    print(f'  {k} = {ent[k]!r}')

if info.get('CFBundleIdentifier') != bundle:
    errors.append(f"CFBundleIdentifier is {info.get('CFBundleIdentifier')!r}, expected {bundle!r}")
if str(info.get('CFBundleVersion')) != build:
    errors.append(f"CFBundleVersion is {info.get('CFBundleVersion')!r}, expected {build!r} "
                  '(manageAppVersionAndBuildNumber must stay false in ExportOptions.plist)')
if info.get('ITSAppUsesNonExemptEncryption') is not False:
    warnings.append('ITSAppUsesNonExemptEncryption is not false in Info.plist; '
                    'TestFlight will hold the build at "Missing Compliance"')
if not (authority.startswith('Apple Distribution:') or authority.startswith('iPhone Distribution:')
        or authority.startswith('3rd Party Mac Developer Application:')):
    errors.append(f'signed by {authority!r}, not a distribution identity')
if prof.get('ProvisionedDevices') or prof.get('ProvisionsAllDevices'):
    errors.append(f"profile {prof.get('Name')!r} is a development/ad-hoc/enterprise profile, "
                  'not an App Store one')
if team not in prof.get('TeamIdentifier', []):
    errors.append(f"profile team {prof.get('TeamIdentifier')} does not include {team}")

get_task_allow = 'get-task-allow' if platform == 'ios' else 'com.apple.security.get-task-allow'
if ent.get(get_task_allow):
    errors.append(f'{get_task_allow} is true; App Store builds must not be debuggable')

group = f'{team}.{bundle}'
if group not in ent.get('keychain-access-groups', []):
    # The keychain default access group is the application identifier, which
    # is this same string, so a build without the key still reads and writes
    # the same items. Loud, but not worth blocking an upload over.
    warnings.append(f'keychain-access-groups does not contain {group} '
                    '(declared in ios/project.yml; the entitlement step may not have taken)')

if platform == 'maccatalyst':
    for key in ('com.apple.security.app-sandbox', 'com.apple.security.network.client'):
        if ent.get(key) is not True:
            errors.append(f'{key} is not true; the Mac App Store rejects an unsandboxed '
                          'app (ITMS-90296) and without network.client the sandbox '
                          'blocks every gateway request')

for w in warnings:
    print(f'::warning::{w}')
for e in errors:
    print(f'::error::{e}')
sys.exit(1 if errors else 0)
PY
