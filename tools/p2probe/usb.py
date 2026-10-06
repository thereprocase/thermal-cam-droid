"""libusb ctypes adapter with bounded transfers, exact lengths and audit log.

Never detaches the kernel driver or claims a UVC interface implicitly. A process
lock protects the single camera mailbox from concurrent diagnostic clients.
Android will supply the same protocol transport from its wrapped USB handle.
"""
import ctypes as c
import ctypes.util
import fcntl
import json
import os
import time
from pathlib import Path

from .protocol import ProtocolError


class Usb:
    def __init__(self, log_path, timeout_ms=1000):
        self.log = open(log_path, 'a', buffering=1)
        self.timeout_ms = timeout_ms
        self.context = c.c_void_p()
        self.handle = None
        self.guard = open('/tmp/p2pro-mailbox.lock', 'a')
        fcntl.flock(self.guard, fcntl.LOCK_EX | fcntl.LOCK_NB)
        self.lib = c.CDLL(ctypes.util.find_library('usb-1.0'))
        self.lib.libusb_init.argtypes = [c.POINTER(c.c_void_p)]
        self.lib.libusb_init.restype = c.c_int
        self.lib.libusb_exit.argtypes = [c.c_void_p]
        self.lib.libusb_open_device_with_vid_pid.argtypes = [c.c_void_p, c.c_uint16, c.c_uint16]
        self.lib.libusb_open_device_with_vid_pid.restype = c.c_void_p
        self.lib.libusb_close.argtypes = [c.c_void_p]
        self.lib.libusb_control_transfer.argtypes = [c.c_void_p, c.c_uint8, c.c_uint8,
            c.c_uint16, c.c_uint16, c.POINTER(c.c_ubyte), c.c_uint16, c.c_uint]
        self.lib.libusb_control_transfer.restype = c.c_int
        self.lib.libusb_error_name.argtypes = [c.c_int]
        self.lib.libusb_error_name.restype = c.c_char_p
        result = self.lib.libusb_init(c.byref(self.context))
        if result:
            self.close()
            raise ProtocolError(f'libusb init {result}')
        self.handle = self.lib.libusb_open_device_with_vid_pid(self.context, 0x0bda, 0x5830)
        if not self.handle:
            self.close()
            raise ProtocolError('P2 Pro unavailable or USB permission denied')

    def transfer(self, direction, mailbox, payload_or_length):
        length = len(payload_or_length) if direction == 'out' else payload_or_length
        buffer = (c.c_ubyte * length)()
        if direction == 'out':
            buffer[:] = payload_or_length
        request_type, request = (0x41, 0x45) if direction == 'out' else (0xc1, 0x44)
        start = time.monotonic()
        result = self.lib.libusb_control_transfer(self.handle, request_type, request,
            0x78, mailbox, buffer, length, self.timeout_ms)
        record = {'utc_ns': time.time_ns(), 'monotonic': start, 'direction': direction,
                  'request_type': request_type, 'request': request, 'value': 0x78,
                  'index': mailbox, 'requested_length': length, 'result': result,
                  'elapsed_ms': (time.monotonic() - start) * 1000,
                  'data_hex': bytes(buffer[:max(result, 0)]).hex() if direction == 'in' else bytes(buffer).hex()}
        self.log.write(json.dumps(record) + '\n')
        if result < 0:
            raise ProtocolError(self.lib.libusb_error_name(result).decode())
        if result != length:
            raise ProtocolError(f'Short {direction} control transfer: {result}/{length}')
        return bytes(buffer)

    def read(self, mailbox, length):
        return self.transfer('in', mailbox, length)

    def write(self, mailbox, data):
        self.transfer('out', mailbox, data)

    def close(self):
        if self.handle:
            self.lib.libusb_close(self.handle)
            self.handle = None
        if self.context:
            self.lib.libusb_exit(self.context)
            self.context = c.c_void_p()
        self.log.close()
        self.guard.close()

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()
