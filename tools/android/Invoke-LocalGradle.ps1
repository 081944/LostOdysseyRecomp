param(
    [Parameter(Mandatory = $true)][string[]] $Tasks,
    [string] $SdkRoot = "$env:LOCALAPPDATA/Android/Sdk",
    [string] $JavaRoot = 'C:/Program Files/Android/Android Studio/jbr',
    [string] $GradleRoot,
    [string] $AgpVersion = '8.13.2',
    [string] $NdkVersion = '29.0.14206865',
    [string] $BuildToolsVersion = '35.0.0',
    [string] $CmakeVersion = '3.31.6',
    [int] $CompileSdk = 36,
    [string[]] $ExtraArguments = @()
)
$ErrorActionPreference = 'Stop'

# Invoke Java directly: no cmd.exe, .bat files, or persistent machine environment changes.
$repoRoot = (Resolve-Path -LiteralPath "$PSScriptRoot/../..").Path
$java = Join-Path $JavaRoot 'bin/java.exe'
if (-not $GradleRoot) {
    $gradleCache = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
    $candidates = @(Get-ChildItem -LiteralPath "$gradleCache/wrapper/dists/gradle-8.13-bin" -Directory -ErrorAction SilentlyContinue |
        ForEach-Object { Join-Path $_.FullName 'gradle-8.13' } |
        Where-Object { Test-Path -LiteralPath "$_/lib/gradle-gradle-cli-main-8.13.jar" })
    if ($candidates.Count -eq 0) { throw 'No installed Gradle 8.13 found; provide -GradleRoot.' }
    $GradleRoot = $candidates[0]
}
$launcher = Join-Path $GradleRoot 'lib/gradle-gradle-cli-main-8.13.jar'
foreach ($path in @($java, $launcher, "$SdkRoot/platforms/android-$CompileSdk/android.jar", "$SdkRoot/ndk/$NdkVersion/source.properties", "$SdkRoot/build-tools/$BuildToolsVersion/source.properties")) {
    if (-not (Test-Path -LiteralPath $path)) { throw "Required local tool is missing: $path" }
}
$previousJava = $env:JAVA_HOME
$previousSdk = $env:ANDROID_HOME
try {
    $env:JAVA_HOME = $JavaRoot
    $env:ANDROID_HOME = $SdkRoot
    & $java '-Dfile.encoding=UTF-8' -classpath $launcher org.gradle.launcher.GradleMain `
        --project-dir "$repoRoot/packaging/android" --console=plain `
        "-PloAgpVersion=$AgpVersion" "-PloCompileSdk=$CompileSdk" `
        "-PloNdkVersion=$NdkVersion" "-PloBuildToolsVersion=$BuildToolsVersion" `
        "-PloCmakeVersion=$CmakeVersion" @Tasks @ExtraArguments
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
} finally {
    $env:JAVA_HOME = $previousJava
    $env:ANDROID_HOME = $previousSdk
}
