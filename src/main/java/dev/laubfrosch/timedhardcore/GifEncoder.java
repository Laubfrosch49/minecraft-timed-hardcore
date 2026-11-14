package dev.laubfrosch.timedhardcore;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import org.jspecify.annotations.Nullable;

/**
 * Writes an animated GIF that loops forever, using only Java's own image library. Discord plays such a GIF
 * right inside a message, which is why death clips are GIFs and not videos.
 */
public final class GifEncoder {

	private static final String METADATA_FORMAT = "javax_imageio_gif_image_1.0";
	/** GIF delays are in hundredths of a second; most viewers treat anything below 2 as "as fast as possible". */
	private static final int MIN_DELAY = 2;
	/** The last frame stays a little longer before the clip starts over. */
	private static final int LAST_FRAME_DELAY = 150;

	/**
	 * One picture of the clip.
	 *
	 * @param rgb pixels row by row from the top, as 0xRRGGBB
	 * @param timeMillis when the picture was taken; the gaps between frames become the delays of the GIF
	 */
	public record Frame(int width, int height, int[] rgb, long timeMillis) {
	}

	private GifEncoder() {
	}

	/**
	 * Encodes the frames, leaving out frames until the GIF fits: first every frame, then every second one,
	 * then every third. Frames with a different size than the last one (window resized) are dropped.
	 *
	 * @return the GIF, or null if there are no frames or it is too large even with every third frame
	 */
	public static byte @Nullable [] encode(List<Frame> frames, int maxBytes) throws IOException {
		if (frames.isEmpty()) {
			return null;
		}
		Frame last = frames.getLast();
		List<Frame> sameSize = frames.stream().filter(frame -> frame.width() == last.width() && frame.height() == last.height()).toList();
		for (int step = 1; step <= 3; step++) {
			byte[] gif = encode(everyNth(sameSize, step));
			if (gif.length <= maxBytes) {
				return gif;
			}
		}
		return null;
	}

	/** Encodes all frames; they must all have the same size. */
	public static byte[] encode(List<Frame> frames) throws IOException {
		ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
			writer.setOutput(stream);
			writer.prepareWriteSequence(null);
			for (int i = 0; i < frames.size(); i++) {
				Frame frame = frames.get(i);
				BufferedImage image = new BufferedImage(frame.width(), frame.height(), BufferedImage.TYPE_INT_RGB);
				image.setRGB(0, 0, frame.width(), frame.height(), frame.rgb(), 0, frame.width());

				int delay = i + 1 < frames.size()
					? (int) Math.max(MIN_DELAY, (frames.get(i + 1).timeMillis() - frame.timeMillis()) / 10)
					: LAST_FRAME_DELAY;
				// The writer reduces each frame to its own palette of 256 colours
				IIOMetadata metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), null);
				metadata.mergeTree(METADATA_FORMAT, frameMetadata(delay, i == 0));
				writer.writeToSequence(new IIOImage(image, null, metadata), null);
			}
			writer.endWriteSequence();
		} finally {
			writer.dispose();
		}
		return out.toByteArray();
	}

	/** Delay of the frame; the first frame also says that the GIF loops forever. */
	private static IIOMetadataNode frameMetadata(int delay, boolean first) {
		IIOMetadataNode root = new IIOMetadataNode(METADATA_FORMAT);

		IIOMetadataNode control = new IIOMetadataNode("GraphicControlExtension");
		control.setAttribute("disposalMethod", "none");
		control.setAttribute("userInputFlag", "FALSE");
		control.setAttribute("transparentColorFlag", "FALSE");
		control.setAttribute("delayTime", Integer.toString(delay));
		control.setAttribute("transparentColorIndex", "0");
		root.appendChild(control);

		if (first) {
			IIOMetadataNode extensions = new IIOMetadataNode("ApplicationExtensions");
			IIOMetadataNode loop = new IIOMetadataNode("ApplicationExtension");
			loop.setAttribute("applicationID", "NETSCAPE");
			loop.setAttribute("authenticationCode", "2.0");
			// Sub-block 1, loop count 0 = forever
			loop.setUserObject(new byte[]{1, 0, 0});
			extensions.appendChild(loop);
			root.appendChild(extensions);
		}
		return root;
	}

	private static List<Frame> everyNth(List<Frame> frames, int step) {
		if (step == 1) {
			return frames;
		}
		List<Frame> result = new ArrayList<>();
		// Counted from the end, so the moment of death is always included
		for (int i = frames.size() - 1; i >= 0; i -= step) {
			result.addFirst(frames.get(i));
		}
		return result;
	}

	/**
	 * Reads the structure of a GIF without decoding the pictures.
	 *
	 * @return the number of frames, or -1 if the data is not a complete GIF or anything in it is larger than
	 *     {@code maxWidth} x {@code maxHeight}
	 */
	public static int countFrames(byte[] data, int maxWidth, int maxHeight) {
		if (!isGif(data) || data.length < 13) {
			return -1;
		}
		if (unsignedShort(data, 6) > maxWidth || unsignedShort(data, 8) > maxHeight) {
			return -1;
		}
		int position = 13 + colorTableBytes(data[10]);
		int frames = 0;
		while (position < data.length) {
			int block = data[position++] & 0xFF;
			if (block == 0x3B) {
				// Trailer: the end of the GIF
				return frames;
			} else if (block == 0x21) {
				// Extension: a label, then data
				position = skipSubBlocks(data, position + 1);
			} else if (block == 0x2C && position + 9 <= data.length) {
				// Picture: position and size, an optional colour table, the code size, then data
				if (unsignedShort(data, position) + unsignedShort(data, position + 4) > maxWidth
					|| unsignedShort(data, position + 2) + unsignedShort(data, position + 6) > maxHeight) {
					return -1;
				}
				frames++;
				position = skipSubBlocks(data, position + 9 + colorTableBytes(data[position + 8]) + 1);
			} else {
				return -1;
			}
			if (position < 0) {
				return -1;
			}
		}
		return -1;
	}

	private static int unsignedShort(byte[] data, int position) {
		return data[position] & 0xFF | (data[position + 1] & 0xFF) << 8;
	}

	/** Size of the colour table announced by a flags byte, 0 if there is none. */
	private static int colorTableBytes(byte flags) {
		return (flags & 0x80) == 0 ? 0 : 3 << (flags & 7) + 1;
	}

	/** Skips a chain of data blocks (each starts with its length, a length of 0 ends the chain); -1 if it runs past the end. */
	private static int skipSubBlocks(byte[] data, int position) {
		while (position >= 0 && position < data.length) {
			int size = data[position] & 0xFF;
			position += size + 1;
			if (size == 0) {
				return position;
			}
		}
		return -1;
	}

	/** Does the data start like a GIF? */
	public static boolean isGif(byte[] data) {
		return data.length > 6 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F' && data[3] == '8' && data[5] == 'a';
	}
}
