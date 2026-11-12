# Changelog

Semua perubahan penting pada CursedVIP dicatat di file ini.

Format mengikuti [Keep a Changelog](https://keepachangelog.com/id-ID/1.1.0/), dan versi mengikuti [Semantic Versioning](https://semver.org/lang/id/).

## [Unreleased]

## [2.0.0] - 2026-11-12

Rilis besar setelah 1.2.5. Hampir seluruh kode ditulis ulang. Waktu VIP kini disimpan sebagai **epoch milliseconds**, bukan "tanggal mulai + sisa hari". Data lama **dimigrasikan otomatis** saat server pertama kali start, tanpa reset.

### Cara upgrade dari 1.x
1. Matikan **semua** server yang memakai CursedVIP (terutama bila beberapa server berbagi satu database MySQL).
2. Ganti jar dengan versi 2.0.0, lalu nyalakan server.
3. Cek console untuk pesan `Migration done: ...`, dan untuk peringatan grup VIP yang tidak ada di plugin permission.
4. Downgrade ke 1.x **tidak didukung** setelah migrasi. Gunakan backup di bawah bila perlu kembali.

### Migrasi otomatis
- **Flatfile**: `data.yml` disalin ke `data-v1-backup.yml` sebelum dikonversi, lalu ditandai `version: 2`.
- **MySQL**: data dari `cursedvip_vips`, `cursedvip_changes`, dan `cursedvip_log` disalin ke tabel baru dalam **satu transaksi**. Bila gagal di tengah jalan, semuanya di-rollback. Tabel lama **tidak diubah** dan bisa dihapus manual setelah semua server memakai 2.0.0. Bila beberapa server start bersamaan, migrasi hanya dijalankan sekali (dikunci dengan `SELECT ... FOR UPDATE`).
- Konversinya mempertahankan perilaku 1.x persis: VIP aktif N hari yang dimulai tanggal D berakhir pukul 00:00 pada D+N (zona waktu server), dan VIP lain masuk antrean dengan sisa N hari.
- Grup yang sudah tidak ada di `vip_groups` tetap disimpan di antrean (tidak dibuang), tetapi tidak akan diaktifkan.
- Key (`keys`) dan cooldown `/changevip` dipertahankan.

### Kompatibilitas
- Minecraft **1.13 sampai versi terbaru** (Spigot/Paper). Kode di-compile terhadap `spigot-api 1.13.2` agar tidak ada API yang lebih baru terpakai. Folia tidak didukung.
- Java **8 sampai 25** (bytecode Java 8).
- `Vault` kini `depend` (wajib), karena plugin memang tidak bisa jalan tanpanya. Plugin permission yang mendaftar ke Vault setelah CursedVIP (mis. LuckPerms) kini terdeteksi.
- Tidak lagi memakai `org.apache.commons.lang` (tidak dibundel di server versi baru).
- Driver MySQL dicari sebagai `com.mysql.cj.jdbc.Driver` (Connector/J 8+) lalu `com.mysql.jdbc.Driver`.
- PlaceholderAPI expansion tetap aktif setelah `/papi reload`.

### Diubah
- **Model waktu**: grup aktif menyimpan waktu berakhir, grup lain di antrean menyimpan sisa waktu yang tidak berkurang sampai grup itu aktif. Saat grup aktif habis ketika server mati, grup berikutnya dihitung mulai dari waktu kedaluwarsa sebelumnya.
- Presisi waktu sampai milidetik. Membeli VIP 30 hari pukul 23:59 tidak lagi kehilangan hampir satu hari.
- **Skema MySQL baru** yang ternormalisasi (`cursedvip_players`, `cursedvip_queue`, `cursedvip_cooldowns`, `cursedvip_key_log`, `cursedvip_meta`). Menambah grup tidak lagi butuh `ALTER TABLE`.
- **MySQL yang gagal saat startup kini mematikan plugin**, alih-alih pindah diam-diam ke `data.yml` yang kosong (di 1.x semua VIP bisa diturunkan ke `member` oleh `remove_non_vip`).
- Perintah admin hanya menerima **nama pemain yang persis**. `Ste` tidak lagi mengenai `Steve`.
- `key_length` minimal 8 untuk key baru (key lama yang lebih pendek tetap bisa dipakai). Key dibuat dengan `SecureRandom`.
- `/viptime` dan `/cursedvip info` menampilkan sisa waktu dalam hari+jam (atau jam+menit) beserta tanggal kedaluwarsa.
- Permission dideklarasikan lengkap di `plugin.yml` (`cursedvip.admin` dan `cursedvip.user` dengan children; default tetap `op`).
- `log.txt` menambahkan epoch millis di akhir baris.
- Hook Vault Economy yang tidak terpakai dihapus.

### Ditambahkan
- **Pemulihan rank saat login** (`restore_vip_on_join`, default `true`): pemain dipasang kembali ke grup VIP aktifnya dan grup VIP lain dilepas, walaupun admin sempat mengubah grupnya (mis. `/lp user X parent set member`).
- Pengecekan VIP saat pemain join, sehingga VIP yang kedaluwarsa langsung diproses.
- Peringatan di console bila grup di `vip_groups` atau `default_group` tidak ada di plugin permission.
- Placeholder `%CursedVIP_time_left%` dan `%CursedVIP_expires%`.
- Config `date_format` (default `dd/MM/yyyy HH:mm`).
- Jeda 3 detik setelah key salah, agar key tidak bisa ditebak dengan spam `/usekey`.
- Pesan error ke pemain/admin bila penyimpanan gagal (sebelumnya perintah diam saja).
- Pesan bahasa baru: `player_no_vip_type`, `error_days_max`, `invalid_command`, `help_hint`, `expires_on`, `key_cooldown`, `storage_error`.

### Diperbaiki
- Infinite loop di thread MySQL saat update VIP.
- VIP MySQL yang kedaluwarsa bisa diproses berkali-kali.
- **Key hangus tanpa VIP diberikan** bila penyimpanan gagal di tengah `/usekey`; kini key dikembalikan.
- Key yang dipakai bersamaan di dua server bisa memberi VIP dua kali; kini penukaran atomik.
- Key bisa "terkunci" selamanya (`code_being_process`) bila terjadi error saat penukaran.
- Beberapa server yang berbagi MySQL bisa saling menimpa perubahan VIP (mis. `/cv add` bersamaan dengan `/usekey`); kini setiap perubahan dikunci per pemain.
- `/cursedvip remove <player> <group>` memakai data sender, bukan target; bisa menghapus semua VIP meskipun grup yang diminta tidak dimiliki.
- `/cursedvip info <player>` menampilkan VIP aktif milik sender; crash bila dijalankan dari console.
- `/cursedvip reload` jatuh ke `help`.
- `/cursedvip changedays`: pesan error tertukar, 1 hari ditolak, batas berbeda antar mode. Kini batasnya 1–99999.
- `/cursedvip add` dengan hari non-angka melempar exception; NPE di flatfile bila belum ada VIP.
- `/cursedvip resetcooldown` (flatfile) tidak menyimpan perubahan.
- Pergantian VIP aktif membebankan hari yang sudah berlalu ke grup baru.
- Cooldown `/changevip` overflow bila lebih dari ~35.000 menit.
- `/changevip` tanpa argumen menampilkan `//changevip`; usage `delkeys` menampilkan `delkey`.
- Vault/Bukkit API dipanggil dari thread async; kini semua efek ke pemain dan grup berjalan di main thread.
- `%CursedVIP_is_vip%` NPE di mode MySQL sebelum data dimuat.
- SQL injection: semua query memakai `PreparedStatement`.
- Koneksi MySQL yang terputus tidak pernah tersambung ulang; kini juga ada batas waktu koneksi (10 dtk) dan query (30 dtk).
- Alias `/cv`, `/vips`, `/vip` bisa membajak command plugin lain bernama sama.
- File bahasa yang tidak ada / key yang hilang menyebabkan NPE; kini fallback ke `language_en.yml` bawaan.
- `data.yml` yang rusak tidak lagi ditimpa kosong; plugin berhenti dengan pesan error.
- Nama grup bertitik (`.`) merusak `data.yml`; kini ditolak dengan peringatan.

### Performa
- Semua operasi database berjalan berurutan di satu thread, tidak memblok server.
- Flatfile: perubahan dalam satu tick disimpan sekali di akhir tick.
- MySQL: koneksi hanya di-ping bila sudah menganggur lebih dari 30 detik.

### Catatan
- `%CursedVIP_days_left%` dibulatkan ke atas (sisa 1 jam tampil sebagai `1`).
- Format key dan perintah tidak berubah.

## [1.2.5]

Baseline dokumentasi. Riwayat versi sebelumnya tidak tercatat.

### Fitur
- Sistem key VIP: `/cursedvip newkey`, `keys`, `delkey`, `delkeys`, dan `/usekey` dengan proteksi double-redeem.
- Manajemen VIP: `give`, `add` (massal), `remove` (semua atau per grup), `changedays`, `info`, `resetcooldown`, `reload`.
- Perintah pemain: `/viptime`, `/changevip` (dengan cooldown & permission bypass).
- Banyak VIP per pemain dengan antrian otomatis saat VIP aktif habis.
- Pengecekan kedaluwarsa periodik (`check_time`) dan `remove_non_vip`.
- Storage flatfile (`data.yml`) atau MySQL, dengan fallback otomatis ke flatfile.
- Logging penukaran key ke `log.txt` / tabel `cursedvip_log`.
- Integrasi Vault (permission, wajib) dan PlaceholderAPI (`is_vip`, `active_vip`, `days_left`).
- Auto-migrasi `config.yml` dan `language_en.yml`.
- Alias command: `/cv`, `/vips`, `/vip`.

[Unreleased]: #
[2.0.0]: #
[1.2.5]: #
