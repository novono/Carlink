from __future__ import annotations

import argparse
import asyncio
import time

from winrt.windows.devices.wifidirect import WiFiDirectAdvertisementPublisher
from winrt.windows.devices.radios import Radio, RadioKind, RadioState


async def enable_wifi_radio() -> None:
    access = await Radio.request_access_async()
    print(f"radio access={access}", flush=True)
    radios = await Radio.get_radios_async()
    for radio in radios:
        if radio.kind == RadioKind.WI_FI:
            result = await radio.set_state_async(RadioState.ON)
            print(f"wifi radio state={radio.state} result={result}", flush=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--seconds", type=int, default=20)
    args = parser.parse_args()

    asyncio.run(enable_wifi_radio())

    publisher = WiFiDirectAdvertisementPublisher()
    advertisement = publisher.advertisement
    advertisement.is_autonomous_group_owner_enabled = True
    advertisement.legacy_settings.is_enabled = True
    advertisement.legacy_settings.ssid = "XHCODING 1868"
    advertisement.legacy_settings.passphrase.password = "opencarlink88"

    def status_changed(_sender: object, event: object) -> None:
        print(
            f"status={getattr(event, 'status', None)} error={getattr(event, 'error', None)}",
            flush=True,
        )

    token = publisher.add_status_changed(status_changed)
    try:
        publisher.start()
        print(f"start returned; publisher.status={publisher.status}", flush=True)
        time.sleep(args.seconds)
    finally:
        publisher.stop()
        publisher.remove_status_changed(token)
        print(f"stopped; publisher.status={publisher.status}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
