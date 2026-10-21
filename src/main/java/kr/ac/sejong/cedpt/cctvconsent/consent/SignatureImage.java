package kr.ac.sejong.cedpt.cctvconsent.consent;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import javax.imageio.ImageIO;

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

	static byte[] fromDataUrl(String dataUrl) {
		if (dataUrl == null || !dataUrl.startsWith(PREFIX)) {
			throw new ConsentException("서명을 입력하세요.");
		}
		byte[] png;
		try {
			png = Base64.getDecoder().decode(dataUrl.substring(PREFIX.length()));
		}
		catch (IllegalArgumentException ex) {
			throw new ConsentException("서명 이미지를 읽을 수 없습니다. 다시 서명하세요.");
		}
		if (png.length > MAX_BYTES) {
			throw new ConsentException("서명 이미지가 너무 큽니다. 다시 서명하세요.");
		}
		BufferedImage image;
		try {
			image = ImageIO.read(new ByteArrayInputStream(png));
		}
		catch (IOException ex) {
			image = null;
		}
		if (image == null || image.getWidth() > MAX_SIDE || image.getHeight() > MAX_SIDE) {
			throw new ConsentException("서명 이미지를 읽을 수 없습니다. 다시 서명하세요.");
		}
		if (inkPixels(image) < MIN_INK_PIXELS) {
			throw new ConsentException("서명을 입력하세요.");
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
