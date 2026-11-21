#!/system/bin/sh
# The trigger is not an assistant: this does not touch the ASSISTANT role or the voice
# interaction service. The app itself asks the vendor enrollment app for the keyphrase model.
PKG=io.github.regloom.dsphotword

while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 2
done
sleep 3

# SoundTrigger middleware checks RECORD_AUDIO for the originator identity. In a ROM build the
# default-permissions XML grants it instead.
pm grant "$PKG" android.permission.RECORD_AUDIO 2>/dev/null
