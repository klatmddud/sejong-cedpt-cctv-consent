package kr.ac.sejong.cedpt.cctvconsent.roster;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TargetRoomsTest {

	private final TargetRooms targetRooms = new TargetRooms("B201, B202,B203,B206,B207");

	@ParameterizedTest
	@CsvSource(nullValues = "NULL", value = {
			"센B201, true",
			"센B207, true",
			"B203, true",
			"센b206, true",
			"호302/센B202, true",
			"센B209, false",
			"호302/센B205, false",
			"센B2011, false",
			"집302, false",
			"NULL, false"
	})
	void matchesRoomAfterBuildingPrefix(String room, boolean expected) {
		assertThat(targetRooms.includes(room)).isEqualTo(expected);
	}

	@Test
	void returnsTargetPartsAsWritten() {
		assertThat(targetRooms.matching("센B201/ 센b202 /호302/B201")).containsExactly(
				new TargetRooms.Room("B201", "센B201"), new TargetRooms.Room("B202", "센b202"));
		assertThat(targetRooms.matching("센B209")).isEmpty();
		assertThat(targetRooms.matching(null)).isEmpty();
	}

}
