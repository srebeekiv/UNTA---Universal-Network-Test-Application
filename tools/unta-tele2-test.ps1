# UNTA: проверка узлов Tele2 с компьютера Windows 10: порты 80/443, http и https, напрямую и через прокси.
# Запуск (PowerShell):
#   powershell -ExecutionPolicy Bypass -File .\unta-tele2-test.ps1
#   powershell -ExecutionPolicy Bypass -File .\unta-tele2-test.ps1 -Proxy socks5h://127.0.0.1:2080 -Sec 8
# Нужен curl.exe (есть в Windows 10 1803+). Обычный "curl" в PowerShell - это другая команда, поэтому здесь curl.exe.
param(
    [string]$Proxy = "socks5h://127.0.0.1:2080",   # SOCKS5 nekoray на этом же компьютере; "" - без проверки через прокси
    [int]$Sec = 8
)

$hosts = @(
    "speedtest.tele2.net", "vln038-speedtest-2.tele2.net", "zgb-speedtest-1.tele2.net",
    "bks4-speedtest-1.tele2.net", "ams-speedtest-1.tele2.net", "bck-speedtest-1.tele2.net"
)
$inv = [Globalization.CultureInfo]::InvariantCulture
$out = New-Object System.Collections.Generic.List[string]
function Say($t) { Write-Host $t; $out.Add($t) }

function Test-Tcp([string]$h, [int]$port, [int]$ms = 4000) {
    $c = New-Object Net.Sockets.TcpClient
    try {
        $r = $c.BeginConnect($h, $port, $null, $null)
        if ($r.AsyncWaitHandle.WaitOne($ms) -and $c.Connected) { $c.EndConnect($r); return "открыт" }
        return "нет ответа ($([int]($ms/1000)) с)"
    } catch { return "отказ/ошибка" } finally { $c.Close() }
}

function Run-Curl([string]$url, [string[]]$extra) {
    $errFile = [IO.Path]::GetTempFileName()
    $fmt = '%{http_code}|%{remote_ip}|%{time_namelookup}|%{time_connect}|%{time_appconnect}|%{time_starttransfer}|%{size_download}|%{speed_download}'
    $res = & curl.exe -sS -k @extra --connect-timeout 6 -m $Sec -r 0-29999999 -o NUL --stderr $errFile -w $fmt $url
    $rc = $LASTEXITCODE
    $err = (Get-Content $errFile -ErrorAction SilentlyContinue) -join ' '
    Remove-Item $errFile -ErrorAction SilentlyContinue
    $p = ("$res").Trim() -split '\|'
    if ($p.Count -lt 8) { return "FAIL curl код $rc  $err" }
    $code = $p[0]; $size = [double]::Parse($p[6], $inv); $spd = [double]::Parse($p[7], $inv)
    $ms = { param($v) [int]([double]::Parse($v, $inv) * 1000) }
    $dns = & $ms $p[2]; $con = & $ms $p[3]; $tls = & $ms $p[4]; $ttfb = & $ms $p[5]
    $ok = ($code -eq '200' -or $code -eq '206') -and ($rc -eq 0 -or ($rc -eq 28 -and $size -gt 0))
    if ($ok) {
        $tlsd = if ($tls -gt 0) { $tls - $con } else { 0 }
        return ("OK   dns {0} мс, tcp {1}, tls {2}, 1-й байт {3} мс, {4} КБ, {5} Мбит/с [{6}]" -f $dns, [Math]::Max($con - $dns, 0), $tlsd, $ttfb, [int]($size / 1000), [Math]::Round($spd * 8 / 1e6, 1), $p[1]) + $(if ($p[1] -like '127.*' -and $extra.Count -eq 0) { "  <-- ВНИМАНИЕ: адрес 127.x, трафик перехвачен локальным прокси/TUN, это не прямое соединение" } else { "" })
    }
    if ($rc -eq 0) { return "FAIL HTTP $code (ответил, но не файлом) [$($p[1])]" }
    return "FAIL curl код $rc  $err"
}

Say ("UNTA Tele2 test (Windows)  " + (Get-Date -Format "yyyy-MM-dd HH:mm:ss") + "   прокси: " + $(if ($Proxy) { $Proxy } else { "не задан" }))
Say ((& curl.exe -V | Select-Object -First 1))
Say ""

foreach ($h in $hosts) {
    Say "== $h"
    Say ("  TCP 80:  " + (Test-Tcp $h 80))
    Say ("  TCP 443: " + (Test-Tcp $h 443))
    foreach ($scheme in "http", "https") {
        $url = "${scheme}://${h}/1GB.zip"
        Say ("  {0,-5} напрямую : {1}" -f $scheme, (Run-Curl $url @()))
        if ($Proxy) { Say ("  {0,-5} прокси   : {1}" -f $scheme, (Run-Curl $url @("-x", $Proxy))) }
    }
}

$file = Join-Path $PSScriptRoot ("unta_tele2_" + (Get-Date -Format "yyyy.MM.dd-HH.mm.ss") + ".txt")
$out | Set-Content -Encoding UTF8 $file
Say ""
Say "Готово. Отчёт сохранён: $file"
