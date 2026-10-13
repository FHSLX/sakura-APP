package android.app;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * 最小 Activity 替身（INT-07）。
 *
 * 提供真实 CapturePermissionActivity 需要的生命周期回调、焦点、finish、
 * startActivityForResult 与结果投递；不含任何授权页控制流。
 */
public class Activity extends Context {

    public static final int RESULT_OK = -1;
    public static final int RESULT_CANCELED = 0;

    private Intent intent = new Intent();
    private boolean finishing;
    private boolean destroyed;
    private boolean changingConfigurations;
    private boolean windowFocus;

    public int startActivityForResultCalls;
    public Intent lastRequestIntent;
    public int lastRequestCode;

    protected void onCreate(Bundle savedInstanceState) {
    }

    protected void onStart() {
    }

    protected void onResume() {
    }

    protected void onPause() {
    }

    protected void onStop() {
    }

    protected void onDestroy() {
        destroyed = true;
    }

    public void onWindowFocusChanged(boolean hasFocus) {
    }

    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    }

    protected void onSaveInstanceState(Bundle outState) {
    }

    // ---- 测试/产品共用的系统入口 ------------------------------------

    public void finish() {
        finishing = true;
    }

    public boolean isFinishing() {
        return finishing;
    }

    public boolean isDestroyed() {
        return destroyed;
    }

    public boolean isChangingConfigurations() {
        return changingConfigurations;
    }

    public boolean hasWindowFocus() {
        return windowFocus;
    }

    public Intent getIntent() {
        return intent;
    }

    public void setIntent(Intent intent) {
        this.intent = intent == null ? new Intent() : intent;
    }

    public void startActivityForResult(Intent request, int requestCode) {
        startActivityForResultCalls++;
        lastRequestIntent = request;
        lastRequestCode = requestCode;
    }

    /** AppCaptureYield 让位路径：把整个任务收到后台。 */
    public int moveTaskToBackCalls;
    public int taskIdForTest = 1001;

    public boolean moveTaskToBack(boolean nonRoot) {
        moveTaskToBackCalls++;
        return true;
    }

    public int getTaskId() {
        return taskIdForTest;
    }

    // ---- 测试控制 ----------------------------------------------------

    public void setWindowFocusForTest(boolean focus) {
        windowFocus = focus;
    }

    public void setChangingConfigurationsForTest(boolean changing) {
        changingConfigurations = changing;
    }
}
