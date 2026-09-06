# Voice-assistant capture attempt, 2026-09-06 11:37 (Pixel 9a)

Buzz triggered the watch's voice assistant a few times with our app holding the BLE link. Goal: learn whether the
watch streams mic audio over BLE/SPP or triggers the phone's assistant over classic-BT HFP, and whether the AI is
selectable.

Result: INCONCLUSIVE for the audio path. HCI snoop did not record (enabling it needs Developer Options > "Enable
Bluetooth HCI snoop log"; `settings put global bluetooth_hci_log 1` + a BT toggle did NOT start logging on this
Android 16 Pixel, and the bugreport contained no btsnoop_hci.log). logcat (logcat.txt, git-ignored) showed the watch
reconnecting its classic profiles after the BT toggle (RFCOMM at 11:37:59) but no assistant/voice-recognition launch
during the triggers, and no BLE voice opcode.

What IS established (decompiled SDK, docs/watch_features_research.md): the vendor app has no RECORD_AUDIO permission and
there is no audio/voice opcode, so the voice feature does NOT go through the app or the BLE link. It is the watch acting
as a classic-BT HFP headset (AT+BVRA -> SCO -> the phone's default digital assistant). The default assistant on this
Pixel is Google (com.google.android.googlequicksearchbox); Gemini (com.google.android.apps.bard) is installed and
selectable at Settings > Apps > Default apps > Digital assistant app.

To get definitive proof: flip Developer Options > Enable Bluetooth HCI snoop log, re-trigger, then
tools/pull_btsnoop.sh + tools/decode_btsnoop.py and grep for AT+BVRA / SCO.
