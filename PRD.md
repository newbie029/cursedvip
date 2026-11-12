# PRD — CursedVIP

| | |
|---|---|
| **Produk** | CursedVIP (plugin Spigot/Paper) |
| **Versi saat ini** | 2.0.0 |
| **Author** | Aneryan |
| **Platform** | Spigot/Paper 1.13 – terbaru (compile terhadap `spigot-api 1.13.2`, `api-version: 1.13`), Java 8 – 25 |
| **Status dokumen** | Draft, disusun dari kode yang ada (reverse-engineered) |

---

## 1. Ringkasan

CursedVIP adalah plugin Minecraft untuk mengelola **rank VIP berbasis waktu**. Admin membuat **key** (kode voucher) atau memberi VIP langsung ke pemain. Setiap VIP berlaku selama sejumlah hari, dan plugin otomatis memindahkan pemain ke grup permission yang sesuai lewat Vault. Saat masa VIP habis, pemain dikembalikan ke grup default.

Seorang pemain bisa memiliki beberapa jenis VIP sekaligus (mis. `vip1` 10 hari + `vvip` 30 hari). Hanya satu yang **aktif**, sisanya "antre" dan dipakai setelah yang aktif habis.

## 2. Masalah yang Diselesaikan

- Server yang menjual rank VIP butuh cara untuk menyerahkan rank **tanpa admin harus online** → sistem key/voucher.
- Rank VIP berbatas waktu harus **kedaluwarsa otomatis** tanpa intervensi manual.
- Server dengan banyak tier VIP perlu cara menumpuk dan berpindah antar tier.
- Jaringan multi-server butuh penyimpanan bersama → dukungan MySQL.

## 3. Pengguna

| Persona | Kebutuhan |
|---|---|
| **Pemain** | Menukarkan key, melihat sisa hari VIP, berganti VIP aktif. |
| **Admin / Staff** | Membuat & menghapus key, memberi/mencabut VIP, mengubah durasi, kompensasi massal (tambah hari ke semua pemilik grup). |
| **Pemilik server** | Konfigurasi tier VIP, storage (flatfile/MySQL), pesan & bahasa, integrasi scoreboard via PlaceholderAPI. |

## 4. Fitur

### 4.1 Sistem Key
- Admin membuat key acak (huruf A–Z + angka 0–9, panjang `key_length`, maks 10) untuk grup & jumlah hari tertentu.
- Pemain menukarkan key dengan `/usekey <key>`. Key sekali pakai, dihapus setelah dipakai.
- Proteksi double-redeem: key yang sedang diproses ditolak (`using_codes`).
- Penukaran key dapat dicatat ke log (`log.txt` atau tabel `cursedvip_log`).

### 4.2 Manajemen VIP
- **Give**: beri VIP langsung ke pemain online (bisa dari console).
- **Stacking**: jika pemain sudah punya grup yang sama, hari ditambahkan; jika grup berbeda, ditambahkan sebagai VIP terpisah.
- **Set on acquire** (`set_vip_on_acquire`): VIP yang baru diperoleh langsung jadi VIP aktif.
- **Change VIP**: pemain memilih VIP aktif di antara VIP yang dimiliki, dengan cooldown opsional (`limit_changevip`, `changevip_cooldown`). Permission bypass tersedia.
- **Remove**: cabut semua VIP pemain, atau satu grup tertentu.
- **Change days**: set ulang jumlah hari satu grup VIP milik pemain.
- **Add (massal)**: tambah N hari ke semua pemain yang memiliki grup tertentu.
- **Info / VIP time**: tampilkan VIP aktif dan sisa hari per grup.

### 4.3 Kedaluwarsa Otomatis
- Waktu disimpan sebagai epoch milliseconds. Grup **aktif** punya `expires_at` (terus berjalan, termasuk saat pemain offline). Grup lain ada di **antrean** dengan `remaining_ms` yang beku sampai grup itu diaktifkan.
- Pemain diperiksa saat join dan setiap `check_time` menit.
- **Saat login** (bila `restore_vip_on_join: true`, default), pemain selalu dipasang ke grup VIP aktifnya dan grup VIP lain dilepas, walaupun admin mengubah grupnya secara manual. Data CursedVIP adalah sumber kebenaran untuk rank VIP.
- Jika VIP aktif habis, grup antrean berikutnya (urutan `vip_groups`) aktif mulai dari waktu kedaluwarsa sebelumnya. Jika tidak ada, pemain dikembalikan ke `default_group`.
- Berganti grup aktif (`/changevip`, `set_vip_on_acquire`) membekukan sisa waktu grup sebelumnya ke antrean.
- `remove_non_vip`: pemain yang ada di grup VIP tetapi tidak tercatat di data CursedVIP akan diturunkan ke `default_group`.

### 4.4 Storage
- **Flatfile** (default): `data.yml`.
- **MySQL**: aktif bila `MySQL.enable: true`. Jika koneksi gagal saat startup, plugin mati (tidak ada fallback ke flatfile, agar VIP tidak diturunkan). Koneksi yang terputus saat berjalan disambung ulang otomatis; timeout koneksi 10 dtk, timeout query 30 dtk.
- Setiap perubahan VIP dibaca-ubah-tulis secara atomik (`Storage.update`; di MySQL dengan `SELECT ... FOR UPDATE`).
- Operasi MySQL dijalankan berurutan di satu thread database; semua efek ke pemain/Vault kembali ke main thread.
- Penukaran key bersifat atomik (aman untuk beberapa server yang berbagi satu database).

### 4.5 Integrasi
- **Vault (Permission)**: wajib (`depend`). Bila provider mendaftar setelah CursedVIP (mis. LuckPerms), plugin mencoba lagi setelah server selesai start; jika tetap tidak ada, plugin men-disable dirinya.
- **PlaceholderAPI**: opsional. Placeholder dibaca dari cache memori.

### 4.6 Lokalisasi & Konfigurasi
- File bahasa `language_<kode>.yml`; key yang hilang di `language_en.yml` diisi otomatis saat startup.
- `config.yml` di-generate dari `config_template.yml`; key yang hilang diisi otomatis oleh `Updater`.
- `/cursedvip reload` memuat ulang config & bahasa.

## 5. Command & Permission

| Command | Alias | Fungsi | Permission |
|---|---|---|---|
| `/usekey <key>` | | Tukar key | `cursedvip.usekey` / `cursedvip.user` |
| `/viptime` | | Lihat VIP sendiri | `cursedvip.viptime` / `cursedvip.user` |
| `/changevip <group>` | | Ganti VIP aktif | `cursedvip.changevip` / `cursedvip.user` |
| `/cursedvip help` | `h` | Bantuan | `cursedvip.help` / `cursedvip.user` |
| `/cursedvip newkey <group> <days>` | `nk` | Buat key | `cursedvip.newkey` |
| `/cursedvip keys` | `key`, `k` | Daftar key | `cursedvip.keys` |
| `/cursedvip delkey <key>` | `dk` | Hapus satu key | `cursedvip.delkey` |
| `/cursedvip delkeys` | `dks` | Hapus semua key | `cursedvip.delkeys` |
| `/cursedvip give <player> <group> <days>` | `g` | Beri VIP | `cursedvip.givevip` |
| `/cursedvip add <group> <days>` | `a` | Tambah hari ke semua pemilik grup | `cursedvip.addvip` |
| `/cursedvip remove <player> [group]` | `rm`, `r` | Cabut VIP | `cursedvip.removevip` |
| `/cursedvip changedays <player> <group> <days>` | `cd` | Ubah hari | `cursedvip.changedays` |
| `/cursedvip info <player>` | `i` | Info VIP pemain | `cursedvip.info` |
| `/cursedvip resetcooldown <player>` | `resetcd`, `rcooldown`, `rcd` | Reset cooldown changevip | `cursedvip.resetcooldown` |
| `/cursedvip reload` | | Reload config & bahasa | `cursedvip.reload` |

- `cursedvip.admin` dan OP melewati semua cek permission.
- `cursedvip.changebypass` melewati cooldown `/changevip`.
- Root command `/cursedvip` punya alias `/cv`, `/vips`, `/vip`.

## 6. PlaceholderAPI

| Placeholder | Output |
|---|---|
| `%CursedVIP_is_vip%` | `Yes` / `No` |
| `%CursedVIP_active_vip%` | Nama grup aktif (uppercase) atau `Not VIP` |
| `%CursedVIP_days_left%` | Sisa hari VIP aktif (dibulatkan ke atas), atau `0` |
| `%CursedVIP_time_left%` | Sisa waktu VIP aktif, mis. `12 days and 3 hour(s)` |
| `%CursedVIP_expires%` | Tanggal kedaluwarsa VIP aktif (format `date_format`), atau `-` |

Nilai diambil dari cache memori yang diisi saat join, setiap siklus `check_time`, dan setiap perubahan VIP (kedua mode storage).

## 7. Model Data

Semua waktu adalah epoch milliseconds (UTC). Zona waktu server hanya dipakai saat menampilkan tanggal.

### Flatfile (`data.yml`, versi 2)
```yaml
version: 2
vips:
  <uuid>:
    active: <group>
    expires: <epoch millis>
    paused:
      <group>: <remaining millis>
keys:
  <KEY>: "<group>,<days>"
cooldown:
  <uuid>: <epoch millis>
```

### MySQL (skema versi 2)
| Tabel | Kolom |
|---|---|
| `cursedvip_meta` | `name` PK, `value` (`schema_version`) |
| `cursedvip_players` | `uuid` VARCHAR(36) PK, `active` VARCHAR(64) NULL, `expires_at` BIGINT |
| `cursedvip_queue` | (`uuid`, `vip_group`) PK, `remaining_ms` BIGINT |
| `cursedvip_keys` | `key` VARCHAR(11) PK, `group` VARCHAR(64), `days` INT |
| `cursedvip_cooldowns` | `uuid` PK, `last_change` BIGINT |
| `cursedvip_key_log` | `id` PK, `uuid`, `key_code`, `vip_group`, `days`, `used_at` BIGINT |

### Migrasi dari 1.x
Berjalan otomatis sekali saat startup.
- **Flatfile**: dibuat backup `data-v1-backup.yml` lebih dulu.
- **MySQL**: berjalan dalam satu transaksi dengan lock; tabel lama (`cursedvip_vips`, `cursedvip_changes`, `cursedvip_log`) dibiarkan sebagai backup.
- **Aturan konversi**: `expires_at = 00:00(start) + hari_grup_aktif`, dan grup lain masuk antrean dengan `hari × 86.400.000` ms.

## 8. Kebutuhan Non-Fungsional

- **Kompatibilitas**: Minecraft 1.13 – terbaru (Spigot/Paper), Java 8 – 25. Folia tidak didukung.
- **Performa**: I/O database tidak memblok main thread.
- **Keandalan**: fallback otomatis ke flatfile bila MySQL gagal; `data.yml` yang rusak tidak ditimpa; config & bahasa auto-migrate tanpa menimpa nilai lama.
- **Keamanan**: key baru 8–10 karakter A–Z/0–9 dari `SecureRandom`; jeda 3 detik setelah key salah; perintah admin hanya menerima nama pemain yang persis; semua query memakai `PreparedStatement`.

## 9. Masalah yang Diketahui

Semua 14 masalah dari review v1.2.5 sudah diperbaiki di 1.3.0 (lihat [CHANGELOG.md](CHANGELOG.md)). Batasan yang tersisa:

1. `give`, `remove`, `info`, `changedays`, `resetcooldown` hanya untuk pemain online.
2. Mengganti `MySQL.enable` butuh restart server (`/cursedvip reload` tidak berpindah storage).
3. Bila server tidak membundel driver MySQL atau database tidak bisa dihubungi saat startup, plugin mati dengan pesan error.
4. Kedaluwarsa terdeteksi saat join atau pada siklus `check_time` berikutnya (default 10 menit), tidak tepat pada milidetiknya.
5. Jam server harus akurat (NTP); bila beberapa server berbagi database, jam semuanya harus sinkron.
6. Setelah migrasi ke 2.0.0, downgrade ke 1.x hanya bisa lewat backup.

## 10. Roadmap (Usulan)

| Prioritas | Item |
|---|---|
| P2 | Isi `CursedVipAPI` (cek VIP, beri VIP, event `VipGiveEvent` / `VipExpireEvent`) untuk plugin lain. |
| P2 | Support pemain offline untuk `give` / `remove` / `info`. |
| P3 | Tab-completion, pembelian VIP via Vault Economy, connection pool (HikariCP), dukungan Folia. |

## 11. Di Luar Cakupan

- Penjualan / pembayaran (dilakukan di luar plugin, mis. webstore).
- Manajemen permission node per grup (tanggung jawab plugin permission seperti LuckPerms).
