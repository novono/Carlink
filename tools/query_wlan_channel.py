from __future__ import annotations

import ctypes
import re
import sys
from uuid import UUID
from ctypes import wintypes


class GUID(ctypes.Structure):
    _fields_ = [
        ("data1", wintypes.DWORD),
        ("data2", wintypes.WORD),
        ("data3", wintypes.WORD),
        ("data4", ctypes.c_ubyte * 8),
    ]

    def __str__(self) -> str:
        tail = bytes(self.data4).hex()
        return (
            f"{self.data1:08x}-{self.data2:04x}-{self.data3:04x}-"
            f"{tail[:4]}-{tail[4:]}"
        )


class WLAN_INTERFACE_INFO(ctypes.Structure):
    _fields_ = [
        ("guid", GUID),
        ("description", wintypes.WCHAR * 256),
        ("state", wintypes.DWORD),
    ]


def main() -> int:
    wlan = ctypes.WinDLL("wlanapi.dll")
    handle = wintypes.HANDLE()
    negotiated = wintypes.DWORD()
    result = wlan.WlanOpenHandle(2, None, ctypes.byref(negotiated), ctypes.byref(handle))
    if result:
        raise OSError(result, "WlanOpenHandle failed")
    interfaces = ctypes.c_void_p()
    try:
        result = wlan.WlanEnumInterfaces(handle, None, ctypes.byref(interfaces))
        if result:
            raise OSError(result, "WlanEnumInterfaces failed")
        base = interfaces.value
        if base is None:
            return 1
        count = ctypes.cast(base, ctypes.POINTER(wintypes.DWORD)).contents.value
        offset = 8
        item_size = ctypes.sizeof(WLAN_INTERFACE_INFO)
        for index in range(count):
            item = WLAN_INTERFACE_INFO.from_address(base + offset + index * item_size)
            data_size = wintypes.DWORD()
            data = ctypes.c_void_p()
            value_type = wintypes.DWORD()
            result = wlan.WlanQueryInterface(
                handle,
                ctypes.byref(item.guid),
                8,  # wlan_intf_opcode_channel_number
                None,
                ctypes.byref(data_size),
                ctypes.byref(data),
                ctypes.byref(value_type),
            )
            channel = None
            if result == 0 and data.value is not None and data_size.value >= 4:
                channel = ctypes.cast(data, ctypes.POINTER(wintypes.DWORD)).contents.value
                wlan.WlanFreeMemory(data)
            print(
                f"guid={item.guid} state={item.state} channel={channel} "
                f"description={item.description}"
            )
        for value in sys.argv[1:]:
            guid = GUID.from_buffer_copy(UUID(value.strip("{} ")).bytes_le)
            data_size = wintypes.DWORD()
            data = ctypes.c_void_p()
            value_type = wintypes.DWORD()
            result = wlan.WlanQueryInterface(
                handle,
                ctypes.byref(guid),
                8,
                None,
                ctypes.byref(data_size),
                ctypes.byref(data),
                ctypes.byref(value_type),
            )
            channel = None
            if result == 0 and data.value is not None and data_size.value >= 4:
                channel = ctypes.cast(data, ctypes.POINTER(wintypes.DWORD)).contents.value
                wlan.WlanFreeMemory(data)
            print(f"explicit guid={guid} result={result} channel={channel}")
            profile_xml = ctypes.c_wchar_p()
            profile_flags = wintypes.DWORD(4)
            granted_access = wintypes.DWORD()
            profile_result = wlan.WlanGetProfile(
                handle,
                ctypes.byref(guid),
                "WFD_GROUP_OWNER_PROFILE",
                None,
                ctypes.byref(profile_xml),
                ctypes.byref(profile_flags),
                ctypes.byref(granted_access),
            )
            key = None
            if profile_result == 0 and profile_xml.value:
                match = re.search(r"<keyMaterial>(.*?)</keyMaterial>", profile_xml.value)
                key = match.group(1) if match else None
                wlan.WlanFreeMemory(profile_xml)
            print(f"profile result={profile_result} key={key}")
    finally:
        if interfaces.value is not None:
            wlan.WlanFreeMemory(interfaces)
        wlan.WlanCloseHandle(handle, None)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
