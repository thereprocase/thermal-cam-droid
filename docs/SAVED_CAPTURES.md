# Saved radiometric captures

Open **Captures** in the Camera pane. The gallery lists complete image/plane/JSON sets accessible through MediaStore Downloads to this installation. Each row shows the original acquisition time, source qualification and saved display settings. Ordering uses export time when recorded, otherwise acquisition time. Reopening does not require a connected camera.

**Open / reanalyze** reads the original 256×192 grayscale16 PNG into sensor order. The app checks PNG chunk checksums, geometry, precision, filters and decompressed size before using it. The ignored preview half is reconstructed as zero bytes; it is not recovered from the raw PNG and is not used for display or measurement. The saved scaling declaration must identify Kelvin × 64.

Reopening restores emissivity, reflected input, apparent/corrected selection, palette, level/span, rotation, mirroring, measurement IDs/geometry, ordered delta and isotherm settings. Readings are recomputed from the original plane using the current app's correction model. They are not treated as newly acquired temperatures. This workflow does not establish the physical accuracy of a camera or the applicability of the graybody assumptions.

Reanalysis uses a separate in-memory survey profile. On first entry, the engine retains the applied live correction table, display settings, sensor geometry, delta/isotherm configuration and next measurement ID. Opening another saved capture preserves that same live profile. Returning to USB, network or Demo restores it and reports “Returned to live survey settings”. Archive display/correction edits do not update live preferences. Units and screen rotation lock remain global convenience settings. Image editing returns to View mode when the live profile is restored.

During a profile transition, the controls show an applying state and capture is unavailable. Measurement input dialogs are dismissed on a lasting profile revision so values entered for an archive are not later applied to a different live profile. These transitions have synthetic AVD checks; physical USB source switching still needs device qualification.

Saved frames are labeled as saved in the viewer. Synthetic ancestry is retained and explicitly qualified in the viewer and annotated export. Native repaint counters describe reanalysis rendering, not sensor acquisition. Backgrounding pauses repainting; foreground recovery preserves edits in the current process. Process restart does not restore an unfinished reanalysis session; the gallery can reopen its saved original or derived capture.

Capturing during reanalysis writes another complete three-file set. Its raw plane retains the original words and sensor orientation. `timestamp_unix_ns` retains acquisition time, `timestamp_basis` identifies an original saved frame, and `exported_at_unix_ns` records the current export time. Unique filename suffixes distinguish repeated exports of the same acquisition. The current measurements and correction inputs describe the derived view.

Limits in this implementation:

- Importing files from another installation through a file picker is not implemented. Android may restrict access to another app installation's MediaStore entries.
- Incomplete sets or unreadable sidecars are skipped during listing; opening reports invalid PNGs or unsupported metadata rather than treating them as a new measurement.
- Derived archive identity records original source kind and timestamp. It preserves available original firmware and the six original/configured register readbacks in `identity.original_device_context`, including through further reanalysis. Missing fields remain missing; older derived exports that omitted them cannot reconstruct that context. The baseline remains explicitly unverified. Device serials and network addresses are omitted from app exports.
- Gallery entries have text summaries. Thumbnails, deletion and custom annotation names are not implemented.

Device checks and their limits are recorded in [VALIDATION.md](VALIDATION.md).
