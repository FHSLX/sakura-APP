package com.sakura.remote;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 独立的截屏授权入口 Activity。
 *
 * 只负责「隐藏悬浮窗 → 确认窗口真的移除且自己已就绪 → 申请系统授权 →
 * 把 grant 交给 CaptureCoordinator → 立刻让位」。
 *
 * 本轮（RV-01 / RV-08 / RV-09）修正：
 *   - manifest 去掉 {@code noHistory=true}：该属性会让发起 startActivityForResult
 *     的 Activity 收不到 onActivityResult（Android 官方文档明确说明）；
 *   - 旋转重建（配置变化）不再取消在途请求，也不重复弹授权框；
 *     请求 id 与「是否已请求授权」写入 savedInstanceState；
 *   - 等待悬浮窗移除有上限，超时明确终结，不再「超时了也去申请授权」；
 *   - 申请授权前要求自己已 RESUMED 且有窗口焦点；
 *   - 授权隐藏以 requestId 为 token 持有，Service 建立 captureHidden 后才释放
 *     同一 token，避免两段隐藏之间出现空窗（RV-09）。
 *
 * 第三轮（INT-03）修正：
 *   - resumed 只由 onResume/onPause 维护，不再拿 {@code !isFinishing && !isDestroyed}
 *     冒充 resumed；
 *   - 焦点只认真实 {@code hasWindowFocus()}，删除「等待次数 ≥ 6 就当有焦点」的
 *     伪判断；onWindowFocusChanged 一到就立刻复核；
 *   - onPause 取消等待，onResume 在**总超时期限**内继续（期限随 savedInstanceState
 *     跨旋转保留，不能被 pause/resume 无限续命）；
 *   - 未满足「悬浮窗已移除 + 真实 resumed + 真实焦点」时启动次数严格为零，
 *     满足后恰好一次。
 */
public class CapturePermissionActivity extends Activity {

    public static final String EXTRA_REQUEST_ID = "capture_request_id";

    private static final int REQUEST_CAPTURE = 0x5344;
    /** 让位 + 焦点等待的总超时：超时明确失败，绝不后台强行申请授权。 */
    private static final long WAIT_TIMEOUT_MS = 3000L;
    private static final long WAIT_STEP_MS = 50L;

    private static final String STATE_REQUEST_ID = "sakura_capture_request_id";
    private static final String STATE_CONSENT_REQUESTED = "sakura_capture_consent_requested";
    private static final String STATE_WAIT_STARTED_AT = "sakura_capture_wait_started_at";

    /** 当前是否有授权页可见（供 CaptureService 等首帧让位，RV-09）。 */
    private static final AtomicInteger visibleCount = new AtomicInteger(0);

    public static boolean isAnyVisible() {
        return visibleCount.get() > 0;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String requestId = "";
    private boolean resultHandled;
    private boolean consentRequested;
    private boolean startedTracked;
    private boolean waitScheduled;
    /** 真实生命周期：只有 onResume 之后、onPause 之前才是 true（INT-03）。 */
    private boolean resumed;
    /**
     * 等待窗口的起点（elapsedRealtime）。
     *
     * 跨 onPause/onResume 累计，所以「反复暂停再恢复」不能无限拖长等待；
     * 旋转重建时经 savedInstanceState 传递，期限不会被重置续命。
     */
    private long waitStartedAt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        if (savedInstanceState != null) {
            requestId = savedInstanceState.getString(STATE_REQUEST_ID, "");
            consentRequested = savedInstanceState.getBoolean(STATE_CONSENT_REQUESTED, false);
            waitStartedAt = savedInstanceState.getLong(STATE_WAIT_STARTED_AT, 0L);
        }
        if ((requestId == null || requestId.isEmpty()) && intent != null) {
            requestId = intent.getStringExtra(EXTRA_REQUEST_ID);
        }
        if (requestId == null) {
            requestId = "";
        }
        if (waitStartedAt <= 0L) {
            waitStartedAt = android.os.SystemClock.elapsedRealtime();
        }
        boolean stillActive = CaptureCoordinator.get().isActive(requestId);
        if (CaptureConsentPolicy.onCreate(stillActive)
                == CaptureConsentPolicy.CreateDecision.FINISH_STALE) {
            // 陈旧/迟到的入口：不申请授权，也不去动隐藏理由
            finish();
            return;
        }
        // 先让悬浮窗让位：MIUI 会在「本应用正在显示系统悬浮窗」时把系统确认框压掉。
        OverlayService.acquirePermissionHidden(requestId);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!startedTracked) {
            startedTracked = true;
            visibleCount.incrementAndGet();
        }
    }

    @Override
    protected void onStop() {
        if (startedTracked) {
            startedTracked = false;
            visibleCount.decrementAndGet();
        }
        super.onStop();
    }

    @Override
    public void onResume() {
        super.onResume();
        resumed = true;
        if (resultHandled) {
            return;
        }
        if (!CaptureCoordinator.get().isActive(requestId)) {
            // 请求已被超时/取消终结：不要继续挂着
            finish();
            return;
        }
        if (consentRequested) {
            // 旋转重建：系统授权框还在，结果会投递到本实例，只等待即可
            return;
        }
        // 恢复后立即用真实状态复核一次；不满足时轮询会继续，
        // 而 onWindowFocusChanged(true) 也会立刻再复核一次
        postFocusCheck();
    }

    @Override
    public void onPause() {
        resumed = false;
        // 真实暂停：停掉轮询，避免在后台「等够次数」就当有焦点；期限继续累计
        handler.removeCallbacks(waitRunnable);
        waitScheduled = false;
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // 焦点真正到达：立刻用真实状态复核，满足就恰好申请一次
            postFocusCheck();
        }
    }

    /**
     * 立刻安排一次真实状态复核。
     *
     * 用 post 而不是直接调用：onResume 刚回来时 hasWindowFocus() 可能还没更新，
     * 放到当前消息循环末尾读到的才是真实值；随后 onWindowFocusChanged(true)
     * 还会再复核一次，满足前提时恰好申请一次系统授权。
     */
    private void postFocusCheck() {
        if (resultHandled || consentRequested || !resumed) {
            return;
        }
        handler.removeCallbacks(waitRunnable);
        waitScheduled = true;
        handler.post(waitRunnable);
    }

    private void runWaitCheck() {
        waitScheduled = false;
        if (resultHandled || consentRequested || !resumed || isFinishing() || isDestroyed()) {
            return;
        }
        long elapsed = android.os.SystemClock.elapsedRealtime() - waitStartedAt;
        CaptureConsentPolicy.WaitAction action = CaptureConsentPolicy.nextWaitAction(
                OverlayService.isOverlayWindowAttached(),
                resumed && !isFinishing() && !isDestroyed(),
                hasWindowFocus(),
                elapsed,
                WAIT_TIMEOUT_MS);
        if (action == CaptureConsentPolicy.WaitAction.REQUEST_CONSENT) {
            requestConsent();
            return;
        }
        if (action == CaptureConsentPolicy.WaitAction.FAIL_NO_YIELD) {
            failAndFinish("悬浮窗未能让位或界面未获得焦点，暂时无法申请截图授权");
            return;
        }
        waitScheduled = true;
        handler.postDelayed(waitRunnable, WAIT_STEP_MS);
    }

    private final Runnable waitRunnable = new Runnable() {
        @Override
        public void run() {
            runWaitCheck();
        }
    };

    private void requestConsent() {
        if (!CaptureConsentPolicy.shouldRequestConsent(consentRequested)) {
            return;   // 授权弹窗只开一次
        }
        // 再校验一次真实前提：任何一条不成立都不申请（绝不后台强行授权）
        if (!resumed || !hasWindowFocus() || OverlayService.isOverlayWindowAttached()) {
            return;
        }
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            failAndFinish("系统不支持屏幕截图");
            return;
        }
        consentRequested = true;
        waitScheduled = false;
        handler.removeCallbacks(waitRunnable);
        try {
            /*
             * Android 14（API 34）起默认的 createScreenCaptureIntent() 可能只共享
             * 「单个应用」，截不到整屏桌面。用公开的
             * MediaProjectionConfig.createConfigForDefaultDisplay() 明确请求默认整屏；
             * 旧系统保持原路径。仍然完整展示系统授权框，不绕过用户选择/ROM 限制。
             */
            Intent consent;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                consent = manager.createScreenCaptureIntent(
                        android.media.projection.MediaProjectionConfig
                                .createConfigForDefaultDisplay());
            } else {
                consent = manager.createScreenCaptureIntent();
            }
            startActivityForResult(consent, REQUEST_CAPTURE);
        } catch (Exception error) {
            consentRequested = false;
            failAndFinish("打不开截图授权页：" + error.getClass().getSimpleName());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAPTURE) {
            return;
        }
        resultHandled = true;
        if (resultCode == RESULT_OK && data != null) {
            // 交棒给协调器：由它启动合法的 mediaProjection 前台服务。
            // 授权隐藏**不在这里释放**：Service 建立 captureHidden 后再释放同一 token。
            // 先启动前台服务（此时本页仍在前台，不受后台启动限制），
            // 再对 App 发起的「桌面截屏」把整个任务收到后台 —— 否则授权页 finish 后
            // MainActivity 又回到前台，首帧会把 App 自己拍进去。
            CaptureCoordinator.get().onConsentGranted(requestId, resultCode, data, this);
            CaptureRequestRegistry.Request active = CaptureCoordinator.get().activeRequest();
            if (active != null && AppCaptureYield.shouldYield(active.origin)) {
                AppCaptureYield.markYielded(requestId, this, getTaskId());
                moveTaskToBack(true);
            }
        } else {
            CaptureCoordinator.get().onConsentDenied(requestId, "你取消了截图授权");
        }
        // 立即让位，避免自己出现在截屏画面里
        finish();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_REQUEST_ID, requestId);
        outState.putBoolean(STATE_CONSENT_REQUESTED, consentRequested);
        // 跨旋转保留等待起点：期限不能被重建「续命」（INT-03）
        outState.putLong(STATE_WAIT_STARTED_AT, waitStartedAt);
    }

    private void failAndFinish(String message) {
        resultHandled = true;
        CaptureCoordinator.get().cancel(requestId, message);
        finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        CaptureConsentPolicy.DestroyDecision decision = CaptureConsentPolicy.onDestroy(
                resultHandled, isChangingConfigurations());
        if (decision == CaptureConsentPolicy.DestroyDecision.CANCEL_REQUEST) {
            // 用户返回 / 被系统结束：请求不能悬着（超时也会兜底）
            CaptureCoordinator.get().cancel(requestId, "授权页已关闭");
        }
        // KEEP_REQUEST：旋转重建，隐藏理由与请求都保留，新实例继续等待授权结果
        super.onDestroy();
    }
}
