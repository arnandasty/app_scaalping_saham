#!/usr/bin/env node
/**
 * Bulk Push Kode Redeem ke Firestore (auto, tanpa klik manual)
 * -------------------------------------------------------------
 * Pakai Firestore REST API + API Key yang sudah ada di LoginConfig.kt.
 * Tidak butuh google-services.json / service account, karena Rules
 * sudah allow write untuk 3 field (deviceId, activatedAt, activationCount)
 * tapi untuk initial create kita pakai allow true sementara,
 * jadi script ini pakai PATCH create langsung.
 *
 * Cara pakai:
 *   node bulk-push.js --until 2026-11-13 --count 3 --note "Paket 1 bulan"
 *   node bulk-push.js --days 30 --count 5 --note "Batch reseller"
 *   node bulk-push.js --push-existing  # push 3 kode barusan (SCLPR5YVZ7P5 dkk)
 */

const PROJECT_ID = "scalping-assistant";
const API_KEY = "AIzaSyBU7LXc9m_s4fjKg97tEt1Fd_D0KaNIK6Y";

// Same alphabet as generate-code.js
const ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
const crypto = require("crypto");

function makeCode(prefix = "SCLP") {
  const bytes = crypto.randomBytes(8);
  let body = "";
  for (const b of bytes) body += ALPHABET[b % ALPHABET.length];
  return prefix + body;
}

function resolveExpiry(opts) {
  if (opts.until) {
    const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(opts.until.trim());
    if (!m) throw new Error("Format --until harus YYYY-MM-DD");
    return new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]), 23, 59, 59, 999).getTime();
  }
  return Date.now() + opts.days * 24 * 60 * 60 * 1000;
}

function parseArgs(argv) {
  const out = { days: 30, until: null, note: "", count: 1, prefix: "SCLP", pushExisting: false };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    switch (a) {
      case "--days": out.days = Number(argv[++i]); break;
      case "--until": out.until = argv[++i]; break;
      case "--note": out.note = argv[++i]; break;
      case "--count": out.count = Number(argv[++i]); break;
      case "--prefix": out.prefix = String(argv[++i]).toUpperCase(); break;
      case "--push-existing": out.pushExisting = true; break;
      default: console.error(`Opsi tidak dikenal: ${a}`); process.exit(1);
    }
  }
  return out;
}

async function pushDoc(code, doc) {
  // Firestore REST: PATCH /projects/{pid}/databases/(default)/documents/redeem_codes/{code}?key=API_KEY
  const url = `https://firestore.googleapis.com/v1/projects/${PROJECT_ID}/databases/(default)/documents/redeem_codes/${code}?key=${API_KEY}`;
  // Firestore REST expects fields wrapped: { fields: { active:{booleanValue:true}, expiresAt:{integerValue:"..."} } }
  const body = {
    fields: {
      active: { booleanValue: doc.active },
      expiresAt: { integerValue: String(doc.expiresAt) },
      note: { stringValue: doc.note },
      createdAt: { integerValue: String(doc.createdAt) },
    }
  };
  const res = await fetch(url, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${text}`);
  return JSON.parse(text);
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));

  // Mode: push 3 kode yang baru kamu generate (biar nggak generate ulang)
  if (opts.pushExisting) {
    const existing = [
      { code: "SCLPR5YVZ7P5", doc: { active: true, expiresAt: 1794589199999, note: "Paket 1 bulan 13 Okt - 13 Nov 2026", createdAt: 1791559984838 } },
      { code: "SCLPX6R7KFPQ", doc: { active: true, expiresAt: 1794589199999, note: "Paket 1 bulan 13 Okt - 13 Nov 2026", createdAt: 1791559984839 } },
      { code: "SCLPYXX4G2Z9", doc: { active: true, expiresAt: 1794589199999, note: "Paket 1 bulan 13 Okt - 13 Nov 2026", createdAt: 1791559984839 } },
    ];
    console.log(`\nPushing ${existing.length} kode existing ke Firestore...`);
    for (const { code, doc } of existing) {
      process.stdout.write(`  ${code} ... `);
      try { await pushDoc(code, doc); console.log("OK ✅"); }
      catch (e) { console.log(`GAGAL ❌: ${e.message}`); }
    }
    console.log("\nSelesai. Cek di Firestore Console → redeem_codes.\n");
    return;
  }

  // Mode normal: generate + langsung push
  const expiresAt = resolveExpiry(opts);
  const pretty = new Date(expiresAt).toLocaleString("id-ID", { dateStyle: "full", timeStyle: "short" });
  console.log(`\nGenerate & push ${opts.count} kode (sampai ${pretty})...\n`);
  for (let i = 0; i < opts.count; i++) {
    const code = makeCode(opts.prefix);
    const doc = { active: true, expiresAt, note: opts.note || "", createdAt: Date.now() };
    process.stdout.write(`  #${i + 1} ${code} ... `);
    try {
      await pushDoc(code, doc);
      console.log("OK ✅");
    } catch (e) {
      console.log(`GAGAL ❌: ${e.message}`);
      console.log("  Pastikan Rules sudah di-update ke auto mode (B).");
    }
    // small delay biar tidak rate-limit
    await new Promise(r => setTimeout(r, 200));
  }
  console.log("\nSelesai. Cek Firestore Console → Data → redeem_codes.\n");
}

main().catch(e => { console.error(e); process.exit(1); });
