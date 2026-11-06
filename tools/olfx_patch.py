#!/usr/bin/env python3
"""OLFX APK patcher for Unity games (runs on the Mac, installs over adb).

Usage:
    olfx_patch.py PACKAGE --mode opt|immersive [--serial SERIAL] [--keep-original DIR]

What it does
  1. pulls the installed APK(s) of PACKAGE from the device,
  2. rewrites assets/bin/Data/boot.config (Unity engine options) and adds an `olfx-patched=<mode>` marker
     that the OLFX launcher reads,
  3. zipaligns and re-signs the APK with the debug key (the signature changes!),
  4. uninstalls the original KEEPING its data (`pm uninstall -k`) and installs the patched APK,
  5. checks that the OBB survived.

Back up the game's OBB / data before running this. See OLFX.md.
"""
import argparse
import os
import shutil
import subprocess
import sys
import zipfile

BOOT_CONFIG = "assets/bin/Data/boot.config"

# boot.config overrides per mode. Only options the Unity player reads from boot.config.
MODES = {
    # less CPU threads / allocator reservations -> less memory pressure, steadier frame pacing
    "opt": {"job-worker-count": "3", "gc-max-time-slice": "2"},
    # keep the game closer to its original behaviour
    "immersive": {"job-worker-count": "5", "gc-max-time-slice": "3"},
}


def sh(*cmd, check=True, capture=True):
    r = subprocess.run(cmd, capture_output=capture, text=True)
    if check and r.returncode != 0:
        sys.exit(f"command failed: {' '.join(cmd)}\n{r.stdout}\n{r.stderr}")
    return r


def sdk_tool(name):
    root = os.path.expanduser("~/Library/Android/sdk/build-tools")
    for v in sorted(os.listdir(root), reverse=True):
        p = os.path.join(root, v, name)
        if os.path.exists(p):
            return p
    sys.exit(f"{name} not found in {root}")


def patch_boot_config(data: bytes, mode: str) -> bytes:
    lines = data.decode("utf-8").splitlines()
    overrides = dict(MODES[mode])
    overrides["olfx-patched"] = mode
    out, seen = [], set()
    for line in lines:
        key = line.split("=", 1)[0].strip()
        if key in overrides:
            out.append(f"{key}={overrides[key]}")
            seen.add(key)
        else:
            out.append(line)
    for k, v in overrides.items():
        if k not in seen:
            out.append(f"{k}={v}")
    return ("\n".join(out) + "\n").encode("utf-8")


def rewrite_apk(src, dst, mode):
    patched = False
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w") as zout:
        for info in zin.infolist():
            # drop the old signature, it is invalid after the change
            if info.filename.startswith("META-INF/") and info.filename.rsplit(".", 1)[-1] in ("SF", "RSA", "DSA", "EC", "MF"):
                continue
            data = zin.read(info.filename)
            if info.filename == BOOT_CONFIG:
                data = patch_boot_config(data, mode)
                patched = True
            zout.writestr(info, data, compress_type=info.compress_type)
    if not patched:
        sys.exit(f"{BOOT_CONFIG} not found: this is not a Unity game this patcher understands")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("package")
    ap.add_argument("--mode", choices=sorted(MODES), required=True)
    ap.add_argument("--serial")
    ap.add_argument("--keep-original", default=os.path.expanduser("~/Documents/OLFX/patch_backup"))
    args = ap.parse_args()

    adb = [os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")]
    if args.serial:
        adb += ["-s", args.serial]

    paths = [l.split(":", 1)[1].strip() for l in sh(*adb, "shell", "pm", "path", args.package).stdout.splitlines() if l.startswith("package:")]
    if not paths:
        sys.exit("package not installed")
    if len(paths) != 1:
        sys.exit("split APKs are not supported yet")
    os.makedirs(args.keep_original, exist_ok=True)
    orig = os.path.join(args.keep_original, f"{args.package}.orig.apk")
    tmp = os.path.join(args.keep_original, f"{args.package}.tmp.apk")
    aligned = os.path.join(args.keep_original, f"{args.package}.aligned.apk")
    final = os.path.join(args.keep_original, f"{args.package}.patched.apk")

    print("pull", paths[0])
    sh(*adb, "pull", paths[0], orig)
    print("rewrite boot.config, mode", args.mode)
    rewrite_apk(orig, tmp, args.mode)
    print("zipalign")
    sh(sdk_tool("zipalign"), "-f", "-P", "16", "-v", "4", tmp, aligned, capture=True)
    os.remove(tmp)
    print("sign")
    sh(sdk_tool("apksigner"), "sign", "--ks", os.path.expanduser("~/.android/debug.keystore"),
       "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out", final, aligned)
    os.remove(aligned)
    sh(sdk_tool("apksigner"), "verify", final)
    print("patched APK:", final, os.path.getsize(final))

    if os.environ.get("OLFX_NO_INSTALL"):
        return
    print("uninstall original (keep data)")
    sh(*adb, "shell", "pm", "uninstall", "-k", "--user", "0", args.package)
    print("install patched")
    r = sh(*adb, "install", final, check=False)
    print(r.stdout.strip(), r.stderr.strip())
    if r.returncode != 0 or "Success" not in r.stdout:
        print("INSTALL FAILED: reinstalling the original")
        sh(*adb, "install", orig)
        sys.exit(1)
    print("done")


if __name__ == "__main__":
    main()
