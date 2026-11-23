import plistlib
import sys
from pathlib import Path

source, destination, product, version = sys.argv[1:]
info = plistlib.loads(Path(source).read_bytes())
if product == "XDConsole":
    info.update(CFBundleDisplayName="XD Console", CFBundleIdentifier="gg.spacecloud.xdconsole")
elif product != "SpaceConnect":
    sys.exit(f"Invalid macOS product: {product}")
info.update(CFBundleExecutable=product, CFBundleShortVersionString=version, CFBundleVersion=version)
Path(destination).write_bytes(plistlib.dumps(info, sort_keys=False))
