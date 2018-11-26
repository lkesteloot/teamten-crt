package com.teamten.com.teamten.crt;

import com.teamten.image.BlendingMode;
import picocli.CommandLine;
import com.teamten.image.ImageUtils;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * Converts a small bit map to a large one with a CRT shadow mask.
 */
@CommandLine.Command(mixinStandardHelpOptions = true)
public class ShadowMask implements Runnable {
    private static final int DOTS_PER_INPUT_PIXEL = 4;
    private static final int SLOTS_PER_INPUT_PIXEL = 2;

    enum ShadowMaskType {
        DELTA,
        // https://en.wikipedia.org/wiki/Porta-Color
        INLINE,
    }

    @CommandLine.Parameters(index = "0", paramLabel = "INPUT", description = "Input bitmap.")
    private String mInputPathname;

    @CommandLine.Parameters(index = "1", paramLabel = "OUTPUT", description = "Output bitmap.")
    private String mOutputPathname;

    @CommandLine.Parameters(index = "2", paramLabel = "WIDTH", description = "Output width in pixels.")
    private int mOutputWidth;

    @CommandLine.Option(names = { "-t", "--type" }, paramLabel = "type",
            description = "Type of mask (DELTA, INLINE).")
    private ShadowMaskType mShadowMaskType = ShadowMaskType.DELTA;

    @CommandLine.Option(names = { "-p", "--portrait" }, description = "CRT is mounted portrait.")
    private boolean mPortrait = false;

    private BufferedImage[] mSpots;

    /**
     * Save an image. On error, print the error and exit.
     */
    private void save(BufferedImage image, String pathname) {
        try {
            ImageUtils.save(image, pathname);
        } catch (IOException e) {
            System.err.println("Cannot save file " + pathname + ": " + e.getMessage());
            System.exit(-1);
        }
    }

    /**
     * Make a gaussian spot for an electron beam hitting the phosphor.
     */
    private BufferedImage makeSpot(int value, int channelIndex, int size) {
        BufferedImage spot = ImageUtils.makeTransparent(size, size);

        int channelShift = (2 - channelIndex)*8;
        int color = 0xFF << channelShift;

        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                // Distance from center of spot.
                double dist = Math.sqrt((x - size/2)*(x - size/2) + (y - size/2)*(y - size/2));

                if (dist < size/2) {
                    double normalized = 5*dist/size;
                    // Gaussian.
                    int brightness = (int) (Math.exp(-(normalized*normalized))*value);
                    spot.setRGB(x, y, (brightness << 24) | color);
                }
            }
        }

//        save(spot, "spot.png");

        return spot;
    }

    /**
     * Get a (possibly cached) image for an electron beam spot for the specified
     * brightness, color, and size.
     */
    private BufferedImage getSpot(int value, int channelIndex, int size) {
        if (mSpots == null) {
            mSpots = new BufferedImage[256*3];
        }

        int index = channelIndex*256 + value;
        if (mSpots[index] == null) {
            mSpots[index] = makeSpot(value, channelIndex, size);
        }

        return mSpots[index];
    }

    /**
     * Make a full-size image for the delta mask for a channel. The image is transparent
     * except for the holes, which are white.
     */
    private BufferedImage makeDeltaMaskImage(int width, int height, int zoomFactor, int channelIndex) {
        int horizontalSpacing = zoomFactor/DOTS_PER_INPUT_PIXEL;
        int verticalSpacing = (int) (horizontalSpacing*Math.sqrt(3)/2 + 0.5);
        int dotSize = horizontalSpacing*2/3;

        BufferedImage maskImage = ImageUtils.makeTransparent(width, height);

        Graphics2D g = ImageUtils.createGraphics(maskImage);
        g.setColor(Color.WHITE);

        boolean evenRow = true;
        for (int y = 0; y < height; y += verticalSpacing) {
            int startX = horizontalSpacing*(channelIndex - 3);
            if (!evenRow) {
                startX += horizontalSpacing*3/2;
            }
            for (int x = startX; x < width; x += horizontalSpacing*3) {
                Ellipse2D.Double dot = new Ellipse2D.Double(x, y, dotSize, dotSize);
                g.fill(dot);
            }

            evenRow = !evenRow;
        }

        g.dispose();

//        save(maskImage, "mask" + channelIndex + ".png");
//        System.exit(0);

        return maskImage;
    }

    /**
     * Make a full-size image for the delta mask for a channel. The image is transparent
     * except for the holes, which are white.
     */
    private BufferedImage makeInlineMaskImage(int width, int height, int zoomFactor, int channelIndex) {
        int horizontalSpacing = zoomFactor/3/SLOTS_PER_INPUT_PIXEL;
        int verticalSpacing = zoomFactor/SLOTS_PER_INPUT_PIXEL;
        int dotWidth = (int) (horizontalSpacing*0.9);
        int dotHeight = (int) (verticalSpacing*0.9);
        int dotSize = Math.min(dotWidth, dotHeight);

        BufferedImage maskImage = ImageUtils.makeTransparent(width, height);

        Graphics2D g = ImageUtils.createGraphics(maskImage);
        g.setColor(Color.WHITE);

        boolean evenColumn = true;
        int startX = horizontalSpacing*(channelIndex - 3);
        for (int x = startX; x < width; x += horizontalSpacing*3) {
            int startY = 0;
            if (!evenColumn) {
                startY -= verticalSpacing/2;
            }
            for (int y = startY; y < height; y += verticalSpacing) {
                RoundRectangle2D.Double dot = new RoundRectangle2D.Double(x, y,
                        dotWidth, dotHeight, dotSize, dotSize);
                g.fill(dot);

            }

            evenColumn = !evenColumn;
        }

        g.dispose();

//        save(maskImage, "mask" + channelIndex + ".png");
//        System.exit(0);

        return maskImage;
    }

    /**
     * Make a full-size image for the mask for a channel. The image is transparent
     * except for the holes, which are white.
     */
    private BufferedImage makeMaskImage(int width, int height, int zoomFactor, int channelIndex) {
        switch (mShadowMaskType) {
            case DELTA:
                return makeDeltaMaskImage(width, height, zoomFactor, channelIndex);

            case INLINE:
                return makeInlineMaskImage(width, height, zoomFactor, channelIndex);

            default:
                throw new IllegalStateException();
        }
    }

    /**
     * Get an image for the given channel for the input image. The image is transparent
     * except for the spots.
     */
    private BufferedImage getChannel(BufferedImage inputImage, int channelIndex,
                            int beamDiameter, int zoomFactor, int outputWidth, int outputHeight) {

        int inputWidth = inputImage.getWidth();
        int inputHeight = inputImage.getHeight();

        int spotSize = beamDiameter*3;

        BufferedImage colorImage = ImageUtils.make(outputWidth, outputHeight, Color.BLACK);

        Graphics2D g = ImageUtils.createGraphics(colorImage);
        int channelShift = (2 - channelIndex)*8;

        // Each input image row.
        for (int sy = 0; sy < inputHeight; sy++) {
            // Make a outputWidth x 1 image of the input stretched, and blur it a bit to
            // simulate slow transitions between color values.
            BufferedImage stretchedImage = ImageUtils.make(outputWidth, 1, Color.BLACK);
            for (int dx = 0; dx < outputWidth; dx++) {
                int rgb = inputImage.getRGB(Math.min(dx/zoomFactor, inputWidth - 1), sy);
                stretchedImage.setRGB(dx, 0, rgb);
            }
            stretchedImage = ImageUtils.blur(stretchedImage, zoomFactor*0.1);

            // Draw an electron beam hit every few pixels.
            for (int dx = 0; dx < outputWidth; dx += beamDiameter/5) {
                int rgb = stretchedImage.getRGB(dx, 0);
                int channelValue = (rgb >> channelShift) & 0xFF;

                if (channelValue != 0) {
                    BufferedImage mask = getSpot(channelValue, channelIndex, spotSize);
                    g.drawImage(mask,
                            dx - spotSize/2,
                            sy*zoomFactor + zoomFactor/2 - spotSize/2,
                            null);
                }
            }
        }

        g.dispose();

        BufferedImage maskImage = makeMaskImage(outputWidth, outputHeight, zoomFactor, channelIndex);
        return ImageUtils.clipToMask(colorImage, maskImage);
    }

    @Override
    public void run() {
        BufferedImage inputImage;
        try {
            inputImage = ImageUtils.load(mInputPathname);
        } catch (IOException e) {
            System.err.println("Cannot load file " + mInputPathname + ": " + e.getMessage());
            return;
        }

        if (mPortrait) {
            // Pre-rotate portrait setup.
            inputImage = ImageUtils.rotateRight(inputImage);
        }

        int inputWidth = inputImage.getWidth();
        int inputHeight = inputImage.getHeight();
        int zoomFactor = mOutputWidth/inputWidth;
        int outputHeight = inputHeight*zoomFactor;

        int beamDiameter = (int) (zoomFactor*0.5);

        // Combine masks into a single color image.
        BufferedImage colorImage = ImageUtils.compose(
                ImageUtils.make(mOutputWidth, outputHeight, Color.BLACK),
                getChannel(inputImage, 0, beamDiameter, zoomFactor, mOutputWidth, outputHeight),
                getChannel(inputImage, 1, beamDiameter, zoomFactor, mOutputWidth, outputHeight),
                getChannel(inputImage, 2, beamDiameter, zoomFactor, mOutputWidth, outputHeight)
        );

        // Add bloom.
        BufferedImage outputImage = ImageUtils.pasteAtWith(
                ImageUtils.blur(colorImage, 1),
                ImageUtils.blur(colorImage, zoomFactor/4),
                0, 0, BlendingMode.SCREEN);


        if (mPortrait) {
            // Pre-rotate portrait setup.
            outputImage = ImageUtils.rotateLeft(outputImage);
        }

        save(outputImage, mOutputPathname);
    }

    public static void main(String[] args) {
        CommandLine.run(new ShadowMask(), args);
    }
}
