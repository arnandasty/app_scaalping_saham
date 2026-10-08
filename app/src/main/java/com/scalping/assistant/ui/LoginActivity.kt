package com.scalping.assistant.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.scalping.assistant.R
import com.scalping.assistant.data.auth.LoginFormat
import com.scalping.assistant.data.auth.LoginRepository
import com.scalping.assistant.data.auth.LoginResult
import kotlinx.coroutines.launch

/**
 * Layar login kode redeem.
 *
 * Pengguna baru memasukkan kode yang Anda terbitkan; kode divalidasi ke server lalu sesi
 * disimpan di perangkat. Bila kode sudah pernah dipakai di perangkat lain, login ditolak
 * (satu kode = satu perangkat).
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var loginRepo: LoginRepository
    private lateinit var etCode: EditText
    private lateinit var btnRedeem: MaterialButton
    private lateinit var tvError: TextView
    private lateinit var layoutLoading: View
    private lateinit var tvLoadingStatus: TextView
    private lateinit var progressTop: View
    private lateinit var tvVersion: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        loginRepo = LoginRepository(applicationContext)

        etCode = findViewById(R.id.etCode)
        btnRedeem = findViewById(R.id.btnRedeem)
        tvError = findViewById(R.id.tvLoginError)
        layoutLoading = findViewById(R.id.layoutLoginLoading)
        tvLoadingStatus = findViewById(R.id.tvLoginLoadingStatus)
        progressTop = findViewById(R.id.loginProgress)
        tvVersion = findViewById(R.id.tvLoginVersion)

        tvVersion.text = "v${packageManager.getPackageInfo(packageName, 0).versionName}"

        etCode.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                attemptRedeem()
                true
            } else false
        }
        btnRedeem.setOnClickListener { attemptRedeem() }

        startGlowAnimation()
        setLoading(false, "")

        // Menampilkan kode tersimpan (bila pengguna logout lalu kembali) agar tidak perlu ketik ulang.
        loginRepo.currentSession()?.code?.let { etCode.setText(it) }

        // Bila sesi sebelumnya masih sah, langsung lanjut ke layar utama tanpa minta kode lagi.
        autoContinueIfSessionValid()
    }

    /**
     * Memeriksa sesi tersimpan secara diam-diam saat layar dibuka.
     * Sesi yang masih berlaku langsung diteruskan ke [com.scalping.assistant.MainActivity];
     * sesi yang sudah kadaluarsa/dicabut otomatis dibersihkan sehingga pengguna melihat form.
     */
    private fun autoContinueIfSessionValid() {
        if (loginRepo.currentSession() == null) return
        setLoading(true, "Memeriksa masa berlaku sesi...")
        lifecycleScope.launch {
            val result = try {
                loginRepo.verifyStoredSession()
            } catch (e: Exception) {
                null
            }
            when (result) {
                is LoginResult.Success -> {
                    com.scalping.assistant.MainActivity.start(this@LoginActivity)
                    finish()
                }
                is LoginResult.Failure -> {
                    setLoading(false, "")
                    if (result.reason == com.scalping.assistant.data.auth.LoginFailReason.EXPIRED ||
                        result.reason == com.scalping.assistant.data.auth.LoginFailReason.REVOKED ||
                        result.reason == com.scalping.assistant.data.auth.LoginFailReason.DEVICE_MISMATCH
                    ) {
                        showError(result.message)
                    }
                }
                null -> setLoading(false, "")
            }
        }
    }

    private fun attemptRedeem() {
        val raw = etCode.text?.toString().orEmpty()
        if (raw.isBlank()) {
            showError("Masukkan kode redeem terlebih dahulu.")
            return
        }
        tvError.visibility = View.GONE
        setLoading(true, "Memverifikasi kode ke server...")

        lifecycleScope.launch {
            val result = try {
                loginRepo.redeem(raw)
            } catch (e: Exception) {
                LoginResult.Failure(
                    com.scalping.assistant.data.auth.LoginFailReason.UNKNOWN,
                    "Terjadi kesalahan: ${e.message ?: "tidak diketahui"}"
                )
            }
            setLoading(false, "")
            when (result) {
                is LoginResult.Success -> {
                    Toast.makeText(this@LoginActivity, "✅ Login berhasil", Toast.LENGTH_SHORT).show()
                    com.scalping.assistant.MainActivity.start(this@LoginActivity)
                    finish()
                }
                is LoginResult.Failure -> {
                    showError(result.message)
                    playShake()
                }
            }
        }
    }

    private fun showError(message: String) {
        tvError.text = "⚠️ $message"
        tvError.visibility = View.VISIBLE
    }

    private fun setLoading(loading: Boolean, status: String) {
        layoutLoading.visibility = if (loading) View.VISIBLE else View.GONE
        tvLoadingStatus.text = status
        btnRedeem.isEnabled = !loading
        etCode.isEnabled = !loading
        btnRedeem.text = if (loading) "Memeriksa..." else "MASUK / AKTIFKAN"
        btnRedeem.backgroundTintList = ColorStateList.valueOf(
            Color.parseColor(if (loading) "#334155" else "#0EA5E9")
        )
    }

    private fun playShake() {
        val anim = ObjectAnimator.ofFloat(etCode, "translationX", 0f, 18f, -18f, 12f, -12f, 6f, -6f, 0f)
        anim.duration = 420
        anim.start()
    }

    /** Animasi denyut lembut pada logo agar layar login terasa hidup. */
    private fun startGlowAnimation() {
        val logo = findViewById<View>(R.id.loginLogo)
        val pulse = ObjectAnimator.ofFloat(logo, "alpha", 1f, 0.55f, 1f).apply {
            duration = 2200
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        pulse.start()

        val scale = ObjectAnimator.ofFloat(logo, "scaleX", 1f, 1.08f).apply {
            duration = 2200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val scaleY = ObjectAnimator.ofFloat(logo, "scaleY", 1f, 1.08f).apply {
            duration = 2200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
        }
        scale.start()
        scaleY.start()
    }
}
