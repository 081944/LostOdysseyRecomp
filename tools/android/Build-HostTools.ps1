param(
    [string] $SdkRoot = "$env:LOCALAPPDATA/Android/Sdk",
    [string] $NdkVersion = '29.0.14206865',
    [int] $Jobs = 4
)
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path -LiteralPath "$PSScriptRoot/../..").Path
$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
if (-not (Test-Path -LiteralPath $vswhere)) { throw 'vswhere.exe is required to find the installed MSVC and Windows SDK.' }
$vsPath = & $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if ($LASTEXITCODE -ne 0 -or -not $vsPath) { throw 'No Visual Studio installation with C++ tools was found.' }
& "$vsPath/Common7/Tools/Launch-VsDevShell.ps1" -Arch amd64 -HostArch amd64 -SkipAutomaticLocation
$clang = Join-Path $SdkRoot "ndk/$NdkVersion/toolchains/llvm/prebuilt/windows-x86_64/bin/clang-cl.exe"
if (-not (Test-Path -LiteralPath $clang)) { throw "clang-cl is missing: $clang" }
$generatorRoot = Join-Path $repoRoot 'tools/XenonRecomp'
$patch = Join-Path $repoRoot 'tools/patches/XenonRecomp-lostodyssey.patch'
& git -C $generatorRoot apply --reverse --check $patch 2>$null
if ($LASTEXITCODE -ne 0) {
    $changes = & git -C $generatorRoot status --porcelain
    if ($LASTEXITCODE -ne 0 -or $changes) { throw 'Generator dependency has unrelated changes; reconcile its patch before building.' }
    & git -C $generatorRoot apply --check $patch
    if ($LASTEXITCODE -ne 0) { throw 'Generator patch does not match the pinned dependency.' }
    & git -C $generatorRoot apply $patch
    if ($LASTEXITCODE -ne 0) { throw 'Could not apply generator patch.' }
}
$build = Join-Path $repoRoot 'out/build/tools-local'
& cmake -S "$repoRoot/tools/xexdump" -B $build -G Ninja `
    '-DCMAKE_BUILD_TYPE=RelWithDebInfo' "-DCMAKE_C_COMPILER=$clang" "-DCMAKE_CXX_COMPILER=$clang"
if ($LASTEXITCODE -ne 0) { throw 'Host tools configuration failed.' }
& cmake --build $build --target XenonRecomp XenonAnalyse xexdump --parallel $Jobs
if ($LASTEXITCODE -ne 0) { throw 'Host tools build failed.' }
