package com.dsh.mobile;

import android.os.Handler;
import android.os.Looper;

/**
 * 一键启动的进度模型。
 *
 * <p>启动分为若干个已知阶段，但「apt install」「npm install」这类阶段的耗时完全不可预知。
 * 因此采用「阶段权重 + 阶段内渐进逼近上限」的策略：
 *
 * <ul>
 *   <li>每个阶段有起点 {@code base} 与上限 {@code cap}；</li>
 *   <li>进入阶段时进度立即跳到 {@code base}；</li>
 *   <li>阶段进行中按 {@code 1 - e^(-t/tau)} 向 {@code cap} 逼近，永远差一点点；</li>
 *   <li>进入下一阶段时再跳到下一段的 {@code base}，形成"完成一段跳一下"的观感。</li>
 * </ul>
 *
 * <p>这样既不会假装知道剩余时间，也不会让进度条长时间停在一个数字上。
 */
public final class DshProgress {

    /** 进度回调：0..100，-1 表示不确定进度（如首次解压环境）。 */
    public interface Sink {
        void onProgress(int percent);
    }

    private static final String[] STAGES = {
            "check", "bench", "mirror", "apt-update", "install-node", "install-dsh", "start"
    };
    private static final int[] BASE = {0, 5, 12, 15, 32, 62, 86};
    private static final int[] CAP = {5, 12, 15, 32, 62, 86, 95};
    /** 各阶段的"时间常数"：越大表示这一阶段预期越久，爬升越慢。 */
    private static final long[] TAU_MS = {1000L, 3000L, 1000L, 25000L, 90000L, 45000L, 12000L};

    private static final long TICK_MS = 300L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Sink sink;

    private int stageIndex = -1;
    private long stageStart;
    private int current;
    private boolean ticking;

    public DshProgress(Sink sink) {
        this.sink = sink;
    }

    /** 进入某个阶段；未知阶段码忽略（如 error:xxx、stopped）。 */
    public void setStage(String code) {
        int idx = indexOf(code);
        if (idx < 0 || idx == stageIndex) {
            return;
        }
        stageIndex = idx;
        stageStart = System.currentTimeMillis();
        // 只增不减，避免重启流程时回退
        current = Math.max(current, BASE[idx]);
        sink.onProgress(current);
        startTicking();
    }

    /** 完成：100%。 */
    public void finish() {
        stopTicking();
        stageIndex = -1;
        current = 100;
        sink.onProgress(100);
    }

    /** 复位到 0%（准备重新启动）。 */
    public void reset() {
        stopTicking();
        stageIndex = -1;
        current = 0;
        sink.onProgress(0);
    }

    /** 不确定进度（首次解压 bootstrap 等）。 */
    public void indeterminate() {
        stopTicking();
        stageIndex = -1;
        sink.onProgress(-1);
    }

    public void release() {
        stopTicking();
    }

    // ---------------------------------------------------------------- 内部

    private int indexOf(String code) {
        if (code == null) {
            return -1;
        }
        for (int i = 0; i < STAGES.length; i++) {
            if (STAGES[i].equals(code)) {
                return i;
            }
        }
        return -1;
    }

    private void startTicking() {
        if (ticking) {
            return;
        }
        ticking = true;
        handler.postDelayed(tick, TICK_MS);
    }

    private void stopTicking() {
        ticking = false;
        handler.removeCallbacksAndMessages(null);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!ticking || stageIndex < 0) {
                return;
            }
            long elapsed = System.currentTimeMillis() - stageStart;
            int base = BASE[stageIndex];
            int cap = CAP[stageIndex];
            long tau = TAU_MS[stageIndex];

            double ratio = 1.0 - Math.exp(-(double) elapsed / (double) tau);
            int value = base + (int) Math.floor((cap - base) * ratio);
            // 永远不越过本阶段上限，留一格给下一阶段
            value = Math.min(value, cap - 1);
            if (value > current) {
                current = value;
                sink.onProgress(current);
            }
            handler.postDelayed(this, TICK_MS);
        }
    };
}
