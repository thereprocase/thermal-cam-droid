"""Lossless, explicit little-endian decoding of the camera-computed plane."""
import array
import sys


def decode_plane(frame, stride=512):
    if not isinstance(stride, int) or stride < 512 or stride % 2:
        raise ValueError('Stride must be an even integer of at least 512 bytes')
    if len(frame) < stride * 384:
        raise ValueError('Incomplete composite frame')
    plane = array.array('H')
    for y in range(192, 384):
        row = array.array('H')
        row.frombytes(frame[y*stride:y*stride+512])
        if sys.byteorder != 'little':
            row.byteswap()
        plane.extend(row)
    return plane


def celsius(raw):
    return raw / 64.0 - 273.15
