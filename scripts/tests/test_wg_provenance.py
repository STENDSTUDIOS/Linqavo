import importlib.util
import pathlib
import tempfile
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location("provenance", pathlib.Path(__file__).resolve().parents[1] / "wg-provenance.py")
provenance = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(provenance)


class NativeProvenanceTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        root = pathlib.Path(self.temporary.name)
        self.source = root / "wg"
        self.source.mkdir()
        (self.source / "go.mod").write_text("module example\n\ngo 1.26.0\ntoolchain go1.27.3\n")
        (self.source / "go.sum").write_text("")
        (self.source / "endpoint.go").write_text("package endpoint\n")
        self.aar = root / "linqavowg.aar"
        self.libraries = {f"jni/{abi}/libgojni.so": abi.encode() for abi in provenance.ABIS}
        self.write_archive()
        self.properties = provenance.expected_properties(self.aar, self.source)

    def write_archive(self, properties=None):
        with zipfile.ZipFile(self.aar, "w") as archive:
            for name, content in self.libraries.items():
                archive.writestr(name, content)
            if properties is not None:
                archive.writestr(provenance.ASSET, "".join(f"{k}={v}\n" for k, v in properties.items()))

    def test_matching_source_and_libraries(self):
        self.write_archive(self.properties)
        provenance.verify(self.aar, self.source)

    def test_stale_source_is_rejected(self):
        self.write_archive(self.properties)
        (self.source / "endpoint.go").write_text("package changed\n")
        with self.assertRaises(ValueError):
            provenance.verify(self.aar, self.source)

    def test_modified_library_is_rejected(self):
        self.libraries["jni/arm64-v8a/libgojni.so"] = b"modified"
        self.write_archive(self.properties)
        with self.assertRaises(ValueError):
            provenance.verify(self.aar, self.source)

    def test_missing_provenance_is_rejected(self):
        with self.assertRaises(KeyError):
            provenance.verify(self.aar, self.source)

    def test_missing_architecture_is_rejected(self):
        del self.libraries["jni/x86/libgojni.so"]
        self.write_archive(self.properties)
        with self.assertRaises(ValueError):
            provenance.verify(self.aar, self.source)


if __name__ == "__main__":
    unittest.main()
