package kr.ac.sejong.cedpt.cctvconsent.consent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 관리자가 고른 과목들의 동의서 PDF를 하나로 묶는다.
 * 과목마다 "시작" 표지, 받은 동의서(학번 순), "끝" 표지 순서로 넣고, 과목은 강의실, 학수번호 순으로 잇는다.
 *
 * 한 학기 동의서가 2천 건 가까이 될 수 있어 메모리에 모으지 않는다. 동의서를 한 건씩 임시 파일로 꺼내 과목별로 합친 뒤
 * 과목 파일을 다시 합친다(한 번에 여는 파일이 과목 하나의 동의서 수 정도를 넘지 않게). 결과도 임시 디렉터리에 있으며
 * 다 쓴 뒤 {@link Bundle#close()}로 지운다.
 */
@Service
public class ConsentBundleService {

	private record CourseRow(long id, String code, String name, String room, String professor) {
	}

	/**
	 * 묶은 PDF. file은 directory 안에 있다.
	 *
	 * @param fileName 내려받을 때 쓸 이름
	 */
	public record Bundle(Path directory, Path file, String fileName, int courseCount, int consentCount)
			implements AutoCloseable {

		@Override
		public void close() {
			deleteRecursively(directory);
		}

	}

	/** 고른 과목이 이 학기에 없다. */
	public static class NoCourseException extends RuntimeException {

		NoCourseException() {
			super("내려받을 과목을 고르세요.");
		}

	}

	private final JdbcTemplate jdbc;
	private final NamedParameterJdbcTemplate named;
	private final ConsentPdfRenderer renderer;

	public ConsentBundleService(JdbcTemplate jdbc, ConsentPdfRenderer renderer) {
		this.jdbc = jdbc;
		this.named = new NamedParameterJdbcTemplate(jdbc);
		this.renderer = renderer;
	}

	/**
	 * @param codes 학수번호. 이 학기에 없는 것은 무시한다.
	 * @throws NoCourseException 고른 과목 중 이 학기에 있는 것이 없음
	 */
	public Bundle bundle(long semesterId, Collection<String> codes) {
		List<CourseRow> courses = codes.isEmpty() ? List.of() : named.query("""
				select id, code, name, room, professor from course
				where semester_id = :semesterId and code in (:codes)
				order by room, code""",
				Map.of("semesterId", semesterId, "codes", codes.stream().distinct().toList()),
				(rs, row) -> new CourseRow(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
						rs.getString("room"), rs.getString("professor")));
		if (courses.isEmpty()) {
			throw new NoCourseException();
		}
		String semester = jdbc.queryForObject("select year || '-' || term || '학기' from semester where id = ?",
				String.class, semesterId);

		Path directory = null;
		try {
			directory = Files.createTempDirectory("consent-bundle-");
			List<Path> courseFiles = new ArrayList<>();
			int consentCount = 0;
			for (CourseRow course : courses) {
				Path courseFile = directory.resolve("course-" + courseFiles.size() + ".pdf");
				consentCount += writeCourse(course, directory, courseFile);
				courseFiles.add(courseFile);
			}
			Path file = directory.resolve("bundle.pdf");
			merge(courseFiles, file);
			return new Bundle(directory, file, fileName(semester, courses), courses.size(), consentCount);
		}
		catch (IOException | RuntimeException ex) {
			deleteRecursively(directory);
			if (ex instanceof IOException io) {
				throw new UncheckedIOException("동의서 PDF를 묶을 수 없습니다.", io);
			}
			throw (RuntimeException) ex;
		}
	}

	/** 과목 하나: 시작 표지, 동의서, 끝 표지를 courseFile로 합친다. @return 동의서 수 */
	private int writeCourse(CourseRow course, Path directory, Path courseFile) throws IOException {
		Path pieces = Files.createDirectory(directory.resolve("pieces-" + course.id()));
		List<Path> files = new ArrayList<>();
		files.add(Files.write(pieces.resolve("start.pdf"), cover("시작", course)));
		jdbc.query("""
				select d.pdf from consent s
				join enrollment e on e.id = s.enrollment_id
				join consent_document d on d.consent_id = s.id
				where e.course_id = ?
				order by e.student_id""",
				(RowCallbackHandler) rs -> {
					Path piece = pieces.resolve("consent-" + files.size() + ".pdf");
					try {
						files.add(Files.write(piece, rs.getBytes("pdf")));
					}
					catch (IOException ex) {
						throw new UncheckedIOException(ex);
					}
				},
				course.id());
		int consents = files.size() - 1;
		files.add(Files.write(pieces.resolve("end.pdf"), cover("끝", course)));
		merge(files, courseFile);
		deleteRecursively(pieces);
		return consents;
	}

	private byte[] cover(String mark, CourseRow course) {
		return renderer.cover(mark, course.name(), course.code(), course.room(), course.professor());
	}

	private static void merge(List<Path> sources, Path target) throws IOException {
		PDFMergerUtility merger = new PDFMergerUtility();
		for (Path source : sources) {
			merger.addSource(source.toFile());
		}
		merger.setDestinationFileName(target.toString());
		// 합치는 동안 쓰는 버퍼도 메모리 대신 임시 파일에 둔다
		merger.mergeDocuments(IOUtils.createTempFileOnlyStreamCache());
	}

	private static String fileName(String semester, List<CourseRow> courses) {
		if (courses.size() == 1) {
			CourseRow course = courses.get(0);
			return "CCTV동의서_" + semester + "_" + course.code() + "_" + course.name() + ".pdf";
		}
		return "CCTV동의서_" + semester + "_" + courses.size() + "과목.pdf";
	}

	private static void deleteRecursively(Path directory) {
		if (directory == null || !Files.exists(directory)) {
			return;
		}
		try (Stream<Path> paths = Files.walk(directory)) {
			paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
		}
		catch (IOException ex) {
			// 임시 파일은 OS가 정리하므로 지우지 못해도 다운로드는 실패시키지 않는다
		}
	}

}
