package kr.ac.sejong.cedpt.cctvconsent.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import kr.ac.sejong.cedpt.cctvconsent.PostgresTestSupport;
import kr.ac.sejong.cedpt.cctvconsent.roster.EnrollmentSync;
import kr.ac.sejong.cedpt.cctvconsent.roster.RosterImportService;
import kr.ac.sejong.cedpt.cctvconsent.roster.RosterTestFixtures;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AdminControllerTest extends PostgresTestSupport {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	EnrollmentSync sync;

	@BeforeEach
	void clean() {
		jdbc.execute("TRUNCATE semester CASCADE");
	}

	@Test
	void adminPagesRequireLogin() throws Exception {
		mvc.perform(get("/admin"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/admin/login"));
	}

	@Test
	void publicPagesAreOpen() throws Exception {
		mvc.perform(get("/")).andExpect(status().isOk());
		mvc.perform(get("/admin/login")).andExpect(status().isOk());
		mvc.perform(get("/actuator/health")).andExpect(status().isOk());
	}

	@Test
	void unknownPathsAreDenied() throws Exception {
		mvc.perform(get("/actuator/env").with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
	}

	@Test
	void logsInWithAdminAccount() throws Exception {
		mvc.perform(formLogin("/admin/login").user(ADMIN_USERNAME).password(ADMIN_PASSWORD))
				.andExpect(authenticated().withRoles("ADMIN"))
				.andExpect(redirectedUrl("/admin"));
		mvc.perform(formLogin("/admin/login").user(ADMIN_USERNAME).password("wrong"))
				.andExpect(unauthenticated())
				.andExpect(redirectedUrl("/admin/login?error"));
	}

	@Test
	void uploadsRosterAndShowsSemester() throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "명부.xlsx", null, RosterTestFixtures.roster(List.of(
				RosterTestFixtures.course("000304-001", "센B201", "20010001", "20010002"),
				RosterTestFixtures.course("000305-001", "센B209", "20010003"))));

		mvc.perform(multipart("/admin/roster").file(file)
						.with(user("admin").roles("ADMIN")).with(csrf()))
				.andExpect(redirectedUrl("/admin/roster"))
				.andExpect(flash().attributeExists("result"));

		mvc.perform(get("/admin").with(user("admin").roles("ADMIN")))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("2026-2학기")))
				.andExpect(content().string(Matchers.containsString("2027-12-31")));
	}

	@Test
	void showsSemesterCoursesAsListAndTimetable() throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "명부.xlsx", null, RosterTestFixtures.roster(List.of(
				RosterTestFixtures.course("000304-001", "센B201", "20010001", "20010002"),
				RosterTestFixtures.course("000400-001", "센B203", "20010003"))));
		mvc.perform(multipart("/admin/roster").file(file)
				.with(user("admin").roles("ADMIN")).with(csrf()));
		long semesterId = jdbc.queryForObject("select id from semester", Long.class);

		mvc.perform(get("/admin/semesters/{id}", semesterId).with(user("admin").roles("ADMIN")))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("미제출 3건")))
				.andExpect(content().string(Matchers.containsString("class=\"course-row\"")))
				.andExpect(content().string(Matchers.containsString("id=\"course-000304-001\"")))
				.andExpect(content().string(Matchers.containsString("20010002")));

		mvc.perform(get("/admin/semesters/{id}", semesterId).param("view", "timetable").param("room", "센B203")
						.with(user("admin").roles("ADMIN")))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("class=\"timetable-block\"")))
				.andExpect(content().string(Matchers.containsString("data-dialog=\"course-000400-001\"")))
				.andExpect(content().string(Matchers.not(Matchers.containsString("class=\"course-row\""))));
	}

	@Test
	void downloadsSelectedCoursesAsOnePdf() throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "명부.xlsx", null, RosterTestFixtures.roster(List.of(
				RosterTestFixtures.course("000304-001", "센B201", "20010001"),
				RosterTestFixtures.course("000400-001", "센B203", "20010003"))));
		mvc.perform(multipart("/admin/roster").file(file).with(user("admin").roles("ADMIN")).with(csrf()));
		long semesterId = jdbc.queryForObject("select id from semester", Long.class);

		mvc.perform(get("/admin/semesters/{id}", semesterId).with(user("admin").roles("ADMIN")))
				.andExpect(content().string(Matchers.containsString("name=\"codes\" value=\"000304-001\"")));

		byte[] pdf = mvc.perform(post("/admin/semesters/{id}/consents/download", semesterId)
						.param("codes", "000304-001", "000400-001", "000304-001")
						.with(user("admin").roles("ADMIN")).with(csrf()))
				.andExpect(status().isOk())
				.andExpect(content().contentType("application/pdf"))
				.andExpect(header().string("Content-Disposition", Matchers.startsWith("attachment;")))
				.andReturn().getResponse().getContentAsByteArray();
		try (var document = org.apache.pdfbox.Loader.loadPDF(pdf)) {
			assertThat(document.getNumberOfPages()).isEqualTo(4);
		}

		mvc.perform(post("/admin/semesters/{id}/consents/download", semesterId)
						.with(user("admin").roles("ADMIN")).with(csrf()))
				.andExpect(redirectedUrl("/admin/semesters/" + semesterId))
				.andExpect(flash().attribute("error", Matchers.containsString("과목을 고르세요")));
	}

	@Test
	void deletesCourseOnlyWhenCodeIsTypedAgain() throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "명부.xlsx", null, RosterTestFixtures.roster(List.of(
				RosterTestFixtures.course("000304-001", "센B201", "20010001"),
				RosterTestFixtures.course("000400-001", "센B203", "20010003"))));
		mvc.perform(multipart("/admin/roster").file(file)
				.with(user("admin").roles("ADMIN")).with(csrf()));
		long semesterId = jdbc.queryForObject("select id from semester", Long.class);

		mvc.perform(post("/admin/semesters/{id}/courses/delete", semesterId).param("code", "000304-001")
						.param("confirmCode", "000304-002").param("consentCount", "0")
						.param("view", "timetable").param("room", "센B201")
						.with(user("admin").roles("ADMIN")).with(csrf()))
				.andExpect(redirectedUrl("/admin/semesters/" + semesterId + "?view=timetable&room=%EC%84%BCB201"))
				.andExpect(flash().attributeExists("error"));
		assertThat(jdbc.queryForObject("select count(*) from course", Integer.class)).isEqualTo(2);

		mvc.perform(post("/admin/semesters/{id}/courses/delete", semesterId).param("code", "000304-001")
						.param("confirmCode", " 000304-001 ").param("consentCount", "0")
						.with(user("admin").roles("ADMIN")).with(csrf()))
				.andExpect(redirectedUrl("/admin/semesters/" + semesterId + "?view=list"))
				.andExpect(flash().attribute("notice", Matchers.containsString("000304-001")));
		assertThat(jdbc.queryForObject("select count(*) from course", Integer.class)).isEqualTo(1);
	}

	@Test
	void uploadsSeveralRosterFilesTogether() throws Exception {
		MockMultipartFile first = new MockMultipartFile("file", "소융.xlsx", null, RosterTestFixtures.roster(List.of(
				RosterTestFixtures.course("000304-001", "센B201", "20010001"))));
		MockMultipartFile second = new MockMultipartFile("file", "AI융.xlsx", null, RosterTestFixtures.roster(List.of(
				RosterTestFixtures.course("000400-001", "센B203", "20010002"))));

		mvc.perform(multipart("/admin/roster").file(first).file(second)
						.with(user("admin").roles("ADMIN")).with(csrf()))
				.andExpect(redirectedUrl("/admin/roster"))
				.andExpect(result -> assertThat(((RosterImportService.Result) result.getFlashMap().get("result"))
						.courseCount()).isEqualTo(2));
	}

	@Test
	void asksBeforeDeletingConsentsOfStudentsMissingFromRoster() throws Exception {
		sync.sync("20010009", "정정취소", 2026, "2", List.of(
				new EnrollmentSync.EnrolledCourse("000304-001", "과목000304-001", "김교수", "월10:30-12:00", "센B201")));
		long enrollmentId = jdbc.queryForObject("select id from enrollment", Long.class);
		long consentId = jdbc.queryForObject("""
				insert into consent (enrollment_id, language, signer_name, signed_at)
				values (?, 'KO', '정정취소', now()) returning id""", Long.class, enrollmentId);
		jdbc.update("insert into consent_document (consent_id, signature_png, pdf) values (?, ?, ?)",
				consentId, new byte[] {1}, new byte[] {1});
		MockMultipartFile file = new MockMultipartFile("file", "명부.xlsx", null, RosterTestFixtures.roster(List.of(
				RosterTestFixtures.course("000304-001", "센B201", "20010001"))));
		MockHttpSession session = new MockHttpSession();

		mvc.perform(multipart("/admin/roster").file(file).session(session)
						.with(user("admin").roles("ADMIN")).with(csrf()))
				.andExpect(redirectedUrl("/admin/roster"))
				.andExpect(flash().attributeCount(0));
		mvc.perform(get("/admin/roster").session(session).with(user("admin").roles("ADMIN")))
				.andExpect(content().string(Matchers.containsString("동의서 1건이 삭제됩니다")))
				.andExpect(content().string(Matchers.containsString("20010009")));
		assertThat(jdbc.queryForObject("select count(*) from consent", Integer.class)).isEqualTo(1);

		mvc.perform(post("/admin/roster/confirm").session(session).with(user("admin").roles("ADMIN")).with(csrf()))
				.andExpect(redirectedUrl("/admin/roster"))
				.andExpect(flash().attributeExists("result"));

		assertThat(jdbc.queryForObject("select count(*) from consent", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from enrollment where student_id = '20010009'", Integer.class))
				.isZero();
		assertThat(session.getAttribute("pendingRoster")).isNull();

		// 확인을 두 번 보내도 한 번만 반영된다
		mvc.perform(post("/admin/roster/confirm").session(session).with(user("admin").roles("ADMIN")).with(csrf()))
				.andExpect(redirectedUrl("/admin/roster"))
				.andExpect(flash().attribute("error", Matchers.containsString("확인할 업로드가 없습니다")));
		assertThat(jdbc.queryForObject("select count(*) from roster_upload", Integer.class)).isEqualTo(1);
	}

	@Test
	void cancelDropsPendingRoster() throws Exception {
		MockHttpSession session = new MockHttpSession();
		session.setAttribute("pendingRoster", "보류 중");

		mvc.perform(post("/admin/roster/cancel").session(session).with(user("admin").roles("ADMIN")).with(csrf()))
				.andExpect(redirectedUrl("/admin/roster"));

		assertThat(session.getAttribute("pendingRoster")).isNull();
	}

	@Test
	void showsParseErrorForInvalidRoster() throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "명부.xlsx", null, "not xlsx".getBytes());

		mvc.perform(multipart("/admin/roster").file(file)
						.with(user("admin").roles("ADMIN")).with(csrf()))
				.andExpect(redirectedUrl("/admin/roster"))
				.andExpect(flash().attribute("error", Matchers.containsString("xlsx")));
	}

	@Test
	void rejectsUploadWithoutCsrfToken() throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "명부.xlsx", null, new byte[] {1});

		mvc.perform(multipart("/admin/roster").file(file)
						.with(user("admin").roles("ADMIN")))
				.andExpect(status().isForbidden());
	}

}
