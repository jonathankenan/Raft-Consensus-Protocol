# Panduan Eksekusi Tugas Besar Raft & KV Store

Dokumen ini berisi langkah-langkah lengkap untuk melakukan *build*, menjalankan kluster Raft (3 node), dan menggunakan Client untuk berinteraksi dengan Key-Value Store.

## Prasyarat

Pastikan komputer Anda sudah terinstall:
1.  **Java JDK 17** atau lebih baru.
2.  **Apache Maven** (pastikan command `mvn -version` bisa berjalan di terminal).

---

## Langkah 1: Build Project

Sebelum menjalankan program, kode harus dikompilasi dan semua library (file `.jar`) harus disalin ke folder target agar bisa dibaca saat *runtime*.

Jalankan perintah berikut di terminal (pastikan berada di *root folder* proyek):

```bash
mvn clean package dependency:copy-dependencies
```

PENTING: Pastikan proses ini menghasilkan pesan `BUILD SUCCESS` dan cek apakah folder `target/dependency` sudah terisi dengan file-file .jar (seperti jsonrpc4j, jackson, dll).

---

Langkah 2: Menjalankan Kluster (3 Node)
Untuk mensimulasikan sistem terdistribusi, kita akan menjalankan 3 instance server di terminal yang berbeda. Silakan pilih instruksi sesuai Sistem Operasi Anda karena pemisah classpath berbeda.

### OPSI A: Windows (PowerShell / CMD)
Gunakan pemisah titik koma (;) untuk classpath.

Buka Terminal 1 (Node Leader - Port 8001) Jalankan node pertama. Node ini akan menjadi inisiator Leader.

PowerShell:
```powershell
java -cp "target/classes;target/dependency/*" com.labbrother.Server localhost 8001
```

Buka Terminal 2 (Node Follower - Port 8002) Jalankan node kedua dan arahkan untuk bergabung ke Node 1.

PowerShell:
```powershell
java -cp "target/classes;target/dependency/*" com.labbrother.Server localhost 8002 localhost 8001
```

Buka Terminal 3 (Node Follower - Port 8003) Jalankan node ketiga dan arahkan untuk bergabung ke Node 1.

PowerShell:
```powershell
java -cp "target/classes;target/dependency/*" com.labbrother.Server localhost 8003 localhost 8001
```

### OPSI B: Linux / WSL / macOS
Gunakan pemisah titik dua (:) untuk classpath.

Buka Terminal 1 (Node Leader - Port 8001)

Bash:
```bash
java -cp "target/classes:target/dependency/*" com.labbrother.Server localhost 8001
```

Buka Terminal 2 (Node Follower - Port 8002)

Bash:
```bash
java -cp "target/classes:target/dependency/*" com.labbrother.Server localhost 8002 localhost 8001
```

Buka Terminal 3 (Node Follower - Port 8003)

Bash:
```bash
java -cp "target/classes:target/dependency/*" com.labbrother.Server localhost 8003 localhost 8001
```

---

Langkah 3: Menjalankan Client
Buka Terminal ke-4 untuk menjalankan aplikasi Client. Hubungkan client ke salah satu node yang aktif (biasanya Leader di port 8001).

Windows (PowerShell):
```powershell
java -cp "target/classes;target/dependency/*" com.labbrother.Client localhost 8001
```
Linux / WSL / macOS:
```bash
java -cp "target/classes:target/dependency/*" com.labbrother.Client localhost 8001
```

Langkah 4: Penggunaan (Commands)
Setelah Client terhubung, Anda bisa mengetikkan perintah berikut untuk memanipulasi Key-Value Store:

```text
# 1. Cek Koneksi
> ping
(Output: PONG)

# 2. Menyimpan Data (Set)
> set <key> <value>
Contoh: > set user1 budi
(Output: OK)

# 3. Mengambil Data (Get)
> get <key>
Contoh: > get user1
(Output: budi)

# 4. Menambahkan String (Append)
> append <key> <tambahan>
Contoh: > append user1 _santoso
(Output: OK -> Nilai menjadi "budi_santoso")

# 5. Cek Panjang String (Strln)
> strln <key>
Contoh: > strln user1
(Output: 12)

# 6. Menghapus Data (Del)
> del <key>
Contoh: > del user1
(Output: budi_santoso -> Mengembalikan nilai yang dihapus)
```

---

Skenario Pengujian (Demo)
Gunakan skenario ini untuk memverifikasi fitur Raft berjalan dengan benar.

A. Uji Log Replication
1. Jalankan 3 Node Server (Terminal 1, 2, 3).
2. Di Terminal Client (Terminal 4), lakukan perintah: `set tes 123`.
3. Cek log di terminal Node 2 dan Node 3 (Follower).

Verifikasi: Harusnya muncul log `[APPLY] Log Index ... Command: set tes 123.` Ini artinya data dari Leader sudah tersalin dan diterapkan di Follower.

B. Uji Leader Election (Failover)
1. Pastikan Node 1 adalah Leader (cek terminalnya, harus ada log `[Leader] Mengirim Heartbeat...`).
2. Matikan terminal Node 1 (tekan Ctrl + C).
3. Amati terminal Node 2 dan Node 3.

Verifikasi:
- Salah satu node akan mendeteksi timeout (Log: Heartbeat Timeout).
- Muncul log `!!! ELECTION STARTED !!!`.
- Salah satu node akan menang dan mencetak `$$$ SAYA ADALAH LEADER $$$`.
- Di Client, hubungkan ulang ke port Leader baru (misal port 8002 atau 8003) dan lakukan `get tes`. Data `123` harusnya masih ada dan konsisten.

---

Troubleshooting
Q: Error `Could not find or load main class`?
A: Pastikan Anda menjalankan perintah dari root folder proyek dan perintah `mvn package` sudah sukses sebelumnya. Perhatikan pemisah classpath (`;` untuk Windows, `:` untuk Linux).

Q: Error `NoClassDefFoundError: com/googlecode/jsonrpc4j/...`?
A: Dependensi belum tersalin. Jalankan ulang `mvn dependency:copy-dependencies` dan pastikan folder `target/dependency` tidak kosong.

Q: Client balasan null atau Timeout?
A: Pastikan minimal 2 node server menyala. Raft membutuhkan mayoritas (2 dari 3 node) untuk memproses request `set` atau `append`.

Q: Node Follower malah jadi Leader sendiri?
A: Pastikan saat menjalankan Follower, Anda menyertakan alamat Leader (contact node) di argumen terakhir (contoh: `localhost 8001`). Jika tidak, node akan merasa sendirian dan mengangkat dirinya sendiri jadi Leader.

---

Jika Anda ingin, saya bisa:
- Menambahkan skrip `.bat` atau `.sh` untuk menjalankan 3 node dan client otomatis.
- Mengimplementasikan fitur redirect client agar client otomatis mengikuti leader.

Selamat mencoba!
# Tugas Besar Sistem Paralel dan Terdistribusi 2025

# [Nama Kelompok] - [Kelas]

## Anggota Kelompok

| Nama           | NIM        |
|----------------|------------|
|    Anggota 1            |   NIM 1         |
|    Anggota 2            |   NIM 2         |
|    Anggota 3            |   NIM 3         |

## Status Fitur
<p>Note: status bisa (selesai, setengah jadi, blm selesai)</p><br/>

| Fitur               | Status       |
|---------------------|--------------|
| Heartbeat          | blm selesai  |
| Leader Election    | blm selesai  |
| Log Replication    | blm selesai  |
| Membership Change  | blm selesai  |
| Unit Testing (bonus)| blm selesai  |
| Transaction (bonus) | blm selesai  |
| Log Compaciton (bonus)| blm selesai  |

