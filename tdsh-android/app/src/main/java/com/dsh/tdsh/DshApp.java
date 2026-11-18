package com.dsh.tdsh;

import android.app.Application;
import android.content.Intent;
import android.os.Build;

import java.io.File;

/**
 * 本 App 的 Application —— 接上"自己是否在前台"，并且在冷启动时**尽早**把容器拉起来。
 *
 * <h3>为什么需要它</h3>
 * 悬浮球要"退到后台有任务才出现、回到我们自己的界面就消失"，
 * 而判断"是不是我们自己在前面"靠 {@link Application.ActivityLifecycleCallbacks}
 * 最稳（零权限、零跨进程调用，且只看自己）。
 * 那套回调必须从 Application 上注册，所以需要这个类。
 *
 * <p>这个类在冷启动路径的最前面，所以只放两件**必须赶早**的事：
 * 主题（建 View 前必须定好）和"把容器提前拉起来"（见 {@link #startContainerEarly()}）。
 * 其它逻辑一律不要往这里加 —— 这里多一行，冷启动就多一行的风险。
 */
public class DshApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // 跟随 DSH 主题：必须在建任何 View 之前设定，
        // 否则会拿到上一个页面留下的颜色。
        Ui.applyTheme(this);
        Ui.applySystemBars(this);
        Foreground.install(this);
        startContainerEarly();
    }

    /**
     * 冷启动：**在界面还没搭起来的时候**就把容器拉起来（2026-11-16）。
     *
     * <h3>为什么挪到 Application</h3>
     * 原来容器是在 {@code MainActivity.onCreate} 结尾、由初始化线程
     * {@code startWebAndWait() → startDshService()} 才拉起来的 ——
     * 也就是说用户得先等我们把整套 View 建完（真机 100~300ms），
     * 容器才开始 boot。而容器 boot 是本 App 启动耗时的主体（8~9 秒），
     * 让它早开始 100~300ms，用户就早 100~300ms 看到界面。
     *
     * <p>这一条只改"什么时候开始"，不改"怎么开始"：
     * 服务本身幂等（{@code isAlive()} 门槛），Activity 后面照旧调用
     * {@code startDshService()}，不会起第二个容器。
     *
     * <h3>两条边界</h3>
     * <ul>
     *   <li><b>容器没装好就什么都不做</b> —— 首次安装仍由 MainActivity 的初始化流程负责，
     *       不能在这里提前把服务拉起来发一条"请打开 App 完成初始化"的通知；</li>
     *   <li>任何异常都吞掉：这里在冷启动最前面，抛出去就是启动崩溃。</li>
     * </ul>
     */
    private void startContainerEarly() {
        try {
            File base = Env.base(this);
            if (!Env.isInstalled(base)) return;      // 首次安装/容器没装好：交给 Activity 的初始化流程
            Intent it = new Intent(this, DshService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(it);
            } else {
                startService(it);
            }
        } catch (Throwable t) {
            android.util.Log.w("DSH_APP", "提前启动容器失败（不影响正常启动）", t);
        }
    }
}
