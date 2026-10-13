package com.sakura.remote;

/**
 * 渲染可用性信号的**主线程落点**（FIX-RUNTIME-01）。
 *
 * 为什么需要这个接口：
 *   Android 的 {@code @JavascriptInterface} 回调运行在 WebView 的**桥线程**，
 *   不是主线程。旧实现里 RemoteBridge → ServiceBridgeHost → OverlayService
 *   一路同步直调，最后直接改 PetWindow 的 WebView Visibility 并同步
 *   reconcile WindowManager 的 addView/removeView —— 在桥线程上做 View 与
 *   WindowManager 工作，属于「只在创建视图的线程上触碰视图层次」的确定性违规，
 *   Android 会抛 CalledFromWrongThreadException 或静默错乱。现场表现就是
 *   「网页已经上报立绘可见，窗口却始终没挂出来」。
 *
 * 因此这里定义**只允许在主线程执行**的落点：{@link RenderSignalRouter}
 * 负责封送（把桥线程调用 post 到主线程），本接口的实现体只管 UI。
 *
 * 实现约束（由 RenderSignalRouter 保证）：
 *   - 所有方法只在主线程被调用；
 *   - 参数只含页面会话 id 与去敏结果码，绝不含 URL/token/聊天/图片内容。
 */
public interface RenderSignalSink {

    /** 网页 boot / 导航开始：推进渲染代际，窗口回到有界加载卡片。 */
    void onRenderPageSession(String pageSessionId);

    /** 网页 boot 完成（DOM 与桥就绪，立绘可能仍在解码）。 */
    void onRenderBootOk(String pageSessionId);

    /** 网页主动上报 boot 失败。 */
    void onRenderBootFailed(String pageSessionId);

    /** 确认有可见且已解码的立绘：唯一允许挂完整窗口的依据。 */
    void onRenderPortraitVisible(String pageSessionId);

    /** 网页 pagehide / 导航开始：注销会话。 */
    void onInvalidatePageSession(String pageSessionId);
}
