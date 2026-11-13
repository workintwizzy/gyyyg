param(
    [string[]]$Tasks = @('testDebugUnitTest','lintDebug','assembleDebug'),
    [string]$ProxyUrl = ''
)
$ErrorActionPreference = 'Stop'
if (-not $env:JAVA_HOME) {
    $studioJava = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
    if (Test-Path (Join-Path $studioJava 'bin\java.exe')) { $env:JAVA_HOME = $studioJava }
}
if (-not (Test-Path (Join-Path $PSScriptRoot 'local.properties'))) {
    $sdkPath = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    if (-not (Test-Path $sdkPath)) { throw 'Установите Android SDK 37 и укажите ANDROID_HOME.' }
    $sdkValue = $sdkPath.Replace('\','/').Replace(':','\:')
    Set-Content (Join-Path $PSScriptRoot 'local.properties') "sdk.dir=$sdkValue" -Encoding utf8
}
# Java argument files on Windows can misread Cyrillic classpaths. SUBST changes
# only the path used by this build; the source remains in the original directory.
$taskDrive = $null
foreach ($letter in @('Z','Y','X','V','U','T')) {
    if (-not (Test-Path "${letter}:\")) { $taskDrive = "${letter}:"; break }
}
if (-not $taskDrive) { throw 'Нет свободной буквы диска для временного пути сборки.' }
& subst.exe $taskDrive $PSScriptRoot
if ($LASTEXITCODE -ne 0) { throw 'Не удалось создать временный путь SUBST.' }
$buildExitCode = 1
Push-Location "${taskDrive}\"
try {
    $gradleArgs = @('--console=plain')
    if ($ProxyUrl) {
        $proxy = [uri]$ProxyUrl
        $gradleArgs += "-Dhttps.proxyHost=$($proxy.Host)", "-Dhttps.proxyPort=$($proxy.Port)", "-Dhttp.proxyHost=$($proxy.Host)", "-Dhttp.proxyPort=$($proxy.Port)"
    }
    & (Join-Path "${taskDrive}\" "gradlew.bat") -p "${taskDrive}\" @gradleArgs @Tasks
    $buildExitCode = $LASTEXITCODE
} finally {
    Pop-Location
    & subst.exe $taskDrive /D
}
exit $buildExitCode
