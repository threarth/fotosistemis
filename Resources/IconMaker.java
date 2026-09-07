import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Cuts the launcher icon out of splash.png.
 *
 * Build tool, not part of the app: run it by hand when the artwork changes
 * (see README.md beside it). It writes the adaptive-icon foreground at the
 * five densities and a preview of how launchers will mask it. The artwork
 * is cropped inside its own border and its outer band is faded to
 * transparent, so the edge melts into the gradient background drawn by
 * ic_launcher_sky.xml instead of showing as a seam.
 */
public class IconMaker {

    /** Adaptive icon canvas, in dp, and its size at each density. */
    private static final int CANVAS_DP = 108;
    private static final int[] CANVAS_PX = {108, 162, 216, 324, 432};
    private static final String[] DENSITIES = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};

    /** The preview shows the 72 dp launchers reveal, at xxxhdpi. */
    private static final int PREVIEW_ICON_PX = 288;
    private static final int PREVIEW_MARGIN_PX = 75;
    private static final int PREVIEW_SQUIRCLE_RADIUS_PX = 100;

    public static void main(String[] args) throws Exception {
        BufferedImage source = ImageIO.read(new File(args[0]));
        if (args[1].equals("probe")) {
            probe(source);
            return;
        }

        File outDir = new File(args[2]);
        int x0 = Integer.parseInt(args[3]);
        int y0 = Integer.parseInt(args[4]);
        int side = Integer.parseInt(args[5]);
        int featherPx = Integer.parseInt(args[6]);
        double artDp = Double.parseDouble(args[7]);
        String[] sky = args[8].split(",");
        Color top = new Color(Integer.parseInt(sky[0], 16));
        Color bottom = new Color(Integer.parseInt(sky[1], 16));

        for (int i = 0; i < CANVAS_PX.length; i++) {
            BufferedImage fg = foreground(source, x0, y0, side, featherPx, artDp, CANVAS_PX[i]);
            File dir = new File(outDir, "mipmap-" + DENSITIES[i]);
            dir.mkdirs();
            ImageIO.write(fg, "png", new File(dir, "ic_launcher_foreground.png"));
        }

        int largest = CANVAS_PX[CANVAS_PX.length - 1];
        BufferedImage fg = foreground(source, x0, y0, side, featherPx, artDp, largest);
        ImageIO.write(preview(fg, top, bottom), "png", new File(args[9]));
    }

    /** The artwork centred on a transparent canvas, spanning artDp of its 108 dp. */
    static BufferedImage foreground(
        BufferedImage source, int x0, int y0, int side, int featherPx, double artDp, int canvas
    ) {
        BufferedImage fg = new BufferedImage(canvas, canvas, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = fg.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        double scale = artDp / CANVAS_DP * canvas / side;
        double span = side * scale;
        double offset = (canvas - span) / 2;
        g.setClip(new Rectangle2D.Double(offset, offset, span, span));
        g.drawImage(
            source.getSubimage(x0, y0, side, side),
            (int) Math.round(offset), (int) Math.round(offset),
            (int) Math.round(span), (int) Math.round(span), null
        );
        g.dispose();

        feather(fg, offset, span, featherPx * scale);
        return fg;
    }

    /** Fades the outer band of the artwork to transparent. */
    static void feather(BufferedImage fg, double offset, double span, double band) {
        for (int y = 0; y < fg.getHeight(); y++) {
            for (int x = 0; x < fg.getWidth(); x++) {
                double dx = Math.min(x - offset, offset + span - 1 - x);
                double dy = Math.min(y - offset, offset + span - 1 - y);
                double distance = Math.min(dx, dy);
                if (distance >= band) continue;

                int argb = fg.getRGB(x, y);
                int alpha = (argb >>> 24) & 0xFF;
                int faded = (int) Math.round(alpha * Math.max(0, distance) / band);
                fg.setRGB(x, y, (faded << 24) | (argb & 0xFFFFFF));
            }
        }
    }

    /** The icon under a circle and a squircle mask, side by side, on white. */
    static BufferedImage preview(BufferedImage fg, Color top, Color bottom) {
        int cell = PREVIEW_ICON_PX + 2 * PREVIEW_MARGIN_PX;
        BufferedImage out = new BufferedImage(2 * cell, cell, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());

        Shape circle = new Ellipse2D.Double(
            PREVIEW_MARGIN_PX, PREVIEW_MARGIN_PX, PREVIEW_ICON_PX, PREVIEW_ICON_PX
        );
        Shape squircle = new RoundRectangle2D.Double(
            cell + PREVIEW_MARGIN_PX, PREVIEW_MARGIN_PX, PREVIEW_ICON_PX, PREVIEW_ICON_PX,
            PREVIEW_SQUIRCLE_RADIUS_PX, PREVIEW_SQUIRCLE_RADIUS_PX
        );
        int bleed = (fg.getWidth() - PREVIEW_ICON_PX) / 2;
        for (Shape mask : new Shape[] {circle, squircle}) {
            Rectangle2D bounds = mask.getBounds2D();
            g.setClip(mask);
            g.setPaint(new GradientPaint(0, (float) bounds.getMinY(), top, 0, (float) bounds.getMaxY(), bottom));
            g.fill(mask);
            g.drawImage(fg, (int) bounds.getMinX() - bleed, (int) bounds.getMinY() - bleed, null);
        }
        g.dispose();
        return out;
    }

    /** Prints the colours along the left and top edges, to find where the artwork starts. */
    static void probe(BufferedImage source) {
        int mid = source.getHeight() / 2;
        int step = 5;
        int reach = 200;
        for (int x = 0; x < reach; x += step) {
            System.out.printf("x=%d %06X  ", x, source.getRGB(x, mid) & 0xFFFFFF);
        }
        System.out.println();
        for (int y = 0; y < reach; y += step) {
            System.out.printf("y=%d %06X  ", y, source.getRGB(mid, y) & 0xFFFFFF);
        }
        System.out.println();
    }
}
