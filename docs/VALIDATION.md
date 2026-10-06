# Validation record

Observed results are scoped to the stated run. An absence of errors during one run is not a guarantee for other devices or sessions. Hardware logs and identifying screenshots are retained privately rather than published.

## 2026-10-06: Pixel 9 Pro, development build

- Android API 37, arm64. Direct USB capture previously reached approximately 25 fps, with 1,300+ callbacks and equal received/rendered counts in one sustained sample; no malformed frames, overflow or source sequence gaps were recorded in that sample. Receiver-to-swap was approximately 2–3 ms and available compositor timestamps approximately 15–31 ms. These intervals are not sensor-to-display latency.
- The phone was moved to charging while the camera was connected to the desktop. Wireless ADB remained connected; stay-awake-while-charging was verified enabled.
- The desktop bridge delivered its protocol header and complete 196,608-byte composites. The Android network client initially timed out. After adding/granting Android 17 local-network permission, opening the configured Tailscale app and restarting the viewer, live reception succeeded. This sequence does not isolate which action established connectivity.
- A steady network sample reported **25.00 fps, 765 received, 765 rendered, zero source gaps, malformed frames and overflow**. The sampled receiver-to-swap interval was 4.43 ms; receiver-to-presentation was 18.36 ms. Network transit is excluded.
- Volume Down created a rendered PNG, raw PNG and JSON in MediaStore Downloads. The JSON identified the network source, camera-apparent scaling, original raw orientation and unknown gain, and excluded persistent identifiers and the source address. Pixel-by-pixel examination of that specific exported live frame remains pending; independent 16-bit PNG round-trip unit tests pass.
- A subsequent NUC/control attempt coincided with the network reader's 2.5-second timeout and stopped reception. This establishes a timeout in this run, not a universal command failure. The reader was changed to a bounded 12-second wait because earlier bench gain transitions exceeded 2.5 seconds. Incoming frames now preserve an in-flight command's busy state, and Network has an explicit reconnect action.
- Retest with the extended wait: the NUC action returned to live reception. Selecting high gain returned successfully; selecting low gain showed the changing-gain status, then live reception and the low-gain label. Bridge register readback was **HIGH_GAIN = 0**. A later sample reported 1,645 received/rendered with zero recorded source gaps, malformed frames or overflow. This validates command completion/readback in this run; it does not validate temperature accuracy in low gain.

## Acceptance gates still open

- Official-app comparison at ε = 0.96: ice-water and approximately 55 °C water; record per-point deltas. No bath delta is currently claimed.
- Qualification of the on-device baseline and the official app's parameter persistence behavior.
- Android measurement tools and correction integration, export annotation correctness across all transforms, and saved-capture reanalysis.
- Repeated physical detach/reattach on the final application build, permission-denial recovery and landscape usability.
- Remote command recovery, per-frame network gain/command provenance, and long network runs under congestion. TCP delivery does not guarantee all source frames are displayed; sequence counters are the evidence.
- Full release signing, dependency redistribution audit and public APK/site delivery.
