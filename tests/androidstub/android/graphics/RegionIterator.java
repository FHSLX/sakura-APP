package android.graphics;

/**
 * 最小 RegionIterator 替身（真实 API 语义：按 y 升序、逐 span 迭代 Region）。
 *
 * 只实现 {@link #next(Rect)}：本项目的使用方是
 * {@code OverlayTouchRegionController.regionSignature}，它只需要
 * 「按稳定顺序取出所有 span」。这里按 op(UNION) 的登记顺序迭代
 *（真实 Region 会先规范化再迭代，但同一份矩形集合的顺序在本替身里同样稳定，
 * 足以验证「同一份区域 → 同一签名」）。
 */
public class RegionIterator {

    private final java.util.List<int[]> spans;
    private int index;

    public RegionIterator(Region region) {
        this.spans = region == null ? new java.util.ArrayList<>() : region.rectsForTest();
    }

    public boolean next(Rect rect) {
        if (rect == null || index >= spans.size()) {
            return false;
        }
        int[] span = spans.get(index);
        index++;
        rect.set(span[0], span[1], span[2], span[3]);
        return true;
    }
}
