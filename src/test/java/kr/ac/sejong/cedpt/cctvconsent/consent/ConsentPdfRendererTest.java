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
