package kr.ac.sejong.cedpt.cctvconsent.roster;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 시간표 한 칸(요일, 시작, 끝). 관리자 시간표 보기에 쓴다.
 *
 * 강의시간 표기는 명부가 "월수13:30-15:00", sjpt가 "월10:30-12:00 수10:30-12:00"처럼 요일들과 시간 구간을 잇는다.
 */
public record CourseTime(DayOfWeek day, LocalTime start, LocalTime end) {

	private static final String DAYS = "월화수목금토일";
	private static final Pattern SEGMENT = Pattern.compile("([" + DAYS + "]+)(\\d{1,2}:\\d{2})-(\\d{1,2}:\\d{2})");
	private static final Pattern SEPARATOR = Pattern.compile("[\\s,]*");

	/** 강의시간 표기를 요일별 칸으로 나눈다. 형식을 읽지 못하면 빈 목록이다(시간표에 놓지 못하고 목록에만 나온다). */
	public static List<CourseTime> parse(String schedule) {
		List<CourseTime> times = new ArrayList<>();
		if (schedule == null || schedule.isBlank()) {
			return times;
		}
		Matcher matcher = SEGMENT.matcher(schedule);
		int position = 0;
		while (matcher.find()) {
			if (!SEPARATOR.matcher(schedule.substring(position, matcher.start())).matches()) {
				return List.of();
			}
			LocalTime start = time(matcher.group(2));
			LocalTime end = time(matcher.group(3));
			if (start == null || end == null || !start.isBefore(end)) {
				return List.of();
			}
			for (char day : matcher.group(1).toCharArray()) {
				times.add(new CourseTime(DayOfWeek.of(DAYS.indexOf(day) + 1), start, end));
			}
			position = matcher.end();
		}
		if (!SEPARATOR.matcher(schedule.substring(position)).matches()) {
			return List.of();
		}
		return List.copyOf(times);
	}

	private static LocalTime time(String text) {
		String[] parts = text.split(":");
		int hour = Integer.parseInt(parts[0]);
		int minute = Integer.parseInt(parts[1]);
		return hour < 24 && minute < 60 ? LocalTime.of(hour, minute) : null;
	}

}
