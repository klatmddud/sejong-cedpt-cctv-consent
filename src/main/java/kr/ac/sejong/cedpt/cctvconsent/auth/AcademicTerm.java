package kr.ac.sejong.cedpt.cctvconsent.auth;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 정규 학기. 계절학기는 다루지 않는다.
 *
 * @param term 1 또는 2
 */
public record AcademicTerm(int year, int term) {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	public AcademicTerm {
		if (term != 1 && term != 2) {
			throw new IllegalArgumentException("학기는 1 또는 2여야 합니다: " + term);
		}
	}

	/** 3~8월은 1학기, 9~12월은 2학기, 1~2월은 전년도 2학기. */
	public static AcademicTerm of(LocalDate date) {
		int month = date.getMonthValue();
		if (month <= 2) {
			return new AcademicTerm(date.getYear() - 1, 2);
		}
		return new AcademicTerm(date.getYear(), month <= 8 ? 1 : 2);
	}

	public static AcademicTerm current(Clock clock) {
		return of(LocalDate.now(clock.withZone(SEOUL)));
	}

	/** sjpt 학기 코드: 10 = 1학기, 20 = 2학기 */
	String smtCd() {
		return term + "0";
	}

	String smtCdName() {
		return term + "학기";
	}

}
