# Feature scope and references

The app is independently branded Thermal Field and uses the operator-requested Gridline design. Vendor product images are feature references only and are not included in this repository or APK.

The supplied reference images visibly show named points, lines with min/max/average, rectangle statistics, a Point/Line/Rect/Scale/Delete toolbar, a saved-capture shortcut, capture control and palette selection. The meaning of the pictured Scale/ruler action is not established by the image alone; physical-distance measurement is not currently claimed.

The supplied Xinfrared product page describes adjustable display range, palette selection, environment/emissivity inputs and reopening saved images for analysis. These inform workflow requirements, not measured accuracy claims. Video remains outside the requested scope.

Core scope: USB live radiometry; fixed/manual span; three palettes; rotation/mirroring; center/extrema; editable spots, box statistics, line profile, ΔT and isotherm; band Planck graybody correction; lossless three-file capture; sharing; NUC/freshness; foreground lifecycle recovery. Saved-capture browsing/reanalysis and a local radiometric network bridge are additional requested workflows. Generic IP-camera protocols require compatible adapters.

References:
- https://m.media-amazon.com/images/I/719RcS3ZoEL._SL1500_.jpg
- https://m.media-amazon.com/images/I/81-hhnu33qL._SL1500_.jpg
- https://www.xinfrared.com/products/infiray_p2_pro_thermal_camera

A source/code review and a Frodo workflow review identified persistent capture access, a visible numeric temperature scale, truthful source/freshness feedback, source-coordinate transforms, lossless export, and clearly separated capture/NUC controls as the highest-priority usability requirements. Reviews occurred during implementation and are not end-to-end acceptance evidence.
