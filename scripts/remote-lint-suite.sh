#!/usr/bin/env bash
# remote-lint-suite.sh — Runs INSIDE the CX11 server.
# Uploaded and executed by hetzner-lint.sh.
# Installs JDK + Android SDK, runs Gradle lint, emits XML reports to /tmp/lint-results/.

set -euo pipefail

RESULTS=/tmp/lint-results
ANDROID_HOME=/opt/android-sdk
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
SRC=/src/glucodroid
SDK_MGR="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

# cmdline-tools zip URL (stable 13.0 release)
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip"

log()  { printf '[%s remote] %s\n' "$(date +%H:%M:%S)" "$*"; }
step() { printf '\n[%s remote] ── %s ──\n' "$(date +%H:%M:%S)" "$*"; }

mkdir -p "$RESULTS"

# ── 1. Install system packages ────────────────────────────────────────────────
step "Installing JDK 17, wget, unzip, python3, xmllint"
export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get install -yq openjdk-17-jdk wget unzip python3 libxml2-utils 2>&1 | tail -5

export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
java -version 2>&1

# ── 3. Install Android cmdline-tools ─────────────────────────────────────────
step "Installing Android SDK cmdline-tools"
mkdir -p "$ANDROID_HOME/cmdline-tools"
wget -q "$CMDLINE_TOOLS_URL" -O /tmp/cmdline-tools.zip
unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools-unzipped
mkdir -p "$ANDROID_HOME/cmdline-tools/latest"
mv /tmp/cmdline-tools-unzipped/cmdline-tools/* "$ANDROID_HOME/cmdline-tools/latest/"
rm -rf /tmp/cmdline-tools.zip /tmp/cmdline-tools-unzipped

export ANDROID_HOME
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

# ── 4. Accept licenses and install SDK components ─────────────────────────────
step "Installing SDK: platforms;android-35, build-tools;35.0.0, platform-tools"
yes | "$SDK_MGR" --licenses > /dev/null 2>&1 || true
"$SDK_MGR" --install "platform-tools" "build-tools;35.0.0" "platforms;android-35" 2>&1 | tail -10

# ── 5. Create minimal NDK stub ────────────────────────────────────────────────
# AGP validates ndkVersion at config time by reading source.properties.
# Lint does not invoke cmake so the stub is sufficient.
step "Creating NDK stub for version 29.0.14206865"
NDK_STUB="$ANDROID_HOME/ndk/29.0.14206865"
mkdir -p "$NDK_STUB"
cat > "$NDK_STUB/source.properties" << 'NDKPROPS'
Pkg.Desc = Android NDK
Pkg.Revision = 29.0.14206865
NDKPROPS
# Only source.properties is needed — adding platforms/ triggers CXX1200 ABI check.

# ── 6. Extract source ─────────────────────────────────────────────────────────
step "Extracting source"
mkdir -p "$SRC"
tar xzf /tmp/glucodroid-src.tar.gz -C "$SRC"
ls "$SRC"

# ── 7. Configure local.properties ─────────────────────────────────────────────
step "Writing local.properties"
cat > "$SRC/local.properties" << LOCALPROPS
sdk.dir=$ANDROID_HOME
ndk.dir=$NDK_STUB
LOCALPROPS

# ── 8. Gradle: list verification tasks (informational) ────────────────────────
step "Querying available lint tasks"
cd "$SRC"
# cpx32 has 16 GB. Give Gradle JVM 5 GB and Kotlin daemon 4 GB.
# Remove any heap limits baked into the checked-in gradle.properties so our
# settings (appended below) are the only ones in effect.
sed -i '/^org\.gradle\.jvmargs/d; /^kotlin\.daemon\.jvmargs/d' gradle.properties
export GRADLE_OPTS="-Xmx5g -Xms512m -XX:MaxMetaspaceSize=512m"
cat >> gradle.properties << 'GPROPS'
kotlin.compiler.execution.strategy=in-process
org.gradle.jvmargs=-Xmx5g -XX:MaxMetaspaceSize=512m
kotlin.daemon.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=512m
GPROPS

./gradlew :Common:tasks --group verification \
    --no-daemon -Pno_x86 -Pno_x86_64 \
    2>&1 | tee "$RESULTS/gradle-tasks.txt" || true

# ── 9. Gradle lint ────────────────────────────────────────────────────────────
step "Running Gradle lint (MobileLibre3SiDexGoogleReleaser variant)"
LINT_EXIT=0
./gradlew :Common:lintMobileLibre3SiDexGoogleReleaser \
    --no-daemon -Pno_x86 -Pno_x86_64 \
    --continue \
    2>&1 | tee "$RESULTS/lint-stdout.txt" || LINT_EXIT=$?

log "lint exit code: $LINT_EXIT"

# Copy XML report(s) if generated
find "$SRC/Common/build/reports/lint-results*.xml" 2>/dev/null \
    | while read -r f; do cp "$f" "$RESULTS/"; done || true
find "$SRC/Common/build/reports/" -name "*.xml" 2>/dev/null \
    | while read -r f; do cp "$f" "$RESULTS/"; done || true
find "$SRC/Common/build/reports/" -name "*.html" 2>/dev/null \
    | while read -r f; do cp "$f" "$RESULTS/"; done || true

# ── 10. Gradle dependencies report ────────────────────────────────────────────
step "Running dependencies report (releaseRuntimeClasspath)"
./gradlew :Common:dependencies \
    --configuration mobileLibre3SiDexGoogleReleaserRuntimeClasspath \
    --no-daemon -Pno_x86 -Pno_x86_64 \
    2>&1 | tee "$RESULTS/dependencies.txt" || true

# ── 11. Summary ───────────────────────────────────────────────────────────────
step "Generating summary"
{
    echo "timestamp=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "lint_exit=$LINT_EXIT"

    # Count findings from XML if present
    XML_FILE=$(ls "$RESULTS"/lint-results*.xml 2>/dev/null | head -1 || true)
    if [[ -n "$XML_FILE" ]]; then
        ERRORS=$(xmllint --xpath "count(//issue[@severity='Error'])" "$XML_FILE" 2>/dev/null || echo 0)
        WARNINGS=$(xmllint --xpath "count(//issue[@severity='Warning'])" "$XML_FILE" 2>/dev/null || echo 0)
        INFO=$(xmllint --xpath "count(//issue[@severity='Information'])" "$XML_FILE" 2>/dev/null || echo 0)
        echo "lint_errors=$ERRORS"
        echo "lint_warnings=$WARNINGS"
        echo "lint_info=$INFO"
    else
        echo "lint_errors=unknown"
        echo "lint_warnings=unknown"
        echo "lint_info=unknown"
    fi
} > "$RESULTS/summary.txt"

cat "$RESULTS/summary.txt"
log "Results in $RESULTS/"
ls -lh "$RESULTS/"
