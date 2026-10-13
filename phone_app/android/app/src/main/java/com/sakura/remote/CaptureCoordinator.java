package com.sakura.remote;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 截图请求与结果路由的唯一协调器。
 *
 * 现在：
 *   - 每个请求有 requestId、origin（APP/OVERLAY）、pageSessionId、状态、开始时间；
 *   - 同时只允许一个活动请求，忙时明确返回 busy，不排队、不覆盖；
 *   - target 用 WeakReference 持有，不把 Activity/WebView 静态强引用当路由表；
 *   - 结果只交给**发起该请求且 page session 仍登记有效**的页面；
 *   - 授权阶段 / 启动服务阶段都有有界超时（RV-08），不会永久 busy；
 *   - 终结时释放属于本 requestId 的隐藏理由（RV-09），并主动停掉截图服务（RV-07）；
 *   - 终结（成功/失败/取消/系统停止）恰好一次，且有界保留一小段记录供页面消费。
 *
 * 页面会话登记在 {@link PageSessionRegistry}，纯状态机在
 * {@link CaptureRequestRegistry}，均可用普通 JVM 做行为测试；
 * 本类只负责 Android 侧的目标持有、超时调度、日志与启动/停止服务。
 *
 * 状态机：IDLE → CONSENT → STARTING_SERVICE → WAITING_FRAME
 *        → COMPLETED / CANCELLED / FAILED
 */
public final class CaptureCoordinator {

    private static final String TAG = "SakuraCapture";

    /** 授权阶段超时：系统弹窗被压掉 / silent BAL 时不能永久占着 registry。 */
    private static final long CONSENT_TIMEOUT_MS = 90_000L;
    /** 启动截图服务阶段超时：Service 没能及时报到就明确失败。 */
    private static final long SERVICE_START_TIMEOUT_MS = 20_000L;

    public enum Origin {
        APP,
        OVERLAY
    }

    /** 发起端回调。实现方通常是 MainActivity 或 OverlayService。 */
    public interface Target {
        /**
         * @param dataUrl 成功时为 data:image/jpeg;base64,... ；失败为空串
         * @param error   失败原因；成功为空串
         */
        void onCaptureResult(String requestId, String dataUrl, String error);
    }

    private static final CaptureCoordinator INSTANCE = new CaptureCoordinator();

    public static CaptureCoordinator get() {
        return INSTANCE;
    }

    private final CaptureRequestRegistry registry = new CaptureRequestRegistry();
    private final PageSessionRegistry sessions = new PageSessionRegistry();
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** requestId → 弱引用目标；终结后移除，不长期持有 Activity/WebView。 */
    private final ConcurrentHashMap<String, WeakReference<Target>> targets =
            new ConcurrentHashMap<>();

    private Runnable consentTimeout;
    private Runnable startTimeout;

    private CaptureCoordinator() {
    }

    // ---- 生命周期 --------------------------------------------------

    /**
     * 开始一次请求。
     *
     * @return 新请求；已有活动请求时返回 null（调用方应给出「忙」的可见反馈）。
     */
    public CaptureRequestRegistry.Request begin(Origin origin, String pageSessionId,
                                                Target target) {
        CaptureRequestRegistry.Request request = registry.begin(
                origin == null ? "UNKNOWN" : origin.name(),
                pageSessionId, System.currentTimeMillis());
        if (request == null) {
            return null;
        }
        targets.put(request.requestId, new WeakReference<>(target));
        scheduleConsentTimeout(request.requestId);
        log("begin " + request.requestId + " origin=" + request.origin
                + " page=" + request.pageSessionId);
        return request;
    }

    public boolean isBusy() {
        return registry.isBusy();
    }

    public CaptureRequestRegistry.Request activeRequest() {
        return registry.active();
    }

    public boolean isActive(String requestId) {
        return registry.isActive(requestId);
    }

    /** 共享的页面会话登记表（RV-02）。 */
    public PageSessionRegistry sessions() {
        return sessions;
    }

    /** 页面 boot / 导航完成时登记真实会话。 */
    public boolean registerPageSession(String pageId, String sessionId) {
        return sessions.register(pageId, sessionId);
    }

    /** 授权被用户拒绝。 */
    public void onConsentDenied(String requestId, String reason) {
        String message = reason == null ? "你取消了截图授权" : reason;
        if (!finishActive(requestId, CaptureRequestRegistry.STATE_CANCELLED, message, "")) {
            // 已被其它路径终结：仍然按 token 释放，确保不泄漏隐藏理由
            releaseHides(requestId);
        }
    }

    /**
     * 授权成功：启动截图前台服务。
     *
     * 注意：这里**不释放**授权隐藏理由（RV-09）。授权页 finish 到 Service 真正
     * 建立 captureHidden 之间必须始终有一个隐藏理由成立，否则悬浮窗会在两段
     * 隐藏之间闪回，被首帧拍进画面。授权隐藏由 {@link CapturePermissionActivity}
     * 以 requestId 为 token 持有，Service 拿到投影并建立 captureHidden 后再释放
     * 同一个 token。
     */
    public void onConsentGranted(String requestId, int resultCode, Intent data, Context context) {
        if (!registry.isActive(requestId)) {
            log("consent-granted-stale " + requestId);
            // 陈旧授权：释放属于它的隐藏 token，避免旧流程把悬浮窗永久藏起来
            releaseHides(requestId);
            return;
        }
        if (data == null) {
            onConsentDenied(requestId, "系统没有返回截图授权数据");
            return;
        }
        registry.setState(requestId, CaptureRequestRegistry.STATE_STARTING_SERVICE);
        cancelConsentTimeout();
        scheduleStartTimeout(requestId);
        try {
            CaptureService.start(context, requestId, resultCode, data);
        } catch (Exception error) {
            log("service-start-failed " + requestId + " " + error.getClass().getSimpleName());
            finishActive(requestId, CaptureRequestRegistry.STATE_FAILED,
                    "启动截图服务失败：" + error.getClass().getSimpleName(), "");
        }
    }

    /** 服务已经拿到 projection，开始等首帧：启动超时交给服务自己的帧超时兜底。 */
    public void onServiceWaitingFrame(String requestId) {
        cancelStartTimeout();
        registry.setState(requestId, CaptureRequestRegistry.STATE_WAITING_FRAME);
    }

    /**
     * CaptureService 的结果终结回调（成功/失败/超时/系统停止）。
     *
     * 恰好一次：registry.finish 只认匹配且未终结的请求，迟到的第二次会被忽略。
     */
    public void onServiceResult(String requestId, String dataUrl, String error) {
        boolean success = error == null || error.isEmpty();
        String state = success
                ? CaptureRequestRegistry.STATE_COMPLETED
                : CaptureRequestRegistry.STATE_FAILED;
        if (!finishActive(requestId, state, error, dataUrl)) {
            log("late-result-ignored " + requestId);
        }
    }

    /** 主动取消：终结请求并停掉可能已在运行的截图服务与资源（RV-07）。 */
    public void cancel(String requestId, String reason) {
        String message = reason == null ? "已取消" : reason;
        if (!finishActive(requestId, CaptureRequestRegistry.STATE_CANCELLED, message, "")) {
            // 已被其它路径终结：仍然按 token 释放，确保不泄漏隐藏理由
            releaseHides(requestId);
        }
        CaptureService.abort(requestId, message);
    }

    /**
     * 页面会话失效（WebView reload / 页面销毁）：在途请求不再送到旧页面。
     *
     * @param pageId  "app" / "overlay"
     */
    public void invalidatePageSession(String pageId, String sessionId) {
        sessions.invalidate(pageId, sessionId);
        CaptureRequestRegistry.Request cancelled = registry.cancelPageSession(sessionId);
        if (cancelled == null) {
            return;
        }
        log("cancel-page-session " + cancelled.requestId);
        cancelTimers();
        releaseHides(cancelled.requestId);
        CaptureService.abort(cancelled.requestId, "页面已刷新或关闭");
        Target target = takeTarget(cancelled.requestId);
        notifyTarget(target, cancelled.requestId, "", "页面已刷新或关闭");
        AppCaptureYield.restore(cancelled.requestId);
    }

    /** 最近一次结果（只在 TTL 内有效，且只含状态/错误码，不含图片数据）。 */
    public CaptureRequestRegistry.Result lastResult() {
        return registry.lastResult();
    }

    /** 供界面/报告显示的状态 JSON。 */
    public String statusJson() {
        CaptureRequestRegistry.Request active = registry.active();
        CaptureRequestRegistry.Result last = registry.lastResult();
        StringBuilder builder = new StringBuilder();
        builder.append("{\"busy\":").append(active != null && !active.isTerminal());
        if (active != null) {
            builder.append(",\"activeId\":").append(quote(active.requestId))
                    .append(",\"activeState\":").append(quote(active.state()))
                    .append(",\"origin\":").append(quote(active.origin));
        }
        if (last != null) {
            builder.append(",\"lastId\":").append(quote(last.requestId))
                    .append(",\"lastState\":").append(quote(last.state))
                    .append(",\"lastSuccess\":").append(last.success)
                    .append(",\"lastError\":").append(quote(last.error));
        }
        builder.append("}");
        return builder.toString();
    }

    // ---- 超时 ------------------------------------------------------

    private void scheduleConsentTimeout(final String requestId) {
        cancelConsentTimeout();
        consentTimeout = () -> {
            CaptureRequestRegistry.Request expired = registry.expireStale(
                    CaptureRequestRegistry.STATE_CONSENT, CONSENT_TIMEOUT_MS,
                    System.currentTimeMillis(), "等待截图授权超时，请重试");
            if (expired == null || !expired.requestId.equals(requestId)) {
                return;
            }
            log("consent-timeout " + requestId);
            releaseHides(requestId);
            Target target = takeTarget(requestId);
            notifyTarget(target, requestId, "", "等待截图授权超时，请重试");
            AppCaptureYield.restore(requestId);
        };
        handler.postDelayed(consentTimeout, CONSENT_TIMEOUT_MS + 500L);
    }

    private void scheduleStartTimeout(final String requestId) {
        cancelStartTimeout();
        startTimeout = () -> {
            CaptureRequestRegistry.Request expired = registry.expireStale(
                    CaptureRequestRegistry.STATE_STARTING_SERVICE, SERVICE_START_TIMEOUT_MS,
                    System.currentTimeMillis(), "截图服务启动超时，请重试");
            if (expired == null || !expired.requestId.equals(requestId)) {
                return;
            }
            log("service-start-timeout " + requestId);
            releaseHides(requestId);
            CaptureService.abort(requestId, "截图服务启动超时");
            Target target = takeTarget(requestId);
            notifyTarget(target, requestId, "", "截图服务启动超时，请重试");
            AppCaptureYield.restore(requestId);
        };
        handler.postDelayed(startTimeout, SERVICE_START_TIMEOUT_MS + 500L);
    }

    private void cancelConsentTimeout() {
        if (consentTimeout != null) {
            handler.removeCallbacks(consentTimeout);
            consentTimeout = null;
        }
    }

    private void cancelStartTimeout() {
        if (startTimeout != null) {
            handler.removeCallbacks(startTimeout);
            startTimeout = null;
        }
    }

    private void cancelTimers() {
        cancelConsentTimeout();
        cancelStartTimeout();
    }

    // ---- 内部 ------------------------------------------------------

    private boolean finishActive(String requestId, String state, String error, String dataUrl) {
        CaptureRequestRegistry.Request request = registry.finish(requestId, state, error);
        if (request == null) {
            return false;
        }
        cancelTimers();
        releaseHides(requestId);
        Target target = takeTarget(requestId);
        notifyTarget(target, requestId, dataUrl, error);
        // App 发起的桌面截屏：任务已被收到后台，终结时尝试带回前台
        AppCaptureYield.restore(requestId);
        log("finish " + requestId + " state=" + state);
        return true;
    }

    /** 只释放属于该 requestId/token 的隐藏理由，绝不动别的任务。 */
    private void releaseHides(String requestId) {
        OverlayService.releasePermissionHidden(requestId);
        OverlayService.releaseCaptureHidden(requestId);
    }

    private Target takeTarget(String requestId) {
        WeakReference<Target> reference = targets.remove(requestId);
        return reference == null ? null : reference.get();
    }

    private void notifyTarget(Target target, String requestId, String dataUrl, String error) {
        if (target == null) {
            return;
        }
        try {
            target.onCaptureResult(requestId, dataUrl == null ? "" : dataUrl,
                    error == null ? "" : error);
        } catch (Exception failure) {
            log("target-callback-failed " + failure.getClass().getSimpleName());
        }
    }

    private static String quote(String value) {
        try {
            return JSONObject.quote(value == null ? "" : value);
        } catch (Throwable error) {
            return "\"\"";
        }
    }

    private static void log(String message) {
        try {
            Log.i(TAG, message);
        } catch (Throwable ignored) {
            // 普通 JVM 单测里 android.util.Log 是 stub，不能让它影响状态机
        }
    }
}
