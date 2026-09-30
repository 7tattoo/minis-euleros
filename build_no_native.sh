set -e
export JAVA_HOME=/opt/jdk21-home
export ANDROID_HOME=/opt/android-sdk
cd src/android
./gradlew :app:assembleDebug \
    -Pandroid.aapt2FromMavenOverride=/opt/android-sdk/build-tools/37.0.0/aapt2 \
    -Dandroid.aapt2FromMavenOverride=/opt/android-sdk/build-tools/37.0.0/aapt2 \
    -x ":app:configureCMakeDebug[arm64-v8a]" \
    -x ":app:buildCMakeDebug[arm64-v8a]" \
    -x ":app:stripDebugDebugSymbols" \
    --no-daemon 2>&1 | tail -20
ls -la app/build/outputs/apk/debug/
