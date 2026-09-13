# ================================================================
# 一键：编译 Debug APK -> 安装到已连接真机 -> 启动应用
# 用法：双击同目录下的「一键安装到手机.bat」，或右键本文件“使用 PowerShell 运行”
#              powershell -ExecutionPolicy Bypass -File build_and_install.ps1
# 说明：真机需用 USB 连接并开启“USB 调试”。每次更新只需运行本脚本，无需再手动找包。
# ================================================================
$ErrorActionPreference = 'Continue'
$NoPause = ($env:BUILD_INSTALL_NOPAUSE -eq '1')   # 设置 BUILD_INSTALL_NOPAUSE=1 可跳过末尾暂停（自动化用）

$ROOT      = 'd:\aid\169'
$JAVA_HOME = 'D:\android\jbr'
$ADB       = 'D:\AndroidSDK\platform-tools\adb.exe'
$PKG       = 'com.momo.tunit'                          # 应用标识（applicationId）
$ACTIVITY  = 'com.mirwanda.nottiled.MainActivity'      # Activity 的 Java 包名不变
$APK       = Join-Path $ROOT 'app\build\outputs\apk\debug\app-debug.apk'
$LATEST    = Join-Path $ROOT 'NotTiled-latest.apk'   # 固定名字的最新包，方便随时取用

$env:JAVA_HOME = $JAVA_HOME
Set-Location $ROOT

function Fail($msg) {
    Write-Host ""
    Write-Host ("[失败] " + $msg) -ForegroundColor Red
    if (-not $NoPause) { Read-Host "按回车键退出" }
    exit 1
}

# ---------- 0. 环境自检 ----------
if (-not (Test-Path $ADB))      { Fail ("找不到 adb：" + $ADB + "`n请在脚本里修改 `$ADB 为你的 platform-tools\adb.exe 路径") }

# ---------- 1. 编译 Debug APK ----------
# 默认每次做“干净重建”(:app:clean)，确保外部源码目录(NotTiled_src)的改动一定被编进包，杜绝“装了个旧包”。
# 若想图快可设环境变量 BUILD_INSTALL_FAST=1 跳过 clean（增量编译，可能漏掉外部改动）。
Write-Host ""
Write-Host "=== [1/3] 编译 Debug APK ===" -ForegroundColor Cyan
$fast = ($env:BUILD_INSTALL_FAST -eq '1')
if ($fast) {
    Write-Host "（增量编译 BUILD_INSTALL_FAST=1）" -ForegroundColor DarkGray
    & .\gradlew.bat :app:assembleDebug --offline --console=plain
} else {
    Write-Host "（干净重建，保证是最新源码；设 BUILD_INSTALL_FAST=1 可改为增量）" -ForegroundColor DarkGray
    & .\gradlew.bat :app:clean :app:assembleDebug --offline --console=plain
}
if ($LASTEXITCODE -ne 0) { Fail "编译失败（EXITCODE=$LASTEXITCODE）" }
if (-not (Test-Path $APK)) { Fail ("没找到 APK：" + $APK) }
Copy-Item -Path $APK -Destination $LATEST -Force
$apkInfo = Get-Item $APK
$apkHash = (Get-FileHash $APK -Algorithm SHA256).Hash
Write-Host ("APK 已生成：" + $APK) -ForegroundColor Green
Write-Host ("  构建时间：" + $apkInfo.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss') + "   大小：" + [math]::Round($apkInfo.Length/1KB) + " KB") -ForegroundColor Green
Write-Host ("  SHA256  ：" + $apkHash) -ForegroundColor Green
Write-Host ("最新包已同步为：" + $LATEST) -ForegroundColor Green

# ---------- 2. 检查设备 ----------
Write-Host ""
Write-Host "=== [2/3] 检查已连接设备 ===" -ForegroundColor Cyan
$list = & $ADB devices
$list | ForEach-Object { Write-Host ("  " + $_) }
$online = @($list | Where-Object { $_ -match "^\S+\s+device$" })
if ($online.Count -eq 0) {
    Write-Host ""
    Write-Host "未检测到已连接的设备。" -ForegroundColor Yellow
    Write-Host "请确认：① 手机已 USB 连接电脑；② 已开启开发者选项里的“USB 调试”；③ 手机上如弹出授权请点“允许”。" -ForegroundColor Yellow
    Fail "没有可用设备"
}
Write-Host ("检测到 " + $online.Count + " 台设备。") -ForegroundColor Green

# ---------- 3. 安装并启动 ----------
Write-Host ""
Write-Host "=== [3/3] 安装到手机 ===" -ForegroundColor Cyan
& $ADB install -r -d $APK
if ($LASTEXITCODE -ne 0) { Fail "安装失败（EXITCODE=$LASTEXITCODE）" }

# 核对手机里刚装的是不是这个包（比对安装时间与版本号，避免“装了个旧的”错觉）
$installed = (& $ADB shell dumpsys package $PKG 2>$null) -join "`n"
$instTime = ''; $instVer = ''
if ($installed -match 'lastUpdateTime=([0-9\-:\s]+)') { $instTime = $Matches[1].Trim() }
if ($installed -match 'versionCode=(\d+)')               { $instVer  = $Matches[1] }
Write-Host ("手机上已安装：versionCode=" + $instVer + "   安装时间=" + $instTime) -ForegroundColor Green

Write-Host "安装成功，正在启动应用…" -ForegroundColor Green
& $ADB shell am start -n ($PKG + "/" + $ACTIVITY) | Out-Null

Write-Host ""
Write-Host "全部完成：已编译、安装并启动。" -ForegroundColor Green
if (-not $NoPause) { Read-Host "按回车键关闭" }
