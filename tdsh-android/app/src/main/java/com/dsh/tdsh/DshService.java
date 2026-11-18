package com.dsh.tdsh;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 常驻前台服务：在容器里维持 `dsh --profile web` 进程。
 *
 * 为什么需要它：
 *   原先 dsh 进程属于 Activity，一旦 App 退到后台，系统随时可能回收，
 *   在 OriginOS 这类后台管理激进的 ROM 上尤其明显。放进前台服务后，
 *   进程有常驻通知与前台优先级，切后台也能继续跑。
 *
 * 注意：本项目 targetSdk = 28（为了 W^X 豁免），所以
 *   · 不需要声明 foregroundServiceType
 *   · 不需要 POST_NOTIFICATIONS 运行时权限
 * 这两个要求都是 targetSdk 34/33 才生效的。
 *
 * 状态通过静态字段暴露给 Activity 轮询（不引入 AndroidX / 广播框架）。
 */
public class DshService extends Service {

    private static final String TAG = "DSH_SERVICE";    private static final String CH_ID = "dsh_runtime";
    private static final int NOTIF_ID = 0x4453;   // "DS"
    public static final String PREFS = "dsh_settings";
    public static final String KEY_API = "deepseek_api_key";

    /**
     * 服务指令：「跑一次启动诊断」（用户从设置里点）。
     *
     * <p>为什么走服务指令而不是 Activity 自己直接跑：诊断要起 proot + node
     * （容器基线那一步），需要和服务共用同一个上下文与串行化；而且它跑在
     * 服务线程里、用完即走，不影响 Activity 的生命周期。
     */
    public static final String ACTION_DIAG = "com.dsh.tdsh.action.DIAG";

    /**
     * 通知栏那个「悬浮球」按钮的动作。
     *
     * <p>悬浮球不是 Service 了（见 {@link BallWindow} 类注释），所以这里用广播，
     * 由本服务自己收、自己加窗 —— 不引入第二个组件。
     */
    public static final String ACTION_BALL_TOGGLE = "com.dsh.tdsh.BALL_TOGGLE";

    /** 通知栏按钮的接收器（运行时注册，跟着服务生死）。 */
    private android.content.BroadcastReceiver ballReceiver;

    /** 供 Activity 轮询的状态。 */
    private static volatile String sUrl;
    private static volatile String sState = "未启动";
    private static volatile Process sProcess;

    private volatile boolean keepRunning = true;
    /** 由 Activity 置位：请求重启容器内的 dsh（例如 API Key 改过）。 */
    private static volatile boolean sRestartRequested = false;

    /** 当前实例。用来让别处（比如换了图标配色）催它刷新一下通知。 */
    private static volatile DshService sInstance;

    /**
     * 本次是否**复用**了一个"App 进程死了但容器还活着"的容器。
     *
     * <p>为 true 时 {@link #sProcess} 是 null（容器不是我们这次拉起来的），
     * 但 dsh 确实在跑 —— 所以 {@link #supervise()} 不能把它当成"没容器"再去起一份。
     */
    private static volatile boolean sAdopted = false;

    /** 复用记录文件：存上次跑起来的那条带 token 的 URL。 */
    private static File runningUrlFile(File base) { return new File(base, "running.url"); }

    /**
     * 让服务重建一次它的常驻通知。
     *
     * <p>为什么要这个：通知的强调色和图标是**构建那一刻**定下来的，
     * 用户在「桌面图标」里换了配色之后，如果服务不重建通知，
     * 通知栏里那条会一直挂着旧颜色 —— 看起来就像"没生效"。
     */
    public static void refreshNotification() {
        DshService s = sInstance;
        if (s == null) return;
        try {
            s.updateNotification(sState);
        } catch (Throwable ignore) { }
    }

    /**
     * 容器还没装好（首次初始化没完成）。
     *
     * 原来的 startDsh() 遇到这种情况只每 10 秒静默重试一次，界面上完全看不出来，
     * 用户只能看到"一直没反应"。现在把它暴露出去，Activity 回到前台时
     * 可以据此自动补跑初始化。
     */
    private static volatile boolean sNeedsProvision = false;

    public static boolean getNeedsProvision() { return sNeedsProvision; }

    /** onCreate 是否跑过 —— 用来区分"服务根本没起来"和"起来了但 dsh 没就绪"。 */
    private static volatile boolean sCreated = false;
    public static boolean isCreated() { return sCreated; }

    /**
     * dsh 进程最近输出的若干行。
     *
     * 为什么必须留：原来这段输出**只看 URL、其余全部丢弃**，
     * 于是 dsh 起不来的时候，用户和我们都拿不到任何原因 ——
     * 只能看到"等待超时"。现在失败信息里会带上这几行。
     */
    private static final java.util.ArrayDeque<String> sRecent =
            new java.util.ArrayDeque<>();
    private static final int RECENT_MAX = 60;

    /**
     * 最后一条 stdout 的到达时刻。
     *
     * <p>光有"最近一行"不够：那一行会**一直待在那里**，DSH 启动完就不再打东西，
     * 于是半小时后 {@link #getCurrentLine()} 返回的还是启动日志里的那句
     * {@code listening on …}，悬浮球的阶段就被它钉死（见 {@link BallStage#LINE_TTL_MS}）。
     */
    private static volatile long sLastLineAt = 0L;

    /*
     * ── 网页上报的「正在干活」与阶段（见 MainActivity.AppBridge.pageBusy/pageStage）──
     *
     * 为什么还要网页这一路：我们的 stdout 只有启动日志，CPU 采样在"等模型吐字"
     * 时也可能测不到增量。而网页**就长在对话界面上** —— 有没有在生成、有没有工具在跑，
     * 它比谁都清楚。这是把"悬浮球阶段"接对的关键一手（2026-10-21 用户报的 bug）。
     */
    private static volatile boolean sPageBusy = false;
    private static volatile long sPageBusyAt = 0L;
    private static volatile int sPageStage = -1;
    private static volatile long sPageStageAt = 0L;

    /** 网页上报"在生成/在跑工具"。由 patch.js 每秒左右推一次。 */
    public static void setPageBusy(boolean b) {
        sPageBusy = b;
        sPageBusyAt = System.currentTimeMillis();
    }

    /** 网页上报阶段（{@link BallStage} 的常量）。 */
    public static void setPageStage(int stage) {
        if (stage < BallStage.IDLE || stage > BallStage.ERROR) return;
        sPageStage = stage;
        sPageStageAt = System.currentTimeMillis();
    }

    /** 网页这一路是否还有效（3 秒内有心跳就算在干活）。 */
    public static boolean pageBusy() {
        return sPageBusy && (System.currentTimeMillis() - sPageBusyAt) < 3_000L;
    }

    public static int pageStage() { return sPageStage; }

    public static long pageStageAge() {
        return sPageStageAt == 0L ? Long.MAX_VALUE
                : System.currentTimeMillis() - sPageStageAt;
    }

    /**
     * 已经写进报告的"非 DSH 的本机服务"地址。
     *
     * <p>为什么要有：插件会在自己的端口上起面板服务、并把地址打进 dsh 的输出。
     * 我们要把它忽略掉（见 {@link UrlPick}），但**得在报告里留一行** ——
     * 否则下次用户说"界面又变成插件的网页了"，日志里什么都看不出来。
     * 用 set 去重，免得同一行刷满报告。
     */
    private final java.util.Set<String> foreignReported =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    public static String getRecentOutput() {
        synchronized (sRecent) {
            if (sRecent.isEmpty()) return "(dsh 没有任何输出)";
            StringBuilder sb = new StringBuilder();
            int skip = Math.max(0, sRecent.size() - 12);   // 只给最后 12 行
            int i = 0;
            for (String l : sRecent) {
                if (i++ < skip) continue;
                sb.append("      ").append(l).append('\n');
            }
            return sb.toString();
        }
    }

    /**
     * 最近一行**有意义**的输出 —— 悬浮球气泡用它当"正在干什么"。
     *
     * <p>为什么要有它：用户希望气泡能反映 agent 的进度（"气泡最好能显示思考过程"）。
     * 我们拿不到模型内部的推理，但 DSH 会把进度打到 stdout（起工具、跑命令、写文件），
     * 那一行就是**当前正在做的事** —— 比干巴巴的"正在运行"有用得多。
     *
     * <p>过滤掉纯装饰行（空行、纯分隔线、只有空格），否则气泡会显示成一片空白。
     */
    public static String getCurrentLine() {
        synchronized (sRecent) {
            java.util.Iterator<String> it = sRecent.descendingIterator();
            while (it.hasNext()) {
                String l = it.next();
                if (l == null) continue;
                String t = l.trim();
                if (t.isEmpty()) continue;
                if (t.matches("[-=_*·—\\s]{3,}")) continue;      // 分隔线
                return t.length() > 120 ? t.substring(0, 120) + "…" : t;
            }
            return null;
        }
    }

    /**
     * 最近一行输出**有多旧**（毫秒）。没输出过返回 {@link Long#MAX_VALUE}。
     *
     * <p>给 {@link BallStage#detect} 用：太旧的行不该参与阶段判定（见那边的说明）。
     */
    public static long getCurrentLineAgeMs() {
        long at = sLastLineAt;
        return at == 0L ? Long.MAX_VALUE : System.currentTimeMillis() - at;
    }

    /**
     * 记一条"非 dsh 进程"的事件到最近输出里（例如无障碍守护的自愈动作）。
     *
     * <p>放进同一个环形缓冲是为了让「复制日志」一次带走全部线索 ——
     * 之前排查权限问题时，最需要的信息恰恰在 dsh 的输出之外。
     */
    public static void note(String line) {
        if (line == null) return;
        synchronized (sRecent) {
            sRecent.addLast("[守护] " + line);
            while (sRecent.size() > RECENT_MAX) sRecent.removeFirst();
        }
    }

    /** 像报错的行（用于把关键输出写进报告）。 */
    private static boolean looksLikeError(String line) {
        String s = line.toLowerCase();
        return s.contains("error") || s.contains("eacces") || s.contains("enoent")
            || s.contains("eaddrinuse") || s.contains("cannot") || s.contains("cannot find")
            || s.contains("throw") || s.contains("exception") || s.contains("failed")
            || s.contains("listen") || s.contains("port");
    }

    /** 往报告文件追加（和 MainActivity 用同一个文件，方便「复制日志」一次带走）。 */
    private void appendReport(String text) {
        try (java.io.OutputStream os = new java.io.FileOutputStream(
                new java.io.File(Env.base(this), "report.txt"), true)) {
            os.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignore) { }
        /*
         * 同时镜像一份到共享存储（2026-10-21）。
         *
         * 为什么：report.txt 在 App 私有目录里，**非 root 拿不到** —— 用户说"发烫 / 启动慢"
         * 时，我这边什么都看不到，只能让他截图。镜像到 /sdcard/.dsh-phone/ 之后，
         * 用数据线或文件管理器就能把日志发出来（启动耗时分解、插件安装结果都在里面）。
         * 超过 256KB 重来，避免长期使用把存储写满。
         */
        try {
            java.io.File dir = new java.io.File("/sdcard/.dsh-phone");
            if (!dir.exists()) dir.mkdirs();
            java.io.File f = new java.io.File(dir, "report-mirror.log");
            if (f.length() > 256 * 1024) f.delete();
            try (java.io.OutputStream os = new java.io.FileOutputStream(f, true)) {
                os.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignore) { }
    }

    /** 请求重启 dsh 进程（不重启 Service 本身，避免 stop/start 的时序问题）。 */
    public static void requestRestart() { sRestartRequested = true; }

    public static String getUrl() { return sUrl; }

    public static String getState() { return sState; }

    /** 从 URL 里取 host:port（状态栏显示用）。 */
    private static String hostPortOf(String url) {
        try {
            java.net.URI u = java.net.URI.create(url);
            return u.getHost() + ":" + u.getPort();
        } catch (Throwable t) {
            return url;
        }
    }

    public static boolean isAlive() {
        Process p = sProcess;
        // 走 Proc：API 24/25 上没有 Process#isAlive（见 Proc 的类注释）
        return Proc.isAlive(p);
    }

    /**
     * 读 API Key —— **读出来先洗一遍**（见 {@link ApiKey}）。
     *
     * <p>为什么在"读"的时候就洗：用户早先存进去的值可能是脏的（带零宽字符之类），
     * 而我们没法让他重填。这里顺手改写回干净值（自愈），于是
     * 「明明填了却说 Key 不对」这一类历史遗留数据在**下一次启动时就自己好了**，
     * 不需要重装 App。
     */
    public static String getApiKey(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY_API, "");
        if (raw == null) raw = "";
        String clean = ApiKey.sanitize(raw);
        if (!clean.equals(raw)) {
            try {
                sp.edit().putString(KEY_API, clean).apply();
                note("API Key 里有 " + (raw.length() - clean.length())
                        + " 个多余字符（空白/零宽/全角），已自动清理："
                        + ApiKey.describe(clean));
            } catch (Throwable ignore) { }
        }
        return clean;
    }

    public static void setApiKey(Context c, String key) {
        // 存之前洗一遍：脏值一旦进 prefs，就只会在下次读的时候才被发现
        String clean = ApiKey.sanitize(key);
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_API, clean).apply();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        // 跟随 DSH 主题：必须在建任何 View 之前设定，
        // 否则会拿到上一个页面留下的颜色。
        Ui.applyTheme(this);
        Ui.applySystemBars(this);
        sCreated = true;
        sInstance = this;
        createChannel();
        startForeground(NOTIF_ID, buildNotification("正在启动容器…"));
        /*
         * 通知栏那个「悬浮球」按钮：用运行时注册的接收器接住。
         *
         * 为什么不放 manifest：这个接收器只在服务活着时有意义
         * （它要做的事就是"往本服务的窗口里加个球"），
         * 随服务生死自动注册/注销最省事，也不会在服务没跑时被空唤起。
         */
        ballReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                try {
                    if (BallWindow.isShown()) {
                        BallWindow.hide(DshService.this);
                        Toast.makeText(DshService.this, "已收起悬浮球（DSH 仍在跑）",
                                Toast.LENGTH_SHORT).show();
                    } else {
                        BallWindow.show(DshService.this);
                        BallWindow.reconcile(DshService.this, true);
                        Toast.makeText(DshService.this, "已打开悬浮球", Toast.LENGTH_SHORT).show();
                    }
                    updateNotification(sState);
                } catch (Throwable t) {
                    Log.w(TAG, "通知栏悬浮球按钮失败", t);
                }
            }
        };
        registerReceiver(ballReceiver, new android.content.IntentFilter(ACTION_BALL_TOGGLE));
        new Thread(this::supervise, "dsh-supervisor").start();
        // 悬浮球单独一个看护线程 —— 为什么不能塞进 supervise()，见 ballLoop() 的注释
        new Thread(this::ballLoop, "dsh-ball").start();
        new Thread(this::watchRestart, "dsh-restart-watcher").start();
        // 无障碍守护：厂商把无障碍开关关掉时自动写回去（需要一次性 WRITE_SECURE_SETTINGS 授权）
        A11yGuard.start(this);
        /*
         * 桌宠悬浮窗宿主**已移除**（2026-10-21，用户要求）。
         *
         * 它原来会读 /sdcard/.dsh-phone/pet-overlay.json，替 dsh-pet-android 插件
         * 建一个 `fullscreen-transparent` 的整屏透明窗来放宠物页面。
         * 问题是那个页面一报错就整块红字浮在对话上面（用户截图："dsh-pet 配置错误：
         * 配置加载失败：config http 404"）—— 而那个窗口归本类管，删掉它就不会再出现。
         * 用户明确说"我已经不需要了"，所以连带 {@code PetOverlayHost.java} 一起删除。
         */
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_DIAG.equals(intent.getAction())) {
            runDiagnosticsNow();
            return START_STICKY;
        }
        // 灵动岛（实时进度悬浮条）：DSH 起来就按用户保存的开关恢复；关过的不再自动开
        HudOverlayService.restore(this);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        // 伴随 DSH 停止收起胶囊，但**保留开关**，下次启动自动恢复
        HudOverlayService.suspend(this);
        keepRunning = false;
        if (sInstance == this) sInstance = null;
        try {
            if (sProcess != null) sProcess.destroy();
        } catch (Throwable ignore) { }
        sProcess = null;
        sUrl = null;
        sState = "已停止";
        // 服务没了，窗口也不该留着；但**不要动用户的开关**（见 detachQuietly 注释）
        try { BallWindow.detachQuietly(); } catch (Throwable ignore) { }
        try { if (ballReceiver != null) unregisterReceiver(ballReceiver); } catch (Throwable ignore) { }
        ballReceiver = null;
        super.onDestroy();
    }

    /**
     * 悬浮球看护线程：用户要"退到后台、有任务时自动出现；回到主界面就消失"。
     *
     * <h3>为什么必须是独立线程（这是踩出来的）</h3>
     * 一开始把它放在 {@link #supervise()} 的循环里，想着"那儿已经每 5 秒跑一次，
     * 不用多起线程"。结果**它一次都没执行过**：{@code startDsh()} 内部会
     * **阻塞读容器的输出流直到进程结束**（这是设计如此 —— 就靠它解析带 token 的 URL），
     * 所以容器活着的整个期间，那个循环根本回不到顶部。
     * 换句话说：**监督循环的"5 秒一次"只在容器没跑的时候成立**，
     * 而悬浮球恰恰要在容器跑着的时候工作。两者节奏完全不搭。
     *
     * <p>独立线程反而更省心：它的周期不受容器状态影响，
     * 也不会因为容器卡住而一起卡住。
     */
    /**
     * 悬浮球看护线程这一拍该睡多久（毫秒）。
     *
     * <h3>为什么不是固定值（2026-10-21 用户报"用久了发热严重"）</h3>
     * 这段循环原来固定 1.5 秒一拍，每拍扫一遍 /proc。手机有几百个进程，
     * 等于**永不停止**地每分钟上万次文件读取 —— 屏幕黑着也照跑，电和热都浪费在这上面。
     *
     * <p>三档依据很简单：**球看不见的时候，没有任何理由高频轮询**。
     * <ul>
     *   <li>息屏 → 15 秒：什么都看不见；</li>
     *   <li>我们的界面在前台 → 4 秒：按规则球本来就不显示（自己的界面在前台一律不显示），
     *       阶段主要由网页那一路报送上来；</li>
     *   <li>其余（在后台）→ 2 秒：球可能随时要冒出来，得跟上。</li>
     * </ul>
     */
    private long ballTickMs() {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) return 15_000L;
        } catch (Throwable ignore) { }
        return Foreground.ours() ? 4_000L : 2_000L;
    }

    private void ballLoop() {
        TaskWatch.Snap snap = null;
        long activeAt = 0L;
        /** 上一拍是否"有任务在跑" —— 用来算出"任务刚刚结束"（→ 已完成）。 */
        boolean wasBusy = false;
        /** 连续几拍 CPU 都在重烧（≥2 才算真的在干活，见下面的说明）。 */
        int heavyStreak = 0;
        /** 带 pid 缓存的采样器（进程列表 20 秒才重扫一次，见 TaskWatch.Sampler）。 */
        TaskWatch.Sampler sampler = null;
        /*
         * 起手先留一行痕迹：用户报"球不换表情"时，第一个要回答的问题是
         * "这个心跳到底跑没跑起来" —— 没有这一行的话，日志文件是空的，
         * "没跑"和"跑了但没判出来"就分不开。
         */
        BallWindow.trace("阶段心跳启动（悬浮球开关=" + BallWindow.enabled(this)
                + "，自动模式=" + BallWindow.autoMode(this) + "）");
        /*
         * 采样间隔**按状态自适应**（2026-10-21 用户报"用久了发热严重"）。
         *
         * 老写法固定 1.5 秒一拍，每拍都把 /proc 全扫一遍 —— 屏幕黑着也照跑，
         * 一小时就是几千次全表扫描。现在分成三档，并且用 {@link TaskWatch.Sampler}
         * 把进程列表缓存起来（20 秒才重扫一次，中间每拍只读 3 个进程的 stat）：
         *
         *   息屏       15 秒：什么都看不见，只有极少数线索要维持
         *   我们在前台  4 秒：球本来就不显示（规则：自己的界面在前台一律不显示），
         *                    阶段主要靠网页那一路报，不需要高频
         *   在后台      2 秒：球可能随时要冒出来，得跟上
         */
        while (keepRunning) {
            try {
                Thread.sleep(ballTickMs());
            } catch (InterruptedException ie) {
                return;
            }
            try {
                /*
                 * ⚠️ **用户主动收起后，这里必须彻底不动**（用户报的 bug）。
                 *
                 * 第一版没有这道闸：用户点「收起」销毁了窗口，但本循环 1.5 秒后
                 * 又看到"容器在干活"，于是 `shouldShow → true` 把它**又显示回来** ——
                 * 用户看到的现象就是"关掉了还会自己冒出来，像关不掉"。
                 *
                 * 现在 `BallWindow.enabled()`（= 用户是否允许它出现）是唯一的总闸：
                 * 关掉后自动那条路完全不介入，直到用户点「打开悬浮球」
                 * 或点常驻通知上的按钮（两者都会把它置回 true）。
                 */
                if (!BallWindow.enabled(this)) {
                    snap = null;                 // 下次开启时重新取基准，避免用过期读数
                    activeAt = 0L;
                    continue;
                }
                /*
                 * ① 先算"在不在干活"——**常驻模式和自动模式都要算**。
                 *
                 * ⚠️ 2026-10-21 修的关键 bug：原来这段在 `if (!autoMode) { … continue; }`
                 * **之后**，于是用户一关掉「自动出现」（很多人为了让球一直挂着就会关），
                 * 这个函数就再也走不到下面的 {@link BallWindow#updateStage} ——
                 * 阶段永远停在初始值 IDLE，球的表情也永远不换。
                 * 用户报的"开了随任务阶段换表情但从来不变"就是这么来的。
                 */
                File rootfs = new File(Env.base(this), "rootfs");
                if (sampler == null) {
                    sampler = new TaskWatch.Sampler(rootfs, android.os.Process.myPid());
                }
                boolean busyNow = false;
                boolean heavyNow = false;
                if (snap == null) {
                    // 首次只做基准：没有上一次的读数就算不出增量（下一拍就能算了）
                    snap = sampler.sample(false);
                } else {
                    TaskWatch.Snap now = sampler.sample(false);
                    long dt = now.at - snap.at;
                    busyNow = AutoBall.busy(snap.cpuMs, now.cpuMs, now.procs, dt);
                    // 判阶段用更严格的档位：闲着时的 node 也在烧几十毫秒，不能用它说"在思考"
                    heavyNow = AutoBall.heavyCpu(snap.cpuMs, now.cpuMs, now.procs, dt);
                    activeAt = AutoBall.markActive(activeAt, now.at, busyNow);
                    snap = now;
                }
                boolean cpuBusy = AutoBall.stillActive(activeAt, System.currentTimeMillis());
                /*
                 * ② "在干活" = 容器 CPU 活跃 **或** 网页说它正在生成/跑工具。
                 *
                 * 为什么要或上网页这一路：CPU 采样只是间接证据（等模型吐字时可能不动），
                 * 而网页是**第一现场**——它自己就在渲染"深度求索中"和工具卡片。
                 */
                boolean busy = cpuBusy || pageBusy();
                /*
                 * 阶段用的"在干活"和显示用的 `busy` **故意不一样**：
                 * `busy` 带 8 秒保持窗口（防止球一闪一闪），但**当前这一刻**是否在干活
                 * 只有"网页怎么说 + CPU 有没有连续在烧"说得准。
                 *
                 * 用带保持窗口的那个去判阶段的话，任务结束后会多出几秒的"思考中…"——
                 * 明明已经做完了却说在思考，比不显示更糟（用户盯着气泡就是想看准）。
                 *
                 * 单拍的重活**也不算数**：容器偶尔一次 GC/写盘就会让增量冒一下，
                 * 直接采信的话气泡会"思考中 → 已完成"闪一下（实测日志里 16:45:14 那一次）。
                 * 连续两拍都重才算真的在跑东西 —— 1.5 秒一拍，代价是晚 1.5 秒，
                 * 换来的是一条不抽搐的气泡。
                 */
                if (heavyNow) heavyStreak++; else heavyStreak = 0;
                // 网页说在干活就是干活；网页不说话时才看 CPU（且要连续两拍）
                boolean stageActive = pageBusy() || heavyStreak >= 2;
                boolean justFinished = wasBusy && !stageActive;
                wasBusy = stageActive;

                /*
                 * ③ ★ 喂给悬浮球的"任务阶段"（气泡第一行 + 球的表情）。
                 *
                 * 信号四路，优先级从高到低（判据本身在纯逻辑 {@link BallStage} 里）：
                 *   · 网页报的阶段（pageStage，最准，它就长在对话界面上）
                 *   · stdout 最近一行（有 20 秒时效，陈年启动日志不算数）
                 *   · busy（CPU / 网页两路取或）
                 *   · justFinished（上一拍在跑、这一拍停了 = 已完成）
                 *
                 * 放在这里而不是另起线程：这里本来就是 1.5 秒一拍的心跳，顺手算完最省。
                 */
                try {
                    BallWindow.updateStage(this, getCurrentLine(), getCurrentLineAgeMs(),
                            stageActive, justFinished, getState(),
                            pageStage(), pageStageAge());
                    /*
                     * 气泡正开着时，让它的文案跟着阶段走。
                     * 不刷新的话，用户把气泡放着不动就永远停在第一次打开时的那句话 ——
                     * 而他打开气泡正是为了盯进度。
                     */
                    BallWindow.refreshBubbleIfVisible();
                } catch (Throwable t) {
                    Log.w(TAG, "阶段更新失败（不影响悬浮球其它行为）", t);
                }

                /*
                 * ④ 再决定窗口要不要显示。
                 *
                 * 常驻模式：球应当一直看得见（但设置页/主界面这种"我们自己的界面在前台"
                 * 仍然要让开，那是用户点名不要的）。
                 */
                boolean want;
                if (!BallWindow.autoMode(this)) {
                    want = !Foreground.ours();
                } else {
                    want = AutoBall.shouldShow(true, Foreground.ours(), busy);
                }
                /*
                 * 窗口按需创建：BallWindow.show() 幂等，而且它只是往**本服务**这个
                 * 已经在跑的前台服务里加个窗口 —— 不再有"在后台 startForegroundService
                 * 被系统拒"的问题（那正是它从 Service 改成窗口管理器的原因）。
                 */
                if (want) {
                    if (!BallWindow.isShown()) BallWindow.show(this);
                    if (!BallWindow.isVisible()) BallWindow.reconcile(this, true);
                } else if (BallWindow.isShown()) {
                    BallWindow.reconcile(this, false);
                }
            } catch (Throwable t) {
                Log.w(TAG, "悬浮球看护异常", t);
            }
        }
    }

    // ── 监督循环：进程挂了就重启 ──────────────────────────────
    private void supervise() {
        /** 已经跑了多少轮 5 秒（用来把"每 N 分钟做一次"的活摊进来）。 */
        int ticks = 0;
        while (keepRunning) {
            try {
                ticks++;
                if (sAdopted) {
                    /*
                     * 复用的容器：每 ~30 秒探一次（本地 HTTP，几百微秒 —— 不穿 proot）。
                     * 它要是死了，就把复用标记清掉，下一圈正常重新拉起。
                     */
                    if (ticks % 6 == 0) {
                        String u = sUrl;
                        if (u == null || !probeDsh(u, 900)) {
                            Log.w(TAG, "复用的容器已不可用，准备重新启动");
                            appendReport("  · 复用的容器已不可用 → 重新启动容器\n");
                            sAdopted = false;
                            sUrl = null;
                            try { runningUrlFile(Env.base(this)).delete(); } catch (Throwable ignore) { }
                        }
                    }
                } else if (!isAlive()) {
                    startDsh();
                }
                /*
                 * 每 ~60 轮（= 5 分钟）查一次 App 有没有新版本。
                 *
                 * 为什么放在这个循环里：它已经在每 5 秒跑一次了，顺手加个计数器
                 * 就不用再起一个定时器/服务（多一个后台组件就多一个被系统清掉的理由）。
                 * AppUpdate 自己带 6 小时缓存、而且**同一个版本只推一次通知**，
                 * 所以这里的频率不会变成打扰。
                 *
                 * 为什么放在独立线程：查 GitHub 要走网络、可能卡十几秒 ——
                 * 绝不能堵住监督循环（那会让容器挂了都不重启）。
                 */
                if (ticks % 60 == 0) {
                    new Thread(() -> {
                        try { AppUpdate.notifyIfNewer(DshService.this); } catch (Throwable ignore) { }
                    }, "app-update-check").start();
                }

                /*
                 * 悬浮球**不放在这个循环里** —— 见下面 {@link #ballLoop()} 的注释：
                 * startDsh() 会阻塞读容器输出流直到进程结束，
                 * 所以这个循环在容器运行期间根本回不到顶部。
                 */
                /*
                 * 装环境期间轮询快一点（1.5 秒）：install 一结束就要立刻起容器，
                 * 否则用户会白等一个完整周期。平时仍是 5 秒。
                 */
                Thread.sleep(MainActivity.isInstalling() ? 1500 : 5000);
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable t) {
                Log.e(TAG, "supervise error", t);
                setState("异常: " + t.getMessage());
                try { Thread.sleep(5000); } catch (InterruptedException ie) { return; }
            }
        }
    }

    /**
     * 处理"用户改了设置"导致的重启请求。
     *
     * 为什么必须是独立线程：startDsh() 内部会**阻塞读取 dsh 的输出流**直到进程结束，
     * 所以 supervise() 在 dsh 运行期间根本回不到循环顶部去检查重启标记。
     * （这是实际踩过的坑：改完 API Key 点保存，界面显示"正在重启"但容器纹丝不动。）
     *
     * 这里主动杀掉进程，读取线程随之 EOF、startDsh() 返回，
     * supervise() 就会用新的 API Key 重新拉起容器。
     */
    private void watchRestart() {
        while (keepRunning) {
            try {
                if (sRestartRequested) {
                    sRestartRequested = false;
                    Log.i(TAG, "收到重启请求，杀掉当前 dsh 进程");
                    setState("正在重启…");
                    updateNotification("正在按新设置重启…");
                    Process p = sProcess;
                    /*
                     * sUrl 必须在 destroy **之前**清掉。
                     * 否则会和 supervise() 里刚起来的 dsh 抢时序：
                     * 新进程已经打印了 URL、sUrl 刚被赋值，这里再清一次就把它抹掉了，
                     * 而 dsh 只在启动时打印一次 URL —— 结果是 awaitUrl 永远等不到。
                     */
                    sUrl = null;
                    if (p != null) {
                        p.destroy();
                        Thread.sleep(800);
                        /*
                         * 只有**确实杀过进程**才去扫残留。
                         * 如果 p 为 null（本来就没进程在跑，例如首次初始化的重启请求），
                         * 这里扫 /proc 会把 supervise() 刚拉起来的 dsh 一起杀掉 ——
                         * 表现就是无限重启。
                         * 启动前的清理已经在 startDsh() 里做过，这里是多余的。
                         */
                        int n = Env.killStaleDsh(Env.base(this));
                        Log.i(TAG, "清理残留进程: " + n + " 个");
                    }
                }
                Thread.sleep(1000);
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable t) {
                Log.e(TAG, "watchRestart error", t);
            }
        }
    }

    private void startDsh() {
        try {
            File base = Env.base(this);
            /*
             * ★ 正在装环境（首次安装）：本服务此刻的唯一职责是**替初始化保住进程**。
             *
             * 2026-11-17 用户日志里的真实事故：初始化在下载 rootfs 时进程被系统清掉，
             * 下载作废、下次点开又从 TEST 1 重来 —— 于是"每次启动都要一分钟以上"。
             * 前台服务在跑 = 进程是前台优先级，系统不会随手回收；
             * 通知栏里还会显示真实进度，用户能看见它在动。
             *
             * ⚠️ 判据用 isInstalling() 而**不是** isProvisioning()：
             * 初始化的最后一步是"起 dsh web 并等它就绪"，那一步正需要本服务去起容器，
             * 若在这里继续让路就会互相等到超时（见 MainActivity.sInstalling 的注释）。
             */
            if (MainActivity.isInstalling()) {
                sNeedsProvision = false;
                int pct = MainActivity.sProvisionPct;
                String stage = MainActivity.sProvisionStage;
                if (stage == null) stage = "准备中";
                stage = stage.replace('\n', ' ').replace('\r', ' ').trim();
                if (stage.length() > 42) stage = stage.substring(0, 42) + "…";
                setState("正在准备运行环境…");
                updateNotification("正在准备运行环境"
                        + (pct >= 0 ? " · " + pct + "%" : "")
                        + " · " + stage + "（锁屏也会继续）");
                Thread.sleep(1200);
                return;
            }
            if (!Env.isInstalled(base)) {
                sNeedsProvision = true;
                setState("容器未安装完成");
                updateNotification("容器尚未安装完成，请打开 App 完成初始化");
                Thread.sleep(10000);
                return;
            }
            sNeedsProvision = false;
            /*
             * ★ 先看看"上次那个容器是不是还活着"（2026-11-17）。
             *
             * 这台机器上 dsh 冷启动要 **73 秒**（用户实测：App 侧只占 3 秒）。
             * 而 App 进程被系统回收时，容器里的 proot/node 经常**还活着** ——
             * 老逻辑下一启动就 killStaleDsh() 把它杀掉、再从零起一份，
             * 等于白扔 73 秒。现在先探一下：能连上、token 还有效就直接接管。
             */
            if (tryAdoptRunning(base)) return;
            /*
             * 先挑一个**没被占用**的端口。3080 很容易撞车，
             * 而端口被占时 dsh 会直接起不来、监督循环无限重启 ——
             * 用户看到的现象和"卡死"完全一样。
             */
            int port = Env.pickFreePort(Env.DSH_PORT, Env.DSH_PORT + 10);
            Env.setPort(port);
            if (port != Env.DSH_PORT) {
                appendReport("  · 默认端口 " + Env.DSH_PORT + " 被占用，改用 " + port + "\n");
                Log.w(TAG, "端口 " + Env.DSH_PORT + " 被占用，改用 " + port);
            } else {
                appendReport("  · 使用端口 127.0.0.1:" + port + "\n");
            }
            setState("正在启动 dsh web…");
            updateNotification("正在启动 DSH…");

            /*
             * 启动计时（2026-10-21，用户报"插件装多了启动特别慢"）。
             *
             * 以前只知道一个总耗时，不知道慢在哪一段 —— 是解包自带插件、
             * 还是容器里 node 加载插件。埋上之后 report.txt 会逐年段写出毫秒数，
             * 用户把日志发过来就能定位，不用再猜。
             */
            final long tBoot = System.currentTimeMillis();

            /*
             * ── 启动前置工作：**并行**做（2026-11-16，用户要求"优化启动速度"）──
             *
             * 下面这几件事互不依赖，但以前是一件一件串着做的 —— 而每一件都要碰
             * 手机存储（写 proot 和两个 .so、写 6 个插件目录、mkdir 共享存储、
             * 扫一遍 /proc），真机上单项几十到几百毫秒。串起来就是：
             * "容器还没开始起，用户已经白等了几百毫秒"。
             *
             * 依赖关系只有两条，所以分成四组：
             *   A. proot 本体（{@code prepareProot}）—— 起容器、装 pnpm 都要它；
             *   B. 手机控制桥 + 容器里的 phone CLI / token —— 独立的资产拷贝；
             *   C. profile 里的插件（摘桌宠 + 装自带插件）—— **两者都改同一个
             *      package.json**，所以必须放进同一个任务里串行，不能拆成两个并行；
             *   D. 挂载点与工作区（mkdir /sdcard/dsh）—— 独立。
             *
             * 所有组都在本线程 join 完才往下走，因此"起 dsh 之前容器与 profile
             * 一定已经就绪"这个前提**没有被改变** —— 只是把等待时间叠在一起了。
             */
            final java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                        Thread t = new Thread(r, "dsh-preflight");
                        t.setDaemon(true);
                        return t;
                    });
            final java.util.concurrent.atomic.AtomicReference<String> libDirRef =
                    new java.util.concurrent.atomic.AtomicReference<>();
            final java.util.concurrent.atomic.AtomicReference<String> tokenDirRef =
                    new java.util.concurrent.atomic.AtomicReference<>();
            final java.util.concurrent.atomic.AtomicReference<String> wsRef =
                    new java.util.concurrent.atomic.AtomicReference<>();
            /** 各组的日志，按组收好后再按固定顺序写报告 —— 免得并行日志互相穿插。 */
            final java.util.List<String> preflightLog =
                    java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
            final long[] msOf = {0L, 0L, 0L, 0L};   // A proot / B 手机桥 / C 插件 / D 挂载点

            /* A. proot 本体 + 依赖库 + loader（后面起容器、装 pnpm 都用 libDir） */
            java.util.concurrent.Future<?> fA = pool.submit(() -> {
                long t = System.currentTimeMillis();
                try {
                    libDirRef.set(Env.prepareProot(DshService.this, base));
                } catch (Throwable e) {
                    preflightLog.add("  · ⚠️ proot 准备失败: " + e + "\n");
                    Log.w(TAG, "prepareProot 失败", e);
                } finally {
                    msOf[0] = System.currentTimeMillis() - t;
                }
            });

            /* B. 手机控制桥（token 目录随后只读绑定进容器）+ 容器里的 phone CLI / AGENTS.md */
            java.util.concurrent.Future<?> fB = pool.submit(() -> {
                long t = System.currentTimeMillis();
                try {
                    PhoneBridge.start(DshService.this);
                    tokenDirRef.set(PhoneBridge.tokenDir(DshService.this));
                    Env.installPhoneTools(DshService.this, base);
                    Log.i(TAG, "手机控制桥 token 目录: " + tokenDirRef.get()
                            + " · 白名单 " + PhoneBridge.allowed(DshService.this).size() + " 个应用");
                } catch (Throwable e) {
                    preflightLog.add("  · ⚠️ 手机控制桥/phone CLI 失败: " + e + "\n");
                    Log.w(TAG, "手机控制前置失败", e);
                } finally {
                    msOf[1] = System.currentTimeMillis() - t;
                }
            });

            /* C. profile 里的插件：摘桌宠 + 装自带插件（同改 package.json → 必须串行） */
            java.util.concurrent.Future<?> fC = pool.submit(() -> {
                long t = System.currentTimeMillis();
                try {
                    /*
                     * 先把「桌宠」插件摘掉（2026-10-21）：它的显示层已经被删了，
                     * 留在容器里只会每 100~200ms 写一次请求文件穿 proot，纯烧电。
                     * 幂等：摘干净之后这里每次都返回 null。
                     */
                    try {
                        String petMsg = Env.disablePetPlugin(base);
                        if (petMsg != null) {
                            Log.i(TAG, petMsg);
                            preflightLog.add("  · " + petMsg + "\n");
                        }
                    } catch (Throwable e) {
                        Log.w(TAG, "摘桌宠插件异常", e);
                    }
                    /*
                     * 装随 APK 自带的 DSH 插件（phone 工具集）。
                     *
                     * 放在起 dsh 之前：插件是 bundle，要靠 profile 启动时加载 ——
                     * 起完再装的话本次会话看不到那些工具，用户会以为没生效。
                     * 幂等（已装过就跳过一次文件读），所以只在首次/升级后真解包。
                     * 失败也不阻断启动：phone CLI 那条路还在，功能不会全丢。
                     */
                    /*
                     * 注意：这里传的 tokenDir / libDir 可能是 null —— 本组与 A、B 组并行，
                     * 那时它们可能还没算出来。{@code installPhonePlugin} 只按包名解 tgz
                     * 并改 profile 的 package.json，**从头到尾不碰这两个参数**
                     * （它只写 rootfs/root/.dsh/profiles/web/），所以传 null 是安全的；
                     * 万一将来这个方法开始用它们，编译期/自检会立刻暴露出来。
                     */
                    String pluginLog = Env.installPhonePlugin(DshService.this, base, null, null);
                    Log.i(TAG, pluginLog);
                    preflightLog.add("  " + pluginLog + "\n");
                } catch (Throwable e) {
                    preflightLog.add("  · ⚠️ 装自带插件异常: " + e + "\n");
                    Log.w(TAG, "装自带插件异常", e);
                } finally {
                    msOf[2] = System.currentTimeMillis() - t;
                }
            });

            /* D. 共享存储挂载点 + 工作区（/sdcard/dsh） */
            java.util.concurrent.Future<?> fD = pool.submit(() -> {
                long t = System.currentTimeMillis();
                try {
                    wsRef.set(Env.ensureStorageMounts(base));
                } catch (Throwable e) {
                    preflightLog.add("  · ⚠️ 挂载点/工作区准备失败: " + e + "\n");
                    Log.w(TAG, "ensureStorageMounts 失败", e);
                } finally {
                    msOf[3] = System.currentTimeMillis() - t;
                }
            });

            /*
             * 等四组都结束。上限给 2 分钟：这些操作正常是毫秒级/百毫秒级，
             * 超时只可能是因为存储异常 —— 超时也**继续往下走**（和以前一样：
             * 这些前置失败不阻断启动），但日志里会明写是谁超时了。
             */
            java.util.concurrent.Future<?>[] groups = {fA, fB, fC, fD};
            String[] groupName = {"proot", "手机控制桥", "自带插件", "挂载点/工作区"};
            for (int i = 0; i < groups.length; i++) {
                try {
                    /*
                     * 上限 30 秒（原来 120 秒）。这几组都是本地文件操作，正常是毫秒级；
                     * 真卡住（存储异常）时也不该让用户等两分钟 —— 超时照样往下走。
                     */
                    groups[i].get(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Throwable e) {
                    preflightLog.add("  · ⚠️ 前置任务「" + groupName[i] + "」未按时完成: "
                            + e.getClass().getSimpleName() + "\n");
                    Log.w(TAG, "前置任务超时/失败: " + groupName[i], e);
                }
            }
            pool.shutdownNow();

            String libDir = libDirRef.get();
            if (libDir == null) libDir = new File(base, "lib").getAbsolutePath();   // 兜底：与旧行为一致
            String tokenDir = tokenDirRef.get();
            if (tokenDir == null) tokenDir = PhoneBridge.tokenDir(this);
            String ws = wsRef.get();
            Log.i(TAG, "工作区: " + Env.WORKSPACE + " · 测试文件夹: " + ws);
            appendReport("  · [启动] 前置并行完成 " + (System.currentTimeMillis() - tBoot)
                    + "ms（proot " + msOf[0] + " · 手机桥 " + msOf[1]
                    + " · 插件 " + msOf[2] + " · 挂载点 " + msOf[3] + "，最慢的一组算总耗时）\n");
            for (String line : preflightLog) appendReport(line);

            String apiKey = getApiKey(this);
            /*
             * 把 Key 的**形态**记进报告（不含密钥本身）。
             *
             * 为什么要留这一行：用户报"用久了 API 掉了""重装就好了"时，
             * 以前日志里只有一个"已设置/未设置"，根本看不出 Key 是被截断了、
             * 还是带了不可见字符。现在一眼能看长度和掩码，再配上
             * {@link ApiKey#plausible} 的形态警告，同类问题不用再猜。
             */
            appendReport("  · API Key：" + ApiKey.describe(apiKey) + "\n");

            /*
             * ⚠️ pnpm **绝不能留在启动路径上**（2026-11-17 修"每次启动至少一分钟"）。
             *
             * 原来这里同步调 {@code Env.ensurePnpm()}：装好时它只是一次文件存在性检查
             * （几毫秒），但**没装好时会在容器里跑 `npm i -g pnpm`** —— 那是一次真实下载，
             * 而它的超时上限是 {@code PLUGIN_INSTALL_TIMEOUT_MS} = **240 秒**。
             * 于是网络慢、或连不上 npm registry 的机器上，每次启动都要先等它失败：
             * 用户看到的就是"启动至少一分钟"，而且**每次都是**（装不上 → 下次还试）。
             *
             * pnpm 只被「插件市场」用到（`dsh plugin add` 内部转发给它），
             * 和"把界面跑起来"毫无关系 —— 所以它必须挪到 dsh 起来之后再做，
             * 见 {@link #ensurePnpmInBackground}。
             */
            appendReport("  · [启动] pnpm 检查已挪到后台（不占启动路径）\n");

            /*
             * 中转站（自定义提供方）的密钥作为环境变量进容器。
             *
             * 为什么在这里注入而不是写进 settings.yaml：DSH 的 llm-pi-ai 用
             * `apiKeyEnv: ACME_GATEWAY_API_KEY` 这种**凭据引用**，配置文件里不放密钥；
             * 而容器命令是 `env -i`（清空环境），所以必须显式带到命令行上。
             * 好处是密钥始终只在这台手机上（App 私有目录 + 启动参数），
             * 容器里、容器写出来的文件里都搜不到它。
             */
            /*
             * ── 运行时选择（2026-11-18）──
             *
             * 默认 PRoot。用户在设置里开了「tawcroot 加速」才走这条，
             * 而且**必须先过自检**：tawcroot 是另一套 fake chroot（seccomp+SIGSYS、
             * 单进程、自己的硬链接仓库），能力边界和 PRoot 不同；
             * 不过自检就永久回落，并把原因写进设置页 ——
             * 绝不拿"插件市场装不上"去换几秒启动。
             */
            final String wantRt = TawcrootGate.mode(this);
            Env.sRuntime = Env.RT_PROOT;                    // 先按最稳的来，下面按设置+自检升级
            if (Env.RT_TAWCROOT.equals(wantRt)) {
                Env.sRuntime = Env.RT_TAWCROOT;
                String gateWhy = TawcrootGate.ensureUsable(this, base, libDir, apiKey, tokenDir);
                if (gateWhy == null) {
                    appendReport("  · ✅ 运行方式：tawcroot（自检通过；无 ptrace，预期启动更快）\n");
                } else {
                    Env.sRuntime = Env.RT_PROOT;
                    TawcrootGate.fallback(this, gateWhy);
                    appendReport("  · ⚠️ tawcroot 自检未通过（" + gateWhy
                            + "）→ 本次改用 PRoot（设置里可重新自检）\n");
                }
            } else if (Env.RT_TERMUX.equals(wantRt)) {
                /*
                 * Termux 原生运行时：bionic node + tawcroot 只做前缀映射。
                 * 没装用户态就安静退回 PRoot（不打扰用户，也不改设置）。
                 */
                Env.sRuntime = Env.RT_TERMUX;
                String gateWhy = TermuxGate.ensureUsable(this, base);
                if (gateWhy == null) {
                    appendReport("  · ✅ 运行方式：Termux 原生（bionic node，无 glibc/无 rootfs 翻译）\n");
                } else {
                    Env.sRuntime = Env.RT_PROOT;
                    TermuxGate.fail(this, gateWhy);
                    appendReport("  · ⚠️ Termux 原生运行时不可用（" + gateWhy
                            + "）→ 本次改用 PRoot（设置里可查看状态）\n");
                }
            }

            java.util.List<String> relayEnv = Providers.envPairs(this);
            if (!relayEnv.isEmpty()) {
                appendReport("  中转站密钥注入 " + relayEnv.size() + " 个（"
                        + Providers.load(this).size() + " 个自定义提供方）\n");
            }
            /*
             * 依赖布局为"零符号链接"时，追加 NODE_OPTIONS=--preserve-symlinks。
             *
             * 依据是用户真机 profile：59 秒里 lstat+realpath 合计约 11 秒，
             * 那部分**专门**来自 pnpm 风格的符号链接（Node 每 import 一次就要
             * lstat + realpath 一次）。布局里没有链接时这个开关不改变任何解析结果，
             * 只让 Node 跳过 realpath —— 纯收益；有链接时则**绝对不能加**
             * （会让同一个包解析成两个实例），判断见 Env.preserveSymlinksEnv。
             */
            if (preserveSymlinksPreferred()) {
                Env.preserveSymlinksEnv(relayEnv);
                appendReport("  · 依赖布局无符号链接 → 已启用 --preserve-symlinks\n");
            }
            String[] cmd = Env.dshWebCommand(base, apiKey, tokenDir, relayEnv);
            /*
             * ⚠️ 这里**不放任何诊断**（2026-11-17 回归修复）。
             *
             * 0.4.16 把 `logDshStateSizes()` 放在了这一行 —— 它要遍历 `.dsh/sessions`
             * 等目录（上限 2 万个文件），而这一行正好在"起容器"的**前面**：
             * 用户实测启动反而慢了几秒。诊断再便宜，也不能待在关键路径上。
             * 现在全部挪到 {@link #deferDiagnostics}（就绪后 40 秒、后台线程、低优先级）。
             */

            // 起新的之前先把可能残留的旧容器进程清掉（否则 3080 端口会被占住）
            try { runningUrlFile(base).delete(); } catch (Throwable ignore) { }
            sAdopted = false;
            int stale = Env.killStaleDsh(base);
            if (stale > 0) Log.i(TAG, "启动前清理残留进程 " + stale + " 个");

            sUrl = null;
            Process p = Env.buildProcess(base, cmd, libDir).start();
            sProcess = p;
            Log.i(TAG, "dsh 进程已启动, apiKey=" + (apiKey.isEmpty() ? "未设置" : "已设置"));
            appendReport("  · [启动] 拉起 dsh 进程 " + (System.currentTimeMillis() - tBoot)
                    + "ms（之后是容器里 node 加载插件的时间）\n");
            /*
             * 通知栏**不再提 API Key**（2026-11-17，用户要求）。
             *
             * 原来这里写"运行中（未设置 API Key）"，而 Key 缺失在很多人的用法里
             * 是**正常状态**（用中转站/自定义提供方），常驻通知挂一句"未设置"
             * 只会天天提醒一件不需要处理的事。现在统一写"运行中"。
             * Key 的状态仍可在「设置」里看到，也仍然写进 report.txt。
             */
            updateNotification("运行中");

            /*
             * pnpm 放到这里（dsh 进程已经拉起来之后）再装：
             * 它只服务插件市场，晚几十秒没有任何影响；而放在前面会把启动拖住。
             * 失败还会退避 6 小时，避免"每次启动都去撞一次慢网络"。
             */
            ensurePnpmInBackground(base, tokenDir, libDir);

            // 持续读取输出：解析带 token 的 URL，同时**保留最近若干行**用于诊断
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int n = 0;
                /*
                 * 逐行计时（2026-11-17）：用户实测 dsh 冷启动 73 秒，需要知道这 73 秒
                 * 是"一路慢"还是"卡在某一步"。做法很简单：
                 *   · 第一行输出的时刻 = 容器/node 启动到能说话的时间；
                 *   · 相邻两行间隔超过 3 秒 → 记一条"静默 Ns（上一行是……）"；
                 *   · 就绪时汇总"首行 / 行数 / 最大静默 + 它出现在哪一行之后"。
                 * 这三条一出来，是网络阻塞、插件扫描还是纯慢，一眼就分开了。
                 */
                long tBootMs = System.currentTimeMillis();
                long tPrev = tBootMs, maxGap = 0L, tFirstLine = 0L;
                String maxGapAfter = null, prevLine = null;
                while ((line = br.readLine()) != null) {
                    long now = System.currentTimeMillis();
                    if (n == 0) {
                        tFirstLine = now;
                        appendReport("  · [启动] dsh 首行输出 " + (now - tBoot) + "ms\n");
                    } else {
                        long gap = now - tPrev;
                        if (gap > 3000L) {
                            appendReport("  · [启动] dsh 静默 " + (gap / 1000) + "s（上一行: "
                                    + clip(prevLine, 60) + "）\n");
                        }
                        if (gap > maxGap) {
                            maxGap = gap;
                            maxGapAfter = prevLine;
                        }
                    }
                    tPrev = now;
                    prevLine = line;
                    n++;
                    synchronized (sRecent) {
                        sRecent.addLast(line);
                        sLastLineAt = System.currentTimeMillis();
                        while (sRecent.size() > RECENT_MAX) sRecent.removeFirst();
                    }
                    // 头几行 + 任何像报错的行都写进报告
                    if (n <= 30 || looksLikeError(line)) {
                        appendReport("    dsh| " + line + "\n");
                    }
                    if (sUrl == null) {
                        /*
                         * 只认 DSH **自己**的地址 —— 见 {@link UrlPick} 的类注释。
                         *
                         * 【真实事故】装了第三方插件（billion-context）之后，那个插件
                         * 在自己的端口上起了个面板服务，并把地址打进了 dsh 的输出；
                         * 旧逻辑"抓第一个 127.0.0.1 地址"于是抓到插件那个端口，
                         * 整个界面被加载成插件的网页 —— 用户看到的就是"点开网页退不出去"。
                         *
                         * 现在要求地址带鉴权 token（DSH 的形态），插件/本地代理的
                         * 裸端口一律不认；另外顺手把"看到的外来回环地址"记进报告，
                         * 下次再有这种事，日志里直接能看出来。
                         */
                        String foreign = UrlPick.pickForeignLoopback(line);
                        if (foreign != null && foreignReported.add(foreign)) {
                            appendReport("  · 输出里还有别的本地服务（不是 DSH，已忽略）："
                                    + foreign + "\n");
                            Log.i(TAG, "忽略非 DSH 的本机地址: " + foreign);
                        }
                        String picked = UrlPick.pickDshUrl(line);
                        if (picked != null) {
                            sUrl = picked;
                            saveRunningUrl(base, picked);   // 供下次启动"接管已运行的容器"
                            appendReport("  · [启动] dsh 计时：首行 " + (tFirstLine - tBoot)
                                    + "ms · 共 " + n + " 行 · 最大静默 " + (maxGap / 1000.0) + "s"
                                    + (maxGapAfter != null ? "（在 " + clip(maxGapAfter, 50) + " 之后）" : "")
                                    + "\n");
                            setState("运行中 · " + hostPortOf(sUrl));
                            appendReport("  dsh 就绪: " + sUrl + "\n");
                            /*
                             * 顺手把"配的是哪个模型 provider"记进报告。
                             *
                             * 起因：用户报"压缩上下文时 gateway/internal: … imageRequestPricing
                             * is not a function"。那个错只跟 provider 的适配器有关
                             * （见 Env.modelRoutes 的注释与 CHANGELOG v0.2.8.3），
                             * 所以这一行是定位它的关键线索；只记 provider/model，不碰凭据。
                             */
                            appendReport("  · 模型路由：" + Env.modelRoutes(this) + "\n");
                            Log.i(TAG, "就绪: " + sUrl);
                            /*
                             * 容器活了才去拉常驻服务。
                             *
                             * 顺序很重要：服务（比如本地代理）要在容器**已经可用**之后
                             * 才起得来；而且它得跑在 dsh web 之前或同时，
                             * 否则"客户端先连、代理还没起来"又会回到用户抱怨的那个绕法。
                             */
                            deferDiagnostics(base);
                            Services.startEnabled(DshService.this);
                            Services.startWatchdog(DshService.this);
                            /*
                             * 内核版本检测：**不占启动路径**，另起一个后台线程。
                             * 它要联网查 npm，快则几百毫秒、慢则几秒；
                             * 卡在这儿没有意义 —— 界面早就该出来了。
                             */
                            new Thread(() -> {
                                try {
                                    String cur = KernelUpgrade.installedVersion(DshService.this);
                                    String up = KernelUpgrade.availableUpdate(DshService.this);
                                    if (up != null) {
                                        appendReport("  ⬆ 内核有新版本: " + up
                                                + "（当前 " + cur + "）—— 设置 →「内核升级（本机）」可一键升级\n");
                                        KernelUpgrade.notifyUpdate(DshService.this, up);
                                    } else if (cur != null) {
                                        appendReport("  内核已是最新（" + cur + "）\n");
                                    }
                                } catch (Throwable t) {
                                    Log.w(TAG, "检查内核版本失败", t);
                                }
                            }, "dsh-version-check").start();
                        }
                    }
                }
            }
            int code = p.waitFor();
            Log.w(TAG, "dsh 进程退出, code=" + code);
            appendReport("  ❌ dsh 进程退出 code=" + code + "\n" + getRecentOutput());
            setState("进程已退出 (" + code + ")，将自动重启");
            updateNotification("DSH 已退出，正在重启…");
        } catch (Throwable t) {
            Log.e(TAG, "startDsh failed", t);
            setState("启动失败: " + t.getClass().getSimpleName() + " " + t.getMessage());
            updateNotification("DSH 启动失败，详见 App 内日志");
        }
    }

    /** pnpm 后台安装的互斥：同一时刻只跑一份。 */
    private static final java.util.concurrent.atomic.AtomicBoolean sPnpmInstalling =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** 上次尝试装 pnpm 的时刻（失败后按它退避，别每次启动都去撞网络）。 */
    private static final String KEY_PNPM_LAST_TRY = "pnpm_last_try";
    /** 失败后多久内不再重试。 */
    private static final long PNPM_RETRY_BACKOFF_MS = 6L * 3600 * 1000;

    /**
     * 在**后台**确保容器里有 pnpm（插件市场的前置条件）。
     *
     * <h3>为什么必须异步（2026-11-17，用户报"每次启动至少一分钟"）</h3>
     * {@code Env.ensurePnpm()} 在 pnpm 缺失时会跑 `npm i -g pnpm` ——
     * 一次真实下载，超时上限 240 秒。它原来被同步放在"起 dsh 之前"，
     * 于是网络慢的机器上每次启动都要先等它失败，界面被硬生生拖住一分钟以上。
     *
     * <p>而 pnpm 只被「插件市场」用到，和"界面能不能用"无关 ——
     * 所以这里改成：dsh 起来之后在后台装，装好了插件市场自然可用；
     * 装不上就退避 {@link #PNPM_RETRY_BACKOFF_MS}，不再每次启动都去撞一次。
     *
     * <p>三条边界：已装好 → 连线程都不起；正在装 → 不重复起；
     * 上次失败且未过退避期 → 直接跳过（日志里写清楚，免得用户以为坏了）。
     */
    private void ensurePnpmInBackground(final File base, final String tokenDir, final String libDir) {
        try {
            final File pnpm = new File(base, "rootfs/opt/" + Env.NODE_DIR + "/bin/pnpm");
            if (pnpm.exists()) return;                       // 已就绪：零成本
            final long now = System.currentTimeMillis();
            long last = getSharedPreferences(PREFS, MODE_PRIVATE).getLong(KEY_PNPM_LAST_TRY, 0L);
            if (last > 0 && now - last < PNPM_RETRY_BACKOFF_MS) {
                long leftMin = (PNPM_RETRY_BACKOFF_MS - (now - last)) / 60000L;
                appendReport("  · pnpm 上次没装成，约 " + leftMin
                        + " 分钟后才会再试（插件市场暂时不可用，其它功能不受影响）\n");
                return;
            }
            if (!sPnpmInstalling.compareAndSet(false, true)) return;
            Thread t = new Thread(() -> {
                try {
                    appendReport("  · [后台] 开始装 pnpm（只影响插件市场，界面不等它）\n");
                    String log = Env.ensurePnpm(base, tokenDir, libDir);
                    Log.i(TAG, log);
                    appendReport("  · [后台] " + log + "\n");
                    boolean ok = pnpm.exists();
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putLong(KEY_PNPM_LAST_TRY, ok ? 0L : System.currentTimeMillis())
                            .apply();
                } catch (Throwable e) {
                    Log.w(TAG, "后台装 pnpm 失败", e);
                    try {
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                .putLong(KEY_PNPM_LAST_TRY, System.currentTimeMillis()).apply();
                    } catch (Throwable ignore) { }
                } finally {
                    sPnpmInstalling.set(false);
                }
            }, "pnpm-install");
            t.setDaemon(true);
            t.start();
        } catch (Throwable e) {
            Log.w(TAG, "ensurePnpmInBackground 异常", e);
        }
    }

    /* ─────────────────── 复用正在运行的容器 ─────────────────── */

    /**
     * 尝试**接管**一个已经在跑的容器，而不是杀掉重起。
     *
     * <h3>为什么（2026-11-17 用户实测：dsh 冷启动 73 秒）</h3>
     * 这台机器上 App 侧只花 3 秒，剩下 73 秒全在容器里。而 App 进程被系统回收时，
     * 容器里的 proot/node 常常**还活着**（它们是独立进程，且 node 的启动代价已经付过）。
     * 老逻辑一上来就 {@code killStaleDsh()} 把它清掉、再从零起一份 —— 白扔 73 秒。
     *
     * <p>现在：上次跑起来时把带 token 的 URL 落到 {@code running.url}；
     * 本次启动先按它探一下（本地 HTTP，几百微秒，**不穿 proot**）：
     * 服务端回 200/302/303 就说明"dsh 还活着、token 还有效" → 直接接管。
     *
     * <p>为什么用"HTTP + token"而不是"看 pid 还在不在"：pid 可能被系统复用，
     * 也可能 dsh 进程在但服务器没起来；而**只有我们的 dsh 知道那串 token**，
     * 能拿它换回 303 就等价于"这就是那台还在跑的 DSH"。
     *
     * <p>探测失败（超时/连接被拒/401）就删掉记录，走原来的启动路径 ——
     * 复用是纯优化，失败绝不影响正确性。
     */
    private boolean tryAdoptRunning(File base) {
        final File f = runningUrlFile(base);
        if (!f.isFile()) return false;
        String url = null;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new java.io.FileInputStream(f), StandardCharsets.UTF_8))) {
            url = br.readLine();
        } catch (Throwable ignore) { }
        if (url == null || !url.startsWith("http://127.0.0.1:")) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return false;
        }
        final long t0 = System.currentTimeMillis();
        if (!probeDsh(url, 1200)) {
            appendReport("  · 上次的容器已不在（探测失败）→ 正常启动\n");
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return false;
        }
        sAdopted = true;
        sUrl = url;
        setState("运行中 · " + hostPortOf(url));
        updateNotification("已复用正在运行的容器（无需重启）");
        appendReport("  · ✅ 复用正在运行的容器（探测 " + (System.currentTimeMillis() - t0)
                + "ms，省掉一次冷启动）\n");
        Log.i(TAG, "复用已运行的容器: " + url);
        return true;
    }

    /** 把"这次跑起来的 URL"记下来，供下次启动接管（见 {@link #tryAdoptRunning}）。 */
    private static void saveRunningUrl(File base, String url) {
        try (java.io.OutputStream os = new java.io.FileOutputStream(runningUrlFile(base), false)) {
            os.write(url.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignore) { }
    }

    private static int portOfUrl(String url) {
        try {
            int c = url.indexOf(':', "http://127.0.0.1".length());
            if (c < 0) return -1;
            int e = url.indexOf('/', c);
            String p = e > 0 ? url.substring(c + 1, e) : url.substring(c + 1);
            return Integer.parseInt(p.trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 本地探活：对 {@code url}（含 token）发一个最简单的 GET，看状态码。
     *
     * <p>只认 200/302/303：DSH 的鉴权流程正是"带 token 的 GET → 303 换 Cookie"，
     * 所以这三者等价于"服务活着且 token 有效"。401 说明服务在但 token 过期 ——
     * 那种情况也当成"不可复用"（界面会登不进去）。
     */
    private static boolean probeDsh(String url, int timeoutMs) {
        final int port = portOfUrl(url);
        if (port <= 0) return false;
        java.net.Socket sock = new java.net.Socket();
        try {
            sock.connect(new java.net.InetSocketAddress("127.0.0.1", port), timeoutMs);
            sock.setSoTimeout(timeoutMs);
            int q = url.indexOf('?');
            String path = q >= 0 ? "/" + url.substring(q) : "/";
            String req = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + port + "\r\n"
                    + "Connection: close\r\n\r\n";
            sock.getOutputStream().write(req.getBytes(StandardCharsets.UTF_8));
            sock.getOutputStream().flush();
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
            String status = br.readLine();
            if (status == null) return false;
            return status.contains(" 200") || status.contains(" 302") || status.contains(" 303");
        } catch (Throwable t) {
            return false;
        } finally {
            try { sock.close(); } catch (Throwable ignore) { }
        }
    }

    /* ─────────────────── 启动诊断（回答"73 秒花在哪"） ─────────────────── */

    /**
     * 把 DSH 的状态目录体积写进日志（文件数 + MB）。
     *
     * <p>为什么要看这个：容器启动慢有两种完全不同的原因 ——
     * ① 机器本身慢（CPU/存储）；② **状态太大**（会话/归档/缓存有几万个文件），
     * dsh 启动时要遍历它们。后者用户在 App 里能处理（删历史对话），
     * 前者只能靠"别让它冷启动"。没有这几个数字，两种原因分不开。
     *
     * <p>走的是普通 Java 文件遍历（**不穿 proot**），并带文件数上限，
     * 避免这个诊断自己变成新的启动负担。
     */
    private void logDshStateSizes(File base) {
        try {
            File dsh = new File(base, "rootfs/root/.dsh");
            if (!dsh.isDirectory()) return;
            String[] names = {"sessions", "dsh-session-archive", "storages", "cache", "logs", "attachments"};
            StringBuilder sb = new StringBuilder();
            for (String n : names) {
                File d = new File(dsh, n);
                if (!d.isDirectory()) continue;
                long[] r = countFiles(d, 3000);
                if (r[0] <= 0) continue;
                sb.append("  · [状态] ").append(n).append(": ").append(r[0]).append(" 个文件 / ")
                        .append(r[1] >> 20).append(" MB\n");
            }
            if (sb.length() > 0) appendReport(sb.toString());
        } catch (Throwable ignore) { }
    }

    /** 递归统计 {文件数, 总字节}，最多数 {@code cap} 个文件；不跟符号链接。 */
    private static long[] countFiles(File dir, int cap) {
        long[] acc = {0, 0};
        java.util.ArrayDeque<File> stack = new java.util.ArrayDeque<>();
        stack.push(dir);
        while (!stack.isEmpty() && acc[0] < cap) {
            File cur = stack.pop();
            File[] kids = cur.listFiles();
            if (kids == null) continue;
            for (File k : kids) {
                if (acc[0] >= cap) break;
                if (Env.isSymlink(k)) continue;          // 不跟进链接（会话目录里可能有指向 /sdcard 的）
                if (k.isDirectory()) stack.push(k);
                else {
                    acc[0]++;
                    acc[1] += k.length();
                }
            }
        }
        return acc;
    }

    /**
     * 一次性测量"容器里最基础的一次 node 启动"要多久，按 App 版本缓存。
     *
     * <p>这一个数字就能把 73 秒分类：
     * <ul>
     *   <li>bash+node 冷启动 <b>1~3 秒</b> → 容器机制没问题，73 秒是 dsh 自己加载插件/状态；</li>
     *   <li>bash+node 就要 <b>10 秒以上</b> → 整台机器上的容器都慢（proot/存储/CPU），
     *       那 App 侧再怎么优化也只能省那 3 秒。</li>
     * </ul>
     *
     * <p>只在版本变化后测一次（结果进 SharedPreferences），
     * 所以长期看不增加任何启动成本。
     */
    /**
     * 用户主动点的「启动诊断」：布局、状态目录、容器基线，一次性跑完写进日志。
     *
     * <p>三项里**容器基线要起一次 proot + node**（真机几秒），所以只能按需触发，
     * 绝不能自动执行（那等于每次启动多背一次容器启动）。
     */
    void runDiagnosticsNow() {
        new Thread(() -> {
            try {
                android.os.Process.setThreadPriority(
                        android.os.Process.THREAD_PRIORITY_BACKGROUND);
                final File base = Env.base(this);
                appendReport("\n=== 启动诊断（用户触发）===\n");
                logDshStateSizes(base);
                measureLayoutOnceNow(base);
                long t = System.currentTimeMillis();
                Env.execInContainer(DshService.this, "node -e '0'", 90000L, null);
                appendReport("  · [诊断] 容器基线：bash + node 冷启动 "
                        + (System.currentTimeMillis() - t)
                        + "ms（>8000ms 说明容器本身很慢）\n");
                appendReport("=== 诊断结束（点「复制启动日志」可一次带走）===\n");
            } catch (Throwable e) {
                Log.w(TAG, "启动诊断失败", e);
            }
        }, "dsh-diag-manual").start();
    }

    /**
     * 把诊断**推到后台、并且推迟**，绝不和"页面加载"抢 CPU/IO。
     *
     * <h3>为什么要这样（2026-11-17 回归修复）</h3>
     * 0.4.16 的两个诊断（状态目录遍历、依赖布局遍历）都在"容器一就绪"就开跑，
     * 而那一刻正是 WebView 加载 DSH 前端（长对话要 7~8 秒）最吃 CPU/IO 的时候；
     * 用户实测：**改完反而慢了几秒**。诊断的价值远小于它抢占的这几秒。
     *
     * <p>现在：
     * <ul>
     *   <li>推迟 {@link #DIAG_DELAY_MS}（40 秒）再跑 —— 那时页面早已稳定；</li>
     *   <li>线程优先级降到 BACKGROUND（{@code Process.setThreadPriority}），
     *       即便真和什么撞上，也是让着别人的那一方；</li>
     *   <li>进程被杀就没了也不要紧 —— 诊断只是日志，不影响任何功能。</li>
     * </ul>
     */
    private void deferDiagnostics(final File base) {
        new Thread(() -> {
            try {
                android.os.Process.setThreadPriority(
                        android.os.Process.THREAD_PRIORITY_BACKGROUND);
                Thread.sleep(DIAG_DELAY_MS);
                logDshStateSizes(base);
                measureLayoutOnceNow(base);
            } catch (InterruptedException ie) {
                // 无需处理：被打断就说明进程要走了
            } catch (Throwable t) {
                Log.w(TAG, "延迟诊断失败（不影响任何功能）", t);
            }
        }, "dsh-diagnostics").start();
    }

    /** 诊断整体推迟多久（毫秒）。 */
    private static final long DIAG_DELAY_MS = 40000L;

    /**
     * 依赖布局诊断：数一数 profile 与全局安装里的「文件数 / 符号链接数」。
     *
     * <p>为什么值得单独测（用户 2026-11-17 的真机 profile）：dsh 冷启动 59 秒里
     * 86% 是路径类系统调用，其中 lstat + realpath 约 11 秒**专门来自符号链接**。
     * 所以"有没有链接、有多少"直接决定两件事：
     * 能不能开 {@code --preserve-symlinks}（零链接才行），
     * 以及扁平化（hoisted）值不值得做。
     *
     * <p>走 App 自己的文件 API（**不穿 proot**），按版本只测一次。
     */
    private void measureLayoutOnceNow(final File base) {
        try {
            final int vc = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
            if (getSharedPreferences(PREFS, MODE_PRIVATE).getInt("layout_vc", -1) == vc) return;
            {
                try {
                    long[] hd = Env.layoutOf(new File(base, "rootfs/root/.dsh/profiles/web/node_modules"), 6000);
                    long[] gl = Env.layoutOf(new File(base, "rootfs/opt/" + Env.NODE_DIR + "/lib/node_modules"), 6000);
                    long links = hd[1] + gl[1];
                    appendReport("  · [布局] 依赖文件 " + (hd[0] + gl[0]) + " 个 · 符号链接 " + links
                            + " 个 · 合计 " + ((hd[2] + gl[2]) >> 20) + " MB"
                            + "（profile " + hd[0] + "/" + hd[1] + "，全局 " + gl[0] + "/" + gl[1] + "）\n");
                    if (links == 0) {
                        appendReport("  · [布局] 无符号链接 → 下次启动启用 --preserve-symlinks"
                                + "（省掉每个模块的 realpath）\n");
                    } else {
                        appendReport("  · [布局] 有 " + links + " 个符号链接 → 暂不启用 --preserve-symlinks"
                                + "（会让同一个包解析成两份）；扁平化依赖预计可省 5~10 秒\n");
                    }
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putInt("layout_vc", vc)
                            .putLong("layout_links", links)
                            .putBoolean("layout_preserve", links == 0L)
                            .apply();
                } catch (Throwable t) {
                    Log.w(TAG, "依赖布局测量失败", t);
                }
            }
        } catch (Throwable ignore) { }
    }

    /** 本次版本测出来的布局是否"零符号链接"（决定要不要加 --preserve-symlinks）。 */
    private boolean preserveSymlinksPreferred() {
        try {
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            int vc = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
            return sp.getInt("layout_vc", -1) == vc && sp.getBoolean("layout_preserve", false);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 容器基线：在容器里跑一次 {@code node -e '0'} 并用它的耗时判断"这台机器的容器有多慢"。
     *
     * <p><b>只在用户主动点「🧪 启动诊断」时跑</b>（2026-11-17 改）。
     * 它会**起一个 proot + node**（真机上几秒 CPU），绝不能自动执行 ——
     * 那就等于每次启动都多背一次容器启动。
     */
    void probeContainerBaselineNow(final File base, final String libDir) {
        new Thread(() -> {
            try {
                android.os.Process.setThreadPriority(
                        android.os.Process.THREAD_PRIORITY_BACKGROUND);
                long t = System.currentTimeMillis();
                Env.execInContainer(DshService.this, "node -e '0'", 90000L, null);
                appendReport("  · [诊断] 容器基线：bash + node 冷启动 "
                        + (System.currentTimeMillis() - t)
                        + "ms（>8000ms 说明容器本身很慢）\n");
            } catch (Throwable e) {
                Log.w(TAG, "容器基线测量失败", e);
            }
        }, "container-baseline").start();
    }

    /** 把一行日志裁短，用于把"静默发生在哪一行之后"写清楚。 */
    private static String clip(String s, int max) {
        if (s == null) return "(无)";
        String t = s.replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    private void setState(String s) { sState = s; }

    // ── 通知 ──────────────────────────────────────────────────
    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CH_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CH_ID, "DSH 运行状态", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("保持 DSH 容器在后台运行");
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
    }

    /**
     * 通知右侧的大图 = 当前配色的桌面图标。
     *
     * <p>用 {@link android.graphics.drawable.Icon} 而不是 BitmapFactory：
     * v26+ 的桌面图标是**自适应图标 XML**，BitmapFactory 解不出来（返回 null），
     * 用 Icon 才能正确渲染。
     */
    private android.graphics.drawable.Icon largeIcon() {
        try {
            int res = IconSwitcher.iconRes(this);
            if (res == 0) return null;
            return android.graphics.drawable.Icon.createWithResource(this, res);
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification(String text) {
        /*
         * 点通知栏「正在运行」要回到**原来那个** MainActivity，不能开新的。
         *
         * 2026-10-15（GitHub issue #3）：原来这里只给了 MainActivity.class、没带任何
         * ACTIVITY 标志，配上 manifest 里也没有 launchMode → 每次点通知都新建一个实例，
         * 新实例的 WebView 全新加载、显示空白会话页，用户以为会话被清空。
         *
         * 现在：manifest 已改成 singleTask（保证只有一份）；这里再补
         * CLEAR_TOP + SINGLE_TOP 双保险 —— 即使有人把 manifest 改回去，
         * 点通知也只会把已有实例提前，不会叠出第二个。
         */
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        /*
         * 通知上的快捷动作（用户要求"在其他软件时也能用 DSH"）：
         * 常驻通知是 App 在后台时**唯一**一直可见的入口，
         * 把「悬浮球」放这里，就不用先切回 App 再点。
         *
         * 用广播而不是 getService()：悬浮球现在**不是 Service 了**（改成由这个
         * 已经在跑的前台服务直接持有窗口 —— 见 BallWindow 类注释里那段
         * "在后台 startForegroundService 会被系统拒"的踩坑记录）。
         */
        PendingIntent overlay = PendingIntent.getBroadcast(this, 2,
                new Intent(this, DshService.class).setAction(ACTION_BALL_TOGGLE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CH_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("Tdsh")
                .setContentText(text)
                /*
                 * 通知图标跟随用户在「桌面图标」里选的那套配色：
                 *   setSmallIcon 用小图标（系统会按强调色着色）
                 *   setColor     决定强调色 —— 小图标和应用名都会被它染色
                 *   setLargeIcon 右侧那张大图，直接用该配色的桌面图标
                 */
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setColor(IconSwitcher.accentColor(this))
                /*
                 * 大图 = 当前配色的桌面图标。
                 * 光靠 setColor 只能给小图标和标题染色，用户看不到"图"本身变了；
                 * 加上 largeIcon 之后，通知右侧那张方图就是桌面图标那张 ——
                 * 这才叫"通知栏里的图片也跟着换"。
                 */
                .setLargeIcon(largeIcon())
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_view, "悬浮窗", overlay).build())
                .setOngoing(true)
                .build();
    }

    /**
     * 用户把 App 从最近任务里划掉时**不跟着死**（用户要求"后台更稳定"）。
     *
     * <p>默认行为是：任务被移除 → 服务被杀。对 DSH 这种"后台常驻跑容器"的定位
     * 明显不合适 —— 用户划掉卡片只是"不想看界面"，不是"把容器关掉"。
     * 这里配合清单里的 {@code android:stopWithTask="false"}，
     * 让进程与容器继续活着，回到 App 时界面直接接上。
     *
     * <p>注意：这**不是**黑科技保活。用户在系统设置里"强行停止"之后，
     * 任何合规手段都无法自动复活 —— 那种需求本项目不做
     * （1px Activity / 静音音频 / 双进程互拉 一类都是要被系统打死的）。
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.i(TAG, "任务被移除，但服务继续运行（容器不受影响）");
        appendReport("  · 用户把 App 从最近任务划掉 —— 容器继续在后台运行\n");
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
        } catch (Throwable t) {
            Log.w(TAG, "updateNotification failed", t);
        }
    }
}
