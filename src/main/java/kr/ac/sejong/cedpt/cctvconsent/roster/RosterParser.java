package kr.ac.sejong.cedpt.cctvconsent.roster;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.dhatim.fastexcel.reader.Cell;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;

/**
 * "출석부" 형식 수강명부 xlsx 파서.
 *
 * 파일 구조 (과목마다 반복, 45명 단위로 페이지가 나뉘면 블록 전체가 다시 나옴):
 * <pre>
 *   2026년도 2학기 출석부
 *   과목명   000304-001   개설학과 ...  학 점 3 ...
 *            공업수학1(GRADE)
 *   담당교수 홍길동  이수구분 전기  강의시간 수금15:00-16:30  강의실 센B201  수강인원 19
 *   순번 학과(부) 학년 학번 재수강 성명 ...
 *   1    컴퓨터공학과 2  20011234 ...  홍길동
 * </pre>
 * 같은 명부가 열 위치만 다른 두 벌로 들어 있으므로, 셀 위치가 아니라 라벨 바로 옆 칸을 읽고
 * 같은 과목의 학생은 학번으로 중복을 제거한다. 과목별 학생 수가 명부의 수강인원과 다르면 거부한다.
 */
public final class RosterParser {

	private static final Pattern TITLE = Pattern.compile("(\\d{4})\\s*년도\\s*(\\S+?)\\s*학기\\s*출석부");
	private static final Pattern STUDENT_ID = Pattern.compile("\\d{8,10}");
	// 학사 시스템이 과목명 뒤에 붙이는 성적 평가 방식: 공업수학1(GRADE), 세종인을위한전공탐색(P/NP)
	private static final Pattern GRADING_SUFFIX = Pattern.compile("\\s*\\((GRADE|P/NP|P/F)\\)\\s*$", Pattern.CASE_INSENSITIVE);
	private static final Set<String> LABELS = Set.of(
			"과목명", "개설학과", "학점", "강의", "실습", "담당교수", "이수구분", "강의시간", "강의실", "수강인원");
	// V1__roster.sql의 칸 길이. 넘으면 저장할 때 실패하므로 읽을 때 어느 줄인지 알려 준다.
	// 과목명, 교수, 강의시간, 강의실은 Course.update가 잘라서 저장한다
	private static final int MAX_TERM = 10;
	private static final int MAX_CODE = 20;
	private static final int MAX_STUDENT_NAME = 100;
	private static final int MAX_DEPARTMENT = 100;
	private static final int MAX_GRADE = 10;

	private RosterParser() {
	}

	public static RosterFile parse(InputStream xlsx) {
		State state = new State();
		try (ReadableWorkbook workbook = new ReadableWorkbook(xlsx);
				Stream<Row> rows = workbook.getFirstSheet().openStream()) {
			rows.map(RosterParser::cells).filter(cells -> !cells.isEmpty()).forEach(state::accept);
		}
		catch (IOException | RuntimeException ex) {
			if (ex instanceof RosterParseException parseException) {
				throw parseException;
			}
			throw new RosterParseException("xlsx 파일을 읽을 수 없습니다. 학사 시스템에서 내려받은 수강명부(xlsx)인지 확인하세요.", ex);
		}
		return state.finish();
	}

	/** 비어 있지 않은 셀만 열 순서대로. 라벨 비교를 위해 앞뒤 공백을 제거한다. */
	private static List<TextCell> cells(Row row) {
		List<TextCell> cells = new ArrayList<>();
		for (Cell cell : row) {
			if (cell == null) {
				continue;
			}
			String text = cell.getText();
			if (text == null) {
				continue;
			}
			// 학사 시스템 내보내기에 섞일 수 있는 줄바꿈 없는 공백(U+00A0)을 일반 공백으로
			text = text.replace(' ', ' ').trim();
			if (!text.isEmpty()) {
				cells.add(new TextCell(cell.getColumnIndex(), text));
			}
		}
		return cells;
	}

	private record TextCell(int column, String text) {

		String label() {
			return text.replaceAll("\\s+", "");
		}

	}

	private static final class CourseBuilder {

		final String code;
		String name;
		String professor;
		String schedule;
		String room;
		Integer declaredCount;
		final Map<String, RosterFile.Student> students = new LinkedHashMap<>();

		CourseBuilder(String code) {
			this.code = code;
		}

	}

	private static final class State {

		final Set<String> titles = new LinkedHashSet<>();
		final Map<String, CourseBuilder> courses = new LinkedHashMap<>();
		CourseBuilder current;
		boolean expectingName;
		Map<String, Integer> studentColumns;

		void accept(List<TextCell> row) {
			String first = row.getFirst().label();
			if (first.contains("출석부")) {
				titles.add(row.getFirst().text().replaceAll("\\s+", " "));
				return;
			}
			switch (first) {
				case "과목명" -> startCourse(row);
				case "담당교수" -> readCourseInfo(row);
				case "순번" -> studentColumns = columnsByLabel(row);
				default -> readNameOrStudent(row);
			}
		}

		private void startCourse(List<TextCell> row) {
			String code = valueAfter(row, "과목명");
			if (code == null) {
				throw new RosterParseException("과목명 행에 학수번호가 없습니다.");
			}
			if (code.length() > MAX_CODE) {
				throw new RosterParseException("학수번호가 " + MAX_CODE + "자를 넘습니다: " + code.substring(0, MAX_CODE) + "…");
			}
			current = courses.computeIfAbsent(code, CourseBuilder::new);
			expectingName = true;
			studentColumns = null;
		}

		private void readCourseInfo(List<TextCell> row) {
			requireCourse();
			expectingName = false;
			if (current.declaredCount != null) {
				return; // 다음 페이지에서 반복된 블록
			}
			current.professor = valueAfter(row, "담당교수");
			current.schedule = valueAfter(row, "강의시간");
			current.room = valueAfter(row, "강의실");
			String count = valueAfter(row, "수강인원");
			try {
				current.declaredCount = Integer.valueOf(count);
			}
			catch (NumberFormatException ex) {
				throw new RosterParseException(current.code + " 과목의 수강인원을 읽을 수 없습니다: " + count);
			}
		}

		private void readNameOrStudent(List<TextCell> row) {
			if (expectingName) {
				requireCourse();
				if (current.name == null) {
					current.name = GRADING_SUFFIX.matcher(row.getFirst().text()).replaceFirst("");
				}
				expectingName = false;
				return;
			}
			if (current == null || studentColumns == null) {
				return;
			}
			String studentId = textAt(row, studentColumns.get("학번"));
			if (studentId == null || !STUDENT_ID.matcher(studentId).matches()) {
				return;
			}
			String name = textAt(row, studentColumns.get("성명"));
			if (name == null) {
				throw new RosterParseException(current.code + " 과목의 학번 " + studentId + " 학생 성명이 비어 있습니다.");
			}
			String department = textAt(row, studentColumns.get("학과(부)"));
			String grade = textAt(row, studentColumns.get("학년"));
			requireLength(studentId, "성명", name, MAX_STUDENT_NAME);
			requireLength(studentId, "학과(부)", department, MAX_DEPARTMENT);
			requireLength(studentId, "학년", grade, MAX_GRADE);
			current.students.putIfAbsent(studentId, new RosterFile.Student(studentId, name, department, grade));
		}

		private void requireLength(String studentId, String label, String value, int max) {
			if (value != null && value.length() > max) {
				throw new RosterParseException(
						current.code + " 과목의 학번 " + studentId + " 학생 " + label + "이(가) " + max + "자를 넘습니다.");
			}
		}

		private void requireCourse() {
			if (current == null) {
				throw new RosterParseException("과목명 행보다 과목 정보가 먼저 나왔습니다.");
			}
		}

		RosterFile finish() {
			if (titles.isEmpty() || courses.isEmpty()) {
				throw new RosterParseException("수강명부(출석부) 형식이 아닙니다. 제목 또는 과목을 찾을 수 없습니다.");
			}
			if (titles.size() > 1) {
				throw new RosterParseException("한 파일에 여러 학기가 섞여 있습니다: " + titles);
			}
			Matcher title = TITLE.matcher(titles.iterator().next());
			if (!title.find()) {
				throw new RosterParseException("제목에서 학기를 읽을 수 없습니다: " + titles.iterator().next());
			}
			if (title.group(2).length() > MAX_TERM) {
				throw new RosterParseException("제목의 학기가 " + MAX_TERM + "자를 넘습니다: " + titles.iterator().next());
			}

			List<String> problems = new ArrayList<>();
			List<RosterFile.Course> result = new ArrayList<>();
			for (CourseBuilder course : courses.values()) {
				if (course.name == null || course.declaredCount == null) {
					problems.add(course.code + "(과목명 또는 수강인원 없음)");
					continue;
				}
				if (course.declaredCount != course.students.size()) {
					problems.add(course.code + "(수강인원 " + course.declaredCount + "명, 명단 " + course.students.size() + "명)");
					continue;
				}
				result.add(new RosterFile.Course(course.code, course.name, course.professor, course.schedule,
						course.room, List.copyOf(course.students.values())));
			}
			if (!problems.isEmpty()) {
				throw new RosterParseException("명부 내용이 맞지 않는 과목이 있습니다: " + String.join(", ", problems));
			}
			return new RosterFile(Integer.parseInt(title.group(1)), title.group(2), List.copyOf(result));
		}

	}

	/** 라벨 바로 다음 칸의 값. 다음 칸이 다른 라벨이면 값이 비어 있는 것이다. */
	private static String valueAfter(List<TextCell> row, String label) {
		for (int i = 0; i < row.size(); i++) {
			if (row.get(i).label().equals(label)) {
				if (i + 1 >= row.size() || LABELS.contains(row.get(i + 1).label())) {
					return null;
				}
				return row.get(i + 1).text();
			}
		}
		return null;
	}

	private static Map<String, Integer> columnsByLabel(List<TextCell> row) {
		Map<String, Integer> columns = new HashMap<>();
		for (TextCell cell : row) {
			columns.putIfAbsent(cell.label(), cell.column());
		}
		for (String required : List.of("학번", "성명")) {
			if (!columns.containsKey(required)) {
				throw new RosterParseException("학생 목록 머리글에 '" + required + "' 열이 없습니다.");
			}
		}
		return columns;
	}

	private static String textAt(List<TextCell> row, Integer column) {
		if (column == null) {
			return null;
		}
		for (TextCell cell : row) {
			if (cell.column() == column) {
				return cell.text();
			}
		}
		return null;
	}

}
