param(
    [string] $SdkRoot = "$env:LOCALAPPDATA/Android/Sdk",
    [string] $NdkVersion = '29.0.14206865',
    [string] $DxcPath,
    [string] $DxcIncludeDirectory,
    [string] $FfmpegSourceDirectory,
    [string] $ZstdSourceDirectory,
    [string] $BuildDirectory,
    [switch] $CompileOnly,
    [int] $Jobs = 4
)
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path -LiteralPath "$PSScriptRoot/../..").Path
$ndkRoot = Join-Path $SdkRoot "ndk/$NdkVersion"
if (-not $BuildDirectory) { $BuildDirectory = Join-Path $repoRoot 'out/build/android-runtime-local' }
if (-not $DxcPath) { $DxcPath = Join-Path $repoRoot 'out/upstream-deps/dxc/lib/arm64-v8a/libdxcompiler.so' }
if (-not $DxcIncludeDirectory) { $DxcIncludeDirectory = Join-Path $repoRoot 'tools/XenosRecomp/thirdparty/dxc-bin/inc' }
$requiredInputs = @("$ndkRoot/build/cmake/android.toolchain.cmake", "$repoRoot/LostOdysseyRecompLib/ppc/ppc_func_mapping.cpp", "$DxcIncludeDirectory/dxcapi.h")
if (-not $CompileOnly) { $requiredInputs += $DxcPath }
foreach ($path in $requiredInputs) {
    if (-not (Test-Path -LiteralPath $path)) { throw "Required build input is missing: $path" }
}
function Apply-DependencyPatch([string] $Directory, [string] $PatchName) {
    $patch = Join-Path $repoRoot "tools/patches/$PatchName"
    & git -C $Directory apply --ignore-space-change --reverse --check $patch 2>$null
    if ($LASTEXITCODE -eq 0) { return }
    & git -C $Directory apply --ignore-space-change --check $patch
    if ($LASTEXITCODE -ne 0) { throw "Reconcile dependency changes before applying $PatchName" }
    & git -C $Directory apply --ignore-space-change $patch
    if ($LASTEXITCODE -ne 0) { throw "Could not apply $PatchName" }
}
$plume = Join-Path $repoRoot 'thirdparty/plume'
function Test-PlumePatchStack {
    # Overlay patches change the base patch's context. Compare against the full
    # expected stack in a separate index instead of undoing the working tree.
    $indexDirectory = Join-Path $repoRoot 'out/patch-checks'
    New-Item -ItemType Directory -Path $indexDirectory -Force | Out-Null
    $indexPath = Join-Path $indexDirectory ("plume-$([guid]::NewGuid()).index")
    $previousIndex = $env:GIT_INDEX_FILE
    try {
        $env:GIT_INDEX_FILE = $indexPath
        & git -C $plume read-tree HEAD
        if ($LASTEXITCODE -ne 0) { throw 'Could not create the isolated plume patch index.' }
        foreach ($patchName in @('plume-lostodyssey.patch', 'plume-sdl3.patch', 'plume-android.patch')) {
            & git -C $plume apply --cached --ignore-space-change "$repoRoot/tools/patches/$patchName"
            if ($LASTEXITCODE -ne 0) { throw "Expected plume patch stack does not apply: $patchName" }
        }
        & git -C $plume diff --quiet
        if ($LASTEXITCODE -gt 1) { throw 'Could not compare the plume patch stack.' }
        return $LASTEXITCODE -eq 0
    } finally {
        $env:GIT_INDEX_FILE = $previousIndex
        if (Test-Path -LiteralPath $indexPath) { Remove-Item -LiteralPath $indexPath -Force }
    }
}
if (-not (Test-PlumePatchStack)) {
    Apply-DependencyPatch $plume 'plume-lostodyssey.patch'
    Apply-DependencyPatch $plume 'plume-sdl3.patch'
    Apply-DependencyPatch $plume 'plume-android.patch'
    if (-not (Test-PlumePatchStack)) { throw 'Plume differs from the expected Android patch stack.' }
}
Apply-DependencyPatch "$repoRoot/thirdparty/SDL" 'sdl-android-surface-lock.patch'

# Native library intermediates only. APK assembly is deliberately a separate operation.
$sourceOverrides = @()
if ($FfmpegSourceDirectory) {
    if (-not (Test-Path -LiteralPath "$FfmpegSourceDirectory/libavcodec/xma_parser.c")) {
        throw 'FFmpeg source directory does not contain the expected decoder source.'
    }
    $sourceOverrides += "-DFETCHCONTENT_SOURCE_DIR_LO_FFMPEG=$FfmpegSourceDirectory"
}
if ($ZstdSourceDirectory) {
    if (-not (Test-Path -LiteralPath "$ZstdSourceDirectory/build/cmake/CMakeLists.txt")) {
        throw 'Zstandard source directory does not contain its CMake entry point.'
    }
    $sourceOverrides += "-DFETCHCONTENT_SOURCE_DIR_LO_PACK_ZSTD=$ZstdSourceDirectory"
}
& cmake -S $repoRoot -B $BuildDirectory -G Ninja `
    "-DCMAKE_TOOLCHAIN_FILE=$ndkRoot/build/cmake/android.toolchain.cmake" `
    '-DANDROID_ABI=arm64-v8a' '-DANDROID_PLATFORM=android-26' '-DANDROID_STL=c++_shared' `
    "-DLO_DXC_INCLUDE_DIR=$DxcIncludeDirectory" `
    '-DCMAKE_BUILD_TYPE=RelWithDebInfo' '-DLO_BUILD_ANDROID_RUNTIME=ON' '-DLO_BUILD_RUNTIME=ON' `
    '-DLO_BUILD_TOOLS=OFF' '-DLO_BUILD_RECOMP_LIB=ON' '-DLO_BUILD_GPU=ON' '-DLO_BUILD_AUDIO_TESTS=OFF' @sourceOverrides
if ($LASTEXITCODE -ne 0) { throw 'Android native configuration failed.' }
& cmake --build $BuildDirectory --target LostOdysseyRecomp --parallel $Jobs
if ($LASTEXITCODE -ne 0) { throw 'Android native compilation failed.' }
if ($CompileOnly) { return }

$stage = Join-Path $repoRoot 'packaging/android/build/runtime-jni/arm64-v8a'
New-Item -ItemType Directory -Path $stage -Force | Out-Null
$llvm = Join-Path $ndkRoot 'toolchains/llvm/prebuilt/windows-x86_64'
$libraries = @("$BuildDirectory/LostOdysseyRecomp/libmain.so", "$BuildDirectory/thirdparty/SDL/libSDL3.so",
    "$llvm/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so", $DxcPath)
$libraries += @(Get-ChildItem -Path "$BuildDirectory/adrenotools/src/hook/lib*.so" -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName)
foreach ($library in $libraries) {
    if (-not (Test-Path -LiteralPath $library)) { throw "Native library is missing: $library" }
    $header = & "$llvm/bin/llvm-readelf.exe" --file-header $library
    if ($LASTEXITCODE -ne 0 -or -not ($header -match 'AArch64')) { throw "Wrong native architecture: $library" }
    Copy-Item -LiteralPath $library -Destination $stage -Force
}
Write-Output "Native libraries staged at $stage. No APK was created."
