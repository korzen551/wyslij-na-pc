# Wyślij na PC

Mała apka na Androida, która wysyła pliki dowolnej wielkości z telefonu na komputer przez prywatną sieć [Tailscale](https://tailscale.com).

**Pobierz apkę:** https://github.com/korzen551/wyslij-na-pc/releases/latest/download/wysylacz.apk

## Jak to działa

- `wysylacz/` – apka na Androida. Wybierasz pliki (albo używasz „Udostępnij” w galerii), apka wysyła je na komputer.
- `odbiornik/` – program na komputer (Python, bez dodatkowych bibliotek). Zapisuje odebrane pliki do folderu `inbox`.

Odbiornik słucha tylko na adresie Tailscale komputera, więc pliki mogą wysyłać wyłącznie urządzenia zalogowane do tej samej sieci Tailscale.

## Uruchomienie

1. Zainstaluj Tailscale na komputerze i telefonie, zaloguj się na obu tym samym kontem.
2. Na komputerze uruchom `odbiornik/start_odbiornik.bat`.
3. W apce wpisz adres Tailscale komputera (`100.x.y.z`) i wysyłaj.

## Budowanie apki

```bash
bash wysylacz/build.sh
```

Wymaga Android SDK (build-tools 35, platforma 35) i JDK 17 lub nowszego.
