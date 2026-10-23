package kr.ac.sejong.cedpt.cctvconsent.consent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class ConsentPdfRendererTest {

	private static final ZonedDateTime SIGNED_AT = ZonedDateTime.of(2026, 3, 4, 10, 30, 0, 0, ZoneId.of("Asia/Seoul"));

	private final ConsentPdfRenderer renderer = new ConsentPdfRenderer();

	@ParameterizedTest
	@EnumSource(ConsentLanguage.class)
	void stampsNameDateAndSignature(ConsentLanguage language) throws IOException {
		byte[] pdf = renderer.render(language, "홍길동", SIGNED_AT, SignatureFixtures.png());

		// 눈으로 위치를 확인할 수 있게 build/ 아래에 남긴다
		Path sample = Path.of("build", "consent-samples", "sample_" + language.code() + ".pdf");
		Files.createDirectories(sample.getParent());
		Files.write(sample, pdf);

		try (PDDocument document = Loader.loadPDF(pdf)) {
			assertThat(document.getNumberOfPages()).isEqualTo(1);
			String text = new PDFTextStripper().getText(document);
			assertThat(text).contains("홍길동");
			assertThat(text).contains("26").contains("3").contains("4");
			assertThat(document.getPage(0).getResources().getXObjectNames()).isNotEmpty();
		}
		assertThat(pdf.length).isLessThan(400_000);
	}

	@ParameterizedTest
	@ValueSource(strings = {"JOSÉ", "Nguyễn Văn An", "Жанна", "张伟", "Ali‮evil\r\n"})
	void drawsNamesOutsideNanumGothicInsteadOfFailing(String name) throws IOException {
		for (ConsentLanguage language : ConsentLanguage.values()) {
			byte[] pdf = renderer.render(language, name, SIGNED_AT, SignatureFixtures.png());
			try (PDDocument document = Loader.loadPDF(pdf)) {
				String text = new PDFTextStripper().getText(document);
				// 라틴 확장, 키릴은 보조 글꼴로 그대로, 두 글꼴 모두에 없는 한자는 ?로, 제어·방향 문자는 빠진다
				String expected = switch (name) {
					case "张伟" -> "??";
					case "Ali‮evil\r\n" -> "Alievil";
					default -> name;
				};
				assertThat(text).contains(expected);
			}
		}
	}

	@Test
	void coverNormalizesCompatibilityCharacters() throws IOException {
		byte[] pdf = renderer.cover("시작", "공업수학Ⅱ", "000304-001", "센B201", "王小明");
		try (PDDocument document = Loader.loadPDF(pdf)) {
			assertThat(new PDFTextStripper().getText(document)).contains("공업수학II").contains("담당교수  ???");
		}
	}

	@Test
	void shrinksPhoneSignatureTo300DpiOfItsBoxAndKeepsSmallOnes() throws IOException {
		// 화면 배율 2배 휴대폰 서명 크기(약 1000×600). 72×34pt 상자에 높이로 맞으므로 34pt × 300/72 ≈ 142px
		var phone = ConsentPdfRenderer.signatureForPdf(SignatureFixtures.png(1000, 600));
		assertThat(phone.getHeight()).isEqualTo(142);
		assertThat(phone.getWidth()).isEqualTo(236);

		var small = ConsentPdfRenderer.signatureForPdf(SignatureFixtures.png(200, 80));
		assertThat(small.getWidth()).isEqualTo(200);
		assertThat(small.getHeight()).isEqualTo(80);
	}

	@Test
	void drawsCoverWithCourseDetails() throws IOException {
		byte[] pdf = renderer.cover("시작", "문제해결및실습:JAVA", "009956-001", "센B201", null);

		Path sample = Path.of("build", "consent-samples", "cover.pdf");
		Files.createDirectories(sample.getParent());
		Files.write(sample, pdf);

		try (PDDocument document = Loader.loadPDF(pdf)) {
			assertThat(document.getNumberOfPages()).isEqualTo(1);
			assertThat(new PDFTextStripper().getText(document))
					.contains("시작", "문제해결및실습:JAVA", "학수번호  009956-001", "강의실  센B201", "담당교수  -");
		}
	}

}
