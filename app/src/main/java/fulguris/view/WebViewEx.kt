package fulguris.view

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintJob
import android.print.PrintManager
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.WebView
import androidx.core.text.parseAsHtml
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import fulguris.R
import fulguris.di.configPrefs
import fulguris.extensions.ihs
import fulguris.extensions.snackbar
import android.view.Gravity
import timber.log.Timber

/**
 * Specialising WebView could be useful at some point.
 * We may want to get rid of [WebPageTab].
 *
 * We used that to try debug our issue with ALT + TAB scrolling back to the top of the page.
 * We could not figure out that issue though.
 */
class WebViewEx : WebView {
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        initNestedScrollDetection()
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        initNestedScrollDetection()
    }

    //
    lateinit var proxy: WebPageTab

    /**
     * True when the current touch gesture started on a nested CSS scrollable element
     * (e.g. a div with overflow:auto/scroll whose content overflows).
     * Set from JavaScript via [NestedScrollBridge] on `touchstart`, reset on `touchend`/`touchcancel`.
     * Read from [PullRefreshLayout.canChildScrollUp] on the UI thread.
     */
    @Volatile
    var isTouchOnNestedScrollable: Boolean = false

    @SuppressLint("JavascriptInterface")
    private fun initNestedScrollDetection() {
        addJavascriptInterface(NestedScrollBridge(), NESTED_SCROLL_JS_INTERFACE)
        addJavascriptInterface(BlobDownloadBridge(), BLOB_JS_INTERFACE)
    }

    /**
     * Set this callback before calling evaluateJavascript with the blob-reading script.
     * It will be invoked on the main thread with the data URL ("data:<mime>;base64,...")
     * and the filename captured from the HTML5 download attribute (may be empty).
     */
    var onBlobDownload: ((dataUrl: String, filename: String) -> Unit)? = null

    /**
     * Map of URL → filename, populated by [BlobDownloadBridge.onFilename]
     * when BlobHook.js intercepts an anchor click whose `download` attribute
     * names the file (blob: and ordinary URLs alike — the WebView's download
     * listener never receives the attribute itself). Consumed (removed) by
     * [LightningDownloadListener] when the matching download starts, so the
     * dialog and the saved file use the attribute's name.
     */
    val blobFilenames: MutableMap<String, String> = mutableMapOf()

    /**
     * Set to true when the user confirmed a blob download via the early dialog shown
     * from [BlobDownloadBridge.onConfirmDownload]. [LightningDownloadListener] checks
     * this to skip showing a duplicate dialog when onDownloadStart fires.
     */
    @Volatile
    var blobDownloadPreConfirmed: Boolean = false

    /**
     * JavaScript interface that lets injected page scripts tell us whether the current
     * touch gesture started on a nested scrollable CSS element.
     */
    inner class NestedScrollBridge {
        @JavascriptInterface
        fun setNestedScrollable(value: Boolean) {
            isTouchOnNestedScrollable = value
        }
    }

    /**
     * JavaScript interface for receiving blob data URLs produced by the blob-download script.
     */
    inner class BlobDownloadBridge {
        @JavascriptInterface
        fun onData(dataUrl: String, filename: String) {
            val cb = onBlobDownload ?: return
            onBlobDownload = null
            Handler(Looper.getMainLooper()).post { cb(dataUrl, filename) }
        }

        @JavascriptInterface
        fun onError(message: String) {
            onBlobDownload = null
            Timber.w("Blob download JS error: %s", message)
        }

        /**
         * Called by BlobHook.js when it intercepts a click on an anchor with a
         * download attribute (blob: or ordinary URLs). This fires BEFORE
         * onDownloadStart, allowing the download dialog to show the correct
         * filename (the WebView's download listener never receives the
         * download attribute).
         */
        @JavascriptInterface
        fun onFilename(blobUrl: String, filename: String) {
            synchronized(blobFilenames) {
                // Entries are consumed by LightningDownloadListener when the
                // download starts; cap the map so a page that fires many
                // anchor clicks without downloads can't grow it unbounded.
                if (blobFilenames.size > 64) blobFilenames.clear()
                blobFilenames[blobUrl] = filename
            }
        }

        /**
         * Called by BlobHook.js when a large Response.blob() download is in progress.
         * Reports progress (loaded/total bytes) so the browser can show a progress bar.
         */
        @JavascriptInterface
        fun onProgress(loaded: Long, total: Long) {
            val percent = if (total > 0) (loaded * 100 / total).toInt().coerceIn(0, 100) else 0
            Handler(Looper.getMainLooper()).post {
                (context as? fulguris.activity.WebBrowserActivity)?.onBlobDownloadProgress(percent)
            }
        }

        /**
         * Called by BlobHook.js when a Response with Content-Disposition: attachment
         * is about to be read as a blob. Shows the download confirmation dialog
         * BEFORE the body is fetched, so the user can decide immediately.
         *
         * @param callbackId  JS callback identifier (window[callbackId](true/false))
         * @param filename    filename extracted from headers or URL
         * @param totalBytes  body size as a string (to avoid JS number issues)
         * @param contentType MIME type from the response
         */
        @JavascriptInterface
        fun onConfirmDownload(callbackId: String, filename: String, totalBytes: String, contentType: String) {
            // Validate callbackId to prevent JS injection
            if (!callbackId.matches(Regex("^_bc\\d+$"))) return

            val size = totalBytes.toLongOrNull() ?: 0L
            val sizeStr = if (size > 0) Formatter.formatFileSize(context, size)
                else context.getString(R.string.unknown_file_size)
            val displayName = filename.ifBlank { "download" }

            // Detect file type for display
            val ext = displayName.substringAfterLast('.', "").lowercase()
            val fileType = if (contentType.isNotBlank() && contentType != "application/octet-stream") {
                contentType
            } else if (ext.isNotEmpty()) {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: contentType.ifBlank { context.getString(R.string.unknown_file_type) }
            } else {
                contentType.ifBlank { context.getString(R.string.unknown_file_type) }
            }

            val message = context.getString(R.string.dialog_download_message, displayName, fileType, sizeStr)

            Handler(Looper.getMainLooper()).post {
                MaterialAlertDialogBuilder(context)
                    .setIcon(R.drawable.ic_download_outline)
                    .setTitle(R.string.dialog_download_title)
                    .setMessage(message.parseAsHtml())
                    .setPositiveButton(R.string.action_download) { _, _ ->
                        blobDownloadPreConfirmed = true
                        // Show pending snackbar immediately, before the body is fetched
                        (context as? android.app.Activity)?.let { activity ->
                            val gravity = if (activity.configPrefs.toolbarsBottom) Gravity.TOP else Gravity.BOTTOM
                            activity.snackbar(activity.getString(R.string.download_pending) + ' ' + displayName, gravity)
                        }
                        evaluateJavascript("window['$callbackId'](true)", null)
                    }
                    .setNegativeButton(R.string.action_cancel) { _, _ ->
                        evaluateJavascript("window['$callbackId'](false)", null)
                    }
                    .setOnCancelListener {
                        evaluateJavascript("window['$callbackId'](false)", null)
                    }
                    .show()
            }
        }
    }

    companion object {
        const val NESTED_SCROLL_JS_INTERFACE = "_fulgurisScroll"
        const val BLOB_JS_INTERFACE = "_fulgurisBlobDownload"
    }

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {

        /*
        if (event?.keyCode == KeyEvent.KEYCODE_TAB) {
            Log.v("WebViewEx","Tab: " + event.action.toString())
        }
        */

        return super.dispatchKeyEvent(event)
    }

    /**
     * Start a print job, thus notably enabling saving a web page as PDF.
     */
    fun print() : PrintJob {
        val printManager: PrintManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val printAdapter: PrintDocumentAdapter = createPrintDocumentAdapter(title as String)
        val jobName = title as String
        val builder: PrintAttributes.Builder = PrintAttributes.Builder()
        builder.setMediaSize(PrintAttributes.MediaSize.ISO_A4)
        return printManager.print(jobName, printAdapter, builder.build())
    }

    /**
     * We shied away from overriding [WebView.destroy] so here we have that function instead.
     */
    private fun destruction() {
        Timber.d("destruction")
        stopLoading()
        onPause()
        clearHistory()
        visibility = View.GONE
        removeAllViews()
        destroyDrawingCache()
        destroy()
    }

        /**
     * Tell if this object should be destroyed.
     */
    private var iNeedDestruction = false

        @Volatile
    var backgroundAudioEnabled: Boolean = true

    override fun onWindowVisibilityChanged(visibility: Int) {
        if (backgroundAudioEnabled && visibility != View.VISIBLE && !iNeedDestruction) {
            super.onWindowVisibilityChanged(View.VISIBLE)
            injectBackgroundMediaScript()
        } else {
            super.onWindowVisibilityChanged(visibility)
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        if (backgroundAudioEnabled && visibility != View.VISIBLE && !iNeedDestruction) {
            super.onVisibilityChanged(changedView, View.VISIBLE)
            injectBackgroundMediaScript()
        } else {
            super.onVisibilityChanged(changedView, visibility)
        }
    }

    override fun onPause() {
        if (backgroundAudioEnabled && !iNeedDestruction) {
            injectBackgroundMediaScript()
            return
        }
        super.onPause()
    }

    fun injectBackgroundMediaScript() {
        if (!backgroundAudioEnabled || iNeedDestruction) return
        val js = """
            (function() {
                if (window.__fulgurisBgMediaActive) return;
                window.__fulgurisBgMediaActive = true;
                try {
                    Object.defineProperty(document, 'hidden', { get: function() { return false; }, configurable: true });
                    Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });
                    Object.defineProperty(document, 'webkitHidden', { get: function() { return false; }, configurable: true });
                    Object.defineProperty(document, 'webkitVisibilityState', { get: function() { return 'visible'; }, configurable: true });
                } catch (e) {}

                var blockEvent = function(e) {
                    if (window.__fulgurisBgMediaEnabled !== false) {
                        e.stopImmediatePropagation();
                    }
                };
                document.addEventListener('visibilitychange', blockEvent, true);
                document.addEventListener('webkitvisibilitychange', blockEvent, true);
                window.addEventListener('pagehide', blockEvent, true);
                window.addEventListener('blur', blockEvent, true);

                var bindVideo = function(v) {
                    if (!v || v.__fulgurisBound) return;
                    v.__fulgurisBound = true;
                    v.addEventListener('ended', function() {
                        if (window.__fulgurisBgMediaEnabled === false) return;
                        setTimeout(function() {
                            if (!v.ended && !v.paused) return;
                            var nextBtn = document.querySelector(
                                '.ytp-next-button, ' +
                                'button[aria-label*="Siguiente" i], ' +
                                'button[aria-label*="Next" i], ' +
                                '[data-testid="next-button"], ' +
                                'ytm-player-controls button[aria-label*="Siguiente" i], ' +
                                'ytm-player-controls button[aria-label*="Next" i]'
                            );
                            if (nextBtn && nextBtn.getAttribute('aria-disabled') !== 'true' && !nextBtn.disabled) {
                                nextBtn.click();
                            }
                        }, 1500);
                    }, false);
                };

                var scanVideos = function() {
                    document.querySelectorAll('video, audio').forEach(bindVideo);
                };
                scanVideos();
                new MutationObserver(scanVideos).observe(document.documentElement || document.body, { childList: true, subtree: true });
            })();
            window.__fulgurisBgMediaEnabled = true;
        """.trimIndent()
        post { evaluateJavascript(js, null) }
    }

    fun setBackgroundMediaState(enabled: Boolean) {
        backgroundAudioEnabled = enabled
        if (enabled) {
            onResume()
            resumeTimers()
            injectBackgroundMediaScript()
        } else {
            post { evaluateJavascript("window.__fulgurisBgMediaEnabled = false;", null) }
        }
    }

    /**
     * Does just that.

     */
    fun destroyIfNeeded() {

        Timber.d("destroyIfNeeded: $iNeedDestruction")
        if (iNeedDestruction) {
            destruction()
        }
    }

    /**
     * Destroy self if no parent else mark this as needing destruction.
     * This is was needed to accommodate for WebView animation.
     */
    fun autoDestruction() {
        Timber.d("autoDestruction: $parent")
        if (parent!=null) {
            // There is probably still an animation running
            iNeedDestruction = true
        } else {
            destruction()
        }
    }

    /**
     *
     */
    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        //Timber.d("onGenericMotionEvent $event")
        return super.onGenericMotionEvent(event)
    }

    /**
     *
     */
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        //Timber.d("onTouchEvent $event")
        return super.onTouchEvent(event)
    }

    /**
     *
     */
    override fun dispatchGenericMotionEvent(event: MotionEvent?): Boolean {
        //Timber.d("dispatchGenericMotionEvent $event")
        return super.dispatchGenericMotionEvent(event)
    }

    /**
     *
     */
    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        //Timber.d("dispatchTouchEvent $event")
        return super.dispatchTouchEvent(event)
    }

    /**
     * Overrides [WebView.loadUrl(String)].
     */
    override fun loadUrl(url: String) {
        Timber.i("$ihs : loadUrl: $url")
        super.loadUrl(url)
        postDelayed({ injectCodeMirrorBlockCopyScript() }, 1200)
    }

        /**
     * Overrides [WebView.loadUrl(String, Map)].
     */
        override fun loadUrl(url: String, additionalHttpHeaders: MutableMap<String, String>) {
        Timber.i("$ihs : loadUrl: $url (${additionalHttpHeaders.size} headers)")
        super.loadUrl(url, additionalHttpHeaders)
        postDelayed({ injectCodeMirrorBlockCopyScript() }, 1200)
    }

    fun injectCodeMirrorBlockCopyScript() {
        val js = """
            (function() {
                if (window.__fulgurisCmCopyHooked) return;
                window.__fulgurisCmCopyHooked = true;

                function getActiveCmView() {
                    var nodes = document.querySelectorAll('.cm-editor, .cm-content');
                    for (var i = 0; i < nodes.length; i++) {
                        var el = nodes[i];
                        while (el && el !== document.body) {
                            if (el.cmView && el.cmView.view && el.cmView.view.state) return el.cmView.view;
                            if (el.cmView && el.cmView.rootView && el.cmView.rootView.view) return el.cmView.rootView.view;
                            var keys = Object.getOwnPropertyNames(el);
                            for (var k = 0; k < keys.length; k++) {
                                var v = el[keys[k]];
                                if (v && typeof v === 'object') {
                                    if (v.state && v.state.doc && v.state.selection) return v;
                                    if (v.view && v.view.state && v.view.state.doc) return v.view;
                                }
                            }
                            el = el.parentElement;
                        }
                    }
                    return null;
                }

                document.addEventListener('copy', function(e) {
                    var cmView = getActiveCmView();
                    if (!cmView || !cmView.state) return;

                    var sel = cmView.state.selection.main;
                    var text = '';
                    if (sel && sel.from !== sel.to) {
                        text = cmView.state.sliceDoc(sel.from, sel.to);
                    } else {
                        var domSel = window.getSelection();
                        if (domSel && domSel.rangeCount > 0) {
                            var range = domSel.getRangeAt(0);
                            var container = range.commonAncestorContainer;
                            var editor = container.nodeType === 1
                                ? container.closest('.cm-editor')
                                : (container.parentElement && container.parentElement.closest('.cm-editor'));
                            if (editor) {
                                var lines = editor.querySelectorAll('.cm-line');
                                var collected = [];
                                for (var i = 0; i < lines.length; i++) {
                                    if (domSel.containsNode(lines[i], true)) {
                                        collected.push(lines[i].textContent);
                                    }
                                }
                                if (collected.length > 0) {
                                    text = collected.join('\n');
                                }
                            }
                        }
                    }

                    if (text && text.length > 0) {
                        if (e.clipboardData) {
                            e.preventDefault();
                            e.stopImmediatePropagation();
                            e.clipboardData.setData('text/plain', text);
                        }
                        if (navigator.clipboard && navigator.clipboard.writeText) {
                            navigator.clipboard.writeText(text).catch(function() {});
                        }
                    }
                }, true);
            })();
        """.trimIndent()
        evaluateJavascript(js, null)
    }


    fun findBlockInPage(query: String, step: Int = 0, onResult: ((activeIndex: Int, total: Int) -> Unit)? = null) {
        val normalized = query.replace("\r\n", "\n").replace("\r", "\n")
        val escapedQuery = org.json.JSONObject.quote(normalized)
        val js = """
            (function() {
                var target = $escapedQuery;
                var step = $step;

                document.querySelectorAll('.cm-line').forEach(function(el) {
                    el.style.backgroundColor = '';
                });

                function getCmView() {
                    var nodes = document.querySelectorAll('.cm-content, .cm-editor, [contenteditable="true"]');
                    for (var n = 0; n < nodes.length; n++) {
                        var el = nodes[n];
                        while (el) {
                            if (el.cmView && el.cmView.view) return el.cmView.view;
                            if (el.cmView && el.cmView.rootView && el.cmView.rootView.view) return el.cmView.rootView.view;
                            for (var k in el) {
                                if (k.indexOf('cm') === 0 && el[k] && el[k].state && el[k].dispatch) return el[k];
                            }
                            el = el.parentElement;
                        }
                    }
                    return null;
                }

                var targetLines = target.split('\n').map(function(l) { return l.trim(); });
                while (targetLines.length > 0 && targetLines[0] === '') targetLines.shift();
                while (targetLines.length > 0 && targetLines[targetLines.length - 1] === '') targetLines.pop();
                if (targetLines.length === 0) return JSON.stringify({ active: -1, total: 0 });

                var cmView = getCmView();
                if (cmView && cmView.state && cmView.state.doc) {
                    var doc = cmView.state.doc;
                    var totalLines = doc.lines;
                    var matches = [];

                    for (var i = 1; i <= totalLines - targetLines.length + 1; i++) {
                        var ok = true;
                        for (var j = 0; j < targetLines.length; j++) {
                            var lineText = doc.line(i + j).text.trim();
                            if (targetLines[j] !== '' && lineText.indexOf(targetLines[j]) === -1) {
                                ok = false;
                                break;
                            }
                        }
                        if (ok) {
                            var fromPos = doc.line(i).from;
                            var toPos = doc.line(i + targetLines.length - 1).to;
                            matches.push({ from: fromPos, to: toPos, startLine: i, endLine: i + targetLines.length - 1 });
                        }
                    }

                    if (matches.length > 0) {
                        var stateKey = target;
                        if (!window.__fulgurisBlockSearch || window.__fulgurisBlockSearch.query !== stateKey) {
                            window.__fulgurisBlockSearch = { query: stateKey, index: 0 };
                        } else if (step !== 0) {
                            window.__fulgurisBlockSearch.index = (window.__fulgurisBlockSearch.index + step + matches.length) % matches.length;
                        }
                        var idx = window.__fulgurisBlockSearch.index % matches.length;
                        var m = matches[idx];

                        if (typeof cmView.dispatch === 'function') {
                            try {
                                cmView.dispatch({
                                    selection: { anchor: m.from, head: m.to },
                                    scrollIntoView: true
                                });
                            } catch (e) {
                                var scroller = document.querySelector('.cm-scroller');
                                if (scroller) {
                                    var ratio = (m.startLine - 1) / Math.max(1, totalLines);
                                    scroller.scrollTop = Math.max(0, scroller.scrollHeight * ratio - scroller.clientHeight * 0.3);
                                }
                            }
                        }

                        setTimeout(function() {
                            var visibleLines = document.querySelectorAll('.cm-line');
                            visibleLines.forEach(function(el) {
                                var txt = el.textContent.trim();
                                if (txt && targetLines.indexOf(txt) !== -1) {
                                    el.style.backgroundColor = 'rgba(255, 215, 0, 0.35)';
                                }
                            });
                        }, 120);

                        return JSON.stringify({ active: idx, total: matches.length });
                    }
                    return JSON.stringify({ active: -1, total: 0 });
                }

                var tas = document.querySelectorAll('textarea');
                for (var t = 0; t < tas.length; t++) {
                    var val = tas[t].value.replace(/\r\n/g, '\n');
                    var pos = val.indexOf(target);
                    if (pos !== -1) {
                        tas[t].focus();
                        tas[t].setSelectionRange(pos, pos + target.length);
                        return JSON.stringify({ active: 0, total: 1 });
                    }
                }

                return JSON.stringify({ active: -1, total: 0 });
            })();
        """.trimIndent()

        evaluateJavascript(js) { rawResult ->
            try {
                val clean = rawResult?.trim('"')?.replace("\\\"", "\"") ?: ""
                if (clean.startsWith("{")) {
                    val obj = org.json.JSONObject(clean)
                    val active = obj.optInt("active", -1)
                    val total = obj.optInt("total", 0)
                    onResult?.invoke(active, total)
                } else {
                    onResult?.invoke(-1, 0)
                }
            } catch (_: Exception) {
                onResult?.invoke(-1, 0)
            }
        }
    }

}


