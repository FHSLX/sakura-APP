package com.sakura.remote;

/**
 * CaptureRequestRegistry 的行为测试（普通 JVM，无需 Android/JUnit）。
 *
 * 验证 R2-2 的核心规则：
 *   - 单活动请求：忙时的不再开始，不排队、不覆盖；
 *   - 陈旧/迟到结果隔离：requestId 不匹配的终结一律无效；
 *   - 恰好一次终结：重复调用不改变已记录结果；
 *   - 页面会话取消只影响匹配会话；
 *   - 结果记录有界（TTL 过后读不到）。
 */
public final class CaptureRequestRegistryTest {

    private static int failures = 0;

    public static void main(String[] args) {
        singleActiveRequest();
        lateResultIgnored();
        exactlyOnceFinish();
        pageSessionCancel();
        boundedResult();
        fullStateSequence();
        failurePathsRecordFailed();
        System.out.println();
        if (failures > 0) {
            System.err.println(failures + " 项失败");
            System.exit(1);
        }
        System.out.println("PASS");
    }

    /** 正常链路：IDLE→CONSENT→STARTING_SERVICE→WAITING_FRAME→COMPLETED。 */
    private static void fullStateSequence() {
        CaptureRequestRegistry registry = new CaptureRequestRegistry();
        CaptureRequestRegistry.Request request = registry.begin("APP", "page-5", 6000L);
        check("链路起点是 CONSENT",
                CaptureRequestRegistry.STATE_CONSENT.equals(request.state()));
        check("推进到 STARTING_SERVICE", registry.setState(request.requestId,
                CaptureRequestRegistry.STATE_STARTING_SERVICE));
        check("推进到 WAITING_FRAME", registry.setState(request.requestId,
                CaptureRequestRegistry.STATE_WAITING_FRAME));
        check("错误 requestId 不推进状态", !registry.setState("app-999-x",
                CaptureRequestRegistry.STATE_COMPLETED));
        check("状态未被别人的 id 改动", CaptureRequestRegistry.STATE_WAITING_FRAME
                .equals(registry.active().state()));
        registry.finish(request.requestId, CaptureRequestRegistry.STATE_COMPLETED, "");
        check("完成结果 success=true",
                registry.lastResult() != null && registry.lastResult().success);
        check("完成结果错误为空",
                registry.lastResult() != null && registry.lastResult().error.isEmpty());
    }

    /** 超时 / 无帧 / 服务被停止都走 FAILED，且错误码保留。 */
    private static void failurePathsRecordFailed() {
        String[] errors = {"等待画面超时，请重试", "截图服务已停止", "系统停止了屏幕共享"};
        for (String error : errors) {
            CaptureRequestRegistry registry = new CaptureRequestRegistry();
            CaptureRequestRegistry.Request request = registry.begin("OVERLAY", "page-6", 7000L);
            registry.setState(request.requestId, CaptureRequestRegistry.STATE_WAITING_FRAME);
            registry.finish(request.requestId, CaptureRequestRegistry.STATE_FAILED, error);
            check("失败路径记录 FAILED(" + error + ")", registry.lastResult() != null
                    && CaptureRequestRegistry.STATE_FAILED.equals(registry.lastResult().state)
                    && error.equals(registry.lastResult().error)
                    && !registry.lastResult().success);
        }
    }

    private static void singleActiveRequest() {
        CaptureRequestRegistry registry = new CaptureRequestRegistry();
        CaptureRequestRegistry.Request first = registry.begin("APP", "page-1", 1000L);
        check("begin 返回请求", first != null);
        check("初始状态是 CONSENT",
                first != null && CaptureRequestRegistry.STATE_CONSENT.equals(first.state()));
        check("isBusy 为 true", registry.isBusy());
        CaptureRequestRegistry.Request second = registry.begin("OVERLAY", "page-2", 1001L);
        check("忙时第二次 begin 返回 null", second == null);
        check("活动请求仍是第一个",
                registry.active() == first);
        check("isActive 认自己的 id", registry.isActive(first.requestId));
        check("isActive 拒绝别人的 id", !registry.isActive("overlay-999-zzz"));
    }

    private static void lateResultIgnored() {
        CaptureRequestRegistry registry = new CaptureRequestRegistry();
        CaptureRequestRegistry.Request request = registry.begin("OVERLAY", "page-9", 2000L);
        registry.setState(request.requestId, CaptureRequestRegistry.STATE_WAITING_FRAME);
        check("状态推进到 WAITING_FRAME",
                CaptureRequestRegistry.STATE_WAITING_FRAME.equals(registry.active().state()));

        CaptureRequestRegistry.Request stale =
                registry.finish("overlay-123-stale", CaptureRequestRegistry.STATE_COMPLETED, "");
        check("陈旧 requestId 不能终结", stale == null);
        check("活动请求仍在进行", registry.isBusy());
        check("陈旧终结没写入结果", registry.lastResult() == null);

        CaptureRequestRegistry.Request done = registry.finish(
                request.requestId, CaptureRequestRegistry.STATE_FAILED, "超时");
        check("匹配 requestId 可以终结", done == request);
        check("结果状态正确", registry.lastResult() != null
                && CaptureRequestRegistry.STATE_FAILED.equals(registry.lastResult().state));
        check("失败结果 success=false", registry.lastResult() != null
                && !registry.lastResult().success);
        check("终结后不再忙", !registry.isBusy());
    }

    private static void exactlyOnceFinish() {
        CaptureRequestRegistry registry = new CaptureRequestRegistry();
        CaptureRequestRegistry.Request request = registry.begin("APP", "page-3", 3000L);
        registry.finish(request.requestId, CaptureRequestRegistry.STATE_COMPLETED, "");
        CaptureRequestRegistry.Result first = registry.lastResult();
        CaptureRequestRegistry.Request again = registry.finish(
                request.requestId, CaptureRequestRegistry.STATE_FAILED, "迟到的错误");
        check("重复终结返回 null", again == null);
        check("重复终结不覆盖结果", registry.lastResult() == first);

        // 终结之后可以开始下一次，且 requestId 不重复
        CaptureRequestRegistry.Request next = registry.begin("APP", "page-3", 3001L);
        check("终结后可以开始新请求", next != null);
        check("新请求 id 与旧的不同",
                next != null && !next.requestId.equals(request.requestId));
    }

    private static void pageSessionCancel() {
        CaptureRequestRegistry registry = new CaptureRequestRegistry();
        CaptureRequestRegistry.Request request = registry.begin("OVERLAY", "page-A", 4000L);
        check("不匹配的会话取消无效",
                registry.cancelPageSession("page-B") == null);
        check("活动请求未被误取消", registry.isActive(request.requestId));
        CaptureRequestRegistry.Request cancelled = registry.cancelPageSession("page-A");
        check("匹配会话被取消", cancelled == request);
        check("取消后不再忙", !registry.isBusy());
        check("取消结果状态是 CANCELLED", registry.lastResult() != null
                && CaptureRequestRegistry.STATE_CANCELLED.equals(registry.lastResult().state));
    }

    private static void boundedResult() {
        CaptureRequestRegistry registry = new CaptureRequestRegistry(-1L);
        CaptureRequestRegistry.Request request = registry.begin("APP", "page-4", 5000L);
        registry.finish(request.requestId, CaptureRequestRegistry.STATE_COMPLETED, "");
        check("TTL 到期后结果记录被清理", registry.lastResult() == null);
    }

    private static void check(String name, boolean condition) {
        System.out.println("[" + (condition ? "PASS" : "FAIL") + "] " + name);
        if (!condition) {
            failures++;
        }
    }
}
