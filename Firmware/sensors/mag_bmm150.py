"""BMM150 magnetometer wrapper for micropython_bmm150 driver."""

from micropython_bmm150 import bmm150


class BMM150:
    CANDIDATE_ADDRS = (0x10, 0x11, 0x12, 0x13)

    def __init__(self, i2c, address=None):
        self.i2c = i2c
        self.address = address if address is not None else self._detect_address()
        if self.address is None:
            raise RuntimeError("BMM150 not found at addresses 0x10-0x13")
        self._device = bmm150.BMM150(self.i2c, address=self.address)

    def _detect_address(self):
        devices = self.i2c.scan()
        for addr in self.CANDIDATE_ADDRS:
            if addr in devices:
                return addr
        return None

    def read_mag(self):
        raw = None

        # Different MicroPython BMM150 packages expose different APIs.
        if hasattr(self._device, "measurements"):
            # This library returns (x, y, z, hall).
            vals = self._device.measurements
            raw = (vals[0], vals[1], vals[2])
        elif hasattr(self._device, "magnetic"):
            raw = self._device.magnetic
        elif hasattr(self._device, "geomagnetic"):
            raw = self._device.geomagnetic
        elif hasattr(self._device, "read_magnetic"):
            raw = self._device.read_magnetic()
        elif hasattr(self._device, "get_magnetic"):
            raw = self._device.get_magnetic()
        elif hasattr(self._device, "get_geomagnetic"):
            raw = self._device.get_geomagnetic()
        elif (
            hasattr(self._device, "get_x")
            and hasattr(self._device, "get_y")
            and hasattr(self._device, "get_z")
        ):
            raw = (self._device.get_x(), self._device.get_y(), self._device.get_z())
        elif (
            hasattr(self._device, "x")
            and hasattr(self._device, "y")
            and hasattr(self._device, "z")
        ):
            raw = (self._device.x, self._device.y, self._device.z)

        if raw is None:
            raise RuntimeError("Unsupported BMM150 driver API")

        mx, my, mz = self._coerce_xyz(raw)
        return {"mx": mx, "my": my, "mz": mz}

    def _coerce_xyz(self, raw):
        if isinstance(raw, dict):
            if "x" in raw and "y" in raw and "z" in raw:
                return raw["x"], raw["y"], raw["z"]
            if "mx" in raw and "my" in raw and "mz" in raw:
                return raw["mx"], raw["my"], raw["mz"]

        if isinstance(raw, (tuple, list)) and len(raw) >= 3:
            return raw[0], raw[1], raw[2]

        if hasattr(raw, "x") and hasattr(raw, "y") and hasattr(raw, "z"):
            return raw.x, raw.y, raw.z

        raise RuntimeError("Unable to parse BMM150 reading format")

