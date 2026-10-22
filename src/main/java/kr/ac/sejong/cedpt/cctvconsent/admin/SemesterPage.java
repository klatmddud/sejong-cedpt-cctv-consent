package kr.ac.sejong.cedpt.cctvconsent.admin;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import kr.ac.sejong.cedpt.cctvconsent.roster.CourseTime;
import kr.ac.sejong.cedpt.cctvconsent.roster.RosterQueryService.CourseSummary;

/**
 * 학기 동의서 현황 화면: 강의실별 목록과 강의실 하나의 주간 시간표.
 *
 * 여러 강의실에서 하는 수업("센B201/센B202")은 대상 강의실마다 넣는다. 대상 강의실이 없는 표기는 표기 그대로 묶는다.
 * 시간표는 월~금 09:00~20:00을 30분 칸으로 그리고, 그 밖의 시간이나 주말 수업이 있으면 넓힌다.
 * 같은 강의실에서 시간이 겹치는 수업(sjpt로만 들어온 타과 수업 등)은 칸을 나눠 나란히 놓는다.
 *
 * @param courses 이 학기 과목, 한 번씩(상세 창용)
 */
record SemesterPage(List<CourseSummary> courses, List<RoomGroup> groups, int unsubmittedCount, String room,
		List<String> hours, List<TimetableDay> days, int timetableHeight, List<CourseSummary> unscheduled) {

	/** 30분 칸 높이(px) */
	static final int SLOT_PX = 26;

	private static final int DAY_START_HOUR = 9;
	private static final int DAY_END_HOUR = 20;

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
	 * @param targetRooms 강의실 표기에서 대상 강의실 부분을 고른다
	 */
	static SemesterPage of(List<CourseSummary> courses, String requestedRoom,
			Function<String, List<String>> targetRooms) {
		Map<String, List<CourseSummary>> byRoom = new TreeMap<>();
		for (CourseSummary course : courses) {
			List<String> rooms = targetRooms.apply(course.room());
			for (String room : rooms.isEmpty() ? List.of(course.room()) : rooms) {
				byRoom.computeIfAbsent(room, key -> new ArrayList<>()).add(course);
			}
		}
		byRoom.values().forEach(list -> list.sort(Comparator.comparing(CourseSummary::code)));
		List<RoomGroup> groups = byRoom.entrySet().stream()
				.map(entry -> new RoomGroup(entry.getKey(), entry.getValue(),
						entry.getValue().stream().mapToLong(CourseSummary::studentCount).sum(),
						entry.getValue().stream().mapToInt(CourseSummary::unsubmittedCount).sum()))
				.toList();
		// 여러 강의실 수업이 두 번 세이지 않도록 과목 기준으로 센다
		int unsubmitted = courses.stream().mapToInt(CourseSummary::unsubmittedCount).sum();
		String room = requestedRoom != null && byRoom.containsKey(requestedRoom) ? requestedRoom
				: byRoom.isEmpty() ? null : byRoom.keySet().iterator().next();
		List<CourseSummary> inRoom = room == null ? List.of() : byRoom.get(room);

		// 분 단위로 계산한다. 끝이 24:00이 될 수 있어 LocalTime으로는 넘어가지 않는다
		List<CourseTime> times = inRoom.stream().flatMap(course -> course.times().stream()).toList();
		int startHour = times.stream().mapToInt(time -> time.start().getHour())
				.reduce(DAY_START_HOUR, Math::min);
		int endHour = times.stream().mapToInt(time -> (minutes(time.end()) + 59) / 60)
				.reduce(DAY_END_HOUR, Math::max);
		DayOfWeek lastDay = times.stream().map(CourseTime::day)
				.reduce(DayOfWeek.FRIDAY, (a, b) -> a.compareTo(b) >= 0 ? a : b);

		List<String> hours = new ArrayList<>();
		for (int hour = startHour; hour < endHour; hour++) {
			hours.add("%02d:00".formatted(hour));
		}
		List<TimetableDay> days = new ArrayList<>();
		for (DayOfWeek day : DayOfWeek.values()) {
			if (day.compareTo(lastDay) > 0) {
				break;
			}
			List<Slot> slots = new ArrayList<>();
			for (CourseSummary course : inRoom) {
				for (CourseTime time : course.times()) {
					if (time.day() == day) {
						slots.add(new Slot(course, time));
					}
				}
			}
			days.add(new TimetableDay(day.getDisplayName(TextStyle.SHORT, Locale.KOREAN), place(slots, startHour * 60)));
		}
		List<CourseSummary> unscheduled = inRoom.stream().filter(course -> course.times().isEmpty()).toList();
		return new SemesterPage(courses, groups, unsubmitted, room, hours, days,
				px((endHour - startHour) * 60), unscheduled);
	}

	private record Slot(CourseSummary course, CourseTime time) {

		int start() {
			return minutes(time.start());
		}

		int end() {
			return minutes(time.end());
		}

	}

	/**
	 * 하루의 수업을 시간표 칸에 놓는다. 시간이 겹치는 수업은 겹치는 무리 안에서 줄을 나눠 나란히 놓는다.
	 */
	private static List<Block> place(List<Slot> slots, int dayStart) {
		List<Slot> sorted = slots.stream()
				.sorted(Comparator.comparingInt(Slot::start).thenComparingInt(Slot::end))
				.toList();
		List<Block> blocks = new ArrayList<>();
		List<Slot> cluster = new ArrayList<>();
		int clusterEnd = -1;
		for (Slot slot : sorted) {
			if (!cluster.isEmpty() && slot.start() >= clusterEnd) {
				blocks.addAll(placeCluster(cluster, dayStart));
				cluster.clear();
			}
			cluster.add(slot);
			clusterEnd = cluster.size() == 1 ? slot.end() : Math.max(clusterEnd, slot.end());
		}
		blocks.addAll(placeCluster(cluster, dayStart));
		return blocks;
	}

	private static List<Block> placeCluster(List<Slot> cluster, int dayStart) {
		// 줄마다 마지막 수업이 끝나는 시각. 들어갈 수 있는 첫 줄에 놓는다
		List<Integer> laneEnds = new ArrayList<>();
		List<Integer> lanes = new ArrayList<>();
		for (Slot slot : cluster) {
			int lane = 0;
			while (lane < laneEnds.size() && slot.start() < laneEnds.get(lane)) {
				lane++;
			}
			if (lane == laneEnds.size()) {
				laneEnds.add(slot.end());
			}
			else {
				laneEnds.set(lane, slot.end());
			}
			lanes.add(lane);
		}
		double width = laneEnds.isEmpty() ? 100 : 100.0 / laneEnds.size();
		List<Block> blocks = new ArrayList<>();
		for (int i = 0; i < cluster.size(); i++) {
			Slot slot = cluster.get(i);
			blocks.add(new Block(slot.course(), px(slot.start() - dayStart) + 2, px(slot.end() - slot.start()) - 4,
					lanes.get(i) * width, width));
		}
		return blocks;
	}

	private static int minutes(LocalTime time) {
		return time.getHour() * 60 + time.getMinute();
	}

	private static int px(int minutes) {
		return minutes * SLOT_PX / 30;
	}

}
