package kr.ac.sejong.cedpt.cctvconsent.roster;

import java.util.List;

/**
 * 학사 시스템에서 내려받은 "출석부" 형식 수강명부를 파싱한 결과.
 */
public record RosterFile(int year, String term, List<Course> courses) {

	public record Course(
			String code,
			String name,
			String professor,
			String schedule,
			String room,
			List<Student> students) {
	}

	public record Student(String studentId, String name, String department, String grade) {
	}

}
