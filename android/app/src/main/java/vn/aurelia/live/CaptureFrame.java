package vn.aurelia.live;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.media.Image;

import java.nio.ByteBuffer;
import java.util.List;

final class CaptureFrame {
    private static final int COLUMNS = 34;
    private static final int ROWS = 56;
    private static final int COLOR_TOLERANCE = 27;

    private CaptureFrame() {}

    static int[] sample(Image image, Rect area) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer pixels = plane.getBuffer();
        int stride = plane.getRowStride();
        int step = plane.getPixelStride();
        int[] grid = new int[COLUMNS * ROWS];
        for (int row = 0; row < ROWS; row++) {
            int start = y(area, row) * stride;
            for (int column = 0; column < COLUMNS; column++) {
                int pixel = start + x(area, column) * step;
                grid[row * COLUMNS + column] = ((pixels.get(pixel) & 255) << 16)
                        | ((pixels.get(pixel + 1) & 255) << 8) | (pixels.get(pixel + 2) & 255);
            }
        }
        return grid;
    }

    static boolean changed(int[] previous, int[] current, Rect area, List<OverlayLayer.Entry> overlay) {
        if (previous == null) return true;
        int different = 0;
        for (int row = 0; row < ROWS; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                int index = row * COLUMNS + column;
                if (distance(previous[index], current[index]) < COLOR_TOLERANCE) continue;
                int x = x(area, column);
                int y = y(area, row);
                boolean covered = false;
                for (OverlayLayer.Entry entry : overlay) {
                    if (entry.box.contains(x, y)) {
                        covered = true;
                        break;
                    }
                }
                if (!covered && ++different >= 6) return true;
            }
        }
        return false;
    }

    static Bitmap toBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        int width = image.getWidth();
        int height = image.getHeight();
        int stride = plane.getRowStride();
        int step = plane.getPixelStride();
        ByteBuffer pixels = plane.getBuffer();
        pixels.rewind();
        Bitmap screen = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        if (step == 4 && stride == width * 4 && pixels.remaining() >= stride * height) {
            screen.copyPixelsFromBuffer(pixels);
            return screen;
        }
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            int position = y * stride;
            for (int x = 0; x < width; x++) {
                int pixel = position + x * step;
                row[x] = 0xff000000 | ((pixels.get(pixel) & 255) << 16)
                        | ((pixels.get(pixel + 1) & 255) << 8) | (pixels.get(pixel + 2) & 255);
            }
            screen.setPixels(row, 0, width, 0, y, width, 1);
        }
        return screen;
    }

    private static int distance(int a, int b) {
        return Math.max(Math.abs((a >> 16) - (b >> 16)),
                Math.max(Math.abs((a >> 8 & 255) - (b >> 8 & 255)), Math.abs((a & 255) - (b & 255))));
    }

    private static int x(Rect area, int column) {
        return area.left + (2 * column + 1) * area.width() / (2 * COLUMNS);
    }

    private static int y(Rect area, int row) {
        return area.top + (2 * row + 1) * area.height() / (2 * ROWS);
    }
}
