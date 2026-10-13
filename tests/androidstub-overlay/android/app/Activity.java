package android.app;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** 最小 Activity 替身（测试专用）：覆盖文件选择辅助页用到的生命周期。 */
public class Activity extends Context {

    public static final int RESULT_OK = -1;
    public static final int RESULT_CANCELED = 0;

    private Intent intent = new Intent();
    private boolean finishing;

    protected void onCreate(Bundle savedInstanceState) {
    }

    protected void onNewIntent(Intent newIntent) {
    }

    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    }

    protected void onSaveInstanceState(Bundle outState) {
    }

    protected void onDestroy() {
    }

    public void onBackPressed() {
    }

    public Intent getIntent() {
        return intent;
    }

    public void setIntent(Intent value) {
        intent = value == null ? new Intent() : value;
    }

    /** 记录最近一次 startActivityForResult（测试断言相机/图库接线用）。 */
    public Intent lastActivityResultIntent;
    public int lastActivityRequestCode = -1;

    public void startActivityForResult(Intent request, int requestCode) {
        lastActivityResultIntent = request;
        lastActivityRequestCode = requestCode;
    }

    public void setResult(int resultCode, Intent data) {
    }

    public void finish() {
        finishing = true;
    }

    public boolean isFinishing() {
        return finishing;
    }

    public void runOnUiThread(Runnable action) {
        if (action != null) {
            action.run();
        }
    }
}
