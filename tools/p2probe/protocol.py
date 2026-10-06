"""Mailbox protocol facts from LeoDJ, plus candidate shutter facts from PR #18.

No inference from an ACK to physical effect: callers must record readback and
frame observations. Settings are integers until firmware-specific scales have
been measured. Maintenance/flash commands are intentionally not executable.
"""
import enum
import struct
import threading
import time


class ProtocolError(RuntimeError):
    pass


class Property(enum.IntEnum):
    DISTANCE = 0
    REFLECTED_K = 1
    ATMOSPHERIC_K = 2
    EMISSIVITY = 3
    TRANSMISSION = 4
    HIGH_GAIN = 5


class DeviceInfo(enum.IntEnum):
    CHIP_ID = 0
    COMPILE_DATE = 1
    QUALIFICATION = 2
    SENSOR_INFO = 3
    PROJECT = 4
    FIRMWARE = 5
    PART_NUMBER = 6
    SERIAL = 7
    SENSOR_ID = 8


INFO_LENGTHS = (8, 8, 8, 26, 4, 50, 48, 16, 4)
PALETTES = {1: 'white_hot', 3: 'iron_red', 4: 'rainbow_1', 5: 'rainbow_2',
            6: 'rainbow_3', 7: 'red_hot', 8: 'hot_red', 9: 'rainbow_4',
            10: 'rainbow_5', 11: 'black_hot'}


def standard_header(code, parameter=0, length=0):
    # Standard parameters are LE; SPI addressing is different and excluded.
    return struct.pack('<HI', code, parameter) + struct.pack('>H', length)


def long_header(code, parameter, value=0):
    return struct.pack('<H', code) + struct.pack('>HI', parameter, value)


class Camera:
    def __init__(self, transport, ready_timeout=2.0):
        self.transport = transport
        self.ready_timeout = ready_timeout
        self.lock = threading.RLock()

    def status(self):
        return self.transport.read(0x0200, 1)[0]

    def ready(self):
        deadline = time.monotonic() + self.ready_timeout
        while True:
            status = self.status()
            # Error must take precedence over ready, unlike the old reference.
            if status & 0xfc:
                raise ProtocolError(f'Camera error status 0x{status:02x}')
            if not status & 3:
                return
            if time.monotonic() >= deadline:
                raise TimeoutError('Camera mailbox stayed busy')
            time.sleep(0.001)

    def _read(self, code, parameter, length):
        with self.lock:
            self.ready()
            self.transport.write(0x1d00, standard_header(code, parameter, length))
            self.ready()
            return self.transport.read(0x1d08, length)

    def _write(self, code, parameter=0, payload=b''):
        with self.lock:
            self.ready()
            if not payload:
                self.transport.write(0x1d00, standard_header(code, parameter))
            else:
                if len(payload) > 8:
                    raise ValueError('Only documented small control payloads are supported')
                self.transport.write(0x9d00, standard_header(code, parameter, len(payload)))
                self.ready()
                self.transport.write(0x1d08, payload)
            self.ready()

    def get_property(self, parameter):
        parameter = Property(parameter)
        with self.lock:
            self.ready()
            self.transport.write(0x9d00, long_header(0x8514, parameter))
            self.transport.write(0x1d08, struct.pack('>II', 0, 2))
            self.ready()
            return struct.unpack('>H', self.transport.read(0x1d10, 2))[0]

    def set_property(self, parameter, value):
        parameter = Property(parameter)
        limits = {Property.DISTANCE: 32767, Property.REFLECTED_K: 1024,
                  Property.ATMOSPHERIC_K: 1024, Property.EMISSIVITY: 128,
                  Property.TRANSMISSION: 128, Property.HIGH_GAIN: 1}
        if not isinstance(value, int) or not 0 <= value <= limits[parameter]:
            raise ValueError('Value outside the documented bounded property domain')
        with self.lock:
            self.ready()
            self.transport.write(0x9d00, long_header(0xc514, parameter, value))
            self.transport.write(0x1d08, bytes(8))
            self.ready()

    def device_info(self, item):
        item = DeviceInfo(item)
        return self._read(0x8405, item, INFO_LENGTHS[item])

    def recover_with_firmware_query(self):
        # Explicit experiment: submit an established read-only command after
        # an error status. Normal methods keep their strict preflight checks.
        with self.lock:
            self.transport.write(0x1d00, standard_header(0x8405, 5, 50))
            self.ready()
            return self.transport.read(0x1d08, 50)

    def palette(self):
        return self._read(0x8409, 0, 1)[0]

    def set_palette(self, palette):
        if palette not in PALETTES:
            raise ValueError('Reserved/unknown palettes are excluded')
        self._write(0xc409, 0, bytes([palette]))

    def shutter_reference(self):
        return struct.unpack('>H', self._read(0x840c, 0, 2))[0]

    def current_reference(self):
        return struct.unpack('>H', self._read(0x8b0d, 0, 2))[0]

    def shutter_state(self):
        # PR #18 claims byte 0 auto-enable; byte 1 0=closed / 1=open.
        return self._read(0x830c, 0, 2)

    def set_shutter_control_enabled(self, enabled):
        self._write(0x410c, int(bool(enabled)))

    def set_auto_shutter(self, enabled):
        # Prior-art name retained for existing bench scripts. Matrix tests on
        # P2_V3.07 also observed this flag gating manual actuation; the complete
        # relationship to autonomous periodic calibration remains unresolved.
        self.set_shutter_control_enabled(enabled)

    def set_shutter_closed(self, closed):
        self._write(0x420c, int(bool(closed)))

    def nuc(self):
        # PR #18 sequence: automatic shutter enabled then B_UPDATE. Do not
        # expose open-shutter background calibration as a normal NUC action.
        with self.lock:
            self.set_auto_shutter(True)
            self._write(0xc10d, 0)

    def preview(self, enabled, y16=False):
        code = (0x010a if enabled else 0x020a) if y16 else (0xc10f if enabled else 0x020f)
        self._write(code)

    def spi_read(self, address, length):
        # Documented GET form only. An address has no semantic name until a
        # memory map or measured content establishes one. Bound research reads.
        if not isinstance(address,int) or not isinstance(length,int) or not 0<=address<=0xffffffff or not 1<=length<=512 or address+length>0x100000000:
            raise ValueError('Bounded SPI read must fit the 32-bit address space')
        with self.lock:
            result=b''
            for offset in range(0,length,256):
                count=min(length-offset,256)
                self.ready()
                header=struct.pack('<H',0x8201)+struct.pack('>IH',address+offset,count)
                self.transport.write(0x1d00,header)
                self.ready()
                result+=self.transport.read(0x1d08,count)
            return result

    def snapshot(self):
        return {'properties': {p.name: self.get_property(p) for p in Property},
                'palette': self.palette(), 'shutter_state_hex': self.shutter_state().hex(),
                'shutter_reference': self.shutter_reference(),
                'current_reference': self.current_reference()}
