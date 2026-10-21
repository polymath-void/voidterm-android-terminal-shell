package com.hybridengine.terminal.sheets

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.net.http.SslError
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.hybridengine.terminal.R

/**
 * LinkWebViewBottomSheet: Material Bottom Sheet hosting an interactive In-App WebView.
 * Users can browse links inline, perform web navigation, and toggle to edge-to-edge Full View.
 */
class LinkWebViewBottomSheet : BottomSheetDialogFragment() {

    private var targetUrl: String = "https://google.com"
    private var webView: WebView? = null
    private var progressBar: ProgressBar? = null
    private var tvTitle: TextView? = null
    private var tvUrl: TextView? = null
    private var isFullScreen: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetUrl = arguments?.getString(ARG_URL) ?: "https://google.com"
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let { sheet ->
                val behavior = BottomSheetBehavior.from(sheet)
                behavior.isFitToContents = false
                behavior.skipCollapsed = true
                val density = resources.displayMetrics.density
                behavior.peekHeight = (450 * density).toInt()
                behavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_webview, container, false)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val btnBack = view.findViewById<TextView>(R.id.btn_wv_back)
        val btnForward = view.findViewById<TextView>(R.id.btn_wv_forward)
        val btnRefresh = view.findViewById<TextView>(R.id.btn_wv_refresh)
        val btnFullscreen = view.findViewById<TextView>(R.id.btn_wv_fullscreen)
        val btnClose = view.findViewById<TextView>(R.id.btn_wv_close)

        tvTitle = view.findViewById(R.id.tv_wv_title)
        tvUrl = view.findViewById(R.id.tv_wv_url)
        progressBar = view.findViewById(R.id.wv_progress_bar)
        webView = view.findViewById(R.id.embedded_webview)

        tvUrl?.text = targetUrl

        // Setup Controls
        btnBack.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (webView?.canGoBack() == true) webView?.goBack()
        }

        btnForward.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (webView?.canGoForward() == true) webView?.goForward()
        }

        btnRefresh.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            webView?.reload()
        }

        btnFullscreen.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            toggleFullScreen(btnFullscreen)
        }

        btnClose.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            dismiss()
        }

        // Configure WebView Settings
        webView?.apply {
            setBackgroundColor(Color.parseColor("#1E1F29"))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = true
                displayZoomControls = false
                cacheMode = WebSettings.LOAD_DEFAULT
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (newProgress < 100) {
                        progressBar?.visibility = View.VISIBLE
                        progressBar?.progress = newProgress
                    } else {
                        progressBar?.visibility = View.GONE
                    }
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    tvTitle?.text = title ?: "Untitled"
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    request?.url?.toString()?.let { url ->
                        tvUrl?.text = url
                    }
                    return false
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    tvUrl?.text = url
                }

                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                    handler?.proceed() // Allow self-signed or local dev server certs
                }
            }

            loadUrl(targetUrl)
        }
    }

    private fun toggleFullScreen(toggleBtn: TextView) {
        val d = dialog as? BottomSheetDialog ?: return
        val sheet = d.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        val behavior = BottomSheetBehavior.from(sheet)

        if (!isFullScreen) {
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            val lp = sheet.layoutParams
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            sheet.layoutParams = lp
            toggleBtn.text = "🗗"
            isFullScreen = true
        } else {
            val density = resources.displayMetrics.density
            val lp = sheet.layoutParams
            lp.height = (550 * density).toInt()
            sheet.layoutParams = lp
            behavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED
            toggleBtn.text = "⛶"
            isFullScreen = false
        }
    }

    override fun onDestroyView() {
        webView?.apply {
            stopLoading()
            destroy()
        }
        webView = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "LinkWebViewBottomSheet"
        private const val ARG_URL = "arg_url"

        fun newInstance(url: String): LinkWebViewBottomSheet {
            return LinkWebViewBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_URL, url)
                }
            }
        }
    }
}
