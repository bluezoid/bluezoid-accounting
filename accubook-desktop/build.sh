#!/usr/bin/env bash
#
# AccuBook Desktop build & packaging.
#
#   ./build.sh --plan basic
#   ./build.sh --plan professional --sign
#   ./build.sh --plan basic --add-on taxation-returns
#   ./build.sh --plan professional --release   # refuses to run until activation exists (P7-7)
#
# Produces a self-contained native installer with a bundled Java runtime, so a
# customer never installs Java.
#
# --plan selects which feature modules are physically linked into the image. A
# module left out is not hidden - its code, screens and use cases are absent from
# the artifact. The plan is resolved by PlanResolverTool rather than by a list
# kept here, so the matrix has one definition and cannot drift.
#
# Signing is a parameterised step on purpose: certificates are a long-lead
# procurement item, and the pipeline must not need redesigning when they arrive.
set -euo pipefail

PLAN="basic"
# development | release. A release launcher refuses to start until licence
# activation exists (P7-7), so a release build cannot ship on the development
# entitlement.
CHANNEL="development"
DO_SIGN=false
SKIP_TESTS=false
ADD_ONS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --plan)        PLAN="$2"; shift 2 ;;
    --add-on)      ADD_ONS+=("$2"); shift 2 ;;
    --sign)        DO_SIGN=true; shift ;;
    --release)     CHANNEL="release"; shift ;;
    --skip-tests)  SKIP_TESTS=true; shift ;;
    -h|--help)
      sed -n '2,22p' "$0"; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

APP_NAME="AccuBook"
APP_VERSION="0.1.0"
MAIN_MODULE="accubook.launcher"
MAIN_CLASS="com.accubook.launcher.Main"
RUNTIME_DIR="target/runtime"
INSTALLER_DIR="target/installer"

case "$(uname -s)" in
  Linux*)  OS=linux ;;
  Darwin*) OS=macos ;;
  MINGW*|MSYS*|CYGWIN*) OS=windows ;;
  *) echo "Unsupported OS: $(uname -s)" >&2; exit 1 ;;
esac

echo "==> AccuBook build: plan=${PLAN} channel=${CHANNEL} os=${OS} sign=${DO_SIGN} add-ons=${ADD_ONS[*]:-none}"

# 1. Compile and test -----------------------------------------------------
if [[ "$SKIP_TESTS" == true ]]; then
  mvn -q clean package -DskipTests
else
  mvn -q clean verify
fi

# 2. Assemble the module path --------------------------------------------
rm -rf "$HERE/target/deps"
mvn -q dependency:copy-dependencies \
    -DincludeScope=runtime \
    -DoutputDirectory="$HERE/target/deps"
# Third-party jars only. Our own modules are taken from each module's target
# directory, so a stale copy here can never shadow a freshly built one.
rm -f "$HERE"/target/deps/accubook-*.jar

# 2b. Resolve the plan to a module set ------------------------------------
# Resolved by the catalogue, not by a list kept in this script: a second copy of
# the matrix would drift, and the drift would only show up as a customer's image
# missing a module they paid for.
RESOLVER_PATH="accubook-module-api/target/accubook-module-api-${APP_VERSION}-SNAPSHOT.jar"
RESOLVER_PATH="${RESOLVER_PATH}:accubook-platform/target/accubook-platform-${APP_VERSION}-SNAPSHOT.jar"
mapfile -t PLAN_MODULES < <(java -p "$RESOLVER_PATH" \
  -m accubook.module.api/com.accubook.module.plan.PlanResolverTool "$PLAN" "${ADD_ONS[@]}")
echo "==> plan '${PLAN}' resolves to ${#PLAN_MODULES[@]} optional modules: ${PLAN_MODULES[*]:-none}"

# Infrastructure is in every build by definition: the kernel, persistence,
# licensing and the shell are not sellable features.
# accounting is here, not in the plan matrix: the posting core is the kernel
# ledger, present in every build. Omitting it produces an application that
# cannot post at all - caught by a real build, not by review.
ALWAYS_INCLUDED=(platform persistence module-api licensing accounting app ui-shell launcher)

is_included() {
  local artifact="$1" candidate
  for candidate in "${ALWAYS_INCLUDED[@]}"; do
    [[ "$artifact" == "accubook-${candidate}" ]] && return 0
  done
  for candidate in "${PLAN_MODULES[@]:-}"; do
    [[ "$artifact" == "accubook-${candidate}" ]] && return 0
  done
  return 1
}

MODULE_PATH="target/deps"
INCLUDED_ARTIFACTS=()
EXCLUDED_ARTIFACTS=()
for jar in accubook-*/target/accubook-*-${APP_VERSION}-SNAPSHOT.jar; do
  [ -f "$jar" ] || continue
  artifact="$(basename "$(dirname "$(dirname "$jar")")")"
  if is_included "$artifact"; then
    MODULE_PATH="${MODULE_PATH}:${jar}"
    INCLUDED_ARTIFACTS+=("$artifact")
  else
    EXCLUDED_ARTIFACTS+=("$artifact")
  fi
done
echo "==> included: ${INCLUDED_ARTIFACTS[*]}"
echo "==> excluded: ${EXCLUDED_ARTIFACTS[*]:-none}"

# 3. jlink ----------------------------------------------------------------
# --bind-services is required, not optional: the SQLite driver is registered as
# a `provides java.sql.Driver` service, and without service binding it is not
# resolved into the image, so DriverManager reports "No suitable driver".
rm -rf "$RUNTIME_DIR"
# Every selected AccuBook module is named explicitly. jlink resolves
# transitively from --add-modules, so a module nothing `requires` yet is silently
# pruned even though it is on the module path: accubook.accounting was absent
# from the image while build.sh reported it as included, because the launcher
# does not depend on it until a use case does. Putting a module on the path is
# necessary and not sufficient.
ADD_MODULES="$MAIN_MODULE"
for jar in $(echo "$MODULE_PATH" | tr ':' '\n' | grep 'accubook-'); do
  artifact="$(basename "$(dirname "$(dirname "$jar")")")"      # accubook-foo-bar
  module="accubook.${artifact#accubook-}"                       # accubook.foo-bar
  module="${module//-/.}"                                       # accubook.foo.bar
  [[ "$module" == "$MAIN_MODULE" ]] && continue
  ADD_MODULES="${ADD_MODULES},${module}"
done
echo "==> jlink --add-modules ${ADD_MODULES}"

jlink \
  --module-path "${JAVA_HOME}/jmods:${MODULE_PATH}" \
  --add-modules "$ADD_MODULES" \
  --bind-services \
  --strip-debug --no-header-files --no-man-pages --compress=2 \
  --output "$RUNTIME_DIR"

echo "==> runtime image: $(du -sh "$RUNTIME_DIR" | cut -f1)"

# 3a. Diagnostic manifest -------------------------------------------------
# Records what this artifact physically contains, for support. It is diagnostic
# only and is never an authorization source: the entitlement decides what may be
# used, so a customer editing this file changes nothing but their own support
# output. That is why it is plain text and unsigned.
MANIFEST="$RUNTIME_DIR/accubook-build.json"
{
  echo "{"
  echo "  \"application\": \"${APP_NAME}\","
  echo "  \"version\": \"${APP_VERSION}\","
  echo "  \"plan\": \"${PLAN}\","
  echo "  \"channel\": \"${CHANNEL}\","
  echo "  \"builtAt\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\","
  echo "  \"os\": \"${OS}\","
  echo "  \"signed\": ${DO_SIGN},"
  printf '  "addOns": ['
  printf '%s' "$(printf '"%s",' "${ADD_ONS[@]:-}" | sed 's/,$//; s/""//')"
  echo "],"
  printf '  "modules": ['
  printf '%s' "$(printf '"%s",' "${PLAN_MODULES[@]:-}" | sed 's/,$//; s/""//')"
  echo "],"
  echo "  \"note\": \"Diagnostic only. Entitlement, not this file, decides what may be used.\""
  echo "}"
} > "$MANIFEST"
echo "==> manifest: $MANIFEST"

# 3b. Preinstall the SQLite native ---------------------------------------
# By default the driver extracts its native library to java.io.tmpdir on first
# use. Verified: that writes 2 files to /tmp. In the field that fails on
# hardened Linux (noexec /tmp), on read-only or full temp volumes, and gets a
# freshly written DLL quarantined by antivirus on Windows.
# Shipping the native in the image and pointing at it writes nothing to tmp.
SQLITE_JAR="$(find "$HOME/.m2" -name 'sqlite-jdbc-*.jar' | head -1)"
NATIVE_DIR="target/native"
rm -rf "$NATIVE_DIR"; mkdir -p "$NATIVE_DIR"
case "$OS" in
  linux)   NATIVE_GLOB='org/sqlite/native/Linux/x86_64/*'   ; NATIVE_LIB=libsqlitejdbc.so ;;
  macos)   NATIVE_GLOB='org/sqlite/native/Mac/*/*'          ; NATIVE_LIB=libsqlitejdbc.dylib ;;
  windows) NATIVE_GLOB='org/sqlite/native/Windows/x86_64/*' ; NATIVE_LIB=sqlitejdbc.dll ;;
esac
unzip -o -j "$SQLITE_JAR" "$NATIVE_GLOB" -d "$NATIVE_DIR" >/dev/null
# Place it inside the runtime image, which jpackage copies wholesale.
# (--app-content would be tidier but is JDK 21+; this works on 17.)
mkdir -p "$RUNTIME_DIR/native"
cp "$NATIVE_DIR"/* "$RUNTIME_DIR/native/"
echo "==> preinstalled native: $(ls "$NATIVE_DIR")"

# 4. jpackage -------------------------------------------------------------
rm -rf "$INSTALLER_DIR"; mkdir -p "$INSTALLER_DIR"

case "$OS" in
  linux)   TYPES=(deb) ;;   # rpm needs rpmbuild, which CI provides
  macos)   TYPES=(dmg) ;;
  windows) TYPES=(msi) ;;
esac

for TYPE in "${TYPES[@]}"; do
  echo "==> jpackage --type $TYPE"
  ARGS=(
    --type "$TYPE"
    --name "$APP_NAME"
    --app-version "$APP_VERSION"
    --runtime-image "$RUNTIME_DIR"
    --module "${MAIN_MODULE}/${MAIN_CLASS}"
    --dest "$INSTALLER_DIR"
    --vendor "AccuBook"
    --description "Offline-first accounting for Indian businesses"
    # Point the driver at the bundled native so nothing is written to tmp.
    --java-options "-Dorg.sqlite.lib.path=\$ROOTDIR/lib/runtime/native"
    --java-options "-Dorg.sqlite.lib.name=${NATIVE_LIB}"
    --java-options "-Daccubook.channel=${CHANNEL}"
  )

  # --- Signing: parameterised, so adding certificates is a credential swap ---
  if [[ "$DO_SIGN" == true ]]; then
    case "$OS" in
      macos)
        : "${MACOS_SIGNING_IDENTITY:?set MACOS_SIGNING_IDENTITY to sign}"
        ARGS+=(--mac-sign --mac-signing-key-user-name "$MACOS_SIGNING_IDENTITY")
        [[ -n "${MACOS_ENTITLEMENTS:-}" ]] && ARGS+=(--mac-entitlements "$MACOS_ENTITLEMENTS")
        ;;
      windows)
        # jpackage has no signing flag on Windows; signtool runs afterwards.
        : "${WINDOWS_CERT_THUMBPRINT:?set WINDOWS_CERT_THUMBPRINT to sign}"
        ;;
      linux)
        echo "    (Linux packages are signed at the repository level, not per file)"
        ;;
    esac
  fi

  jpackage "${ARGS[@]}"

  if [[ "$DO_SIGN" == true && "$OS" == "windows" ]]; then
    signtool sign /sha1 "$WINDOWS_CERT_THUMBPRINT" /tr http://timestamp.digicert.com \
      /td sha256 /fd sha256 "$INSTALLER_DIR"/*.msi
  fi

  if [[ "$DO_SIGN" == true && "$OS" == "macos" ]]; then
    # Notarization is effectively mandatory: an un-notarized app cannot be
    # opened by a normal user on current macOS.
    : "${MACOS_NOTARY_PROFILE:?set MACOS_NOTARY_PROFILE to notarize}"
    xcrun notarytool submit "$INSTALLER_DIR"/*.dmg \
      --keychain-profile "$MACOS_NOTARY_PROFILE" --wait
    xcrun stapler staple "$INSTALLER_DIR"/*.dmg
  fi
done

echo "==> installers in $INSTALLER_DIR:"
ls -lh "$INSTALLER_DIR"
