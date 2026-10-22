package kr.ac.sejong.cedpt.cctvconsent.consent;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * 브라우저 서명 패드가 보낸 PNG(data URL)를 검증한다. 빈 서명, PNG가 아닌 값, 지나치게 큰 이미지를 거부한다.
 */
final class SignatureImage {

	private static final String PREFIX = "data:image/png;base64,";
	private static final int MAX_BYTES = 500_000;
	private static final int MAX_SIDE = 3000;
	// 이 수 이상의 픽셀에 잉크가 있어야 서명으로 본다 (점 하나 찍기 방지)
	private static final int MIN_INK_PIXELS = 150;

	private SignatureImage() {
	}

	/**
	 * 헤더의 가로세로를 먼저 보고 MAX_SIDE 안일 때만 픽셀을 읽는다. 작은 파일에 큰 크기를 적은 PNG를 그대로 읽으면
	 * 디코더가 그 크기만큼 메모리를 먼저 잡아 서버가 멈출 수 있다.
	 *
	 * @return PNG가 아니거나 크기가 넘으면 null
	 */
	private static BufferedImage decode(byte[] png) {
		try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(png))) {
			Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("png");
			if (in == null || !readers.hasNext()) {
				return null;
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(in, true, true);
				if (reader.getWidth(0) > MAX_SIDE || reader.getHeight(0) > MAX_SIDE) {
					return null;
				}
				return reader.read(0);
			}
			finally {
				reader.dispose();
			}
		}
		catch (IOException | RuntimeException ex) {
			return null;
		}
	}

	static byte[] fromDataUrl(String dataUrl) {
		if (dataUrl == null || !dataUrl.startsWith(PREFIX)) {
			throw new ConsentException("consent.error.signature");
		}
		byte[] png;
		try {
			png = Base64.getDecoder().decode(dataUrl.substring(PREFIX.length()));
		}
		catch (IllegalArgumentException ex) {
			throw new ConsentException("consent.error.signatureInvalid");
		}
		if (png.length > MAX_BYTES) {
			throw new ConsentException("consent.error.signatureInvalid");
		}
		BufferedImage image = decode(png);
		if (image == null) {
			throw new ConsentException("consent.error.signatureInvalid");
		}
		if (inkPixels(image) < MIN_INK_PIXELS) {
			throw new ConsentException("consent.error.signature");
		}
		return png;
	}

	/** 불투명하고 어두운 픽셀 수. 서명 패드 배경은 투명이다. */
	private static int inkPixels(BufferedImage image) {
		int count = 0;
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				int argb = image.getRGB(x, y);
				int alpha = argb >>> 24;
				int luminance = ((argb >> 16 & 0xff) + (argb >> 8 & 0xff) + (argb & 0xff)) / 3;
				if (alpha > 128 && luminance < 160 && ++count >= MIN_INK_PIXELS) {
					return count;
				}
			}
		}
		return count;
	}

}
