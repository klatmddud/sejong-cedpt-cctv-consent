package kr.ac.sejong.cedpt.cctvconsent.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
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
		importService.importRoster(importService.read(List.of(new RosterImportService.UploadedFile("명부.xlsx", roster))), List.of());
		semesterId = jdbc.queryForObject("select id from semester", Long.class);
		sign("000304-001", "20010002", "둘째");
		sign("000304-001", "20010001", "첫째");
		sign("000400-001", "20010004", "넷째");
	}

	@Test
	void zipsOneCoursePdfPerSelectedCourseInSelectedOrder() throws IOException {
		Map<String, byte[]> entries;
		try (ConsentBundleService.Download download = bundles.open(semesterId,
				List.of("000400-001", "000304-001", "000400-001"))) {
			assertThat(download.zip()).isTrue();
			assertThat(download.contentType()).isEqualTo("application/zip");
			assertThat(download.fileName()).isEqualTo("CCTV동의서_2026-2학기_2과목.zip");
			entries = unzip(write(download));
			assertThat(download.courseCount()).isEqualTo(2);
			assertThat(download.consentCount()).isEqualTo(3);
			download.close();
			assertThat(download.directory()).doesNotExist();
		}

		// 고른 순서(겹친 것은 처음 한 번): 000400-001 다음 000304-001
		assertThat(entries.keySet()).containsExactly("01_000400-001_과목000400-001.pdf",
				"02_000304-001_과목000304-001.pdf");
		List<String> first = pageTexts(entries.get("01_000400-001_과목000400-001.pdf"));
		assertThat(first).hasSize(3);
		assertThat(first.get(1)).contains("넷째");

		// 과목 안에서는 시작 표지, 동의서(학번 순), 끝 표지
		List<String> second = pageTexts(entries.get("02_000304-001_과목000304-001.pdf"));
		assertThat(second).hasSize(4);
		assertThat(second.get(0)).contains("시작", "과목000304-001", "학수번호  000304-001", "강의실  센B201",
				"담당교수  교수000304-001");
		assertThat(second.get(1)).contains("첫째");
		assertThat(second.get(2)).contains("둘째");
		assertThat(second.get(3)).contains("끝", "학수번호  000304-001");
	}

	@Test
	void leavesOutConsentsOfStudentsNoLongerEnrolled() throws IOException {
		jdbc.update("update enrollment set in_latest_roster = false, in_sjpt = false where student_id = '20010002'");

		try (ConsentBundleService.Download download = bundles.open(semesterId, List.of("000304-001"))) {
			List<String> pages = pageTexts(write(download));
			assertThat(download.consentCount()).isEqualTo(1);
			assertThat(pages).hasSize(3);
			assertThat(pages.get(1)).contains("첫째");
		}
	}

	@Test
	void singleCourseIsOnePdfWithCoversEvenWithoutConsents() throws IOException {
		try (ConsentBundleService.Download download = bundles.open(semesterId, List.of("000500-001"))) {
			assertThat(download.zip()).isFalse();
			assertThat(download.contentType()).isEqualTo("application/pdf");
			assertThat(download.fileName()).isEqualTo("CCTV동의서_2026-2학기_000500-001_과목000500-001.pdf");
			List<String> pages = pageTexts(write(download));
			assertThat(download.consentCount()).isZero();
			assertThat(pages).hasSize(2);
			assertThat(pages.get(0)).contains("시작");
			assertThat(pages.get(1)).contains("끝");
		}
	}

	@Test
	void buildsOneDownloadAtATime() {
		ConsentBundleService.Download first = bundles.open(semesterId, List.of("000304-001"));
		assertThatThrownBy(() -> bundles.open(semesterId, List.of("000400-001")))
				.isInstanceOf(ConsentBundleService.BusyException.class);

		first.close();
		first.close();
		bundles.open(semesterId, List.of("000400-001")).close();
	}

	@Test
	void refusesWhenNoSelectedCourseIsInSemester() {
		assertThatThrownBy(() -> bundles.open(semesterId, List.of("999999-001")))
				.isInstanceOf(ConsentBundleService.NoCourseException.class);
		assertThatThrownBy(() -> bundles.open(semesterId, List.of()))
				.isInstanceOf(ConsentBundleService.NoCourseException.class);
		// 거절한 요청은 차례를 잡지 않는다
		bundles.open(semesterId, List.of("000304-001")).close();
	}

	private static byte[] write(ConsentBundleService.Download download) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		download.writeTo(out);
		return out.toByteArray();
	}

	private static Map<String, byte[]> unzip(byte[] zip) throws IOException {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
			for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
				entries.put(entry.getName(), in.readAllBytes());
			}
		}
		return entries;
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
