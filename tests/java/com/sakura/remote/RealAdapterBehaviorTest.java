package com.sakura.remote;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 真实适配层行为测试（INT-02..INT-07）。
 *
 * 与 {@link IntegrationBehaviorTest} 的关键区别：
 *   - 这里**编译并实例化真实生产类**：{@link CaptureService}、
 *     {@link CapturePermissionActivity}、{@link OverlayTouchRegionController}，
 *     以及它们真实调用的 {@link CaptureCoordinator} / 各策略类；
 *   - 测试里**没有**复制 onStartCommand / onResume / abort / 区域提交等生产控制流；
 *     只提供 Android 系统接口的最小替身（tests/androidstub：可控 Handler 队列、
 *     焦点、surface、projection、显示创建计数、假 Coordinator 目标）。
 *
 * 运行方式见 tests/real_adapter_behavior_check.ps1：
 *   javac -d out &lt;真实生产源码&gt; &lt;androidstub&gt; RealAdapterBehaviorTest.java
 *   java -cp out com.sakura.remote.RealAdapterBehaviorTest
 */
public final class RealAdapterBehaviorTest {

    private static int failures = 0;

    public static void main(String[] args) {
        // INT-02：真实 CaptureService 的拒绝分支不得停掉活动截图
        captureServiceRejectKeepsLiveRequest();
        captureServiceEmptyRejectStopsOnlyThatStart();
        captureServiceDuplicateStartIsIdempotent();
        // INT-05：让位超时明确失败绝不建显示；registerCallback 失败立即终结
        captureServiceYieldTimeoutNeverCreatesDisplay();
        captureServiceRegisterCallbackFailureFinalizes();
        // INT-06：所有异步事件执行瞬间复核 owner + runGeneration
        captureServiceAbortOwnerRecheckKeepsReplacedRun();
        captureServiceOldYieldWaitDoesNotAffectNewRun();
        captureServiceOldProjectionStopDoesNotKillNewRun();
        captureServiceOldFrameDoesNotAffectNewRun();
        captureServiceOldFrameTimeoutDoesNotKillNewRun();
        // INT-03：真实授权 Activity 的生命周期/焦点/超时/旋转
        consentActivityRealLifecycleGating();
        consentActivityTimeoutAndRotation();
        // INT-04：真实控制器 + RegionSessionState 的原子提交/迟到查询/写入前复核
        regionStateLateLookupPreservesNewSnapshot();
        regionStateSubmitIsAtomicAndMonotonic();
        regionControllerLateTaskCannotRestoreOldRegion();
        regionControllerWriteTimeGenerationRecheck();
        // TS-03：同一代际下区域内容完全一致时不重复写 surface，但照常上报 ACK
        regionControllerSkipsIdenticalRegionWrites();
        regionSignatureNeverDeduplicatesTruncatedPrefixes();

        System.out.println();
        if (failures > 0) {
            System.err.println(failures + " 项失败");
            System.exit(1);
        }
        System.out.println("PASS (real production sources: CaptureService/CapturePermissionActivity/OverlayTouchRegionController)");
    }

    private static void regionSignatureNeverDeduplicatesTruncatedPrefixes() {
        try {
            java.lang.reflect.Method method = OverlayTouchRegionController.class
                    .getDeclaredMethod("regionSignature", android.graphics.Region.class);
            method.setAccessible(true);
            android.graphics.Region region = new android.graphics.Region();
            for (int i = 0; i < 4097; i++) {
                region.op(new android.graphics.Rect(i * 2, 0, i * 2 + 1, 1),
                        android.graphics.Region.Op.UNION);
            }
            check("Oversized region disables dedup instead of comparing truncated prefixes",
                    method.invoke(null, region) == null);
        } catch (Exception error) {
            check("Oversized region signature test executes", false, error);
        }
    }

    // ================= 公共替身与工具 =================

    private static final class RecordingTarget implements CaptureCoordinator.Target {
        final List<String> results = new ArrayList<>();

        @Override
        public void onCaptureResult(String requestId, String dataUrl, String error) {
            results.add(requestId + "|" + (error == null ? "" : error)
                    + "|" + (dataUrl == null ? 0 : dataUrl.length()));
        }
    }

    private static final class RecordingHost implements OverlayTouchRegionController.Host {
        long appliedRevision = Long.MIN_VALUE;
        int appliedRectCount = -1;
        boolean appliedFallback;
        int appliedCount;
        String rejected = "";
        String unsupported = "";

        @Override
        public void onTouchRegionApplied(long revision, int rectCount, boolean fallbackFull) {
            appliedRevision = revision;
            appliedRectCount = rectCount;
            appliedFallback = fallbackFull;
            appliedCount++;
        }

        @Override
        public void onTouchRegionRejected(String reason) {
            rejected = reason == null ? "" : reason;
        }

        @Override
        public void onTouchRegionUnsupported(String reason) {
            unsupported = reason == null ? "" : reason;
        }
    }

    /** 承载真实 CaptureService 及其系统替身。 */
    private static final class ServiceHarness {
        final CaptureService service = new CaptureService();
        final MediaProjectionManager manager = new MediaProjectionManager();
        final MediaProjection projection = new MediaProjection();
        final RecordingTarget target = new RecordingTarget();

        ServiceHarness() {
            manager.setProjectionForTest(projection);
            service.setSystemServiceForTest(Context.MEDIA_PROJECTION_SERVICE, manager);
            service.setSystemServiceForTest(Context.WINDOW_SERVICE, new WindowManager());
            service.setSystemServiceForTest(Context.NOTIFICATION_SERVICE, null);
        }

        int start(String requestId, int startId) {
            Intent intent = new Intent();
            intent.putExtra(CaptureService.EXTRA_REQUEST_ID, requestId);
            intent.putExtra(CaptureService.EXTRA_RESULT_CODE, Activity.RESULT_OK);
            intent.putExtra(CaptureService.EXTRA_RESULT_DATA, new Intent());
            return service.onStartCommand(intent, 0, startId);
        }

        int startInvalid(String startId2, int startId) {
            Intent intent = new Intent();
            intent.putExtra(CaptureService.EXTRA_REQUEST_ID, startId2);
            intent.putExtra(CaptureService.EXTRA_RESULT_CODE, Activity.RESULT_OK);
            intent.putExtra(CaptureService.EXTRA_RESULT_DATA, new Intent());
            return service.onStartCommand(intent, 0, startId);
        }
    }

    private static CaptureCoordinator coordinator() {
        return CaptureCoordinator.get();
    }

    /** 结束一切在途请求，保证下一个场景从 IDLE 开始。 */
    private static void ensureIdle() {
        CaptureCoordinator coordinator = coordinator();
        for (int i = 0; i < 4; i++) {
            CaptureRequestRegistry.Request active = coordinator.activeRequest();
            if (active == null || active.isTerminal()) {
                break;
            }
            coordinator.cancel(active.requestId, "test-reset");
            Handler.drainAll();
            Handler.clearQueue();
        }
        Handler.clearQueue();
    }

    private static void resetWorld() {
        ensureIdle();
        OverlayService.resetForTest();
        VirtualDisplay.resetCreateCountForTest();
        ImageReader.resetLastInstanceForTest();
        Handler.clearQueue();
        SystemClock.setElapsedRealtimeForTest(1_000L);
    }

    private static String beginRequest(final String pageSession, final RecordingTarget target) {
        CaptureRequestRegistry.Request request = coordinator().begin(
                CaptureCoordinator.Origin.APP, pageSession, target);
        if (request == null) {
            throw new IllegalStateException("coordinator busy while beginning " + pageSession);
        }
        return request.requestId;
    }

    /**
     * 消费协调器在 begin 时排入的「授权阶段超时」任务。
     *
     * 这个定时器 90s 后才可能生效，但在替身队列里会排在服务事件之前；
     * 测试在该任务确实位于队首时显式取走它，从而精确控制后续事件顺序。
     */
    private static void consumeConsentTimer() {
        Handler.drainOne();
    }

    /** 排空直到真实产品创建了 VirtualDisplay（消费掉前面的协调器定时器等）。 */
    private static void drainUntilDisplayCreated(ServiceHarness harness) {
        for (int i = 0; i < 8 && harness.projection.createVirtualDisplayCalls == 0; i++) {
            if (!Handler.drainOne()) {
                return;
            }
        }
    }

    // ================= INT-02 =================

    private static void captureServiceRejectKeepsLiveRequest() {
        System.out.println("--- INT-02 real CaptureService: rejected start keeps live run ---");
        resetWorld();
        ServiceHarness harness = new ServiceHarness();
        String idA = beginRequest("page-A", harness.target);
        OverlayService.acquirePermissionHidden(idA);
        OverlayService.setOverlayAttachedForTest(true);   // 让位未成立，先不建 surface

        check("合法 A 被接受", harness.start(idA, 1) == CaptureService.START_NOT_STICKY);
        check("A 已进入前台", harness.service.startForegroundCalls.size() == 1,
                harness.service.startForegroundCalls);
        check("A 已注册 projection 回调",
                harness.projection.registeredCallback != null);
        check("A 持有 captureHidden 令牌",
                idA.equals(OverlayService.captureHiddenOwnerForTest()),
                OverlayService.captureHiddenOwnerForTest());
        check("授权隐藏已交接给 captureHidden", OverlayService.permissionHiddenOwnerForTest() == null,
                OverlayService.permissionHiddenOwnerForTest());

        // 陈旧请求：协调器里不活动
        check("陈旧 start 被拒（真实 decide）",
                harness.start("req-stale-not-active", 2) == CaptureService.START_NOT_STICKY);
        // 非法请求：空 requestId
        Intent invalid = new Intent();
        invalid.putExtra(CaptureService.EXTRA_REQUEST_ID, "");
        check("空 requestId 被拒",
                harness.service.onStartCommand(invalid, 0, 3) == CaptureService.START_NOT_STICKY);
        // 重复的当前请求：幂等
        check("重复 A 幂等", harness.start(idA, 4) == CaptureService.START_NOT_STICKY);

        check("被拒的 start 没有 stopSelf", harness.service.stopSelfCalls.isEmpty(),
                harness.service.stopSelfCalls);
        check("被拒后 A 仍是活动 run", harness.service.stopSelfCalls.isEmpty());
        check("被拒后 A 的前台未停", harness.service.stopForegroundCount == 0,
                harness.service.stopForegroundCount);
        check("被拒后 A 仍持有 captureHidden",
                idA.equals(OverlayService.captureHiddenOwnerForTest()),
                OverlayService.captureHiddenOwnerForTest());
        check("被拒后 A 仍持有 projection", harness.projection.stopCalls == 0,
                harness.projection.stopCalls);
        check("被拒后未创建显示", harness.projection.createVirtualDisplayCalls == 0,
                harness.projection.createVirtualDisplayCalls);
        check("被拒请求没有伪造结果", harness.target.results.isEmpty(), harness.target.results);

        // 现在放行让位并交付一帧：A 成功，恰好一次
        OverlayService.setOverlayAttachedForTest(false);
        drainUntilDisplayCreated(harness);   // 让位等待 → 创建 reader/显示
        check("让位成立后恰好创建一次显示",
                harness.projection.createVirtualDisplayCalls == 1,
                harness.projection.createVirtualDisplayCalls);
        ImageReader reader = ImageReader.lastInstanceForTest();
        check("真实产品的帧监听器已设置", reader != null && reader.listenerForTest() != null);
        ImageReader.OnImageAvailableListener listener = reader.listenerForTest();
        reader.setNextImageForTest(new Image());
        listener.onImageAvailable(reader);

        check("A 成功恰好回调一次", harness.target.results.size() == 1, harness.target.results);
        check("A 结束后释放 captureHidden",
                OverlayService.captureHiddenOwnerForTest() == null,
                OverlayService.captureHiddenOwnerForTest());
        check("A 结束恰好一次 stopSelf",
                harness.service.stopSelfCalls.size() == 1, harness.service.stopSelfCalls);
        check("A 结束后 projection 已停止", harness.projection.stopCalls == 1,
                harness.projection.stopCalls);

        // 重复终结：读帧后 listener 再触发也不能再回调
        reader.setNextImageForTest(new Image());
        listener.onImageAvailable(reader);
        check("重复帧不再回调", harness.target.results.size() == 1, harness.target.results);
        check("重复帧不再 stopSelf", harness.service.stopSelfCalls.size() == 1,
                harness.service.stopSelfCalls);
    }

    private static void captureServiceEmptyRejectStopsOnlyThatStart() {
        System.out.println("--- INT-02 real CaptureService: empty stale start cleans only that start ---");
        resetWorld();
        ServiceHarness harness = new ServiceHarness();
        String idGone = beginRequest("page-gone", harness.target);
        coordinator().onConsentDenied(idGone, "already gone");   // 协调器里不再活动

        harness.start(idGone, 7);
        check("空启动按 startId 清理",
                harness.service.stopSelfCalls.size() == 1
                        && harness.service.stopSelfCalls.get(0) == 7,
                harness.service.stopSelfCalls);
        check("空启动不建资源", harness.projection.createVirtualDisplayCalls == 0,
                harness.projection.createVirtualDisplayCalls);
        check("空启动不碰隐藏令牌", OverlayService.captureHiddenOwnerForTest() == null,
                OverlayService.captureHiddenOwnerForTest());
    }

    private static void captureServiceDuplicateStartIsIdempotent() {
        System.out.println("--- INT-02 real CaptureService: duplicate start idempotent ---");
        resetWorld();
        ServiceHarness harness = new ServiceHarness();
        String idA = beginRequest("page-A", harness.target);
        OverlayService.setOverlayAttachedForTest(true);
        harness.start(idA, 1);
        int foregroundBefore = harness.service.startForegroundCalls.size();
        check("重复 A 幂等", harness.start(idA, 2) == CaptureService.START_NOT_STICKY);
        check("重复 A 不再重建（无显示）", harness.projection.createVirtualDisplayCalls == 0,
                harness.projection.createVirtualDisplayCalls);
        check("重复 A 不 stopSelf", harness.service.stopSelfCalls.isEmpty(),
                harness.service.stopSelfCalls);
        check("重复 A 不重复进前台",
                harness.service.startForegroundCalls.size() == foregroundBefore,
                harness.service.startForegroundCalls);
        check("重复 A 保持令牌归属",
                idA.equals(OverlayService.captureHiddenOwnerForTest()),
                OverlayService.captureHiddenOwnerForTest());
        ensureIdle();
    }

    // ================= INT-05 =================

    private static void captureServiceYieldTimeoutNeverCreatesDisplay() {
        System.out.println("--- INT-05 real CaptureService: yield budget exhaustion fails, 0 display ---");
        // 三种「让位始终不成立」：悬浮窗 attached / 授权页可见 / 两者都成立
        runYieldClosedScenario(true, false, "悬浮窗仍 attached");
        runYieldClosedScenario(false, true, "授权页仍可见");
        runYieldClosedScenario(true, true, "授权页可见且悬浮窗 attached");
    }

    private static void runYieldClosedScenario(boolean overlayAttached, boolean activityVisible,
                                               String label) {
        resetWorld();
        ServiceHarness harness = new ServiceHarness();
        String idA = beginRequest("page-yield", harness.target);
        OverlayService.acquirePermissionHidden(idA);
        OverlayService.setOverlayAttachedForTest(overlayAttached);
        CapturePermissionActivity visibleActivity = null;
        if (activityVisible) {
            visibleActivity = new CapturePermissionActivity();
            visibleActivity.onStart();
        }
        try {
            harness.start(idA, 1);
            int steps = Handler.drainAll();
            check("[" + label + "] 让位超时明确终结并停止服务",
                    harness.service.stopSelfCalls.size() == 1, harness.service.stopSelfCalls);
            check("[" + label + "] createVirtualDisplay 调用为零",
                    harness.projection.createVirtualDisplayCalls == 0,
                    harness.projection.createVirtualDisplayCalls);
            check("[" + label + "] 系统显示创建计数为零",
                    VirtualDisplay.createCountForTest() == 0, VirtualDisplay.createCountForTest());
            check("[" + label + "] 资源释放（projection.stop 恰好一次）",
                    harness.projection.stopCalls == 1, harness.projection.stopCalls);
            check("[" + label + "] 释放 captureHidden 令牌",
                    OverlayService.captureHiddenOwnerForTest() == null,
                    OverlayService.captureHiddenOwnerForTest());
            check("[" + label + "] 明确失败且恰好一次结果",
                    harness.target.results.size() == 1 && !harness.target.results.get(0).contains("||"),
                    harness.target.results);
            check("[" + label + "] 让位轮询确实耗尽预算（>1 步）", steps > 1, steps);
        } finally {
            if (visibleActivity != null) {
                visibleActivity.onStop();
            }
        }
    }

    private static void captureServiceRegisterCallbackFailureFinalizes() {
        System.out.println("--- INT-05 real CaptureService: registerCallback failure finalizes ---");
        resetWorld();
        ServiceHarness harness = new ServiceHarness();
        harness.projection.throwOnRegisterCallback = true;
        String idA = beginRequest("page-register", harness.target);
        OverlayService.acquirePermissionHidden(idA);
        OverlayService.setOverlayAttachedForTest(false);   // 即使让位成立也不得继续

        harness.start(idA, 1);

        check("注册失败不创建显示", harness.projection.createVirtualDisplayCalls == 0,
                harness.projection.createVirtualDisplayCalls);
        check("注册失败系统显示计数为零", VirtualDisplay.createCountForTest() == 0,
                VirtualDisplay.createCountForTest());
        check("注册失败恰好一次 stopSelf", harness.service.stopSelfCalls.size() == 1,
                harness.service.stopSelfCalls);
        check("注册失败释放 projection", harness.projection.stopCalls == 1,
                harness.projection.stopCalls);
        check("注册失败释放授权隐藏令牌",
                OverlayService.permissionHiddenOwnerForTest() == null,
                OverlayService.permissionHiddenOwnerForTest());
        check("注册失败不等待帧超时（队列清空）", Handler.pending() == 0, Handler.pending());
        check("注册失败恰好一次结果", harness.target.results.size() == 1, harness.target.results);
    }

    // ================= INT-06 =================

    private static void captureServiceAbortOwnerRecheckKeepsReplacedRun() {
        System.out.println("--- INT-06 real CaptureService: queued abort(A) cannot kill replaced B ---");
        resetWorld();
        ServiceHarness harness = new ServiceHarness();
        String idA = beginRequest("page-A", harness.target);
        OverlayService.setOverlayAttachedForTest(true);   // A 的让位等待留在队列里
        harness.start(idA, 1);
        consumeConsentTimer();   // 队首是协调器定时器，取走后只剩 A 的让位等待

        // 排队一个旧 A 的 abort（此时 A 仍是当前 run）
        CaptureService.abort(idA, "旧的取消事件");

        // 先让 B 成为当前 run：A 在协调器里失效 → B 登记 → REPLACE_STALE
        coordinator().onConsentDenied(idA, "被替换");
        RecordingTarget targetB = new RecordingTarget();
        String idB = beginRequest("page-B", targetB);
        harness.start(idB, 2);
        check("B 已成为当前 run",
                idB.equals(OverlayService.captureHiddenOwnerForTest()),
                OverlayService.captureHiddenOwnerForTest());
        int stopSelfBefore = harness.service.stopSelfCalls.size();

        // 执行排队的旧 A 让位等待 + 旧 abort(A)：都必须被忽略
        Handler.drainOne();
        Handler.drainOne();
        check("旧 abort(A) 不结束 B", harness.service.stopSelfCalls.size() == stopSelfBefore,
                harness.service.stopSelfCalls);
        check("旧 abort(A) 后 B 仍持有令牌",
                idB.equals(OverlayService.captureHiddenOwnerForTest()),
                OverlayService.captureHiddenOwnerForTest());
        check("旧 abort(A) 后 B 未回调结果", targetB.results.isEmpty(), targetB.results);

        // 当前 B 的取消：恰好一次
        CaptureService.abort(idB, "取消当前的 B");
        Handler.drainAll();
        check("B 取消恰好一次结果", targetB.results.size() == 1, targetB.results);
        check("B 取消恰好一次 stopSelf", harness.service.stopSelfCalls.size() == 1,
                harness.service.stopSelfCalls);
        check("B 取消后释放令牌",
                OverlayService.captureHiddenOwnerForTest() == null,
                OverlayService.captureHiddenOwnerForTest());
        check("B 结束后 projection 已停止", harness.projection.stopCalls >= 1,
                harness.projection.stopCalls);
    }

    private static void captureServiceOldYieldWaitDoesNotAffectNewRun() {
        System.out.println("--- INT-06 real CaptureService: old yield wait ignored after replacement ---");
        resetWorld();
        ServiceHarness harness = new ServiceHarness();
        String idA = beginRequest("page-A", harness.target);
        OverlayService.setOverlayAttachedForTest(true);
        harness.start(idA, 1);   // 队列里留下 A 的让位等待
        consumeConsentTimer();

        coordinator().onConsentDenied(idA, "被替换");
        RecordingTarget targetB = new RecordingTarget();
        String idB = beginRequest("page-B", targetB);
        harness.start(idB, 2);   // 队列：[让位(A), 让位(B)]

        Handler.drainOne();      // 执行旧的让位(A)
        check("旧让位等待不创建显示", harness.projection.createVirtualDisplayCalls == 0,
                harness.projection.createVirtualDisplayCalls);
        check("旧让位等待不结束 B",
                idB.equals(OverlayService.captureHiddenOwnerForTest()),
                OverlayService.captureHiddenOwnerForTest());
        CaptureService.abort(idB, "cleanup");
        Handler.drainAll();
    }

    private static void captureServiceOldProjectionStopDoesNotKillNewRun() {
        System.out.println("--- INT-06 real CaptureService: old projection.onStop ignored ---");
        resetWorld();
        ServiceHarness harness = new ServiceHarness();
        String idA = beginRequest("page-A", harness.target);
        OverlayService.setOverlayAttachedForTest(true);
        harness.start(idA, 1);
        MediaProjection.Callback oldCallback = harness.projection.registeredCallback;
        check("A 注册了 projection.onStop 回调", oldCallback != null);

        coordinator().onConsentDenied(idA, "被替换");
        RecordingTarget targetB = new RecordingTarget();
        String idB = beginRequest("page-B", targetB);
        harness.start(idB, 2);
        check("B 重新注册了 projection 回调",
                harness.projection.registeredCallback != null
                        && harness.projection.registeredCallback != oldCallback);

        // 旧 A 的 projection 回调迟到
        oldCallback.onStop();
        check("旧 projection.onStop 不结束 B",
                idB.equals(OverlayService.captureHiddenOwnerForTest()),
                OverlayService.captureHiddenOwnerForTest());
        check("旧 projection.onStop 不产生结果", targetB.results.isEmpty(), targetB.results);

        CaptureService.abort(idB, "cleanup");
        Handler.drainAll();
        check("B 仍恰好一次结果", targetB.results.size() == 1, targetB.results);
    }

    private static void captureServiceOldFrameDoesNotAffectNewRun() {
        System.out.println("--- INT-06 real CaptureService: old frame ignored ---");
        resetWorld();
        ServiceHarness harness = new ServiceHarness();
        String idA = beginRequest("page-A", harness.target);
        OverlayService.setOverlayAttachedForTest(false);
        harness.start(idA, 1);
        drainUntilDisplayCreated(harness);   // 建 A 的 reader/显示
        ImageReader oldReader = ImageReader.lastInstanceForTest();
        check("A 的 reader 已创建", oldReader != null);
        ImageReader.OnImageAvailableListener oldListener = oldReader.listenerForTest();
        check("A 的帧监听器已设置", oldListener != null);

        coordinator().onConsentDenied(idA, "被替换");
        RecordingTarget targetB = new RecordingTarget();
        String idB = beginRequest("page-B", targetB);
        OverlayService.setOverlayAttachedForTest(true);   // B 先不建 surface
        harness.start(idB, 2);
        int stopSelfBefore = harness.service.stopSelfCalls.size();

        // 旧 A 的帧迟到
        oldReader.setNextImageForTest(new Image());
        oldListener.onImageAvailable(oldReader);
        check("旧帧不结束 B", harness.service.stopSelfCalls.size() == stopSelfBefore,
                harness.service.stopSelfCalls);
        check("旧帧不产生 B 的结果", targetB.results.isEmpty(), targetB.results);
        check("旧帧不结束 B 的令牌",
                idB.equals(OverlayService.captureHiddenOwnerForTest()),
                OverlayService.captureHiddenOwnerForTest());

        CaptureService.abort(idB, "cleanup");
        Handler.drainAll();
        check("B 仍恰好一次结果", targetB.results.size() == 1, targetB.results);
    }

    private static void captureServiceOldFrameTimeoutDoesNotKillNewRun() {
        System.out.println("--- INT-06 real CaptureService: old frame timeout ignored ---");
        resetWorld();
        ServiceHarness harness = new ServiceHarness();
        String idA = beginRequest("page-A", harness.target);
        OverlayService.setOverlayAttachedForTest(false);
        harness.start(idA, 1);
        drainUntilDisplayCreated(harness);   // 建 A 的 surface 并排入 A 的帧超时
        check("A 已排入帧超时", Handler.pending() == 1, Handler.pending());

        coordinator().onConsentDenied(idA, "被替换");
        RecordingTarget targetB = new RecordingTarget();
        String idB = beginRequest("page-B", targetB);
        harness.start(idB, 2);   // 队列：[超时(A), 让位(B)]

        Handler.drainOne();      // 执行旧的帧超时
        check("旧帧超时不结束 B",
                idB.equals(OverlayService.captureHiddenOwnerForTest()),
                OverlayService.captureHiddenOwnerForTest());
        check("旧帧超时不产生 B 的结果", targetB.results.isEmpty(), targetB.results);

        CaptureService.abort(idB, "cleanup");
        Handler.drainAll();
        check("B 仍恰好一次结果", targetB.results.size() == 1, targetB.results);
    }

    // ================= INT-03 =================

    private static CapturePermissionActivity newConsentActivity(String requestId) {
        CapturePermissionActivity activity = new CapturePermissionActivity();
        Intent intent = new Intent();
        intent.putExtra(CapturePermissionActivity.EXTRA_REQUEST_ID, requestId);
        activity.setIntent(intent);
        activity.setSystemServiceForTest(Context.MEDIA_PROJECTION_SERVICE,
                new MediaProjectionManager());
        return activity;
    }

    private static void consentActivityRealLifecycleGating() {
        System.out.println("--- INT-03 real CapturePermissionActivity: resumed/focus gating ---");
        resetWorld();
        RecordingTarget target = new RecordingTarget();
        String id = beginRequest("page-consent", target);
        OverlayService.setOverlayAttachedForTest(true);
        CapturePermissionActivity activity = newConsentActivity(id);
        activity.onCreate(null);
        activity.onStart();
        activity.onResume();
        consumeConsentTimer();

        check("悬浮窗未让位时不申请授权", activity.startActivityForResultCalls == 0,
                activity.startActivityForResultCalls);

        // 让位后仍无真实焦点：连续超过旧实现的 6 次阈值也不得申请
        OverlayService.setOverlayAttachedForTest(false);
        activity.setWindowFocusForTest(false);
        for (int i = 0; i < 12; i++) {
            Handler.drainOne();
        }
        check("无焦点超过 6 次仍不申请授权", activity.startActivityForResultCalls == 0,
                activity.startActivityForResultCalls);
        check("无焦点时仍在等待而不是失败", !activity.isFinishing());

        // 真实焦点到达：恰好一次
        activity.setWindowFocusForTest(true);
        activity.onWindowFocusChanged(true);
        Handler.drainOne();
        check("真实焦点到达后恰好申请一次", activity.startActivityForResultCalls == 1,
                activity.startActivityForResultCalls);

        // 焦点抖动/继续轮询都不再申请
        activity.setWindowFocusForTest(false);
        activity.onWindowFocusChanged(false);
        activity.setWindowFocusForTest(true);
        activity.onWindowFocusChanged(true);
        Handler.drainOne();
        check("授权弹窗只开一次", activity.startActivityForResultCalls == 1,
                activity.startActivityForResultCalls);

        activity.onStop();
        coordinator().onConsentDenied(id, "cleanup");
        Handler.clearQueue();
    }

    private static void consentActivityTimeoutAndRotation() {
        System.out.println("--- INT-03 real CapturePermissionActivity: timeout + rotation ---");
        resetWorld();
        RecordingTarget target = new RecordingTarget();
        String id = beginRequest("page-timeout", target);
        OverlayService.setOverlayAttachedForTest(false);
        CapturePermissionActivity activity = newConsentActivity(id);
        activity.onCreate(null);
        activity.onStart();
        activity.onResume();
        activity.setWindowFocusForTest(false);
        consumeConsentTimer();

        // 无焦点一直到超时：明确失败，启动次数为零
        SystemClock.advanceForTest(3_100L);
        Handler.drainOne();
        check("无焦点超时后启动次数为零", activity.startActivityForResultCalls == 0,
                activity.startActivityForResultCalls);
        check("无焦点超时后明确失败", activity.isFinishing());
        activity.onStop();

        // 旋转：elapsed 经 savedInstanceState 保留，期限不被续命
        resetWorld();
        RecordingTarget rotateTarget = new RecordingTarget();
        String rotateId = beginRequest("page-rotate", rotateTarget);
        OverlayService.setOverlayAttachedForTest(false);
        SystemClock.setElapsedRealtimeForTest(1_000L);
        CapturePermissionActivity first = newConsentActivity(rotateId);
        first.onCreate(null);
        first.onStart();
        first.onResume();
        SystemClock.setElapsedRealtimeForTest(3_990L);   // 已经等了 2.99s
        Bundle state = new Bundle();
        first.setChangingConfigurationsForTest(true);
        first.onSaveInstanceState(state);
        first.onStop();
        first.onDestroy();   // KEEP_REQUEST：请求保留

        check("旋转不取消在途请求", coordinator().isActive(rotateId));

        CapturePermissionActivity second = newConsentActivity(rotateId);
        second.onCreate(state);
        second.onStart();
        second.onResume();
        second.setWindowFocusForTest(true);
        SystemClock.advanceForTest(60L);   // 3.05s > 3s：期限跨旋转累计
        Handler.drainOne();
        check("旋转后超时不再启动授权", second.startActivityForResultCalls == 0,
                second.startActivityForResultCalls);
        check("旋转后超时明确失败", second.isFinishing());
        second.onStop();
        coordinator().onConsentDenied(rotateId, "cleanup");
        Handler.clearQueue();
    }

    // ================= INT-04 =================

    private static RegionSnapshotStore.Snapshot snapshot(String sessionId, long revision) {
        return new RegionSnapshotStore.Snapshot(
                sessionId, revision, 393f, 700f,
                new float[][]{{10f, 10f, 40f, 400f}}, false);
    }

    private static void regionStateLateLookupPreservesNewSnapshot() {
        System.out.println("--- INT-04 real RegionSessionState: late lookup preserves B snapshot ---");
        RegionSessionState state = new RegionSessionState();
        state.beginSession("page-A");
        long oldGeneration = state.generation();
        state.beginSession("page-B");
        RegionSnapshotStore.Snapshot b = snapshot("page-B", 1L);
        check("B revision=1 原子提交被接受",
                state.submit("page-B", 1L, b).accepted());
        check("旧代际查询被拒", state.applicableSnapshot(oldGeneration, "page-A") == null);
        check("旧查询没有清掉最新有效 B 快照", state.latest() == b, state.latest());
        check("B 快照仍可应用", state.isApplicable(b, state.generation()));
    }

    private static void regionStateSubmitIsAtomicAndMonotonic() {
        System.out.println("--- INT-04 real RegionSessionState: atomic submit + revision monotonicity ---");
        RegionSessionState state = new RegionSessionState();
        state.beginSession("page-A");
        RegionSnapshotStore.Snapshot a1 = snapshot("page-A", 1L);
        check("A revision=1 原子提交被接受", state.submit("page-A", 1L, a1).accepted());
        check("存储的就是本次提交的快照", state.latest() == a1);
        check("同 A 陈旧 revision=1 被拒",
                state.submit("page-A", 1L, snapshot("page-A", 1L)).decision
                        == RegionSessionGate.Decision.STALE_REVISION);
        check("同 A 更高 revision 接受",
                state.submit("page-A", 2L, snapshot("page-A", 2L)).accepted());
        check("未登记会话提交被拒",
                state.submit("page-B", 1L, snapshot("page-B", 1L)).decision
                        == RegionSessionGate.Decision.STALE_SESSION);
        // 提交被拒时快照没有落库
        RegionSnapshotStore.Snapshot before = state.latest();
        state.submit("page-B", 5L, snapshot("page-B", 5L));
        check("被拒提交不改变已存快照", state.latest() == before);
    }

    private static void regionControllerLateTaskCannotRestoreOldRegion() {
        System.out.println("--- INT-04 real OverlayTouchRegionController: late task cannot restore A ---");
        resetWorld();
        View root = new View();
        root.width = 1080;
        root.height = 2400;
        View input = new View();
        input.width = 1080;
        input.height = 2400;
        RecordingHost host = new RecordingHost();
        OverlayTouchRegionController controller =
                new OverlayTouchRegionController(root, input, host);
        controller.setPassThroughEnabled(true);

        controller.beginSession("page-A");
        check("A 载荷受理",
                "queued".equals(controller.applyPayload(payload("page-A", 1L, 100f, 100f,
                        new int[][]{{0, 0, 50, 50}}))));
        Handler.drainAll();
        check("A 已应用到 surface", host.appliedRevision == 1L, host.appliedRevision);
        int writesBeforeDuplicateEnable = root.surfaceControl.setTouchableRegionCalls;
        controller.setPassThroughEnabled(true);
        controller.setPassThroughEnabled(true);
        Handler.drainAll();
        check("重复开启不重置已生效轮廓、不再写 surface",
                root.surfaceControl.setTouchableRegionCalls == writesBeforeDuplicateEnable,
                root.surfaceControl.setTouchableRegionCalls);

        // 换页到 B：即使 A 的异步任务还在队列里也不能把 A 区域恢复回来
        controller.beginSession("page-B");
        Handler.drainAll();
        check("换页后窗口恢复整窗", host.appliedRevision == -1L, host.appliedRevision);

        check("B revision=1 可接受",
                "queued".equals(controller.applyPayload(payload("page-B", 1L, 100f, 100f,
                        new int[][]{{0, 0, 50, 50}}))));
        Handler.drainAll();
        check("B 已应用", host.appliedRevision == 1L, host.appliedRevision);
        check("B 区域含 1 个矩形", host.appliedRectCount == 1, host.appliedRectCount);

        // 同 B 再登记：无操作，陈旧 revision=1 仍拒绝
        controller.beginSession("page-B");
        check("旧 revision=1 在重复登记后仍被拒",
                "stale".equals(controller.applyPayload(payload("page-B", 1L, 100f, 100f,
                        new int[][]{{0, 0, 50, 50}}))));
        check("重复登记不清 B 快照", controller.applyPayload(payload("page-B", 2L, 100f, 100f,
                new int[][]{{0, 0, 60, 60}})).equals("queued"));
        Handler.drainAll();
        check("B revision=2 已应用", host.appliedRevision == 2L, host.appliedRevision);

        // 旧 A 注销不影响 B
        controller.endSession("page-A");
        check("旧 A 注销后 B 快照仍在（重新挂载可重建）",
                B_snapshotStillApplies(root, controller, host));
        controller.destroy();
    }

    private static boolean B_snapshotStillApplies(View root, OverlayTouchRegionController controller,
                                                  RecordingHost host) {
        long before = host.appliedCount;
        root.dispatchDetachedForTest();
        root.dispatchAttachedForTest();
        Handler.drainAll();
        return host.appliedCount > before && host.appliedRevision == 2L;
    }

    private static void regionControllerWriteTimeGenerationRecheck() {
        System.out.println("--- INT-04 real OverlayTouchRegionController: write-time generation recheck ---");
        resetWorld();
        View root = new View();
        root.width = 1080;
        root.height = 2400;
        View input = new View();
        input.width = 1080;
        input.height = 2400;
        RecordingHost host = new RecordingHost();
        OverlayTouchRegionController controller =
                new OverlayTouchRegionController(root, input, host);
        controller.setPassThroughEnabled(true);

        // 排入携带 A 代际的任务，但在它们执行前换成 B
        controller.beginSession("page-A");
        controller.applyPayload(payload("page-A", 1L, 100f, 100f,
                new int[][]{{0, 0, 50, 50}}));
        controller.beginSession("page-B");
        Handler.drainAll();

        check("旧代际任务没有写回 A 的区域", host.appliedRevision == -1L, host.appliedRevision);
        int writesAfterSwitch = root.surfaceControl.setTouchableRegionCalls;
        check("换页提交确实写了 surface（整窗）", writesAfterSwitch >= 1, writesAfterSwitch);

        // B 建立自己的快照：只有 B 的区域能写入
        controller.applyPayload(payload("page-B", 1L, 100f, 100f,
                new int[][]{{0, 0, 50, 50}}));
        Handler.drainAll();
        check("B 快照应用成功", host.appliedRevision == 1L, host.appliedRevision);
        controller.destroy();
    }

    /**
     * TS-03：同一代际下**逐设备像素相同**的区域不再重复写 surface。
     *
     * 网页在滑动/布局抖动时可能连续提交矩形完全一致、只有 revision 不同的载荷；
     * 每次都写 setTouchableRegion 等于让 WMS 白算一次输入窗口。这里断言：
     *   1) 内容相同的第二次提交不增加 setTouchableRegion 调用，但仍上报 applied；
     *   2) 内容变化（矩形不同）必须真正写一次；
     *   3) 换页恢复整窗必须真正写一次（不得被跳过）；
     *   4) 换页后同样的矩形必须重新写（代际参与签名）。
     */
    private static void regionControllerSkipsIdenticalRegionWrites() {
        System.out.println("--- TS-03 real OverlayTouchRegionController: identical region not rewritten ---");
        resetWorld();
        View root = new View();
        root.width = 1080;
        root.height = 2400;
        View input = new View();
        input.width = 1080;
        input.height = 2400;
        RecordingHost host = new RecordingHost();
        OverlayTouchRegionController controller =
                new OverlayTouchRegionController(root, input, host);
        controller.setPassThroughEnabled(true);
        controller.beginSession("page-A");

        controller.applyPayload(payload("page-A", 1L, 100f, 100f, new int[][]{{0, 0, 50, 50}}));
        Handler.drainAll();
        int writesAfterFirst = root.surfaceControl.setTouchableRegionCalls;
        long appliedAfterFirst = host.appliedCount;
        check("首次区域写入了 surface", writesAfterFirst >= 1, writesAfterFirst);

        // 同样的矩形、更高的 revision：不应再写 surface，但仍要 ACK
        controller.applyPayload(payload("page-A", 2L, 100f, 100f, new int[][]{{0, 0, 50, 50}}));
        Handler.drainAll();
        check("内容相同的区域不再写 surface",
                root.surfaceControl.setTouchableRegionCalls == writesAfterFirst,
                root.surfaceControl.setTouchableRegionCalls);
        check("跳过写入仍然上报 ACK", host.appliedCount > appliedAfterFirst
                && host.appliedRevision == 2L, host.appliedRevision);

        // 矩形变化：必须真正写一次
        controller.applyPayload(payload("page-A", 3L, 100f, 100f, new int[][]{{0, 0, 60, 60}}));
        Handler.drainAll();
        check("区域变化必须重新写 surface",
                root.surfaceControl.setTouchableRegionCalls > writesAfterFirst,
                root.surfaceControl.setTouchableRegionCalls);
        int writesAfterChange = root.surfaceControl.setTouchableRegionCalls;

        // 换页：恢复整窗必须真正写（不得被内容签名跳过）
        controller.beginSession("page-B");
        Handler.drainAll();
        check("换页恢复整窗必须真正写 surface",
                root.surfaceControl.setTouchableRegionCalls > writesAfterChange,
                root.surfaceControl.setTouchableRegionCalls);
        int writesAfterSwitch = root.surfaceControl.setTouchableRegionCalls;

        // 换页后与上一页完全相同的矩形：代际不同，必须重新写
        controller.applyPayload(payload("page-B", 1L, 100f, 100f, new int[][]{{0, 0, 60, 60}}));
        Handler.drainAll();
        check("换页后相同矩形必须重新写 surface",
                root.surfaceControl.setTouchableRegionCalls > writesAfterSwitch,
                root.surfaceControl.setTouchableRegionCalls);
        check("换页后区域确实应用", host.appliedRevision == 1L, host.appliedRevision);
        controller.destroy();
    }

    private static String payload(String sessionId, long revision, float cssW, float cssH,
                                  int[][] rects) {
        StringBuilder builder = new StringBuilder();
        builder.append("{\"revision\":").append(revision)
                .append(",\"pageSessionId\":").append(org.json.JSONObject.quote(sessionId))
                .append(",\"cssW\":").append(cssW)
                .append(",\"cssH\":").append(cssH)
                .append(",\"rects\":[");
        for (int i = 0; i < rects.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append('[');
            for (int j = 0; j < 4; j++) {
                if (j > 0) {
                    builder.append(',');
                }
                builder.append(rects[i][j]);
            }
            builder.append(']');
        }
        builder.append("]}");
        return builder.toString();
    }

    // ================= 断言辅助 =================

    private static void check(String name, boolean condition) {
        System.out.println("[" + (condition ? "PASS" : "FAIL") + "] " + name);
        if (!condition) {
            failures++;
        }
    }

    private static void check(String name, boolean condition, Object detail) {
        System.out.println("[" + (condition ? "PASS" : "FAIL") + "] " + name
                + (condition ? "" : " -> " + String.valueOf(detail)));
        if (!condition) {
            failures++;
        }
    }
}
