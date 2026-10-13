package android.graphics;

import java.util.ArrayList;
import java.util.List;

/** 最小 Region 替身：记录 op(UNION) 合并进来的矩形，供测试断言。 */
public class Region {

    public enum Op {
        UNION,
        INTERSECT,
        DIFFERENCE,
        REPLACE,
        REVERSE_DIFFERENCE
    }

    private final List<int[]> rects = new ArrayList<>();

    public Region() {
    }

    public Region(int left, int top, int right, int bottom) {
        rects.add(new int[]{left, top, right, bottom});
    }

    public boolean isEmpty() {
        return rects.isEmpty();
    }

    public boolean op(Rect rect, Op op) {
        if (rect == null) {
            return false;
        }
        if (op == Op.UNION) {
            rects.add(new int[]{rect.left, rect.top, rect.right, rect.bottom});
        }
        return true;
    }

    /** 测试观察用：本次 Region 里包含的矩形（顺序即 op 调用顺序）。 */
    public List<int[]> rectsForTest() {
        return rects;
    }

    /** 真实 API 的最小替身：合并后区域的包围盒（供内容签名使用）。 */
    public Rect getBounds() {
        if (rects.isEmpty()) {
            return new Rect();
        }
        int left = Integer.MAX_VALUE;
        int top = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        int bottom = Integer.MIN_VALUE;
        for (int[] rect : rects) {
            left = Math.min(left, rect[0]);
            top = Math.min(top, rect[1]);
            right = Math.max(right, rect[2]);
            bottom = Math.max(bottom, rect[3]);
        }
        return new Rect(left, top, right, bottom);
    }
}
