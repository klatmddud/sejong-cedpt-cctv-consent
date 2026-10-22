package kr.ac.sejong.cedpt.cctvconsent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SjptClientTest {

	@Test
	void reportsSubmitError() {
		String body = "{\"_SUBMIT_ERROR_\":{\"ERRCODE\":\"-1\",\"ERRMSG\":\"java.lang.NullPointerException\"}}";

		assertThatThrownBy(() -> SjptClient.parseResponse("/sch/doList.do", body))
				.isInstanceOfSatisfying(SejongAuthException.class, ex -> {
					assertThat(ex.getReason()).isEqualTo(SejongAuthException.Reason.UNEXPECTED_RESPONSE);
					assertThat(ex.getMessage()).contains("/sch/doList.do", "NullPointerException");
				});
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = " ")
	void rejectsBlankCredentialsWithoutCallingPortal(String blank) {
		assertThatThrownBy(() -> new SjptClient().courses(blank, "password"))
				.isInstanceOfSatisfying(SejongAuthException.class,
						ex -> assertThat(ex.getReason()).isEqualTo(SejongAuthException.Reason.INVALID_CREDENTIALS));
		assertThatThrownBy(() -> new SjptClient().courses("20010001", blank))
				.isInstanceOf(SejongAuthException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {"<html><script>location.href='/login'</script></html>", "", "[]"})
	void rejectsNonObjectResponse(String body) {
		assertThatThrownBy(() -> SjptClient.parseResponse("/x.do", body))
				.isInstanceOfSatisfying(SejongAuthException.class,
						ex -> assertThat(ex.getReason()).isEqualTo(SejongAuthException.Reason.UNEXPECTED_RESPONSE));
	}

	@Test
	void findsCourseListMenuByPagePath() {
		String left = """
				{"dl_UserMenuListLeft":[
				  {"MENU_CD":"SELF_STUD_GRADSELF_SUB_30SELF_MENU_10","MENU_LEVEL":3},
				  {"KEY_PG_ID":"SELF_STUD_GRADSELF_SUB_30SELF_MENU_10SueOpenTimeQ",
				   "PGM_FULL_PATH_URL":"/sch/modules/sue/SueOpenTimeQ.xml"},
				  {"KEY_PG_ID":"SELF_STUD_GRADSELF_SUB_30SELF_MENU_10SueReqLesnQ",
				   "PGM_FULL_PATH_URL":"/sch/modules/sue/SueReqLesnQ.xml"}
				]}""";

		assertThat(SjptClient.courseListMenuKey(SjptClient.parseResponse("/x.do", left).path("dl_UserMenuListLeft")))
				.isEqualTo("SELF_STUD_GRADSELF_SUB_30SELF_MENU_10SueReqLesnQ");
		assertThat(SjptClient.courseListMenuKey(SjptClient.parseResponse("/x.do", "{\"dl_UserMenuListLeft\":[]}")
				.path("dl_UserMenuListLeft"))).isEmpty();
	}

	@Test
	void returnsJsonObject() {
		assertThat(SjptClient.parseResponse("/x.do", "{\"dl_main\":[{\"CURI_NO\":\"000304\"}]}")
				.path("dl_main").get(0).get("CURI_NO").asString()).isEqualTo("000304");
	}

}
