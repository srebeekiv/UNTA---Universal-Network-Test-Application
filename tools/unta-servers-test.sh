#!/system/bin/sh
# UNTA: проверка всех серверов замера прямо из консоли телефона (root ssh, adb shell, Termux).
# Для каждого сервера пробует IPv4 и IPv6: DNS, TCP, TLS, первый байт, скорость скачивания.
#
#   sh unta-servers-test.sh                      обычная проверка (по 6 секунд на сервер)
#   sh unta-servers-test.sh -t 10                время теста на сервер в секундах
#   sh unta-servers-test.sh -u                   ещё и проверка отдачи (шлёт 3 МБ на серверы с upload)
#   sh unta-servers-test.sh -p socks5h://127.0.0.1:9080     всё через прокси (curl -x)
#   sh unta-servers-test.sh -o cf,tele2          только указанные коды серверов
#   sh unta-servers-test.sh -s                   те же серверы, но http:// заменён на https:// (порт 443 вместо 80)
#   sh unta-servers-test.sh -6                   проверять ещё и IPv6 (по умолчанию только IPv4)
#   sh unta-servers-test.sh -d 94.140.14.14      DNS для curl (по умолчанию берётся из системы)
#
# curl в консоли Android собран с c-ares и сам системный DNS не видит, поэтому DNS передаётся явно.
#
# Результат печатается на экран и сохраняется в /data/local/tmp/unta_servers_<дата>.txt
# (если туда писать нельзя, то в $TMPDIR или /tmp)
# Сертификаты не проверяются (-k): у curl в консоли телефона часто нет бандла CA.

PX=""; T=6; UP=0; ONLY=""; DNS=""; V6=0; TLS=0
while [ $# -gt 0 ]; do
    case "$1" in
        -p) PX="-x $2"; shift ;;
        -t) T="$2"; shift ;;
        -u) UP=1 ;;
        -6) V6=1 ;;
        -s) TLS=1 ;;
        -o) ONLY="$2"; shift ;;
        -d) DNS="$2"; shift ;;
        -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
        *) echo "неизвестный параметр: $1"; exit 1 ;;
    esac
    shift
done

command -v curl >/dev/null 2>&1 || { echo "curl не найден"; exit 1; }
if [ -z "$DNS" ]; then
    DNS=""
    for ip in $(dumpsys connectivity 2>/dev/null | grep -m1 -o 'DnsAddresses: \[[^]]*\]' | grep -o '[0-9]\{1,3\}\(\.[0-9]\{1,3\}\)\{3\}'); do
        case ",$DNS," in *",$ip,"*) ;; *) DNS="${DNS:+$DNS,}$ip" ;; esac
    done
    DNSSRC="взяты из системы (dumpsys connectivity)"
    if [ -z "$DNS" ]; then DNS="1.1.1.1,8.8.8.8"; DNSSRC="определить не удалось, взяты 1.1.1.1 и 8.8.8.8 (результат может отличаться от DNS вашей сети; задайте свой через -d)"; fi
else
    DNSSRC="заданы вручную"
fi
DNSARG="--dns-servers $DNS"
UA="Mozilla/5.0 (Linux; Android) UNTA-test"
D=/data/local/tmp; [ -d "$D" ] && [ -w "$D" ] || D="${TMPDIR:-/tmp}"
OUT="$D/unta_servers_$(date +%Y.%m.%d-%H.%M.%S).txt"
ERR="$D/unta_curl_err.$$"
UPF="$D/unta_up.$$.bin"
trap 'rm -f "$ERR" "$UPF"' EXIT

# код | подпись | URL скачивания ({BYTES}/{MB} подставляются) | URL отдачи или - | режим отдачи
LIST='cf|Cloudflare|https://speed.cloudflare.com/__down?bytes={BYTES}|https://speed.cloudflare.com/__up|multipart
tele2|Tele2-Anycast|http://speedtest.tele2.net/1GB.zip|http://speedtest.tele2.net/upload.php|put
tele2-hr|Tele2-HR|http://zgb-speedtest-1.tele2.net/1GB.zip|http://zgb-speedtest-1.tele2.net/upload.php|put
tele2-de|Tele2-DE|http://fra36-speedtest-1.tele2.net/1GB.zip|http://fra36-speedtest-1.tele2.net/upload.php|put
tele2-lv|Tele2-LV|http://bks4-speedtest-1.tele2.net/1GB.zip|http://bks4-speedtest-1.tele2.net/upload.php|put
tele2-lt|Tele2-LT|http://vln038-speedtest-2.tele2.net/1GB.zip|http://vln038-speedtest-2.tele2.net/upload.php|put
tele2-nl|Tele2-NL|http://ams-speedtest-1.tele2.net/1GB.zip|http://ams-speedtest-1.tele2.net/upload.php|put
tele2-se-got|Tele2-SE-Got|http://bck-speedtest-1.tele2.net/1GB.zip|http://bck-speedtest-1.tele2.net/upload.php|put
tele2-se-sto|Tele2-SE-Sto|http://hgd-speedtest-1.tele2.net/1GB.zip|http://hgd-speedtest-1.tele2.net/upload.php|put
de-hetzner|Hetzner-DE|https://fsn1-speed.hetzner.com/100MB.bin|-|-
fi-hetzner|Hetzner-FI|https://hel1-speed.hetzner.com/100MB.bin|-|-
us-hetzner|Hetzner-US|https://ash-speed.hetzner.com/100MB.bin|-|-
sg-hetzner|Hetzner-SG|https://sin-speed.hetzner.com/100MB.bin|-|-
fr-gra|OVH-Gravelines|https://lil2-gra.speedtest.network.ovh.net/files/1Gb.dat|-|-
fr-rbx|OVH-Roubaix|https://lil1-rbx.speedtest.network.ovh.net/files/1Gb.dat|-|-
uk-eri|OVH-Erith|https://lon1-eri.speedtest.network.ovh.net/files/1Gb.dat|-|-
ca-bhs|OVH-Beauharnois|https://ymq1-bhs.speedtest.network.ovh.net/files/1Gb.dat|-|-
us-hil|OVH-Hillsboro|https://pdx1-hil.speedtest.network.ovh.net/files/1Gb.dat|-|-
au-syd|OVH-Sydney|https://syd1-sy2.speedtest.network.ovh.net/files/1Gb.dat|-|-
in-bom|OVH-Mumbai|https://bom1-ynm1.speedtest.network.ovh.net/files/1Gb.dat|-|-
librespeed-ams|LibreSpeed-Clouvider-AMS|https://ams.speedtest.clouvider.net/backend/garbage.php?ckSize={MB}|https://ams.speedtest.clouvider.net/backend/empty.php|post-raw'

# секунды с дробью ("0.012345") -> миллисекунды целым числом
ms() { x=$(echo "$1" | sed 's/\(.*\)\.\(...\).*/\1\2/; s/^0*//'); echo "${x:-0}"; }
# байт/с ("8765432.000") -> "70.1" Мбит/с
mbit() { b="${1%.*}"; b="${b:-0}"; v=$((b * 8 / 100000)); echo "$((v / 10)).$((v % 10))"; }
errtxt() { tr '\n' ' ' < "$ERR" | cut -c1-100; }
rcname() {
    case "$1" in
        6) echo "DNS не резолвит имя" ;;
        7) echo "соединение отклонено или нет маршрута" ;;
        28) echo "таймаут" ;;
        35|51|58|60) echo "ошибка TLS" ;;
        52|56) echo "сервер оборвал соединение" ;;
        *) echo "curl код $1" ;;
    esac
}

test_dl() { # $1 = -4|-6|"" ; $2 = URL
    fam="$1"; url="$2"; rng=""
    [ "$TLS" = 1 ] && url=$(echo "$url" | sed 's#^http://#https://#')
    case "$url" in
        *'{BYTES}'*) u=$(echo "$url" | sed 's/{BYTES}/30000000/') ;;
        *'{MB}'*) u=$(echo "$url" | sed 's/{MB}/30/') ;;
        *) u="$url"; rng="-r 0-29999999" ;;
    esac
    res=$(curl -sS -k $fam $PX $DNSARG -A "$UA" --connect-timeout 8 -m "$T" $rng -o /dev/null \
        -w '%{http_code}|%{remote_ip}|%{time_namelookup}|%{time_connect}|%{time_appconnect}|%{time_starttransfer}|%{size_download}|%{speed_download}' \
        "$u" 2>"$ERR" </dev/null)
    rc=$?
    OLDIFS="$IFS"; IFS='|'; set -- $res; IFS="$OLDIFS"
    code="${1:-000}"; ip="${2:--}"; dns=$(ms "${3:-0}"); con=$(ms "${4:-0}"); tls=$(ms "${5:-0}"); ttfb=$(ms "${6:-0}"); size="${7:-0}"; spd="${8:-0}"
    if { [ "$rc" = 0 ] || { [ "$rc" = 28 ] && [ "$size" != 0 ]; }; } && { [ "$code" = 200 ] || [ "$code" = 206 ]; }; then
        tcpd=$((con - dns)); [ "$tcpd" -lt 0 ] && tcpd=0
        tlsd=0; [ "$tls" -gt 0 ] && tlsd=$((tls - con))
        printf '  OK    %s  dns %s мс, tcp %s, tls %s, 1-й байт %s мс, скачано %s КБ, %s Мбит/с  [%s]\n' "$lbl" "$dns" "$tcpd" "$tlsd" "$ttfb" "$((size / 1000))" "$(mbit "$spd")" "$ip"
    elif [ "$rc" = 0 ]; then
        printf '  FAIL  %s  HTTP %s (сервер ответил, но не файлом)  [%s]\n' "$lbl" "$code" "$ip"
    else
        extra=""
        # соединение не прошло: отвечает ли хост хотя бы на ICMP-пинг? (различает «хост мёртв» и «порт фильтруется»)
        if [ -z "$PX" ] && { [ "$rc" = 28 ] || [ "$rc" = 7 ]; } && command -v ping >/dev/null 2>&1; then
            tgt="$ip"; [ -z "$tgt" ] || [ "$tgt" = "-" ] && tgt=$(echo "$u" | sed 's#^[a-z]*://##; s#[:/].*##')
            if ping -c 1 -W 2 "$tgt" >/dev/null 2>&1; then extra="  [$tgt: ICMP-пинг ЕСТЬ, хост жив, а TCP не проходит]"; else extra="  [$tgt: ICMP-пинга тоже нет]"; fi
        fi
        printf '  FAIL  %s  %s: %s%s\n' "$lbl" "$(rcname "$rc")" "$(errtxt)" "$extra"
    fi
}

test_ul() { # $1 = URL  $2 = режим
    [ "$TLS" = 1 ] && set -- "$(echo "$1" | sed 's#^http://#https://#')" "$2"
    [ -s "$UPF" ] || head -c 3000000 /dev/urandom > "$UPF"
    case "$2" in
        put) set -- "$1" -T "$UPF" ;;
        multipart) set -- "$1" -F "file=@$UPF" ;;
        *) set -- "$1" --data-binary "@$UPF" -H 'Content-Type: application/octet-stream' ;;
    esac
    u="$1"; shift
    res=$(curl -sS -k $PX $DNSARG -A "$UA" --connect-timeout 8 -m 20 -o /dev/null \
        -w '%{http_code} %{size_upload} %{speed_upload}' "$@" "$u" 2>"$ERR" </dev/null)
    rc=$?
    set -- $res
    if [ "$rc" = 0 ] && [ "${1:-000}" -ge 200 ] 2>/dev/null && [ "${1:-000}" -lt 300 ]; then
        printf '  OK    отдача: HTTP %s, %s КБ, %s Мбит/с\n' "$1" "$((${2:-0} / 1000))" "$(mbit "${3:-0}")"
    else
        printf '  FAIL  отдача: HTTP %s %s %s\n' "${1:-000}" "$( [ "$rc" != 0 ] && rcname "$rc")" "$(errtxt)"
    fi
}

run_all() {
echo "UNTA: проверка серверов  $(date '+%Y-%m-%d %H:%M:%S')  время теста ${T} с${PX:+  прокси: ${PX#-x }}"
echo "curl: $(curl -V | head -1)"
echo "DNS для curl: $DNS ($DNSSRC)"
echo

echo "$LIST" | while IFS='|' read -r code name dl ul mode; do
    [ -n "$ONLY" ] && case ",$ONLY," in *",$code,"*) ;; *) continue ;; esac
    echo "== $code  ($name)"
    if [ -n "$PX" ]; then fams="proxy"; elif [ "$V6" = 1 ]; then fams="-4 -6"; else fams="-4"; fi
    for f in $fams; do
        case "$f" in -4) lbl="IPv4"; fam="-4" ;; -6) lbl="IPv6"; fam="-6" ;; *) lbl="прокси"; fam="" ;; esac
        test_dl "$fam" "$dl"
    done
    if [ "$UP" = 1 ] && [ "$ul" != "-" ]; then test_ul "$ul" "$mode"; fi
done

echo
echo "== LibreSpeed: официальный список серверов"
curl -sS -k $PX $DNSARG -A "$UA" --connect-timeout 8 -m 15 -o $D/librespeed_servers.json \
    -w '  HTTP %{http_code}, %{size_download} байт\n' "https://librespeed.org/backend-servers/servers.json" 2>"$ERR" </dev/null \
    || echo "  FAIL: $(errtxt)"
if [ -s $D/librespeed_servers.json ]; then
    echo "  серверов в списке: $(grep -o '"server"' $D/librespeed_servers.json | wc -l)"
    echo "  начало файла:"; head -c 700 $D/librespeed_servers.json | sed 's/^/    /'; echo
fi

echo
echo "== Определение внешнего IP"
for u in "https://ipinfo.io/json" "http://ip-api.com/json" "https://speed.cloudflare.com/cdn-cgi/trace"; do
    echo "  $u"
    curl -sS -k -4 $PX $DNSARG -A "$UA" --connect-timeout 8 -m 10 "$u" 2>"$ERR" </dev/null | head -c 300 | sed 's/^/    /'
    [ -s "$ERR" ] && echo "    ошибка: $(errtxt)"
    echo
done

echo "Готово. Отчёт сохранён: $OUT"
}

run_all 2>&1 | tee "$OUT"
