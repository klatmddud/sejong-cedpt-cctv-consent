package kr.ac.sejong.cedpt.cctvconsent.roster;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 동의서를 받을 강의실. 명부의 강의실 표기("센B201", "호302/센B205")를 '/'로 나누고
 * 앞에 붙은 건물 약자를 뗀 값이 목록과 정확히 같으면 대상이다.
 */
@Component
public class TargetRooms {

	private final Set<String> rooms;

	public TargetRooms(@Value("${consent.target-rooms}") String rooms) {
		this.rooms = Arrays.stream(rooms.split(","))
				.map(String::trim)
				.filter(room -> !room.isEmpty())
				.map(room -> room.toUpperCase(Locale.ROOT))
				.collect(Collectors.toUnmodifiableSet());
	}

	public boolean includes(String roomLabel) {
		return !matching(roomLabel).isEmpty();
	}

	/** 강의실 표기 중 대상 강의실인 부분, 표기 그대로(예: "호302/센B205" → ["센B205"]). */
	public List<String> matching(String roomLabel) {
		if (roomLabel == null) {
			return List.of();
		}
		return Arrays.stream(roomLabel.split("/"))
				.map(String::trim)
				.filter(part -> rooms.contains(part.replaceFirst("^[^A-Za-z0-9]+", "").toUpperCase(Locale.ROOT)))
				.distinct()
				.toList();
	}

}
