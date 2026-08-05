from __future__ import annotations

import sys
from pathlib import Path


if __package__ in {None, ""}:
    source_root = Path(__file__).resolve().parents[1]
    sys.path.insert(0, str(source_root))

from open_carlink_pc.ui import run_gui


def self_test() -> int:
    from open_carlink_pc.config import load_identity
    from open_carlink_pc.wireless import build_car_name_service_data, build_primary_service_data

    identity = load_identity()
    identity.validate()
    if len(build_primary_service_data(identity)) != 15:
        return 2
    if len(build_car_name_service_data(identity.short_name)) != 16:
        return 3
    return 0


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        raise SystemExit(self_test())
    run_gui(auto_connect="--auto-connect" in sys.argv)
