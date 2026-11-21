# Local audit counterpart of build-wg-aar.sh. Requires prepared trusted tools;
# downloads/installs nothing. The normal build route remains CI.
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
if (-not $env:ANDROID_HOME -or -not $env:ANDROID_NDK_HOME) {
    throw 'Set ANDROID_HOME and ANDROID_NDK_HOME to reviewed SDK/NDK directories.'
}
$env:GOTOOLCHAIN = 'local'
Push-Location (Join-Path $repo 'wg')
try {
    $expected = ((Get-Content go.mod | Where-Object { $_ -like 'toolchain *' }) -split ' ')[1]
    if ((& go env GOVERSION) -ne $expected) { throw "Expected $expected" }
    $mobile = & go list -m -f '{{.Version}}' golang.org/x/mobile
    if ($LASTEXITCODE -ne 0) { throw 'Cannot resolve pinned x/mobile' }
    & go install "golang.org/x/mobile/cmd/gomobile@$mobile"
    if ($LASTEXITCODE -ne 0) { throw 'gomobile build failed' }
    & go install "golang.org/x/mobile/cmd/gobind@$mobile"
    if ($LASTEXITCODE -ne 0) { throw 'gobind build failed' }
    $goPath = & go env GOPATH
    $gomobile = Join-Path $goPath 'bin/gomobile.exe'
    $env:PATH = "$(Join-Path $goPath 'bin');$env:PATH"
    & $gomobile init
    if ($LASTEXITCODE -ne 0) { throw 'gomobile init failed' }
    $out = Join-Path $repo 'android/app/libs/linqavowg.aar'
    New-Item -ItemType Directory -Force (Split-Path $out -Parent) | Out-Null
    & $gomobile bind '-target=android/arm64,android/arm,android/amd64,android/386' `
        -androidapi 26 '-ldflags=-s -w -extldflags=-Wl,-z,max-page-size=16384' -o $out .
    if ($LASTEXITCODE -ne 0) { throw 'Native AAR build failed' }
    & python (Join-Path $repo 'scripts/wg-provenance.py') stamp --aar $out
    if ($LASTEXITCODE -ne 0) { throw 'Native provenance failed' }
    # Read every LOAD header; 64-bit Android must support 16 KiB pages.
    $work = Join-Path $repo 'android/build/native-inspect'
    New-Item -ItemType Directory -Force $work | Out-Null
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($out)
    try {
        foreach ($abi in @('arm64-v8a', 'x86_64')) {
            $library = Join-Path $work "$abi-libgojni.so"
            [IO.Compression.ZipFileExtensions]::ExtractToFile($archive.GetEntry("jni/$abi/libgojni.so"), $library, $true)
            $headers = & "$env:ANDROID_NDK_HOME/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe" -lW $library
            if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect ELF' }
            $loads = @($headers | Where-Object { $_ -match '^\s+LOAD\s' })
            if ($loads.Count -eq 0) { throw 'No ELF LOAD segments' }
            foreach ($load in $loads) {
                if (($load.Trim() -split '\s+')[-1] -notin @('0x4000', '0x10000')) { throw "Bad $abi alignment: $load" }
            }
            Write-Output "$abi ELF alignment passed"
        }
    } finally { $archive.Dispose() }
} finally { Pop-Location }
