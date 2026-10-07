#!/usr/bin/env python3
"""Exercise visible controls on the isolated synthetic-source emulator app."""

import argparse
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET


PACKAGE = "com.thereprocase.thermalfield.emulator"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("This check requires an emulator serial, not a physical device")

    def adb(*command):
        return subprocess.run([args.adb, "-s", args.serial, *command], check=True,
                              capture_output=True, text=True, timeout=45).stdout

    def nodes():
        adb("shell", "uiautomator", "dump", "/data/local/tmp/thermal-ui.xml")
        return list(ET.fromstring(adb("shell", "cat", "/data/local/tmp/thermal-ui.xml")).iter("node"))

    def find(label, snapshot):
        return next((n for n in snapshot if label in (n.get("text"), n.get("content-desc")) or
                     label == "SYNTHETIC · not a measurement" and n.get("text", "").endswith(label)), None)

    def require(label):
        for _ in range(3):
            snapshot = nodes()
            if find(label, snapshot) is not None:
                return snapshot
            time.sleep(0.5)
        raise AssertionError(f"Visible label missing: {label}")

    def action_node(label_node, snapshot):
        bounds = list(map(int, re.findall(r"\d+", label_node.get("bounds"))))
        candidates = []
        for node in snapshot:
            if node.get("clickable") != "true":
                continue
            box = list(map(int, re.findall(r"\d+", node.get("bounds"))))
            if box[0] <= bounds[0] and box[1] <= bounds[1] and box[2] >= bounds[2] and box[3] >= bounds[3]:
                candidates.append(((box[2]-box[0])*(box[3]-box[1]), node))
        if not candidates:
            raise AssertionError("No clickable action contains the label")
        return min(candidates, key=lambda item: item[0])[1]

    def tap(label, occurrence=0):
        snapshot = nodes()
        matches = [n for n in snapshot if label in (n.get("text"), n.get("content-desc"))]
        if len(matches) <= occurrence:
            raise AssertionError(f"Visible control missing: {label}")
        node = action_node(matches[occurrence], snapshot)
        if node.get("enabled") != "true":
            raise AssertionError(f"Control disabled: {label}")
        x0, y0, x1, y1 = map(int, re.findall(r"\d+", node.get("bounds")))
        adb("shell", "input", "tap", str((x0+x1)//2), str((y0+y1)//2))
        time.sleep(0.4)

    def scroll_to(label, prefix=False):
        for _ in range(12):
            snapshot = nodes()
            if find(label, snapshot) is not None or prefix and any(n.get("text", "").startswith(label) for n in snapshot):
                return
            # Use the actual scroll pane, rather than display-relative points
            # that can land on the pinned viewport or capture bar.
            pane = next((n for n in snapshot if n.get("scrollable") == "true"), None)
            if pane is None:
                raise AssertionError("Controls scroll pane is missing")
            x0, y0, x1, y1 = map(int, re.findall(r"\d+", pane.get("bounds")))
            margin = max(20, (y1-y0)//10)
            adb("shell", "input", "swipe", str((x0+x1)//2), str(y1-margin),
                str((x0+x1)//2), str(y0+margin), "400")
        raise AssertionError(f"Control not found after scrolling: {label}")

    # This package has its own preferences/captures and cannot address USB
    # hardware attached to the operator's Pixel through these ADB commands.
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-n", f"{PACKAGE}/com.thereprocase.thermalfield.MainActivity",
        "--ez", "fixture", "true")
    time.sleep(3)
    require("SYNTHETIC · not a measurement")
    tap("Full screen")
    if find("Viewing full screen", nodes()) is not None:
        tap("Got it")
    full_screen = require("Exit full screen")
    nuc = find("Send NUC shutter command", full_screen)
    if nuc is None or action_node(nuc, full_screen).get("enabled") != "false":
        raise AssertionError("Full-screen NUC should be visible and disabled for Demo")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    require("Full screen")
    tap("Full screen")
    tap("Exit full screen")
    require("Full screen")
    tap("Controls")
    tap("View")
    scroll_to("Rotate +90°")
    orientation = next((n.get("text") for n in nodes()
                        if re.fullmatch(r"(?:0|90|180|270)°(?: · preview mirrored)?", n.get("text", ""))), None)
    if orientation is None:
        raise AssertionError("Orientation label is missing")
    degrees = int(orientation.split("°")[0])
    mirrored = orientation.endswith(" · preview mirrored")

    def orientation_label(degrees, mirrored):
        return f"{degrees % 360}°" + (" · preview mirrored" if mirrored else "")

    tap("Rotate +90°")
    require(orientation_label(degrees+90, mirrored))
    tap("Mirror output")
    require(orientation_label(degrees+90, not mirrored))
    tap("Flip 180°")
    require(orientation_label(degrees+270, not mirrored))
    # Return orientation to its initial state for subsequent runs.
    tap("Flip 180°")
    tap("Mirror output")
    for _ in range(3):
        tap("Rotate +90°")
    require(orientation)
    tap("Correction")
    scroll_to("Set emissivity / reflected T")
    tap("Set emissivity / reflected T")
    snapshot = require("EMISSIVITY / REFLECTED TEMPERATURE")
    fields = [n for n in snapshot if n.get("class") == "android.widget.EditText"]
    if len(fields) != 2:
        raise AssertionError("Expected emissivity and reflected-temperature fields")
    original = fields[1].get("text")
    tap("Change temperature sign")
    flipped = [n.get("text") for n in nodes() if n.get("class") == "android.widget.EditText"][1]
    expected = original[1:] if original.startswith("-") else "-" + original.lstrip("+")
    if flipped != expected:
        raise AssertionError("Sign button did not change the reflected-temperature field")
    tap("Change temperature sign")
    tap("Apply inputs")
    tap("Done")
    tap("Full screen")
    tap("Exit full screen")
    tap("Controls")
    tap("Measure")
    scroll_to("Isotherm…")
    tap("Isotherm…")
    tap("Band")
    def threshold_fields():
        return [n for n in nodes() if n.get("class") == "android.widget.EditText"]
    if len(threshold_fields()) != 2:
        raise AssertionError("Band editor should expose two limits")
    tap("Below")
    require("Below threshold °C")
    if len(threshold_fields()) != 1:
        raise AssertionError("Below editor should expose one threshold")
    tap("Above")
    require("Above threshold °C")
    if len(threshold_fields()) != 1:
        raise AssertionError("Above editor should expose one threshold")
    tap("Apply cyan highlight")
    scroll_to("Isotherm above ", prefix=True)
    snapshot = nodes()
    if not any(n.get("text", "").startswith("Isotherm above ") for n in snapshot):
        raise AssertionError("Viewer isotherm summary omitted the active threshold")
    tap("Isotherm…")
    tap("Disable isotherm")
    tap("Done")
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    adb("shell", "am", "start", "-n", f"{PACKAGE}/com.thereprocase.thermalfield.MainActivity")
    require("SYNTHETIC · not a measurement")
    print(json.dumps({"source": "synthetic", "full_screen_button_and_back": "passed",
                      "rotation_mirror_flip": "passed", "full_screen_nuc_disabled_for_demo": "passed", "reflected_sign_button": "passed",
                      "mode_specific_isotherm_fields_and_summary": "passed",
                      "home_return_source_label": "passed", "physical_camera_validation": "not tested"}))


if __name__ == "__main__":
    main()
