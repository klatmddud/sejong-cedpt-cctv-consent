package kr.ac.sejong.cedpt.cctvconsent.auth;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * sjpt 수강내역조회(dl_main)의 과목 한 줄. 암호화된 학번(STUDENT_NO_ENC) 등 쓰지 않는 필드는 담지 않는다.
 *
 * @param curiNo 학수번호 (CURI_NO)
 * @param classNo 분반 (CLASS)
 * @param code 학수번호-분반 (CURI_CLASS), 명부의 Course.code와 같은 형식
 * @param name 교과목명 (CURI_NM)
 * @param department 개설학과 (DEPT_ALIAS)
 * @param courseType 이수구분 (CURI_TYPE_CD_NM)
 * @param credits 학점 (CDT), 예: "3.0"
 * @param schedule 시간표 (TIME_ALL), 예: "목15:00-18:00(교수명/센819)"
 * @param term 학기 표기 (YEAR_SMT), 예: "2026/2학기"
 * @param organization 소속 구분 (ORGN_CLSF_NM)
 */
public record SjptCourse(
		String curiNo,
		String classNo,
		String code,
		String name,
		String department,
		String courseType,
		String credits,
		String schedule,
		String term,
		String organization) {

	// 시간표 한 칸: 요일시간(교수/강의실). 칸이 여러 개면 쉼표 등으로 이어진다고 보고, 괄호 단위로 읽는다.
	private static final Pattern SLOT = Pattern.compile("([^(),]*)\\(([^()]*)\\)");

	private record Slot(String time, List<String> professors, String room) {
	}

	/** 요일과 시간만, 예: "목15:00-18:00". 시간표 형식을 읽지 못하면 원문 그대로. */
	public String times() {
		List<Slot> slots = slots();
		return slots.isEmpty() ? schedule : join(slots, slot -> List.of(slot.time()), " ");
	}

	/** 교수명, 여러 명이면 ", "로 잇는다. */
	public String professors() {
		return join(slots(), Slot::professors, ", ");
	}

	/** 강의실, 여러 곳이면 "/"로 잇는다 (명부의 강의실 표기와 같은 형식, 예: "호302/센B205"). */
	public String rooms() {
		return join(slots(), slot -> List.of(slot.room()), "/");
	}

	private List<Slot> slots() {
		List<Slot> slots = new ArrayList<>();
		if (schedule == null) {
			return slots;
		}
		Matcher matcher = SLOT.matcher(schedule);
		while (matcher.find()) {
			String[] parts = matcher.group(2).split("/");
			List<String> professors = new ArrayList<>();
			for (int i = 0; i < parts.length - 1; i++) {
				professors.add(parts[i].trim());
			}
			slots.add(new Slot(matcher.group(1).trim(), professors, parts[parts.length - 1].trim()));
		}
		return slots;
	}

	private static String join(List<Slot> slots, Function<Slot, List<String>> values, String delimiter) {
		Set<String> distinct = new LinkedHashSet<>();
		slots.forEach(slot -> values.apply(slot).stream().filter(value -> !value.isEmpty()).forEach(distinct::add));
		return String.join(delimiter, distinct);
	}

}
