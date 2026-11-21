#!/usr/bin/env python3
"""Stamp/verify a local source-built AAR. Integrity bookkeeping, not a signature."""
import argparse
import hashlib
import pathlib
import subprocess
import tempfile
import zipfile

ASSET = "assets/linqavo-native.properties"
ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")


def source_digest(source):
    files = sorted(p for p in source.rglob("*.go") if not p.name.endswith("_test.go"))
    files += [source / "go.mod", source / "go.sum"]
    records = sorted((p.relative_to(source).as_posix(), p.read_bytes()) for p in files)
    digest = hashlib.sha256()
    for name, content in records:
        digest.update((name + "\0" + hashlib.sha256(content).hexdigest() + "\n").encode())
    return digest.hexdigest()


def inspect(aar):
    with zipfile.ZipFile(aar) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("duplicate AAR entries")
        expected = {f"jni/{abi}/libgojni.so" for abi in ABIS}
        if {n for n in names if n.endswith(".so")} != expected:
            raise ValueError("AAR must contain exactly the four expected JNI libraries")
        return {f"sha256.{abi}": hashlib.sha256(archive.read(f"jni/{abi}/libgojni.so")).hexdigest()
                for abi in ABIS}


def expected_properties(aar, source):
    compiler = next(line.split()[1] for line in (source / "go.mod").read_text().splitlines()
                    if line.startswith("toolchain "))
    return {"format": "1", "api": "2", "source.sha256": source_digest(source),
            "compiler": compiler, **inspect(aar)}


def verify(aar, source):
    expected = expected_properties(aar, source)
    with zipfile.ZipFile(aar) as archive:
        actual = dict(line.split("=", 1) for line in archive.read(ASSET).decode("ascii").splitlines())
    if actual != expected:
        raise ValueError("native provenance differs from current source/compiler/library hashes")


def stamp(aar, source, go):
    properties = expected_properties(aar, source)
    actual_compiler = subprocess.check_output([go, "env", "GOVERSION"], cwd=source, text=True).strip()
    if actual_compiler != properties["compiler"]:
        raise ValueError("compiler differs from go.mod toolchain pin")
    # Replace the archive atomically; never leave a partial AAR on failure.
    with tempfile.NamedTemporaryFile(dir=aar.parent, suffix=".aar", delete=False) as handle:
        temporary = pathlib.Path(handle.name)
    try:
        with zipfile.ZipFile(aar) as original, zipfile.ZipFile(temporary, "w") as result:
            for entry in original.infolist():
                if entry.filename != ASSET:
                    result.writestr(entry, original.read(entry))
            result.writestr(ASSET, "".join(f"{key}={value}\n" for key, value in sorted(properties.items())))
        temporary.replace(aar)
    finally:
        temporary.unlink(missing_ok=True)
    verify(aar, source)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("stamp", "verify"))
    parser.add_argument("--aar", type=pathlib.Path, required=True)
    parser.add_argument("--source", type=pathlib.Path, default=pathlib.Path(__file__).resolve().parent.parent / "wg")
    parser.add_argument("--go", default="go")
    args = parser.parse_args()
    if args.action == "stamp":
        stamp(args.aar.resolve(), args.source.resolve(), args.go)
    else:
        verify(args.aar.resolve(), args.source.resolve())
    print("Native provenance matches source, compiler pin and four library hashes.")
