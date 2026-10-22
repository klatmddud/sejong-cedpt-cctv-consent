package kr.ac.sejong.cedpt.cctvconsent.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
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
