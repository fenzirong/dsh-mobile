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
import android.widget.Button;
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

import com.dsh.mobile.DshLauncher;
import com.dsh.mobile.DshMirrors;
import com.dsh.mobile.DshProgress;
import com.dsh.mobile.DshPrefs;
import com.dsh.mobile.MirrorBenchmark;
import com.termux.R;
import com.termux.shared.termux.file.TermuxFileUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 合并版入口：DSH 前端 + 内置 Termux 运行时。
 *
 * <p>首次启动解压内置 bootstrap，之后「一键启动」会自动完成
 * 装 Node → 装 DSH → 起 dsh web → 抓取带 token 的地址 → 加载界面，
 * 用户全程只需点一次按钮。
 */
public class DshActivity extends AppCompatActivity {

    private static final int REQ_FILE_CHOOSER = 2001;
    private static final int REQ_WEB_PERMISSIONS = 2002;

    private DshPrefs prefs;
    private DshLauncher launcher;

    private WebView webView;
    private ProgressBar progress;
    private ProgressBar startBar;
    private TextView percentView;
    private DshProgress progressModel;
    private View webContainer;
    private View setupPanel;
    private View manualPanel;
    private Button startButton;
    private Button manualButton;
    private EditText baseUrlInput;
    private EditText tokenInput;
    private TextView statusView;
    private TextView logView;

    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingPermissionRequest;
    private boolean authFailed;
    private boolean bootstrapReady;
    /** 懒加载的窄屏 UI 补丁脚本（来自 tdsh-android 的 dsh-mobile.js）。 */
    private String mobilePatch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dsh_activity_main);

        prefs = new DshPrefs(this);
        launcher = new DshLauncher(this);
        progressModel = new DshProgress(this::applyProgress);

        webView = findViewById(R.id.dsh_webview);
        progress = findViewById(R.id.dsh_progress);
        startBar = findViewById(R.id.dsh_progress_bar);
        percentView = findViewById(R.id.dsh_percent);
        webContainer = findViewById(R.id.dsh_web_container);
        setupPanel = findViewById(R.id.dsh_setup_panel);
        manualPanel = findViewById(R.id.dsh_manual_panel);
        startButton = findViewById(R.id.dsh_btn_start);
        manualButton = findViewById(R.id.dsh_btn_manual);
        baseUrlInput = findViewById(R.id.dsh_input_base_url);
        tokenInput = findViewById(R.id.dsh_input_token);
        statusView = findViewById(R.id.dsh_status);
        logView = findViewById(R.id.dsh_log);

        baseUrlInput.setText(prefs.baseUrl());
        tokenInput.setText(prefs.token());

        setupWebView();
        wireUi();
        showSetup(null);

        ensureBootstrap();
    }

    // ------------------------------------------------------------ 首次初始化

    /** 首次启动解压内置 bootstrap；已安装则直接进入。 */
    private void ensureBootstrap() {
        try {
            if (TermuxFileUtils.isTermuxPrefixDirectoryEmpty()) {
                progressModel.indeterminate();
                setStatus("正在初始化 Termux 运行环境，请稍候…");
                TermuxInstaller.setupBootstrapIfNeeded(this, () -> runOnUiThread(() -> {
                    bootstrapReady = true;
                    startButton.setEnabled(true);
                    progressModel.reset();
                    setStatus("环境已就绪，点「一键启动」即可。");
                    autoReuse();
                }));
                return;
            }
        } catch (Exception e) {
            setStatus("环境检查失败：" + e.getMessage());
            return;
        }
        bootstrapReady = true;
        startButton.setEnabled(true);
        autoReuse();
    }

    /** 若后端已在运行，直接连上，省掉一次点击。 */
    private void autoReuse() {
        launcher.reuseIfRunning(new DshLauncher.Listener() {
            @Override
            public void onStatus(String code) {
                setStatus("点「一键启动」开始。");
            }

            @Override
            public void onReady(String url) {
                connectTo(url);
            }

            @Override
            public void onDetail(String logTail) {
                showDetail(logTail);
            }

            @Override
            public void onProblem(String message) {
                setStatus(message);
            }
        });
    }

    // ------------------------------------------------------------ 一键启动

    private void onStartClicked() {
        if (!bootstrapReady) {
            setStatus("运行环境还在初始化，请稍候…");
            return;
        }
        startButton.setEnabled(false);
        startButton.setText("启动中…");
        progressModel.reset();
        setStatus("正在检查运行环境…");

        // 已有实例在跑就直接连，省掉测速与启动
        launcher.reuseIfRunning(new DshLauncher.Listener() {
            @Override
            public void onStatus(String code) {
                benchmarkThenStart();
            }

            @Override
            public void onReady(String url) {
                progressModel.finish();
                resetStartButton();
                connectTo(url);
            }

            @Override
            public void onDetail(String logTail) {
                showDetail(logTail);
            }

            @Override
            public void onProblem(String message) {
                resetStartButton();
                setStatus(message);
            }
        });
    }

    /** 并行测速所有镜像源，挑最快的那个交给启动脚本。 */
    private void benchmarkThenStart() {
        progressModel.setStage("bench");
        setStatus("正在测速镜像源…");

        MirrorBenchmark.run(DshMirrors.MIRRORS, (best, all) -> {
            String mirror;
            if (best != null) {
                mirror = best.url;
                prefs.setMirrorUrl(mirror);
                setStatus("最快镜像：" + best.name + "（" + best.millis + " ms）");
            } else {
                mirror = prefs.mirrorUrl();
                setStatus("镜像测速失败，改用默认源");
            }
            launcher.start(mirror, startListener);
        });
    }

    /** 启动脚本的阶段回调。 */
    private final DshLauncher.Listener startListener = new DshLauncher.Listener() {
        @Override
        public void onStatus(String code) {
            progressModel.setStage(code);
            setStatus(statusText(code));
            if (code.startsWith("error:") || code.equals("stopped")) {
                resetStartButton();
            }
        }

        @Override
        public void onReady(String url) {
            progressModel.finish();
            resetStartButton();
            connectTo(url);
        }

        @Override
        public void onDetail(String logTail) {
            showDetail(logTail);
        }

        @Override
        public void onProblem(String message) {
            resetStartButton();
            setStatus(message);
        }
    };

    private void resetStartButton() {
        startButton.setEnabled(true);
        startButton.setText("一键启动");
    }

    private String statusText(String code) {
        if (code == null || code.isEmpty()) {
            return "";
        }
        switch (code) {
            case "check":
                return "正在检查运行环境…";
            case "mirror":
                return "正在设置软件源镜像…";
            case "apt-update":
                return "正在更新软件源（首次较慢）…";
            case "install-node":
                return "正在安装 Node.js 与 npm…";
            case "install-dsh":
                return "正在安装 DSH…";
            case "init-profile":
                return "正在初始化 DSH 配置目录…";
            case "install-plugins":
                return "正在安装自带插件…";
            case "start":
                return "正在启动 DSH 主机…";
            case "stopped":
                return "DSH 主机已停止。可再次点「一键启动」。";
            default:
                if (code.startsWith("error:")) {
                    return "启动失败（" + code.substring(6) + "）。\n"
                            + "可点「打开终端」查看日志，或切换软件源镜像后重试。";
                }
                return code;
        }
    }

    /** 从带 token 的 URL 里解析出地址与令牌，保存后加载界面。 */
    private void connectTo(String url) {
        Uri uri = Uri.parse(url);
        String token = uri.getQueryParameter("token");
        String base = buildBase(uri);
        if (base == null || TextUtils.isEmpty(token)) {
            setStatus("启动器拿到的地址不完整：" + url);
            return;
        }
        prefs.setBaseUrl(base);
        prefs.setToken(token);
        baseUrlInput.setText(base);
        tokenInput.setText(token);
        setStatus("已连接：" + base);
        showWeb();
        loadEntry();
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

    /**
     * 注入窄屏 UI 补丁：把 DSH 的设置弹窗从「左分类 + 右内容」改成列表→详情两态，
     * 避免中文被 124px 的右栏挤成一字一行。
     *
     * <p>脚本来自 tdsh-android，对原生桥的调用都带 {@code if (window.DshAndroid)} 守卫，
     * 在没有该桥的合并版里会自动降级，只保留纯 CSS/JS 的布局改造。
     */
    private void injectMobilePatch() {
        if (mobilePatch == null) {
            try (InputStream in = getAssets().open("dsh-mobile.js")) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                mobilePatch = bos.toString("UTF-8");
            } catch (IOException e) {
                mobilePatch = "";
            }
        }
        if (!mobilePatch.isEmpty()) {
            webView.evaluateJavascript(mobilePatch, null);
        }
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
                injectMobilePatch();
            }
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                        WebResourceResponse errorResponse) {
            if (request.isForMainFrame() && errorResponse.getStatusCode() == 401) {
                authFailed = true;
                runOnUiThread(() -> {
                    showSetup("授权已失效，点「一键启动」重新拉起后端即可。");
                    resetStartButton();
                });
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) {
                String desc = error == null ? "" : String.valueOf(error.getDescription());
                runOnUiThread(() -> {
                    showSetup("无法连接后端：" + desc + "\n点「一键启动」重新拉起。");
                    resetStartButton();
                });
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

    private void wireUi() {
        startButton.setOnClickListener(v -> onStartClicked());
        findViewById(R.id.dsh_btn_terminal).setOnClickListener(v -> openTerminal());
        manualButton.setOnClickListener(v -> toggleManual());
        findViewById(R.id.dsh_btn_paste).setOnClickListener(v -> pasteFromClipboard());
        findViewById(R.id.dsh_btn_connect).setOnClickListener(v -> connectManual());
        findViewById(R.id.dsh_btn_help).setOnClickListener(v -> showHelp());
        findViewById(R.id.dsh_btn_menu).setOnClickListener(this::showMenu);
    }

    private void toggleManual() {
        boolean show = manualPanel.getVisibility() != View.VISIBLE;
        manualPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        manualButton.setText(show ? "收起配置" : "手动配置");
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

    private void connectManual() {
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
                .setTitle("软件源镜像（启动时自动测速选最快）")
                .setItems(names, (dialog, which) -> {
                    try {
                        String line = DshMirrors.apply(mirrors[which].url);
                        prefs.setMirrorUrl(mirrors[which].url);
                        new AlertDialog.Builder(this)
                                .setTitle("已切换镜像")
                                .setMessage("已写入：\n" + line
                                        + "\n\n注意：点「一键启动」时仍会重新测速并选用最快的镜像，"
                                        + "此处设置只影响终端里手动执行的 pkg/apt。")
                                .setPositiveButton("知道了", null)
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
                .setTitle("使用说明")
                .setMessage("1. 点「一键启动」\n"
                        + "   自动完成：装 Node.js → 装 DSH → 启动 dsh web → 连接界面。\n"
                        + "   首次需要联网下载，耗时几分钟。\n\n"
                        + "2. 国内网络建议先在菜单里切换「软件源镜像」。\n\n"
                        + "3. 需要手动操作时点「打开终端」，可执行 pkg / npm 等命令。\n\n"
                        + "4. 连接远程 DSH 主机时用「手动配置」填地址与令牌。\n\n"
                        + "日志：$PREFIX/tmp/dsh-boot.log、dsh-web.log")
                .setPositiveButton("关闭", null)
                .show();
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "重新连接");
        menu.getMenu().add(0, 2, 1, prefs.desktopMode() ? "✓ 桌面模式" : "桌面模式");
        menu.getMenu().add(0, 3, 2, "打开终端");
        menu.getMenu().add(0, 4, 3, "软件源镜像");
        menu.getMenu().add(0, 5, 4, "重启后端");
        menu.getMenu().add(0, 6, 5, "使用说明");
        menu.getMenu().add(0, 7, 6, "查看日志");
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
                    showSetup("点「一键启动」重新拉起后端。");
                    resetStartButton();
                    return true;
                case 6:
                    showHelp();
                    return true;
                case 7:
                    showLogDialog();
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

    /** 显示启动日志的最后几行，让"看起来卡住"其实能看出在下什么。 */
    private void showDetail(String tail) {
        if (tail == null || tail.isEmpty()) {
            logView.setVisibility(View.GONE);
            return;
        }
        logView.setText(tail);
        logView.setVisibility(View.VISIBLE);
    }

    /** 弹窗展示完整启动日志（末尾若干行）。 */
    private void showLogDialog() {
        new Thread(() -> {
            final String text = readTail(new java.io.File(DshLauncher.PREFIX + "/tmp/dsh-boot.log"), 200);
            final String err = readTail(new java.io.File(DshLauncher.PREFIX + "/tmp/dsh-boot.err"), 30);
            final String fix = readTail(new java.io.File(DshLauncher.PREFIX + "/tmp/dsh-fixdeb.log"), 60);
            runOnUiThread(() -> {
                StringBuilder sb = new StringBuilder();
                if (err != null) {
                    sb.append("== 失败原因 (dsh-boot.err) ==\n").append(err).append("\n\n");
                }
                if (fix != null) {
                    sb.append("== 包改写 (dsh-fixdeb.log) ==\n").append(fix).append("\n\n");
                }
                if (text != null) {
                    sb.append("== 启动日志 (dsh-boot.log) ==\n").append(text);
                }
                if (sb.length() == 0) {
                    sb.append("暂无日志。");
                }
                new AlertDialog.Builder(this)
                        .setTitle("启动日志")
                        .setMessage(sb.toString())
                        .setPositiveButton("关闭", null)
                        .show();
            });
        }, "dsh-log-reader").start();
    }

    private static String readTail(java.io.File file, int maxLines) {
        if (!file.isFile()) {
            return null;
        }
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
            long len = raf.length();
            int take = (int) Math.min(len, 64 * 1024);
            byte[] buf = new byte[take];
            raf.seek(Math.max(0, len - take));
            raf.readFully(buf);
            String[] parts = new String(buf, java.nio.charset.StandardCharsets.UTF_8).split("\n");
            int from = Math.max(0, parts.length - maxLines);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < parts.length; i++) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(parts[i]);
            }
            return sb.toString();
        } catch (IOException e) {
            return null;
        }
    }

    /** 渲染进度：-1 表示不确定进度。 */
    private void applyProgress(int percent) {
        if (percent < 0) {
            startBar.setIndeterminate(true);
            percentView.setText("…");
            return;
        }
        if (startBar.isIndeterminate()) {
            startBar.setIndeterminate(false);
        }
        startBar.setProgress(percent);
        percentView.setText(percent + "%");
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
        launcher.stop();
        progressModel.release();
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
