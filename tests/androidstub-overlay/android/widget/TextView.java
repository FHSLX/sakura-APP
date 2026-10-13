package android.widget;

import android.content.Context;
import android.view.View;

/** 最小 TextView 替身：保存文本，供卡片文案断言。 */
public class TextView extends View {

    private CharSequence text = "";
    public float textSize;
    public int textColor;
    public boolean singleLine;
    public int maxLines;
    public CharSequence hint;

    public TextView(Context context) {
        super(context);
    }

    public void setText(CharSequence text) {
        this.text = text == null ? "" : text;
    }

    public CharSequence getText() {
        return text;
    }

    public void setTextColor(int color) {
        this.textColor = color;
    }

    public void setTextSize(float size) {
        this.textSize = size;
    }

    public void setSingleLine(boolean singleLine) {
        this.singleLine = singleLine;
    }

    public void setMaxLines(int maxLines) {
        this.maxLines = maxLines;
    }

    public void setPadding(int left, int top, int right, int bottom) {
    }
}
