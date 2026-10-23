package kr.ac.sejong.cedpt.cctvconsent.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SignatureImageTest {

	@Test
	void acceptsDrawnSignature() {
		byte[] png = SignatureFixtures.png();

		assertThat(SignatureImage.fromDataUrl(SignatureFixtures.dataUrl(png))).isEqualTo(png);
	}

	@Test
	void rejectsBlankSignature() {
		assertThatThrownBy(() -> SignatureImage.fromDataUrl(SignatureFixtures.dataUrl(SignatureFixtures.blankPng())))
				.isInstanceOf(ConsentException.class);
	}

	@Test
	void rejectsHugeCanvasEvenWhenSidesAreWithinLimit() {
		// 한 변은 2500 이하지만 넓이가 120만 픽셀을 넘는다. 서명 패드는 가장 넓은 화면에서도 약 83만 픽셀이다
		assertThatThrownBy(() -> SignatureImage.fromDataUrl(SignatureFixtures.dataUrl(SignatureFixtures.png(2400, 1200))))
				.isInstanceOf(ConsentException.class);
		assertThat(SignatureImage.fromDataUrl(SignatureFixtures.dataUrl(SignatureFixtures.png(1200, 700)))).isNotEmpty();
	}

	@Test
	void rejectsSixteenBitPng() throws IOException {
		// 브라우저 캔버스는 8비트만 만든다. 16비트는 같은 크기에 메모리를 두 배 쓴다
		BufferedImage image = new BufferedImage(600, 240, BufferedImage.TYPE_USHORT_GRAY);
		Graphics2D g = image.createGraphics();
		g.setColor(Color.WHITE);
		g.fillRect(0, 0, 600, 240);
		g.setColor(Color.BLACK);
		g.fillRect(50, 100, 500, 20);
		g.dispose();
		ByteArrayOutputStream png = new ByteArrayOutputStream();
		ImageIO.write(image, "png", png);

		assertThatThrownBy(() -> SignatureImage.fromDataUrl(SignatureFixtures.dataUrl(png.toByteArray())))
				.isInstanceOf(ConsentException.class);
	}

	@Test
	void rejectsSingleDotFromThickPen() {
		// 5px 펜으로 화면 배율 3배(조작하면 가능)로 한 번 찍고 살짝 움직인 모양: 지름 16px 점과 짧은 획
		BufferedImage image = new BufferedImage(1050, 420, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setColor(new Color(17, 17, 17));
		g.fillOval(500, 200, 16, 16);
		g.fillRect(508, 205, 3, 6);
		g.dispose();
		ByteArrayOutputStream png = new ByteArrayOutputStream();
		try {
			ImageIO.write(image, "png", png);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}

		assertThatThrownBy(() -> SignatureImage.fromDataUrl(SignatureFixtures.dataUrl(png.toByteArray())))
				.isInstanceOf(ConsentException.class);
	}

	@Test
	void rejectsPngClaimingHugeSizeWithoutDecodingIt() {
		// 헤더에만 9000×9000(RGBA)이라고 적은 수십 바이트 PNG. 그대로 디코딩하면 300MB 넘게 잡는다
		byte[] png = pngHeaderOnly(9000, 9000);

		assertThatThrownBy(() -> SignatureImage.fromDataUrl(SignatureFixtures.dataUrl(png)))
				.isInstanceOf(ConsentException.class);
	}

	private static byte[] pngHeaderOnly(int width, int height) {
		ByteBuffer ihdr = ByteBuffer.allocate(13).putInt(width).putInt(height)
				.put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
		writeChunk(out, "IHDR", ihdr.array());
		writeChunk(out, "IEND", new byte[0]);
		return out.toByteArray();
	}

	private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) {
		CRC32 crc = new CRC32();
		crc.update(type.getBytes(StandardCharsets.US_ASCII));
		crc.update(data);
		out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
		out.writeBytes(type.getBytes(StandardCharsets.US_ASCII));
		out.writeBytes(data);
		out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"data:image/jpeg;base64,AAAA", "data:image/png;base64,!!!", "data:image/png;base64,AAAA"})
	void rejectsInvalidData(String dataUrl) {
		assertThatThrownBy(() -> SignatureImage.fromDataUrl(dataUrl)).isInstanceOf(ConsentException.class);
	}

}
