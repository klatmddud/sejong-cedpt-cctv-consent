package kr.ac.sejong.cedpt.cctvconsent.admin;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import kr.ac.sejong.cedpt.cctvconsent.roster.CourseTime;
import kr.ac.sejong.cedpt.cctvconsent.roster.RosterQueryService.CourseSummary;

/**
 * 학기 동의서 현황 화면: 강의실별 목록과 강의실 하나의 주간 시간표.
 *
 * 시간표는 월~금 09:00~20:00을 30분 칸으로 그리고, 그 밖의 시간이나 주말 수업이 있으면 넓힌다.
 * 같은 강의실에서 시간이 겹치는 수업(sjpt로만 들어온 타과 수업 등)은 칸을 나눠 나란히 놓는다.
 */
record SemesterPage(List<RoomGroup> groups, int unsubmittedCount, String room, List<String> hours,
		List<TimetableDay> days, int timetableHeight, List<CourseSummary> unscheduled) {

	/** 30분 칸 높이(px) */
	static final int SLOT_PX = 26;

	private static final LocalTime DAY_START = LocalTime.of(9, 0);
	private static final LocalTime DAY_END = LocalTime.of(20, 0);

	record RoomGroup(String room, List<CourseSummary> courses, long studentCount, int unsubmittedCount) {
	}

	record TimetableDay(String name, List<Block> blocks) {
	}

	/** top, height는 시간표 칸의 위치(px), left, width는 요일 칸 안에서 차지하는 비율(%) */
	record Block(CourseSummary course, int top, int height, double left, double width) {
	}

	/**
	 * @param courses 강의실, 학수번호 순
	 * @param requestedRoom 시간표로 볼 강의실. 없거나 이 학기에 없는 강의실이면 첫 강의실
	 */
	static SemesterPage of(List<CourseSummary> courses, String requestedRoom) {
		Map<String, List<CourseSummary>> byRoom = new LinkedHashMap<>();
		courses.forEach(course -> byRoom.computeIfAbsent(course.room(), key -> new ArrayList<>()).add(course));
		List<RoomGroup> groups = byRoom.entrySet().stream()
				.map(entry -> new RoomGroup(entry.getKey(), entry.getValue(),
						entry.getValue().stream().mapToLong(CourseSummary::studentCount).sum(),
						entry.getValue().stream().mapToInt(CourseSummary::unsubmittedCount).sum()))
				.toList();
		int unsubmitted = groups.stream().mapToInt(RoomGroup::unsubmittedCount).sum();
		String room = byRoom.containsKey(requestedRoom) ? requestedRoom
				: byRoom.isEmpty() ? null : byRoom.keySet().iterator().next();
		List<CourseSummary> inRoom = room == null ? List.of() : byRoom.get(room);

		List<CourseTime> times = inRoom.stream().flatMap(course -> course.times().stream()).toList();
		LocalTime start = times.stream().map(time -> time.start().truncatedTo(ChronoUnit.HOURS))
				.reduce(DAY_START, (a, b) -> a.isBefore(b) ? a : b);
		LocalTime end = times.stream().map(SemesterPage::ceilToHour)
				.reduce(DAY_END, (a, b) -> a.isAfter(b) ? a : b);
		DayOfWeek lastDay = times.stream().map(CourseTime::day)
				.reduce(DayOfWeek.FRIDAY, (a, b) -> a.compareTo(b) >= 0 ? a : b);

		List<String> hours = new ArrayList<>();
		for (LocalTime hour = start; hour.isBefore(end); hour = hour.plusHours(1)) {
			hours.add(hour.toString());
		}
		List<TimetableDay> days = new ArrayList<>();
		for (DayOfWeek day = DayOfWeek.MONDAY; day.compareTo(lastDay) <= 0; day = day.plus(1)) {
			List<Slot> slots = new ArrayList<>();
			for (CourseSummary course : inRoom) {
				for (CourseTime time : course.times()) {
					if (time.day() == day) {
						slots.add(new Slot(course, time));
					}
				}
			}
			days.add(new TimetableDay(day.getDisplayName(TextStyle.SHORT, Locale.KOREAN), place(slots, start)));
			if (day == DayOfWeek.SUNDAY) {
				break;
			}
		}
		List<CourseSummary> unscheduled = inRoom.stream().filter(course -> course.times().isEmpty()).toList();
		return new SemesterPage(groups, unsubmitted, room, hours, days, px(start, end), unscheduled);
	}

	private record Slot(CourseSummary course, CourseTime time) {
	}

	/**
	 * 하루의 수업을 시간표 칸에 놓는다. 시간이 겹치는 수업은 겹치는 무리 안에서 줄을 나눠 나란히 놓는다.
	 */
	private static List<Block> place(List<Slot> slots, LocalTime dayStart) {
		List<Slot> sorted = slots.stream()
				.sorted(Comparator.comparing((Slot slot) -> slot.time().start()).thenComparing(slot -> slot.time().end()))
				.toList();
		List<Block> blocks = new ArrayList<>();
		List<Slot> cluster = new ArrayList<>();
		LocalTime clusterEnd = null;
		for (Slot slot : sorted) {
			if (clusterEnd != null && !slot.time().start().isBefore(clusterEnd)) {
				blocks.addAll(placeCluster(cluster, dayStart));
				cluster.clear();
				clusterEnd = null;
			}
			cluster.add(slot);
			clusterEnd = clusterEnd == null || slot.time().end().isAfter(clusterEnd) ? slot.time().end() : clusterEnd;
		}
		blocks.addAll(placeCluster(cluster, dayStart));
		return blocks;
	}

	private static List<Block> placeCluster(List<Slot> cluster, LocalTime dayStart) {
		// 줄마다 마지막 수업이 끝나는 시각. 들어갈 수 있는 첫 줄에 놓는다
		List<LocalTime> laneEnds = new ArrayList<>();
		List<Integer> lanes = new ArrayList<>();
		for (Slot slot : cluster) {
			int lane = 0;
			while (lane < laneEnds.size() && slot.time().start().isBefore(laneEnds.get(lane))) {
				lane++;
			}
			if (lane == laneEnds.size()) {
				laneEnds.add(slot.time().end());
			}
			else {
				laneEnds.set(lane, slot.time().end());
			}
			lanes.add(lane);
		}
		double width = 100.0 / laneEnds.size();
		List<Block> blocks = new ArrayList<>();
		for (int i = 0; i < cluster.size(); i++) {
			CourseTime time = cluster.get(i).time();
			blocks.add(new Block(cluster.get(i).course(), px(dayStart, time.start()) + 2,
					px(time.start(), time.end()) - 4, lanes.get(i) * width, width));
		}
		return blocks;
	}

	private static LocalTime ceilToHour(CourseTime time) {
		LocalTime hour = time.end().truncatedTo(ChronoUnit.HOURS);
		// 23시대에 끝나는 수업은 자정을 넘기지 않도록 23:59에서 멈춘다
		return hour.equals(time.end()) ? hour : hour.getHour() == 23 ? LocalTime.MAX : hour.plusHours(1);
	}

	private static int px(LocalTime from, LocalTime to) {
		return (int) (ChronoUnit.MINUTES.between(from, to) * SLOT_PX / 30);
	}

}
