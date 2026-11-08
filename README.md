# PDF Viewer Android — Versi 1.0

Aplikasi Android native sederhana untuk membuka dan membaca dokumen PDF dari penyimpanan perangkat.

## Fitur versi awal
- Memilih PDF melalui pemilih dokumen bawaan Android.
- Menampilkan seluruh halaman secara vertikal.
- Menampilkan jumlah halaman dan nama dokumen.
- Menggunakan Android `PdfRenderer` tanpa layanan daring atau izin penyimpanan luas.

## Membuat APK

### Cara A — Android Studio
1. Ekstrak folder proyek.
2. Buka Android Studio, pilih **Open**, lalu pilih folder `PdfViewerAndroid`.
3. Tunggu Gradle Sync selesai dan izinkan Android Studio mengunduh komponen SDK yang diperlukan.
4. Pilih **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
5. APK debug tersedia di `app/build/outputs/apk/debug/app-debug.apk`.

### Cara B — Build otomatis di GitHub (tanpa Android Studio)
1. Buat repository baru di GitHub.
2. Unggah seluruh isi folder proyek ini ke repository (termasuk folder `.github`).
3. Buka tab **Actions** dan pilih workflow **Build Android APK**.
4. Jika workflow belum berjalan otomatis, tekan **Run workflow**.
5. Setelah selesai, buka hasil workflow dan unduh artifact **PdfViewerAndroid-debug-apk**.
6. Ekstrak ZIP artifact tersebut. File `app-debug.apk` adalah file yang dipasang di HP Android.

## Kebutuhan
- Android Studio: JDK 17 dan Android SDK Platform 35.
- GitHub Actions: build menggunakan Java 17 dan Gradle 8.9.

## Catatan
Proyek ini adalah source code Android Studio. APK belum dikompilasi atau diuji pada perangkat fisik dalam sesi ini. Zoom pinch, pencarian teks, bookmark, dan pengelolaan riwayat belum termasuk versi 1.0.
