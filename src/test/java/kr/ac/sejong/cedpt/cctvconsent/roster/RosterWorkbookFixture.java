package kr.ac.sejong.cedpt.cctvconsent.roster;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;

/**
 * 학사 시스템 "출석부" 형식을 흉내 낸 가짜 수강명부 xlsx. 실제 명부와 같은 특징을 재현한다:
 * 과목 블록 반복, pageSize 명 단위 페이지 분할, 열 위치만 다른 두 번째 사본.
 */
final class RosterWorkbookFixture {

	record FixtureCourse(String code, String name, String room, Integer declaredCount, List<String> studentIds) {

		static FixtureCourse of(String code, String room, String... studentIds) {
			return new FixtureCourse(code, "과목" + code, room, null, List.of(studentIds));
		}

		int declared() {
			return declaredCount != null ? declaredCount : studentIds.size();
		}

	}

	/** 1-based 열 번호. 두 번째 사본은 오른쪽 라벨 열이 1~2칸 왼쪽에 있다. */
	private record Layout(int credit, int creditValue, int room, int roomValue, int count, int countValue) {
	}

	private static final Layout FIRST = new Layout(35, 41, 35, 41, 59, 63);
	private static final Layout SECOND = new Layout(33, 40, 33, 40, 58, 62);

	private RosterWorkbookFixture() {
	}

	/**
	 * @param numericStudentIds 학번을 문자열 대신 숫자 셀로 쓴다
	 * @param studentWithoutName 이 학번의 성명 칸을 비운다 (null이면 모두 채움)
	 */
	record Options(int pageSize, boolean numericStudentIds, String studentWithoutName) {
	}

	static byte[] workbook(String title, int pageSize, List<FixtureCourse> courses) {
		return workbook(title, new Options(pageSize, false, null), courses);
	}

	static byte[] workbook(String title, Options options, List<FixtureCourse> courses) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			Workbook workbook = new Workbook(out, "fixture", "1.0");
			Worksheet sheet = workbook.newWorksheet("Col1");
			int row = 1;
			int pageSize = options.pageSize();
			for (Layout layout : List.of(FIRST, SECOND)) {
				for (FixtureCourse course : courses) {
					List<String> ids = course.studentIds();
					for (int from = 0; from == 0 || from < ids.size(); from += pageSize) {
						row = writePage(sheet, row, title, layout, course,
								ids.subList(from, Math.min(ids.size(), from + pageSize)), from, options);
					}
				}
			}
			workbook.finish();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return out.toByteArray();
	}

	private static int writePage(Worksheet sheet, int row, String title, Layout layout, FixtureCourse course,
			List<String> ids, int offset, Options options) {
		put(sheet, row, 2, title);
		row += 1;
		put(sheet, row, 2, "과목명");
		put(sheet, row, 4, course.code());
		put(sheet, row, 13, "개설학과");
		put(sheet, row, 17, "컴퓨터공학과");
		put(sheet, row, layout.credit(), "학 점");
		put(sheet, row, layout.creditValue(), "3");
		row += 1;
		put(sheet, row, 4, course.name());
		row += 1;
		put(sheet, row, 2, "담당교수");
		put(sheet, row, 4, "교수" + course.code());
		put(sheet, row, 7, "이수구분");
		put(sheet, row, 10, "전기");
		put(sheet, row, 13, "강의시간");
		if (course.room() != null) {
			put(sheet, row, 17, "월10:30-12:00");
		}
		put(sheet, row, layout.room(), "강의실");
		if (course.room() != null) {
			put(sheet, row, layout.roomValue(), course.room());
		}
		put(sheet, row, layout.count(), "수강인원");
		put(sheet, row, layout.countValue(), String.valueOf(course.declared()));
		row += 2;
		put(sheet, row, 2, "순번");
		put(sheet, row, 3, "학과(부)");
		put(sheet, row, 6, "학년");
		put(sheet, row, 9, "학번");
		put(sheet, row, 12, "재수강");
		put(sheet, row, 14, "성명");
		put(sheet, row, 18, "학생\n구분");
		row += 2;
		for (int i = 0; i < ids.size(); i++) {
			put(sheet, row, 2, String.valueOf(offset + i + 1));
			put(sheet, row, 3, "컴퓨터공학과");
			put(sheet, row, 6, "2");
			String id = ids.get(i);
			if (options.numericStudentIds()) {
				sheet.value(row - 1, 9 - 1, Long.parseLong(id));
			}
			else {
				put(sheet, row, 9, id);
			}
			if (!id.equals(options.studentWithoutName())) {
				put(sheet, row, 14, "학생" + id.substring(id.length() - 2));
			}
			row += 1;
		}
		return row + 1;
	}

	private static void put(Worksheet sheet, int row, int column, String value) {
		sheet.value(row - 1, column - 1, value);
	}

}
