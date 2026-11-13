package com.termux.app;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.TextUtils;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.dsh.mobile.DshBackend;
import com.dsh.mobile.DshMirrors;
import com.dsh.mobile.DshPrefs;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.file.TermuxFileUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 合并版入口：DSH 前端 + 内置 Termux 运行时。
 *
 * <p>首次启动会解压内置的 bootstrap（已改写为 com.dshapp 前缀），之后即可在终端里
 * 安装 Node 与 DSH，并通过本页的 WebView 使用完整 Web GUI 与全部插件。
 */
public class DshActivity extends AppCompatActivity {

    private static final int REQ_FILE_CHOOSER = 2001;
    private static final int REQ_WEB_PERMISSIONS = 2002;

    private DshPrefs prefs;
    private WebView webView;
    private ProgressBar progress;
    private View webContainer;
    private View setupPanel;
    private EditText baseUrlInput;
    private EditText tokenInput;
    private TextView statusView;

    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingPermissionRequest;
    private boolean authFailed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dsh_activity_main);

        prefs = new DshPrefs(this);
        webView = findViewById(R.id.dsh_webview);
        progress = findViewById(R.id.dsh_progress);
        webContainer = findViewById(R.id.dsh_web_container);
        setupPanel = findViewById(R.id.dsh_setup_panel);
        baseUrlInput = findViewById(R.id.dsh_input_base_url);
        tokenInput = findViewById(R.id.dsh_input_token);
        statusView = findViewById(R.id.dsh_status);

        baseUrlInput.setText(prefs.baseUrl());
        tokenInput.setText(prefs.token());

        setupWebView();
        wireSetupUi();
        showSetup(null);

        ensureBootstrap();
    }

    /** 首次启动解压内置 bootstrap；已安装则直接进入。 */
    private void ensureBootstrap() {
        try {
            if (TermuxFileUtils.isTermuxPrefixDirectoryEmpty()) {
                setStatus("正在初始化 Termux 运行环境，请稍候…");
                TermuxInstaller.setupBootstrapIfNeeded(this, () -> runOnUiThread(() -> {
                    setStatus("环境已就绪。请先在终端中安装 Node 与 DSH。");
                    if (prefs.configured()) {
                        showWeb();
                        loadEntry();
                    }
                }));
                return;
            }
        } catch (Exception e) {
            setStatus("环境检查失败：" + e.getMessage());
            return;
        }
        if (prefs.configured()) {
            showWeb();
            loadEntry();
        }
    }

    // ---------------------------------------------------------------- WebView

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        s.setUserAgentString(s.getUserAgentString() + " DSHMobile/1.0");
        applyLayoutMode();

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setBackgroundColor(Color.parseColor("#0d0f14"));
        webView.setWebViewClient(new DshWebViewClient());
        webView.setWebChromeClient(new DshChromeClient());
        webView.setDownloadListener(this::onDownloadStart);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
    }

    private void applyLayoutMode() {
        WebSettings s = webView.getSettings();
        boolean desktop = prefs.desktopMode();
        s.setUseWideViewPort(desktop);
        s.setLoadWithOverviewMode(desktop);
    }

    private void loadEntry() {
        authFailed = false;
        progress.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        webView.loadUrl(prefs.entryUrl());
    }

    private final class DshWebViewClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleUrl(request.getUrl());
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleUrl(Uri.parse(url));
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            progress.setVisibility(View.GONE);
            if (authFailed) {
                return;
            }
            if (url != null && !url.contains("token=")) {
                showWeb();
            }
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                        WebResourceResponse errorResponse) {
            if (request.isForMainFrame() && errorResponse.getStatusCode() == 401) {
                authFailed = true;
                runOnUiThread(() -> showSetup(
                        "授权已失效。请在终端中重新运行 dsh web，并粘贴新打印的 URL。"));
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) {
                String desc = error == null ? "" : String.valueOf(error.getDescription());
                runOnUiThread(() -> showSetup(
                        "无法连接后端：" + desc + "\n请先在终端中启动 dsh web。"));
            }
        }
    }

    private final class DshChromeClient extends WebChromeClient {

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            progress.setProgress(newProgress);
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(null);
            }
            fileCallback = callback;
            try {
                startActivityForResult(params.createIntent(), REQ_FILE_CHOOSER);
                return true;
            } catch (ActivityNotFoundException e) {
                fileCallback = null;
                toast("没有可用的文件选择器");
                return false;
            }
        }

        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            runOnUiThread(() -> handleWebPermission(request));
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin,
                                                       GeolocationPermissions.Callback callback) {
            callback.invoke(origin, true, false);
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                      android.os.Message resultMsg) {
            return false;
        }
    }

    private void handleWebPermission(PermissionRequest request) {
        List<String> needed = new ArrayList<>();
        for (String resource : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                needed.add(Manifest.permission.CAMERA);
            } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                needed.add(Manifest.permission.RECORD_AUDIO);
            }
        }
        List<String> missing = new ArrayList<>();
        for (String permission : needed) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        if (missing.isEmpty()) {
            request.grant(request.getResources());
            return;
        }
        pendingPermissionRequest = request;
        ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), REQ_WEB_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_WEB_PERMISSIONS || pendingPermissionRequest == null) {
            return;
        }
        boolean granted = grantResults.length > 0;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                granted = false;
                break;
            }
        }
        if (granted) {
            pendingPermissionRequest.grant(pendingPermissionRequest.getResources());
        } else {
            pendingPermissionRequest.deny();
        }
        pendingPermissionRequest = null;
    }

    // ------------------------------------------------------------------- 导航

    private boolean handleUrl(Uri uri) {
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme();
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            if (isSameOrigin(uri)) {
                return false;
            }
            openExternal(uri);
            return true;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException ignored) {
            toast("无法打开该链接");
        }
        return true;
    }

    private boolean isSameOrigin(Uri uri) {
        Uri base = Uri.parse(prefs.baseUrl());
        String host = uri.getHost();
        String baseHost = base.getHost();
        if (host == null || baseHost == null) {
            return false;
        }
        int port = uri.getPort() > 0 ? uri.getPort()
                : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
        int basePort = base.getPort() > 0 ? base.getPort()
                : ("https".equalsIgnoreCase(base.getScheme()) ? 443 : 80);
        return host.equalsIgnoreCase(baseHost) && port == basePort;
    }

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException ignored) {
            toast("无法打开该链接");
        }
    }

    // ------------------------------------------------------------------- 下载

    private void onDownloadStart(String url, String userAgent, String contentDisposition,
                                 String mimeType, long contentLength) {
        try {
            String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle(fileName);
            request.setDescription("DSH 下载");
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) {
                request.addRequestHeader("Cookie", cookie);
            }
            if (userAgent != null) {
                request.addRequestHeader("User-Agent", userAgent);
            }
            DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager == null) {
                toast("下载服务不可用");
                return;
            }
            manager.enqueue(request);
            toast("已开始下载：" + fileName);
        } catch (Exception e) {
            toast("下载失败：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------- 配置

    private void wireSetupUi() {
        findViewById(R.id.dsh_btn_paste).setOnClickListener(v -> pasteFromClipboard());
        findViewById(R.id.dsh_btn_connect).setOnClickListener(v -> connect());
        findViewById(R.id.dsh_btn_termux).setOnClickListener(v -> startBackend());
        findViewById(R.id.dsh_btn_help).setOnClickListener(v -> showHelp());
        findViewById(R.id.dsh_btn_menu).setOnClickListener(this::showMenu);
    }

    private void pasteFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) {
            toast("剪贴板为空");
            return;
        }
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            toast("剪贴板为空");
            return;
        }
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        String value = text == null ? "" : text.toString().trim();
        if (value.isEmpty()) {
            toast("剪贴板为空");
            return;
        }
        if (value.contains("://")) {
            Uri uri = Uri.parse(value);
            String token = uri.getQueryParameter("token");
            String base = buildBase(uri);
            if (base != null) {
                baseUrlInput.setText(base);
            }
            if (token != null && !token.isEmpty()) {
                tokenInput.setText(token);
                setStatus("已从剪贴板识别地址与令牌");
            } else {
                setStatus("已识别地址，但未找到 token 参数");
            }
        } else {
            tokenInput.setText(value);
            setStatus("已粘贴令牌");
        }
    }

    private static String buildBase(Uri uri) {
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(scheme).append("://").append(host);
        if (uri.getPort() > 0) {
            sb.append(':').append(uri.getPort());
        }
        return sb.toString();
    }

    private void connect() {
        String base = baseUrlInput.getText().toString().trim();
        String token = tokenInput.getText().toString().trim();

        if (token.contains("://")) {
            Uri uri = Uri.parse(token);
            String parsedBase = buildBase(uri);
            String parsedToken = uri.getQueryParameter("token");
            if (parsedBase == null) {
                setStatus("URL 无法解析，请检查格式");
                return;
            }
            base = parsedBase;
            token = parsedToken == null ? "" : parsedToken;
            baseUrlInput.setText(base);
            tokenInput.setText(token);
        }

        if (TextUtils.isEmpty(token)) {
            setStatus("请填写令牌，或直接粘贴 dsh web 打印的完整 URL");
            return;
        }

        prefs.setBaseUrl(base);
        prefs.setToken(token);
        setStatus("正在连接…");
        showWeb();
        loadEntry();
    }

    private void startBackend() {
        boolean ok = DshBackend.start(this, "dsh web");
        if (ok) {
            new AlertDialog.Builder(this)
                    .setTitle("已在终端会话中启动")
                    .setMessage("dsh web 正在终端里运行，并会打印一个带 token 的访问 URL。\n\n"
                            + "打开终端复制该 URL，回到本页点「粘贴」即可。")
                    .setPositiveButton("打开终端", (d, w) -> openTerminal())
                    .setNegativeButton("稍后", null)
                    .show();
        } else {
            toast("启动失败，请到终端手动执行 dsh web");
            openTerminal();
        }
    }

    private void openTerminal() {
        try {
            startActivity(new Intent(this, TermuxActivity.class));
        } catch (Exception e) {
            toast("无法打开终端：" + e.getMessage());
        }
    }

    private void showMirrorDialog() {
        final DshMirrors.Mirror[] mirrors = DshMirrors.MIRRORS;
        String[] names = new String[mirrors.length];
        for (int i = 0; i < mirrors.length; i++) {
            names[i] = mirrors[i].name;
        }
        new AlertDialog.Builder(this)
                .setTitle("软件源镜像")
                .setItems(names, (dialog, which) -> {
                    try {
                        String line = DshMirrors.apply(mirrors[which].url);
                        prefs.setMirrorUrl(mirrors[which].url);
                        new AlertDialog.Builder(this)
                                .setTitle("已切换镜像")
                                .setMessage("已写入：\n" + line
                                        + "\n\n请在终端执行 apt update 使其生效。")
                                .setPositiveButton("去终端", (d, w) -> openTerminal())
                                .setNegativeButton("关闭", null)
                                .show();
                    } catch (Exception e) {
                        toast("写入失败：" + e.getMessage());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showHelp() {
        new AlertDialog.Builder(this)
                .setTitle("首次使用")
                .setMessage("1. 打开终端，安装 Node 与 DSH：\n"
                        + "pkg update\n"
                        + "pkg install nodejs-lts\n"
                        + "npm i -g @deepseek-ai/dsh\n\n"
                        + "2. 启动主机：\n"
                        + "dsh web\n\n"
                        + "3. 复制它打印的 http://127.0.0.1:3090/?token=... 地址，\n"
                        + "回到本页点「粘贴」→「连接」。\n\n"
                        + "提示：令牌随 dsh web 进程变化，重启后需要重新粘贴。\n"
                        + "国内网络建议先在菜单里切换软件源镜像。")
                .setPositiveButton("关闭", null)
                .show();
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "重新连接");
        menu.getMenu().add(0, 2, 1, prefs.desktopMode() ? "✓ 桌面模式" : "桌面模式");
        menu.getMenu().add(0, 3, 2, "打开终端");
        menu.getMenu().add(0, 4, 3, "软件源镜像");
        menu.getMenu().add(0, 5, 4, "连接设置");
        menu.getMenu().add(0, 6, 5, "使用帮助");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    loadEntry();
                    return true;
                case 2:
                    prefs.setDesktopMode(!prefs.desktopMode());
                    applyLayoutMode();
                    loadEntry();
                    return true;
                case 3:
                    openTerminal();
                    return true;
                case 4:
                    showMirrorDialog();
                    return true;
                case 5:
                    showSetup(null);
                    return true;
                case 6:
                    showHelp();
                    return true;
                default:
                    return false;
            }
        });
        menu.show();
    }

    // ------------------------------------------------------------------- 视图

    private void showSetup(String message) {
        setupPanel.setVisibility(View.VISIBLE);
        webContainer.setVisibility(View.GONE);
        progress.setVisibility(View.GONE);
        if (message != null) {
            setStatus(message);
        }
    }

    private void showWeb() {
        setupPanel.setVisibility(View.GONE);
        webContainer.setVisibility(View.VISIBLE);
    }

    private void setStatus(String message) {
        statusView.setText(message);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    // --------------------------------------------------------------- 生命周期

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            if (fileCallback == null) {
                return;
            }
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            fileCallback.onReceiveValue(results);
            fileCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (webContainer.getVisibility() == View.VISIBLE && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        CookieManager.getInstance().flush();
        webView.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
