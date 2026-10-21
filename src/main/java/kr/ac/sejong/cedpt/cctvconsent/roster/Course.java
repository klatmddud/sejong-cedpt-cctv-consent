package kr.ac.sejong.cedpt.cctvconsent.roster;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

@Entity
public class Course {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "semester_id")
	private Semester semester;

	/** 학수번호-분반, 예: 000304-001 */
	@Column(nullable = false)
	private String code;

	@Column(nullable = false)
	private String name;

	private String professor;

	private String schedule;

	@Column(nullable = false)
	private String room;

	protected Course() {
	}

	public Course(Semester semester, String code) {
		this.semester = semester;
		this.code = code;
	}

	public void update(String name, String professor, String schedule, String room) {
		this.name = name;
		this.professor = professor;
		this.schedule = schedule;
		this.room = room;
	}

	public Long getId() {
		return id;
	}

	public Semester getSemester() {
		return semester;
	}

	public String getCode() {
		return code;
	}

	public String getName() {
		return name;
	}

	public String getProfessor() {
		return professor;
	}

	public String getSchedule() {
		return schedule;
	}

	public String getRoom() {
		return room;
	}

}
