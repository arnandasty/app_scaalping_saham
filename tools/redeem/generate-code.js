#!/usr/bin/env node
/**
 * Generator Kode Redeem — Scalping AI Assistant
 * ------------------------------------------------------------
 * Membuat kode redeem baru beserta JSON siap-tempel untuk
 * Cloud Firestore (Firebase Console → Firestore → Start collection).
 *
 * Tidak butuh kredensial apa pun: cukup menyalin hasil JSON ke Console.
 *
 * Cara pakai (dari folder ini):
 *   node generate-code.js --days 30
 *   node generate-code.js --until 2026-12-31 --note "Budi - paket 1 bulan"
 *   node generate-code.js --days 7 --count 5
 *   node generate-code.js --days 30 --prefix GO
 *
 * Opsi:
 *   --days <n>        Masa berlaku N hari dari sekarang (default: 30)
 *   --until <tanggal> Masa berlaku sampai tanggal YYYY-MM-DD (23:59 lokal). Menimpa --days
 *   --note <teks>     Catatan bebas (mis. nama pembeli)
 *   --count <n>       Jumlah kode yang dibuat sekaligus (default: 1)
 *   --prefix <teks>   Awalan kode (default: SCLP)
 *   --json            Cetak hanya JSON (tanpa penjelasan)
 */

const crypto = require("crypto");

// Karakter tanpa huruf/angka yang mudah tertukar (0/O, 1/I/L) supaya tidak salah ketik.
const ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";

function parseArgs(argv) {
    const out = { days: 30, until: null, note: "", count: 1, prefix: "SCLP", json: false };
    for (let i = 0; i < argv.length; i++) {
        const a = argv[i];
        switch (a) {
            case "--days": out.days = Number(argv[++i]); break;
            case "--until": out.until = argv[++i]; break;
            case "--note": out.note = argv[++i]; break;
            case "--count": out.count = Number(argv[++i]); break;
            case "--prefix": out.prefix = String(argv[++i]).toUpperCase(); break;
            case "--json": out.json = true; break;
            case "--help":
            case "-h":
                console.log(require("fs").readFileSync(__filename, "utf8").split("*/")[0]);
                process.exit(0);
            default:
                console.error(`Opsi tidak dikenal: ${a} (pakai --help)`);
                process.exit(1);
        }
    }
    if (!Number.isFinite(out.days) || out.days <= 0) {
        console.error("Nilai --days harus angka > 0.");
        process.exit(1);
    }
    if (!Number.isFinite(out.count) || out.count < 1) {
        console.error("Nilai --count harus angka >= 1.");
        process.exit(1);
    }
    return out;
}

/** Menghitung epoch-millis kadaluarsa dari opsi --until / --days. */
function resolveExpiry(opts) {
    if (opts.until) {
        // Format YYYY-MM-DD → akhir hari (23:59:59.999) waktu lokal.
        const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(opts.until.trim());
        if (!m) {
            console.error("Format --until harus YYYY-MM-DD, mis. 2026-12-31");
            process.exit(1);
        }
        const d = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]), 23, 59, 59, 999);
        if (isNaN(d.getTime())) {
            console.error("Tanggal --until tidak valid.");
            process.exit(1);
        }
        return d.getTime();
    }
    return Date.now() + opts.days * 24 * 60 * 60 * 1000;
}

/** Kode acak: PREFIX + 8 karakter aman-ketik. Contoh: SCLP7K2M9QX4. */
function makeCode(prefix) {
    const bytes = crypto.randomBytes(8);
    let body = "";
    for (const b of bytes) body += ALPHABET[b % ALPHABET.length];
    return prefix + body;
}

function main() {
    const opts = parseArgs(process.argv.slice(2));
    const expiresAt = resolveExpiry(opts);
    const pretty = new Date(expiresAt).toLocaleString("id-ID", { dateStyle: "full", timeStyle: "short" });

    const results = [];
    for (let i = 0; i < opts.count; i++) {
        results.push({
            code: makeCode(opts.prefix),
            doc: {
                active: true,
                expiresAt: expiresAt,            // number (epoch millis) — dibaca aplikasi
                note: opts.note || "",
                createdAt: Date.now(),
            },
        });
    }

    if (opts.json) {
        const payload = opts.count === 1 ? results[0].doc : results.map(r => r.doc);
        console.log(JSON.stringify(payload, null, 2));
        return;
    }

    console.log("");
    console.log("========================================================");
    console.log("  KODE REDEEM BARU — Scalping AI Assistant");
    console.log("========================================================");
    console.log(`  Berlaku sampai : ${pretty}`);
    console.log(`  (epoch millis) : ${expiresAt}`);
    console.log(`  Jumlah kode    : ${opts.count}`);
    console.log("--------------------------------------------------------");

    for (let i = 0; i < results.length; i++) {
        const r = results[i];
        console.log("");
        console.log(`  #${i + 1}  KODE  →  ${r.code}`);
        console.log("");
        console.log("  Salin JSON berikut ke Firebase Console → Firestore →");
        console.log(`  Collection "redeem_codes" → Add document → ID: ${r.code}`);
        console.log("");
        console.log(JSON.stringify(r.doc, null, 2).split("\n").map(l => "    " + l).join("\n"));
    }

    console.log("");
    console.log("========================================================");
    console.log("  LANGKAH DI FIREBASE CONSOLE");
    console.log("========================================================");
    console.log("  1. Buka https://console.firebase.google.com → pilih project Anda");
    console.log("  2. Menu Firestore Database → tab Data");
    console.log('  3. Jika koleksi "redeem_codes" belum ada: klik "Start collection"');
    console.log("  4. Add document → Document ID = kode di atas");
    console.log("  5. Tambahkan field sesuai JSON, lalu Save.");
    console.log("  6. Untuk mencabut kode: ubah field active menjadi false.");
    console.log("  7. Untuk memperpanjang: ubah field expiresAt (angka epoch millis).");
    console.log("");
    console.log("  Tips: butuh banyak kode? Jalankan dengan --count 5,");
    console.log("  lalu tempel satu JSON per pecahan.");
    console.log("");
}

main();
