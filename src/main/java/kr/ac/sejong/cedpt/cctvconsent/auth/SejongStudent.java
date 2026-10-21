package kr.ac.sejong.cedpt.cctvconsent.auth;

/**
 * 세종대 포털 인증으로 확인한 학생 정보.
 */
public record SejongStudent(
		String studentId,
		String name,
		String major,
		String grade,
		String status) {
}
