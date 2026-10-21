package kr.ac.sejong.cedpt.cctvconsent.admin;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import kr.ac.sejong.cedpt.cctvconsent.PostgresTestSupport;
import kr.ac.sejong.cedpt.cctvconsent.roster.RosterTestFixtures;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AdminControllerTest extends PostgresTestSupport {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

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

		mvc.perform(multipart("/admin/roster").file(file).param("endsOn", "2026-12-21")
						.with(user("admin").roles("ADMIN")).with(csrf()))
				.andExpect(redirectedUrl("/admin/roster"))
				.andExpect(flash().attributeExists("result"));

		mvc.perform(get("/admin").with(user("admin").roles("ADMIN")))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("2026-2학기")))
				.andExpect(content().string(Matchers.containsString("2027-12-21")));
	}

	@Test
	void showsParseErrorForInvalidRoster() throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "명부.xlsx", null, "not xlsx".getBytes());

		mvc.perform(multipart("/admin/roster").file(file).param("endsOn", "2026-12-21")
						.with(user("admin").roles("ADMIN")).with(csrf()))
				.andExpect(redirectedUrl("/admin/roster"))
				.andExpect(flash().attribute("error", Matchers.containsString("xlsx")));
	}

	@Test
	void rejectsUploadWithoutCsrfToken() throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "명부.xlsx", null, new byte[] {1});

		mvc.perform(multipart("/admin/roster").file(file).param("endsOn", "2026-12-21")
						.with(user("admin").roles("ADMIN")))
				.andExpect(status().isForbidden());
	}

}
