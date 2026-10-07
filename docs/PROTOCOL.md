# App protocol and provenance

This is the protocol subset used by Thermal Field. Sources describe packet layouts and identifiers; experimental observations are scoped in [VALIDATION.md](VALIDATION.md). Register readback, command completion, shutter movement and physical temperature accuracy are separate claims.

## Reference snapshots and licenses

The full repository/license/reuse inventory is [NOTICE.md](../NOTICE.md). The byte references below use these fixed snapshots:

- LeoDJ/P2Pro-Viewer, MIT, `23887289d3841fdae25c3a11b8d3eed8cd778800`: primary frame and mailbox reference.
- Pinoerkel/P2Pro-Viewer command work, MIT, `163860a8e5e0a3ad7fdee761bd65e0f1a60fc50d`: NUC sequence reference.
- mbuesch/p2pro-rs, MIT, `4c3330b6e8cb82e49605a3593348851028c9f538`: Android path/decode cross-check.
- leswright1977/PyThermalCamera, Apache-2.0, `b19821a1a25e081594c666022a3d64e2b9cf41cc`: scaling cross-check.

GPL applications and the unlicensed Android reference supplied feature/protocol context, not copied app implementation. Their licenses and scope are recorded in NOTICE.md.

## USB and frame plane

The app matches VID **0x0bda**, PID **0x5830**. Android owns the UsbDeviceConnection fd; libuvc `uvc_wrap` invokes the libusb system-fd wrapper. Native teardown precedes Java fd closure. The capture format is **256×384 YUYV at 25 Hz**, with a 512-byte packed row. The upper 192 rows are preview data; the lower 192 rows contain **49,152 unsigned LE16 words**, starting at packed offset **98,304**. Display palettes operate on the lower plane rather than the camera preview.

Pinned primary layout references: [video.py:13](https://github.com/LeoDJ/P2Pro-Viewer/blob/23887289d3841fdae25c3a11b8d3eed8cd778800/P2Pro/video.py#L13) and [video.py:110–118](https://github.com/LeoDJ/P2Pro-Viewer/blob/23887289d3841fdae25c3a11b8d3eed8cd778800/P2Pro/video.py#L110). The NumPy uint16 interpretation relies on the reference host's byte order; the app explicitly decodes little endian.

The app conversion is **T_C = word / 64 − 273.15**. Formula references are [p2pro-rs camera.rs:221](https://github.com/mbuesch/p2pro-rs/blob/4c3330b6e8cb82e49605a3593348851028c9f538/src/camera.rs#L221) and [PyThermalCamera tc001v4.2.py:111](https://github.com/leswright1977/PyThermalCamera/blob/b19821a1a25e081594c666022a3d64e2b9cf41cc/src/tc001v4.2.py#L111). The inspected LeoDJ snapshot supplies the raw-plane split; the explicit formula was not found by the recorded source search, so it is not attributed there. The committed captured fixture's center word **19052** decodes to **24.5375 °C**, locked by `native/test_protocol.cpp`. This proves the implemented conversion for the fixture, not independent surface calibration.

## Control transfer envelope

All app vendor mailbox operations use these libusb parameters:

| Direction | bmRequestType | bRequest | wValue | wIndex |
| --- | --- | --- | --- | --- |
| Write | 0x41 | 0x45 | 0x0078 | Mailbox below |
| Read | 0xc1 | 0x44 | 0x0078 | Mailbox below |

Source: [P2Pro_cmd.py:94](https://github.com/LeoDJ/P2Pro-Viewer/blob/23887289d3841fdae25c3a11b8d3eed8cd778800/P2Pro/P2Pro_cmd.py#L94), [123–137](https://github.com/LeoDJ/P2Pro-Viewer/blob/23887289d3841fdae25c3a11b8d3eed8cd778800/P2Pro/P2Pro_cmd.py#L123), [160](https://github.com/LeoDJ/P2Pro-Viewer/blob/23887289d3841fdae25c3a11b8d3eed8cd778800/P2Pro/P2Pro_cmd.py#L160).

Before/after transactions, read **one byte at wIndex 0x0200**. The app treats bits 2–7 as error, then bits 0–1 as busy, otherwise ready. Its ready wait is bounded and cancellation-aware. The source's status predicates are [87–115](https://github.com/LeoDJ/P2Pro-Viewer/blob/23887289d3841fdae25c3a11b8d3eed8cd778800/P2Pro/P2Pro_cmd.py#L87); the app deliberately rejects error bits before considering busy/ready.

A standard header is eight bytes: **LE16 code, LE32 parameter, BE16 response length**. A long header is **LE16 code, BE16 property index, BE32 value**, followed by another eight-byte **BE32 first / BE32 second** parameter block. Reference packing: [117–137](https://github.com/LeoDJ/P2Pro-Viewer/blob/23887289d3841fdae25c3a11b8d3eed8cd778800/P2Pro/P2Pro_cmd.py#L117), [152–160](https://github.com/LeoDJ/P2Pro-Viewer/blob/23887289d3841fdae25c3a11b8d3eed8cd778800/P2Pro/P2Pro_cmd.py#L152). Independent app codecs are `native/p2pro_protocol.cpp`.

## App command bytes

Each write below is the complete payload at the stated mailbox; ready polling is omitted from the table for readability.

| Operation | Mailboxes and payloads | Pinned reference |
| --- | --- | --- |
| Read correction property `i` | OUT 0x9d00: `14 85 00 ii 00 00 00 00`; OUT 0x1d08: `00 00 00 00 00 00 00 02`; IN 0x1d10: two-byte BE integer | [248–250](https://github.com/LeoDJ/P2Pro-Viewer/blob/23887289d3841fdae25c3a11b8d3eed8cd778800/P2Pro/P2Pro_cmd.py#L248), long packing above |
| Set gain high | OUT 0x9d00: `14 c5 00 05 00 00 00 01`; OUT 0x1d08: eight zero bytes; read property 5 back | [245–246](https://github.com/LeoDJ/P2Pro-Viewer/blob/23887289d3841fdae25c3a11b8d3eed8cd778800/P2Pro/P2Pro_cmd.py#L245), [43](https://github.com/LeoDJ/P2Pro-Viewer/blob/23887289d3841fdae25c3a11b8d3eed8cd778800/P2Pro/P2Pro_cmd.py#L43) |
| Set gain low | Same as high, final value byte `00`; read property 5 back | Same property/packing references |
| Enable shutter control | OUT 0x1d00: `0c 41 01 00 00 00 00 00` | [PR command codes:82](https://github.com/Pinoerkel/P2Pro-Viewer/blob/163860a8e5e0a3ad7fdee761bd65e0f1a60fc50d/P2Pro/P2Pro_cmd.py#L82), [enable enum:37–39](https://github.com/Pinoerkel/P2Pro-Viewer/blob/163860a8e5e0a3ad7fdee761bd65e0f1a60fc50d/P2Pro/P2Pro_cmd.py#L37) |
| NUC/B update, after enable | OUT 0x1d00: `0d c1 00 00 00 00 00 00` | [PR B_UPDATE:45–48](https://github.com/Pinoerkel/P2Pro-Viewer/blob/163860a8e5e0a3ad7fdee761bd65e0f1a60fc50d/P2Pro/P2Pro_cmd.py#L45), [sequence:275–278](https://github.com/Pinoerkel/P2Pro-Viewer/blob/163860a8e5e0a3ad7fdee761bd65e0f1a60fc50d/P2Pro/P2Pro_cmd.py#L275) |
| Read firmware item 5 | OUT 0x1d00: `05 84 05 00 00 00 00 32`; IN 0x1d08: 50 bytes | [device info enum/lengths:46–56](https://github.com/LeoDJ/P2Pro-Viewer/blob/23887289d3841fdae25c3a11b8d3eed8cd778800/P2Pro/P2Pro_cmd.py#L46), [252–254](https://github.com/LeoDJ/P2Pro-Viewer/blob/23887289d3841fdae25c3a11b8d3eed8cd778800/P2Pro/P2Pro_cmd.py#L252) |

Property IDs: 0 distance, 1 reflected temperature, 2 atmospheric temperature, 3 emissivity, 4 transmission, 5 gain. Primary comments describe integer Kelvin for 1/2, 1/127 and maximum 127 for 3/4, and 0=low/1=high gain: [37–43](https://github.com/LeoDJ/P2Pro-Viewer/blob/23887289d3841fdae25c3a11b8d3eed8cd778800/P2Pro/P2Pro_cmd.py#L37). Experimental readbacks accepted **128** for 3/4 on the tested unit; this discrepancy is preserved rather than resolved by assumption. Startup requests `[32,300,300,128,128,selected_gain]` and requires matching readbacks. It does not establish physical ε=1 or transmission=1. Host correction uses its separately documented inputs/model.

The official app's device-side persistence/parameter behavior remains unqualified. Original/configured property arrays and firmware are retained for comparison. Persistent serial/part/sensor identifiers are excluded under the later no-PII requirement. No automatic shutter event is inferred solely from unchanged samples. App byte/fixture tests and short direct-USB command results are in VALIDATION.md; physical shutter/calibration and official-app bath comparisons remain open.
