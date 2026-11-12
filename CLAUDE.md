# CLAUDE.md

Panduan untuk Claude Code saat bekerja di repo ini.

## Proyek

**CursedVIP** adalah plugin Spigot/Paper untuk rank VIP berbasis waktu (key/voucher, give, kedaluwarsa otomatis). Detail produk ada di [PRD.md](PRD.md), riwayat perubahan di [CHANGELOG.md](CHANGELOG.md).

- Target: **Minecraft 1.13 – terbaru**, **Java 8 – 25**.
- Compile terhadap `spigot-api 1.13.2-R0.1-SNAPSHOT` (API tertua yang didukung). Jangan menaikkannya; API baru tidak boleh dipakai. Kalau butuh fitur versi baru, pakai reflection dengan fallback.
- Bytecode Java 8: di JDK 9+ profile `jdk9plus` memakai `--release 8`. Jangan pakai `var`, `List.of`, `String.isBlank`, `InputStream.transferTo`, switch expression, record, atau text block.
- Jangan pakai library yang tidak dijamin ada di semua versi server (contoh: `org.apache.commons.lang`, yang sudah hilang di server baru).
- Folder disinkron via Syncthing. `.gitignore` sudah ada; repo git dibuat oleh pemilik proyek. File IDE tidak disimpan di repo: buka proyek dengan meng-import `pom.xml`.
- Dokumentasi untuk pemilik server ada di `README.md` (bahasa Inggris); dokumen internal di `PRD.md`.

## Build & Test

```powershell
mvn clean package
```

Output: `target/cursedvip-<version>.jar`. Unit test (JUnit 4) mencakup model waktu (`VipDataTest`), konverter 1.x (`LegacyConverterTest`), migrasi `data.yml` (`FlatfileMigrationTest`), dan `MySQLStorage` termasuk migrasinya (`MySQLStorageTest`, memakai H2 mode MySQL, bukan MySQL asli). Alur Bukkit/Vault diverifikasi di server uji.

Maven tidak terpasang global di mesin ini; Maven 3.9.9 sempat dipakai dari folder scratchpad sesi.

## Struktur

```
src/main/java/id/cursedcraft/cursedvip/
  CursedVIP.java          Main: lifecycle, config/bahasa, threading (runStorage/sync), helper Vault, pesan, cache PAPI
  service/VipService.java Semua logika VIP (grant, usekey, settle/kedaluwarsa, remove, changevip, keys)
  storage/
    Storage.java          Interface persistence
    FlatfileStorage.java  data.yml (+ migrasi v1 -> v2)
    MySQLStorage.java     MySQL (PreparedStatement, reconnect, transaksi, migrasi v1 -> v2)
    VipData.java          Model waktu murni: active + expiresAt, paused (remaining ms); expire/add/activate/remove
    LegacyConverter.java  Konversi format 1.x (tanggal + hari) ke VipData
    KeyData.java
  commands/               Parsing argumen & cek permission saja, lalu delegasi ke VipService
  placeholders/CursedPAPI.java  Hanya membaca cache memori
  updater/Updater.java    mergeMissing(): isi key yang hilang dari resource bawaan
  util/HelpUtil.java
src/test/java/...         VipDataTest, LegacyConverterTest, FlatfileMigrationTest, MySQLStorageTest (H2)
```

## Aturan Threading (penting)

- `plugin.runStorage(task)`: di flatfile berjalan inline di main thread, di MySQL berjalan di **satu** thread DB (berurutan). Semua akses `Storage` harus lewat ini.
- Di dalam task storage, **semua** panggilan Bukkit/Vault (perms, `getOnlinePlayers`, dll.) dibungkus `plugin.sync(...)` / `plugin.syncLater(...)`. `plugin.send`, `sendRaw`, dan `broadcast` sudah aman dipanggil dari thread mana pun.
- Semua perubahan VIP lewat `storage().update(uuid, data -> { settle(data, c); ...; return changed; })`, lalu `afterWrite(p, c, d)` (cache, grup Vault, pesan kedaluwarsa). Jangan baca lalu tulis terpisah: di MySQL `update` mengunci baris agar server lain tidak menimpa.
- Mutasi di dalam `update` harus murni (tanpa Bukkit/Vault, tanpa pesan); simpan hasilnya di `Change` lalu bertindak setelah `update` selesai, karena transaksi bisa di-rollback.
- Saat join, `check(p, restore_vip_on_join)` memasang ulang grup VIP aktif (default aktif). Data CursedVIP adalah sumber kebenaran.
- Storage MySQL yang gagal saat startup **harus** mematikan plugin; jangan tambahkan fallback ke flatfile.
- Operasi yang dipicu perintah memakai `plugin.runStorage(sender, ...)` agar pengirim mendapat `storage_error` saat gagal.
- `/usekey` menghapus key sebelum `grant`; bila `grant` gagal, key dibuat ulang. Pertahankan urutan dan pemulihan ini.

## Model Waktu & Data

- Semua waktu adalah epoch millis (`System.currentTimeMillis()`), jangan `nanoTime`. Zona waktu hanya dipakai untuk tampilan (`formatDate`) dan konversi data 1.x.
- Grup aktif: `expiresAt` (terus berjalan). Grup antrean: `paused` remaining ms (beku). Logika ini ada di `VipData` dan harus tetap murni (tanpa Bukkit) agar bisa diuji.
- Skema data ber-versi: flatfile `version: 2`, MySQL `cursedvip_meta.schema_version = 2`. Perubahan format berikutnya **wajib** menaikkan versi dan menambah langkah migrasi (versi N → N+1) di `FlatfileStorage.migrate` / `MySQLStorage.migrateIfNeeded`, dengan backup (flatfile) atau dalam transaksi (MySQL), plus test-nya.
- Jangan hapus tabel/backup 1.x secara otomatis; biar admin yang menghapusnya.
- Nama grup tidak lagi menjadi nama kolom; selalu kirim sebagai parameter `?`.

## Konfigurasi & Bahasa

- Key config/bahasa baru cukup ditambahkan ke `config_template.yml` / `language_en.yml`. `Updater.mergeMissing` otomatis mengisinya ke file user.
- `plugin.msg(key, "%ph%", value, ...)` mengembalikan teks berwarna; key yang hilang fallback ke `language_en.yml` bawaan.
- Key bahasa `mintues` memang salah eja. Jangan di-rename, karena file bahasa user memakainya.

## Versi & Rilis

Versi hanya ditulis di `pom.xml` `<version>`; `plugin.yml` memakai `${project.version}` (hanya `plugin.yml` yang di-filter). Setiap perubahan dicatat di `CHANGELOG.md` bagian `[Unreleased]`.

## Konvensi

- 4 spasi, gaya kode mengikuti file yang ada.
- Command class hanya memvalidasi input (`plugin.parseDays`, `matchVipGroup`, `findPlayer`) dan cek permission (`plugin.hasPerm`); logikanya ada di `VipService`.
- Semua query baru wajib `PreparedStatement`.
