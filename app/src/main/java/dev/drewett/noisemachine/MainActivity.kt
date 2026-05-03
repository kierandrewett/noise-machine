package dev.drewett.noisemachine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import dev.drewett.noisemachine.databinding.ActivityMainBinding
import dev.drewett.noisemachine.state.Settings

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val settings = Settings.get(this)
        if (settings.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        // Make sure the service is up before pointing the WebView at the local server.
        NoiseService.start(this)
        promptForPermissionsIfNeeded()

        val wv: WebView = binding.webview
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
        }
        wv.webViewClient = WebViewClient()
        wv.webChromeClient = WebChromeClient()
        // Tiny delay to let the embedded server bind on cold start.
        wv.postDelayed({
            wv.loadUrl("http://127.0.0.1:${settings.port}/")
        }, 350)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webview.canGoBack()) binding.webview.goBack()
                else moveTaskToBack(true)
            }
        })
    }

    override fun onDestroy() {
        // Don't stop the service - the noise machine should keep playing in the background.
        binding.webview.destroy()
        super.onDestroy()
    }

    private fun promptForPermissionsIfNeeded() {
        // Battery optimisation exemption is the only one we ask for proactively
        // (one prompt, returns to the app). DND access is shown as a warning in
        // the web UI; the user can open the system page from there if they want.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")))
                } catch (_: Throwable) {}
            }
        }
    }
}
