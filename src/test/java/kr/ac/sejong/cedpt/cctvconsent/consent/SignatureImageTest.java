package kr.ac.sejong.cedpt.cctvconsent.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"data:image/jpeg;base64,AAAA", "data:image/png;base64,!!!", "data:image/png;base64,AAAA"})
	void rejectsInvalidData(String dataUrl) {
		assertThatThrownBy(() -> SignatureImage.fromDataUrl(dataUrl)).isInstanceOf(ConsentException.class);
	}

}
