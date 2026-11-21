#!/system/bin/sh
# KernelSU/hybrid_mount show module files with a 1970 mtime, so PackageManager keeps a stale
# parse in /data/system/package_cache after the module APK changes (old manifest, or a boot
# loop on the privapp allowlist check). Drop only this app's cache entries when the APK changed.
MODDIR=${0%/*}
APK="$MODDIR/system/product/priv-app/DspHotwordTrigger/DspHotwordTrigger.apk"
STAMP="$MODDIR/.apk.sha1"
[ -f "$APK" ] || exit 0
NEW=$(sha1sum "$APK" | cut -d' ' -f1)
if [ "$NEW" != "$(cat "$STAMP" 2>/dev/null)" ]; then
    for f in /data/system/package_cache/*/DspHotwordTrigger-[0-9]*-*; do
        [ -f "$f" ] && rm -f "$f"
    done
    echo "$NEW" > "$STAMP"
fi
