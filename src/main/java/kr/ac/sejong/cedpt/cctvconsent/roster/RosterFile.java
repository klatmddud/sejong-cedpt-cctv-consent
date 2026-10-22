package kr.ac.sejong.cedpt.cctvconsent.roster;

import java.io.Serializable;
import java.util.List;

/**
 * 학사 시스템에서 내려받은 "출석부" 형식 수강명부를 파싱한 결과. 동의서 삭제 확인을 기다리는 동안 세션에 둔다.
 */
public record RosterFile(int year, String term, List<Course> courses) implements Serializable {

	public record Course(
			String code,
			String name,
			String professor,
			String schedule,
			String room,
			List<Student> students) implements Serializable {
	}

	public record Student(String studentId, String name, String department, String grade) implements Serializable {
	}

}
