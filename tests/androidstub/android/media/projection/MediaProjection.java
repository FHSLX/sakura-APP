package android.media.projection;

import android.hardware.display.VirtualDisplay;
import android.os.Handler;
import android.view.Surface;

/**
 * 最小 MediaProjection 替身（INT-07）。
 *
 * 记录真实 CaptureService 注册的回调（用于 INT-06「旧 projection.onStop 不得
 * 终结替换后的新 run」），并统计 createVirtualDisplay 的调用次数。
 */
public class MediaProjection {

    public abstract static class Callback {
        public void onStop() {
        }
    }

    public Callback registeredCallback;
    public int createVirtualDisplayCalls;
    public int stopCalls;
    public int unregisterCallbackCalls;
    /** 测试控制：为 true 时 registerCallback 抛异常（INT-05）。 */
    public boolean throwOnRegisterCallback;

    public void registerCallback(Callback callback, Handler handler) {
        if (throwOnRegisterCallback) {
            throw new IllegalStateException("registerCallback rejected");
        }
        registeredCallback = callback;
    }

    public void unregisterCallback(Callback callback) {
        unregisterCallbackCalls++;
        if (registeredCallback == callback) {
            registeredCallback = null;
        }
    }

    public VirtualDisplay createVirtualDisplay(String name, int width, int height, int densityDpi,
                                               int flags, Surface surface, Callback callback,
                                               Handler handler) {
        createVirtualDisplayCalls++;
        VirtualDisplay.countCreationForTest();
        return new VirtualDisplay();
    }

    public void stop() {
        stopCalls++;
    }
}
