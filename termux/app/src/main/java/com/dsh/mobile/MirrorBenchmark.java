package com.dsh.mobile;

import android.os.Handler;
import android.os.Looper;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 镜像源测速。
 *
 * <p>并行请求每个镜像的 {@code dists/stable/Release}（apt 仓库里最小的元数据文件，
 * 十几 KB），以「建立连接 + 首字节 + 完整读完」的耗时作为排名依据。耗时最短者胜出。
 *
 * <p>这个文件足够小，不会在蜂窝网络下产生明显流量；又能覆盖 DNS、TLS、RTT 三个主要
 * 影响因素，对后续 apt/npm 下载的代表性足够。
 */
public final class MirrorBenchmark {

    /** 用于测速的仓库相对路径。 */
    private static final String PROBE_PATH = "/dists/stable/Release";

    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 4000;
    private static final long OVERALL_TIMEOUT_MS = 8000L;

    public static final class Result {
        public final String name;
        public final String url;
        public final long millis;

        Result(String name, String url, long millis) {
            this.name = name;
            this.url = url;
            this.millis = millis;
        }
    }

    public interface Callback {
        /**
         * @param best 最快的可用镜像；全部失败时为 null
         * @param all  所有成功的结果，按耗时升序
         */
        void onResult(Result best, List<Result> all);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** 保证回调只触发一次（正常完成与超时兜底之间竞争）。 */
    private static final AtomicBoolean PUBLISHED = new AtomicBoolean(false);

    private MirrorBenchmark() {
    }

    public static void run(final DshMirrors.Mirror[] mirrors, final Callback callback) {
        if (mirrors == null || mirrors.length == 0) {
            MAIN.post(() -> callback.onResult(null, Collections.emptyList()));
            return;
        }

        PUBLISHED.set(false);
        final ExecutorService pool = Executors.newFixedThreadPool(mirrors.length);
        final Result[] slots = new Result[mirrors.length];
        final AtomicInteger remaining = new AtomicInteger(mirrors.length);
        final CountDownLatch latch = new CountDownLatch(mirrors.length);

        for (int i = 0; i < mirrors.length; i++) {
            final int index = i;
            final DshMirrors.Mirror mirror = mirrors[i];
            pool.execute(() -> {
                try {
                    long ms = probe(mirror.url + PROBE_PATH);
                    slots[index] = ms >= 0 ? new Result(mirror.name, mirror.url, ms) : null;
                } catch (Throwable ignored) {
                    slots[index] = null;
                } finally {
                    latch.countDown();
                    if (remaining.decrementAndGet() == 0) {
                        publish(slots, callback);
                    }
                }
            });
        }

        // 兜底：任何线程卡住都不能让启动流程无限等待
        MAIN.postDelayed(() -> {
            if (latch.getCount() > 0) {
                publish(slots, callback);
            }
        }, OVERALL_TIMEOUT_MS);

        pool.shutdown();
    }

    private static void publish(Result[] slots, Callback callback) {
        if (!PUBLISHED.compareAndSet(false, true)) {
            return;
        }
        List<Result> ok = new ArrayList<>();
        for (Result r : slots) {
            if (r != null) {
                ok.add(r);
            }
        }
        ok.sort(Comparator.comparingLong(r -> r.millis));
        Result best = ok.isEmpty() ? null : ok.get(0);
        MAIN.post(() -> callback.onResult(best, ok));
    }

    /** 返回耗时毫秒；失败返回 -1。 */
    private static long probe(String url) {
        HttpURLConnection conn = null;
        try {
            long start = System.currentTimeMillis();
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Connection", "close");

            if (conn.getResponseCode() != 200) {
                return -1;
            }
            // 真正读完，避免只测到 TTFB
            byte[] buffer = new byte[8192];
            try (InputStream in = conn.getInputStream()) {
                while (in.read(buffer) != -1) {
                    // drain
                }
            }
            return System.currentTimeMillis() - start;
        } catch (Exception e) {
            return -1;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
