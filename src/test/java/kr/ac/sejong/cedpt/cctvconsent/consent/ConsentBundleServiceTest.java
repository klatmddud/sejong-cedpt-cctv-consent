package kr.ac.sejong.cedpt.cctvconsent.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import kr.ac.sejong.cedpt.cctvconsent.PostgresTestSupport;
import kr.ac.sejong.cedpt.cctvconsent.roster.RosterImportService;
import kr.ac.sejong.cedpt.cctvconsent.roster.RosterTestFixtures;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class ConsentBundleServiceTest extends PostgresTestSupport {

	@Autowired
	ConsentBundleService bundles;

	@Autowired
	RosterImportService importService;

	@Autowired
	ConsentPdfRenderer renderer;

	@Autowired
	JdbcTemplate jdbc;

	long semesterId;

	@BeforeEach
	void setUp() {
		jdbc.execute("TRUNCATE semester CASCADE");
		byte[] roster = RosterTestFixtures.roster(List.of(
				RosterTestFixtures.course("000304-001", "센B201", "20010002", "20010001", "20010003"),
				RosterTestFixtures.course("000400-001", "센B203", "20010004"),
				RosterTestFixtures.course("000500-001", "센B206", "20010005")));
		importService.importRoster(List.of(new RosterImportService.UploadedFile("명부.xlsx", roster)), List.of());
		semesterId = jdbc.queryForObject("select id from semester", Long.class);
		sign("000304-001", "20010002", "둘째");
		sign("000304-001", "20010001", "첫째");
		sign("000400-001", "20010004", "넷째");
	}

	@Test
	void putsCoversAroundEachCourseAndSortsConsentsByStudentId() throws IOException {
		List<String> pages;
		try (ConsentBundleService.Bundle bundle = bundles.bundle(semesterId, List.of("000400-001", "000304-001"))) {
			assertThat(bundle.courseCount()).isEqualTo(2);
			assertThat(bundle.consentCount()).isEqualTo(3);
			assertThat(bundle.fileName()).isEqualTo("CCTV동의서_2026-2학기_2과목.pdf");
			pages = pageTexts(Files.readAllBytes(bundle.file()));
			bundle.close();
			assertThat(bundle.directory()).doesNotExist();
		}

		// 강의실 순: 센B201(000304-001) 다음 센B203(000400-001)
		assertThat(pages).hasSize(2 + 2 + 1 + 2);
		assertThat(pages.get(0)).contains("시작", "과목000304-001", "학수번호  000304-001", "강의실  센B201",
				"담당교수  교수000304-001");
		assertThat(pages.get(1)).contains("첫째");
		assertThat(pages.get(2)).contains("둘째");
		assertThat(pages.get(3)).contains("끝", "학수번호  000304-001");
		assertThat(pages.get(4)).contains("시작", "학수번호  000400-001");
		assertThat(pages.get(5)).contains("넷째");
		assertThat(pages.get(6)).contains("끝", "학수번호  000400-001");
	}

	@Test
	void keepsCoversForCourseWithoutConsents() throws IOException {
		try (ConsentBundleService.Bundle bundle = bundles.bundle(semesterId, List.of("000500-001"))) {
			assertThat(bundle.consentCount()).isZero();
			assertThat(bundle.fileName()).isEqualTo("CCTV동의서_2026-2학기_000500-001_과목000500-001.pdf");
			List<String> pages = pageTexts(Files.readAllBytes(bundle.file()));
			assertThat(pages).hasSize(2);
			assertThat(pages.get(0)).contains("시작");
			assertThat(pages.get(1)).contains("끝");
		}
	}

	@Test
	void refusesWhenNoSelectedCourseIsInSemester() {
		assertThatThrownBy(() -> bundles.bundle(semesterId, List.of("999999-001")))
				.isInstanceOf(ConsentBundleService.NoCourseException.class);
		assertThatThrownBy(() -> bundles.bundle(semesterId, List.of()))
				.isInstanceOf(ConsentBundleService.NoCourseException.class);
	}

	private void sign(String courseCode, String studentId, String name) {
		byte[] pdf = renderer.render(ConsentLanguage.KO, name,
				ZonedDateTime.of(2026, 9, 1, 10, 0, 0, 0, ZoneId.of("Asia/Seoul")), SignatureFixtures.png());
		long consentId = jdbc.queryForObject("""
				insert into consent (enrollment_id, language, signer_name, signed_at)
				select e.id, 'KO', ?, now() from enrollment e join course c on c.id = e.course_id
				where c.code = ? and e.student_id = ? returning id""", Long.class, name, courseCode, studentId);
		jdbc.update("insert into consent_document (consent_id, signature_png, pdf) values (?, ?, ?)",
				consentId, SignatureFixtures.png(), pdf);
	}

	private static List<String> pageTexts(byte[] pdf) throws IOException {
		try (PDDocument document = Loader.loadPDF(pdf)) {
			List<String> pages = new ArrayList<>();
			PDFTextStripper stripper = new PDFTextStripper();
			for (int page = 1; page <= document.getNumberOfPages(); page++) {
				stripper.setStartPage(page);
				stripper.setEndPage(page);
				pages.add(stripper.getText(document));
			}
			return pages;
		}
	}

}
