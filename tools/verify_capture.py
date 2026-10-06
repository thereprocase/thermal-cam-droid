#!/usr/bin/env python3
"""Verify an Android demo export against its original composite fixture.

Requires Pillow on the development host. Uses direct, finer Planck quadrature
and bisection rather than the app's interpolation table. This checks numerical
and file consistency, not the physical accuracy of a camera or graybody model.
"""
import argparse
import bisect
import json
import math
import struct
from pathlib import Path
from PIL import Image


def radiance(kelvin):
    if kelvin == 0:
        return 0.0
    intervals = 768
    step = 6e-6 / intervals
    total = 0.0
    for index in range(intervals + 1):
        wavelength = 8e-6 + index * step
        exponent = 6.62607015e-34 * 299792458 / (wavelength * 1.380649e-23 * kelvin)
        value = 0 if exponent > 700 else 2 * 6.62607015e-34 * 299792458**2 / (wavelength**5 * math.expm1(exponent))
        weight = 1 if index in (0, intervals) else 4 if index % 2 else 2
        total += weight * value
    return total * step / 3


def corrected(word, metadata):
    if not metadata['correction_applied'] or metadata['emissivity'] == 1:
        return word / 64 - 273.15
    epsilon = metadata['emissivity']
    target = (radiance(word / 64) - (1 - epsilon) * radiance(metadata['reflected_apparent_celsius'] + 273.15)) / epsilon
    if target <= 0 or target > radiance(1100):
        return None
    lower, upper = 0.0, 1100.0
    for _ in range(48):
        middle = (lower + upper) / 2
        if radiance(middle) < target:
            lower = middle
        else:
            upper = middle
    return (lower + upper) / 2 - 273.15


def verify(composite_path, raw_path, metadata_path):
    composite = Path(composite_path).read_bytes()
    assert len(composite) == 196608, 'Reference must be a complete original composite'
    expected = struct.unpack('<49152H', composite[98304:])
    png = Path(raw_path).read_bytes()
    assert png[24:26] == bytes([16, 0]), 'Expected grayscale 16-bit PNG'
    with Image.open(raw_path) as image:
        assert image.size == (256, 192), 'Raw sensor geometry changed'
        actual = tuple(image.getpixel((x, y)) for y in range(192) for x in range(256))
    assert actual == expected, 'Export changed at least one original radiometric word'
    metadata = json.loads(Path(metadata_path).read_text())
    valid = expected
    if metadata['correction_applied'] and metadata['emissivity'] != 1:
        epsilon = metadata['emissivity']
        floor = (1 - epsilon) * radiance(metadata['reflected_apparent_celsius'] + 273.15)
        ceiling = floor + epsilon * radiance(1100)
        unique = sorted(set(expected))
        lower = bisect.bisect_right(unique, floor, key=lambda word: radiance(word / 64))
        upper = bisect.bisect_right(unique, ceiling, key=lambda word: radiance(word / 64))
        valid_words = set(unique[lower:upper])
        valid = tuple(word for word in expected if word in valid_words)
    assert metadata['invalid_pixels'] == len(expected) - len(valid), 'Invalid-pixel count differs'
    words = {'minimum_celsius': min(valid) if valid else None, 'maximum_celsius': max(valid) if valid else None, 'center_celsius': expected[96 * 256 + 128]}
    for key, word in words.items():
        reference = corrected(word, metadata) if word is not None else None
        actual_value = metadata[key]
        if reference is None:
            assert actual_value is None, f'{key}: invalid radiance was exported as a value'
        else:
            assert actual_value is not None and abs(reference - actual_value) < .001, f'{key}: {reference} vs {actual_value}'
    print('All 49,152 raw words preserved; min/max/center match independent band-radiance calculation within 0.001 °C.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('composite'); parser.add_argument('raw_png'); parser.add_argument('sidecar')
    args = parser.parse_args()
    verify(args.composite, args.raw_png, args.sidecar)
