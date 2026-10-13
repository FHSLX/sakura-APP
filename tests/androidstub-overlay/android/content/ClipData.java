package android.content;

import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

/** 最小 ClipData 替身（测试专用）。 */
public class ClipData {

    public static class Item {
        private final Uri uri;

        public Item(Uri uri) {
            this.uri = uri;
        }

        public Uri getUri() {
            return uri;
        }
    }

    private final List<Item> items = new ArrayList<>();

    public void addItemForTest(Uri uri) {
        items.add(new Item(uri));
    }

    public int getItemCount() {
        return items.size();
    }

    public Item getItemAt(int index) {
        return items.get(index);
    }
}
